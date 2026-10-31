package br.com.rbs.portal.atlas.provedor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Um servidor HTTP falso do provedor (compatível com chat completions), local, numa porta livre, só para os testes do Atlas:
 * guarda cada requisição que chega (método, caminho, TODOS os valores de cada cabeçalho e o corpo) e responde, em ordem, com o
 * que o teste enfileirou: uma resposta JSON inteira, ou uma resposta em fluxo no formato da OpenAI ({@code text/event-stream},
 * {@code data: {...choices[0].delta...}} e {@code data: [DONE]}; veja {@link Fluxo}). Com {@link #exigirRaciocinio}, faz como
 * o DeepSeek com o raciocínio ligado: responde 400 se uma mensagem do assistente com {@code tool_calls} vier sem o
 * {@code reasoning_content}. Nada sai da máquina. Não faz parte da API.
 * <p>
 * Responde também às chamadas do <b>resumo do raciocínio</b>, à parte da fila: reconhece-as pelo pedido sem ferramentas e com o
 * prompt do resumo ({@code atlas/prompts/resumo-do-raciocinio.txt}) como mensagem de sistema. Elas não entram em
 * {@link #recebidas} (que continua sendo só as idas do loop) nem consomem a fila: ficam em {@link #resumosRecebidos}, e a
 * resposta é a que o teste enfileirou com {@link #responderResumo} ou, na falta dela, {@link #RESUMO_PADRAO}. Atende cada
 * pedido numa thread própria, para um resumo lento ({@link #atrasarResumos}) não segurar a ida seguinte do loop.
 */
public final class ServidorDeLlmFalso implements AutoCloseable {

    /** O resumo que o servidor devolve quando o teste não enfileirou outro: passa na checagem do texto do chat. */
    public static final String RESUMO_PADRAO = "Conferi o que foi pedido e decidi como responder.";

    /** O prompt do resumo do raciocínio, o mesmo recurso que a API manda como mensagem de sistema. */
    private static final String PROMPT_DO_RESUMO = lerOPromptDoResumo();

    /** O uso que toda resposta falsa traz: 10 tokens de entrada, 5 de saída e o custo de lista do OpenCode Go (usage.opencode.list_cost). */
    public static final String USO = "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15,"
            + "\"opencode\":{\"list_cost\":0.0004}}";
    public static final int TOKENS_DE_ENTRADA = 10;
    public static final int TOKENS_DE_SAIDA = 5;
    public static final String CUSTO_DE_LISTA = "0.0004";

    /** A frase do 400 de quando falta o reasoning_content (a do DeepSeek). */
    public static final String SEM_RACIOCINIO = "The reasoning_content in the thinking mode must be passed back to the API.";

    /** O que o provedor recebeu. {@code cabecalhos} guarda o nome em minúsculas e todos os valores, na ordem em que vieram. */
    public record Recebida(String metodo, String caminho, Map<String, List<String>> cabecalhos, String corpo) {

        public List<String> cabecalho(String nome) {
            return cabecalhos.getOrDefault(nome.toLowerCase(), List.of());
        }
    }

    private record Resposta(int status, String corpo, String tipo) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer servidor;
    private final Deque<Resposta> respostas = new ArrayDeque<>();
    private final List<Recebida> recebidas = new CopyOnWriteArrayList<>();
    private final Deque<Resposta> respostasDoResumo = new ArrayDeque<>();
    private final List<Recebida> resumosRecebidos = new CopyOnWriteArrayList<>();
    private final ExecutorService threads = Executors.newCachedThreadPool();
    private volatile Consumer<Recebida> aoReceber;
    private volatile boolean exigirRaciocinio;
    private volatile Duration atrasoDoResumo = Duration.ZERO;

    private ServidorDeLlmFalso(HttpServer servidor) {
        this.servidor = servidor;
    }

    public static ServidorDeLlmFalso iniciar() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            ServidorDeLlmFalso falso = new ServidorDeLlmFalso(http);
            http.createContext("/", falso::atender);
            http.setExecutor(falso.threads);
            http.start();
            return falso;
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }
    }

    /** A base que vai em spring.ai.openai.base-url (o SDK acrescenta /chat/completions). */
    public String urlBase() {
        return "http://127.0.0.1:" + servidor.getAddress().getPort() + "/inference/go/openai/v1";
    }

    public synchronized void responder(String json) {
        respostas.add(new Resposta(200, json, "application/json"));
    }

    public synchronized void responderComStatus(int status, String json) {
        respostas.add(new Resposta(status, json, "application/json"));
    }

    /** Uma resposta em fluxo (o corpo de {@link Fluxo#fim}). */
    public synchronized void responderEmFluxo(String corpoSse) {
        respostas.add(new Resposta(200, corpoSse, "text/event-stream"));
    }

    /** Como o DeepSeek com o raciocínio ligado: 400 se um assistente com tool_calls vier sem o reasoning_content. */
    public void exigirRaciocinio(boolean exigir) {
        this.exigirRaciocinio = exigir;
    }

    public List<Recebida> recebidas() {
        return List.copyOf(recebidas);
    }

    public Recebida ultima() {
        return recebidas.get(recebidas.size() - 1);
    }

    /** Enfileira o resumo que a próxima chamada de resumo recebe (as seguintes, sem fila, recebem {@link #RESUMO_PADRAO}). */
    public synchronized void responderResumo(String resumo) {
        respostasDoResumo.add(new Resposta(200, texto(resumo), "application/json"));
    }

    /** Enfileira uma falha para a próxima chamada de resumo (por exemplo, 500). */
    public synchronized void responderResumoComStatus(int status, String json) {
        respostasDoResumo.add(new Resposta(status, json, "application/json"));
    }

    /** Faz cada chamada de resumo demorar este tanto para responder (a ida do loop não espera por ela). */
    public void atrasarResumos(Duration atraso) {
        this.atrasoDoResumo = atraso;
    }

    /** As chamadas de resumo do raciocínio que chegaram, na ordem. Não entram em {@link #recebidas}. */
    public List<Recebida> resumosRecebidos() {
        return List.copyOf(resumosRecebidos);
    }

    public synchronized void limpar() {
        respostas.clear();
        recebidas.clear();
        respostasDoResumo.clear();
        resumosRecebidos.clear();
        atrasoDoResumo = Duration.ZERO;
        aoReceber = null;
        exigirRaciocinio = false;
    }

    /** Roda a cada requisição, depois de guardá-la e antes de responder (na linha do servidor). Para mexer no meio de um turno. */
    public void aoReceber(Consumer<Recebida> gancho) {
        this.aoReceber = gancho;
    }

    private void atender(HttpExchange troca) throws IOException {
        byte[] corpo = troca.getRequestBody().readAllBytes();
        Map<String, List<String>> cabecalhos = new java.util.TreeMap<>();
        troca.getRequestHeaders().forEach((nome, valores) -> cabecalhos.put(nome.toLowerCase(), new ArrayList<>(valores)));
        Recebida recebida = new Recebida(troca.getRequestMethod(), troca.getRequestURI().getPath(), cabecalhos,
                new String(corpo, StandardCharsets.UTF_8));
        if (ehResumoDoRaciocinio(recebida.corpo())) {
            atenderResumo(troca, recebida);
            return;
        }
        recebidas.add(recebida);
        Consumer<Recebida> gancho = aoReceber;
        if (gancho != null) {
            gancho.accept(recebida);
        }
        Resposta resposta;
        if (exigirRaciocinio && faltaRaciocinio(recebida.corpo())) {
            resposta = new Resposta(400, "{\"error\":{\"message\":\"" + SEM_RACIOCINIO + "\",\"type\":\"invalid_request_error\"}}",
                    "application/json");
        } else {
            synchronized (this) {
                resposta = respostas.poll();
            }
        }
        if (resposta == null) {
            resposta = new Resposta(500, "{\"error\":{\"message\":\"o teste não enfileirou resposta\"}}", "application/json");
        }
        enviar(troca, resposta);
    }

    /** A chamada do resumo do raciocínio: guarda, espera o atraso do teste e responde pela fila própria (ou o resumo padrão). */
    private void atenderResumo(HttpExchange troca, Recebida recebida) throws IOException {
        resumosRecebidos.add(recebida);
        try {
            Thread.sleep(atrasoDoResumo.toMillis());
        } catch (InterruptedException interrompido) {
            Thread.currentThread().interrupt();
        }
        Resposta resposta;
        synchronized (this) {
            resposta = respostasDoResumo.poll();
        }
        enviar(troca, resposta != null ? resposta : new Resposta(200, texto(RESUMO_PADRAO), "application/json"));
    }

    private static void enviar(HttpExchange troca, Resposta resposta) throws IOException {
        byte[] saida = resposta.corpo().getBytes(StandardCharsets.UTF_8);
        troca.getResponseHeaders().add("Content-Type", resposta.tipo());
        if ("text/event-stream".equals(resposta.tipo())) {
            // em pedaços, um evento por vez, como um provedor que transmite
            troca.sendResponseHeaders(resposta.status(), 0);
            try (OutputStream fluxo = troca.getResponseBody()) {
                for (String evento : resposta.corpo().split("(?<=\n\n)")) {
                    fluxo.write(evento.getBytes(StandardCharsets.UTF_8));
                    fluxo.flush();
                }
            }
        } else {
            troca.sendResponseHeaders(resposta.status(), saida.length);
            troca.getResponseBody().write(saida);
        }
        troca.close();
    }

    /** Um pedido sem ferramentas cuja mensagem de sistema é o prompt do resumo do raciocínio. */
    private static boolean ehResumoDoRaciocinio(String corpo) {
        JsonNode raiz = JSON.readTree(corpo);
        JsonNode ferramentas = raiz.path("tools");
        if (ferramentas.isArray() && !ferramentas.isEmpty()) {
            return false;
        }
        JsonNode primeira = raiz.path("messages").path(0);
        return "system".equals(primeira.path("role").asString(""))
                && PROMPT_DO_RESUMO.strip().equals(primeira.path("content").asString("").strip());
    }

    private static String lerOPromptDoResumo() {
        try (var entrada = ServidorDeLlmFalso.class.getResourceAsStream("/atlas/prompts/resumo-do-raciocinio.txt")) {
            return new String(java.util.Objects.requireNonNull(entrada, "falta o prompt do resumo do raciocínio").readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }
    }

    /** Alguma mensagem do assistente com tool_calls veio sem o reasoning_content? */
    private static boolean faltaRaciocinio(String corpo) {
        JsonNode mensagens = JSON.readTree(corpo).path("messages");
        for (JsonNode mensagem : mensagens) {
            boolean comChamadas = mensagem.path("tool_calls").isArray() && !mensagem.path("tool_calls").isEmpty();
            String raciocinio = mensagem.path("reasoning_content").asString("");
            if ("assistant".equals(mensagem.path("role").asString("")) && comChamadas && raciocinio.isBlank()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        servidor.stop(0);
        threads.shutdownNow();
    }

    // ---------------------------------------------------------------------
    // Respostas prontas no formato de chat completions
    // ---------------------------------------------------------------------

    /** Uma resposta só de texto. */
    public static String texto(String conteudo) {
        return completacao("\"content\":" + json(conteudo), "stop");
    }

    /** Uma resposta com um pedido de ferramenta, e o reasoning_content se não for nulo. */
    public static String chamada(String id, String nome, String argumentosJson, String raciocinio) {
        String chamada = "{\"id\":" + json(id) + ",\"type\":\"function\",\"function\":{\"name\":" + json(nome)
                + ",\"arguments\":" + json(argumentosJson) + "}}";
        String mensagem = "\"content\":null,\"tool_calls\":[" + chamada + "]"
                + (raciocinio == null ? "" : ",\"reasoning_content\":" + json(raciocinio));
        return completacao(mensagem, "tool_calls");
    }

    private static String completacao(String mensagem, String motivo) {
        return "{\"id\":\"chatcmpl-teste\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"deepseek-v4.1-flash\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\"," + mensagem + "},\"finish_reason\":\"" + motivo + "\"}],"
                + USO + "}";
    }

    static String json(String texto) {
        return JSON.writeValueAsString(texto);
    }

    /**
     * Uma resposta em fluxo no formato da OpenAI (como o DeepSeek a manda): cada pedaço é um evento
     * {@code data: {"object":"chat.completion.chunk","choices":[{"index":0,"delta":{...}}]}}; o raciocínio vem em
     * {@code delta.reasoning_content}, o texto em {@code delta.content} e o pedido de ferramenta em pedaços de
     * {@code delta.tool_calls} (o primeiro com id e nome, os outros só com mais argumentos). {@link #fim} fecha com o
     * {@code finish_reason}, o pedaço do uso e {@code data: [DONE]}.
     */
    public static final class Fluxo {

        private final StringBuilder eventos = new StringBuilder();
        private boolean primeiro = true;

        public Fluxo raciocinio(String pedaco) {
            return delta("\"reasoning_content\":" + json(pedaco));
        }

        public Fluxo texto(String pedaco) {
            return delta("\"content\":" + json(pedaco));
        }

        /** O começo de um pedido de ferramenta: id e nome, com os argumentos ainda vazios. */
        public Fluxo chamada(String id, String nome) {
            return delta("\"tool_calls\":[{\"index\":0,\"id\":" + json(id) + ",\"type\":\"function\",\"function\":{\"name\":"
                    + json(nome) + ",\"arguments\":\"\"}}]");
        }

        /** Mais um pedaço dos argumentos do pedido de ferramenta (o DeepSeek manda o id vazio nas continuações). */
        public Fluxo argumentos(String pedaco) {
            return delta("\"tool_calls\":[{\"index\":0,\"id\":\"\",\"function\":{\"arguments\":" + json(pedaco) + "}}]");
        }

        /** Os argumentos inteiros, em pedaços de {@code tamanho} caracteres. */
        public Fluxo argumentosEmPedacos(String argumentos, int tamanho) {
            for (int i = 0; i < argumentos.length(); i += tamanho) {
                argumentos(argumentos.substring(i, Math.min(argumentos.length(), i + tamanho)));
            }
            return this;
        }

        /** Fecha o fluxo: {@code stop} (texto) ou {@code tool_calls}. Devolve o corpo da resposta. */
        public String fim(String motivo) {
            evento("{\"index\":0,\"delta\":{},\"finish_reason\":\"" + motivo + "\"}");
            eventos.append("data: {\"id\":\"chatcmpl-fluxo\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                    + "\"model\":\"deepseek-v4.1-flash\",\"choices\":[]," + USO + "}\n\n");
            eventos.append("data: [DONE]\n\n");
            return eventos.toString();
        }

        private Fluxo delta(String campos) {
            String papel = primeiro ? "\"role\":\"assistant\"," : "";
            primeiro = false;
            evento("{\"index\":0,\"delta\":{" + papel + campos + "},\"finish_reason\":null}");
            return this;
        }

        private void evento(String escolha) {
            eventos.append("data: {\"id\":\"chatcmpl-fluxo\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                    + "\"model\":\"deepseek-v4.1-flash\",\"choices\":[").append(escolha).append("]}\n\n");
        }
    }
}
