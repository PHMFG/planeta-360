package br.com.rbs.portal.atlas.embeddings;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *   <li>na <b>ingestão</b> ({@link #paraTrechos}), 429 espera e tenta de novo (até {@value #TENTATIVAS_NO_429} vezes, respeitando o
 *       {@code Retry-After}) e 500 a 504 tentam mais uma vez; cada tentativa tem até 60 s;</li>
 *   <li>na <b>pergunta</b> ({@link #paraConsulta}), que um turno do Atlas espera de pé, é uma tentativa só: sem espera no 429 nem nos
 *       5xx, e com um limite total curto (5 s, {@code atlas.embeddings.limite-da-consulta}) que vai até o último byte da resposta.
 *       Falhou ou estourou o limite, é {@link EmbeddingsIndisponivelException} e a busca segue só com a lista textual (modo
 *       degradado). Em 195 buscas reais, 5% levaram uns 31 s só no embedding, por esperar o 429 como a ingestão espera;</li>
 *   <li>a norma é conferida: fora de 1 ± 0,01, o vetor é normalizado; número de dimensões diferente de 768, valor que não é um número
 *       finito ({@code null}, texto, {@code 1e40}) e vetor só de zeros são erro ({@link EmbeddingsIndisponivelException}), porque o
 *       cosseno do pgvector daria NaN;</li>
 *   <li>a chave nunca sai em mensagem de erro, nem inteira nem em parte (a frase do provedor entra sem ela);</li>
 *   <li>nenhuma transação: quem chama não pode segurar uma durante estas chamadas.</li>
 * </ul>
 * A URL base é configurável ({@code atlas.embeddings.base-url}), para os testes usarem o servidor HTTP falso.
 */
public class EmbeddingsGemini implements ProvedorDeEmbeddings {

    static final int TAMANHO_DO_LOTE = 50;
    static final int TENTATIVAS_NO_429 = 4;
    private static final String VETOR_INVALIDO = "O Gemini devolveu um vetor inválido";
    private static final Duration LIMITE_DA_ESPERA = Duration.ofSeconds(30);
    private static final Duration LIMITE_DA_INGESTAO = Duration.ofSeconds(60);
    /** O limite total da pergunta quando ninguém configura outro. */
    public static final Duration LIMITE_PADRAO_DA_CONSULTA = Duration.ofSeconds(5);
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
    private final Duration limiteDaConsulta;
    private final HttpClient http;

    /**
     * @param baseUrl     o endereço do Gemini, sem barra no fim ({@code https://generativelanguage.googleapis.com})
     * @param chave       a chave da API; nula ou em branco deixa o provedor desligado ({@link #ligado()} falso)
     * @param modelo      {@code gemini-embedding-2}
     * @param dimensoes   768
     * @param esperaNo429 quanto esperar antes de tentar de novo depois de um 429 sem {@code Retry-After}
     *                    (cresce a cada tentativa; os testes passam {@link Duration#ZERO}); só a ingestão espera
     */
    public EmbeddingsGemini(String baseUrl, String chave, String modelo, int dimensoes, Duration esperaNo429) {
        this(baseUrl, chave, modelo, dimensoes, esperaNo429, LIMITE_PADRAO_DA_CONSULTA);
    }

    /**
     * @param limiteDaConsulta o tempo total que a pergunta ({@link #paraConsulta}) tem para o Gemini responder, em uma tentativa só;
     *                         nulo, zero ou negativo vale {@link #LIMITE_PADRAO_DA_CONSULTA}
     */
    public EmbeddingsGemini(String baseUrl, String chave, String modelo, int dimensoes, Duration esperaNo429, Duration limiteDaConsulta) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.chave = chave == null || chave.isBlank() ? null : chave.strip();
        this.modelo = modelo;
        this.dimensoes = dimensoes;
        this.esperaNo429 = esperaNo429;
        this.limiteDaConsulta = limiteDaConsulta == null || limiteDaConsulta.isZero() || limiteDaConsulta.isNegative()
                ? LIMITE_PADRAO_DA_CONSULTA : limiteDaConsulta;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
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

    /**
     * Uma tentativa só, com o limite total da pergunta: um 429 ou um 5xx não esperam nem tentam de novo (a busca segue sem o vetor), e
     * o servidor que não responde a tempo é erro, como qualquer falha do provedor.
     */
    @Override
    public float[] paraConsulta(String consulta) {
        exigirChave();
        String corpo = JSON.writeValueAsString(Map.of(
                "content", Map.of("parts", List.of(Map.of("text", ProvedorDeEmbeddings.textoDaConsulta(consulta)))),
                "embedContentConfig", Map.of("outputDimensionality", dimensoes)));
        Resposta resposta = umaChamada(":embedContent", corpo, limiteDaConsulta);
        if (resposta.status() == 429) {
            throw new EmbeddingsIndisponivelException("O Gemini recusou por limite de uso (429).");
        }
        return vetorDe(lerResposta(resposta).path("embedding").path("values"));
    }

    // ---------------------------------------------------------------------
    // A chamada
    // ---------------------------------------------------------------------

    private void exigirChave() {
        if (chave == null) {
            throw new EmbeddingsIndisponivelException(motivoDeDesligado());
        }
    }

    /**
     * A chamada da ingestão: espera e tenta de novo no 429 e uma vez nos 5xx. Devolve a resposta (com o status) para quem chamou decidir.
     */
    private Resposta enviar(String acao, String corpo) {
        int tentativa = 0;
        while (true) {
            tentativa++;
            Resposta resposta = umaChamada(acao, corpo, LIMITE_DA_INGESTAO);
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

    /**
     * Uma chamada, sem tentar de novo: o tempo {@code limite} vale para a resposta inteira (cabeçalhos e corpo), e quando estoura a
     * chamada é cancelada. Falha de rede e tempo esgotado viram {@link EmbeddingsIndisponivelException} com o nome da causa, nunca o
     * texto dela (que pode trazer o endereço) e nunca a chave.
     */
    private Resposta umaChamada(String acao, String corpo, Duration limite) {
        HttpRequest pedido = HttpRequest.newBuilder(URI.create(baseUrl + "/v1beta/models/" + modelo + acao)).timeout(limite)
                .header("Content-Type", "application/json").header("x-goog-api-key", chave)
                .POST(HttpRequest.BodyPublishers.ofString(corpo)).build();
        CompletableFuture<HttpResponse<byte[]>> envio = http.sendAsync(pedido, HttpResponse.BodyHandlers.ofByteArray());
        try {
            HttpResponse<byte[]> retorno = envio.get(Math.max(1, limite.toMillis()), TimeUnit.MILLISECONDS);
            return new Resposta(retorno.statusCode(), new String(retorno.body(), StandardCharsets.UTF_8),
                    retorno.headers().firstValue("Retry-After").orElse(null));
        } catch (TimeoutException estourou) {
            envio.cancel(true);
            throw new EmbeddingsIndisponivelException(naoRespondeu(limite));
        } catch (ExecutionException falha) {
            Throwable causa = falha.getCause() == null ? falha : falha.getCause();
            if (causa instanceof HttpTimeoutException) {
                throw new EmbeddingsIndisponivelException(naoRespondeu(limite));
            }
            // Só o nome da exceção: o texto dela pode trazer o endereço da chamada, e a chave nunca está nele, mas não custa.
            throw new EmbeddingsIndisponivelException("Não consegui falar com o Gemini (" + causa.getClass().getSimpleName() + ").");
        } catch (InterruptedException interrompida) {
            envio.cancel(true);
            Thread.currentThread().interrupt();
            throw new EmbeddingsIndisponivelException("A espera pelo Gemini foi interrompida.");
        }
    }

    private static String naoRespondeu(Duration limite) {
        return "O Gemini não respondeu em " + (limite.toMillis() % 1000 == 0 ? limite.toSeconds() + " s" : limite.toMillis() + " ms") + ".";
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

    /** O vetor de um {@code values}, conferido como na indexação: veja {@link #vetorDe(JsonNode, int)}. */
    private float[] vetorDe(JsonNode values) {
        return vetorDe(values, dimensoes);
    }

    /**
     * O vetor de um {@code values}: {@code dimensoes} números, todos finitos (como float), com norma maior que zero, e normalizado se a
     * norma fugir de 1 por mais de 0,01. Valor que não é número ({@code null}, texto), número que estoura o float ({@code 1e40}) e vetor
     * só de zeros são erro, e não um vetor: um vetor de zeros dá NaN no cosseno do pgvector, e um {@code null} contaria como 0. É a
     * mesma conferência da indexação e do teste do provedor (rota 53, que chama este método).
     *
     * @throws EmbeddingsIndisponivelException se o vetor não serve
     */
    public static float[] vetorDe(JsonNode values, int dimensoes) {
        if (!values.isArray() || values.size() != dimensoes) {
            throw new EmbeddingsIndisponivelException("O Gemini devolveu " + (values.isArray() ? values.size() : 0)
                    + " dimensões e o Atlas espera " + dimensoes + ".");
        }
        float[] vetor = new float[values.size()];
        for (int i = 0; i < vetor.length; i++) {
            JsonNode valor = values.get(i);
            vetor[i] = valor.isNumber() ? (float) valor.doubleValue() : Float.NaN;
            if (!Float.isFinite(vetor[i])) {
                throw new EmbeddingsIndisponivelException(VETOR_INVALIDO + ": a posição " + i + " não é um número finito.");
            }
        }
        double norma = ProvedorDeEmbeddings.norma(vetor);
        if (!Double.isFinite(norma) || norma == 0.0) {
            throw new EmbeddingsIndisponivelException(VETOR_INVALIDO + ": todos os valores são zero.");
        }
        if (Math.abs(norma - 1.0) > 0.01) {
            log.warn("Atlas: o Gemini devolveu um vetor com norma {} e o Atlas o normalizou.", norma);
            return ProvedorDeEmbeddings.normalizarSeFugir(vetor);
        }
        return vetor;
    }
}
