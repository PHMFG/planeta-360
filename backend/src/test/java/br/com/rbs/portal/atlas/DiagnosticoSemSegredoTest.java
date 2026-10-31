package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.RBS;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.tracoDaConversa;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.tudoOQueOTracoGuarda;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.conversa;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.falarEmFluxo;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.ApoioDoDiagnostico.Linha;
import br.com.rbs.portal.atlas.diagnostico.ServidorHttpFalso;
import br.com.rbs.portal.atlas.provedor.ServidorDeLlmFalso;
import br.com.rbs.portal.atlas.provedor.ServidorDeLlmFalso.Fluxo;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * [ATLAS] O BLOQUEANTE do M2 (plano, seção M2): nenhum traço nem resposta da API contém o raciocínio cru do modelo, a chave de um
 * provedor ou o prompt de sistema. A API sobe com o Spring AI de verdade apontando para servidores HTTP falsos e locais (o modelo de
 * linguagem, o JEV e o Gemini), com um raciocínio falso conhecido ({@code RACIOCINIO-SECRETO-123}), chaves falsas e uma frase do
 * prompt de sistema. Roda um turno em JSON, um em fluxo (SSE) e um em que o provedor recusa a chave e a repete no corpo do erro; e
 * procura os três em tudo o que foi gravado em {@code atlas_traco} e em tudo o que as rotas 44, 45, 49, 50, 51, 52 e 53 devolveram,
 * no corpo do fluxo e no log. Antes de procurar, confere que os três realmente passaram pelo sistema (o raciocínio voltou ao modelo, o
 * prompt e a chave foram ao provedor): sem isso a busca não provaria nada.
 * <p>
 * Não é {@code @Transactional} (o turno em fluxo e o resumo rodam em outras threads); o que o teste confirmou é apagado no fim.
 */
@TesteDeIntegracao
@ExtendWith(OutputCaptureExtension.class)
class DiagnosticoSemSegredoTest {

    private static final String SEGREDO = "RACIOCINIO-SECRETO-123";
    private static final String CHAVE_DO_LLM = "chave-do-llm-de-mentira-77aa";
    private static final String CHAVE_DO_JEV = "chave-do-jev-de-mentira-88bb";
    private static final String CHAVE_DO_GEMINI = "chave-do-gemini-de-mentira-99cc";
    private static final ServidorDeLlmFalso LLM = ServidorDeLlmFalso.iniciar();
    private static final ServidorHttpFalso JEV = ServidorHttpFalso.iniciar();
    private static final ServidorHttpFalso GEMINI = ServidorHttpFalso.iniciar();
    /** Um trecho do prompt de sistema que vai ao provedor inteiro (sem os campos @MARCA@ e @DATA@, que o servidor preenche). */
    private static final String FRASE_DO_PROMPT = umaFraseDoPrompt();

    @DynamicPropertySource
    static void ligarTudoNosServidoresFalsos(DynamicPropertyRegistry registro) {
        registro.add("spring.ai.model.chat", () -> "openai");
        registro.add("spring.ai.openai.api-key", () -> CHAVE_DO_LLM);
        registro.add("spring.ai.openai.base-url", LLM::urlBase);
        registro.add("spring.ai.openai.max-retries", () -> "0");
        registro.add("atlas.prazo-do-resumo", () -> "PT2S");
        registro.add("atlas.diagnostico.ligado", () -> "true");
        registro.add("atlas.diagnostico.marca", () -> "true");
        registro.add("atlas.jev.base-url", JEV::urlBase);
        registro.add("atlas.jev.chave", () -> CHAVE_DO_JEV);
        registro.add("atlas.embeddings.base-url", GEMINI::urlBase);
        registro.add("atlas.embeddings.chave", () -> CHAVE_DO_GEMINI);
    }

