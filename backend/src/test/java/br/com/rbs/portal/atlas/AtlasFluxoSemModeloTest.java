package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.provedor.DisponibilidadeDoLlm;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] Sem modelo de linguagem (o padrão), a mensagem de texto da rota 44 em fluxo dá o 503 de sempre, em ProblemDetail (JSON),
 * antes de o fluxo abrir, com {@code Accept: text/event-stream} sozinho e com {@code text/event-stream, application/json}.
 */
@TesteDeIntegracao
@Transactional
class AtlasFluxoSemModeloTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"text/event-stream", "text/event-stream, application/json"})
    void textoSemModeloDa503AntesDoFluxo(String aceita) {
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, ApoioDoAtlas.AMARELO);
        MvcTestResult aberta = marca.post("/api/parcerias/" + SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo") + "/atlas/conversas", "{}");
        long conversa = ((Number) lerJson(aberta, "$.conversaId")).longValue();

        MvcTestResult resposta = marca.postAceitando("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Oi\"}", aceita);

        AtlasFluxoErrosTest.problema(resposta, HttpStatus.SERVICE_UNAVAILABLE, DisponibilidadeDoLlm.SEM_MODELO);
    }
}
