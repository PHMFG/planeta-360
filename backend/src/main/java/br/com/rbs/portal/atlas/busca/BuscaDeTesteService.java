package br.com.rbs.portal.atlas.busca;

import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.EscopoDaParceria;
import br.com.rbs.portal.atlas.busca.BuscaDeTesteDtos.BuscaDeTesteDto;
import br.com.rbs.portal.atlas.busca.BuscaDeTesteDtos.BuscaDeTesteRequisicao;
import br.com.rbs.portal.atlas.busca.BuscaDeTesteDtos.CandidatoDto;
import br.com.rbs.portal.atlas.busca.BuscaDeTesteDtos.DocumentoVisivelDto;
import br.com.rbs.portal.atlas.busca.BuscaDeTesteDtos.ParceriaDaBuscaDto;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.Candidato;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.Escopo;
import br.com.rbs.portal.atlas.busca.ConsultaAosDocumentos.Consultada;
import br.com.rbs.portal.atlas.limites.LimitesPorUsuario;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * A busca de teste da RBS (rota 61, M4 do plano do Atlas completo). [ATLAS] [PERMISSAO] Só o Admin RBS: a RBS escolhe uma parceria e
 * uma consulta e vê, sem o modelo, o que a busca faria por essa parceria: as duas listas com as posições, o RRF e os documentos que a
 * parceria enxerga. [ISOLAMENTO] Passa pelo mesmo caminho do modelo ({@link ConsultaAosDocumentos} e {@link BuscaHibrida}, como
 * {@code atlas_ro} e com a RLS), de modo que mostra exatamente o que a marca veria: não há atalho que leia documento de outra
 * parceria. Não grava nada (nem histórico: é uma leitura), não chama o modelo de linguagem e gasta, no máximo, um embedding.
 */
@Service
public class BuscaDeTesteService {

    private static final int TAMANHO_DO_INICIO_DO_TEXTO = 240;

    private final VerificadorDeAcesso verificador;
    private final AtlasRepositorio atlas;
    private final ConsultaAosDocumentos consultas;
    private final BuscaHibrida busca;
    private final LimitesPorUsuario limites;

    public BuscaDeTesteService(VerificadorDeAcesso verificador, AtlasRepositorio atlas, ConsultaAosDocumentos consultas,
                               BuscaHibrida busca, LimitesPorUsuario limites) {
        this.verificador = verificador;
        this.atlas = atlas;
        this.consultas = consultas;
        this.busca = busca;
        this.limites = limites;
    }

    /**
     * Rota 61. Ordem das respostas: 403 (não é da RBS, antes do corpo), 400 (corpo ausente ou quebrado) e 422 (campos). Parceria que
     * não existe é 422 em {@code campos.parceriaId}, como no envio de documento. Sem termo útil na consulta, a resposta é 200 com as
     * listas vazias e {@code semTermoUtil} verdadeiro. [M8] Cada chamada pode gastar um embedding do Gemini, então o usuário tem um
     * limite de buscas por hora ({@code atlas.limites.buscas-de-teste-por-hora}): 429 com quanto falta, depois dos 403, 400 e 422 (que
     * não gastam ficha) e antes do embedding.
     */
    public BuscaDeTesteDto buscar(UsuarioAutenticado usuario, BuscaDeTesteRequisicao corpo) {
        verificador.exigirRbs(usuario);
        Map<String, String> campos = new LinkedHashMap<>();
        EscopoDaParceria escopoDaParceria = null;
        if (corpo.parceriaId() == null) {
            campos.put("parceriaId", "Informe a parceria (parceriaId).");
        } else {
            escopoDaParceria = atlas.escopoDaParceriaSeExistir(corpo.parceriaId()).orElse(null);
            if (escopoDaParceria == null) {
                campos.put("parceriaId", "Esta parceria não existe.");
            }
        }
        String consulta = corpo.consulta() == null || corpo.consulta().isBlank() ? null : corpo.consulta().strip();
        if (consulta == null) {
            campos.put("consulta", "Escreva a consulta.");
        } else if (consulta.length() > ConsultaAosDocumentos.LIMITE_DA_CONSULTA) {
            campos.put("consulta", "A consulta tem no máximo " + ConsultaAosDocumentos.LIMITE_DA_CONSULTA + " caracteres.");
        } else if (consulta.indexOf('\u0000') >= 0) {
            campos.put("consulta", "A consulta tem caracteres inválidos.");
        }
        String tipo = corpo.tipo() == null || corpo.tipo().isBlank() ? null : corpo.tipo().strip();
        if (tipo != null && !ConsultaAosDocumentos.TIPOS.contains(tipo)) {
            campos.put("tipo", "Tipo inválido. Use " + String.join(", ", ConsultaAosDocumentos.TIPOS.stream().sorted().toList())
                    + ", ou não informe o tipo.");
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }

        limites.consumirBuscaDeTeste(usuario.id());   // 429: o limite por hora, antes do embedding
        Escopo escopo = new Escopo(escopoDaParceria.eventoId(), escopoDaParceria.cotaTipoId(), corpo.parceriaId());
        Consultada consultada = consultas.consultar(escopo, consulta, tipo);
        var resultado = consultada.resultado();
        List<DocumentoVisivelDto> visiveis = busca.documentosVisiveis(escopo).stream()
                .map(d -> new DocumentoVisivelDto(d.id(), d.titulo(), d.versao(), d.tipo(), d.escopo(), d.trechos())).toList();
        return new BuscaDeTesteDto(
                new ParceriaDaBuscaDto(corpo.parceriaId(), atlas.nomeDaMarca(corpo.parceriaId()), escopoDaParceria.edicao(),
                        escopoDaParceria.cotaCodigo()),
                consulta, tipo, resultado.k(), resultado.degradado(), consultada.motivoDoDegradado(), consultada.erroDoEmbedding(),
                resultado.semTermoUtil(), consultada.embeddingMs(), resultado.sqlMs(), resultado.trechosDeOutroModelo(),
                candidatos(resultado.vetorial()), candidatos(resultado.textual()), candidatos(resultado.escolhidos()), visiveis);
    }

    private static List<CandidatoDto> candidatos(List<Candidato> lista) {
        return lista.stream().map(c -> new CandidatoDto(c.trechoId(), c.documentoId(), c.documento(), c.versao(), c.tipo(), c.pagina(),
                c.secao(), c.posicaoVetorial(), c.distancia(), c.posicaoTextual(), c.nota(), c.rrf(),
                c.texto().length() > TAMANHO_DO_INICIO_DO_TEXTO ? c.texto().substring(0, TAMANHO_DO_INICIO_DO_TEXTO) : c.texto())).toList();
    }
}
