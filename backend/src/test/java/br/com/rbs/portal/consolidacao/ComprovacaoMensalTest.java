package br.com.rbs.portal.consolidacao;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [RELATORIO] [PERMISSAO] A comprovação mensal (rotas 88 a 90; R3.2, R3.3 e E2.5). Cada linha é conferida contra um SELECT
 * feito aqui, direto em {@code veiculacao}, agrupado por mês (Brasília), tipo de mídia e unidade; unidades diferentes nunca se
 * somam; o total do mês bate com a consolidação (rota 29). Roxo (Subcota G) e Verde (Subcota) recebem 403 com a frase de cota e
 * a marca de outra parceria recebe 404. Cada teste é desfeito no fim.
 */
@TesteDeIntegracao
@Transactional
class ComprovacaoMensalTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String ROXO = "marketing@roxo.example";
    private static final String VERDE = "marketing@verde.example";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    long parceria(String patrocinador) {
        return SessaoDeApi.parceriaDe2027(jdbc, patrocinador);
    }

    String rota(long parceriaId, String sufixo) {
        return "/api/parcerias/" + parceriaId + "/comprovacao-mensal" + sufixo;
    }

    /** O SELECT de conferência do plano (mapa 2, seção 1.5), por mês x tipo x unidade, direto nas tabelas. */
    Map<String, BigDecimal> esperado(long parceriaId) {
        Map<String, BigDecimal> mapa = new TreeMap<>();
        jdbc.sql("""
                SELECT to_char(v.exibido_em AT TIME ZONE 'America/Sao_Paulo', 'YYYY-MM') AS mes, ap.tipo_midia, ap.unidade,
                       sum(v.quantidade) AS soma
                FROM veiculacao v JOIN aproveitamento_previsto ap ON ap.id = v.aproveitamento_id
                WHERE ap.parceria_id = :p AND ap.categoria = 'MIDIA'
                GROUP BY 1, 2, 3
                """).param("p", parceriaId).query((rs, n) -> {
            mapa.put(rs.getString("mes") + "|" + rs.getString("tipo_midia") + "|" + rs.getString("unidade"), rs.getBigDecimal("soma"));
            return null;
        }).list();
        return mapa;
    }

    Map<String, BigDecimal> devolvido(MvcTestResult resposta) {
        Map<String, BigDecimal> mapa = new TreeMap<>();
        List<Map<String, Object>> linhas = lerLista(resposta, "$.linhas");
        for (Map<String, Object> l : linhas) {
            String chave = l.get("mes") + "|" + l.get("tipoMidia") + "|" + l.get("unidade");
            assertThat(mapa).as("linha repetida " + chave).doesNotContainKey(chave);
            mapa.put(chave, new BigDecimal(String.valueOf(l.get("exibido"))));
        }
        return mapa;
    }

    void assertIgual(Map<String, BigDecimal> obtido, Map<String, BigDecimal> esperado) {
        assertThat(obtido.keySet()).containsExactlyElementsOf(esperado.keySet());
        esperado.forEach((chave, soma) -> assertThat(obtido.get(chave)).as(chave).isEqualByComparingTo(soma));
    }

    /** Um plano em CPM para o Amarelo, para haver duas unidades no mesmo mês e tipo. */
    long planoEmCpm(long parceriaId) {
        return jdbc.sql("""
                INSERT INTO aproveitamento_previsto (parceria_id, categoria, veiculo, formato, espaco_programa, tipo_midia,
                                                     quantidade_prevista, unidade)
                VALUES (:p, 'MIDIA', 'Rede Atlântida RS', 'Formato de teste em CPM', 'Teste', 'ASSINADA', 500, 'CPM') RETURNING id
                """).param("p", parceriaId).query(Long.class).single();
    }

    void exibir(long aproveitamento, String instante, int quantidade) {
        jdbc.sql("INSERT INTO veiculacao (aproveitamento_id, exibido_em, quantidade) VALUES (:a, CAST(:t AS timestamptz), :q)")
                .param("a", aproveitamento).param("t", instante).param("q", quantidade).update();
    }

    // ---------------------------------------------------------------------
    // R3.2 · os números
    // ---------------------------------------------------------------------

    @Test
    void cadaLinhaDoAmareloEIgualASomaDasVeiculacoesPorMesTipoEUnidade() {
        long amarelo = parceria("Cliente Amarelo");

        MvcTestResult resposta = SessaoDeApi.entrar(mvc, AMARELO).get(rota(amarelo, ""));

        assertThat(resposta).hasStatus(HttpStatus.OK);
        Map<String, BigDecimal> esperado = esperado(amarelo);
        assertThat(esperado.keySet().stream().map(c -> c.split("\\|")[0]).distinct().count())
                .as("a amostra espalha as exibições do Amarelo em ao menos 3 meses").isGreaterThanOrEqualTo(3);
        assertIgual(devolvido(resposta), esperado);
        assertThat(((Number) lerJson(resposta, "$.parceriaId")).longValue()).isEqualTo(amarelo);
        assertThat(lerJson(resposta, "$.patrocinador")).isEqualTo("Cliente Amarelo");
        // O previsto da linha é o do plano inteiro para o tipo e a unidade.
        List<Map<String, Object>> linhas = lerLista(resposta, "$.linhas");
        for (Map<String, Object> l : linhas) {
            BigDecimal previsto = jdbc.sql("""
                    SELECT sum(quantidade_prevista) FROM aproveitamento_previsto
                    WHERE parceria_id = :p AND categoria = 'MIDIA' AND tipo_midia = :t AND unidade = :u
                    """).param("p", amarelo).param("t", l.get("tipoMidia")).param("u", l.get("unidade")).query(BigDecimal.class).single();
            assertThat(new BigDecimal(String.valueOf(l.get("previsto")))).isEqualByComparingTo(previsto);
        }
    }

    @Test
    void unidadesDiferentesNuncaSeSomamNemNoMesmoMesETipo() {
        long amarelo = parceria("Cliente Amarelo");
        long cpm = planoEmCpm(amarelo);
        // Duas exibições em CPM num mês que já tem inserções do mesmo tipo (ASSINADA).
        exibir(cpm, "2030-06-10T15:00:00Z", 30);
        exibir(cpm, "2030-06-11T15:00:00Z", 12);
        long insercao = jdbc.sql("SELECT id FROM aproveitamento_previsto WHERE parceria_id = :p AND categoria = 'MIDIA' "
                + "AND tipo_midia = 'ASSINADA' AND unidade = 'INSERCAO' ORDER BY id LIMIT 1").param("p", amarelo).query(Long.class).single();
        exibir(insercao, "2030-06-12T15:00:00Z", 5);

        MvcTestResult resposta = SessaoDeApi.entrar(mvc, AMARELO).get(rota(amarelo, ""));

        Map<String, BigDecimal> obtido = devolvido(resposta);
        assertThat(obtido.get("2030-06|ASSINADA|CPM")).isEqualByComparingTo("42");
        assertThat(obtido.get("2030-06|ASSINADA|INSERCAO")).isEqualByComparingTo("5");
        assertIgual(obtido, esperado(amarelo));
        // Os totais do mês também são por unidade.
        List<Map<String, Object>> totais = lerLista(resposta, "$.totais");
        assertThat(totais.stream().filter(t -> "2030-06".equals(t.get("mes"))).map(t -> t.get("unidade")))
                .containsExactlyInAnyOrder("INSERCAO", "CPM");
    }

    @Test
    void oMesVemDaDataDeExibicaoNoHorarioDeBrasilia() {
        long amarelo = parceria("Cliente Amarelo");
        long cpm = planoEmCpm(amarelo);
        exibir(cpm, "2030-04-01T02:30:00Z", 7);   // 31/03 às 23h30 em Brasília
        exibir(cpm, "2030-04-01T03:10:00Z", 11);  // 01/04 às 00h10 em Brasília

        Map<String, BigDecimal> obtido = devolvido(SessaoDeApi.entrar(mvc, AMARELO).get(rota(amarelo, "")));

        assertThat(obtido.get("2030-03|ASSINADA|CPM")).isEqualByComparingTo("7");
        assertThat(obtido.get("2030-04|ASSINADA|CPM")).isEqualByComparingTo("11");
    }

    @Test
    void aSomaDoMesBateComAConsolidacaoDaRota29() {
        long amarelo = parceria("Cliente Amarelo");
        long cpm = planoEmCpm(amarelo);
        exibir(cpm, "2030-06-10T15:00:00Z", 30);
        SessaoDeApi sessao = SessaoDeApi.entrar(mvc, AMARELO);

        MvcTestResult comprovacao = sessao.get(rota(amarelo, ""));
        MvcTestResult consolidacao = sessao.get("/api/parcerias/" + amarelo + "/consolidacao");

        Map<String, BigDecimal> mesesPorUnidade = new TreeMap<>();
        List<Map<String, Object>> totais = lerLista(comprovacao, "$.totais");
        for (Map<String, Object> t : totais) {
            mesesPorUnidade.merge(String.valueOf(t.get("unidade")), new BigDecimal(String.valueOf(t.get("exibido"))), BigDecimal::add);
        }
        List<Map<String, Object>> porUnidade = lerLista(consolidacao, "$.midia.porUnidade");
        assertThat(porUnidade).isNotEmpty();
        assertThat(mesesPorUnidade.keySet()).containsExactlyInAnyOrderElementsOf(porUnidade.stream().map(u -> (String) u.get("unidade")).toList());
        for (Map<String, Object> unidade : porUnidade) {
            assertThat(mesesPorUnidade.get((String) unidade.get("unidade")))
                    .as("soma dos meses em " + unidade.get("unidade") + " x consolidação")
                    .isEqualByComparingTo(new BigDecimal(String.valueOf(unidade.get("exibido"))));
        }
    }

    @Test
    void osTotaisPorMesEUnidadeSaoASomaDasLinhas() {
        long amarelo = parceria("Cliente Amarelo");

        MvcTestResult resposta = SessaoDeApi.entrar(mvc, RBS).get(rota(amarelo, ""));

        Map<String, BigDecimal> somas = new TreeMap<>();
        esperado(amarelo).forEach((chave, soma) -> {
            String[] p = chave.split("\\|");
            somas.merge(p[0] + "|" + p[2], soma, BigDecimal::add);
        });
        Map<String, BigDecimal> totais = new TreeMap<>();
        List<Map<String, Object>> lista = lerLista(resposta, "$.totais");
        for (Map<String, Object> t : lista) {
            totais.put(t.get("mes") + "|" + t.get("unidade"), new BigDecimal(String.valueOf(t.get("exibido"))));
        }
        assertIgual(totais, somas);
    }

    // ---------------------------------------------------------------------
    // R3.3 · quem recebe
    // ---------------------------------------------------------------------

    @Test
    void roxoEVerdeRecebem403ComAFraseDeCotaNasTresRotas() {
        for (String email : List.of(ROXO, VERDE)) {
            SessaoDeApi marca = SessaoDeApi.entrar(mvc, email);
            long propria = parceria(email.equals(ROXO) ? "Cliente Roxo" : "Cliente Verde");
            for (String sufixo : List.of("", ".xlsx", ".csv")) {
                MvcTestResult resposta = marca.get(rota(propria, sufixo));
                assertThat(resposta).as(email + " " + sufixo).hasStatus(HttpStatus.FORBIDDEN);
                assertThat(lerJson(resposta, "$.detail")).isEqualTo(ComprovacaoMensalService.SO_AS_MASTER);
            }
        }
    }

    @Test
    void marcaEmParceriaDeOutraMarcaRecebe404() {
        long amarelo = parceria("Cliente Amarelo");
        SessaoDeApi roxo = SessaoDeApi.entrar(mvc, ROXO);

        for (String sufixo : List.of("", ".xlsx", ".csv")) {
            assertThat(roxo.get(rota(amarelo, sufixo))).as(sufixo).hasStatus(HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void rbsLeQualquerParceriaInclusiveDeSubcota() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        assertThat(rbs.get(rota(parceria("Cliente Amarelo"), ""))).hasStatus(HttpStatus.OK);
        assertThat(rbs.get(rota(parceria("Cliente Roxo"), ""))).hasStatus(HttpStatus.OK);
        assertThat(rbs.get(rota(parceria("Cliente Verde"), ".csv"))).hasStatus(HttpStatus.OK);
    }

    @Test
    void abaMidiaFechadaDa403DeAbaParaAMasterEOLoteNegaParaAMarca() {
        long amarelo = parceria("Cliente Amarelo");
        jdbc.sql("UPDATE parceria_aba SET liberada = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'MIDIA')")
                .param("p", amarelo).update();

        MvcTestResult resposta = SessaoDeApi.entrar(mvc, AMARELO).get(rota(amarelo, ".csv"));

        assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("Esta aba ainda não foi liberada para a sua parceria.");
        long evento = jdbc.sql("SELECT evento_id FROM parceria WHERE id = :p").param("p", amarelo).query(Long.class).single();
        assertThat(SessaoDeApi.entrar(mvc, AMARELO).get("/api/eventos/" + evento + "/comprovacao-mensal.xlsx"))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------------
    // Os arquivos
    // ---------------------------------------------------------------------

    @Test
    void csvTemBomUtf8PontoEVirgulaDecimalComVirgulaENeutralizaFormula() throws Exception {
        long amarelo = parceria("Cliente Amarelo");
        jdbc.sql("UPDATE patrocinador SET nome_canonico = '=HYPERLINK(\"http://x\")' WHERE nome_canonico = 'Cliente Amarelo'").update();
        long cpm = planoEmCpm(amarelo);
        exibir(cpm, "2030-06-10T15:00:00Z", 30);

        MvcTestResult resposta = SessaoDeApi.entrar(mvc, RBS).get(rota(amarelo, ".csv"));

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(resposta.getResponse().getContentType()).startsWith("text/csv");
        assertThat(resposta.getResponse().getHeader("Content-Disposition")).startsWith("attachment").contains("comprovacao-mensal-");
        byte[] bytes = resposta.getResponse().getContentAsByteArray();
        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String texto = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        String[] linhas = texto.split("\r\n");
        assertThat(linhas[0]).isEqualTo("Marca;Mês;Tipo de mídia;Unidade;Exibido no mês;Previsto no plano");
        assertThat(linhas.length - 1).isEqualTo(esperado(amarelo).size());
        // Formula nunca executa: o nome da marca começa com "=" e sai com apóstrofo.
        assertThat(linhas[1]).startsWith("\"'=HYPERLINK(\"\"http://x\"\")\";");
        assertThat(texto).contains(";2030-06;Assinada;CPM;30;500");
    }

    @Test
    void oMesPedidoTrazSoAqueleMesNoCsvENoXlsx() {
        long amarelo = parceria("Cliente Amarelo");
        long cpm = planoEmCpm(amarelo);
        exibir(cpm, "2030-06-10T15:00:00Z", 30);
        exibir(cpm, "2030-07-10T15:00:00Z", 12);
        SessaoDeApi sessao = SessaoDeApi.entrar(mvc, AMARELO);

        MvcTestResult csv = sessao.get(rota(amarelo, ".csv") + "?mes=2030-06");
        MvcTestResult xlsx = sessao.get(rota(amarelo, ".xlsx") + "?mes=2030-06");

        assertThat(csv).hasStatus(HttpStatus.OK);
        String texto = new String(csv.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(texto).contains(";2030-06;Assinada;CPM;30;500");
        assertThat(texto).doesNotContain(";2030-07;");
        assertThat(xlsx).hasStatus(HttpStatus.OK);
    }

    @Test
    void mesSemExibicaoDevolveSoOCabecalho() {
        long amarelo = parceria("Cliente Amarelo");

        MvcTestResult csv = SessaoDeApi.entrar(mvc, AMARELO).get(rota(amarelo, ".csv") + "?mes=2031-12");

        assertThat(csv).hasStatus(HttpStatus.OK);
        String texto = new String(csv.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        String[] linhas = texto.substring(1).split("\r\n");
        assertThat(linhas).hasSize(1);
        assertThat(linhas[0]).isEqualTo("Marca;Mês;Tipo de mídia;Unidade;Exibido no mês;Previsto no plano");
    }

    @Test
    void mesMalformadoDa422ComCamposMesNasDuasRotas() {
        long amarelo = parceria("Cliente Amarelo");
        SessaoDeApi sessao = SessaoDeApi.entrar(mvc, AMARELO);

        for (String sufixo : List.of(".xlsx", ".csv")) {
            for (String mes : List.of("2026-13", "2026-1", "abc", "")) {
                MvcTestResult resposta = sessao.get(rota(amarelo, sufixo) + "?mes=" + mes);
                assertThat(resposta).as(sufixo + " ?mes=" + mes).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(lerJson(resposta, "$.campos.mes")).isEqualTo(ComprovacaoMensalService.MES_INVALIDO);
            }
        }
    }

    @Test
    void xlsxVemComOTipoEOAnexoCertos() {
        long amarelo = parceria("Cliente Amarelo");

        MvcTestResult resposta = SessaoDeApi.entrar(mvc, AMARELO).get(rota(amarelo, ".xlsx"));

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(resposta.getResponse().getContentType()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(resposta.getResponse().getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"comprovacao-mensal-cliente-amarelo.xlsx\"");
        assertThat(resposta.getResponse().getContentAsByteArray()).startsWith((byte) 'P', (byte) 'K');
    }

    @Test
    void cadaDownloadGravaNoHistoricoDaParceria() {
        long amarelo = parceria("Cliente Amarelo");
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);

        marca.get(rota(amarelo, ".xlsx"));
        marca.get(rota(amarelo, ".csv"));
        marca.get(rota(amarelo, ""));   // o JSON não é arquivo: não grava

        List<String> detalhes = jdbc.sql("SELECT detalhe FROM historico WHERE parceria_id = :p AND entidade = 'comprovacao_mensal' "
                + "AND acao = 'DOWNLOAD' ORDER BY id").param("p", amarelo).query(String.class).list();
        assertThat(detalhes).containsExactly("xlsx", "csv");
    }
}
