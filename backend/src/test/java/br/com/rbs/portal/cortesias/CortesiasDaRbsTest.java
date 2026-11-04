package br.com.rbs.portal.cortesias;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.AMARELO;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.RBS;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.VERDE;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.celula;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.cotas;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.eventoDe;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.linha;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.uri;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.uriCotas;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.verde;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
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
 * [R5.4.a] [R5.4.b] A RBS grava a cota de cortesias por setor e dia (rota 81) e o prazo de destinação da edição (rota 82); a marca
 * vê a tabela ao recarregar (rota 80), com os mesmos totais da rota 29. Quantidade negativa, acima de 32.767 ou abaixo do já
 * destinado dá 422, nunca 500; os 4 setores e os 3 tipos são aceitos. Cada teste é desfeito no fim.
 */
@TesteDeIntegracao
@Transactional
class CortesiasDaRbsTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void rbsGravaAGradeInteiraEAMarcaVeAoRecarregar() {
        long parceria = verde(jdbc);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        List<String> celulas = new java.util.ArrayList<>();
        int qtd = 1;
        for (String setor : List.of("ARENA", "CAMAROTE", "PREMIUM", "LOUNGE")) {
            for (String dia : List.of("PASSAPORTE", "SEXTA", "SABADO")) {
                // Arena e Camarote de sexta e sábado já existem na Verde e têm destinação: o valor novo não pode ficar abaixo dela
                celulas.add(celula(setor, dia, 10 + qtd++, qtd % 2 == 0, "Portão " + qtd));
            }
        }

        MvcTestResult gravado = rbs.patch(uriCotas(parceria), cotas(celulas.toArray(String[]::new)));

