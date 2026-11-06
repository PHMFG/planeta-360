package br.com.rbs.portal.atlas.provedor;

import com.openai.core.JsonValue;
import com.openai.models.completions.CompletionUsage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.util.MimeType;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * O {@link ProvedorDeLlm} que fala com o {@code ChatModel} do Spring AI (módulo da OpenAI, apontado para o OpenCode Go). [ATLAS]
 * Usa o {@code ChatModel} direto, sem {@code ChatClient} nem advisor: no Spring AI 2.0 uma chamada direta ao modelo NUNCA executa
 * ferramenta (devolve os {@code tool_calls}), e é isso que o loop do agente quer, porque ele executa as chamadas uma a uma e para
 * no cartão. As ferramentas vão ao modelo só como definição (nome, descrição e JSON Schema); quem as executa é o loop.
 * <ul>
 *   <li>O modelo e a temperatura vêm das opções padrão do modelo (application.yml); a cada chamada acrescentam-se as ferramentas,
 *       o cabeçalho {@code x-opencode-session} com o id da conversa e o {@code thinking} ({@code {"type":"enabled"}} ou
 *       {@code "disabled"}, pelo {@code atlas.raciocinio}), no topo do corpo.</li>
 *   <li>O {@code reasoning_content} que o modelo mandar volta na resposta, para o loop guardá-lo; e o que o loop remonta do
 *       banco entra nos metadados da mensagem do assistente (chave {@code reasoningContent}), que o Spring AI reenvia sozinho.
 *       Com o raciocínio ligado e ferramentas, o DeepSeek responde 400 se ele não voltar em todas as idas seguintes.</li>
 *   <li>Em fluxo ({@link #responderEmFluxo}), usa o {@code stream()} do mesmo modelo: o Spring AI 2.0.2 entrega o texto em
 *       pedaços, o {@code reasoning_content} acumulado nos metadados de cada pedaço (daqui sai só o que cresceu) e os pedidos de
 *       ferramenta já juntados, num pedaço só, quando chega o {@code finish_reason} deles.</li>
 *   <li>Com o raciocínio desligado ({@code atlas.raciocinio=false}) o modelo não raciocina, e este provedor também não repassa
 *       nenhum {@code reasoning_content} que chegue mesmo assim: sem raciocínio, não há o que gravar, transmitir nem resumir.</li>
 *   <li>{@link #resumir} é a chamada à parte do resumo do raciocínio: o mesmo {@code ChatModel}, sem ferramentas, com
 *       {@code "thinking": {"type": "disabled"}}, no máximo {@value #MAXIMO_DE_TOKENS_DO_RESUMO} tokens de saída e temperatura
 *       baixa. Se falha, nem o log nem a exceção levam o texto do erro do provedor (só o nome da falha), para o raciocínio, que é
 *       o que se manda nessa chamada, nunca parar em log.</li>
 *   <li>O {@code User-Agent} próprio é posto pelo {@link LlmConfig}: o {@code custom-headers} do Spring AI não troca o
 *       "spring-ai-openai" que ele manda por padrão, só acrescenta um segundo valor ao cabeçalho.</li>
 * </ul>
 * Qualquer falha vira {@link ProvedorIndisponivelException}, com mensagem sem chave nem cabeçalho (o corpo do erro de um provedor
 * pode devolver a chave que recebeu, por isso a chave configurada é riscada da mensagem).
 */
public class ProvedorSpringAi implements ProvedorDeLlm {

    /** O cabeçalho que o OpenCode Go pede, estável por conversa. */
    public static final String CABECALHO_DA_SESSAO = "x-opencode-session";
    /** A chave dos metadados da mensagem do assistente onde o Spring AI guarda o raciocínio (e de onde o reenvia). */
    static final String RACIOCINIO_NOS_METADADOS = "reasoningContent";

    private static final Logger log = LoggerFactory.getLogger(ProvedorSpringAi.class);
    private static final Pattern SEGREDO = Pattern.compile("(?i)(sk-[A-Za-z0-9_-]{6,}|bearer\\s+\\S+|api[_-]?key\\S*)");

    /** O campo do corpo que liga ou desliga o raciocínio no DeepSeek (vai no topo do JSON, aninhado). */
    static final String THINKING = "thinking";
    /** O teto de tokens de saída do resumo do raciocínio: uma ou duas frases curtas cabem folgadas. */
    public static final int MAXIMO_DE_TOKENS_DO_RESUMO = 200;
    /** O resumo tem de ser fiel ao raciocínio, não criativo. */
    public static final double TEMPERATURA_DO_RESUMO = 0.1;

    private final ChatModel modelo;
    private final String chave;
    private final boolean raciocinio;

    /**
     * {@code chave} é a chave de API configurada, só para riscá-la de mensagens de erro e de log (pode ser nula).
     * {@code raciocinio} liga o {@code thinking} do modelo ({@code atlas.raciocinio}).
     */
    public ProvedorSpringAi(ChatModel modelo, String chave, boolean raciocinio) {
        if (!(modelo.getOptions() instanceof OpenAiChatOptions)) {
            throw new IllegalStateException("O Atlas espera o ChatModel da OpenAI (compatível com o OpenCode Go).");
        }
        this.modelo = modelo;
        this.chave = chave;
        this.raciocinio = raciocinio;
    }

    /** O raciocínio do modelo está ligado neste provedor? */
    public boolean raciocinio() {
        return raciocinio;
    }

    @Override
    public String modelo() {
        return ((OpenAiChatOptions) modelo.getOptions()).getModel();
    }

    /** O provedor da OpenAI aceita imagem: ela vai como {@code image_url} na mensagem da pessoa. */
    @Override
    public boolean leImagem() {
        return true;
    }

    @Override
    public RespostaDoLlm responder(PedidoAoLlm pedido) {
        long inicio = System.nanoTime();
        try {
            ChatResponse resposta = modelo.call(new Prompt(mensagens(pedido.mensagens(), pedido.imagem()), opcoes(pedido)));
            RespostaDoLlm saida = resposta(resposta);
            long ms = (System.nanoTime() - inicio) / 1_000_000;
            return new RespostaDoLlm(saida.texto(), saida.chamadas(), raciocinio ? saida.raciocinio() : null,
                    uso(resposta == null ? null : resposta.getMetadata(), motivoDoFim(resposta), ms));
        } catch (RuntimeException erro) {
            throw falha(erro);
        }
    }

    @Override
    public String resumir(PedidoDeResumo pedido) {
        return resumirMedindo(pedido).texto();
    }

    @Override
    public ResumoDoLlm resumirMedindo(PedidoDeResumo pedido) {
        long inicio = System.nanoTime();
        try {
            Prompt prompt = new Prompt(List.of(new SystemMessage(pedido.instrucoes()), new UserMessage(pedido.entrada())),
                    opcoesDoResumo(pedido));
            ChatResponse resposta = modelo.call(prompt);
            Generation geracao = resposta == null ? null : resposta.getResult();
            String texto = geracao == null || geracao.getOutput() == null ? null : vazioViraNulo(geracao.getOutput().getText());
            long ms = (System.nanoTime() - inicio) / 1_000_000;
            return new ResumoDoLlm(texto, uso(resposta == null ? null : resposta.getMetadata(), motivoDoFim(resposta), ms));
        } catch (RuntimeException erro) {
            // Nada do erro do provedor: nesta chamada vai o raciocínio, e o corpo de um erro pode devolver o que recebeu.
            String nome = erro.getClass().getSimpleName();
            log.warn("O modelo de linguagem não respondeu ao resumo do raciocínio ({})", nome);
            throw new ProvedorIndisponivelException("O modelo de linguagem não respondeu ao resumo do raciocínio (" + nome + ").");
        }
    }

    @Override
    public RespostaDoLlm responderEmFluxo(PedidoAoLlm pedido, OuvinteDoFluxo ouvinte) {
        StringBuilder texto = new StringBuilder();
        String raciocinioAcumulado = "";
        List<ChamadaDeFerramenta> chamadas = new ArrayList<>();
        long inicio = System.nanoTime();
        ChatResponseMetadata maisCompleta = null;   // o pedaço com o uso: no fluxo ele chega por último, com a lista de escolhas vazia
        String motivoDoFim = null;
        try {
            // toIterable consome o fluxo nesta thread (a do turno): o cliente HTTP enche a fila na dele, e nada fica em transação.
            // O uso vem do stream_options.include_usage, que o Spring AI 2.0.2 já manda em todo fluxo (conferido em teste).
            for (ChatResponse parte : modelo.stream(new Prompt(mensagens(pedido.mensagens(), pedido.imagem()), opcoes(pedido))).toIterable()) {
                if (parte.getMetadata() != null && (maisCompleta == null || tokensDe(parte.getMetadata().getUsage()) > tokensDe(maisCompleta.getUsage()))) {
                    maisCompleta = parte.getMetadata();
                }
                String motivo = motivoDoFim(parte);
                if (motivo != null) {
                    motivoDoFim = motivo;
                }
                for (Generation geracao : parte.getResults()) {
                    AssistantMessage saida = geracao.getOutput();
                    if (saida == null) {
                        continue;
                    }
                    // O Spring AI põe nos metadados o raciocínio ACUMULADO até este pedaço: o que é novo é o que cresceu.
                    if (raciocinio && saida.getMetadata().get(RACIOCINIO_NOS_METADADOS) instanceof String acumulado
                            && acumulado.length() > raciocinioAcumulado.length() && acumulado.startsWith(raciocinioAcumulado)) {
                        ouvinte.raciocinio(acumulado.substring(raciocinioAcumulado.length()));
                        raciocinioAcumulado = acumulado;
                    }
                    String pedaco = saida.getText();
                    if (pedaco != null && !pedaco.isEmpty()) {
                        texto.append(pedaco);
                        ouvinte.texto(pedaco);
                    }
                    saida.getToolCalls().forEach(c -> chamadas.add(new ChamadaDeFerramenta(c.id(), c.name(), c.arguments())));
                }
            }
        } catch (RuntimeException erro) {
            throw falha(erro);
        }
        long ms = (System.nanoTime() - inicio) / 1_000_000;
        return new RespostaDoLlm(vazioViraNulo(texto.toString()), chamadas, vazioViraNulo(raciocinioAcumulado),
                uso(maisCompleta, motivoDoFim, ms));
    }

    /**
     * A falha do provedor como {@link ProvedorIndisponivelException}. A mensagem do SDK pode trazer o corpo do erro do provedor:
     * limpa, curta e sem chave. O que importa para quem depura (status e motivo) fica; o cabeçalho de autorização nunca entra.
     */
    private ProvedorIndisponivelException falha(RuntimeException erro) {
        if (erro instanceof ProvedorIndisponivelException jaTratada) {
            return jaTratada;
        }
        String motivo = limpar(erro.getClass().getSimpleName() + ": " + erro.getMessage(), chave);
        log.warn("O modelo de linguagem não respondeu ({})", motivo);
        return new ProvedorIndisponivelException("O modelo de linguagem não respondeu (" + motivo + ").", erro);
    }

    // ---------------------------------------------------------------------
    // O que a ida gastou (diagnóstico)
    // ---------------------------------------------------------------------

    /**
     * O que se mediu da ida: modelo, tokens, motivo do fim, latência e o custo de lista do provedor, quando ele diz. Resposta sem
     * o campo {@code usage} chega do Spring AI com tokens zerados: aí os tokens ficam nulos (o provedor não disse), nunca 0.
     */
    static UsoDoLlm uso(ChatResponseMetadata metadados, String motivoDoFim, long latenciaMs) {
        Usage uso = metadados == null ? null : metadados.getUsage();
        boolean informado = tokensDe(uso) > 0;
        String modelo = metadados == null || metadados.getModel() == null || metadados.getModel().isBlank() ? null : metadados.getModel();
        return new UsoDoLlm(modelo, informado ? uso.getPromptTokens() : null, informado ? uso.getCompletionTokens() : null,
                motivoDoFim, latenciaMs, informado ? custoDeLista(uso) : null);
    }

    private static int tokensDe(Usage uso) {
        if (uso == null) {
            return 0;
        }
        Integer total = uso.getTotalTokens();
        return total == null ? 0 : total;
    }

    /** O motivo do fim da geração (stop, tool_calls, length...), em minúsculas como o provedor o escreve; nulo se não veio. */
    private static String motivoDoFim(ChatResponse resposta) {
        if (resposta == null) {
            return null;
        }
        String motivo = null;
        for (Generation geracao : resposta.getResults()) {
            String deste = geracao.getMetadata() == null ? null : geracao.getMetadata().getFinishReason();
            if (deste != null && !deste.isBlank()) {
                motivo = deste.toLowerCase(java.util.Locale.ROOT);
            }
        }
        return motivo;
    }

    /**
     * O {@code usage.opencode.list_cost} da resposta (dólares, a preço de lista), somado pelo diagnóstico no lugar de um preço fixo
     * por token. O SDK guarda o que não conhece como propriedade adicional do uso; número ou texto numérico servem. Nulo se o
     * provedor não manda, ou se o formato não é o esperado.
     */
    private static BigDecimal custoDeLista(Usage uso) {
        if (!(uso.getNativeUsage() instanceof CompletionUsage nativo)) {
            return null;
        }
        JsonValue opencode = nativo._additionalProperties().get("opencode");
        if (opencode == null) {
            return null;
        }
        try {
            if (opencode.convert(Map.class) instanceof Map<?, ?> mapa) {
                Object custo = mapa.get("list_cost");
                if (custo instanceof Number numero) {
                    return new BigDecimal(numero.toString());
                }
                if (custo instanceof String texto && !texto.isBlank()) {
                    return new BigDecimal(texto.strip());
                }
            }
        } catch (RuntimeException formatoInesperado) {
            log.debug("Atlas: o usage.opencode da resposta não tem o formato esperado.");
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Nosso formato -> Spring AI
    // ---------------------------------------------------------------------

    private OpenAiChatOptions opcoes(PedidoAoLlm pedido) {
        List<ToolCallback> ferramentas = pedido.ferramentas().stream().map(ProvedorSpringAi::soDefinicao).toList();
        return base(pedido.sessao(), raciocinio).toolCallbacks(ferramentas).build();
    }

    /** As opções do resumo: sem ferramentas, o raciocínio SEMPRE desligado, poucos tokens e temperatura baixa. */
    private OpenAiChatOptions opcoesDoResumo(PedidoDeResumo pedido) {
        return base(pedido.sessao(), false).toolCallbacks(List.of()).maxTokens(MAXIMO_DE_TOKENS_DO_RESUMO)
                .temperature(TEMPERATURA_DO_RESUMO).build();
    }

    /**
     * As opções padrão do modelo com o que muda a cada chamada: o cabeçalho da sessão e o {@code thinking}. As opções da chamada
     * não se juntam às padrão no Spring AI 2.0: parte-se das padrão (modelo, temperatura e extra-body) e acrescenta-se o resto.
     */
    private OpenAiChatOptions.Builder base(String sessao, boolean comRaciocinio) {
        OpenAiChatOptions padrao = (OpenAiChatOptions) modelo.getOptions();
        Map<String, String> cabecalhos = new LinkedHashMap<>();
        if (padrao.getCustomHeaders() != null) {
            cabecalhos.putAll(padrao.getCustomHeaders());
        }
        cabecalhos.put(CABECALHO_DA_SESSAO, sessao);
        Map<String, Object> corpoExtra = new LinkedHashMap<>();
        if (padrao.getExtraBody() != null) {
            corpoExtra.putAll(padrao.getExtraBody());
        }
        corpoExtra.put(THINKING, Map.of("type", comRaciocinio ? "enabled" : "disabled"));
        return padrao.mutate().customHeaders(cabecalhos).extraBody(corpoExtra);
    }

    private static List<Message> mensagens(List<MensagemDeChat> mensagens, Imagem imagem) {
        List<Message> convertidas = new ArrayList<>();
        for (MensagemDeChat mensagem : mensagens) {
            convertidas.add(switch (mensagem.papel()) {
                case "system" -> new SystemMessage(mensagem.conteudo());
                case "user" -> usuario(mensagem.conteudo(), imagem);
                case "assistant" -> assistente(mensagem);
                case "tool" -> ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponse(mensagem.chamadaId(),
                                mensagem.ferramenta() == null ? "ferramenta" : mensagem.ferramenta(), mensagem.conteudo())))
                        .build();
                default -> throw new IllegalArgumentException("Papel de mensagem desconhecido: " + mensagem.papel());
            });
        }
        return convertidas;
    }

    /** A mensagem da pessoa; com a imagem do pedido, ela vai como {@code image_url} (base64) junto do texto. */
    private static UserMessage usuario(String texto, Imagem imagem) {
        if (imagem == null || imagem.base64() == null || imagem.base64().isBlank()) {
            return new UserMessage(texto);
        }
        Media midia = Media.builder().mimeType(MimeType.valueOf(imagem.mimeType()))
                .data(Base64.getDecoder().decode(imagem.base64())).build();
        return UserMessage.builder().text(texto).media(midia).build();
    }

    private static AssistantMessage assistente(MensagemDeChat mensagem) {
        List<AssistantMessage.ToolCall> chamadas = mensagem.chamadas().stream()
                .map(c -> new AssistantMessage.ToolCall(c.id(), "function", c.nome(), c.argumentos())).toList();
        Map<String, Object> metadados = new LinkedHashMap<>();
        if (mensagem.raciocinio() != null && !mensagem.raciocinio().isBlank()) {
            metadados.put(RACIOCINIO_NOS_METADADOS, mensagem.raciocinio());
        }
        return AssistantMessage.builder().content(mensagem.conteudo()).toolCalls(chamadas).properties(metadados).build();
    }

    /** Uma ferramenta que o Spring AI só descreve ao modelo: executá-la aqui seria um erro, quem executa é o loop. */
    private static ToolCallback soDefinicao(DefinicaoDeFerramenta definicao) {
        ToolDefinition descricao = DefaultToolDefinition.builder().name(definicao.nome()).description(definicao.descricao())
                .inputSchema(definicao.esquema()).build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return descricao;
            }

            @Override
            public String call(String entrada) {
                throw new UnsupportedOperationException("As ferramentas do Atlas são executadas pelo loop do agente.");
            }
        };
    }

    // ---------------------------------------------------------------------
    // Spring AI -> nosso formato
    // ---------------------------------------------------------------------

    private static RespostaDoLlm resposta(ChatResponse resposta) {
        Generation geracao = resposta == null ? null : resposta.getResult();
        if (geracao == null || geracao.getOutput() == null) {
            return new RespostaDoLlm(null, List.of(), null);
        }
        AssistantMessage saida = geracao.getOutput();
        List<ChamadaDeFerramenta> chamadas = saida.getToolCalls().stream()
                .map(c -> new ChamadaDeFerramenta(c.id(), c.name(), c.arguments())).toList();
        Object raciocinio = saida.getMetadata().get(RACIOCINIO_NOS_METADADOS);
        return new RespostaDoLlm(vazioViraNulo(saida.getText()), chamadas,
                raciocinio instanceof String texto ? vazioViraNulo(texto) : null);
    }

    private static String vazioViraNulo(String texto) {
        return texto == null || texto.isBlank() ? null : texto;
    }

    /** A mensagem de erro sem a chave configurada (se não for nula) nem trecho que pareça chave, e curta. */
    public static String limpar(String mensagem, String chave) {
        String texto = mensagem == null ? "" : mensagem;
        if (chave != null && !chave.isBlank()) {
            texto = texto.replace(chave, "[omitido]");
        }
        String semSegredo = SEGREDO.matcher(texto).replaceAll("[omitido]");
        return semSegredo.length() > 300 ? semSegredo.substring(0, 300) + "..." : semSegredo;
    }
}
