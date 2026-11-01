package br.com.rbs.portal.atlas.embeddings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.atlas.diagnostico.ServidorHttpFalso;
import br.com.rbs.portal.atlas.diagnostico.ServidorHttpFalso.Recebida;
import br.com.rbs.portal.atlas.embeddings.ProvedorDeEmbeddings.TextoDoTrecho;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * [ATLAS] O cliente do Gemini contra o servidor HTTP falso (nada sai da máquina e nenhuma chave de verdade entra): a URL, o cabeçalho
 * da chave, os prefixos de tarefa, a dimensão no lugar certo, os lotes de 50, o lote que se divide ao meio no 400, a espera no 429,
 * a norma e o erro que nunca leva a chave.
 */
class EmbeddingsGeminiTest {

    private static final String CHAVE = "AIzaSyChaveDeTesteQueNaoEhDeVerdade12345";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ServidorHttpFalso servidor;
    private EmbeddingsGemini gemini;

    @BeforeEach
    void iniciar() {
        servidor = ServidorHttpFalso.iniciar();
        gemini = new EmbeddingsGemini(servidor.urlBase(), CHAVE, "gemini-embedding-2", 768, Duration.ZERO);
    }

    @AfterEach
    void parar() {
        servidor.close();
    }

    /** Um vetor de 768 valores iguais; com {@code unitario} a norma dá 1. */
    private static String valores(boolean unitario, int dimensoes) {
        double cada = unitario ? 1.0 / Math.sqrt(dimensoes) : 0.5;
        return IntStream.range(0, dimensoes).mapToObj(i -> Double.toString(cada)).collect(Collectors.joining(",", "[", "]"));
    }

    private static String respostaDoLote(int quantos) {
        return "{\"embeddings\":[" + IntStream.range(0, quantos).mapToObj(i -> "{\"values\":" + valores(true, 768) + "}")
                .collect(Collectors.joining(",")) + "]}";
    }

    /** Um vetor unitário que tem 1 só na posição {@code k}: cada trecho do teste tem o seu, para a ordem da resposta aparecer. */
    private static String umNaPosicao(int k) {
        return IntStream.range(0, 768).mapToObj(i -> i == k ? "1.0" : "0.0").collect(Collectors.joining(",", "[", "]"));
    }

    /** Um lote cujo item {@code j} tem o 1 na posição {@code primeiro + j}. */
    private static String respostaDoLoteComVetoresDiferentes(int primeiro, int quantos) {
        return "{\"embeddings\":[" + IntStream.range(0, quantos).mapToObj(j -> "{\"values\":" + umNaPosicao(primeiro + j) + "}")
                .collect(Collectors.joining(",")) + "]}";
    }

    /** A posição do 1 de um vetor unitário feito por {@link #umNaPosicao(int)}. */
    private static int posicaoDoUm(float[] vetor) {
        for (int i = 0; i < vetor.length; i++) {
            if (vetor[i] == 1.0f) {
                return i;
            }
        }
        throw new AssertionError("o vetor não tem o 1");
    }

    private static List<TextoDoTrecho> trechos(int quantos) {
        return IntStream.rangeClosed(1, quantos).mapToObj(i -> new TextoDoTrecho("4 Energia elétrica", "Texto do trecho " + i + ".")).toList();
    }

    private static JsonNode corpo(Recebida recebida) {
        return JSON.readTree(recebida.corpo());
    }

