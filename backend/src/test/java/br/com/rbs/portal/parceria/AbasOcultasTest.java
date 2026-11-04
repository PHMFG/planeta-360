package br.com.rbs.portal.parceria;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [PERMISSAO] R6.a: a RBS oculta uma aba (liberada = false, visivel = false). Toda rota que mostra abas diz {@code visivel}; para
 * a marca a aba oculta se comporta como fechada (403 com a frase de aba); fechada continua visível, com o cadeado. O Atlas não
 * cita a aba oculta (ConsultarCotaTest). Uma parceria nova nasce com as 7 abas visíveis. Cada teste é desfeito no fim.
 */
@TesteDeIntegracao
@Transactional
class AbasOcultasTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String ROXO = "marketing@roxo.example";
    private static final String ABA_FECHADA = "Esta aba ainda não foi liberada para a sua parceria.";
    private static final String OCULTAR_CORTESIAS = "{\"abas\":[{\"codigo\":\"CORTESIAS\",\"liberada\":false,\"visivel\":false}]}";
    private static final String CORTESIAS_VISIVEL = "$.abas[?(@.codigo=='CORTESIAS')].visivel";
    private static final String CORTESIAS_EU = "$.parcerias[0].abas[?(@.codigo=='CORTESIAS')].visivel";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    long roxo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Roxo");
    }

    @Test
    void todaRotaDeAbasDizVisivelEOcultaNaoContaComoLiberada() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        SessaoDeApi roxo = SessaoDeApi.entrar(mvc, ROXO);
        long id = roxo();
        assertThat(lerLista(roxo.get("/api/auth/eu"), "$.parcerias[0].abas[*].visivel")).hasSize(7).containsOnly(true);

        MvcTestResult oculta = rbs.patch("/api/parcerias/" + id, OCULTAR_CORTESIAS);

        assertThat(oculta).hasStatus(HttpStatus.OK);   // rota 10
        assertThat(lerLista(oculta, CORTESIAS_VISIVEL)).containsExactly(false);
        assertThat(lerLista(oculta, "$.abas[?(@.codigo=='CORTESIAS')].liberada")).containsExactly(false);
        assertThat(lerLista(rbs.get("/api/parcerias/{id}", id), CORTESIAS_VISIVEL)).as("rota 9").containsExactly(false);
        assertThat(lerLista(roxo.get("/api/parcerias/{id}", id), CORTESIAS_VISIVEL)).as("rota 9, da marca").containsExactly(false);
        assertThat(lerLista(roxo.get("/api/auth/eu"), CORTESIAS_EU)).as("rota 4").containsExactly(false);
        MvcTestResult login = SessaoDeApi.anonima(mvc).post("/api/auth/login",
                "{\"email\":\"" + ROXO + "\",\"senha\":\"" + SessaoDeApi.SENHA + "\"}");
        assertThat(lerLista(login, CORTESIAS_EU)).as("rota 2").containsExactly(false);
        assertThat(lerLista(roxo.get("/api/auth/eu"), "$.parcerias[0].abas[?(@.codigo!='CORTESIAS')].visivel")).containsOnly(true);
    }

    @Test
    void fechadaContinuaVisivelEReabrirRestauraAAba() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long id = roxo();

        MvcTestResult fechada = rbs.patch("/api/parcerias/" + id, "{\"abas\":[{\"codigo\":\"CORTESIAS\",\"liberada\":false,\"visivel\":true}]}");
        assertThat(lerLista(fechada, CORTESIAS_VISIVEL)).as("fechada: a marca vê o cadeado").containsExactly(true);
        assertThat(lerLista(fechada, "$.abas[?(@.codigo=='CORTESIAS')].liberada")).containsExactly(false);

        rbs.patch("/api/parcerias/" + id, OCULTAR_CORTESIAS);
        MvcTestResult liberada = rbs.patch("/api/parcerias/" + id, "{\"abas\":[{\"codigo\":\"CORTESIAS\",\"liberada\":true,\"visivel\":true}]}");
        assertThat(lerLista(liberada, CORTESIAS_VISIVEL)).containsExactly(true);
        assertThat(lerLista(liberada, "$.abas[?(@.codigo=='CORTESIAS')].liberada")).containsExactly(true);
        assertThat(SessaoDeApi.entrar(mvc, ROXO).get("/api/parcerias/{id}/entregas?aba=CORTESIAS", id)).hasStatus(HttpStatus.OK);
    }

    @Test
    void marcaNaRotaDeUmaAbaOcultaRecebe403ComAFraseDeAba() {
        long id = roxo();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, ROXO);
        assertThat(marca.get("/api/parcerias/{id}/entregas?aba=CORTESIAS", id)).as("antes de ocultar").hasStatus(HttpStatus.OK);

        rbs.patch("/api/parcerias/" + id, OCULTAR_CORTESIAS);
        MvcTestResult oculta = marca.get("/api/parcerias/{id}/entregas?aba=CORTESIAS", id);

        assertThat(oculta).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(oculta, "$.detail")).isEqualTo(ABA_FECHADA);
        assertThat(rbs.get("/api/parcerias/{id}/entregas?aba=CORTESIAS", id)).as("a RBS vê tudo").hasStatus(HttpStatus.OK);
        // Só o visivel decide: mesmo com liberada = true, a aba oculta dá 403.
        rbs.patch("/api/parcerias/" + id, "{\"abas\":[{\"codigo\":\"CORTESIAS\",\"liberada\":true,\"visivel\":false}]}");
        MvcTestResult soOculta = marca.get("/api/parcerias/{id}/entregas?aba=CORTESIAS", id);
        assertThat(soOculta).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(soOculta, "$.detail")).isEqualTo(ABA_FECHADA);
        // A marca nunca oculta: a rota 10 é só da RBS.
        assertThat(marca.patch("/api/parcerias/" + id, "{\"abas\":[{\"codigo\":\"CORTESIAS\",\"liberada\":true,\"visivel\":true}]}"))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void parceriaNovaNasceComAsSeteAbasVisiveis() {
        long evento = jdbc.sql("SELECT id FROM evento WHERE edicao = 2027").query(Long.class).single();
        MvcTestResult nova = SessaoDeApi.entrar(mvc, RBS).post("/api/parcerias", """
                {"patrocinador":{"nome":"Cliente Oculta","segmento":"Varejo"},"eventoId":%d,"cotaCodigo":"MASTER","emails":[]}"""
                .formatted(evento));

        assertThat(nova).hasStatus(HttpStatus.CREATED);   // rota 8
        assertThat(lerLista(nova, "$.abas[*].visivel")).hasSize(7).containsOnly(true);
        long id = ((Number) lerJson(nova, "$.id")).longValue();
        assertThat(jdbc.sql("SELECT count(*) FROM parceria_aba WHERE parceria_id = :p AND visivel").param("p", id)
                .query(Long.class).single()).isEqualTo(7);
    }
}
