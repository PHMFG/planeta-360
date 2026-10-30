package br.com.rbs.portal.atlas.provedor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Um servidor HTTP falso do provedor (compatível com chat completions), local, numa porta livre, só para os testes do Atlas:
 * guarda cada requisição que chega (método, caminho, TODOS os valores de cada cabeçalho e o corpo) e responde, em ordem, com o
 * que o teste enfileirou. Nada sai da máquina. Atende o item 5 do pedido da terceira parte do Atlas. Não faz parte da API.
 */
public final class ServidorDeLlmFalso implements AutoCloseable {

    /** O que o provedor recebeu. {@code cabecalhos} guarda o nome em minúsculas e todos os valores, na ordem em que vieram. */
    public record Recebida(String metodo, String caminho, Map<String, List<String>> cabecalhos, String corpo) {

        public List<String> cabecalho(String nome) {
            return cabecalhos.getOrDefault(nome.toLowerCase(), List.of());
        }
    }

    private record Resposta(int status, String corpo) {
    }

    private final HttpServer servidor;
    private final Deque<Resposta> respostas = new ArrayDeque<>();
    private final List<Recebida> recebidas = new CopyOnWriteArrayList<>();
    private volatile Consumer<Recebida> aoReceber;

    private ServidorDeLlmFalso(HttpServer servidor) {
        this.servidor = servidor;
    }

    public static ServidorDeLlmFalso iniciar() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            ServidorDeLlmFalso falso = new ServidorDeLlmFalso(http);
            http.createContext("/", falso::atender);
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
        respostas.add(new Resposta(200, json));
    }

    public synchronized void responderComStatus(int status, String json) {
        respostas.add(new Resposta(status, json));
    }

    public List<Recebida> recebidas() {
        return List.copyOf(recebidas);
    }

    public Recebida ultima() {
        return recebidas.get(recebidas.size() - 1);
    }

    public synchronized void limpar() {
        respostas.clear();
        recebidas.clear();
        aoReceber = null;
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
        recebidas.add(recebida);
        Consumer<Recebida> gancho = aoReceber;
        if (gancho != null) {
            gancho.accept(recebida);
        }
        Resposta resposta;
        synchronized (this) {
            resposta = respostas.poll();
        }
        if (resposta == null) {
            resposta = new Resposta(500, "{\"error\":{\"message\":\"o teste não enfileirou resposta\"}}");
        }
        byte[] saida = resposta.corpo().getBytes(StandardCharsets.UTF_8);
        troca.getResponseHeaders().add("Content-Type", "application/json");
        troca.sendResponseHeaders(resposta.status(), saida.length);
        troca.getResponseBody().write(saida);
        troca.close();
    }

    @Override
    public void close() {
        servidor.stop(0);
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
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
    }

    private static String json(String texto) {
        return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(texto);
    }
}
