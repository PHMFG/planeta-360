package br.com.rbs.portal.atlas.debug;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.loop.LoopDoAgente;
import br.com.rbs.portal.atlas.provedor.FabricaDeProvedor;
import br.com.rbs.portal.atlas.provedor.ProvedorAtual;
import br.com.rbs.portal.atlas.provedor.ProvedorAtual.ChaveDoDebug;
import br.com.rbs.portal.atlas.provedor.ServidorDeLlmFalso;
import com.jayway.jsonpath.JsonPath;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] As rotas de debug (temporárias) da chave do modelo de linguagem, com {@code atlas.debug.chaves=true}: pôr, ver, apagar
 * e testar a chave pela tela, como o front as usa (login, sessão e CSRF). O modelo montado com a chave vai a um servidor HTTP
 * falso e local: nenhum provedor de verdade é chamado, e a chave é de mentira. Confere também que a chave inteira nunca sai numa
 * resposta nem no log, e que as quatro rotas são só da RBS (o patrocinador recebe 403). Cada teste é desfeito no fim
 * (@Transactional) e começa sem chave de debug.
 */
@TesteDeIntegracao
@TestPropertySource(properties = "atlas.debug.chaves=true")
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class DebugDoLlmTest {

    private static final String CHAVE = "chave-de-mentira-do-debug-a1b2";
    private static final String OUTRA_CHAVE = "outra-chave-de-mentira-z9y8";
    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String BASE_URL_PADRAO = "https://opencode.ai/inference/go/openai/v1";
    private static final String MODELO_PADRAO = "deepseek-v4.1-flash";
    private static final String ARGS_INICIAR = "{\"modelo\":\"ATIVACAO_EVENTO\",\"objetivo\":\"Relatório da ativação\"}";
    private static final ServidorDeLlmFalso SERVIDOR = ServidorDeLlmFalso.iniciar();

    @AfterAll
    static void derrubarOServidor() {
        SERVIDOR.close();
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProvedorAtual provedorAtual;

    @Autowired
    FabricaDeProvedor fabrica;

    @BeforeEach
    void semChaveDeDebug() {
        provedorAtual.esquecerChaveDoDebug();
        SERVIDOR.limpar();
    }

    SessaoDeApi entrar(String email) {
        return SessaoDeApi.entrar(mvc, email);
    }

    static String json(String chave, String baseUrl, String modelo) {
        return "{\"chave\":" + texto(chave) + ",\"baseUrl\":" + texto(baseUrl) + ",\"modelo\":" + texto(modelo) + "}";
    }

    static String texto(String valor) {
        return valor == null ? "null" : "\"" + valor.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Põe a CHAVE apontando para o servidor falso e confere o 200. */
    MvcTestResult porAChave(SessaoDeApi pessoa) {
        MvcTestResult resposta = pessoa.put("/api/debug/llm", json(CHAVE, SERVIDOR.urlBase(), null));
        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(corpo(resposta)).doesNotContain(CHAVE);
        return resposta;
    }

    long abrirConversa(SessaoDeApi marca, boolean llmDisponivel) {
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        MvcTestResult aberta = marca.post("/api/parcerias/" + parceria + "/atlas/conversas", "{}");
        assertThat(aberta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(aberta, "$.llmDisponivel")).isEqualTo(llmDisponivel);
        return ((Number) lerJson(aberta, "$.conversaId")).longValue();
    }

    // ---------------------------------------------------------------------
    // GET, PUT e DELETE
    // ---------------------------------------------------------------------

    @Test
    void semChaveNenhumaAOrigemENenhumComOsPadroesDoYaml() {
        MvcTestResult resposta = entrar(RBS).get("/api/debug/llm");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        Map<String, Object> estado = JsonPath.read(corpo(resposta), "$");
        assertThat(estado).containsExactly(entry("origem", "NENHUM"), entry("baseUrl", BASE_URL_PADRAO),
                entry("modelo", MODELO_PADRAO), entry("finalDaChave", null), entry("configuradoPor", null),
                entry("configuradoEm", null), entry("llmDisponivel", false), entry("raciocinio", true));
    }

    @Test
    void oRaciocinioDaChaveDoDebugVaiAoModeloEFicaQuandoOPutNaoODiz() {
        SessaoDeApi rbs = entrar(RBS);
        assertThat(fabrica.raciocinioPadrao()).as("atlas.raciocinio vem ligado por padrão").isTrue();

        // desligado pela tela
        MvcTestResult desligado = rbs.put("/api/debug/llm", "{\"chave\":\"" + CHAVE + "\",\"baseUrl\":\"" + SERVIDOR.urlBase()
                + "\",\"raciocinio\":false}");
        assertThat(desligado).hasStatus(HttpStatus.OK);
        assertThat(lerJson(desligado, "$.raciocinio")).isEqualTo(false);
        assertThat(lerJson(rbs.get("/api/debug/llm"), "$.raciocinio")).isEqualTo(false);
        SessaoDeApi marca = entrar(AMARELO);
        long conversa = abrirConversa(marca, true);
        SERVIDOR.responder(ServidorDeLlmFalso.texto("Certo."));
        assertThat(marca.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Oi\"}")).hasStatus(HttpStatus.OK);
        assertThat(JsonPath.<Object>read(SERVIDOR.ultima().corpo(), "$.thinking")).isEqualTo(Map.of("type", "disabled"));

        // outra chave sem dizer o raciocínio: fica o que estava (desligado)
        MvcTestResult outra = rbs.put("/api/debug/llm", json(OUTRA_CHAVE, SERVIDOR.urlBase(), null));
        assertThat(lerJson(outra, "$.raciocinio")).isEqualTo(false);
        assertThat(lerJson(outra, "$.finalDaChave")).isEqualTo("z9y8");

        // ligado de novo
        MvcTestResult ligado = rbs.put("/api/debug/llm", "{\"chave\":\"" + CHAVE + "\",\"baseUrl\":\"" + SERVIDOR.urlBase()
                + "\",\"raciocinio\":true}");
        assertThat(lerJson(ligado, "$.raciocinio")).isEqualTo(true);
        SERVIDOR.responder(ServidorDeLlmFalso.texto("Certo."));
        assertThat(marca.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Oi de novo\"}")).hasStatus(HttpStatus.OK);
        assertThat(JsonPath.<Object>read(SERVIDOR.ultima().corpo(), "$.thinking")).isEqualTo(Map.of("type", "enabled"));

        // um valor que não é verdadeiro nem falso não passa; sem a chave do debug, volta o padrão
        assertThat(rbs.put("/api/debug/llm", "{\"chave\":\"" + CHAVE + "\",\"raciocinio\":\"talvez\"}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        rbs.put("/api/debug/llm", "{\"chave\":\"" + CHAVE + "\",\"raciocinio\":false}");
        assertThat(rbs.delete("/api/debug/llm")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(lerJson(rbs.get("/api/debug/llm"), "$.raciocinio")).isEqualTo(true);
        assertThat(corpo(rbs.get("/api/debug/llm"))).doesNotContain(CHAVE);
    }

    @Test
    void putValidoLigaAChaveDoDebugEAsRotas42e43PassamATerOModelo() {
        SessaoDeApi rbs = entrar(RBS);
        OffsetDateTime antes = OffsetDateTime.now().minusSeconds(1);

        MvcTestResult posta = rbs.put("/api/debug/llm", json(CHAVE, SERVIDOR.urlBase(), "deepseek-v4.1-flash"));

        assertThat(posta).hasStatus(HttpStatus.OK);
        assertThat(corpo(posta)).doesNotContain(CHAVE);
        for (MvcTestResult resposta : List.of(posta, rbs.get("/api/debug/llm"))) {
            assertThat(lerJson(resposta, "$.origem")).isEqualTo("DEBUG");
            assertThat(lerJson(resposta, "$.baseUrl")).isEqualTo(SERVIDOR.urlBase());
            assertThat(lerJson(resposta, "$.modelo")).isEqualTo(MODELO_PADRAO);
            assertThat(lerJson(resposta, "$.finalDaChave")).isEqualTo("a1b2");
            assertThat(lerJson(resposta, "$.configuradoPor.nome")).isEqualTo(jdbc.sql("SELECT nome FROM usuario WHERE email = :e")
                    .param("e", RBS).query(String.class).single());
            assertThat(lerJson(resposta, "$.configuradoPor.perfil")).isEqualTo("ADMIN_RBS");
            assertThat(lerJson(resposta, "$.configuradoPor.id")).isNotNull();
            String quando = (String) lerJson(resposta, "$.configuradoEm");
            assertThat(quando).endsWith("-03:00");
            assertThat(OffsetDateTime.parse(quando)).isAfter(antes);
            assertThat(lerJson(resposta, "$.llmDisponivel")).isEqualTo(true);
            assertThat(corpo(resposta)).doesNotContain(CHAVE);
        }

        // as rotas 42 e 43 do Atlas passam a dizer que há modelo
        SessaoDeApi marca = entrar(AMARELO);
        abrirConversa(marca, true);
        MvcTestResult lista = marca.get("/api/parcerias/" + SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo") + "/atlas/conversas");
        assertThat(lista).hasStatus(HttpStatus.OK);
        assertThat(lerJson(lista, "$.llmDisponivel")).isEqualTo(true);
        assertThat(SERVIDOR.recebidas()).as("pôr a chave não chama o provedor").isEmpty();
    }

    @Test
    void baseUrlEModeloVaziosFicamComOsPadroes() {
        MvcTestResult resposta = entrar(RBS).put("/api/debug/llm", json(CHAVE, "", "  "));

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.baseUrl")).isEqualTo(BASE_URL_PADRAO);
        assertThat(lerJson(resposta, "$.modelo")).isEqualTo(MODELO_PADRAO);
        assertThat(lerJson(resposta, "$.configuradoPor.perfil")).isEqualTo("ADMIN_RBS");
        assertThat(entrar(RBS).put("/api/debug/llm", "{\"chave\":\"" + CHAVE + "\"}")).hasStatus(HttpStatus.OK);
    }

    @Test
    void asQuatroRotasSaoSoDaRbsEOPatrocinadorRecebe403SemMexerNaChave() {
        SessaoDeApi rbs = entrar(RBS);
        porAChave(rbs);
        SessaoDeApi marca = entrar(AMARELO);
        String soRbs = "Esta operação é restrita à equipe da RBS.";

        for (MvcTestResult resposta : List.of(marca.get("/api/debug/llm"), marca.put("/api/debug/llm", json(OUTRA_CHAVE, null, null)),
                marca.delete("/api/debug/llm"), marca.post("/api/debug/llm/teste", null))) {
            assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo(soRbs);
            assertThat(corpo(resposta)).doesNotContain(CHAVE).doesNotContain(OUTRA_CHAVE);
        }
        // nada mudou: a chave da RBS continua valendo, e o provedor falso não recebeu chamada nenhuma
        assertThat(lerJson(rbs.get("/api/debug/llm"), "$.finalDaChave")).isEqualTo("a1b2");
        assertThat(SERVIDOR.recebidas()).isEmpty();
        // sem sessão, 401, como sempre
        assertThat(SessaoDeApi.anonima(mvc).get("/api/debug/llm")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void umTurnoDeChatVaiAoServidorFalsoComAChaveDoDebug() {
        porAChave(entrar(RBS));
        SessaoDeApi marca = entrar(AMARELO);
        long conversa = abrirConversa(marca, true);
        SERVIDOR.responder(ServidorDeLlmFalso.texto("Sou o Atlas. Posso montar o relatório de ativação."));

        MvcTestResult resposta = marca.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Quem é você?\"}");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerLista(resposta, "$.itens[*].tipo")).containsExactly("usuario", "texto");
        assertThat(SERVIDOR.recebidas()).hasSize(1);
        var recebida = SERVIDOR.ultima();
        assertThat(recebida.caminho()).isEqualTo("/inference/go/openai/v1/chat/completions");
        assertThat(recebida.cabecalho("authorization")).containsExactly("Bearer " + CHAVE);
        assertThat(recebida.cabecalho("user-agent")).containsExactly("PlanetaAtlas/0.1");
        assertThat(recebida.cabecalho("x-opencode-session")).containsExactly(String.valueOf(conversa));
        // as mesmas opções do application.yml: raciocínio ligado (atlas.raciocinio), temperatura e modelo
        assertThat(JsonPath.<Object>read(recebida.corpo(), "$.thinking")).isEqualTo(Map.of("type", "enabled"));
        assertThat(JsonPath.<Double>read(recebida.corpo(), "$.temperature")).isEqualTo(0.2);
        assertThat(JsonPath.<String>read(recebida.corpo(), "$.model")).isEqualTo(MODELO_PADRAO);
        assertThat(corpo(resposta)).doesNotContain(CHAVE);
    }

    @Test
    void umTurnoEmAndamentoTerminaComOProvedorQueComecou() {
        porAChave(entrar(RBS));
        SessaoDeApi marca = entrar(AMARELO);
        long conversa = abrirConversa(marca, true);
        SERVIDOR.responder(ServidorDeLlmFalso.chamada("call_1", "relatorio_iniciar", ARGS_INICIAR, null));
        SERVIDOR.responder(ServidorDeLlmFalso.texto("Comecei o relatório de ativação."));
        // na primeira ida ao modelo, alguém troca a chave pela tela
        AtomicBoolean trocou = new AtomicBoolean();
        SERVIDOR.aoReceber(recebida -> {
            if (trocou.compareAndSet(false, true)) {
                provedorAtual.usarChaveDoDebug(new ChaveDoDebug(fabrica.montar(OUTRA_CHAVE, SERVIDOR.urlBase(), MODELO_PADRAO),
                        SERVIDOR.urlBase(), MODELO_PADRAO, true, "y8", null, OffsetDateTime.now()));
            }
        });

        MvcTestResult resposta = marca.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Monte o relatório.\"}");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(SERVIDOR.recebidas()).hasSize(2);
        assertThat(SERVIDOR.recebidas()).allSatisfy(r -> assertThat(r.cabecalho("authorization")).containsExactly("Bearer " + CHAVE));

        // o turno seguinte já usa a chave nova
        SERVIDOR.limpar();
        SERVIDOR.responder(ServidorDeLlmFalso.texto("Certo."));
        assertThat(marca.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Obrigado.\"}")).hasStatus(HttpStatus.OK);
        assertThat(SERVIDOR.ultima().cabecalho("authorization")).containsExactly("Bearer " + OUTRA_CHAVE);
    }

    @Test
    void deleteVoltaANenhumEATextoDaRota44VoltaAo503() {
        SessaoDeApi rbs = entrar(RBS);
        porAChave(rbs);
        SessaoDeApi marca = entrar(AMARELO);
        long conversa = abrirConversa(marca, true);

        MvcTestResult apagada = rbs.delete("/api/debug/llm");

        assertThat(apagada).hasStatus(HttpStatus.NO_CONTENT);
        MvcTestResult estado = rbs.get("/api/debug/llm");
        assertThat(lerJson(estado, "$.origem")).isEqualTo("NENHUM");
        assertThat(lerJson(estado, "$.finalDaChave")).isNull();
        assertThat(lerJson(estado, "$.llmDisponivel")).isEqualTo(false);
        MvcTestResult texto = marca.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Oi\"}");
        assertThat(texto).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(lerJson(texto, "$.detail")).isEqualTo("O Atlas está sem o modelo de linguagem agora. Use o botão Montar relatório.");
        assertThat(SERVIDOR.recebidas()).isEmpty();
        // apagar de novo, sem chave, também é 204
        assertThat(rbs.delete("/api/debug/llm")).hasStatus(HttpStatus.NO_CONTENT);
    }

    // ---------------------------------------------------------------------
    // POST /teste
    // ---------------------------------------------------------------------

    @Test
    void testeComOServidorRespondendo() {
        SessaoDeApi rbs = entrar(RBS);
        porAChave(rbs);
        SERVIDOR.responder(ServidorDeLlmFalso.texto("ok"));

        MvcTestResult resposta = rbs.post("/api/debug/llm/teste", null);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.ok")).isEqualTo(true);
        assertThat(lerJson(resposta, "$.resposta")).isEqualTo("ok");
        assertThat(lerJson(resposta, "$.modelo")).isEqualTo(MODELO_PADRAO);
        assertThat(((Number) lerJson(resposta, "$.milissegundos")).longValue()).isGreaterThanOrEqualTo(0);
        assertThat(corpo(resposta)).doesNotContain("\"erro\"").doesNotContain(CHAVE);
        // a mensagem mínima, sem ferramentas, com a chave do debug
        var recebida = SERVIDOR.ultima();
        assertThat(JsonPath.<List<String>>read(recebida.corpo(), "$.messages[*].content")).containsExactly("Responda só com a palavra ok.");
        assertThat(recebida.corpo()).doesNotContain("\"tools\"");
        assertThat(recebida.cabecalho("authorization")).containsExactly("Bearer " + CHAVE);
        assertThat(recebida.cabecalho("user-agent")).containsExactly("PlanetaAtlas/0.1");
        // o teste não grava nada: não conta para o teto diário
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_mensagem").query(Long.class).single()).isZero();
    }

    @Test
    void testeCortaARespostaEm200Caracteres() {
        SessaoDeApi rbs = entrar(RBS);
        porAChave(rbs);
        SERVIDOR.responder(ServidorDeLlmFalso.texto("o".repeat(300)));

        MvcTestResult resposta = rbs.post("/api/debug/llm/teste", null);

        assertThat(lerJson(resposta, "$.ok")).isEqualTo(true);
        assertThat((String) lerJson(resposta, "$.resposta")).hasSize(200);
    }

    @Test
    void testeComOServidorForaDoArOuRecusandoAChaveDizOMotivoSemAChave() {
        SessaoDeApi rbs = entrar(RBS);
        ServidorDeLlmFalso desligado = ServidorDeLlmFalso.iniciar();
        String urlDesligada = desligado.urlBase();
        desligado.close();
        assertThat(rbs.put("/api/debug/llm", json(CHAVE, urlDesligada, null))).hasStatus(HttpStatus.OK);

        MvcTestResult foraDoAr = rbs.post("/api/debug/llm/teste", null);

        assertThat(foraDoAr).hasStatus(HttpStatus.OK);
        assertThat(lerJson(foraDoAr, "$.ok")).isEqualTo(false);
        assertThat((String) lerJson(foraDoAr, "$.erro")).contains("Causa:").doesNotContain(CHAVE);
        assertThat(lerJson(foraDoAr, "$.modelo")).isEqualTo(MODELO_PADRAO);
        assertThat(corpo(foraDoAr)).doesNotContain("\"resposta\"").doesNotContain(CHAVE);

        // o provedor recusa e devolve a chave no corpo do erro: ela é riscada
        porAChave(rbs);
        SERVIDOR.responderComStatus(401, "{\"error\":{\"message\":\"Incorrect API key provided: " + CHAVE + "\"}}");
        MvcTestResult recusada = rbs.post("/api/debug/llm/teste", null);
        assertThat(recusada).hasStatus(HttpStatus.OK);
        assertThat(lerJson(recusada, "$.ok")).isEqualTo(false);
        assertThat((String) lerJson(recusada, "$.erro")).contains("401").doesNotContain(CHAVE);
        assertThat(corpo(recusada)).doesNotContain(CHAVE);
    }

    @Test
    void testeSemChaveNenhuma() {
        MvcTestResult resposta = entrar(RBS).post("/api/debug/llm/teste", null);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.ok")).isEqualTo(false);
        assertThat(lerJson(resposta, "$.erro")).isEqualTo("Nenhuma chave configurada.");
        assertThat(lerJson(resposta, "$.milissegundos")).isEqualTo(0);
        assertThat(lerJson(resposta, "$.modelo")).isEqualTo(MODELO_PADRAO);
    }

    // ---------------------------------------------------------------------
    // Validação, CSRF e sessão
    // ---------------------------------------------------------------------

    @Test
    void cadaValidacaoDoPutDa422ComOCampo() {
        SessaoDeApi rbs = entrar(RBS);
        String chaveOk = CHAVE;
        Map<String, List<String>> invalidos = Map.of(
                "chave", List.of(json(null, null, null), json("", null, null), json("1234567", null, null),
                        json("chave com espaco", null, null), json(" " + chaveOk, null, null), json("x".repeat(501), null, null),
                        "{}"),
                "baseUrl", List.of(json(chaveOk, "http://exemplo.com/v1", null), json(chaveOk, "ftp://exemplo.com", null),
                        json(chaveOk, "http://localhost.exemplo.com/v1", null), json(chaveOk, "https://", null),
                        json(chaveOk, "https://exemplo.com/" + "a".repeat(500), null), json(chaveOk, "exemplo.com", null)),
                "modelo", List.of(json(chaveOk, null, "deepseek v4"), json(chaveOk, null, "a".repeat(101)),
                        json(chaveOk, null, "modelo;drop")));

        invalidos.forEach((campo, corpos) -> {
            for (String corpoInvalido : corpos) {
                MvcTestResult resposta = rbs.put("/api/debug/llm", corpoInvalido);
                assertThat(resposta).as(corpoInvalido).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(lerJson(resposta, "$.campos." + campo)).as(corpoInvalido).isNotNull();
                assertThat(corpo(resposta)).doesNotContain(chaveOk);
            }
        });
        assertThat(rbs.put("/api/debug/llm", "isto nao e json")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(lerJson(rbs.get("/api/debug/llm"), "$.origem")).as("nenhuma recusa pôs a chave").isEqualTo("NENHUM");

        // os limites que valem
        assertThat(rbs.put("/api/debug/llm", json("12345678", "http://localhost:1234/v1", "a".repeat(100)))).hasStatus(HttpStatus.OK);
        assertThat(rbs.put("/api/debug/llm", json("x".repeat(500), "http://127.0.0.1", "org/modelo:v1.2_b-c"))).hasStatus(HttpStatus.OK);
    }

    @Test
    void putDeleteETesteExigemCsrfESessao() {
        SessaoDeApi rbs = entrar(RBS);
        porAChave(rbs);

        assertThat(rbs.semCsrf(HttpMethod.PUT, "/api/debug/llm", json(OUTRA_CHAVE, null, null))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(rbs.semCsrf(HttpMethod.DELETE, "/api/debug/llm", null)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(rbs.semCsrf(HttpMethod.POST, "/api/debug/llm/teste", null)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(rbs.get("/api/debug/llm"), "$.finalDaChave")).as("nada mudou").isEqualTo("a1b2");
        assertThat(SERVIDOR.recebidas()).isEmpty();

        SessaoDeApi anonima = SessaoDeApi.anonima(mvc);
        assertThat(anonima.get("/api/debug/llm")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(anonima.put("/api/debug/llm", json(OUTRA_CHAVE, null, null))).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(anonima.delete("/api/debug/llm")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(anonima.post("/api/debug/llm/teste", null)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(lerJson(rbs.get("/api/debug/llm"), "$.finalDaChave")).isEqualTo("a1b2");
    }

    // ---------------------------------------------------------------------
    // A chave nunca vai ao log
    // ---------------------------------------------------------------------

    @Test
    void aChaveInteiraNuncaVaiAoLog(CapturedOutput saida) {
        SessaoDeApi rbs = entrar(RBS);
        SessaoDeApi marca = entrar(AMARELO);
        long conversa = abrirConversa(marca, false);

        porAChave(rbs);
        rbs.get("/api/debug/llm");
        SERVIDOR.responder(ServidorDeLlmFalso.texto("ok"));
        rbs.post("/api/debug/llm/teste", null);
        SERVIDOR.responderComStatus(401, "{\"error\":{\"message\":\"Incorrect API key provided: " + CHAVE + "\"}}");
        rbs.post("/api/debug/llm/teste", null);
        SERVIDOR.responderComStatus(401, "{\"error\":{\"message\":\"Incorrect API key provided: " + CHAVE + "\"}}");
        assertThat(marca.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Oi\"}")).hasStatus(HttpStatus.OK);
        rbs.put("/api/debug/llm", json(CHAVE + " com espaco", null, null));
        rbs.delete("/api/debug/llm");

        assertThat(saida.getAll()).doesNotContain(CHAVE);
        // a linha de INFO da troca: quem, a origem, a base URL, o modelo e o final da chave
        assertThat(saida.getAll()).contains("Atlas (debug):", "origem DEBUG, base URL " + SERVIDOR.urlBase() + ", modelo "
                + MODELO_PADRAO + ", final da chave a1b2", "origem NENHUM");
        assertThat(jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'assistant'").param("c", conversa)
                .query(String.class).list()).containsExactly(LoopDoAgente.FRASE_DA_FALHA);
    }
}
