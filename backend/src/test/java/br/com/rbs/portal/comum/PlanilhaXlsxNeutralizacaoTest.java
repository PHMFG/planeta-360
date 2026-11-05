package br.com.rbs.portal.comum;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

/**
 * [SEGURANCA] Texto que começa com {@code =}, {@code +}, {@code -} ou {@code @} é neutralizado com um apóstrofo (injeção de fórmula).
 * O caractere de controle sai ANTES disso: {@code "\u0001=1+1"} perde o controle e fica {@code "'=1+1"}, nunca uma fórmula.
 */
class PlanilhaXlsxNeutralizacaoTest {

    private static String primeiraPlanilha(byte[] xlsx) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry parte = zip.getNextEntry(); parte != null; parte = zip.getNextEntry()) {
                if (parte.getName().equals("xl/worksheets/sheet1.xml")) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("sem sheet1.xml");
    }

    @Test
    void controleNoComecoNaoEscondeAFormula() throws Exception {
        byte[] xlsx = PlanilhaXlsx.gerar(List.of(new PlanilhaXlsx.Aba("Teste",
                List.of(List.of("Marca"), List.of("\u0001=1+1", "\u0002@SOMA(A1)", "=2+2")))));

        String xml = primeiraPlanilha(xlsx).replace("&apos;", "'").replace("&#39;", "'");

        assertThat(xml).contains(">'=1+1<", ">'@SOMA(A1)<", ">'=2+2<");
        assertThat(xml).doesNotContain(">=1+1<", ">@SOMA(A1)<", ">=2+2<");
    }
}
