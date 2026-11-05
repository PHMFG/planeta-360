package br.com.rbs.portal.comum;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

/**
 * Teste unitário (sem Spring e sem banco) do conteúdo de exemplo que a amostra de demonstração usa. Atende o item 4 da
 * Entrega 2. [UPLOAD] Cada conteúdo passa na mesma conferência de assinatura que o envio de arquivos aplica.
 */
class ConteudoDeExemploTest {

    @Test
    void cadaTipoAceitoTemUmConteudoPequenoQuePassaNaConferenciaDoEnvio() {
        for (TipoDeArquivo tipo : TipoDeArquivo.values()) {
            if (tipo == TipoDeArquivo.DOC || tipo == TipoDeArquivo.XLS || tipo == TipoDeArquivo.PPT) {
                continue;   // a amostra não tem arquivo de Office antigo
            }
            String nome = "exemplo." + tipo.extensao();

            byte[] conteudo = ConteudoDeExemplo.para(nome).orElseThrow(() -> new AssertionError("Sem exemplo para " + nome));

            assertThat(tipo.conteudoConfere(conteudo)).as(nome + " passa na conferência de assinatura").isTrue();
            assertThat(conteudo.length).as(nome + " é pequeno").isBetween(1, 50_000);
        }
    }

    @Test
    void txtDeExemploDizQueEFicticioComONomeDoArquivoEEUtf8() {
        String texto = new String(ConteudoDeExemplo.para("faq_do_patrocinador.txt").orElseThrow(), StandardCharsets.UTF_8);

        assertThat(texto).startsWith("Arquivo de exemplo").contains("faq_do_patrocinador.txt").contains("fictício");
    }

    @Test
    void officeAntigoNaoTemExemplo() {
        assertThat(ConteudoDeExemplo.para("planilha.xls")).isEmpty();
        assertThat(ConteudoDeExemplo.para("texto.doc")).isEmpty();
        assertThat(ConteudoDeExemplo.para("slides.ppt")).isEmpty();
    }

    @Test
    void extensaoForaDaListaNaoGeraNada() {
        assertThat(ConteudoDeExemplo.para("programa.exe")).isEmpty();
        assertThat(ConteudoDeExemplo.para("sem-extensao")).isEmpty();
        assertThat(ConteudoDeExemplo.para("logo.AI")).isEmpty();
    }

    @Test
    void aExtensaoNaoDiferenciaMaiusculas() {
        assertThat(ConteudoDeExemplo.para("RELATORIO.PDF")).isPresent();
        assertThat(ConteudoDeExemplo.para("foto.JPEG")).isPresent();
    }

    @Test
    void pdfTemATabelaDeReferenciasCertaEDizQueEhExemploComONomeDoArquivo() {
        byte[] pdf = ConteudoDeExemplo.pdf("plano_comercial_cliente_roxo_PA27.pdf");
        String texto = new String(pdf, StandardCharsets.ISO_8859_1);

        assertThat(texto).startsWith("%PDF-1.4").endsWith("%%EOF\n");
        assertThat(texto).contains("Arquivo de exemplo").contains("plano_comercial_cliente_roxo_PA27.pdf").contains("fictício");
        // startxref aponta para a tabela, e cada entrada da tabela aponta para o "N 0 obj" certo.
        Matcher inicio = Pattern.compile("startxref\n(\\d+)\n").matcher(texto);
        assertThat(inicio.find()).isTrue();
        int tabela = Integer.parseInt(inicio.group(1));
        assertThat(texto.substring(tabela)).startsWith("xref\n0 6\n");
        Matcher entradas = Pattern.compile("(\\d{10}) 00000 n \n").matcher(texto.substring(tabela));
        int numero = 1;
        while (entradas.find()) {
            int deslocamento = Integer.parseInt(entradas.group(1));
            assertThat(texto.substring(deslocamento)).as("objeto " + numero).startsWith(numero + " 0 obj\n");
            numero++;
        }
        assertThat(numero).as("5 objetos").isEqualTo(6);
        // /Length do conteúdo bate com os bytes entre "stream" e "endstream".
        Matcher fluxo = Pattern.compile("/Length (\\d+) >>\nstream\n(.*?)endstream", Pattern.DOTALL).matcher(texto);
        assertThat(fluxo.find()).isTrue();
        assertThat(fluxo.group(2).length()).isEqualTo(Integer.parseInt(fluxo.group(1)));
    }

    @Test
    void pdfComNomeEstranhoNaoQuebraAEstruturaDoArquivo() {
        byte[] pdf = ConteudoDeExemplo.pdf("Relatório (final) \\ v2 — \"Planeta\".pdf");
        String texto = new String(pdf, StandardCharsets.ISO_8859_1);

        assertThat(texto).contains("Relatório \\(final\\) \\\\ v2 ? \"Planeta\".pdf");
        assertThat(texto).as("o travessão (fora do ISO-8859-1) vira ?").doesNotContain("—");
        Matcher inicio = Pattern.compile("startxref\n(\\d+)\n").matcher(texto);
        assertThat(inicio.find()).isTrue();
        assertThat(texto.substring(Integer.parseInt(inicio.group(1)))).startsWith("xref\n");
    }

    @Test
    void csvDeExemploEhUtf8ComONomeEntreAspas() {
        String csv = new String(ConteudoDeExemplo.csv("lista \"final\".csv"), StandardCharsets.UTF_8);

        assertThat(csv).startsWith("arquivo,observacao\n").contains("\"lista \"\"final\"\".csv\"").contains("dados fictícios");
    }

    @Test
    void docxXlsxPptxEZipSaoPacotesZipBemFormados() throws IOException {
        for (String extensao : List.of("docx", "xlsx", "pptx", "zip")) {
            byte[] conteudo = ConteudoDeExemplo.para("exemplo." + extensao).orElseThrow();
            List<String> partes = new ArrayList<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(conteudo))) {
                for (ZipEntry parte = zip.getNextEntry(); parte != null; parte = zip.getNextEntry()) {
                    partes.add(parte.getName());
                }
            }
            assertThat(partes).as(extensao).isNotEmpty();
            if (!extensao.equals("zip")) {
                assertThat(partes).as(extensao).contains("[Content_Types].xml", "_rels/.rels");
            }
        }
    }
}
