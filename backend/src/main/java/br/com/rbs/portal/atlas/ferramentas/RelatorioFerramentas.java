package br.com.rbs.portal.atlas.ferramentas;

import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeRelatorio;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.DefinicaoDeFerramenta;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.CartaoAberto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.OperacaoDeTexto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.RelatorioFinalizado;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.ReferenciaDesconhecidaException;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.SemPermissaoException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;

/**
 * As ferramentas {@code relatorio_*} que o modelo de linguagem chama (plano, seção 10.2): {@code relatorio_iniciar},
 * {@code relatorio_solicitar_campos}, {@code relatorio_editar} (só {@code definir_texto}) e {@code relatorio_finalizar}.
 * [ATLAS] [ISOLAMENTO] São um embrulho fino do {@link RelatorioAtlasService}, o mesmo que o plano B usa. Três regras:
 * <ul>
 *   <li><b>Nenhuma recebe parceria.</b> A parceria, o usuário e a conversa vêm do {@link AtlasContexto} (sessão e
 *       VerificadorDeAcesso). O relatório sobre o qual a ferramenta age é o que está em montagem NESTA conversa. Argumento que
 *       não está no esquema (um {@code parceria_id}, por exemplo) é recusado, e o resultado não muda de parceria nunca.</li>
 *   <li><b>A validação dos argumentos é nossa</b>, em Java (o modo {@code strict} dos provedores é beta e não vale aqui): tipo,
 *       tamanho e argumento desconhecido. O JSON Schema que vai ao modelo só o orienta.</li>
 *   <li><b>O servidor oferece só as ferramentas do estado atual</b> (seção 10.1): sem relatório, só {@code relatorio_iniciar};
 *       em montagem, as outras três; com o cartão aberto, nenhuma.</li>
 * </ul>
 * Erro de ferramenta não é exceção para quem chama o modelo: {@link #erroComoJson} o transforma na mensagem de ferramenta que
 * volta ao modelo, e o loop nunca devolve 500 por causa dele.
 */
@Component
public class RelatorioFerramentas {

    public static final String INICIAR = "relatorio_iniciar";
    public static final String SOLICITAR_CAMPOS = "relatorio_solicitar_campos";
    public static final String EDITAR = "relatorio_editar";
    public static final String FINALIZAR = "relatorio_finalizar";

    /** O que uma ferramenta fez: o JSON do resultado; {@code pausa} se abriu o cartão (sem resultado até a pessoa responder). */
    public record Execucao(String resultadoJson, boolean pausa, boolean pronto) {

