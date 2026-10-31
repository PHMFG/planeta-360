package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.RBS;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.diagnostico.ServidorHttpFalso;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * [ATLAS] As rotas 52 e 53 do diagnóstico: o estado dos três provedores (modelo de linguagem, JEV e Gemini) e a chamada mínima a cada
 * um. O JEV e o Gemini são servidores HTTP falsos e locais (a URL base é configurável) com chaves de mentira; o modelo de linguagem é o
 * roteirizado. Confere o que a API pede a cada provedor (caminho, cabeçalho da chave, corpo), as respostas de sucesso e de erro, que a
 * chave inteira nunca sai (nem na resposta de erro do provedor, que a repete), e que as rotas são da RBS. Nenhum provedor de verdade é
 * chamado. O caso sem chave está em DiagnosticoSemMarcaTest e a falha de rede, em ProvedoresServiceTest.
 */
@TesteComLlmRoteirizado
class ProvedoresTest {

    private static final String CHAVE_DO_JEV = "jev-chave-de-mentira-9f8e7d";
    private static final String CHAVE_DO_GEMINI = "gemini-chave-de-mentira-4c3b2a";
    private static final ServidorHttpFalso JEV = ServidorHttpFalso.iniciar();
    private static final ServidorHttpFalso GEMINI = ServidorHttpFalso.iniciar();

    @DynamicPropertySource
    static void ligarOsProvedores(DynamicPropertyRegistry registro) {
        registro.add("atlas.diagnostico.ligado", () -> "true");
        registro.add("atlas.diagnostico.marca", () -> "true");
        registro.add("atlas.jev.base-url", JEV::urlBase);
        registro.add("atlas.jev.chave", () -> CHAVE_DO_JEV);
        registro.add("atlas.jev.modo", () -> "sombra");
        registro.add("atlas.embeddings.base-url", GEMINI::urlBase);
        registro.add("atlas.embeddings.chave", () -> CHAVE_DO_GEMINI);
    }

