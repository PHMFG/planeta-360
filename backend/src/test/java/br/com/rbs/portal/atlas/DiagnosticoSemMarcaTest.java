package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.RBS;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.diagnostico.ServidorHttpFalso;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] O diagnóstico LIGADO sem a marca ({@code atlas.diagnostico.ligado=true}, {@code atlas.diagnostico.marca=false}) e sem chave
 * de provedor nenhuma. A RBS lê o traço de todos os turnos, mas a marca não vê o das próprias conversas: a rota 49 responde 404 também
 * ao dono, {@code diagnosticoDisponivel} sai falso e os itens não trazem o {@code turno}. Sem chave no JEV nem no Gemini, a rota 52
 * diz {@code NENHUM} e a rota 53 responde SEM chamar nada (os servidores falsos, que valem como a rede, não recebem nada), com o
 * motivo. Cada teste é desfeito no fim (@Transactional).
 */
@TesteComLlmRoteirizado
@Transactional
class DiagnosticoSemMarcaTest {

    private static final ServidorHttpFalso JEV = ServidorHttpFalso.iniciar();
    private static final ServidorHttpFalso GEMINI = ServidorHttpFalso.iniciar();

    @DynamicPropertySource
    static void ligarSemMarcaESemChaves(DynamicPropertyRegistry registro) {
        registro.add("atlas.diagnostico.ligado", () -> "true");
        registro.add("atlas.diagnostico.marca", () -> "false");
        registro.add("atlas.jev.base-url", JEV::urlBase);
        registro.add("atlas.embeddings.base-url", GEMINI::urlBase);
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

    @BeforeEach
    void zerar() {
        modelo.limpar();
        JEV.limpar();
        GEMINI.limpar();
    }

    @Test
    void oTurnoFicaGravadoParaARbsMasAMarcaNaoVeNada() {
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        long conversa = ((Number) lerJson(marca.post("/api/parcerias/" + parceria + "/atlas/conversas", "{}"), "$.conversaId")).longValue();
        modelo.texto("Posso ajudar com os dados da parceria.");

        MvcTestResult turno = marca.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Oi\"}");

        assertThat(turno).hasStatus(HttpStatus.OK);
        assertThat(lerJson(turno, "$.diagnosticoDisponivel")).isEqualTo(false);
        assertThat(corpo(turno)).doesNotContain("\"turno\"");
        assertThat(lerJson(marca.get("/api/atlas/conversas/" + conversa), "$.diagnosticoDisponivel")).isEqualTo(false);
        // a marca dona da conversa não lê o diagnóstico dela
        MvcTestResult daMarca = marca.get("/api/atlas/conversas/" + conversa + "/diagnostico");
        assertThat(daMarca).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(daMarca, "$.detail")).isEqualTo("Não encontramos esta conversa.");
        // mas o traço foi gravado, e a RBS o lê
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        MvcTestResult lista = rbs.get("/api/atlas/diagnostico/turnos?parceriaId=" + parceria);
        assertThat(lerJson(lista, "$.total")).isEqualTo(1);
        assertThat(lerJson(lista, "$.itens[0].resultado")).isEqualTo("OK");
        MvcTestResult detalhe = rbs.get("/api/atlas/diagnostico/turnos/" + lerJson(lista, "$.itens[0].turno"));
        assertThat(detalhe).hasStatus(HttpStatus.OK);
        assertThat(lerLista(detalhe, "$.etapas[*].etapa")).containsExactly("G1_LIMITES", "LLM_IDA", "CHECAGEM", "SAIDA");
    }

    @Test
    void semChaveOsProvedoresDizemNenhumEOTesteNaoChamaNada() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult lista = rbs.get("/api/atlas/provedores");

        assertThat(lista).hasStatus(HttpStatus.OK);
        assertThat(lerJson(lista, "$.provedores[?(@.nome=='llm')].origem")).isEqualTo(java.util.List.of("AMBIENTE"));   // o roteirizado
        assertThat(lerLista(lista, "$.provedores[1:3].configurado")).containsExactly(false, false);
        assertThat(lerLista(lista, "$.provedores[1:3].origem")).containsExactly("NENHUM", "NENHUM");
        assertThat(lerLista(lista, "$.provedores[1:3].finalDaChave")).containsOnlyNulls();
        assertThat(lerJson(lista, "$.provedores[1].modo")).isEqualTo("DESLIGADO");   // ATLAS_JEV não definido: none
        assertThat(lerJson(lista, "$.provedores[1].baseUrl")).isEqualTo(JEV.urlBase());

        for (String nome : java.util.List.of("jev", "gemini")) {
            MvcTestResult teste = rbs.post("/api/atlas/provedores/" + nome + "/teste", null);
            assertThat(teste).as(nome).hasStatus(HttpStatus.OK);
            assertThat(lerJson(teste, "$.provedor")).isEqualTo(nome);
            assertThat(lerJson(teste, "$.ok")).isEqualTo(false);
            assertThat(lerJson(teste, "$.chamou")).isEqualTo(false);
            assertThat(lerJson(teste, "$.milissegundos")).isEqualTo(0);
            assertThat(lerJson(teste, "$.erro")).isEqualTo("Nenhuma chave configurada.");
            assertThat(corpo(teste)).doesNotContain("\"resposta\"");
        }
        assertThat(JEV.recebidas()).isEmpty();
        assertThat(GEMINI.recebidas()).isEmpty();
    }
}
