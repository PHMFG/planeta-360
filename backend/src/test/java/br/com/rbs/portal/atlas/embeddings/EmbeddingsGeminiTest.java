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
    void seO429NaoPassaDepoisDe4TentativasOErroDizOLimiteDeUso() {
        for (int i = 0; i < 6; i++) {
            servidor.responder(429, "{\"error\":{\"message\":\"Resource has been exhausted\"}}");
        }

        assertThatThrownBy(() -> gemini.paraConsulta("pergunta")).isInstanceOf(EmbeddingsIndisponivelException.class)
                .hasMessage("O Gemini recusou por limite de uso (429) mesmo depois de 4 tentativas.");
        assertThat(servidor.recebidas()).hasSize(4);
    }

    @Test
    void erroDeServidorTentaUmaVezMais() {
        servidor.responder(503, "{\"error\":{\"message\":\"Indisponível\"}}");
        servidor.responder(200, "{\"embedding\":{\"values\":" + valores(true, 768) + "}}");

        assertThat(gemini.paraConsulta("pergunta")).hasSize(768);
        assertThat(servidor.recebidas()).hasSize(2);
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