    @AfterAll
    static void derrubarOsServidores() {
        JEV.close();
        GEMINI.close();
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProvedorRoteirizado modelo;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void guardarOQueJaExiste() {
        modelo.limpar();
        JEV.limpar();
        GEMINI.limpar();
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagarOQueOTesteConfirmou() {
        limpeza.apagar();
    }

    SessaoDeApi rbs() {
        return SessaoDeApi.entrar(mvc, RBS);
    }

    static String umaListaDe768() {
        return IntStream.range(0, 768).mapToObj(i -> "0.0" + (i % 10)).collect(Collectors.joining(",", "[", "]"));
    }

    // ---------------------------------------------------------------------
    // Rota 52: o estado de cada provedor
    // ---------------------------------------------------------------------

    @Test
    void listaOsTresProvedoresSemNuncaMostrarAChaveInteira() {
        MvcTestResult resposta = rbs().get("/api/atlas/provedores");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerLista(resposta, "$.provedores[*].nome")).containsExactly("llm", "jev", "gemini");
        // o modelo de linguagem: o roteirizado do teste é o provedor das variáveis de ambiente
        assertThat(lerJson(resposta, "$.provedores[0].configurado")).isEqualTo(true);
        assertThat(lerJson(resposta, "$.provedores[0].origem")).isEqualTo("AMBIENTE");
        assertThat(lerJson(resposta, "$.provedores[0].modelo")).isEqualTo("deepseek-v4.1-flash");
        assertThat(lerJson(resposta, "$.provedores[0].baseUrl")).isEqualTo("https://opencode.ai/inference/go/openai/v1");
        // o JEV
        assertThat(lerJson(resposta, "$.provedores[1].configurado")).isEqualTo(true);
        assertThat(lerJson(resposta, "$.provedores[1].origem")).isEqualTo("AMBIENTE");
        assertThat(lerJson(resposta, "$.provedores[1].modelo")).isEqualTo("jev-1.14.0");
        assertThat(lerJson(resposta, "$.provedores[1].baseUrl")).isEqualTo(JEV.urlBase());
        assertThat(lerJson(resposta, "$.provedores[1].finalDaChave")).isEqualTo(CHAVE_DO_JEV.substring(CHAVE_DO_JEV.length() - 4));
        assertThat(lerJson(resposta, "$.provedores[1].modo")).isEqualTo("SOMBRA");
        // o Gemini
        assertThat(lerJson(resposta, "$.provedores[2].configurado")).isEqualTo(true);
        assertThat(lerJson(resposta, "$.provedores[2].modelo")).isEqualTo("gemini-embedding-2");
        assertThat(lerJson(resposta, "$.provedores[2].baseUrl")).isEqualTo(GEMINI.urlBase());
        assertThat(lerJson(resposta, "$.provedores[2].finalDaChave")).isEqualTo(CHAVE_DO_GEMINI.substring(CHAVE_DO_GEMINI.length() - 4));
        assertThat(lerLista(resposta, "$.provedores[?(@.nome=='gemini')].modo")).containsOnlyNulls();
        // o disjuntor é do M8: nulo por enquanto, nos três
        assertThat(lerLista(resposta, "$.provedores[*].disjuntor")).containsOnlyNulls().hasSize(3);
        assertThat(lerJson(resposta, "$.tetoDiario.limite")).isEqualTo(200);
        // nunca a chave inteira
        assertThat(corpo(resposta)).doesNotContain(CHAVE_DO_JEV).doesNotContain(CHAVE_DO_GEMINI);
    }

    @Test
    void aLatenciaOsErrosEOTetoDeCadaProvedorVemDoTraco() {
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        long conversa = ((Number) lerJson(marca.post("/api/parcerias/" + parceria + "/atlas/conversas", "{}"), "$.conversaId")).longValue();
        modelo.texto("Um.").texto("Dois.").falha();
        for (String texto : List.of("Um", "Dois", "Tres")) {
            assertThat(marca.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"" + texto + "\"}")).hasStatus(HttpStatus.OK);
        }

        MvcTestResult resposta = rbs().get("/api/atlas/provedores");

        // três idas ao modelo, uma com erro; a latência é das que deram certo
        assertThat(lerJson(resposta, "$.provedores[0].chamadas24h")).isEqualTo(3);
        assertThat(lerJson(resposta, "$.provedores[0].erros24h")).isEqualTo(1);
        assertThat(((Number) lerJson(resposta, "$.provedores[0].latenciaP50Ms")).longValue()).isGreaterThanOrEqualTo(0);
        assertThat(((Number) lerJson(resposta, "$.provedores[0].latenciaP95Ms")).longValue())
                .isGreaterThanOrEqualTo(((Number) lerJson(resposta, "$.provedores[0].latenciaP50Ms")).longValue());
        // o JEV e o Gemini ainda não deixam traço
        assertThat(lerLista(resposta, "$.provedores[1:3].chamadas24h")).containsExactly(0, 0);
        assertThat(lerLista(resposta, "$.provedores[1:3].latenciaP50Ms")).containsOnlyNulls();
        // o teto diário: as 3 mensagens do assistente que a parceria gastou hoje (duas respostas e a frase da falha)
        assertThat(lerLista(resposta, "$.tetoDiario.parcerias[*].parceria")).contains("Cliente Amarelo");
        assertThat(lerLista(resposta, "$.tetoDiario.parcerias[?(@.parceria=='Cliente Amarelo')].usadas")).containsExactly(3);
        assertThat(lerLista(resposta, "$.tetoDiario.parcerias[?(@.parceria=='Cliente Amarelo')].parceriaId"))
                .extracting(n -> ((Number) n).longValue()).containsExactly(parceria);
    }

    // ---------------------------------------------------------------------
    // Rota 53: o modelo de linguagem
    // ---------------------------------------------------------------------

    @Test
    void testeDoModeloDeLinguagemMandaAMensagemMinimaSemFerramentas() {
        modelo.texto("ok");
        long mensagensAntes = jdbc.sql("SELECT count(*) FROM atlas_mensagem").query(Long.class).single();

        MvcTestResult resposta = rbs().post("/api/atlas/provedores/llm/teste", null);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.provedor")).isEqualTo("llm");
        assertThat(lerJson(resposta, "$.ok")).isEqualTo(true);
        assertThat(lerJson(resposta, "$.chamou")).isEqualTo(true);
        assertThat(lerJson(resposta, "$.resposta")).isEqualTo("ok");
        assertThat(lerJson(resposta, "$.modelo")).isEqualTo("deepseek-v4.1-flash");
        assertThat(((Number) lerJson(resposta, "$.milissegundos")).longValue()).isGreaterThanOrEqualTo(0);
        assertThat(corpo(resposta)).doesNotContain("\"erro\"");
        assertThat(modelo.ultimoPedido().mensagens()).extracting(m -> m.papel() + ":" + m.conteudo())
                .containsExactly("user:Responda só com a palavra ok.");
        assertThat(modelo.ultimoPedido().ferramentas()).isEmpty();
        // o teste não grava nada: não conta para o teto diário
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_mensagem").query(Long.class).single()).isEqualTo(mensagensAntes);
    }