        static Execucao resultado(String json) {
            return new Execucao(json, false, false);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(RelatorioFerramentas.class);

    private static final DefinicaoDeFerramenta DEFINICAO_INICIAR = new DefinicaoDeFerramenta(INICIAR,
            "Começa o relatório de Ativação no evento desta parceria a partir do modelo pronto. Devolve as seções, os dados que "
                    + "já existem (cada um com a referência ref, que você usa nos textos como marcador {{ref}}), os blocos que você "
                    + "escreve e as lacunas: números que só a marca sabe. Chame uma vez por relatório.",
            """
            {"type":"object","properties":{
              "modelo":{"type":"string","enum":["ATIVACAO_EVENTO"],"description":"O modelo do relatório."},
              "objetivo":{"type":"string","maxLength":300,"description":"O que a pessoa quer, em uma frase curta e sem números."}},
             "required":["modelo"],"additionalProperties":false}""");

    private static final DefinicaoDeFerramenta DEFINICAO_SOLICITAR = new DefinicaoDeFerramenta(SOLICITAR_CAMPOS,
            "Abre um cartão de formulário para a pessoa informar números que o portal não tem. Peça só campos da lista de lacunas, "
                    + "todos de uma vez, e no máximo três cartões por relatório. A conversa para até a pessoa responder; a resposta "
                    + "volta como { acao, campos }, com acao accept, decline ou cancel.",
            """
            {"type":"object","properties":{
              "mensagem":{"type":"string","maxLength":400,"description":"O que você diz à pessoa no cartão, em português e sem números."},
              "campos":{"type":"array","minItems":1,"maxItems":8,"items":{"type":"string"},
                        "description":"As chaves das lacunas (campo chave), por exemplo ativacao.visitantes_total."}},
             "required":["mensagem","campos"],"additionalProperties":false}""");

    private static final DefinicaoDeFerramenta DEFINICAO_EDITAR = new DefinicaoDeFerramenta(EDITAR,
            "Escreve o texto dos parágrafos do relatório. Cada operação definir_texto leva o id de um bloco redigível e o texto: no "
                    + "máximo três frases e 600 caracteres, sem HTML, link ou URL, e sem nenhum algarismo nem número por extenso. "
                    + "Onde o texto precisar de um dado, escreva o marcador {{ref}} com uma referência que as ferramentas "
                    + "devolveram (por exemplo {{dado:ativacao.visitantes_total}}); o servidor o troca pelo valor. Devolve o "
                    + "resultado de cada operação e os blocos que ainda estão sem texto.",
            """
            {"type":"object","properties":{
              "operacoes":{"type":"array","minItems":1,"maxItems":10,"items":{"type":"object","properties":{
                "op":{"type":"string","enum":["definir_texto"]},
                "bloco":{"type":"string","description":"O id de um bloco redigível, como veio em relatorio_iniciar."},
                "texto":{"type":"string","maxLength":600,"description":"O texto, só com marcadores no lugar dos dados."}},
               "required":["op","bloco","texto"],"additionalProperties":false}}},
             "required":["operacoes"],"additionalProperties":false}""");

    private static final DefinicaoDeFerramenta DEFINICAO_FINALIZAR = new DefinicaoDeFerramenta(FINALIZAR,
            "Fecha o relatório quando todo bloco redigível tem texto e as lacunas obrigatórias foram respondidas ou recusadas no "
                    + "cartão. Depois dela o relatório está pronto e não muda mais.",
            """
            {"type":"object","properties":{},"additionalProperties":false}""");

    private static final Set<String> ARGUMENTOS_DE_INICIAR = Set.of("modelo", "objetivo");
    private static final Set<String> ARGUMENTOS_DE_SOLICITAR = Set.of("mensagem", "campos");
    private static final Set<String> ARGUMENTOS_DE_EDITAR = Set.of("operacoes");
    private static final Set<String> ARGUMENTOS_DA_OPERACAO = Set.of("op", "bloco", "texto");
    private static final String OPERACAO_DEFINIR_TEXTO = "definir_texto";

    private final RelatorioAtlasService relatorios;
    private final AtlasRepositorio repositorio;

    public RelatorioFerramentas(RelatorioAtlasService relatorios, AtlasRepositorio repositorio) {
        this.relatorios = relatorios;
        this.repositorio = repositorio;
    }

    // ---------------------------------------------------------------------
    // O que é oferecido ao modelo
    // ---------------------------------------------------------------------

    /**
     * As ferramentas do estado atual (plano, seção 10.1). {@code ativo} é o relatório em montagem da conversa, se houver;
     * {@code podePedirCartao} é falso quando ele já usou os três cartões que o modelo pode pedir.
     */
    public List<DefinicaoDeFerramenta> oferecidas(Optional<LinhaDeRelatorio> ativo, boolean podePedirCartao) {
        if (ativo.isEmpty()) {
            return List.of(DEFINICAO_INICIAR);
        }
        if (!"MONTANDO".equals(ativo.get().status())) {
            return List.of();   // AGUARDANDO_RESPOSTA: nenhuma ferramenta do relatório roda até o cartão voltar
        }
        List<DefinicaoDeFerramenta> ferramentas = new ArrayList<>();
        if (podePedirCartao) {
            ferramentas.add(DEFINICAO_SOLICITAR);
        }
        ferramentas.add(DEFINICAO_EDITAR);
        ferramentas.add(DEFINICAO_FINALIZAR);
        return ferramentas;
    }

    // ---------------------------------------------------------------------
    // Executar
    // ---------------------------------------------------------------------

    /**
     * Executa o pedido do modelo no contexto da conversa. {@code chamadaId} é o id do pedido (liga o cartão à resposta que vai
     * chegar depois). Qualquer problema sobe como exceção, que o loop transforma em {@link #erroComoJson}: 422 (argumentos),
     * 409 (o relatório não está nesse estado), 403/404 (acesso).
     */
    public Execucao executar(AtlasContexto contexto, String nome, String argumentosJson, String chamadaId) {
        Map<String, Object> argumentos = lerArgumentos(argumentosJson);
        Optional<LinhaDeRelatorio> ativo = repositorio.relatorioAtivoDaConversa(contexto.conversaId());
        return switch (nome) {
            case INICIAR -> iniciar(contexto, argumentos);
            case SOLICITAR_CAMPOS -> solicitarCampos(contexto, argumentos, exigirMontando(ativo), chamadaId);
            case EDITAR -> editar(contexto, argumentos, exigirMontando(ativo));
            case FINALIZAR -> finalizar(contexto, argumentos, exigirMontando(ativo));
            default -> throw new DadosInvalidosException("Não existe a ferramenta " + nome + ". Use só as ferramentas oferecidas.",
                    Map.of());
        };
    }

    private Execucao iniciar(AtlasContexto contexto, Map<String, Object> argumentos) {
        Map<String, String> erros = new LinkedHashMap<>();
        desconhecidos(argumentos, ARGUMENTOS_DE_INICIAR, erros);
        String modelo = texto(argumentos, "modelo", true, erros);
        String objetivo = texto(argumentos, "objetivo", false, erros);
        exigirSemErros(erros);
        return Execucao.resultado(AtlasJson.escrever(relatorios.iniciar(contexto, modelo, objetivo)));
    }

    private Execucao solicitarCampos(AtlasContexto contexto, Map<String, Object> argumentos, LinhaDeRelatorio relatorio, String chamadaId) {
        Map<String, String> erros = new LinkedHashMap<>();
        desconhecidos(argumentos, ARGUMENTOS_DE_SOLICITAR, erros);
        String mensagem = texto(argumentos, "mensagem", true, erros);
        List<String> campos = lista(argumentos, "campos", erros);
        exigirSemErros(erros);
        CartaoAberto cartao = relatorios.solicitarCampos(contexto, relatorio.id(), mensagem, campos, chamadaId);
        log.debug("Atlas: cartão {} aberto na conversa {}", cartao.solicitacaoId(), contexto.conversaId());
        return new Execucao(null, true, false);
    }

    private Execucao editar(AtlasContexto contexto, Map<String, Object> argumentos, LinhaDeRelatorio relatorio) {
        Map<String, String> erros = new LinkedHashMap<>();
        desconhecidos(argumentos, ARGUMENTOS_DE_EDITAR, erros);
        List<OperacaoDeTexto> operacoes = new ArrayList<>();
        Object bruto = argumentos.get("operacoes");
        if (!(bruto instanceof List<?> itens) || itens.isEmpty() || itens.size() > RelatorioAtlasService.MAXIMO_DE_OPERACOES_POR_EDICAO) {
            erros.put("operacoes", "Envie de 1 a " + RelatorioAtlasService.MAXIMO_DE_OPERACOES_POR_EDICAO + " operações.");
        } else {
            for (int i = 0; i < itens.size(); i++) {
                if (!(itens.get(i) instanceof Map<?, ?> operacao)) {
                    erros.put("operacoes[" + i + "]", "A operação tem de ser um objeto com op, bloco e texto.");
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> campos = (Map<String, Object>) operacao;
                String prefixo = "operacoes[" + i + "].";
                Map<String, String> errosDaOperacao = new LinkedHashMap<>();
                desconhecidos(campos, ARGUMENTOS_DA_OPERACAO, errosDaOperacao);
                String op = texto(campos, "op", true, errosDaOperacao);
                if (op != null && !OPERACAO_DEFINIR_TEXTO.equals(op)) {
                    errosDaOperacao.put("op", "Só existe a operação " + OPERACAO_DEFINIR_TEXTO + ".");
                }
                String bloco = texto(campos, "bloco", true, errosDaOperacao);
                String texto = texto(campos, "texto", true, errosDaOperacao);
                errosDaOperacao.forEach((campo, motivo) -> erros.put(prefixo + campo, motivo));
                if (errosDaOperacao.isEmpty()) {
                    operacoes.add(new OperacaoDeTexto(bloco, texto));
                }
            }
        }
        exigirSemErros(erros);
        return Execucao.resultado(AtlasJson.escrever(relatorios.definirTextos(contexto, relatorio.id(), operacoes)));
    }

    private Execucao finalizar(AtlasContexto contexto, Map<String, Object> argumentos, LinhaDeRelatorio relatorio) {
        Map<String, String> erros = new LinkedHashMap<>();
        desconhecidos(argumentos, Set.of(), erros);
        exigirSemErros(erros);
        RelatorioFinalizado pronto = relatorios.finalizar(contexto, relatorio.id());
        return new Execucao(pronto.resultadoJson(), false, true);
    }

    // ---------------------------------------------------------------------
    // Estado
    // ---------------------------------------------------------------------

    private static LinhaDeRelatorio exigirMontando(Optional<LinhaDeRelatorio> ativo) {
        LinhaDeRelatorio relatorio = ativo.orElseThrow(() -> new ConflitoException(
                "Ainda não há relatório sendo montado nesta conversa. Comece com " + INICIAR + "."));
        if (!"MONTANDO".equals(relatorio.status())) {
            throw new ConflitoException(AGUARDANDO_O_CARTAO);
        }
        return relatorio;
    }

    /** A frase de erro de toda ferramenta chamada com o cartão aberto. */
    public static final String AGUARDANDO_O_CARTAO = "Aguardando a resposta do cartão. Nenhuma ferramenta do relatório roda até ela voltar.";

    // ---------------------------------------------------------------------
    // Validação dos argumentos (nossa, em Java)
    // ---------------------------------------------------------------------

    private static Map<String, Object> lerArgumentos(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> argumentos = AtlasJson.ler(json, new TypeReference<Map<String, Object>>() {
            });
            return argumentos == null ? new LinkedHashMap<>() : argumentos;
        } catch (JacksonException | ClassCastException formatoInvalido) {
            throw new DadosInvalidosException("Os argumentos da ferramenta não são um objeto JSON válido.", Map.of());
        }
    }

    private static void desconhecidos(Map<String, Object> argumentos, Set<String> aceitos, Map<String, String> erros) {
        for (String nome : argumentos.keySet()) {
            if (!aceitos.contains(nome)) {
                erros.put(nome, "Argumento desconhecido: esta ferramenta não aceita " + nome + ".");
            }
        }
    }

    private static String texto(Map<String, Object> argumentos, String nome, boolean obrigatorio, Map<String, String> erros) {
        Object valor = argumentos.get(nome);
        if (valor == null) {
            if (obrigatorio) {
                erros.put(nome, "Informe " + nome + ".");
            }
            return null;
        }
        if (!(valor instanceof String texto)) {
            erros.put(nome, nome + " tem de ser um texto.");
            return null;
        }
        if (obrigatorio && texto.isBlank()) {
            erros.put(nome, "Informe " + nome + ".");
            return null;
        }
        return texto;
    }

    private static List<String> lista(Map<String, Object> argumentos, String nome, Map<String, String> erros) {
        Object valor = argumentos.get(nome);
        if (!(valor instanceof List<?> itens)) {
            erros.put(nome, nome + " tem de ser uma lista de textos.");
            return List.of();
        }
        List<String> textos = new ArrayList<>();
        for (Object item : itens) {
            if (item instanceof String texto) {
                textos.add(texto);
            } else {
                erros.put(nome, nome + " tem de ser uma lista de textos.");
                return List.of();
            }
        }
        return textos;
    }

    private static void exigirSemErros(Map<String, String> erros) {
        if (!erros.isEmpty()) {
            throw new DadosInvalidosException("Os argumentos da ferramenta estão errados.", erros);
        }
    }

    // ---------------------------------------------------------------------
    // Erro de ferramenta volta ao modelo
    // ---------------------------------------------------------------------

    /**
     * O erro como mensagem de ferramenta ({@code {"erro": "...", "campos": {...}}}), no lugar de uma resposta HTTP de erro. [ATLAS]
     * Só sai a frase que o portal já mostraria à pessoa; erro inesperado vira uma frase genérica (o rastro fica no log).
     */
    public static String erroComoJson(RuntimeException erro) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        if (erro instanceof DadosInvalidosException invalidos) {
            corpo.put("erro", invalidos.getMessage());
            if (!invalidos.campos().isEmpty()) {
                corpo.put("campos", invalidos.campos());
            }
        } else if (erro instanceof ConflitoException || erro instanceof NaoEncontradoException || erro instanceof SemPermissaoException
                || erro instanceof ReferenciaDesconhecidaException) {
            corpo.put("erro", erro.getMessage());
        } else {
            log.error("Atlas: uma ferramenta falhou por um motivo inesperado", erro);
            corpo.put("erro", "A ferramenta falhou por um motivo interno. Tente de novo; se continuar, diga à pessoa para usar o botão "
                    + "Montar relatório.");
        }
        return AtlasJson.escrever(corpo);
    }

    /** O erro que o próprio loop devolve (pausa do cartão, limite de chamadas), no mesmo formato. */
    public static String erroComoJson(String frase) {
        return AtlasJson.escrever(Map.of("erro", frase));
    }
}