        assertThat(gravado).hasStatus(HttpStatus.OK);
        assertThat((List<?>) lerJson(gravado, "$.cotas")).hasSize(12);
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, VERDE);
        MvcTestResult lido = marca.get(uri(parceria));
        assertThat(lido).hasStatus(HttpStatus.OK);
        for (String setor : List.of("ARENA", "CAMAROTE", "PREMIUM", "LOUNGE")) {
            for (String dia : List.of("PASSAPORTE", "SEXTA", "SABADO")) {
                assertThat(linha(lido, setor, dia)).as(setor + "/" + dia).isNotNull();
            }
        }
        Map<String, Object> premium = linha(lido, "PREMIUM", "PASSAPORTE");
        assertThat(premium).containsEntry("destinadas", 0).containsKey("acesso").containsKey("comShuttle");
        assertThat(premium.get("cota")).isEqualTo(((Number) premium.get("disponiveis")).intValue());
    }

    @Test
    void acessoEShuttleVoltamParaAMarcaEMudamNoMesmoPatch() {
        long parceria = verde(jdbc);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult gravado = rbs.patch(uriCotas(parceria), cotas(celula("ARENA", "SABADO", 3, true, "Portão 2")));

        assertThat(gravado).hasStatus(HttpStatus.OK);
        Map<String, Object> arena = linha(SessaoDeApi.entrar(mvc, VERDE).get(uri(parceria)), "ARENA", "SABADO");
        assertThat(arena).containsEntry("cota", 3).containsEntry("acesso", "Portão 2").containsEntry("comShuttle", true)
                .containsEntry("destinadas", 2).containsEntry("disponiveis", 1);
    }

    @Test
    void totaisSaoOsDaRota29() {
        long parceria = verde(jdbc);
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, VERDE);

        MvcTestResult cortesias = marca.get(uri(parceria));
        MvcTestResult consolidacao = marca.get("/api/parcerias/" + parceria + "/consolidacao");

        assertThat(cortesias).hasStatus(HttpStatus.OK);
        assertThat(lerJson(cortesias, "$.totais.cota")).isEqualTo(lerJson(consolidacao, "$.cortesias.disponiveis"));
        assertThat(lerJson(cortesias, "$.totais.destinadas")).isEqualTo(lerJson(consolidacao, "$.cortesias.destinadas"));
        assertThat(lerJson(cortesias, "$.totais.percentual")).isEqualTo(lerJson(consolidacao, "$.cortesias.percentual"));
        int cota = ((Number) lerJson(cortesias, "$.totais.cota")).intValue();
        int destinadas = ((Number) lerJson(cortesias, "$.totais.destinadas")).intValue();
        assertThat(((Number) lerJson(cortesias, "$.totais.disponiveis")).intValue()).isEqualTo(cota - destinadas);
        assertThat(cota).isEqualTo(8);          // Verde: 3 + 3 + 1 + 1
        assertThat(destinadas).isEqualTo(6);    // 3 + 2 + 1 + 0
    }

    @Test
    void quantidadeNegativaAcimaDoLimiteOuAbaixoDoDestinadoDa422ENadaMuda() {
        long parceria = verde(jdbc);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        for (String quantidade : List.of("-1", "32768", "99999999", "null", "\"\"")) {
            MvcTestResult resposta = rbs.patch(uriCotas(parceria), cotas(celula("ARENA", "SABADO", quantidade, false, null)));
            assertThat(resposta).as("quantidade " + quantidade).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(corpo(resposta)).contains("cotas[0].quantidade");
        }
        // 2 já destinadas na Arena de sábado: a cota não desce a 1
        MvcTestResult abaixo = rbs.patch(uriCotas(parceria), cotas(celula("ARENA", "SABADO", 1, false, null)));
        assertThat(abaixo).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) lerJson(abaixo, "$.campos['cotas[0].quantidade']")).contains("abaixo das 2 cortesias já destinadas");
        // 0 também é "abaixo do destinado" quando há destinação
        assertThat(rbs.patch(uriCotas(parceria), cotas(celula("ARENA", "SABADO", 0, false, null))))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);

        Map<String, Object> arena = linha(rbs.get(uri(parceria)), "ARENA", "SABADO");
        assertThat(arena).containsEntry("cota", 3).containsEntry("destinadas", 2);
    }

    @Test
    void quantidadeNoLimiteDaColunaEAceita() {
        long parceria = verde(jdbc);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult gravado = rbs.patch(uriCotas(parceria), cotas(celula("LOUNGE", "PASSAPORTE", 32767, false, null)));

        assertThat(gravado).hasStatus(HttpStatus.OK);
        assertThat(linha(gravado, "LOUNGE", "PASSAPORTE")).containsEntry("cota", 32767).containsEntry("disponiveis", 32767);
    }

    @Test
    void setorOuDiaForaDaListaEParRepetidoDao422() {
        long parceria = verde(jdbc);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult setor = rbs.patch(uriCotas(parceria), cotas(celula("PISTA", "SEXTA", 5, false, null)));
        assertThat(setor).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(corpo(setor)).contains("cotas[0].setor");
        MvcTestResult dia = rbs.patch(uriCotas(parceria), cotas(celula("ARENA", "DOMINGO", 5, false, null)));
        assertThat(dia).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(corpo(dia)).contains("cotas[0].dia");
        MvcTestResult repetido = rbs.patch(uriCotas(parceria),
                cotas(celula("PREMIUM", "SEXTA", 5, false, null), celula("PREMIUM", "SEXTA", 6, false, null)));
        assertThat(repetido).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(corpo(repetido)).contains("cotas[1].dia");
        assertThat(rbs.patch(uriCotas(parceria), "{\"cotas\":[]}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(rbs.patch(uriCotas(parceria), "{}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void acessoComNulControleOuMuitoLongoDa422() {
        long parceria = verde(jdbc);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        for (String acesso : List.of("Port\\u0000ao", "Port\\u0007ao", "Port\\u202Eao", "x".repeat(81))) {
            MvcTestResult resposta = rbs.patch(uriCotas(parceria), cotas(celula("PREMIUM", "SEXTA", 5, false, acesso)));
            assertThat(resposta).as("acesso " + acesso.length() + " caracteres").hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(corpo(resposta)).contains("cotas[0].acesso");
        }
        assertThat(rbs.patch(uriCotas(parceria), cotas(celula("PREMIUM", "SEXTA", 5, false, "x".repeat(80)))))
                .hasStatus(HttpStatus.OK);
    }

    @Test
    void tudoOuNadaUmaCelulaRecusadaDesfazAsOutras() {
        long parceria = verde(jdbc);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult resposta = rbs.patch(uriCotas(parceria),
                cotas(celula("LOUNGE", "SEXTA", 7, false, null), celula("ARENA", "SABADO", 1, false, null)));

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(linha(rbs.get(uri(parceria)), "LOUNGE", "SEXTA")).as("a célula válida não ficou gravada").isNull();
    }

    @Test
    void quantidadeZeroRetiraACotaSemDestinacaoEGravaOHistorico() {
        long parceria = verde(jdbc);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        // Camarote de sábado: cota 1, nada destinado
        MvcTestResult resposta = rbs.patch(uriCotas(parceria), cotas(celula("CAMAROTE", "SABADO", 0, true, null)));

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(linha(resposta, "CAMAROTE", "SABADO")).isNull();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM historico WHERE parceria_id = :p AND entidade = 'cortesia_cota' AND acao = 'EXCLUSAO'
                """).param("p", parceria).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void patchSemMudancaNaoGravaHistoricoEMudancaGravaSoANumeracao() {
        long parceria = verde(jdbc);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long antes = jdbc.sql("SELECT count(*) FROM historico WHERE parceria_id = :p").param("p", parceria).query(Long.class).single();

        // a Arena de sábado da Verde: 3, sem shuttle, acesso "P6" na amostra; mandar o mesmo não muda nada
        String acesso = jdbc.sql("SELECT acesso FROM cortesia_cota WHERE parceria_id = :p AND setor = 'ARENA' AND dia = 'SABADO'")
                .param("p", parceria).query(String.class).single();
        assertThat(rbs.patch(uriCotas(parceria), cotas(celula("ARENA", "SABADO", 3, false, acesso)))).hasStatus(HttpStatus.OK);
        assertThat(jdbc.sql("SELECT count(*) FROM historico WHERE parceria_id = :p").param("p", parceria).query(Long.class).single())
                .isEqualTo(antes);

        assertThat(rbs.patch(uriCotas(parceria), cotas(celula("ARENA", "SABADO", 4, false, acesso)))).hasStatus(HttpStatus.OK);
        Map<String, Object> registro = jdbc.sql("""
                SELECT valor_anterior, valor_novo, detalhe, acao FROM historico
                WHERE parceria_id = :p AND entidade = 'cortesia_cota' ORDER BY id DESC LIMIT 1
                """).param("p", parceria).query().singleRow();
        assertThat(registro).containsEntry("valor_anterior", "3").containsEntry("valor_novo", "4")
                .containsEntry("detalhe", "Arena · sábado").containsEntry("acao", "EDICAO");
    }

    @Test
    void soARbsGravaACotaEMarcaDeOutraParceriaNaoEnxerga() {
        long parceria = verde(jdbc);
        String corpo = cotas(celula("ARENA", "SABADO", 9, false, null));

        // a marca da própria parceria: 403 qualquer que seja o corpo
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, VERDE);
        for (String c : List.of(corpo, "", "{}", "isto nao e json")) {
            assertThat(marca.patch(uriCotas(parceria), c)).as("corpo [" + c + "]").hasStatus(HttpStatus.FORBIDDEN);
        }
        assertThat(linha(SessaoDeApi.entrar(mvc, RBS).get(uri(parceria)), "ARENA", "SABADO")).containsEntry("cota", 3);
        // outra marca na parceria da Verde: a leitura é 404
        assertThat(SessaoDeApi.entrar(mvc, AMARELO).get(uri(parceria))).hasStatus(HttpStatus.NOT_FOUND);
        // parceria que não existe
        assertThat(SessaoDeApi.entrar(mvc, RBS).patch(uriCotas(999_999), corpo)).hasStatus(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------------
    // Rota 82: o prazo de destinação da edição
    // ---------------------------------------------------------------------

    @Test
    void rbsDefineELimpaOPrazoEAMarcaOVeNaRota80() {
        long parceria = verde(jdbc);
        long evento = eventoDe(jdbc, parceria);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, VERDE);
        assertThat(lerJson(marca.get(uri(parceria)), "$.cortesiasPrazo")).as("a amostra não define prazo").isNull();

        MvcTestResult definido = rbs.patch("/api/eventos/" + evento + "/cortesias", "{\"cortesiasPrazo\":\"2027-01-15\"}");

        assertThat(definido).hasStatus(HttpStatus.OK);
        assertThat(lerJson(definido, "$.cortesiasPrazo")).isEqualTo("2027-01-15");
        assertThat(lerJson(definido, "$.eventoId")).isEqualTo((int) evento);
        MvcTestResult lido = marca.get(uri(parceria));
        assertThat(lerJson(lido, "$.cortesiasPrazo")).isEqualTo("2027-01-15");
        assertThat(lerJson(lido, "$.prazoEncerrado")).isEqualTo(false);
        assertThat(jdbc.sql("SELECT valor_novo FROM historico WHERE entidade = 'evento' AND entidade_id = :e ORDER BY id DESC LIMIT 1")
                .param("e", evento).query(String.class).single()).isEqualTo("2027-01-15");

        assertThat(rbs.patch("/api/eventos/" + evento + "/cortesias", "{\"cortesiasPrazo\":null}")).hasStatus(HttpStatus.OK);
        assertThat(lerJson(marca.get(uri(parceria)), "$.cortesiasPrazo")).isNull();
    }

    @Test
    void prazoComCorpoOuDataInvalidosDa422ESemEdicaoDa404() {
        long evento = eventoDe(jdbc, verde(jdbc));
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        for (String c : List.of("{}", "{\"outro\":1}", "{\"cortesiasPrazo\":\"15/01/2027\"}", "{\"cortesiasPrazo\":\"2027-02-30\"}",
                "{\"cortesiasPrazo\":\"\"}", "{\"cortesiasPrazo\":20270115}", "{\"cortesiasPrazo\":\"+300000-01-01\"}")) {
            MvcTestResult resposta = rbs.patch("/api/eventos/" + evento + "/cortesias", c);
            assertThat(resposta).as("corpo " + c).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(corpo(resposta)).contains("cortesiasPrazo");
        }
        assertThat(rbs.patch("/api/eventos/999999/cortesias", "{\"cortesiasPrazo\":\"2027-01-15\"}")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(jdbc.sql("SELECT count(*) FROM evento WHERE id = :e AND cortesias_prazo IS NOT NULL").param("e", evento)
                .query(Long.class).single()).as("nenhum corpo recusado mexeu no prazo").isZero();
    }

    @Test
    void marcaNaoDefinePrazoQualquerQueSejaOCorpo() {
        long evento = eventoDe(jdbc, verde(jdbc));
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, VERDE);

        for (String c : List.of("{\"cortesiasPrazo\":\"2027-01-15\"}", "", "{}", "isto nao e json")) {
            assertThat(marca.patch("/api/eventos/" + evento + "/cortesias", c)).as("corpo [" + c + "]").hasStatus(HttpStatus.FORBIDDEN);
        }
    }
}
