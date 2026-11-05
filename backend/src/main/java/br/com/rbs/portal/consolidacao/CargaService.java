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
import br.com.rbs.portal.consolidacao.CargaRepositorio.LinhaDePlano;
import br.com.rbs.portal.consolidacao.CargaRepositorio.LinhaDeVeiculacao;
import br.com.rbs.portal.consolidacao.LeitorDeCsv.Coluna;
import br.com.rbs.portal.consolidacao.LeitorDeCsv.Linha;
import br.com.rbs.portal.materiais.MateriaisDtos.PaginaDto;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
 * Três tipos: veiculação (mídia exibida), pesquisa (resultados agregados) e plano (o plano comercial, que enche o previsto de
 * mídia e de aplicações de marca). [CARGA] [DUPLICIDADE] A API confere o arquivo e o cabeçalho, guarda a planilha como arquivo
 * da RBS, grava as linhas na área de passagem e chama a função do banco, que rejeita o que não serve e insere sem duplicar.
 * [PERMISSAO] Tudo aqui é só da RBS (403 para o patrocinador, antes de qualquer outra conferência). [HISTORICO] Cada carga
 * registra a ação CARGA.
 */
@Service
public class CargaService {

    static final String VEICULACAO = "VEICULACAO";
    static final String PESQUISA = "PESQUISA";
    static final String PLANO = "PLANO";
    private static final Set<String> ESCOPOS = Set.of("ATIVACAO", "GERAL");

    static final String FRASE_DE_TIPO_INVALIDO = "Tipo inválido. Use VEICULACAO, PESQUISA ou PLANO.";

    /** Linhas gravadas por vez na área de passagem (um lote por ida ao banco). */
    private static final int LOTE = 1000;

    // Os nomes são os do cabeçalho do CSV (os mesmos de stg_veiculacao e stg_pesquisa); o limite é o da coluna no banco.
    private static final List<Coluna> COLUNAS_DA_VEICULACAO = List.of(new Coluna("patrocinador", 120),
            new Coluna("veiculo", 80), new Coluna("formato", 160), new Coluna("espaco_programa", 160),
            new Coluna("exibido_em", 40), new Coluna("quantidade", 40), new Coluna("link", 500));
    private static final List<Coluna> COLUNAS_DA_PESQUISA = List.of(new Coluna("patrocinador", 120),
            new Coluna("pergunta", 40), new Coluna("indicador", 120), new Coluna("alternativa", 120),
            new Coluna("valor", 40), new Coluna("unidade", 12), new Coluna("base_respondentes", 40));
    // O plano comercial (os nomes são os de stg_plano e de aproveitamento_previsto; o limite é o da coluna do banco). A quantidade
    // e a página têm 40: são lidas como número, e o limite só protege contra texto absurdo (a coluna do texto original é de 40).
    private static final List<Coluna> COLUNAS_DO_PLANO = List.of(new Coluna("patrocinador", 120), new Coluna("categoria", 20),
            new Coluna("bloco", 80), new Coluna("veiculo", 80), new Coluna("formato", 160), new Coluna("espaco_programa", 160),
            new Coluna("janela", 80), new Coluna("tipo_midia", 20), new Coluna("quantidade_prevista", 40),
            new Coluna("unidade", 15), new Coluna("fonte_pagina", 40));

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
     * Rota 26. Ordem das respostas: 403 (patrocinador), 422 (parte ausente, planilha de 0 bytes, tipo, evento ou escopo
     * inválido, ou edição sem a pesquisa pedida; o escopo só vale na pesquisa), 415 (não é .csv), 413/415 do Armazenamento (tamanho; conteúdo que não é
     * texto UTF-8, com a instrução de salvar como CSV UTF-8) e 422 (cabeçalho sem coluna, texto maior que a coluna, CSV mal
     * formado, planilha sem linhas de dados).
     * Tudo corre numa transação: se qualquer passo falhar, a carga, as linhas da área de passagem, o registro do arquivo e
     * o arquivo em disco não ficam (o Armazenamento apaga o arquivo no rollback).
     */
    @Transactional
    public CargaDto enviar(UsuarioAutenticado usuario, MultipartFile arquivo, String tipoInformado, String eventoIdInformado,
                           String escopoInformado, HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);

