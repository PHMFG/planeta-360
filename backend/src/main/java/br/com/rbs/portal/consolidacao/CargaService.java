package br.com.rbs.portal.consolidacao;

import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.Armazenamento;
import br.com.rbs.portal.comum.ArquivoRecusadoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.TipoDeArquivo;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.consolidacao.CargaDtos.CargaDto;
import br.com.rbs.portal.consolidacao.CargaDtos.RejeicaoDto;
import br.com.rbs.portal.consolidacao.CargaRepositorio.LinhaDePesquisa;
import br.com.rbs.portal.consolidacao.CargaRepositorio.LinhaDeVeiculacao;
import br.com.rbs.portal.consolidacao.LeitorDeCsv.Coluna;
import br.com.rbs.portal.consolidacao.LeitorDeCsv.Linha;
import br.com.rbs.portal.materiais.MateriaisDtos.PaginaDto;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Carga de planilhas CSV da RBS: envio (rota 26), lista de cargas (27) e linhas rejeitadas (28). Atende o item 5 da Entrega 2.
 * [CARGA] [DUPLICIDADE] A API confere o arquivo e o cabeçalho, guarda a planilha como arquivo da RBS, grava as linhas na área
 * de passagem e chama a função do banco, que rejeita o que não serve e insere sem duplicar. [PERMISSAO] Tudo aqui é só da
 * RBS (403 para o patrocinador, antes de qualquer outra conferência). [HISTORICO] Cada carga registra a ação CARGA.
 */
@Service
public class CargaService {

    static final String VEICULACAO = "VEICULACAO";
    static final String PESQUISA = "PESQUISA";
    private static final Set<String> ESCOPOS = Set.of("ATIVACAO", "GERAL");

    /** Linhas gravadas por vez na área de passagem (um lote por ida ao banco). */
    private static final int LOTE = 1000;

    // Os nomes são os do cabeçalho do CSV (os mesmos de stg_veiculacao e stg_pesquisa); o limite é o da coluna no banco.
    private static final List<Coluna> COLUNAS_DA_VEICULACAO = List.of(new Coluna("patrocinador", 120),
            new Coluna("veiculo", 80), new Coluna("formato", 160), new Coluna("espaco_programa", 160),
            new Coluna("exibido_em", 40), new Coluna("quantidade", 40), new Coluna("link", 500));
    private static final List<Coluna> COLUNAS_DA_PESQUISA = List.of(new Coluna("patrocinador", 120),
            new Coluna("pergunta", 40), new Coluna("indicador", 120), new Coluna("alternativa", 120),
            new Coluna("valor", 40), new Coluna("unidade", 12), new Coluna("base_respondentes", 40));

    private final CargaRepositorio repositorio;
    private final Armazenamento armazenamento;
    private final VerificadorDeAcesso verificador;
    private final HistoricoService historico;

    public CargaService(CargaRepositorio repositorio, Armazenamento armazenamento, VerificadorDeAcesso verificador,
                        HistoricoService historico) {
        this.repositorio = repositorio;
        this.armazenamento = armazenamento;
        this.verificador = verificador;
        this.historico = historico;
    }

    // ---------------------------------------------------------------------
    // Rota 26: enviar
    // ---------------------------------------------------------------------

    /**
     * Rota 26. Ordem das respostas: 403 (patrocinador), 422 (parte ausente, tipo, evento ou escopo inválido, ou edição sem
     * a pesquisa pedida), 415 (não é .csv), 413/415/422 do Armazenamento (tamanho, conteúdo que não é texto UTF-8, arquivo
     * vazio) e 422 (cabeçalho sem coluna, texto maior que a coluna, CSV mal formado, planilha sem linhas de dados).
     * Tudo corre numa transação: se qualquer passo falhar, a carga, as linhas da área de passagem, o registro do arquivo e
     * o arquivo em disco não ficam (o Armazenamento apaga o arquivo no rollback).
     */
    @Transactional
    public CargaDto enviar(UsuarioAutenticado usuario, MultipartFile arquivo, String tipoInformado, String eventoIdInformado,
                           String escopoInformado, HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);