    @Test
    void aPerguntaVaiParaOEmbedContentComAChaveNoCabecalhoEOPrefixoDeBuscaEADimensaoNoLugarCerto() {
        servidor.responder(200, "{\"embedding\":{\"values\":" + valores(true, 768) + "}}");

        float[] vetor = gemini.paraConsulta("Até quando informo os geradores?");

        Recebida recebida = servidor.ultima();
        assertThat(recebida.metodo()).isEqualTo("POST");
        assertThat(recebida.caminho()).isEqualTo("/v1beta/models/gemini-embedding-2:embedContent");
        assertThat(recebida.cabecalho("x-goog-api-key")).containsExactly(CHAVE);
        assertThat(recebida.cabecalho("content-type").get(0)).startsWith("application/json");
        JsonNode json = corpo(recebida);
        assertThat(json.path("content").path("parts").get(0).path("text").asString())
                .isEqualTo("task: search result | query: Até quando informo os geradores?");
        assertThat(json.path("embedContentConfig").path("outputDimensionality").asInt()).isEqualTo(768);
        assertThat(json.has("outputDimensionality")).as("o campo de cima é obsoleto").isFalse();
        assertThat(json.has("task_type")).as("o modelo não aceita task_type").isFalse();
        assertThat(vetor).hasSize(768);
        assertThat(ProvedorDeEmbeddings.norma(vetor)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    void osTrechosVaoParaOBatchEmbedContentComOModeloEmCadaItemEOPrefixoDeTituloESecao() {
        servidor.responder(200, respostaDoLote(2));

        List<float[]> vetores = gemini.paraTrechos("Manual de Operações 2027", List.of(
                new TextoDoTrecho("4 Energia elétrica", "A tarifa é de R$ 312,00 por kVA."), new TextoDoTrecho(null, "Texto sem seção.")));

        assertThat(vetores).hasSize(2).allSatisfy(v -> assertThat(v).hasSize(768));
        Recebida recebida = servidor.ultima();
        assertThat(recebida.caminho()).isEqualTo("/v1beta/models/gemini-embedding-2:batchEmbedContents");
        assertThat(recebida.cabecalho("x-goog-api-key")).containsExactly(CHAVE);
        JsonNode itens = corpo(recebida).path("requests");
        assertThat(itens.size()).isEqualTo(2);
        for (JsonNode item : itens) {
            assertThat(item.path("model").asString()).isEqualTo("models/gemini-embedding-2");
            assertThat(item.path("embedContentConfig").path("outputDimensionality").asInt()).isEqualTo(768);
        }
        assertThat(itens.get(0).path("content").path("parts").get(0).path("text").asString())
                .isEqualTo("title: Manual de Operações 2027 | text: 4 Energia elétrica\nA tarifa é de R$ 312,00 por kVA.");
        assertThat(itens.get(1).path("content").path("parts").get(0).path("text").asString())
                .isEqualTo("title: Manual de Operações 2027 | text: Texto sem seção.");
    }

    @Test
    void maisDe50TrechosVaoEmLotesDeAte50EVoltamNaOrdem() {
        servidor.responder(200, respostaDoLoteComVetoresDiferentes(0, 50));
        servidor.responder(200, respostaDoLoteComVetoresDiferentes(50, 50));
        servidor.responder(200, respostaDoLoteComVetoresDiferentes(100, 20));

        List<float[]> vetores = gemini.paraTrechos("Titulo", trechos(120));

        assertThat(vetores).hasSize(120);
        assertThat(vetores.stream().mapToInt(EmbeddingsGeminiTest::posicaoDoUm)).as("o vetor k é o do trecho k, lote a lote")
                .containsExactlyElementsOf(IntStream.range(0, 120).boxed().toList());
        assertThat(servidor.recebidas()).hasSize(3);
        assertThat(servidor.recebidas()).extracting(r -> corpo(r).path("requests").size()).containsExactly(50, 50, 20);
        assertThat(corpo(servidor.recebidas().get(2)).path("requests").get(19).path("content").path("parts").get(0).path("text").asString())
                .endsWith("Texto do trecho 120.");
    }

    @Test
    void num400DoLoteDivideAoMeioEJuntaAsMetades() {
        servidor.responder(400, "{\"error\":{\"message\":\"Request payload size exceeds the limit\"}}");
        servidor.responder(200, respostaDoLoteComVetoresDiferentes(0, 2));
        servidor.responder(200, respostaDoLoteComVetoresDiferentes(2, 2));

        List<float[]> vetores = gemini.paraTrechos("Titulo", trechos(4));

        assertThat(vetores).hasSize(4);
        assertThat(vetores.stream().mapToInt(EmbeddingsGeminiTest::posicaoDoUm)).as("a primeira metade vem antes da segunda")
                .containsExactly(0, 1, 2, 3);
        assertThat(servidor.recebidas()).extracting(r -> corpo(r).path("requests").size()).containsExactly(4, 2, 2);
        assertThat(corpo(servidor.recebidas().get(1)).path("requests").get(0).path("content").path("parts").get(0).path("text").asString())
                .endsWith("Texto do trecho 1.");
        assertThat(corpo(servidor.recebidas().get(2)).path("requests").get(0).path("content").path("parts").get(0).path("text").asString())
                .endsWith("Texto do trecho 3.");
    }

    @Test
    void um400NoItemSozinhoEErroSemLoopInfinito() {
        servidor.responder(400, "{\"error\":{\"message\":\"Texto inválido\"}}");

        assertThatThrownBy(() -> gemini.paraTrechos("Titulo", trechos(1))).isInstanceOf(EmbeddingsIndisponivelException.class)
                .hasMessage("O Gemini respondeu 400: Texto inválido.");
        assertThat(servidor.recebidas()).hasSize(1);
    }

    @Test
    void num429EsperaETentaDeNovo() {
        servidor.responder(429, "{\"error\":{\"message\":\"Resource has been exhausted\"}}");
        servidor.responder(429, "{\"error\":{\"message\":\"Resource has been exhausted\"}}");
        servidor.responder(200, respostaDoLote(1));

        assertThat(gemini.paraTrechos("Titulo", trechos(1))).hasSize(1);
        assertThat(servidor.recebidas()).hasSize(3);
    }

    @Test
    void seO429NaoPassaDepoisDe6TentativasNaIngestaoOErroDizOLimiteDeUso() {
        for (int i = 0; i < 8; i++) {
            servidor.responder(429, "{\"error\":{\"message\":\"Resource has been exhausted\"}}");
        }

        assertThatThrownBy(() -> gemini.paraTrechos("Titulo", trechos(1))).isInstanceOf(EmbeddingsIndisponivelException.class)
                .hasMessage("O Gemini recusou por limite de uso (429) mesmo depois de 6 tentativas.");
        assertThat(servidor.recebidas()).hasSize(6);
    }

    @Test
    void erroDeServidorNaIngestaoTentaUmaVezMais() {
        servidor.responder(503, "{\"error\":{\"message\":\"Indisponível\"}}");
        servidor.responder(200, respostaDoLote(1));

        assertThat(gemini.paraTrechos("Titulo", trechos(1))).hasSize(1);
        assertThat(servidor.recebidas()).hasSize(2);
    }

    @Test
    void naIngestaoORetryAfterDoGeminiAindaValeEOTempoDeEsperaAindaEFeito() {
        servidor.responderComCabecalho(429, "{\"error\":{\"message\":\"Resource has been exhausted\"}}", "Retry-After", "1");
        servidor.responder(200, respostaDoLote(1));

        long inicio = System.nanoTime();
        assertThat(comEsperasCurtas().paraTrechos("Titulo", trechos(1))).hasSize(1);
        Duration gasto = Duration.ofNanos(System.nanoTime() - inicio);

        assertThat(servidor.recebidas()).hasSize(2);
        assertThat(gasto).as("a ingestão espera o Retry-After de 1 s, mais a folga").isGreaterThanOrEqualTo(Duration.ofMillis(1000));
    }

    // ---------------------------------------------------------------------
    // M4: na ingestão, o 429 diz quanto esperar (retryDelay do corpo); espera longa é a cota do dia e falha na hora
    // ---------------------------------------------------------------------

    private static final String COTA_DO_DIA = "A cota do dia do Gemini acabou; tente de novo depois que ela renovar.";

    /** O corpo real de um 429 do Gemini gratuito (forma conferida em 01/11/2026): a violação da cota e o RetryInfo. */
    private static String limiteComRetryDelay(String retryDelayEmJson) {
        return "{\"error\": {\"code\": 429, \"message\": \"You exceeded your current quota...\", \"status\": \"RESOURCE_EXHAUSTED\","
                + " \"details\": [ {\"@type\": \"type.googleapis.com/google.rpc.QuotaFailure\","
                + " \"violations\": [{\"quotaMetric\": \"generativelanguage.googleapis.com/embed_content_free_tier_requests\","
                + " \"quotaId\": \"EmbedContentRequestsPerDayPerUserPerProjectPerModel-FreeTier\", \"quotaValue\": \"1000\"}]},"
                + " {\"@type\": \"type.googleapis.com/google.rpc.RetryInfo\", \"retryDelay\": " + retryDelayEmJson + "} ]}}";
    }

    private static String limiteComRetryDelayEmSegundos(String segundos) {
        return limiteComRetryDelay("\"" + segundos + "\"");
    }

    /** Um cliente de esperas curtas: sem espera configurada, folga de 50 ms e espera máxima de 2 s (o teste não dorme). */
    private EmbeddingsGemini comEsperasCurtas() {
        return comEsperasCurtas(Duration.ZERO, Duration.ofSeconds(2));
    }

    private EmbeddingsGemini comEsperasCurtas(Duration esperaNo429, Duration esperaMaxima) {
        return new EmbeddingsGemini(servidor.urlBase(), CHAVE, "gemini-embedding-2", 768, esperaNo429, Duration.ofSeconds(5),
                esperaMaxima, Duration.ofMillis(50));
    }

    @Test
    void oRetryDelayCurtoDoCorpoEsperaEssePoucoMaisAFolgaETentaDeNovo() {
        servidor.responder(429, limiteComRetryDelayEmSegundos("0.4s"));
        servidor.responder(200, respostaDoLote(1));
        EmbeddingsGemini cliente = new EmbeddingsGemini(servidor.urlBase(), CHAVE, "gemini-embedding-2", 768, Duration.ZERO,
                Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofMillis(100));

        long inicio = System.nanoTime();
        assertThat(cliente.paraTrechos("Titulo", trechos(1))).hasSize(1);
        Duration gasto = Duration.ofNanos(System.nanoTime() - inicio);

        assertThat(servidor.recebidas()).hasSize(2);
        assertThat(gasto).as("0,4 s do retryDelay mais 0,1 s de folga, e não a espera configurada (zero)")
                .isGreaterThanOrEqualTo(Duration.ofMillis(490)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void oRetryDelayDoDiaFalhaNaHoraComUmaChamadaSoEAMensagemClaraSemOCorpoDoProvedor() {
        servidor.responder(429, limiteComRetryDelayEmSegundos("2209s"));
        servidor.responder(200, respostaDoLote(1));

        long inicio = System.nanoTime();
        assertThatThrownBy(() -> gemini.paraTrechos("Titulo", trechos(1))).isInstanceOf(EmbeddingsIndisponivelException.class)
                .hasMessage(COTA_DO_DIA);
        Duration gasto = Duration.ofNanos(System.nanoTime() - inicio);

        assertThat(servidor.recebidas()).as("uma chamada só: não insiste à toa").hasSize(1);
        assertThat(gasto).as("sem esperar").isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void aCotaDoDiaVaiParaOErroSemNadaDoCorpoDoProvedorNemAChave() {
        servidor.responder(429, "{\"error\":{\"message\":\"Quota exceeded for key " + CHAVE + "\",\"details\":[{\"@type\":"
                + "\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"2209s\"}]}}");

        assertThatThrownBy(() -> gemini.paraTrechos("Titulo", trechos(1))).isInstanceOf(EmbeddingsIndisponivelException.class)
                .hasMessage(COTA_DO_DIA).hasMessageNotContaining(CHAVE).hasMessageNotContaining("AIza").hasMessageNotContaining("Quota exceeded");
    }

    @Test
    void passaDe90sPorPadraoEACotaDoDia() {
        // até 90 s ainda espera (91 s com a folga), e este teste não dorme tudo isso: confere só o lado de lá do corte
        servidor.responder(429, limiteComRetryDelayEmSegundos("91s"));
        servidor.responder(429, limiteComRetryDelayEmSegundos("90.001s"));

        assertThatThrownBy(() -> gemini.paraTrechos("Titulo", trechos(1))).hasMessage(COTA_DO_DIA);
        assertThatThrownBy(() -> gemini.paraTrechos("Titulo", trechos(1))).hasMessage(COTA_DO_DIA);
        assertThat(servidor.recebidas()).hasSize(2);
    }

    @Test
    void aEsperaMaximaEConfiguravelEIgualAEsperaMaximaAindaEspera() {
        EmbeddingsGemini cliente = comEsperasCurtas(Duration.ZERO, Duration.ofMillis(300));
        servidor.responder(429, limiteComRetryDelayEmSegundos("0.3s"));
        servidor.responder(200, respostaDoLote(1));
        assertThat(cliente.paraTrechos("Titulo", trechos(1))).hasSize(1);
        assertThat(servidor.recebidas()).as("0,3 s não passa de 0,3 s: espera").hasSize(2);

        servidor.limpar();
        servidor.responder(429, limiteComRetryDelayEmSegundos("0.4s"));
        servidor.responder(200, respostaDoLote(1));
        assertThatThrownBy(() -> cliente.paraTrechos("Titulo", trechos(1))).hasMessage(COTA_DO_DIA);
        assertThat(servidor.recebidas()).as("0,4 s passa de 0,3 s: é a cota do dia").hasSize(1);
    }

    @Test
    void esperaCurtaNaPrimeiraEDiaNaSegundaFalhaNaSegundaSemMaisEspera() {
        servidor.responder(429, limiteComRetryDelayEmSegundos("0.1s"));
        servidor.responder(429, limiteComRetryDelayEmSegundos("2209s"));
        servidor.responder(200, respostaDoLote(1));

        assertThatThrownBy(() -> comEsperasCurtas().paraTrechos("Titulo", trechos(1))).hasMessage(COTA_DO_DIA);
        assertThat(servidor.recebidas()).hasSize(2);
    }

    @Test
    void asSeisTentativasSaoPorLoteERetryDelayCurtoNaoMudaOTotal() {
        for (int i = 0; i < 8; i++) {
            servidor.responder(429, limiteComRetryDelayEmSegundos("0s"));
        }

        assertThatThrownBy(() -> comEsperasCurtas().paraTrechos("Titulo", trechos(1))).isInstanceOf(EmbeddingsIndisponivelException.class)
                .hasMessage("O Gemini recusou por limite de uso (429) mesmo depois de 6 tentativas.").hasMessageNotContaining(CHAVE);
        assertThat(servidor.recebidas()).hasSize(6);
    }

    @Test
    void cadaLoteTemAsSuasSeisTentativas() {
        // o 1º lote leva cinco 429 e passa na 6ª; o 2º também: nenhum esgota as suas
        for (int i = 0; i < 5; i++) {
            servidor.responder(429, limiteComRetryDelayEmSegundos("0s"));
        }
        servidor.responder(200, respostaDoLoteComVetoresDiferentes(0, 50));
        for (int i = 0; i < 5; i++) {
            servidor.responder(429, limiteComRetryDelayEmSegundos("0s"));
        }
        servidor.responder(200, respostaDoLoteComVetoresDiferentes(50, 10));

        assertThat(comEsperasCurtas().paraTrechos("Titulo", trechos(60))).hasSize(60);
        assertThat(servidor.recebidas()).hasSize(12);
    }

    @Test
    void retryDelayQueNaoPrestaCaiNaEsperaConfiguradaSemExcecaoDeParse() {
        List<String> corpos = List.of(
                "isto não é json",
                "",
                "[1,2,3]",
                "null",
                "{\"error\":42}",
                "{\"error\":{\"details\":\"x\"}}",
                "{\"error\":{\"details\":[null,1,\"x\",{\"retryDelay\":null}]}}",
                limiteComRetryDelay("\"abc\""),
                limiteComRetryDelay("\"-5s\""),
                limiteComRetryDelay("\"-0.5s\""),
                limiteComRetryDelay("\"5\""),
                limiteComRetryDelay("\"1e3s\""),
                limiteComRetryDelay("\"s\""),
                limiteComRetryDelay("\"PT5S\""),
                limiteComRetryDelay("\"\""),
                limiteComRetryDelay("5"),
                limiteComRetryDelay("-5"),
                limiteComRetryDelay("null"),
                limiteComRetryDelay("{\"seconds\":5}"));
        EmbeddingsGemini cliente = comEsperasCurtas(Duration.ofMillis(120), Duration.ofSeconds(2));

        for (String corpo : corpos) {
            servidor.limpar();
            servidor.responder(429, corpo);
            servidor.responder(200, respostaDoLote(1));

            long inicio = System.nanoTime();
            assertThat(cliente.paraTrechos("Titulo", trechos(1))).as(corpo).hasSize(1);
            Duration gasto = Duration.ofNanos(System.nanoTime() - inicio);

            assertThat(servidor.recebidas()).as(corpo).hasSize(2);
            assertThat(gasto).as("a espera configurada de 120 ms, sem folga: " + corpo).isGreaterThanOrEqualTo(Duration.ofMillis(110));
        }
    }

    @Test
    void semRetryDelayNemRetryAfterVaiAEsperaConfiguradaQueCresceATentativa() {
        servidor.responder(429, LIMITE_DE_USO);
        servidor.responder(429, LIMITE_DE_USO);
        servidor.responder(200, respostaDoLote(1));
        EmbeddingsGemini cliente = comEsperasCurtas(Duration.ofMillis(100), Duration.ofSeconds(2));

        long inicio = System.nanoTime();
        assertThat(cliente.paraTrechos("Titulo", trechos(1))).hasSize(1);
        Duration gasto = Duration.ofNanos(System.nanoTime() - inicio);

        assertThat(gasto).as("100 ms na primeira e 200 ms na segunda").isGreaterThanOrEqualTo(Duration.ofMillis(290));
    }

    @Test
    void oRetryAfterEAReservaQuandoOCorpoNaoTemRetryDelay() {
        servidor.responderComCabecalho(429, "isto não é json", "Retry-After", "1");
        servidor.responder(200, respostaDoLote(1));

        long inicio = System.nanoTime();
        assertThat(comEsperasCurtas().paraTrechos("Titulo", trechos(1))).hasSize(1);
        Duration gasto = Duration.ofNanos(System.nanoTime() - inicio);

        assertThat(gasto).isGreaterThanOrEqualTo(Duration.ofMillis(1000));
    }

    @Test
    void umRetryAfterDeUmaHoraTambemECotaDoDiaEFalhaNaHora() {
        servidor.responderComCabecalho(429, "{\"error\":{\"message\":\"Resource has been exhausted\"}}", "Retry-After", "3600");
        servidor.responder(200, respostaDoLote(1));

        long inicio = System.nanoTime();
        assertThatThrownBy(() -> gemini.paraTrechos("Titulo", trechos(1))).hasMessage(COTA_DO_DIA);
        Duration gasto = Duration.ofNanos(System.nanoTime() - inicio);

        assertThat(servidor.recebidas()).hasSize(1);
        assertThat(gasto).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void oRetryDelayDoCorpoVenceORetryAfter() {
        servidor.responderComCabecalho(429, limiteComRetryDelayEmSegundos("0.1s"), "Retry-After", "3600");
        servidor.responder(200, respostaDoLote(1));

        assertThat(comEsperasCurtas().paraTrechos("Titulo", trechos(1))).hasSize(1);
        assertThat(servidor.recebidas()).hasSize(2);
    }

    @Test
    void umRetryAfterQueEUmaDataCaiNaEsperaConfigurada() {
        servidor.responderComCabecalho(429, "isto não é json", "Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT");
        servidor.responder(200, respostaDoLote(1));

        assertThat(comEsperasCurtas().paraTrechos("Titulo", trechos(1))).hasSize(1);
        assertThat(servidor.recebidas()).hasSize(2);
    }

    @Test
    void aMensagemDeLimiteDeUsoDaIngestaoNuncaTemAChave() {
        for (int i = 0; i < 6; i++) {
            servidor.responder(429, "{\"error\":{\"message\":\"Quota exceeded for key " + CHAVE + "\",\"details\":[{\"retryDelay\":\"0s\"}]}}");
        }

        assertThatThrownBy(() -> comEsperasCurtas().paraTrechos("Titulo", trechos(1))).isInstanceOf(EmbeddingsIndisponivelException.class)
                .hasMessage("O Gemini recusou por limite de uso (429) mesmo depois de 6 tentativas.")
                .hasMessageNotContaining(CHAVE).hasMessageNotContaining("AIza");
    }

    // ---------------------------------------------------------------------
    // M4: o embedding da pergunta não herda a espera da ingestão (uma tentativa, limite total curto)
    // ---------------------------------------------------------------------

    private static final String LIMITE_DE_USO = "{\"error\":{\"message\":\"Resource has been exhausted\"}}";

    /** Um cliente cujas esperas da ingestão são longas (2 s por tentativa): se a consulta esperasse, o teste notaria. */
    private EmbeddingsGemini comEsperaLonga(Duration limiteDaConsulta) {
        return new EmbeddingsGemini(servidor.urlBase(), CHAVE, "gemini-embedding-2", 768, Duration.ofSeconds(2), limiteDaConsulta,
                EmbeddingsGemini.ESPERA_MAXIMA_PADRAO, EmbeddingsGemini.FOLGA_PADRAO);
    }

    @Test
    void o429NaPerguntaFalhaNaHoraSemSegundaChamadaESemEspera() {
        for (int i = 0; i < 4; i++) {
            servidor.responder(429, LIMITE_DE_USO);
        }
        EmbeddingsGemini cliente = comEsperaLonga(Duration.ofSeconds(5));

        long inicio = System.nanoTime();
        assertThatThrownBy(() -> cliente.paraConsulta("pergunta")).isInstanceOf(EmbeddingsIndisponivelException.class)
                .hasMessageContaining("429").hasMessageNotContaining(CHAVE);
        Duration gasto = Duration.ofNanos(System.nanoTime() - inicio);

        assertThat(servidor.recebidas()).as("uma chamada só").hasSize(1);
        assertThat(gasto).as("nenhuma espera (a da ingestão seria de 2 s, 4 s e 6 s)").isLessThan(Duration.ofMillis(1500));
    }

    @Test
    void o429NaPerguntaNaoEsperaNemORetryAfterDoGemini() {
        servidor.responderComCabecalho(429, LIMITE_DE_USO, "Retry-After", "20");
        servidor.responder(200, "{\"embedding\":{\"values\":" + valores(true, 768) + "}}");
        EmbeddingsGemini cliente = comEsperaLonga(Duration.ofSeconds(5));

        long inicio = System.nanoTime();
        assertThatThrownBy(() -> cliente.paraConsulta("pergunta")).isInstanceOf(EmbeddingsIndisponivelException.class);
        Duration gasto = Duration.ofNanos(System.nanoTime() - inicio);

        assertThat(servidor.recebidas()).hasSize(1);
        assertThat(gasto).isLessThan(Duration.ofMillis(1500));
    }

    @Test
    void aPerguntaNaoEsperaNemORetryDelayCurtoDoCorpoNemOLongo() {
        for (String retryDelay : List.of("0.3s", "1s", "2209s")) {
            servidor.limpar();
            servidor.responder(429, limiteComRetryDelayEmSegundos(retryDelay));
            servidor.responder(200, "{\"embedding\":{\"values\":" + valores(true, 768) + "}}");
            EmbeddingsGemini cliente = comEsperaLonga(Duration.ofSeconds(5));

            long inicio = System.nanoTime();
            assertThatThrownBy(() -> cliente.paraConsulta("pergunta")).as(retryDelay).isInstanceOf(EmbeddingsIndisponivelException.class)
                    .hasMessage("O Gemini recusou por limite de uso (429).");
            Duration gasto = Duration.ofNanos(System.nanoTime() - inicio);

            assertThat(servidor.recebidas()).as(retryDelay + ": uma chamada só").hasSize(1);
            assertThat(gasto).as(retryDelay + ": nenhuma espera").isLessThan(Duration.ofMillis(1500));
        }
    }

    @Test
    void oErroDeServidorNaPerguntaFalhaNaHoraSemTentarDeNovo() {
        for (int status : List.of(500, 502, 503, 504)) {
            servidor.limpar();
            servidor.responder(status, "{\"error\":{\"message\":\"Indisponível\"}}");
            servidor.responder(200, "{\"embedding\":{\"values\":" + valores(true, 768) + "}}");
            EmbeddingsGemini cliente = comEsperaLonga(Duration.ofSeconds(5));

            long inicio = System.nanoTime();
            assertThatThrownBy(() -> cliente.paraConsulta("pergunta")).as("status " + status)
                    .isInstanceOf(EmbeddingsIndisponivelException.class).hasMessageContaining(String.valueOf(status));
            Duration gasto = Duration.ofNanos(System.nanoTime() - inicio);

            assertThat(servidor.recebidas()).as("status " + status + ": uma chamada só").hasSize(1);
            assertThat(gasto).as("status " + status).isLessThan(Duration.ofMillis(1500));
        }
    }

    @Test
    void aPerguntaQueTemRespostaBoaDeUmaChamadaSoPassaNormalmente() {
        servidor.responder(200, "{\"embedding\":{\"values\":" + valores(true, 768) + "}}");

        assertThat(comEsperaLonga(Duration.ofSeconds(5)).paraConsulta("pergunta")).hasSize(768);
        assertThat(servidor.recebidas()).hasSize(1);
    }

    @Test
    void servidorLentoEstouraOLimiteDaPerguntaEAMensagemNaoTemAChaveNemOEndereco() {
        servidor.responderDepoisDe(Duration.ofSeconds(3), 200, "{\"embedding\":{\"values\":" + valores(true, 768) + "}}");
        EmbeddingsGemini cliente = comEsperaLonga(Duration.ofMillis(400));

        long inicio = System.nanoTime();
        assertThatThrownBy(() -> cliente.paraConsulta("pergunta")).isInstanceOf(EmbeddingsIndisponivelException.class)
                .hasMessageContaining("não respondeu").hasMessageNotContaining(CHAVE).hasMessageNotContaining("127.0.0.1");
        Duration gasto = Duration.ofNanos(System.nanoTime() - inicio);

        assertThat(gasto).as("estourou perto do limite, não nos 3 s do servidor").isLessThan(Duration.ofMillis(2000));
        assertThat(servidor.recebidas()).hasSize(1);
    }

    @Test
    void oLimiteDaPerguntaNaoMordeAIngestao() {
        // a ingestão tem o limite dela (60 s por tentativa): um lote que demora mais que o da pergunta passa
        servidor.responderDepoisDe(Duration.ofMillis(700), 200, respostaDoLote(1));
        EmbeddingsGemini cliente = comEsperaLonga(Duration.ofMillis(200));

        assertThat(cliente.paraTrechos("Titulo", trechos(1))).hasSize(1);
        assertThat(servidor.recebidas()).hasSize(1);
    }

    @Test
    void aChaveNaoSaiNaMensagemDoLimiteDeUsoDaPergunta() {
        servidor.responder(429, "{\"error\":{\"message\":\"Quota exceeded for key " + CHAVE + "\"}}");

        assertThatThrownBy(() -> gemini.paraConsulta("pergunta")).isInstanceOf(EmbeddingsIndisponivelException.class)
                .hasMessageNotContaining(CHAVE).hasMessageNotContaining("AIzaSy");
    }

    @Test
    void aChaveNuncaSaiNaMensagemDeErro() {
        // O provedor repete a chave na frase de erro (acontece com "API key not valid"): ela sai riscada.
        servidor.responder(403, "{\"error\":{\"message\":\"API key not valid. Please pass a valid API key: " + CHAVE + "\"}}");

        assertThatThrownBy(() -> gemini.paraConsulta("pergunta")).isInstanceOf(EmbeddingsIndisponivelException.class)
                .hasMessageNotContaining(CHAVE).hasMessageNotContaining("AIzaSy").hasMessageContaining("O Gemini respondeu 403")
                .hasMessageContaining("[omitida]");
    }

    @Test
    void falhaDeRedeNaoLevaAChaveNemOEndereco() {
        EmbeddingsGemini fora = new EmbeddingsGemini("http://127.0.0.1:1", CHAVE, "gemini-embedding-2", 768, Duration.ZERO);

        assertThatThrownBy(() -> fora.paraConsulta("pergunta")).isInstanceOf(EmbeddingsIndisponivelException.class)
                .hasMessageStartingWith("Não consegui falar com o Gemini (").hasMessageNotContaining(CHAVE).hasMessageNotContaining("127.0.0.1");
    }

    @Test
    void respostaQueNaoEJsonOuSemOsVetoresEErro() {
        servidor.responder(200, "isto não é json");
        servidor.responder(200, "{\"embeddings\":[]}");

        assertThatThrownBy(() -> gemini.paraConsulta("p")).hasMessage("O Gemini respondeu 200, mas não no formato esperado.");
        assertThatThrownBy(() -> gemini.paraTrechos("T", trechos(2))).hasMessage("O Gemini devolveu 0 vetores para 2 textos.");
    }

    @Test
    void numeroDeDimensoesDiferenteDe768EErro() {
        servidor.responder(200, "{\"embedding\":{\"values\":[0.1,0.2,0.3]}}");

        assertThatThrownBy(() -> gemini.paraConsulta("p")).hasMessage("O Gemini devolveu 3 dimensões e o Atlas espera 768.");
    }

    @Test
    void normaForaDeUmMaisOuMenos001ENormalizada() {
        servidor.responder(200, "{\"embedding\":{\"values\":" + valores(false, 768) + "}}");
        servidor.responder(200, "{\"embedding\":{\"values\":" + valores(true, 768) + "}}");

        assertThat(ProvedorDeEmbeddings.norma(gemini.paraConsulta("p"))).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(ProvedorDeEmbeddings.norma(gemini.paraConsulta("p"))).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
    }

    // ---------------------------------------------------------------------
    // M3, rodada de correção: vetor inválido nunca chega ao banco (achado 8)
    // ---------------------------------------------------------------------

    private static final String SEM_VETOR_VALIDO = "O Gemini devolveu um vetor inválido";

    private static String valoresCom(int posicao, String valor) {
        return IntStream.range(0, 768).mapToObj(i -> i == posicao ? valor : "0.036").collect(Collectors.joining(",", "[", "]"));
    }

    @Test
    void vetorSoDeZerosEErroPorqueOCossenoDariaNaN() {
        servidor.responder(200, "{\"embedding\":{\"values\":" + IntStream.range(0, 768).mapToObj(i -> "0").collect(Collectors.joining(",", "[", "]")) + "}}");
        servidor.responder(200, "{\"embeddings\":[{\"values\":" + IntStream.range(0, 768).mapToObj(i -> "0.0").collect(Collectors.joining(",", "[", "]")) + "}]}");

        assertThatThrownBy(() -> gemini.paraConsulta("p")).isInstanceOf(EmbeddingsIndisponivelException.class)
                .hasMessage(SEM_VETOR_VALIDO + ": todos os valores são zero.");
        assertThatThrownBy(() -> gemini.paraTrechos("T", trechos(1))).isInstanceOf(EmbeddingsIndisponivelException.class)
                .hasMessage(SEM_VETOR_VALIDO + ": todos os valores são zero.");
    }

    @Test
    void valorNuloOuQueNaoEhNumeroNoVetorEErroEmVezDeVirarZero() {
        for (String ruim : List.of("null", "\"abc\"", "\"0.5\"", "true", "[0.1]", "{\"a\":1}")) {
            servidor.responder(200, "{\"embedding\":{\"values\":" + valoresCom(7, ruim) + "}}");

            assertThatThrownBy(() -> gemini.paraConsulta("p")).as(ruim).isInstanceOf(EmbeddingsIndisponivelException.class)
                    .hasMessage(SEM_VETOR_VALIDO + ": a posição 7 não é um número finito.");
        }
    }

    @Test
    void umNuloEOutrosZerosNaoPassamComoVetorUnitario() {
        servidor.responder(200, "{\"embedding\":{\"values\":[1," + IntStream.range(0, 767).mapToObj(i -> "null").collect(Collectors.joining(",")) + "]}}");

        assertThatThrownBy(() -> gemini.paraConsulta("p")).isInstanceOf(EmbeddingsIndisponivelException.class)
                .hasMessage(SEM_VETOR_VALIDO + ": a posição 1 não é um número finito.");
    }

    @Test
    void valorQueEstouraOFloatEErroEmVezDeVirarInfinitoENaN() {
        for (String enorme : List.of("1e40", "-1e40", "3.5e38")) {
            servidor.responder(200, "{\"embeddings\":[{\"values\":" + valoresCom(3, enorme) + "}]}");

            assertThatThrownBy(() -> gemini.paraTrechos("T", trechos(1))).as(enorme).isInstanceOf(EmbeddingsIndisponivelException.class)
                    .hasMessage(SEM_VETOR_VALIDO + ": a posição 3 não é um número finito.");
        }
    }

    @Test
    void umVetorInvalidoNoMeioDoLoteDerrubaOLoteInteiroComAFraseClaraESemAChave() {
        servidor.responder(200, "{\"embeddings\":[{\"values\":" + umNaPosicao(0) + "},{\"values\":" + valoresCom(0, "null") + "}]}");

        assertThatThrownBy(() -> gemini.paraTrechos("T", trechos(2))).isInstanceOf(EmbeddingsIndisponivelException.class)
                .hasMessageContaining(SEM_VETOR_VALIDO).hasMessageNotContaining(CHAVE).hasMessageNotContaining("AIza");
    }

    @Test
    void semChaveNaoChamaNadaEODiagnosticoDizPorque() {
        EmbeddingsGemini semChave = new EmbeddingsGemini(servidor.urlBase(), "  ", "gemini-embedding-2", 768, Duration.ZERO);

        assertThat(semChave.ligado()).isFalse();
        assertThat(semChave.motivoDeDesligado()).isEqualTo("Falta a chave do Gemini (GEMINI_API_KEY).");
        assertThatThrownBy(() -> semChave.paraConsulta("p")).isInstanceOf(EmbeddingsIndisponivelException.class);
        assertThatThrownBy(() -> semChave.paraTrechos("T", trechos(1))).isInstanceOf(EmbeddingsIndisponivelException.class);
        assertThat(servidor.recebidas()).isEmpty();
        assertThat(gemini.ligado()).isTrue();
        assertThat(gemini.modelo()).isEqualTo("gemini-embedding-2");
    }

    @Test
    void aUrlBaseComBarraNoFimFunciona() {
        EmbeddingsGemini comBarra = new EmbeddingsGemini(servidor.urlBase() + "/", CHAVE, "gemini-embedding-2", 768, Duration.ZERO);
        servidor.responder(200, "{\"embedding\":{\"values\":" + valores(true, 768) + "}}");

        comBarra.paraConsulta("p");

        assertThat(servidor.ultima().caminho()).isEqualTo("/v1beta/models/gemini-embedding-2:embedContent");
        assertThat(new ArrayList<>(servidor.recebidas())).hasSize(1);
    }
}