    @AfterAll
    static void derrubarOsServidores() {
        LLM.close();
        JEV.close();
        GEMINI.close();
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void guardarOQueJaExiste() {
        LLM.limpar();
        LLM.exigirRaciocinio(true);
        JEV.limpar();
        GEMINI.limpar();
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagarOQueOTesteConfirmou() {
        limpeza.apagar();
    }

    /** A maior linha do prompt do modo em uso (o relatório está ligado no perfil de teste) que não tem campo a preencher. */
    private static String umaFraseDoPrompt() {
        try {
            String prompt = new String(new ClassPathResource("atlas/prompts/relatorio.txt").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String linha = prompt.lines().filter(l -> !l.contains("@") && l.strip().length() >= 70)
                    .max(Comparator.comparingInt(l -> l.strip().length())).orElseThrow();
            return linha.strip().substring(0, 70);
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }
    }

    // ---------------------------------------------------------------------
    // Os turnos
    // ---------------------------------------------------------------------

    @Test
    void turnoEmJsonComFerramentaERaciocinioNaoDeixaSegredoNoTracoNemNasRespostas(CapturedOutput saida) {
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(marca, jdbc);
        LLM.responder(ServidorDeLlmFalso.chamada("c1", "parceria_consultar", "{}", SEGREDO + " primeiro, vou consultar os dados."));
        LLM.responder(ServidorDeLlmFalso.textoComRaciocinio("Os dados estão no portal.", SEGREDO + " agora respondo."));
        List<String> respostas = new ArrayList<>();

        MvcTestResult turno = marca.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Como estou?\"}");
        assertThat(turno).hasStatus(HttpStatus.OK);
        respostas.add(corpo(turno));

        confereQueOSegredoPassouPeloSistema(conversa, 2);
        respostas.addAll(oQueAsRotasDevolvem(marca, conversa));
        exigirSemSegredo(conversa, respostas, saida);
    }

    @Test
    void turnoEmFluxoComRaciocinioNaoDeixaSegredoNoTracoNemNoFluxoNemNasRespostas(CapturedOutput saida) {
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(marca, jdbc);
        LLM.responderEmFluxo(new Fluxo().raciocinio(SEGREDO).raciocinio(" pensando em fluxo.").texto("Pronto, ").texto("os dados estão aí.").fim("stop"));
        List<String> respostas = new ArrayList<>();

        respostas.add(corpo(falarEmFluxo(marca, conversa, "Como estou?")));   // o corpo SSE inteiro

        confereQueOSegredoPassouPeloSistema(conversa, 1);
        respostas.addAll(oQueAsRotasDevolvem(marca, conversa));
        exigirSemSegredo(conversa, respostas, saida);
    }

    @Test
    void provedorQueRecusaARepeteAChaveNoErroENadaDissoSaiDaApi(CapturedOutput saida) {
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(marca, jdbc);
        LLM.responderComStatus(401, "{\"error\":{\"message\":\"Incorrect API key provided: " + CHAVE_DO_LLM + "\"}}");
        List<String> respostas = new ArrayList<>();

        MvcTestResult turno = marca.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Como estou?\"}");
        assertThat(turno).hasStatus(HttpStatus.OK);
        respostas.add(corpo(turno));

        // o erro está no traço (o diagnóstico serve para isso), e a chave não
        List<Linha> traco = tracoDaConversa(jdbc, conversa);
        Linha ida = traco.stream().filter(l -> l.etapa().equals("LLM_IDA")).findFirst().orElseThrow();
        assertThat(ida.resultado()).isEqualTo("ERRO");
        assertThat((String) ida.dados().get("detalhe")).contains("401").contains("[omitido]");
        assertThat(LLM.recebidas().get(0).cabecalho("authorization")).containsExactly("Bearer " + CHAVE_DO_LLM);
        respostas.addAll(oQueAsRotasDevolvem(marca, conversa));
        exigirSemSegredo(conversa, respostas, saida);
    }

    // ---------------------------------------------------------------------
    // Apoio
    // ---------------------------------------------------------------------

    /**
     * Prova que a busca vale: o raciocínio foi gravado e voltou ao modelo na ida seguinte (o DeepSeek o exige), a chave foi ao provedor
     * e o prompt de sistema também. Se nada disso tivesse acontecido, "não achei o segredo" não diria nada.
     */
    private void confereQueOSegredoPassouPeloSistema(long conversa, int idas) {
        assertThat(LLM.recebidas()).hasSize(idas);
        assertThat(LLM.recebidas()).allSatisfy(r -> assertThat(r.cabecalho("authorization")).containsExactly("Bearer " + CHAVE_DO_LLM));
        assertThat(JsonPath.<String>read(LLM.recebidas().get(0).corpo(), "$.messages[0].content")).contains(FRASE_DO_PROMPT);
        assertThat(jdbc.sql("SELECT string_agg(reasoning_content, ' ') FROM atlas_mensagem WHERE conversa_id = :c").param("c", conversa)
                .query(String.class).single()).contains(SEGREDO);
        if (idas > 1) {
            assertThat(LLM.recebidas().get(1).corpo()).contains(SEGREDO);
        }
        assertThat(tracoDaConversa(jdbc, conversa)).as("o traço foi gravado").isNotEmpty();
    }

    /** Tudo o que as rotas do diagnóstico e da conversa devolvem para esta conversa, como a marca e como a RBS. */
    private List<String> oQueAsRotasDevolvem(SessaoDeApi marca, long conversa) {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        List<String> respostas = new ArrayList<>();
        respostas.add(corpo(marca.get("/api/atlas/conversas/" + conversa)));
        MvcTestResult diagnostico = marca.get("/api/atlas/conversas/" + conversa + "/diagnostico");
        assertThat(diagnostico).hasStatus(HttpStatus.OK);
        respostas.add(corpo(diagnostico));
        String turno = (String) lerJson(diagnostico, "$.turnos[0].turno");
        for (String uri : List.of("/api/atlas/diagnostico/turnos", "/api/atlas/diagnostico/turnos/" + turno, "/api/atlas/provedores")) {
            MvcTestResult resposta = rbs.get(uri);
            assertThat(resposta).as(uri).hasStatus(HttpStatus.OK);
            respostas.add(corpo(resposta));
        }
        // o teste de conexão de cada provedor: sucesso do JEV, recusa do Gemini que repete a chave, e o modelo de linguagem
        JEV.responder(200, "{\"answers\":{\"relevante\":{\"noul\":0.9}}}");
        GEMINI.responder(403, "{\"error\":{\"message\":\"A chave " + CHAVE_DO_GEMINI + " não vale.\"}}");
        LLM.responderComStatus(401, "{\"error\":{\"message\":\"Incorrect API key provided: " + CHAVE_DO_LLM + "\"}}");
        for (String nome : List.of("jev", "gemini", "llm")) {
            MvcTestResult teste = rbs.post("/api/atlas/provedores/" + nome + "/teste", null);
            assertThat(teste).as(nome).hasStatus(HttpStatus.OK);
            respostas.add(corpo(teste));
        }
        assertThat(JEV.recebidas().get(JEV.recebidas().size() - 1).cabecalho("authorization")).containsExactly("Bearer " + CHAVE_DO_JEV);
        assertThat(GEMINI.recebidas().get(GEMINI.recebidas().size() - 1).cabecalho("x-goog-api-key")).containsExactly(CHAVE_DO_GEMINI);
        return respostas;
    }

    private void exigirSemSegredo(long conversa, List<String> respostas, CapturedOutput saida) {
        String noBanco = tudoOQueOTracoGuarda(jdbc, conversa);
        assertThat(noBanco).as("o traço gravado").isNotBlank();
        List<String> segredos = List.of(SEGREDO, CHAVE_DO_LLM, CHAVE_DO_JEV, CHAVE_DO_GEMINI, FRASE_DO_PROMPT);
        for (String segredo : segredos) {
            assertThat(noBanco).as("atlas_traco.dados não pode ter [" + segredo + "]").doesNotContain(segredo);
            for (String resposta : respostas) {
                assertThat(resposta).as("uma resposta da API não pode ter [" + segredo + "]").doesNotContain(segredo);
            }
            assertThat(saida.getAll()).as("o log não pode ter [" + segredo + "]").doesNotContain(segredo);
        }
        // nem o prompt do resumo (um texto nosso que também só vai ao provedor), nem a mensagem de sistema inteira
        assertThat(noBanco).doesNotContain("Você é o Atlas");
    }
}