        // [VALIDACAO] Junta os erros de campo numa resposta só.
        Map<String, String> campos = new LinkedHashMap<>();
        if (arquivo == null || arquivo.isEmpty()) {
            campos.put("arquivo", "Envie a planilha CSV na parte \"arquivo\" do formulário.");
        }
        String tipo = tipoInformado == null ? "" : tipoInformado.strip();
        if (!VEICULACAO.equals(tipo) && !PESQUISA.equals(tipo)) {
            campos.put("tipo", "Tipo inválido. Use VEICULACAO ou PESQUISA.");
        }
        Long eventoId = numero(eventoIdInformado);
        Integer edicao = null;
        if (eventoId == null) {
            campos.put("eventoId", "Informe a edição do festival (eventoId).");
        } else {
            edicao = repositorio.edicaoDoEvento(eventoId).orElse(null);
            if (edicao == null) {
                campos.put("eventoId", "Esta edição do festival não existe.");
            }
        }
        // O escopo só existe na pesquisa; na veiculação a parte é ignorada. Sem escopo, vale a pesquisa da ativação.
        String escopo = null;
        if (PESQUISA.equals(tipo)) {
            escopo = escopoInformado == null || escopoInformado.isBlank() ? "ATIVACAO" : escopoInformado.strip();
            if (!ESCOPOS.contains(escopo)) {
                campos.put("escopo", "Escopo inválido. Use ATIVACAO ou GERAL.");
            }
        }
        Long pesquisaId = null;
        if (campos.isEmpty() && PESQUISA.equals(tipo)) {
            // A função do banco liga os resultados a uma pesquisa já cadastrada para a edição; não há rota para abri-la.
            pesquisaId = repositorio.pesquisaDaEdicao(eventoId, escopo).orElse(null);
            if (pesquisaId == null) {
                campos.put("escopo", "A edição " + edicao + " ainda não tem a pesquisa de "
                        + ("ATIVACAO".equals(escopo) ? "ativação" : "festival (geral)") + " aberta. Peça à equipe técnica para cadastrá-la.");
            }
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }

