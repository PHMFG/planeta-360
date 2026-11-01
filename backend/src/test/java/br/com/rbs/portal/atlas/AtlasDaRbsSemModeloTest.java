package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.RBS;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.provedor.DisponibilidadeDoLlm;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] O Atlas da RBS ligado, mas sem modelo de linguagem (o contexto padrão do teste, com {@code atlas.rbs.ligado=true}): as
 * rotas 67 e 68 dizem {@code llmDisponivel} falso, e a mensagem de texto responde 503 com a frase do chat (a RBS não tem o botão
 * Montar relatório a oferecer), antes do teto e sem gravar mensagem nem traço. Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@TestPropertySource(properties = "atlas.rbs.ligado=true")
@Transactional
class AtlasDaRbsSemModeloTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    long evento2027() {
        return jdbc.sql("SELECT id FROM evento WHERE edicao = 2027").query(Long.class).single();
    }

    @Test
    void semModeloAsRotasDizemLlmDisponivelFalsoEAMensagemDeTextoDa503SemBotao() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        MvcTestResult aberta = rbs.post("/api/eventos/" + evento2027() + "/atlas/conversas", "");
        assertThat(aberta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(aberta, "$.llmDisponivel")).isEqualTo(false);
        long conversa = ((Number) lerJson(aberta, "$.conversaId")).longValue();
        assertThat(lerJson(rbs.get("/api/eventos/" + evento2027() + "/atlas/conversas"), "$.llmDisponivel")).isEqualTo(false);

        MvcTestResult texto = rbs.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Oi\"}");

        assertThat(texto).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(lerJson(texto, "$.detail")).isEqualTo(DisponibilidadeDoLlm.SEM_MODELO_NO_CHAT).isEqualTo(
                "O Atlas está sem o modelo de linguagem agora. Tente de novo mais tarde.");
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_mensagem WHERE conversa_id = :c").param("c", conversa).query(Long.class).single())
                .isZero();
        assertThat(lerLista(rbs.get("/api/atlas/conversas/" + conversa), "$.itens")).isEmpty();
        // o corpo ruim continua dando 422 antes do 503
        assertThat(rbs.post("/api/atlas/conversas/" + conversa + "/mensagens", "{}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
