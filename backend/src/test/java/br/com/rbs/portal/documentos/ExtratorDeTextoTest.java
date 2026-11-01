package br.com.rbs.portal.documentos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.comum.TipoDeArquivo;
import br.com.rbs.portal.documentos.ExtratorDeTexto.ExtracaoFalhouException;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Extracao;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Linha;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;

/**
 * [ATLAS] Teste unitário (sem Spring e sem banco) da extração do texto dos documentos: PDF pelo PDFBox, uma página por vez; TXT
 * em UTF-8; e os motivos de um documento que não vira trecho (sem texto, com senha, corrompido, grande demais).
 */
class ExtratorDeTextoTest {

    private final ExtratorDeTexto extrator = new ExtratorDeTexto();

    /** Um PDF de verdade, montado na hora: uma página por texto dado (nenhum texto = página em branco). */
    static byte[] pdf(String... paginas) throws Exception {
        try (PDDocument documento = new PDDocument(); ByteArrayOutputStream saida = new ByteArrayOutputStream()) {
            PDType1Font fonte = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String texto : paginas) {
                PDPage pagina = new PDPage();
                documento.addPage(pagina);
                if (texto != null) {
                    try (PDPageContentStream conteudo = new PDPageContentStream(documento, pagina)) {
                        conteudo.beginText();
                        conteudo.setFont(fonte, 12);
                        conteudo.newLineAtOffset(72, 700);
                        conteudo.showText(texto);
                        conteudo.endText();
                    }
                }
            }
            documento.save(saida);
            return saida.toByteArray();
        }
    }

    /** Um PDF válido, mas sem nenhuma página. */
    static byte[] pdfSemPaginas() throws Exception {
        try (PDDocument documento = new PDDocument(); ByteArrayOutputStream saida = new ByteArrayOutputStream()) {
            documento.save(saida);
            return saida.toByteArray();
        }
    }

    @Test
    void pdfSemNenhumaPaginaEUmPdfCorrompidoEDizPaginasNulasEnaoZero() throws Exception {
        assertThatThrownBy(() -> extrator.extrairPdf(pdfSemPaginas())).isInstanceOfSatisfying(ExtracaoFalhouException.class, erro -> {
            assertThat(erro.getMessage()).isEqualTo("O PDF está corrompido ou não pôde ser lido.");
            assertThat(erro.paginas()).as("0 páginas violaria o CHECK paginas >= 1 do documento").isNull();
        });
    }

    @Test
    void ocaractereNuloSaiDoTextoPorqueOPostgresqlNaoOGuarda() {
        assertThat(ExtratorDeTexto.limpar("a\u0000b\u0000c")).isEqualTo("abc");
        assertThat(ExtratorDeTexto.limpar("\u0000")).isEmpty();

        Extracao extracao = extrator.extrairTxt("tex\u0000to com nulo\nsegunda\u0000 linha\n".getBytes(StandardCharsets.UTF_8));

        assertThat(extracao.paginas().get(0).linhas()).extracting(Linha::texto).containsExactly("texto com nulo", "segunda linha");
        assertThatThrownBy(() -> extrator.extrairTxt("\u0000\u0000\n  ".getBytes(StandardCharsets.UTF_8))).hasMessage("O arquivo de texto está vazio.");
    }

    @Test
    void extraiOTextoDeCadaPaginaNaOrdemEComONumeroDela() throws Exception {
        Extracao extracao = extrator.extrairPdf(pdf("Primeira pagina", "Segunda pagina", "Terceira pagina"));

        assertThat(extracao.totalDePaginas()).isEqualTo(3);
        assertThat(extracao.paginas()).extracting(ExtratorDeTexto.Pagina::numero).containsExactly(1, 2, 3);
        assertThat(extracao.paginas()).extracting(ExtratorDeTexto.Pagina::texto)
                .containsExactly("Primeira pagina", "Segunda pagina", "Terceira pagina");
        assertThat(extracao.milissegundos()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void paginaEmBrancoNoMeioFicaSemTextoMasContaNaNumeracao() throws Exception {
        Extracao extracao = extrator.extrairPdf(pdf("Com texto", null, "Outra com texto"));

        assertThat(extracao.totalDePaginas()).isEqualTo(3);
        assertThat(extracao.paginas().get(1).linhas()).isEmpty();
        assertThat(extracao.paginas().get(2).texto()).isEqualTo("Outra com texto");
    }

    @Test
    void pdfSoDeImagemNaoTemTextoExtraivelEOMotivoDizOcrForaDoEscopo() throws Exception {
        // O D12 do kit é uma página inteira desenhada como imagem; uma página em branco dá o mesmo resultado.
        assertThatThrownBy(() -> extrator.extrairPdf(KitDeDocumentos.por("D12").conteudo()))
                .isInstanceOfSatisfying(ExtracaoFalhouException.class, erro -> {
                    assertThat(erro.getMessage()).isEqualTo("Sem texto extraível; OCR fora do escopo.");
                    assertThat(erro.paginas()).isEqualTo(1);
                });
        assertThatThrownBy(() -> extrator.extrairPdf(pdf((String) null))).hasMessage(ExtratorDeTexto.SEM_TEXTO);
    }

    @Test
    void pdfComSenhaECorrompidoViramErroComOMotivo() throws Exception {
        byte[] comSenha;
        try (PDDocument documento = new PDDocument(); ByteArrayOutputStream saida = new ByteArrayOutputStream()) {
            documento.addPage(new PDPage());
            documento.protect(new StandardProtectionPolicy("dona", "usuaria", new AccessPermission()));
            documento.save(saida);
            comSenha = saida.toByteArray();
        }

        assertThatThrownBy(() -> extrator.extrairPdf(comSenha)).isInstanceOf(ExtracaoFalhouException.class)
                .hasMessage("O PDF está protegido por senha.");
        assertThatThrownBy(() -> extrator.extrairPdf("%PDF-1.4\nisto não é um pdf".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ExtracaoFalhouException.class).hasMessage("O PDF está corrompido ou não pôde ser lido.");
    }

    @Test
    void pdfComMaisDe300PaginasNaoEIndexado() throws Exception {
        String[] paginas = new String[ExtratorDeTexto.LIMITE_DE_PAGINAS + 1];
        java.util.Arrays.fill(paginas, "x");

        assertThatThrownBy(() -> extrator.extrairPdf(pdf(paginas))).isInstanceOfSatisfying(ExtracaoFalhouException.class, erro -> {
            assertThat(erro.getMessage()).isEqualTo("O PDF tem 301 páginas e o limite é 300.");
            assertThat(erro.paginas()).isEqualTo(301);
        });
        assertThat(extrator.extrairPdf(pdf(java.util.Arrays.copyOf(paginas, ExtratorDeTexto.LIMITE_DE_PAGINAS))).totalDePaginas())
                .as("300 páginas passam").isEqualTo(300);
    }

    @Test
    void txtEUmaPaginaSoEGuardaAsLinhasEmBrancoQueSeparamOsParagrafos() {
        String texto = "﻿Título\r\n\r\nPrimeiro parágrafo.\nSegundo parágrafo.\r\n";

        Extracao extracao = extrator.extrairTxt(texto.getBytes(StandardCharsets.UTF_8));

        assertThat(extracao.totalDePaginas()).isEqualTo(1);
        assertThat(extracao.paginas().get(0).linhas()).extracting(Linha::texto)
                .containsExactly("Título", "", "Primeiro parágrafo.", "Segundo parágrafo.");
        assertThatThrownBy(() -> extrator.extrairTxt("   \n \n".getBytes(StandardCharsets.UTF_8))).hasMessage("O arquivo de texto está vazio.");
    }

    @Test
    void oTextoSaiEmNfcMesmoQuandoOArquivoTrazAcentoSeparado() {
        String decomposto = Normalizer.normalize("informação", Normalizer.Form.NFD);
        assertThat(decomposto).isNotEqualTo("informação");

        Extracao extracao = extrator.extrair(TipoDeArquivo.TXT, decomposto.getBytes(StandardCharsets.UTF_8));

        assertThat(extracao.paginas().get(0).texto()).isEqualTo("informação");
    }

    @Test
    void soPdfETxtSaoIndexados() {
        assertThatThrownBy(() -> extrator.extrair(TipoDeArquivo.ZIP, new byte[] {'P', 'K', 3, 4})).isInstanceOf(ExtracaoFalhouException.class);
    }

    @Test
    void asLinhasDeUmaTabelaSaemMarcadasEOTextoVizinhoNao() throws Exception {
        Extracao d01 = extrator.extrairPdf(KitDeDocumentos.por("D01").conteudo());

        var pagina3 = d01.paginas().get(2).linhas();
        var legenda = pagina3.stream().filter(l -> l.texto().startsWith("Tabela 1")).findFirst().orElseThrow();
        var ultimaLinhaDaTabela = pagina3.stream().filter(l -> l.texto().startsWith("Desmontar as ativações")).findFirst().orElseThrow();
        var textoDepois = pagina3.stream().filter(l -> l.texto().startsWith("O cronograma não inclui")).findFirst().orElseThrow();
        var textoAntes = pagina3.stream().filter(l -> l.texto().startsWith("A tabela 1 lista")).findFirst().orElseThrow();

        assertThat(legenda.tabela()).as("a legenda faz parte da tabela").isTrue();
        assertThat(ultimaLinhaDaTabela.tabela()).isTrue();
        assertThat(textoDepois.tabela()).as("o parágrafo depois da tabela não é tabela").isFalse();
        assertThat(textoAntes.tabela()).isFalse();
        assertThat(pagina3.stream().filter(Linha::tabela).count()).as("legenda, cabeçalho e as linhas de 10 marcos").isGreaterThan(15);
        // Uma página sem tabela não marca nada.
        assertThat(d01.paginas().get(3).linhas()).noneMatch(Linha::tabela);
    }
}