    @Test
    void testeDoModeloDeLinguagemQueFalhaDizOMotivoSemGravarTraco() {
        modelo.falha();
        long antes = jdbc.sql("SELECT count(*) FROM atlas_traco").query(Long.class).single();

        MvcTestResult resposta = rbs().post("/api/atlas/provedores/llm/teste", null);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.ok")).isEqualTo(false);
        assertThat(lerJson(resposta, "$.chamou")).isEqualTo(true);
        assertThat(lerJson(resposta, "$.erro")).isEqualTo("o provedor do teste não respondeu");
        assertThat(corpo(resposta)).doesNotContain("\"resposta\"");
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_traco").query(Long.class).single()).isEqualTo(antes);
    }

    // ---------------------------------------------------------------------
    // Rota 53: o JEV
    // ---------------------------------------------------------------------

    @Test
    void testeDoJevFazUmaPerguntaNoulEmPortuguesComAChaveNoCabecalho() {
        JEV.responder(200, "{\"answers\":{\"relevante\":{\"noul\":0.95}}}");

        MvcTestResult resposta = rbs().post("/api/atlas/provedores/jev/teste", null);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.provedor")).isEqualTo("jev");
        assertThat(lerJson(resposta, "$.ok")).isEqualTo(true);
        assertThat(lerJson(resposta, "$.chamou")).isEqualTo(true);
        assertThat(lerJson(resposta, "$.resposta")).isEqualTo("noul 0.95");
        assertThat(lerJson(resposta, "$.modelo")).isEqualTo("jev-1.14.0");
        assertThat(corpo(resposta)).doesNotContain(CHAVE_DO_JEV).doesNotContain("\"erro\"");
        assertThat(JEV.recebidas()).hasSize(1);
        ServidorHttpFalso.Recebida recebida = JEV.ultima();
        assertThat(recebida.metodo()).isEqualTo("POST");
        assertThat(recebida.caminho()).isEqualTo("/v1/systemone");
        assertThat(recebida.cabecalho("authorization")).containsExactly("Bearer " + CHAVE_DO_JEV);
        assertThat(recebida.cabecalho("content-type")).containsExactly("application/json");
        assertThat(JsonPath.<String>read(recebida.corpo(), "$.model")).isEqualTo("jev-1.14.0");
        assertThat(JsonPath.<String>read(recebida.corpo(), "$.state")).isEqualTo("Até quando preciso informar os geradores na Arena?");
        assertThat(JsonPath.<String>read(recebida.corpo(), "$.questions.relevante.type")).isEqualTo("noul");
        assertThat(JsonPath.<String>read(recebida.corpo(), "$.questions.relevante.instructions")).isEqualTo("A mensagem pergunta sobre um prazo?");
        assertThat(GEMINI.recebidas()).isEmpty();
    }

    @Test
    void testeDoJevRecusadoDizOStatusESemAChaveMesmoQueOProvedorARepita() {
        JEV.responder(401, "{\"detail\":\"Token inválido: " + CHAVE_DO_JEV + "\"}");

        MvcTestResult resposta = rbs().post("/api/atlas/provedores/jev/teste", null);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.ok")).isEqualTo(false);
        assertThat(lerJson(resposta, "$.chamou")).isEqualTo(true);
        assertThat((String) lerJson(resposta, "$.erro")).startsWith("O provedor respondeu 401: Token inválido:").contains("[omitido]");
        assertThat(corpo(resposta)).doesNotContain(CHAVE_DO_JEV).doesNotContain("\"resposta\"");
    }

