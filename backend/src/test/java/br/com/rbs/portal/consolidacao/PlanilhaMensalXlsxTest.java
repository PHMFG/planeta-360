package br.com.rbs.portal.consolidacao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.comum.PlanilhaXlsx;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
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
 * [RELATORIO] [SEGURANCA] O {@code .xlsx} da comprovação mensal (rotas 89 e 91), lido por um leitor independente: o Apache POI,
 * que só existe no escopo de teste (o gravador é o {@link PlanilhaXlsx}, sem dependência). Confere os mesmos números do SELECT
 * de conferência, a ausência de fórmula, o escape de texto que começa com {@code =}, os nomes de aba e o lote da rota 91
 * (uma aba por parceria Master da edição). Cada teste é desfeito no fim.
 */
@TesteDeIntegracao
@Transactional
class PlanilhaMensalXlsxTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String ROXO = "marketing@roxo.example";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    long evento() {
        return jdbc.sql("SELECT evento_id FROM parceria WHERE id = :p").param("p", amarelo()).query(Long.class).single();
    }

    static Workbook abrir(MvcTestResult resposta) throws Exception {
        assertThat(resposta).hasStatus(HttpStatus.OK);
        return new XSSFWorkbook(new ByteArrayInputStream(resposta.getResponse().getContentAsByteArray()));
    }

    static String texto(Cell celula) {
        return celula.getStringCellValue();
    }

    /** Soma de veiculacao.quantidade por mês x tipo x unidade, nos nomes que a planilha mostra. */
    Map<String, BigDecimal> esperado(long parceriaId) {
        Map<String, BigDecimal> mapa = new TreeMap<>();
        jdbc.sql("""
                SELECT to_char(v.exibido_em AT TIME ZONE 'America/Sao_Paulo', 'YYYY-MM') AS mes,
                       CASE ap.tipo_midia WHEN 'ASSINADA' THEN 'Assinada' WHEN 'PROPRIA' THEN 'Própria' WHEN 'MERCHANDISING' THEN 'Merchandising'
                                          WHEN 'BRANDED' THEN 'Branded' ELSE 'Conteúdo de marca' END AS tipo,
                       CASE ap.unidade WHEN 'INSERCAO' THEN 'Inserções' WHEN 'CPM' THEN 'CPM' ELSE 'Aplicações' END AS unidade,
                       sum(v.quantidade) AS soma
                FROM veiculacao v JOIN aproveitamento_previsto ap ON ap.id = v.aproveitamento_id
                WHERE ap.parceria_id = :p AND ap.categoria = 'MIDIA'
                GROUP BY 1, 2, 3
                """).param("p", parceriaId).query((rs, n) -> {
            mapa.put(rs.getString("mes") + "|" + rs.getString("tipo") + "|" + rs.getString("unidade"), rs.getBigDecimal("soma"));
            return null;
        }).list();
        return mapa;
    }

    /** Lê as linhas de dados (da segunda em diante) da primeira aba: chave mês|tipo|unidade e o número da coluna "Exibido". */
    static Map<String, BigDecimal> lido(Sheet aba) {
        Map<String, BigDecimal> mapa = new TreeMap<>();
        for (int i = 1; i <= aba.getLastRowNum(); i++) {
            Row linha = aba.getRow(i);
            assertThat(linha.getCell(4).getCellType()).as("quantidade é número").isEqualTo(CellType.NUMERIC);
            String chave = texto(linha.getCell(1)) + "|" + texto(linha.getCell(2)) + "|" + texto(linha.getCell(3));
            assertThat(mapa).doesNotContainKey(chave);
            mapa.put(chave, BigDecimal.valueOf(linha.getCell(4).getNumericCellValue()));
        }
        return mapa;
    }

    // ---------------------------------------------------------------------
    // Os números
    // ---------------------------------------------------------------------

    @Test
    void oXlsxDoAmareloTemOsMesmosNumerosDoSelectDeConferencia() throws Exception {
        long amarelo = amarelo();

        try (Workbook livro = abrir(SessaoDeApi.entrar(mvc, AMARELO).get("/api/parcerias/" + amarelo + "/comprovacao-mensal.xlsx"))) {
            assertThat(livro.getNumberOfSheets()).isEqualTo(2);
            Sheet aba = livro.getSheetAt(0);
            assertThat(aba.getSheetName()).isEqualTo("Comprovação mensal");
            Row cabecalho = aba.getRow(0);
            assertThat(List.of(texto(cabecalho.getCell(0)), texto(cabecalho.getCell(1)), texto(cabecalho.getCell(2)), texto(cabecalho.getCell(3)),
                    texto(cabecalho.getCell(4)), texto(cabecalho.getCell(5))))
                    .containsExactly("Marca", "Mês", "Tipo de mídia", "Unidade", "Exibido no mês", "Previsto no plano");

            Map<String, BigDecimal> esperado = esperado(amarelo);
            Map<String, BigDecimal> lido = lido(aba);
            assertThat(esperado).hasSizeGreaterThan(2);
            assertThat(lido.keySet()).containsExactlyElementsOf(esperado.keySet());
            esperado.forEach((chave, soma) -> assertThat(lido.get(chave)).as(chave).isEqualByComparingTo(soma));

            // A segunda aba traz o total do mês por unidade; a soma dela por unidade é a soma das linhas da primeira.
            Sheet totais = livro.getSheetAt(1);
            assertThat(totais.getSheetName()).isEqualTo("Totais por mês");
            BigDecimal dosTotais = BigDecimal.ZERO;
            for (int i = 1; i <= totais.getLastRowNum(); i++) {
                dosTotais = dosTotais.add(BigDecimal.valueOf(totais.getRow(i).getCell(3).getNumericCellValue()));
            }
            assertThat(dosTotais).isEqualByComparingTo(esperado.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        }
    }

    @Test
    void naoHaCelulaDeFormulaNemUnidadesSomadas() throws Exception {
        long amarelo = amarelo();
        long cpm = jdbc.sql("""
                INSERT INTO aproveitamento_previsto (parceria_id, categoria, veiculo, formato, espaco_programa, tipo_midia,
                                                     quantidade_prevista, unidade)
                VALUES (:p, 'MIDIA', 'Rede Atlântida RS', 'Formato de teste em CPM', 'Teste', 'ASSINADA', 500, 'CPM') RETURNING id
                """).param("p", amarelo).query(Long.class).single();
        jdbc.sql("INSERT INTO veiculacao (aproveitamento_id, exibido_em, quantidade) VALUES (:a, '2030-06-10T15:00:00Z', 30)")
                .param("a", cpm).update();

        try (Workbook livro = abrir(SessaoDeApi.entrar(mvc, RBS).get("/api/parcerias/" + amarelo + "/comprovacao-mensal.xlsx"))) {
            for (Sheet aba : livro) {
                for (Row linha : aba) {
                    for (Cell celula : linha) {
                        assertThat(celula.getCellType()).as(aba.getSheetName() + "!" + celula.getAddress()).isNotEqualTo(CellType.FORMULA);
                    }
                }
            }
            Map<String, BigDecimal> lido = lido(livro.getSheetAt(0));
            assertThat(lido.get("2030-06|Assinada|CPM")).isEqualByComparingTo("30");
            assertThat(lido).doesNotContainKey("2030-06|Assinada|Inserções");
        }
    }

    @Test
    void textoQueComecaComIgualVaiComApostrofoENuncaViraFormula() throws Exception {
        long amarelo = amarelo();
        jdbc.sql("UPDATE patrocinador SET nome_canonico = '=1+1' WHERE nome_canonico = 'Cliente Amarelo'").update();

        try (Workbook livro = abrir(SessaoDeApi.entrar(mvc, RBS).get("/api/parcerias/" + amarelo + "/comprovacao-mensal.xlsx"))) {
            Cell marca = livro.getSheetAt(0).getRow(1).getCell(0);
            assertThat(marca.getCellType()).isEqualTo(CellType.STRING);
            assertThat(marca.getStringCellValue()).isEqualTo("'=1+1");
            assertThat(livro.getSheetAt(1).getRow(1).getCell(0).getStringCellValue()).isEqualTo("'=1+1");
        }
    }

    @Test
    void oGravadorEscapaCadaGatilhoDeFormulaEDescartaControlesDoXml() throws Exception {
        byte[] bytes = PlanilhaXlsx.gerar(List.of(new PlanilhaXlsx.Aba("T", List.<List<Object>>of(
                List.<Object>of("Texto"),
                List.<Object>of("=A1"), List.<Object>of("+1"), List.<Object>of("-1"), List.<Object>of("@x"), List.<Object>of("\tx"),
                List.<Object>of("a\u0001b\u0000c & <d>")))));

        try (Workbook livro = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet aba = livro.getSheetAt(0);
            List<String> celulas = new ArrayList<>();
            for (int i = 1; i <= aba.getLastRowNum(); i++) {
                celulas.add(aba.getRow(i).getCell(0).getStringCellValue());
            }
            assertThat(celulas).containsExactly("'=A1", "'+1", "'-1", "'@x", "'\tx", "abc & <d>");
        }
    }

    @Test
    void nomesDeAbaSaoSaneadosECurtosEUnicos() throws Exception {
        String longo = "Marca [Muito]: Longa/Com*Nome?Que\\Passa de trinta e um caracteres";
        byte[] bytes = PlanilhaXlsx.gerar(List.of(
                new PlanilhaXlsx.Aba(longo, List.<List<Object>>of(List.<Object>of("A"))),
                new PlanilhaXlsx.Aba(longo, List.<List<Object>>of(List.<Object>of("A"))),
                new PlanilhaXlsx.Aba("''", List.<List<Object>>of(List.<Object>of("A")))));

        try (Workbook livro = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            List<String> nomes = new ArrayList<>();
            livro.forEach(aba -> nomes.add(aba.getSheetName()));
            assertThat(nomes).hasSize(3).doesNotHaveDuplicates();
            assertThat(nomes).allSatisfy(nome -> {
                assertThat(nome.length()).isBetween(1, 31);
                assertThat(nome).doesNotContainPattern("[\\[\\]:*?/\\\\]");
            });
        }
    }

    @Test
    void oPacoteTemAsPartesDoOoxmlESemFormula() throws Exception {
        byte[] bytes = PlanilhaXlsx.gerar(List.of(new PlanilhaXlsx.Aba("T", List.<List<Object>>of(List.<Object>of("A", 1)))));
        List<String> partes = new ArrayList<>();
        StringBuilder tudo = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                partes.add(e.getName());
                tudo.append(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        assertThat(partes).contains("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml",
                "xl/worksheets/sheet1.xml");
        assertThat(tudo.toString()).doesNotContain("<f>").doesNotContain("<f ");
    }

    // ---------------------------------------------------------------------
    // R3.3 · o lote da rota 91
    // ---------------------------------------------------------------------

    @Test
    void oLoteTemUmaAbaPorParceriaMasterDaEdicaoComOsNumerosDeCada() throws Exception {
        long amarelo = amarelo();
        long evento = evento();
        long patrocinador = jdbc.sql("INSERT INTO patrocinador (nome_canonico) VALUES ('Segunda Master: [Muito] Longa / Sem*Fim? Ltda') RETURNING id")
                .query(Long.class).single();
        long segunda = jdbc.sql("INSERT INTO parceria (evento_id, patrocinador_id, cota_tipo_id, situacao) VALUES (:e, :p, 1, 'ATIVA') RETURNING id")
                .param("e", evento).param("p", patrocinador).query(Long.class).single();
        long plano = jdbc.sql("""
                INSERT INTO aproveitamento_previsto (parceria_id, categoria, veiculo, formato, tipo_midia, quantidade_prevista, unidade)
                VALUES (:p, 'MIDIA', 'atl.la', 'Post', 'PROPRIA', 10, 'INSERCAO') RETURNING id
                """).param("p", segunda).query(Long.class).single();
        jdbc.sql("INSERT INTO veiculacao (aproveitamento_id, exibido_em, quantidade) VALUES (:a, '2030-02-10T15:00:00Z', 4)").param("a", plano).update();

        try (Workbook livro = abrir(SessaoDeApi.entrar(mvc, RBS).get("/api/eventos/" + evento + "/comprovacao-mensal.xlsx"))) {
            assertThat(livro.getNumberOfSheets()).as("Roxo e Verde (Subcotas) ficam de fora").isEqualTo(2);
            Sheet doAmarelo = livro.getSheet("Cliente Amarelo");
            assertThat(doAmarelo).isNotNull();
            Map<String, BigDecimal> esperado = esperado(amarelo);
            Map<String, BigDecimal> lido = lido(doAmarelo);
            assertThat(lido.keySet()).containsExactlyElementsOf(esperado.keySet());
            esperado.forEach((chave, soma) -> assertThat(lido.get(chave)).as(chave).isEqualByComparingTo(soma));

            Sheet outra = livro.getSheetAt(1);
            assertThat(outra.getSheetName().length()).isLessThanOrEqualTo(31);
            assertThat(outra.getSheetName()).doesNotContainPattern("[\\[\\]:*?/\\\\]");
            assertThat(lido(outra)).containsOnlyKeys("2030-02|Própria|Inserções");
            assertThat(lido(outra).get("2030-02|Própria|Inserções")).isEqualByComparingTo("4");
        }
    }

    @Test
    void oLoteIgnoraParceriaMasterEncerradaESoAAtivaEntra() throws Exception {
        long evento = evento();
        jdbc.sql("UPDATE parceria SET situacao = 'ENCERRADA' WHERE id = :p").param("p", amarelo()).update();

        try (Workbook livro = abrir(SessaoDeApi.entrar(mvc, RBS).get("/api/eventos/" + evento + "/comprovacao-mensal.xlsx"))) {
            assertThat(livro.getNumberOfSheets()).isEqualTo(1);
            assertThat(livro.getSheetAt(0).getLastRowNum()).isZero();
        }
    }

    @Test
    void oLoteEDaRbsEEventoInexistenteDa404() {
        long evento = evento();

        assertThat(SessaoDeApi.entrar(mvc, AMARELO).get("/api/eventos/" + evento + "/comprovacao-mensal.xlsx")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(SessaoDeApi.entrar(mvc, ROXO).get("/api/eventos/" + evento + "/comprovacao-mensal.xlsx")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(SessaoDeApi.entrar(mvc, RBS).get("/api/eventos/999999/comprovacao-mensal.xlsx")).hasStatus(HttpStatus.NOT_FOUND);
    }
}
