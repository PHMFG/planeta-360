package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.provedor.DisponibilidadeDoLlm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] O Atlas só chat ({@code atlas.relatorio.ligado=false}) sem modelo de linguagem (o padrão): não há plano B nem botão. A
 * conversa abre vazia, {@code llmDisponivel} e {@code relatorioDisponivel} saem falsos, e a mensagem de texto da rota 44, em JSON
 * ou em fluxo, dá 503 com a frase que não fala do botão.
 */
@TesteDeIntegracao
@Transactional
@TestPropertySource(properties = "atlas.relatorio.ligado=false")
class AtlasSoChatSemModeloTest {

    private static final String FRASE_503 = "O Atlas está sem o modelo de linguagem agora. Tente de novo mais tarde.";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/event-stream", "text/event-stream, application/json"})
    void textoSemModeloDa503SemFalarDoBotao(String aceita) {
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, ApoioDoAtlas.AMARELO);
        MvcTestResult aberta = marca.post("/api/parcerias/" + SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo") + "/atlas/conversas", "{}");
        assertThat(aberta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(aberta, "$.llmDisponivel")).isEqualTo(false);
        assertThat(lerJson(aberta, "$.relatorioDisponivel")).isEqualTo(false);
        assertThat(lerLista(aberta, "$.itens")).isEmpty();
        long conversa = ((Number) lerJson(aberta, "$.conversaId")).longValue();

        MvcTestResult resposta = marca.postAceitando("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Oi\"}", aceita);

        assertThat(resposta).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo(FRASE_503).isEqualTo(DisponibilidadeDoLlm.SEM_MODELO_NO_CHAT);
        assertThat(corpo(resposta)).doesNotContain("botão", "Montar relatório");
    }

    @Test
    void semModeloNaoHaBotaoNemPlanoB() {
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, ApoioDoAtlas.AMARELO);
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        long conversa = ((Number) lerJson(marca.post("/api/parcerias/" + parceria + "/atlas/conversas", "{}"), "$.conversaId")).longValue();

        MvcTestResult atalho = marca.post("/api/atlas/conversas/" + conversa + "/mensagens",
                "{\"atalho\":\"MONTAR_RELATORIO\",\"modelo\":\"ATIVACAO_EVENTO\"}");

        assertThat(atalho).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(atalho, "$.campos.atalho")).isEqualTo("O relatório não está disponível nesta versão.");
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_relatorio").query(Long.class).single()).isZero();
        assertThat(lerJson(marca.get("/api/parcerias/" + parceria + "/atlas/conversas"), "$.llmDisponivel")).isEqualTo(false);
    }
}
