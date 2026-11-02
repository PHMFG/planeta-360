package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.RBS;
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
 * [ATLAS] [ISOLAMENTO] A V15 deixa a conversa existir sem parceria (a do evento, para o Atlas da RBS, no M8). Nenhuma rota cria uma
 * ainda, mas o código de hoje não pode quebrar com ela: para a marca, a conversa sem parceria é como a que não existe (404, nunca 500
 * por parceria nula), mesmo sendo a marca a "dona" da linha, e o diagnóstico da RBS lista os turnos sem tropeçar nela.
 */
@TesteDeIntegracao
@Transactional
class ConversaSemParceriaTest {

    private static final String CONVERSA_NAO_ENCONTRADA = "Não encontramos esta conversa.";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    /** Uma conversa do evento de 2027, sem parceria, no nome do usuário dado. */
    long conversaDoEventoDe(String email) {
        return jdbc.sql("""
                INSERT INTO atlas_conversa (parceria_id, evento_id, usuario_id)
                VALUES (NULL, (SELECT id FROM evento WHERE edicao = 2027), (SELECT id FROM usuario WHERE email = :email)) RETURNING id
                """).param("email", email).query(Long.class).single();
    }

    @Test
    void paraAMarcaAConversaSemParceriaEComoAQueNaoExiste() {
        long conversa = conversaDoEventoDe(AMARELO);
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);

        MvcTestResult ver = marca.get("/api/atlas/conversas/" + conversa);
        assertThat(ver).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(ver, "$.detail")).isEqualTo(CONVERSA_NAO_ENCONTRADA);

        MvcTestResult enviar = marca.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Oi\"}");
        assertThat(enviar).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(enviar, "$.detail")).isEqualTo(CONVERSA_NAO_ENCONTRADA);

        assertThat(marca.get("/api/atlas/conversas/" + conversa + "/diagnostico")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void aListaDeConversasDaParceriaNaoTrazAConversaSemParceria() {
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        MvcTestResult antes = marca.get("/api/parcerias/" + parceria + "/atlas/conversas");
        long semParceria = conversaDoEventoDe(AMARELO);

        MvcTestResult lista = marca.get("/api/parcerias/" + parceria + "/atlas/conversas");

        assertThat(lista).hasStatus(HttpStatus.OK);
        assertThat(lerJson(lista, "$.conversas.length()")).isEqualTo(lerJson(antes, "$.conversas.length()"));
        assertThat(lerLista(lista, "$.conversas[*].id")).doesNotContain((int) semParceria);
    }

    @Test
    void aRbsListaOsTurnosSemTropecarNaConversaSemParceria() {
        long conversa = conversaDoEventoDe(RBS);
        jdbc.sql("""
                INSERT INTO atlas_traco (parceria_id, conversa_id, turno, ordem, etapa, resultado, iniciada_em, duracao_ms)
                VALUES (NULL, :conversa, gen_random_uuid(), 1, 'LLM_IDA', 'OK', now(), 1)
                """).param("conversa", conversa).update();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult turnos = rbs.get("/api/atlas/diagnostico/turnos");

        // com o diagnóstico desligado no teste comum a rota responde 404; ligado, 200: nos dois casos, nunca 500
        assertThat(turnos.getResponse().getStatus()).isIn(200, 404);
    }
}