        // [UPLOAD] Só CSV. O Armazenamento já aceita outras extensões (pdf, xlsx...), mas aqui elas não servem.
        String nome = arquivo.getOriginalFilename();
        if (TipoDeArquivo.daExtensao(nome).orElse(null) != TipoDeArquivo.CSV) {
            throw new ArquivoRecusadoException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "A carga aceita só planilha em CSV (arquivo .csv, texto UTF-8 separado por vírgulas).");
        }
        byte[] conteudo = bytesDe(arquivo);

        // A planilha fica guardada como arquivo geral da RBS (sem parceria), com autor, data e hash. Confere tamanho e
        // conteúdo (413/415); se algo falhar daqui em diante, o rollback apaga o arquivo do disco.
        var gravado = armazenamento.gravar(conteudo, nome, VEICULACAO.equals(tipo) ? "RELATORIO_VEICULACAO" : "PESQUISA",
                usuario.id(), null);

        long cargaId = repositorio.criar(VEICULACAO.equals(tipo) ? "MIDIA" : "PESQUISA", gravado.id(), eventoId, usuario.id());
        if (VEICULACAO.equals(tipo)) {
            LeitorDeCsv.ler(conteudo, COLUNAS_DA_VEICULACAO, LOTE, lote -> repositorio.gravarVeiculacoes(cargaId,
                    lote.stream().map(CargaService::veiculacao).toList()));
            repositorio.processarVeiculacoes(cargaId, eventoId);
        } else {
            LeitorDeCsv.ler(conteudo, COLUNAS_DA_PESQUISA, LOTE, lote -> repositorio.gravarPesquisa(cargaId,
                    lote.stream().map(CargaService::pesquisa).toList()));
            repositorio.processarPesquisa(cargaId, pesquisaId);
        }

        CargaDto carga = repositorio.buscar(cargaId).orElseThrow();
        // [HISTORICO] Carga é da RBS, sem parceria; o que entrou e o que não entrou fica no detalhe.
        historico.registrar(AcaoHistorico.CARGA, usuario, null, "carga", cargaId, null,
                gravado.nomeOriginal() + " (" + tipo + (escopo == null ? "" : " " + escopo) + ", edição " + edicao + ")",
                carga.linhasLidas() + " lidas, " + carga.linhasInseridas() + " inseridas, " + carga.linhasDuplicadas()
                        + " duplicadas, " + carga.linhasRejeitadas() + " rejeitadas", requisicao);
        return carga;
    }

    // ---------------------------------------------------------------------
    // Rotas 27 e 28: leitura
    // ---------------------------------------------------------------------

    /** Rota 27. A carga mais nova primeiro; filtros opcionais por tipo (VEICULACAO ou PESQUISA) e por edição. */
    @Transactional(readOnly = true)
    public PaginaDto<CargaDto> listar(UsuarioAutenticado usuario, String tipoInformado, Long eventoId, int pagina, int tamanho) {
        verificador.exigirRbs(usuario);
        String tipo = null;
        if (tipoInformado != null && !tipoInformado.isBlank()) {
            tipo = switch (tipoInformado.strip()) {
                case VEICULACAO -> "MIDIA";
                case PESQUISA -> "PESQUISA";
                default -> throw new DadosInvalidosException(Map.of("tipo", "Tipo inválido. Use VEICULACAO ou PESQUISA."));
            };
        }
        long total = repositorio.contar(tipo, eventoId);
        return new PaginaDto<>(repositorio.pagina(tipo, eventoId, pagina, tamanho), pagina, tamanho, total);
    }

    /** Rota 28. As linhas que não entraram, na ordem do arquivo. Carga que não existe: 404 (só a RBS chega até aqui). */
    @Transactional(readOnly = true)
    public PaginaDto<RejeicaoDto> rejeicoes(UsuarioAutenticado usuario, long cargaId, int pagina, int tamanho) {
        verificador.exigirRbs(usuario);
        if (!repositorio.existe(cargaId)) {
            throw new NaoEncontradoException("Não encontramos esta carga.");
        }
        return new PaginaDto<>(repositorio.rejeicoes(cargaId, pagina, tamanho), pagina, tamanho,
                repositorio.contarRejeicoes(cargaId));
    }

    // ---------------------------------------------------------------------
    // Conversão das linhas do CSV
    // ---------------------------------------------------------------------

    // Os valores chegam na ordem de COLUNAS_DA_VEICULACAO. O que não converte (data ou quantidade ilegível) vai nulo e a
    // função do banco rejeita a linha com o campo e o motivo.
    private static LinhaDeVeiculacao veiculacao(Linha l) {
        List<String> v = l.valores();
        return new LinhaDeVeiculacao(l.numero(), v.get(0), v.get(1), v.get(2), v.get(3), Celulas.data(v.get(4)),
                Celulas.decimal(v.get(5), 14), v.get(6));
    }

    // Colunas de stg_pesquisa: pergunta é smallint, valor é numeric(10,2) e a base é integer.
    private static LinhaDePesquisa pesquisa(Linha l) {
        List<String> v = l.valores();
        return new LinhaDePesquisa(l.numero(), v.get(0), Celulas.inteiro(v.get(1), Short.MIN_VALUE, Short.MAX_VALUE), v.get(2),
                v.get(3), Celulas.decimal(v.get(4), 10), Celulas.unidade(v.get(5)),
                Celulas.inteiro(v.get(6), Integer.MIN_VALUE, Integer.MAX_VALUE));
    }

    private static Long numero(String texto) {
        try {
            return texto == null ? null : Long.valueOf(texto.strip());
        } catch (NumberFormatException invalido) {
            return null;
        }
    }

    private static byte[] bytesDe(MultipartFile arquivo) {
        try {
            return arquivo.getBytes();
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }
    }
}