    @Test
    void testeDoJevComCorpoQueNaoEOEsperadoEErro() {
        JEV.responder(200, "{}");

        MvcTestResult resposta = rbs().post("/api/atlas/provedores/jev/teste", null);

        assertThat(lerJson(resposta, "$.ok")).isEqualTo(false);
        assertThat(lerJson(resposta, "$.erro")).isEqualTo("O provedor respondeu 200, mas não no formato esperado.");
    }

    // ---------------------------------------------------------------------
    // Rota 53: o Gemini
    // ---------------------------------------------------------------------

    @Test
    void testeDoGeminiPedeUmEmbeddingDe768DimensoesComAChaveNoCabecalho() {
        GEMINI.responder(200, "{\"embedding\":{\"values\":" + umaListaDe768() + "}}");

        MvcTestResult resposta = rbs().post("/api/atlas/provedores/gemini/teste", null);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.provedor")).isEqualTo("gemini");
        assertThat(lerJson(resposta, "$.ok")).isEqualTo(true);
        assertThat(lerJson(resposta, "$.resposta")).isEqualTo("768 dimensões");
        assertThat(lerJson(resposta, "$.modelo")).isEqualTo("gemini-embedding-2");
        assertThat(corpo(resposta)).doesNotContain(CHAVE_DO_GEMINI);
        assertThat(GEMINI.recebidas()).hasSize(1);
        ServidorHttpFalso.Recebida recebida = GEMINI.ultima();
        assertThat(recebida.metodo()).isEqualTo("POST");
        assertThat(recebida.caminho()).isEqualTo("/v1beta/models/gemini-embedding-2:embedContent");
        assertThat(recebida.cabecalho("x-goog-api-key")).containsExactly(CHAVE_DO_GEMINI);
        assertThat(recebida.cabecalho("authorization")).isEmpty();
        assertThat(JsonPath.<Integer>read(recebida.corpo(), "$.embedContentConfig.outputDimensionality")).isEqualTo(768);
        assertThat(JsonPath.<String>read(recebida.corpo(), "$.content.parts[0].text")).isNotBlank();
        assertThat(recebida.caminho()).doesNotContain(CHAVE_DO_GEMINI);   // a chave nunca vai na URL
        assertThat(JEV.recebidas()).isEmpty();
    }

    @Test
    void testeDoGeminiRecusadoTrazAFraseDoProvedorSemAChave() {
        GEMINI.responder(400, "{\"error\":{\"code\":400,\"message\":\"API key not valid (" + CHAVE_DO_GEMINI + "). Please pass a valid API key.\"}}");

        MvcTestResult resposta = rbs().post("/api/atlas/provedores/gemini/teste", null);

        assertThat(lerJson(resposta, "$.ok")).isEqualTo(false);
        assertThat((String) lerJson(resposta, "$.erro")).startsWith("O provedor respondeu 400: API key not valid (").contains("[omitido]");
        assertThat(corpo(resposta)).doesNotContain(CHAVE_DO_GEMINI);
    }

    @Test
    void testeDoGeminiQueDevolveOutroNumeroDeDimensoesEErro() {
        GEMINI.responder(200, "{\"embedding\":{\"values\":[0.1,0.2,0.3]}}");

        MvcTestResult resposta = rbs().post("/api/atlas/provedores/gemini/teste", null);

        assertThat(lerJson(resposta, "$.ok")).isEqualTo(false);
        assertThat(lerJson(resposta, "$.erro")).isEqualTo("O provedor respondeu 200, mas não no formato esperado.");
    }

    // ---------------------------------------------------------------------
    // Rota 53: o nome, o CSRF
    // ---------------------------------------------------------------------

    @Test
    void provedorQueNaoExisteDa404ESemCsrfDa403() {
        SessaoDeApi rbs = rbs();

        MvcTestResult inexistente = rbs.post("/api/atlas/provedores/openai/teste", null);
        assertThat(inexistente).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(inexistente, "$.detail")).isEqualTo("Não encontramos este provedor.");
        assertThat(rbs.semCsrf(HttpMethod.POST, "/api/atlas/provedores/jev/teste", null)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(JEV.recebidas()).isEmpty();
        // o nome vale em qualquer caixa
        JEV.responder(200, "{\"answers\":{\"relevante\":{\"noul\":0.5}}}");
        assertThat(lerJson(rbs.post("/api/atlas/provedores/JEV/teste", null), "$.ok")).isEqualTo(true);
        assertThat(JEV.recebidas()).hasSize(1);
    }
}
