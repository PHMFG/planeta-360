package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
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
 * [ATLAS] [RELATORIO] O selo do servidor no relatório de verdade (M7), pela rota 47, como o front a lê: a grade de aplicações do Amarelo
 * de 2027 tem 19 previstas e 17 comprovadas, então o item "Comprovadas" leva {@code "abaixo do previsto"} e os outros itens, {@code null}
 * (o campo existe em todo item). O relatório pronto guarda o selo no snapshot, e o de montagem o recalcula com os números de agora.
 * Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class SelosNoRelatorioTest {

    private static final String BOTAO = "{\"atalho\":\"MONTAR_RELATORIO\",\"modelo\":\"ATIVACAO_EVENTO\"}";
    private static final String ACCEPT = "{\"acao\":\"accept\",\"valores\":[{\"chave\":\"ativacao.visitantes_total\",\"valor\":1200},"
            + "{\"chave\":\"ativacao.cadastros_consentidos\",\"valor\":1072}]}";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    private long relatorioMontando(SessaoDeApi pessoa) {
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        MvcTestResult aberta = pessoa.post("/api/parcerias/" + parceria + "/atlas/conversas", BOTAO);
        assertThat(aberta).hasStatus(HttpStatus.CREATED);
        return ((Number) lerLista(aberta, "$.itens[?(@.tipo=='cartao')].relatorioId").get(0)).longValue();
    }

    @Test
    void oItemComprovadasLevaOSeloEOsOutrosLevamNulo() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long relatorio = relatorioMontando(pessoa);

        MvcTestResult resposta = pessoa.get("/api/atlas/relatorios/" + relatorio);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        String grade = "$.secoes[?(@.id=='aplicacoes')].blocos[?(@.tipo=='kpi_grade')].itens[*]";
        assertThat(lerLista(resposta, grade + ".rotulo")).containsExactly("Previstas", "Comprovadas", "Comprovação", "Fotos");
        assertThat(lerLista(resposta, grade + ".selo")).containsExactly(null, "abaixo do previsto", null, null);
        assertThat(lerLista(resposta, grade + ".valor")).containsExactly("19", "17", "89,4%", "17");
    }

    @Test
    void oSeloDoRelatorioProntoFicaNoSnapshot() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long relatorio = relatorioMontando(pessoa);
        long solicitacao = jdbc.sql("SELECT s.id FROM atlas_solicitacao s WHERE s.relatorio_id = :r").param("r", relatorio).query(Long.class).single();
        assertThat(pessoa.post("/api/atlas/solicitacoes/" + solicitacao + "/respostas", ACCEPT)).hasStatus(HttpStatus.OK);

        // pronto: o snapshot gravado leva o selo, e a leitura de novo o devolve
        assertThat(jdbc.sql("SELECT snapshot::text FROM atlas_relatorio WHERE id = :r").param("r", relatorio).query(String.class).single())
                .contains("\"selo\": \"abaixo do previsto\"");
        MvcTestResult lido = pessoa.get("/api/atlas/relatorios/" + relatorio);
        assertThat(lerLista(lido, "$.secoes[?(@.id=='aplicacoes')].blocos[?(@.tipo=='kpi_grade')].itens[1].selo"))
                .containsExactly("abaixo do previsto");
    }
}
