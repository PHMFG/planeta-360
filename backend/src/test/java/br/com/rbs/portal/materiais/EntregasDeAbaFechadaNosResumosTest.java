package br.com.rbs.portal.materiais;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [PERMISSAO] Os resumos de entregas (total, aprovadas, pendentes, naoAplicaveis, percentual) que o patrocinador recebe na
 * consolidação (rota 29) e na listagem de parcerias (rota 7) contam só as entregas de aba aberta, as mesmas que a lista de
 * entregas devolve; a RBS recebe os números de todas as abas. Os números vêm da amostra de 2027:
 * Amarelo 11 entregas (10 aprovadas, MIDIA tem 2 com 1 aprovada); Verde 9 (7 aprovadas, 1 não aplicável na PESQUISA, que a
 * amostra já deixa fechada, e MIDIA com 2 aprovadas); Roxo 8 (6 aprovadas, 1 não aplicável na PESQUISA, também fechada).
 * Atende o item 3 e o item 5 da Entrega 2. Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class EntregasDeAbaFechadaNosResumosTest extends ApoioDeMateriais {

    private static final String VERDE = "marketing@verde.example";
    private static final List<String> ABAS = List.of("PERFIL", "MIDIA", "APLICACOES", "CORTESIAS", "PESQUISA", "CREDENCIAMENTO", "CONTRATO");

    /** Os cinco números do resumo, na ordem total, aprovadas, pendentes, naoAplicaveis, percentual (nulo = null). */
    record Resumo(Number total, Number aprovadas, Number pendentes, Number naoAplicaveis, String percentual) {
    }

    static Resumo resumo(MvcTestResult resposta, String prefixo) {
        Object pct = lerJson(resposta, prefixo + ".percentualConcluido");
        return new Resumo((Number) lerJson(resposta, prefixo + ".total"), (Number) lerJson(resposta, prefixo + ".aprovadas"),
                (Number) lerJson(resposta, prefixo + ".pendentes"), (Number) lerJson(resposta, prefixo + ".naoAplicaveis"),
                pct == null ? null : new BigDecimal(String.valueOf(pct)).stripTrailingZeros().toPlainString());
    }

    static Resumo esperado(long total, long aprovadas, long pendentes, long naoAplicaveis, String percentual) {
        return new Resumo(total, aprovadas, pendentes, naoAplicaveis, percentual);
    }

    /** Compara por valor (o JSON devolve Integer, o esperado é Long). */
    static void confere(Resumo real, Resumo esperado) {
        assertThat(real.total().longValue()).as("total").isEqualTo(esperado.total().longValue());
        assertThat(real.aprovadas().longValue()).as("aprovadas").isEqualTo(esperado.aprovadas().longValue());
        assertThat(real.pendentes().longValue()).as("pendentes").isEqualTo(esperado.pendentes().longValue());
        assertThat(real.naoAplicaveis().longValue()).as("naoAplicaveis").isEqualTo(esperado.naoAplicaveis().longValue());
        assertThat(real.percentual()).as("percentual").isEqualTo(esperado.percentual());
    }

    Resumo naConsolidacao(String email, long parceriaId) {
        MvcTestResult resposta = entrar(email).get("/api/parcerias/" + parceriaId + "/consolidacao");
        assertThat(resposta).hasStatus(HttpStatus.OK);
        return resumo(resposta, "$.entregas");
    }

    Resumo naListagem(String email, long parceriaId) {
        MvcTestResult resposta = entrar(email).get("/api/parcerias");
        assertThat(resposta).hasStatus(HttpStatus.OK);
        String filtro = "$[?(@.id==" + parceriaId + ")].entregas";
        assertThat(lerLista(resposta, filtro + ".total")).hasSize(1);
        return new Resumo(first(resposta, filtro + ".total"), first(resposta, filtro + ".aprovadas"), first(resposta, filtro + ".pendentes"),
                first(resposta, filtro + ".naoAplicaveis"), percentual(resposta, filtro + ".percentualConcluido"));
    }

    private static Number first(MvcTestResult resposta, String caminho) {
        return (Number) lerLista(resposta, caminho).getFirst();
    }

    private static String percentual(MvcTestResult resposta, String caminho) {
        Object pct = lerLista(resposta, caminho).getFirst();
        return pct == null ? null : new BigDecimal(String.valueOf(pct)).stripTrailingZeros().toPlainString();
    }

    private void fecharTodas(long parceriaId) {
        ABAS.forEach(aba -> jdbc.sql("UPDATE parceria_aba SET liberada = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = :a)")
                .param("p", parceriaId).param("a", aba).update());
    }

    private void fechar(long parceriaId, String aba) {
        jdbc.sql("UPDATE parceria_aba SET liberada = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = :a)")
                .param("p", parceriaId).param("a", aba).update();
    }

    // ---------------------------------------------------------------------
    // Sem regressão: tudo aberto, a marca vê o mesmo que a RBS
    // ---------------------------------------------------------------------

    @Test
    void comTodasAsAbasAbertasAMarcaVeOsMesmosNumerosDaRbs() {
        Resumo completo = esperado(11, 10, 1, 0, "90.9");

        confere(naConsolidacao(AMARELO, amarelo()), completo);
        confere(naConsolidacao(RBS, amarelo()), completo);
        confere(naListagem(AMARELO, amarelo()), completo);
        confere(naListagem(RBS, amarelo()), completo);
    }

    // ---------------------------------------------------------------------
    // Aba fechada fica fora das contas da marca; a RBS conta tudo
    // ---------------------------------------------------------------------

    @Test
    void consolidacaoDaMarcaNaoContaEntregasDeAbaFechada() {
        fechar(amarelo(), "MIDIA");

        // 11 - 2 entregas da MIDIA (1 aprovada, 1 pendente): sobram 9, todas aprovadas.
        confere(naConsolidacao(AMARELO, amarelo()), esperado(9, 9, 0, 0, "100"));
        confere(naConsolidacao(RBS, amarelo()), esperado(11, 10, 1, 0, "90.9"));
    }

    @Test
    void listagemDaMarcaNaoContaEntregasDeAbaFechada() {
        fechar(amarelo(), "MIDIA");

        confere(naListagem(AMARELO, amarelo()), esperado(9, 9, 0, 0, "100"));
        confere(naListagem(RBS, amarelo()), esperado(11, 10, 1, 0, "90.9"));
    }

    @Test
    void abaEscondidaDoMenuTambemSaiDasContasDaMarca() {
        jdbc.sql("UPDATE parceria_aba SET visivel = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'MIDIA')")
                .param("p", amarelo()).update();

        confere(naConsolidacao(AMARELO, amarelo()), esperado(9, 9, 0, 0, "100"));
        confere(naListagem(AMARELO, amarelo()), esperado(9, 9, 0, 0, "100"));
    }

    @Test
    void abaQueAAmostraJaDeixaFechadaNaoEntraNasContasDaMarca() {
        // Verde: PESQUISA fechada na amostra (a única entrega dela é não aplicável). 9 - 1 = 8, 7 aprovadas, 1 pendente (CORTESIAS).
        Resumo daMarca = esperado(8, 7, 1, 0, "87.5");
        Resumo completo = esperado(9, 7, 1, 1, "87.5");

        confere(naConsolidacao(VERDE, verde()), daMarca);
        confere(naListagem(VERDE, verde()), daMarca);
        confere(naConsolidacao(RBS, verde()), completo);
        confere(naListagem(RBS, verde()), completo);
    }

    @Test
    void duasAbasFechadasSomamOsDescontos() {
        // Verde com MIDIA também fechada: saem mais 2 aprovadas. 6 entregas, 5 aprovadas, 1 pendente: 83,33 cortado em 83.3.
        fechar(verde(), "MIDIA");

        confere(naConsolidacao(VERDE, verde()), esperado(6, 5, 1, 0, "83.3"));
        confere(naListagem(VERDE, verde()), esperado(6, 5, 1, 0, "83.3"));
        confere(naConsolidacao(RBS, verde()), esperado(9, 7, 1, 1, "87.5"));
        confere(naListagem(RBS, verde()), esperado(9, 7, 1, 1, "87.5"));
    }

    @Test
    void semNenhumaEntregaDeAbaAbertaAMarcaVeOQueVeParceriaSemEntrega() {
        fecharTodas(amarelo());

        MvcTestResult consolidacao = entrar(AMARELO).get("/api/parcerias/" + amarelo() + "/consolidacao");
        for (String campo : List.of("total", "aprovadas", "pendentes", "naoAplicaveis", "percentualConcluido")) {
            assertThat(lerJson(consolidacao, "$.entregas." + campo)).as("consolidação, " + campo).isNull();
        }
        confere(naListagem(AMARELO, amarelo()), new Resumo(0, 0, 0, 0, null));
        confere(naConsolidacao(RBS, amarelo()), esperado(11, 10, 1, 0, "90.9"));
        confere(naListagem(RBS, amarelo()), esperado(11, 10, 1, 0, "90.9"));
    }
}
