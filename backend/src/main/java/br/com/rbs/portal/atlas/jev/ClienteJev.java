package br.com.rbs.portal.atlas.jev;

import br.com.rbs.portal.atlas.diagnostico.ConfiguracaoDeProvedores;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * O cliente HTTP do JEV (plano do Atlas completo, M7): {@code POST /v1/systemone} com {@code Authorization: Bearer}, o modelo fixado
 * por versão ({@code ATLAS_JEV_MODELO}, {@code jev-1.14.0}), conexão em 300 ms e a requisição inteira (a resposta lida) em 800 ms.
 * [ATLAS] O corpo é {@code {model, state, questions:{<nome>:{type:"choice",instructions,criteria:{<opção>:<descrição>}} |
 * {type:"noul",instructions}}}}, e a resposta traz {@code answers.<nome>} com {@code choice}, {@code confidence} e
 * {@code probabilities}, ou {@code noul} (veja {@code scripts/fumaca-provedores.sh}). Uma tentativa só: 400, 401, 413, 415, 422, 429,
 * 529, qualquer outro status, o tempo esgotado e a rede fora viram {@link JevIndisponivelException}, e o turno segue o caminho do
 * modelo de linguagem. A chave só vai no cabeçalho, para o endereço configurado; não segue redirecionamento, e nem a chave nem o corpo
 * da resposta entram na exceção.
 */
@Component
public class ClienteJev implements ProvedorDeJev {

    public static final Duration PRAZO_DA_CONEXAO = Duration.ofMillis(300);
    public static final Duration PRAZO_DA_REQUISICAO = Duration.ofMillis(800);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ConfiguracaoDeProvedores config;
    private final Duration prazoDaRequisicao;
    private final HttpClient http;

    @Autowired
    public ClienteJev(ConfiguracaoDeProvedores config, @Value("${atlas.jev.timeout:PT0.8S}") Duration prazoDaRequisicao,
                      @Value("${atlas.jev.timeout-conexao:PT0.3S}") Duration prazoDaConexao) {
        this.config = config;
        this.prazoDaRequisicao = prazoDaRequisicao;
        this.http = HttpClient.newBuilder().connectTimeout(prazoDaConexao).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public String modelo() {
        return config.jevModelo();
    }

    @Override
    public RespostaDoJev perguntar(PedidoAoJev pedido) {
        if (!config.jevTemChave()) {
            throw new JevIndisponivelException("SEM_CHAVE");
        }
        HttpRequest requisicao = HttpRequest.newBuilder(URI.create(base(config.jevBaseUrl()) + "/v1/systemone"))
                .timeout(prazoDaRequisicao.plusMillis(200))
                .header("Authorization", "Bearer " + config.jevChave()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo(pedido, config.jevModelo()))).build();
        long inicio = System.nanoTime();
        CompletableFuture<HttpResponse<String>> futuro = http.sendAsync(requisicao, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        HttpResponse<String> resposta;
        try {
            // O prazo vale para a requisição inteira, o corpo lido: o turno não espera o JEV além disto.
            resposta = futuro.get(prazoDaRequisicao.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException esgotou) {
            futuro.cancel(true);
            throw new JevIndisponivelException("TIMEOUT");
        } catch (InterruptedException interrompida) {
            futuro.cancel(true);
            Thread.currentThread().interrupt();
            throw new JevIndisponivelException("INTERROMPIDO");
        } catch (ExecutionException falhou) {
            Throwable causa = falhou.getCause() == null ? falhou : falhou.getCause();
            if (causa instanceof HttpConnectTimeoutException) {
                throw new JevIndisponivelException("TIMEOUT_DA_CONEXAO");
            }
            if (causa instanceof HttpTimeoutException) {
                throw new JevIndisponivelException("TIMEOUT");
            }
            throw new JevIndisponivelException("REDE_" + causa.getClass().getSimpleName());
        }
        if (resposta.statusCode() != 200) {
            throw new JevIndisponivelException("HTTP_" + resposta.statusCode());
        }
        return lerResposta(pedido, resposta.body(), (System.nanoTime() - inicio) / 1_000_000);
    }

    /** O corpo da requisição, com as perguntas na ordem em que foram dadas e as opções de cada {@code choice} na ordem delas. */
    static String corpo(PedidoAoJev pedido, String modelo) {
        Map<String, Object> perguntas = new LinkedHashMap<>();
        pedido.perguntas().forEach((nome, pergunta) -> {
            Map<String, Object> corpoDaPergunta = new LinkedHashMap<>();
            corpoDaPergunta.put("type", pergunta.tipo());
            corpoDaPergunta.put("instructions", pergunta.instrucoes());
            if (pergunta.ehEscolha()) {
                corpoDaPergunta.put("criteria", pergunta.criterios());
            }
            perguntas.put(nome, corpoDaPergunta);
        });
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("model", modelo);
        corpo.put("state", pedido.estado());
        corpo.put("questions", perguntas);
        return JSON.writeValueAsString(corpo);
    }

    private RespostaDoJev lerResposta(PedidoAoJev pedido, String texto, long latenciaMs) {
        JsonNode raiz;
        try {
            raiz = texto == null || texto.isBlank() ? null : JSON.readTree(texto);
        } catch (RuntimeException naoEJson) {
            throw new JevIndisponivelException("RESPOSTA_INVALIDA");
        }
        JsonNode respostas = raiz == null ? null : raiz.get("answers");
        if (respostas == null || !respostas.isObject()) {
            throw new JevIndisponivelException("RESPOSTA_INVALIDA");
        }
        Map<String, RespostaDaPergunta> lidas = new LinkedHashMap<>();
        for (Map.Entry<String, PerguntaAoJev> pergunta : pedido.perguntas().entrySet()) {
            JsonNode resposta = respostas.get(pergunta.getKey());
            if (resposta == null || !resposta.isObject()) {
                continue;
            }
            if (pergunta.getValue().ehEscolha()) {
                JsonNode escolha = resposta.get("choice");
                JsonNode confianca = resposta.get("confidence");
                if (escolha != null && escolha.isString() && confianca != null && confianca.isNumber()) {
                    JsonNode probabilidades = resposta.get("probabilities");
                    lidas.put(pergunta.getKey(), RespostaDaPergunta.deEscolha(escolha.asString(), confianca.asDouble(),
                            probabilidades == null || probabilidades.isNull() ? null : JSON.treeToValue(probabilidades, Object.class)));
                }
            } else {
                JsonNode noul = resposta.get("noul");
                if (noul != null && noul.isNumber()) {
                    lidas.put(pergunta.getKey(), RespostaDaPergunta.deNota(noul.asDouble()));
                }
            }
        }
        return new RespostaDoJev(lidas, latenciaMs);
    }

    private static String base(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
