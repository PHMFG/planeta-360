package br.com.rbs.portal.atlas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * [ATLAS] Linha 11 da seção 12.2 do plano (M8): um PDF com fonte que executa JavaScript (CVE-2024-4367, do pdf.js até a 4.2.66). O
 * visualizador do front usa o {@code pdfjs-dist}; esta conferência lê o {@code package.json} e o {@code package-lock.json} do front e
 * exige uma versão já corrigida (a 4.2.67 ou mais nova), a mesma nos dois arquivos. É o que {@code npm ls pdfjs-dist} diz, sem
 * precisar do npm na suíte do back. Sem a pasta do front (um build só do back), o teste é pulado.
 */
class PdfjsCorrigidoTest {

    /** A primeira versão do pdfjs-dist sem o CVE-2024-4367. */
    private static final int[] CORRIGIDA = {4, 2, 67};
    private static final Path FRONT = Path.of("..", "frontend");

    private static String ler(String arquivo) throws IOException {
        Path caminho = FRONT.resolve(arquivo);
        assumeTrue(Files.isRegularFile(caminho), "o front não está ao lado do back: " + caminho);
        return Files.readString(caminho, StandardCharsets.UTF_8);
    }

    private static int[] versao(String texto) {
        Matcher achado = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(texto);
        assertThat(achado.find()).as("uma versão x.y.z em [" + texto + "]").isTrue();
        return new int[] {Integer.parseInt(achado.group(1)), Integer.parseInt(achado.group(2)), Integer.parseInt(achado.group(3))};
    }

    private static boolean ehPeloMenos(int[] versao, int[] minima) {
        for (int i = 0; i < 3; i++) {
            if (versao[i] != minima[i]) {
                return versao[i] > minima[i];
            }
        }
        return true;
    }

    @Test
    void oPdfjsDistDoFrontEUmaVersaoSemOCve20244367EFixaIgualNoPackageELock() throws IOException {
        Matcher declarada = Pattern.compile("\"pdfjs-dist\"\\s*:\\s*\"([^\"]+)\"").matcher(ler("package.json"));
        assertThat(declarada.find()).as("o package.json declara o pdfjs-dist").isTrue();
        String pedida = declarada.group(1);
        Matcher travada = Pattern.compile("\"node_modules/pdfjs-dist\"\\s*:\\s*\\{\\s*\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(ler("package-lock.json"));
        assertThat(travada.find()).as("o package-lock.json trava o pdfjs-dist").isTrue();

        assertThat(pedida).as("versão fixa, sem ^ nem ~: o npm não sobe para uma versão que ninguém conferiu").matches("\\d+\\.\\d+\\.\\d+");
        assertThat(travada.group(1)).as("a do lock é a do package.json").isEqualTo(pedida);
        assertThat(ehPeloMenos(versao(pedida), CORRIGIDA)).as("pdfjs-dist " + pedida + " precisa ser a 4.2.67 ou mais nova (CVE-2024-4367)").isTrue();
    }
}
