package br.com.rbs.portal.atlas.provedor;

import java.util.ArrayList;
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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
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
    public RespostaDoLlm responder(PedidoAoLlm pedido) {
        try {
            ChatResponse resposta = modelo.call(new Prompt(mensagens(pedido.mensagens()), opcoes(pedido)));
            return resposta(resposta);
        } catch (RuntimeException erro) {
            throw falha(erro);
        }
    }

    @Override
    public RespostaDoLlm responderEmFluxo(PedidoAoLlm pedido, OuvinteDoFluxo ouvinte) {
        StringBuilder texto = new StringBuilder();
        String raciocinioAcumulado = "";
        List<ChamadaDeFerramenta> chamadas = new ArrayList<>();
        try {
            // toIterable consome o fluxo nesta thread (a do turno): o cliente HTTP enche a fila na dele, e nada fica em transação.
            for (ChatResponse parte : modelo.stream(new Prompt(mensagens(pedido.mensagens()), opcoes(pedido))).toIterable()) {
                for (Generation geracao : parte.getResults()) {
                    AssistantMessage saida = geracao.getOutput();
                    if (saida == null) {
                        continue;
                    }
                    // O Spring AI põe nos metadados o raciocínio ACUMULADO até este pedaço: o que é novo é o que cresceu.
                    if (saida.getMetadata().get(RACIOCINIO_NOS_METADADOS) instanceof String acumulado
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
        return new RespostaDoLlm(vazioViraNulo(texto.toString()), chamadas, vazioViraNulo(raciocinioAcumulado));
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
    // Nosso formato -> Spring AI
    // ---------------------------------------------------------------------

    private OpenAiChatOptions opcoes(PedidoAoLlm pedido) {
        OpenAiChatOptions padrao = (OpenAiChatOptions) modelo.getOptions();
        Map<String, String> cabecalhos = new LinkedHashMap<>();
        if (padrao.getCustomHeaders() != null) {
            cabecalhos.putAll(padrao.getCustomHeaders());
        }
        cabecalhos.put(CABECALHO_DA_SESSAO, pedido.sessao());
        List<ToolCallback> ferramentas = pedido.ferramentas().stream().map(ProvedorSpringAi::soDefinicao).toList();
        Map<String, Object> corpoExtra = new LinkedHashMap<>();
        if (padrao.getExtraBody() != null) {
            corpoExtra.putAll(padrao.getExtraBody());
        }
        corpoExtra.put(THINKING, Map.of("type", raciocinio ? "enabled" : "disabled"));
        // As opções da chamada não se juntam às padrão no Spring AI 2.0: parte-se das padrão (modelo, temperatura e extra-body)
        // e acrescenta-se o que muda a cada chamada.
        return padrao.mutate().customHeaders(cabecalhos).toolCallbacks(ferramentas).extraBody(corpoExtra).build();
    }

    private static List<Message> mensagens(List<MensagemDeChat> mensagens) {
        List<Message> convertidas = new ArrayList<>();
        for (MensagemDeChat mensagem : mensagens) {
            convertidas.add(switch (mensagem.papel()) {
                case "system" -> new SystemMessage(mensagem.conteudo());
                case "user" -> new UserMessage(mensagem.conteudo());
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