        // [VALIDACAO] Junta os erros de campo numa resposta só.
        Map<String, String> campos = new LinkedHashMap<>();
        if (arquivo == null || (arquivo.isEmpty() && vazio(arquivo.getOriginalFilename()))) {
            campos.put("arquivo", "Envie a planilha CSV na parte \"arquivo\" do formulário.");
        } else if (arquivo.isEmpty()) {
            // Chegou um arquivo com nome, mas sem nenhum byte: o problema é a planilha, e não o formulário.
            campos.put("arquivo", "A planilha está vazia.");
        }
        String tipo = tipoInformado == null ? "" : tipoInformado.strip();
        if (!VEICULACAO.equals(tipo) && !PESQUISA.equals(tipo) && !PLANO.equals(tipo)) {
            campos.put("tipo", FRASE_DE_TIPO_INVALIDO);
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
        // O escopo só existe na pesquisa; na veiculação e no plano a parte é ignorada. Sem escopo, vale a pesquisa da ativação.
        String escopo = null;
        if (PESQUISA.equals(tipo)) {
            escopo = escopoInformado == null || escopoInformado.isBlank() ? "ATIVACAO" : escopoInformado.strip();
            if (!ESCOPOS.contains(escopo)) {
                campos.put("escopo", "Escopo inválido. Use ATIVACAO ou GERAL.");
            }
        }
        Long pesquisaId = null;
        if (campos.isEmpty() && PESQUISA.equals(tipo)) {
            // A função do banco liga os resultados a uma pesquisa já aberta para a edição (a RBS a abre pela rota 93).
            pesquisaId = repositorio.pesquisaDaEdicao(eventoId, escopo).orElse(null);
            if (pesquisaId == null) {
                campos.put("escopo", "A edição " + edicao + " ainda não tem a pesquisa de "
                        + ("ATIVACAO".equals(escopo) ? "ativação" : "festival (geral)") + " aberta. Abra-a em Pesquisa, no menu da RBS.");
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
        // [CARGA] No banco a veiculação é MIDIA e o plano é PLANO_COMERCIAL (o CHECK de carga.tipo); a categoria do arquivo
        // acompanha o assunto da planilha (o plano guarda o CSV como PLANO_COMERCIAL, categoria que o CHECK de arquivo já tem).
        var gravado = armazenamento.gravar(conteudo, nome, switch (tipo) {
            case VEICULACAO -> "RELATORIO_VEICULACAO";
            case PLANO -> "PLANO_COMERCIAL";
            default -> "PESQUISA";
        }, usuario.id(), null);

        long cargaId = repositorio.criar(switch (tipo) {
            case VEICULACAO -> "MIDIA";
            case PLANO -> "PLANO_COMERCIAL";
            default -> "PESQUISA";
        }, gravado.id(), eventoId, usuario.id());
        switch (tipo) {
            case VEICULACAO -> {
                LeitorDeCsv.ler(conteudo, COLUNAS_DA_VEICULACAO, LOTE, lote -> repositorio.gravarVeiculacoes(cargaId,
                        lote.stream().map(CargaService::veiculacao).toList()));
                repositorio.processarVeiculacoes(cargaId, eventoId);
            }
            case PLANO -> {
                LeitorDeCsv.ler(conteudo, COLUNAS_DO_PLANO, LOTE, lote -> repositorio.gravarPlano(cargaId,
                        lote.stream().map(CargaService::plano).toList()));
                repositorio.processarPlano(cargaId, eventoId);
            }
            default -> {
                LeitorDeCsv.ler(conteudo, COLUNAS_DA_PESQUISA, LOTE, lote -> repositorio.gravarPesquisa(cargaId,
                        lote.stream().map(CargaService::pesquisa).toList()));
                repositorio.processarPesquisa(cargaId, pesquisaId);
            }
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

    /** Rota 27. A carga mais nova primeiro; filtros opcionais por tipo (VEICULACAO, PESQUISA ou PLANO) e por edição. */
    @Transactional(readOnly = true)
    public PaginaDto<CargaDto> listar(UsuarioAutenticado usuario, String tipoInformado, Long eventoId, int pagina, int tamanho) {
        verificador.exigirRbs(usuario);
        String tipo = null;
        if (tipoInformado != null && !tipoInformado.isBlank()) {
            tipo = switch (tipoInformado.strip()) {
                case VEICULACAO -> "MIDIA";
                case PESQUISA -> "PESQUISA";
                case PLANO -> "PLANO_COMERCIAL";
                default -> throw new DadosInvalidosException(Map.of("tipo", FRASE_DE_TIPO_INVALIDO));
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
    // função do banco rejeita a linha com o campo e o motivo. O texto original do que não converteu vai junto (e só nesse
    // caso), para a função distinguir "ilegível" de "vazio" e dizer qual é o formato esperado.
    private static LinhaDeVeiculacao veiculacao(Linha l) {
        List<String> v = l.valores();
        OffsetDateTime exibidoEm = Celulas.data(v.get(4));
        BigDecimal quantidade = Celulas.decimal(v.get(5), 14);
        return new LinhaDeVeiculacao(l.numero(), v.get(0), v.get(1), v.get(2), v.get(3), exibidoEm, quantidade, v.get(6),
                exibidoEm == null ? v.get(4) : null, quantidade == null ? v.get(5) : null);
    }

    // Os valores chegam na ordem de COLUNAS_DO_PLANO. Categoria, tipo e unidade são códigos de lista fechada: valem em maiúsculas
    // ("midia" vale como MIDIA) e a função do banco confere se são um dos aceitos. A quantidade que não converte vai nula, com o
    // texto original ao lado, e a função rejeita a linha dizendo o formato; a página (opcional) fora de 1 a 32767 vai nula.
    private static LinhaDePlano plano(Linha l) {
        List<String> v = l.valores();
        BigDecimal quantidade = Celulas.decimal(v.get(8), 14);
        Integer pagina = Celulas.inteiro(v.get(10), 1, Short.MAX_VALUE);
        return new LinhaDePlano(l.numero(), v.get(0), Celulas.codigo(v.get(1)), v.get(2), v.get(3), v.get(4), v.get(5), v.get(6),
                Celulas.codigo(v.get(7)), quantidade, quantidade == null ? v.get(8) : null, Celulas.codigo(v.get(9)),
                pagina == null ? null : pagina.shortValue());
    }

    // Colunas de stg_pesquisa: pergunta é smallint, valor é numeric(10,2) e a base é integer.
    private static LinhaDePesquisa pesquisa(Linha l) {
        List<String> v = l.valores();
        Integer pergunta = Celulas.inteiro(v.get(1), Short.MIN_VALUE, Short.MAX_VALUE);
        BigDecimal valor = Celulas.decimal(v.get(4), 10);
        Integer base = Celulas.inteiro(v.get(6), Integer.MIN_VALUE, Integer.MAX_VALUE);
        return new LinhaDePesquisa(l.numero(), v.get(0), pergunta, v.get(2), v.get(3), valor, Celulas.unidade(v.get(5)), base,
                pergunta == null ? v.get(1) : null, valor == null ? v.get(4) : null, base == null ? v.get(6) : null);
    }

    private static boolean vazio(String texto) {
        return texto == null || texto.isBlank();
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
