package br.com.rbs.portal.atlas.diagnostico;

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
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Um servidor HTTP falso e local (numa porta livre) para os testes dos provedores que não são o modelo de linguagem: o JEV e o
 * Gemini. Guarda cada requisição (método, caminho, todos os valores de cada cabeçalho e o corpo) e responde, em ordem, com o que o
 * teste enfileirou. Sem resposta enfileirada, responde 500. Nada sai da máquina. Não faz parte da API.
 */
public final class ServidorHttpFalso implements AutoCloseable {

    /** O que o servidor recebeu. {@code cabecalhos} guarda o nome em minúsculas e todos os valores. */
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
    private final ExecutorService threads = Executors.newCachedThreadPool();

    private ServidorHttpFalso(HttpServer servidor) {
        this.servidor = servidor;
    }

    public static ServidorHttpFalso iniciar() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            ServidorHttpFalso falso = new ServidorHttpFalso(http);
            http.createContext("/", falso::atender);
            http.setExecutor(falso.threads);
            http.start();
            return falso;
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }
    }

    /** O endereço base, sem barra no fim: {@code http://127.0.0.1:porta}. */
    public String urlBase() {
        return "http://127.0.0.1:" + servidor.getAddress().getPort();
    }

    public synchronized void responder(int status, String json) {
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
    }

    private void atender(HttpExchange troca) throws IOException {
        byte[] corpo = troca.getRequestBody().readAllBytes();
        Map<String, List<String>> cabecalhos = new TreeMap<>();
        troca.getRequestHeaders().forEach((nome, valores) -> cabecalhos.put(nome.toLowerCase(), new ArrayList<>(valores)));
        recebidas.add(new Recebida(troca.getRequestMethod(), troca.getRequestURI().getPath(), cabecalhos,
                new String(corpo, StandardCharsets.UTF_8)));
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
        threads.shutdownNow();
    }
}
