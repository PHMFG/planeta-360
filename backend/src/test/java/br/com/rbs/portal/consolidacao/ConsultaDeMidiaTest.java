package br.com.rbs.portal.consolidacao;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [RELATORIO] [PERMISSAO] As rotas 105 a 108: a consulta da mídia com filtros, da parceria e da RBS, em JSON, .xlsx e CSV.
 * Cada número é conferido contra um SELECT direto em {@code veiculacao} e {@code aproveitamento_previsto}; unidades
 * diferentes nunca se somam; a marca de outra cota não recebe o recorte por mês (M7). Cada teste é desfeito no fim.
 */
@TesteDeIntegracao
@Transactional
class ConsultaDeMidiaTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String ROXO = "marketing@roxo.example";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    long parceria(String cliente) {
        return SessaoDeApi.parceriaDe2027(jdbc, cliente);
    }

    String rota(long parceriaId, String sufixo) {
        return "/api/parcerias/" + parceriaId + "/midia" + sufixo;
    }

    /** Um plano em CPM para a parceria, para haver duas unidades. */
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

    static BigDecimal numero(Object valor) {
        return new BigDecimal(String.valueOf(valor));
    }

    static Map<String, Object> linha(List<Map<String, Object>> linhas, long aproveitamentoId) {
        return linhas.stream().filter(l -> ((Number) l.get("aproveitamentoId")).longValue() == aproveitamentoId)
                .findFirst().orElseThrow(() -> new AssertionError("Sem a linha " + aproveitamentoId));
    }

    static Workbook abrir(MvcTestResult resposta) throws Exception {
        assertThat(resposta).hasStatus(HttpStatus.OK);
        return new XSSFWorkbook(new ByteArrayInputStream(resposta.getResponse().getContentAsByteArray()));
    }

    static String texto(Cell celula) {
        return celula.getStringCellValue();
    }

    // ---------------------------------------------------------------------
    // Rota 105: os números
    // ---------------------------------------------------------------------

    @Test
    void aConsultaDoAmareloTemUmaLinhaPorLinhaDoPlanoESomaOsMeses() {
        long amarelo = parceria("Cliente Amarelo");

        MvcTestResult resposta = SessaoDeApi.entrar(mvc, AMARELO).get(rota(amarelo, ""));

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.parceriaId")).isEqualTo((int) amarelo);
        long planos = jdbc.sql("SELECT count(*) FROM aproveitamento_previsto WHERE parceria_id = :p AND categoria = 'MIDIA'")
                .param("p", amarelo).query(Long.class).single();
        List<Map<String, Object>> linhas = lerLista(resposta, "$.linhas");
        assertThat(linhas).hasSize((int) planos);
        assertThat(lerLista(resposta, "$.linhas[*].mes")).containsOnlyNulls();
        assertThat(lerLista(resposta, "$.meses")).isNotEmpty();

        for (Map<String, Object> l : linhas) {
            long aproveitamento = ((Number) l.get("aproveitamentoId")).longValue();
            BigDecimal previsto = jdbc.sql("SELECT quantidade_prevista FROM aproveitamento_previsto WHERE id = :id")
                    .param("id", aproveitamento).query(BigDecimal.class).single();
            BigDecimal exibido = jdbc.sql("SELECT coalesce(sum(quantidade), 0) FROM veiculacao WHERE aproveitamento_id = :id")
                    .param("id", aproveitamento).query(BigDecimal.class).single();
            BigDecimal impacto = jdbc.sql("SELECT sum(impacto) FROM veiculacao WHERE aproveitamento_id = :id")
                    .param("id", aproveitamento).query(BigDecimal.class).optional().orElse(null);
            assertThat(numero(l.get("previsto"))).as("previsto de " + aproveitamento).isEqualByComparingTo(previsto);
            assertThat(numero(l.get("exibido"))).as("exibido de " + aproveitamento).isEqualByComparingTo(exibido);
            if (impacto == null) {
                assertThat(l.get("impacto")).isNull();
            } else {
                assertThat(numero(l.get("impacto"))).isEqualByComparingTo(impacto);
            }
        }
    }

    @Test
    void osTotaisSaoPorUnidadeENuncaSomamInsercoesComCpm() {
        long amarelo = parceria("Cliente Amarelo");
        long cpm = planoEmCpm(amarelo);
        exibir(cpm, "2030-06-10T15:00:00Z", 30);
        exibir(cpm, "2030-06-11T15:00:00Z", 12);

        MvcTestResult resposta = SessaoDeApi.entrar(mvc, AMARELO).get(rota(amarelo, ""));

        List<Map<String, Object>> totais = lerLista(resposta, "$.totais");
        Map<String, Object> doCpm = totais.stream().filter(t -> "CPM".equals(t.get("unidade"))).findFirst().orElseThrow();
        Map<String, Object> daInsercao = totais.stream().filter(t -> "INSERCAO".equals(t.get("unidade"))).findFirst().orElseThrow();
        assertThat(numero(doCpm.get("exibido"))).isEqualByComparingTo("42");
        assertThat(numero(doCpm.get("previsto"))).isEqualByComparingTo("500");
        assertThat(numero(doCpm.get("percentual"))).isEqualByComparingTo("8.4");
        BigDecimal insercao = jdbc.sql("""
                SELECT coalesce(sum(v.quantidade), 0) FROM veiculacao v JOIN aproveitamento_previsto ap ON ap.id = v.aproveitamento_id
                WHERE ap.parceria_id = :p AND ap.categoria = 'MIDIA' AND ap.unidade = 'INSERCAO'
                """).param("p", amarelo).query(BigDecimal.class).single();
        assertThat(numero(daInsercao.get("exibido"))).isEqualByComparingTo(insercao);
    }

    @Test
    void oMesPedidoTrazSoAqueleMesEALinhaSemExibicaoVemComZero() {
        long amarelo = parceria("Cliente Amarelo");
        long cpm = planoEmCpm(amarelo);
        exibir(cpm, "2030-06-10T15:00:00Z", 30);
        exibir(cpm, "2030-07-10T15:00:00Z", 12);
        long semExibicao = jdbc.sql("""
                SELECT ap.id FROM aproveitamento_previsto ap
                WHERE ap.parceria_id = :p AND ap.categoria = 'MIDIA' AND ap.id <> :cpm
                ORDER BY ap.id LIMIT 1
                """).param("p", amarelo).param("cpm", cpm).query(Long.class).single();

        MvcTestResult resposta = SessaoDeApi.entrar(mvc, AMARELO).get(rota(amarelo, "") + "?mes=2030-06");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        List<Map<String, Object>> linhas = lerLista(resposta, "$.linhas");
        Map<String, Object> doCpm = linha(linhas, cpm);
        assertThat(doCpm.get("mes")).isEqualTo("2030-06");
        assertThat(numero(doCpm.get("exibido"))).isEqualByComparingTo("30");
        assertThat(numero(doCpm.get("percentual"))).isEqualByComparingTo("6.0");
        assertThat(numero(doCpm.get("previsto"))).as("o previsto é o da linha inteira").isEqualByComparingTo("500");
        assertThat(linha(linhas, semExibicao).get("mes")).isEqualTo("2030-06");
        assertThat(numero(linha(linhas, semExibicao).get("exibido"))).isEqualByComparingTo("0");
        assertThat(lerLista(resposta, "$.meses")).contains("2030-06", "2030-07");
    }

    @Test
    void mesSemExibicaoDevolveAsLinhasComZeroEMesmoAssimOsMesesDisponiveis() {
        long amarelo = parceria("Cliente Amarelo");

        MvcTestResult resposta = SessaoDeApi.entrar(mvc, AMARELO).get(rota(amarelo, "") + "?mes=2031-12");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        List<Map<String, Object>> linhas = lerLista(resposta, "$.linhas");
        assertThat(linhas).isNotEmpty();
        assertThat(linhas).allSatisfy(l -> {
            assertThat(l.get("mes")).isEqualTo("2031-12");
            assertThat(numero(l.get("exibido"))).isEqualByComparingTo("0");
        });
        assertThat(lerLista(resposta, "$.meses")).isNotEmpty();
    }

    @Test
    void osFiltrosDeVeiculoFormatoTipoEUnidadeFuncionamJuntos() {
        long amarelo = parceria("Cliente Amarelo");
        SessaoDeApi sessao = SessaoDeApi.entrar(mvc, AMARELO);

        // Em outra caixa (e sem acento, quando houver) o filtro do banco casa: "pioneiro" acha "Pioneiro".
        List<Map<String, Object>> porVeiculo = lerLista(sessao.get(rota(amarelo, "") + "?veiculo=pioneiro"), "$.linhas");
        assertThat(porVeiculo).isNotEmpty();
        assertThat(porVeiculo).allSatisfy(l -> assertThat(l.get("veiculo")).isEqualTo("Pioneiro"));

        List<Map<String, Object>> porFormato = lerLista(sessao.get(rota(amarelo, "") + "?formato=Chamadas"), "$.linhas");
        assertThat(porFormato).isNotEmpty();
        assertThat(porFormato).allSatisfy(l -> assertThat((String) l.get("formato") + " " + l.get("espacoPrograma"))
                .containsIgnoringCase("chamadas"));

        List<Map<String, Object>> porTipo = lerLista(sessao.get(rota(amarelo, "") + "?tipoMidia=ASSINADA"), "$.linhas");
        assertThat(porTipo).isNotEmpty();
        assertThat(porTipo).allSatisfy(l -> assertThat(l.get("tipoMidia")).isEqualTo("ASSINADA"));

        List<Map<String, Object>> porUnidade = lerLista(sessao.get(rota(amarelo, "") + "?unidade=INSERCAO"), "$.linhas");
        assertThat(porUnidade).isNotEmpty();
        assertThat(porUnidade).allSatisfy(l -> assertThat(l.get("unidade")).isEqualTo("INSERCAO"));
    }

    @Test
    void aSubcotaVeSemMesENaoPodePedirORecorte() {
        long roxo = parceria("Cliente Roxo");
        SessaoDeApi sessao = SessaoDeApi.entrar(mvc, ROXO);

        MvcTestResult comMes = sessao.get(rota(roxo, "") + "?mes=2026-01");
        assertThat(comMes).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(comMes, "$.campos.mes")).isEqualTo(ConsultaDeMidiaService.MES_SO_MASTER);

        MvcTestResult semMes = sessao.get(rota(roxo, ""));
        assertThat(semMes).hasStatus(HttpStatus.OK);
        assertThat(corpo(semMes)).doesNotContain("\"meses\"");
        assertThat(lerLista(semMes, "$.linhas[*].mes")).isEmpty();
        assertThat(lerLista(semMes, "$.linhas")).isNotEmpty();
    }

    @Test
    void mesMalformadoDa422ParaAMaster() {
        long amarelo = parceria("Cliente Amarelo");

        MvcTestResult marca = SessaoDeApi.entrar(mvc, AMARELO).get(rota(amarelo, "") + "?mes=2026-13");
        assertThat(marca).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(marca, "$.campos.mes")).isEqualTo(ComprovacaoMensalService.MES_INVALIDO);
    }

    // ---------------------------------------------------------------------
    // Rota 105: permissões
    // ---------------------------------------------------------------------

    @Test
    void semSessaoDa401EMarcaDeOutraParceriaDa404EAbaFechadaDa403() {
        long amarelo = parceria("Cliente Amarelo");
        long roxo = parceria("Cliente Roxo");

        assertThat(SessaoDeApi.anonima(mvc).get(rota(amarelo, ""))).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(SessaoDeApi.entrar(mvc, ROXO).get(rota(amarelo, ""))).hasStatus(HttpStatus.NOT_FOUND);

        jdbc.sql("UPDATE parceria_aba SET liberada = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'MIDIA')")
                .param("p", roxo).update();
        MvcTestResult fechada = SessaoDeApi.entrar(mvc, ROXO).get(rota(roxo, ""));
        assertThat(fechada).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(fechada, "$.detail")).isEqualTo("Esta aba ainda não foi liberada para a sua parceria.");
    }

    @Test
    void aRbsLeQualquerParceriaInclusiveDeSubcota() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        assertThat(rbs.get(rota(parceria("Cliente Amarelo"), ""))).hasStatus(HttpStatus.OK);
        assertThat(rbs.get(rota(parceria("Cliente Roxo"), ""))).hasStatus(HttpStatus.OK);
        assertThat(rbs.get(rota(parceria("Cliente Roxo"), ".csv"))).hasStatus(HttpStatus.OK);
    }

    // ---------------------------------------------------------------------
    // Comprovantes e links
    // ---------------------------------------------------------------------

    @Test
    void cadaLinhaTrazOsComprovantesEOsLinksValidos() {
        long amarelo = parceria("Cliente Amarelo");
        long aproveitamento = jdbc.sql("""
                SELECT id FROM aproveitamento_previsto WHERE parceria_id = :p AND categoria = 'MIDIA' ORDER BY id LIMIT 1
                """).param("p", amarelo).query(Long.class).single();
        Map<String, Object> arquivo = jdbc.sql("""
                SELECT id, nome_original FROM arquivo WHERE parceria_id = :p AND nome_original LIKE '%.jpg' ORDER BY id LIMIT 1
                """).param("p", amarelo).query().singleRow();
        jdbc.sql("""
                INSERT INTO veiculacao (aproveitamento_id, exibido_em, quantidade, comprovante_arquivo_id, link)
                VALUES (:a, '2030-09-10T15:00:00Z', 1, :arquivo, 'javascript:alert(1)')
                """).param("a", aproveitamento).param("arquivo", ((Number) arquivo.get("id")).longValue()).update();
        jdbc.sql("""
                INSERT INTO veiculacao (aproveitamento_id, exibido_em, quantidade, link)
                VALUES (:a, '2030-09-11T15:00:00Z', 1, 'https://exemplo.example/post/1')
                """).param("a", aproveitamento).update();

        MvcTestResult resposta = SessaoDeApi.entrar(mvc, AMARELO).get(rota(amarelo, ""));
        Map<String, Object> linha = linha(lerLista(resposta, "$.linhas"), aproveitamento);

        List<Map<String, Object>> comprovantes = (List<Map<String, Object>>) linha.get("comprovantes");
        assertThat(comprovantes).anySatisfy(c -> {
            assertThat(((Number) c.get("arquivoId")).longValue()).isEqualTo(((Number) arquivo.get("id")).longValue());
            assertThat(c.get("nome")).isEqualTo(arquivo.get("nome_original"));
            assertThat(c.get("tipo")).isEqualTo("JPG");
        });
        List<String> links = (List<String>) linha.get("links");
        assertThat(links).contains("https://exemplo.example/post/1").doesNotContain("javascript:alert(1)");
    }

    @Test
    void osLinksVemAteCincoDoMaisRecenteAoMaisAntigo() {
        long roxo = parceria("Cliente Roxo");

        MvcTestResult resposta = SessaoDeApi.entrar(mvc, ROXO).get(rota(roxo, ""));

        List<Map<String, Object>> linhas = lerLista(resposta, "$.linhas");
        List<String> comLink = linhas.stream().filter(l -> !((List<?>) l.get("links")).isEmpty())
                .flatMap(l -> ((List<String>) l.get("links")).stream()).toList();
        assertThat(comLink).isNotEmpty().allMatch(l -> l.startsWith("https://"));
        assertThat(linhas).allSatisfy(l -> assertThat((List<?>) l.get("links")).hasSizeLessThanOrEqualTo(5));
    }

    // ---------------------------------------------------------------------
    // Rotas 106 e 108: os arquivos
    // ---------------------------------------------------------------------

    @Test
    void oXlsxDaParceriaTemAsColunasEAsLinhasDoPlanoELePeloPoi() throws Exception {
        long amarelo = parceria("Cliente Amarelo");
        long planos = jdbc.sql("SELECT count(*) FROM aproveitamento_previsto WHERE parceria_id = :p AND categoria = 'MIDIA'")
                .param("p", amarelo).query(Long.class).single();

        MvcTestResult resposta = SessaoDeApi.entrar(mvc, AMARELO).get(rota(amarelo, ".xlsx"));

        assertThat(resposta.getResponse().getContentType()).startsWith("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(resposta.getResponse().getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"midia-cliente-amarelo.xlsx\"");
        try (Workbook livro = abrir(resposta)) {
            Sheet aba = livro.getSheetAt(0);
            assertThat(aba.getSheetName()).isEqualTo("Mídia");
            assertThat(aba.getLastRowNum()).isEqualTo((int) planos);
            Row cabecalho = aba.getRow(0);
            assertThat(List.of(texto(cabecalho.getCell(0)), texto(cabecalho.getCell(1)), texto(cabecalho.getCell(2)),
                    texto(cabecalho.getCell(3)), texto(cabecalho.getCell(4)), texto(cabecalho.getCell(5)),
                    texto(cabecalho.getCell(6)), texto(cabecalho.getCell(7)), texto(cabecalho.getCell(8)),
                    texto(cabecalho.getCell(9)), texto(cabecalho.getCell(10))))
                    .containsExactly("Mês", "Veículo", "Formato", "Espaço ou programa", "Tipo de mídia", "Unidade",
                            "Previsto (linha do plano)", "Exibido", "Entrega (%)", "Impacto", "Comprovantes");
        }
    }

    @Test
    void oCsvDaParceriaComMesTrazSoOMesEPedeOMesmoNome() {
        long amarelo = parceria("Cliente Amarelo");
        long cpm = planoEmCpm(amarelo);
        exibir(cpm, "2030-06-10T15:00:00Z", 30);
        exibir(cpm, "2030-07-10T15:00:00Z", 12);

        MvcTestResult resposta = SessaoDeApi.entrar(mvc, AMARELO).get(rota(amarelo, ".csv") + "?mes=2030-06");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(resposta.getResponse().getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"midia-cliente-amarelo-2030-06.csv\"");
        String texto = new String(resposta.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        String semBom = texto.substring(1);   // o CSV sai com BOM UTF-8
        assertThat(semBom).startsWith("Mês;Veículo;Formato;Espaço ou programa;Tipo de mídia;Unidade;Previsto (linha do plano);Exibido;Entrega (%);Impacto;Comprovantes");
        assertThat(semBom).contains("2030-06;").doesNotContain("2030-07;");
        // Tipo e unidade como a tela mostra, não o código do banco.
        assertThat(semBom).contains(";Assinada;CPM;").doesNotContain(";ASSINADA;");
    }

    @Test
    void cadaDownloadGravaNoHistoricoDaEntidadeMidia() {
        long amarelo = parceria("Cliente Amarelo");
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        rbs.get(rota(amarelo, ".xlsx"));
        rbs.get(rota(amarelo, ".csv"));

        List<Map<String, Object>> linhas = jdbc.sql("""
                SELECT parceria_id, entidade_id, detalhe FROM historico
                WHERE entidade = 'midia' AND acao = 'DOWNLOAD' ORDER BY id
                """).query().listOfRows();
        assertThat(linhas).hasSize(2);
        assertThat(linhas).allSatisfy(l -> assertThat(((Number) l.get("parceria_id")).longValue()).isEqualTo(amarelo));
        assertThat(linhas).extracting(l -> l.get("detalhe")).containsExactly("xlsx", "csv");
    }

}
