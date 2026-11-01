package br.com.rbs.portal.atlas.embeddings;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Os embeddings do Gemini ({@code gemini-embedding-2}, 768 dimensões), pela API REST (M3 do plano do Atlas completo). [ATLAS]
 * Conferido de verdade em 31/10/2026: {@code POST /v1beta/models/gemini-embedding-2:embedContent} e {@code :batchEmbedContents},
 * com a chave no cabeçalho {@code x-goog-api-key}; no lote, {@code model: "models/gemini-embedding-2"} em cada item; e a dimensão em
 * {@code embedContentConfig.outputDimensionality} (o campo de cima, {@code outputDimensionality}, é obsoleto). A resposta traz
 * {@code embedding.values} (um) ou {@code embeddings[].values} (o lote), de 768 números e norma 1.
 * <ul>
 *   <li>Lotes de até {@value #TAMANHO_DO_LOTE} itens (o limite de itens por lote não está publicado): num 400, o lote se divide ao
 *       meio, até achar o item que o Gemini não aceita;</li>
 *   <li>429 espera e tenta de novo (até {@value #TENTATIVAS_NO_429} vezes, respeitando o {@code Retry-After}); 500 a 504 tentam mais
 *       uma vez;</li>
 *   <li>a norma é conferida: fora de 1 ± 0,01, o vetor é normalizado; número de dimensões diferente de 768 é erro;</li>
 *   <li>a chave nunca sai em mensagem de erro, nem inteira nem em parte (a frase do provedor entra sem ela);</li>
 *   <li>nenhuma transação: quem chama não pode segurar uma durante estas chamadas.</li>
 * </ul>
 * A URL base é configurável ({@code atlas.embeddings.base-url}), para os testes usarem o servidor HTTP falso.
 */
public class EmbeddingsGemini implements ProvedorDeEmbeddings {

    static final int TAMANHO_DO_LOTE = 50;
    static final int TENTATIVAS_NO_429 = 4;
    private static final Duration LIMITE_DA_ESPERA = Duration.ofSeconds(30);
    private static final Logger log = LoggerFactory.getLogger(EmbeddingsGemini.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** O que o Gemini respondeu: o status, o corpo e o {@code Retry-After} (nulo quando não veio). */
    private record Resposta(int status, String corpo, String esperar) {
    }

    private final String baseUrl;
    private final String chave;
    private final String modelo;
    private final int dimensoes;
    private final Duration esperaNo429;
    private final RestClient http;

    /**
     * @param baseUrl     o endereço do Gemini, sem barra no fim ({@code https://generativelanguage.googleapis.com})
     * @param chave       a chave da API; nula ou em branco deixa o provedor desligado ({@link #ligado()} falso)
     * @param modelo      {@code gemini-embedding-2}
     * @param dimensoes   768
     * @param esperaNo429 quanto esperar antes de tentar de novo depois de um 429 sem {@code Retry-After}
     *                    (cresce a cada tentativa; os testes passam {@link Duration#ZERO})
     */
    public EmbeddingsGemini(String baseUrl, String chave, String modelo, int dimensoes, Duration esperaNo429) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.chave = chave == null || chave.isBlank() ? null : chave.strip();
        this.modelo = modelo;
        this.dimensoes = dimensoes;
        this.esperaNo429 = esperaNo429;
        HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(cliente);
        fabrica.setReadTimeout(Duration.ofSeconds(60));
        this.http = RestClient.builder().requestFactory(fabrica).build();
    }

    @Override
    public String modelo() {
        return modelo;
    }

    @Override
    public boolean ligado() {
        return chave != null;
    }

    @Override
    public String motivoDeDesligado() {
        return chave == null ? "Falta a chave do Gemini (GEMINI_API_KEY)." : "";
    }

    // ---------------------------------------------------------------------
    // Os trechos de um documento, em lotes
    // ---------------------------------------------------------------------

    @Override
    public List<float[]> paraTrechos(String tituloDoDocumento, List<TextoDoTrecho> trechos) {
        exigirChave();
        List<String> textos = trechos.stream().map(t -> ProvedorDeEmbeddings.textoDoTrecho(tituloDoDocumento, t)).toList();
        List<float[]> vetores = new ArrayList<>(textos.size());
        for (int inicio = 0; inicio < textos.size(); inicio += TAMANHO_DO_LOTE) {
            vetores.addAll(lote(textos.subList(inicio, Math.min(inicio + TAMANHO_DO_LOTE, textos.size()))));
        }
        return vetores;
    }

    /** Um lote; se o Gemini devolve 400 e o lote tem mais de um item, divide ao meio e junta o que cada metade devolver. */
    private List<float[]> lote(List<String> textos) {
        if (textos.isEmpty()) {
            return List.of();
        }
        String corpo = JSON.writeValueAsString(Map.of("requests", textos.stream().map(texto -> Map.of(
                "model", "models/" + modelo,
                "content", Map.of("parts", List.of(Map.of("text", texto))),
                "embedContentConfig", Map.of("outputDimensionality", dimensoes))).toList()));
        Resposta resposta = enviar(":batchEmbedContents", corpo);
        if (resposta.status() == 400 && textos.size() > 1) {
            int meio = textos.size() / 2;
            List<float[]> junto = new ArrayList<>(lote(textos.subList(0, meio)));
            junto.addAll(lote(textos.subList(meio, textos.size())));
            return junto;
        }
        JsonNode json = lerResposta(resposta);
        JsonNode embeddings = json.path("embeddings");
        if (!embeddings.isArray() || embeddings.size() != textos.size()) {
            throw new EmbeddingsIndisponivelException("O Gemini devolveu " + (embeddings.isArray() ? embeddings.size() : 0)
                    + " vetores para " + textos.size() + " textos.");
        }
        List<float[]> vetores = new ArrayList<>(textos.size());
        for (JsonNode embedding : embeddings) {
            vetores.add(vetorDe(embedding.path("values")));
        }
        return vetores;
    }

    // ---------------------------------------------------------------------
    // A pergunta
    // ---------------------------------------------------------------------

    @Override
    public float[] paraConsulta(String consulta) {
        exigirChave();
        String corpo = JSON.writeValueAsString(Map.of(
                "content", Map.of("parts", List.of(Map.of("text", ProvedorDeEmbeddings.textoDaConsulta(consulta)))),
                "embedContentConfig", Map.of("outputDimensionality", dimensoes)));
        return vetorDe(lerResposta(enviar(":embedContent", corpo)).path("embedding").path("values"));
    }

    // ---------------------------------------------------------------------
    // A chamada
    // ---------------------------------------------------------------------

    private void exigirChave() {
        if (chave == null) {
            throw new EmbeddingsIndisponivelException(motivoDeDesligado());
        }
    }

    /** Faz a chamada, esperando e tentando de novo no 429 e uma vez nos 5xx. Devolve a resposta (com o status) para quem chamou decidir. */
    private Resposta enviar(String acao, String corpo) {
        URI uri = URI.create(baseUrl + "/v1beta/models/" + modelo + acao);
        int tentativa = 0;
        while (true) {
            tentativa++;
            Resposta resposta;
            try {
                resposta = http.post().uri(uri).contentType(MediaType.APPLICATION_JSON).header("x-goog-api-key", chave).body(corpo)
                        .exchange((pedido, retorno) -> new Resposta(retorno.getStatusCode().value(),
                                new String(retorno.getBody().readAllBytes(), StandardCharsets.UTF_8), retorno.getHeaders().getFirst("Retry-After")));
            } catch (RestClientException | IllegalStateException falha) {
                // Só o nome da exceção: o texto dela pode trazer o endereço da chamada, e a chave nunca está nele, mas não custa.
                throw new EmbeddingsIndisponivelException("Não consegui falar com o Gemini (" + falha.getClass().getSimpleName() + ").");
            }
            boolean limite = resposta.status() == 429;
            boolean instavel = resposta.status() >= 500 && resposta.status() <= 504;
            if (limite && tentativa < TENTATIVAS_NO_429) {
                esperar(resposta, tentativa);
                continue;
            }
            if (instavel && tentativa < 2) {
                esperar(resposta, tentativa);
                continue;
            }
            if (limite) {
                throw new EmbeddingsIndisponivelException("O Gemini recusou por limite de uso (429) mesmo depois de " + tentativa + " tentativas.");
            }
            return resposta;
        }
    }

    private void esperar(Resposta resposta, int tentativa) {
        Duration espera = esperaNo429.multipliedBy(tentativa);
        if (resposta.esperar() != null) {
            try {
                espera = Duration.ofSeconds(Long.parseLong(resposta.esperar().strip()));
            } catch (NumberFormatException naoEhNumero) {
                // o Retry-After pode ser uma data; fica a espera padrão
            }
        }
        if (espera.compareTo(LIMITE_DA_ESPERA) > 0) {
            espera = LIMITE_DA_ESPERA;
        }
        if (espera.isZero() || espera.isNegative()) {
            return;
        }
        try {
            Thread.sleep(espera);
        } catch (InterruptedException interrompida) {
            Thread.currentThread().interrupt();
            throw new EmbeddingsIndisponivelException("A espera pelo Gemini foi interrompida.");
        }
    }

    /** Lê o JSON de uma resposta 2xx; qualquer outro status vira erro com a frase do Gemini, sem a chave. */
    private JsonNode lerResposta(Resposta resposta) {
        JsonNode json = lerJson(resposta.corpo());
        if (resposta.status() / 100 != 2) {
            throw new EmbeddingsIndisponivelException("O Gemini respondeu " + resposta.status() + explicacao(json) + ".");
        }
        if (json == null) {
            throw new EmbeddingsIndisponivelException("O Gemini respondeu " + resposta.status() + ", mas não no formato esperado.");
        }
        return json;
    }

    private static JsonNode lerJson(String texto) {
        try {
            return texto == null || texto.isBlank() ? null : JSON.readTree(texto);
        } catch (RuntimeException naoEJson) {
            return null;
        }
    }

    /** A frase de erro do Gemini ({@code error.message}), curta e sem a chave; vazia se não houver. */
    private String explicacao(JsonNode json) {
        if (json == null) {
            return "";
        }
        String frase = json.path("error").path("message").asString("");
        if (frase.isBlank()) {
            return "";
        }
        if (chave != null) {
            frase = frase.replace(chave, "[omitida]");
        }
        frase = frase.replaceAll("AIza[0-9A-Za-z_\\-]{20,}", "[omitida]").replaceAll("\\s+", " ").strip();
        return ": " + (frase.length() > 200 ? frase.substring(0, 200) + "…" : frase);
    }

    /** O vetor de um {@code values}: 768 números, normalizado se a norma fugir de 1 por mais de 0,01. */
    private float[] vetorDe(JsonNode values) {
        if (!values.isArray() || values.size() != dimensoes) {
            throw new EmbeddingsIndisponivelException("O Gemini devolveu " + (values.isArray() ? values.size() : 0)
                    + " dimensões e o Atlas espera " + dimensoes + ".");
        }
        float[] vetor = new float[values.size()];
        for (int i = 0; i < vetor.length; i++) {
            vetor[i] = (float) values.get(i).asDouble();
        }
        double norma = ProvedorDeEmbeddings.norma(vetor);
        if (Math.abs(norma - 1.0) > 0.01) {
            log.warn("Atlas: o Gemini devolveu um vetor com norma {} e o Atlas o normalizou.", norma);
            return ProvedorDeEmbeddings.normalizarSeFugir(vetor);
        }
        return vetor;
    }
}
