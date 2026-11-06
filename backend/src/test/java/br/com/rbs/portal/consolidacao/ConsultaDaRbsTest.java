package br.com.rbs.portal.consolidacao;

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
 * [RELATORIO] [PERMISSAO] As rotas 107 e 108: a consulta da mídia de todas as parcerias da edição, com a marca em cada
 * linha, em JSON, .xlsx e CSV. Só a RBS; a edição padrão é a mais nova. Cada teste é desfeito no fim.
 */
@TesteDeIntegracao
@Transactional
class ConsultaDaRbsTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    long parceria(String cliente) {
        return SessaoDeApi.parceriaDe2027(jdbc, cliente);
    }

    long evento(int edicao) {
        return jdbc.sql("SELECT id FROM evento WHERE edicao = :e").param("e", edicao).query(Long.class).single();
    }

    static BigDecimal numero(Object valor) {
        return new BigDecimal(String.valueOf(valor));
    }

    static Workbook abrir(MvcTestResult resposta) throws Exception {
        assertThat(resposta).hasStatus(HttpStatus.OK);
        return new XSSFWorkbook(new ByteArrayInputStream(resposta.getResponse().getContentAsByteArray()));
    }

    static String texto(Cell celula) {
        return celula.getStringCellValue();
    }

    @Test
    void aConsultaDaRbsUsaAEdicaoMaisNovaEDeixaFiltrarPorParceriaECota() {
        long amarelo = parceria("Cliente Amarelo");
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult todas = rbs.get("/api/midia");
        assertThat(todas).hasStatus(HttpStatus.OK);
        assertThat(lerJson(todas, "$.filtros.eventoId")).isEqualTo((int) evento(2027));
        List<Map<String, Object>> linhas = lerLista(todas, "$.linhas");
        assertThat(linhas).isNotEmpty();
        assertThat(linhas).allSatisfy(l -> assertThat(l.get("parceria")).isNotNull());

        List<Map<String, Object>> daParceria = lerLista(rbs.get("/api/midia?parceriaId=" + amarelo), "$.linhas");
        assertThat(daParceria).isNotEmpty();
        assertThat(daParceria).allSatisfy(l -> assertThat(((Number) ((Map<?, ?>) l.get("parceria")).get("id")).longValue())
                .isEqualTo(amarelo));

        List<Map<String, Object>> daCota = lerLista(rbs.get("/api/midia?cota=MASTER"), "$.linhas");
        assertThat(daCota).isNotEmpty();
        assertThat(daCota).allSatisfy(l -> assertThat(((Map<?, ?>) l.get("parceria")).get("cota")).isEqualTo("MASTER"));

        MvcTestResult de2026 = rbs.get("/api/midia?eventoId=" + evento(2026));
        assertThat(de2026).hasStatus(HttpStatus.OK);
        assertThat(lerLista(de2026, "$.linhas")).isEmpty();
        assertThat(lerLista(de2026, "$.meses")).isEmpty();
    }

    @Test
    void aConsultaDaRbsESoDelaEEdicaoQueNaoExisteDa404() {
        SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        assertThat(SessaoDeApi.anonima(mvc).get("/api/midia")).hasStatus(HttpStatus.UNAUTHORIZED);
        MvcTestResult daMarca = amarelo.get("/api/midia");
        assertThat(daMarca).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(daMarca, "$.detail")).isEqualTo("Esta operação é restrita à equipe da RBS.");
        MvcTestResult inexistente = rbs.get("/api/midia?eventoId=999999");
        assertThat(inexistente).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(inexistente, "$.detail")).isEqualTo("Não encontramos esta edição do festival.");
    }

    @Test
    void mesMalformadoDa422NaConsultaDaRbs() {
        MvcTestResult resposta = SessaoDeApi.entrar(mvc, RBS).get("/api/midia?mes=2026-1");

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(resposta, "$.campos.mes")).isEqualTo(ComprovacaoMensalService.MES_INVALIDO);
    }

    @Test
    void osTotaisDaRbsSomamAsParceriasPorUnidade() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult resposta = rbs.get("/api/midia");

        List<Map<String, Object>> totais = lerLista(resposta, "$.totais");
        assertThat(totais).isNotEmpty();
        for (Map<String, Object> total : totais) {
            String unidade = (String) total.get("unidade");
            BigDecimal exibido = jdbc.sql("""
                    SELECT coalesce(sum(v.quantidade), 0) FROM veiculacao v
                    JOIN aproveitamento_previsto ap ON ap.id = v.aproveitamento_id
                    JOIN parceria pa ON pa.id = ap.parceria_id
                    WHERE pa.evento_id = :e AND ap.categoria = 'MIDIA' AND ap.unidade = :u
                    """).param("e", evento(2027)).param("u", unidade).query(BigDecimal.class).single();
            assertThat(numero(total.get("exibido"))).as(unidade).isEqualByComparingTo(exibido);
        }
    }

    @Test
    void oXlsxDaRbsTemAMarcaPrimeiroEOCsvTambem() throws Exception {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult xlsx = rbs.get("/api/midia.xlsx");
        assertThat(xlsx.getResponse().getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"midia-edicao-2027.xlsx\"");
        try (Workbook livro = abrir(xlsx)) {
            Row cabecalho = livro.getSheetAt(0).getRow(0);
            assertThat(texto(cabecalho.getCell(0))).isEqualTo("Marca");
            assertThat(texto(cabecalho.getCell(1))).isEqualTo("Mês");
        }

        MvcTestResult csv = rbs.get("/api/midia.csv");
        assertThat(csv).hasStatus(HttpStatus.OK);
        String textoSemBom = new String(csv.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8).substring(1);
        assertThat(textoSemBom)
                .startsWith("Marca;Mês;Veículo;Formato;Espaço ou programa;Tipo de mídia;Unidade;Previsto (linha do plano);Exibido;Entrega (%);Impacto;Comprovantes");
        List<Map<String, Object>> linhas = jdbc.sql("""
                SELECT parceria_id, entidade_id, detalhe FROM historico
                WHERE entidade = 'midia' AND acao = 'DOWNLOAD' ORDER BY id
                """).query().listOfRows();
        assertThat(linhas).extracting(l -> l.get("detalhe")).containsExactly("xlsx da edição 2027", "csv da edição 2027");
    }
}
