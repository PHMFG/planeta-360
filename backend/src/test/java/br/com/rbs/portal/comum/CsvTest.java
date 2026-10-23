package br.com.rbs.portal.comum;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/**
 * Teste unitário do utilitário de CSV (sem Spring e sem banco). Atende os itens 5 e 6 da Entrega 2.
 * [CARGA] Confere o BOM, o ponto e vírgula, as aspas da RFC 4180 e a neutralização de fórmulas.
 */
class CsvTest {

    /** O caractere U+FEFF que abre o arquivo; os bytes EF BB BF em UTF-8. */
    private static final String BOM = "\uFEFF";

    private static String texto(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void comecaComBomUtf8ESeparaAsColunasComPontoEVirgula() {
        byte[] bytes = Csv.gerar(List.of("id", "nome"), List.of(Csv.linha(1L, "Amarelo")));

        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        // O BOM é o caractere U+FEFF; depois dele vem o cabeçalho e uma linha por item, terminadas em CRLF.
        assertThat(texto(bytes)).isEqualTo(BOM + "id;nome\r\n1;Amarelo\r\n");
    }

    @Test
    void acentosSaemEmUtf8() {
        byte[] bytes = Csv.gerar(List.of("indicador"), List.of(Csv.linha("Mídia: previsto x exibido · ação")));

        assertThat(texto(bytes)).contains("Mídia: previsto x exibido · ação");
        assertThat(bytes).as("í em UTF-8 ocupa dois bytes (C3 AD)").contains((byte) 0xC3, (byte) 0xAD);
    }

    @Test
    void aspasSegueARfc4180() {
        byte[] bytes = Csv.gerar(List.of("a", "b", "c", "d"), List.of(
                Csv.linha("tem;ponto e vírgula", "diz \"oi\"", "duas\nlinhas", "simples")));

        assertThat(texto(bytes)).isEqualTo(
                BOM + "a;b;c;d\r\n\"tem;ponto e vírgula\";\"diz \"\"oi\"\"\";\"duas\nlinhas\";simples\r\n");
    }

    @Test
    void celulaQueComecaComSinalDeFormulaGanhaApostrofo() {
        byte[] bytes = Csv.gerar(List.of("c"), List.of(
                Csv.linha("=1+1"), Csv.linha("+55 51 99999-0000"), Csv.linha("-2+3"), Csv.linha("@SOMA(A1)"),
                Csv.linha("\t=1+1"), Csv.linha("\r=1+1")));

        String csv = texto(bytes);
        assertThat(csv).contains("'=1+1\r\n").contains("'+55 51 99999-0000\r\n").contains("'-2+3\r\n")
                .contains("'@SOMA(A1)\r\n");
        assertThat(csv).as("tabulação e retorno de carro no começo também").contains("'\t=1+1").contains("\"'\r=1+1\"");
        assertThat(csv).as("nenhuma célula começa com o sinal puro").doesNotContain("\r\n=1+1").doesNotContain(";=1+1");
    }

    @Test
    void soNeutralizaNoComecoDaCelula() {
        assertThat(Csv.neutralizar("1+1=2")).isEqualTo("1+1=2");
        assertThat(Csv.neutralizar("Rede - Atlântida")).isEqualTo("Rede - Atlântida");
        assertThat(Csv.neutralizar("e-mail@exemplo.com")).isEqualTo("e-mail@exemplo.com");
        assertThat(Csv.neutralizar("")).isEmpty();
        assertThat(Csv.neutralizar(null)).isEmpty();
        assertThat(Csv.neutralizar("'=1+1")).as("já começa com apóstrofo").isEqualTo("'=1+1");
    }

    @Test
    void numeroNegativoContinuaNumeroENaoGanhaApostrofo() {
        byte[] bytes = Csv.gerar(List.of("n"), List.of(
                Csv.linha(-5), Csv.linha(new BigDecimal("-12.50")), Csv.linha("-5")));

        assertThat(texto(bytes)).isEqualTo(BOM + "n\r\n-5\r\n-12.5\r\n'-5\r\n");
    }

    @Test
    void nuloViraCelulaVaziaEDecimalSaiSemZerosSobrando() {
        byte[] bytes = Csv.gerar(List.of("a", "b", "c", "d"), List.of(
                Csv.linha("x", null, new BigDecimal("90.90"), new BigDecimal("100.00"))));

        assertThat(texto(bytes)).isEqualTo(BOM + "a;b;c;d\r\nx;;90.9;100\r\n");
        assertThat(texto(Csv.gerar(List.of("a"), List.of(Csv.linha(new BigDecimal("0.00")))))).endsWith("\r\n0\r\n");
    }

    @Test
    void dataSaiEmIsoComOFusoDeBrasilia() {
        OffsetDateTime emUtc = OffsetDateTime.parse("2026-11-22T13:00:00Z");

        byte[] bytes = Csv.gerar(List.of("quando"), List.of(Csv.linha(emUtc)));

        assertThat(texto(bytes)).isEqualTo(BOM + "quando\r\n2026-11-22T10:00:00-03:00\r\n");
    }

    @Test
    void respostaTemTipoTextCsvEContentDispositionAttachment() {
        ResponseEntity<byte[]> resposta = Csv.resposta("historico-2026-10-23.csv", List.of("id"), List.of(Csv.linha(7L)));

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        assertThat(resposta.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("text/csv; charset=UTF-8");
        assertThat(resposta.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"historico-2026-10-23.csv\"");
        assertThat(texto(resposta.getBody())).isEqualTo(BOM + "id\r\n7\r\n");
    }
}
