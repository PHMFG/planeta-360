package br.com.rbs.portal.parceria;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [PERMISSAO] [HISTORICO] R6.d1: a RBS edita o investimento e a base (bruto de tabela ou líquido) pela rota 10 e o histórico
 * guarda o antes e o depois; a marca não edita (403) e nada muda. A edição de vários campos, com o valor anterior e o novo, também
 * está em ParceriasControllerTest#patchEditaOPerfilEGravaOValorAnteriorEONovoNoHistorico.
 */
@TesteDeIntegracao
@Transactional
class EdicaoDoInvestimentoTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    List<Map<String, Object>> historico(String detalhe) {
        return jdbc.sql("SELECT valor_anterior, valor_novo FROM historico WHERE acao = 'EDICAO' AND detalhe = :d ORDER BY id")
                .param("d", detalhe).query().listOfRows();
    }

    String estado(long id) {
        return jdbc.sql("SELECT valor_investimento || '/' || coalesce(base_valor, '') FROM parceria WHERE id = :p")
                .param("p", id).query(String.class).single();
    }

    @Test
    void rbsEditaValorEBaseEOHistoricoGuardaOAntesEODepois() {
        long id = amarelo();
        String baseAntes = jdbc.sql("SELECT base_valor FROM parceria WHERE id = :p").param("p", id).query(String.class).single();
        String baseNova = "LIQUIDO".equals(baseAntes) ? "BRUTO_TABELA" : "LIQUIDO";

        MvcTestResult resposta = SessaoDeApi.entrar(mvc, RBS).patch("/api/parcerias/" + id,
                "{\"valorInvestimento\":1234567.89,\"baseValor\":\"" + baseNova + "\"}");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.valorInvestimento")).isEqualTo(1234567.89);
        assertThat(lerJson(resposta, "$.baseValor")).isEqualTo(baseNova);
        assertThat(historico("valorInvestimento")).singleElement()
                .satisfies(h -> assertThat(h).containsEntry("valor_anterior", "1000000.00").containsEntry("valor_novo", "1234567.89"));
        assertThat(historico("baseValor")).singleElement()
                .satisfies(h -> assertThat(h).containsEntry("valor_anterior", baseAntes).containsEntry("valor_novo", baseNova));
        // A marca vê o próprio valor atualizado na rota 9, mas não o edita.
        assertThat(lerJson(SessaoDeApi.entrar(mvc, AMARELO).get("/api/parcerias/{id}", id), "$.valorInvestimento")).isEqualTo(1234567.89);
    }

    @Test
    void baseInvalidaDa422ENadaFicaGravado() {
        long id = amarelo();
        String antes = estado(id);

        MvcTestResult invalida = SessaoDeApi.entrar(mvc, RBS).patch("/api/parcerias/" + id, "{\"baseValor\":\"OUTRA\"}");

        assertThat(invalida).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(estado(id)).isEqualTo(antes);
        assertThat(historico("baseValor")).isEmpty();
    }

    @Test
    void marcaNaoEditaOInvestimento() {
        long id = amarelo();
        String antes = estado(id);

        MvcTestResult resposta = SessaoDeApi.entrar(mvc, AMARELO).patch("/api/parcerias/" + id,
                "{\"valorInvestimento\":1,\"baseValor\":\"LIQUIDO\"}");

        assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(estado(id)).isEqualTo(antes);
        assertThat(jdbc.sql("SELECT valor_investimento FROM parceria WHERE id = :p").param("p", id).query(BigDecimal.class).single())
                .isEqualByComparingTo("1000000");
        assertThat(historico("valorInvestimento")).isEmpty();
    }
}
