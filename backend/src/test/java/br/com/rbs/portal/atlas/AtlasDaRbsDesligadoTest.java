package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.RBS;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] O Atlas da RBS vem DESLIGADO por padrão ({@code atlas.rbs.ligado=false}; ligado nos perfis dev e demo): as rotas 67 e 68
 * respondem 404 à RBS, e nada muda no resto. A RBS segue sem conversar pelas rotas 44, 45 e 49, com o 403 de sempre, até numa
 * conversa de evento que exista no banco. Este é o contexto padrão do teste (sem a propriedade). Cada teste é desfeito no fim
 * (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class AtlasDaRbsDesligadoTest {

    private static final String SO_PATROCINADOR = "Só o Admin Patrocinador da parceria conversa com o Atlas.";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    long evento() {
        return jdbc.sql("SELECT id FROM evento WHERE edicao = 2027").query(Long.class).single();
    }

    @Test
    void desligadoAsDuasRotasDao404ARbsSemCriarNada() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long antes = jdbc.sql("SELECT count(*) FROM atlas_conversa").query(Long.class).single();

        MvcTestResult abrir = rbs.post("/api/eventos/" + evento() + "/atlas/conversas", "");
        MvcTestResult listar = rbs.get("/api/eventos/" + evento() + "/atlas/conversas");

        assertThat(abrir).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(listar).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(abrir, "$.detail")).isEqualTo("Não encontramos o que você pediu.");
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_conversa").query(Long.class).single()).isEqualTo(antes);
    }

    @Test
    void desligadoAMarcaAindaRecebe403NasDuasRotas() {
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);

        assertThat(marca.post("/api/eventos/" + evento() + "/atlas/conversas", "")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(marca.get("/api/eventos/" + evento() + "/atlas/conversas")).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void desligadoARbsContinuaComOs403DasRotasDaConversaMesmoNaConversaDoEvento() {
        long conversa = jdbc.sql("""
                INSERT INTO atlas_conversa (parceria_id, evento_id, usuario_id)
                VALUES (NULL, :evento, (SELECT id FROM usuario WHERE email = :email)) RETURNING id
                """).param("evento", evento()).param("email", RBS).query(Long.class).single();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        for (String corpo : List.of("", "{}", "isto nao e json", "{\"texto\":\"Oi\"}")) {
            MvcTestResult enviar = rbs.post("/api/atlas/conversas/" + conversa + "/mensagens", corpo);
            assertThat(enviar).as("POST [" + corpo + "]").hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(enviar, "$.detail")).isEqualTo(SO_PATROCINADOR);
        }
        for (String uri : List.of("/api/atlas/conversas/" + conversa, "/api/atlas/conversas/" + conversa + "/diagnostico")) {
            MvcTestResult ler = rbs.get(uri);
            assertThat(ler).as(uri).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(ler, "$.detail")).isEqualTo(SO_PATROCINADOR);
        }
    }
}
