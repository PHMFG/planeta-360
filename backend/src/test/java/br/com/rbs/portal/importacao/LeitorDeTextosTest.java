package br.com.rbs.portal.importacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.documentos.ExtratorDeTexto;
import br.com.rbs.portal.importacao.leitores.LeitorDePdf;
import br.com.rbs.portal.importacao.leitores.LeitorDePowerPoint;
import br.com.rbs.portal.importacao.leitores.LeitorDeTexto;
import br.com.rbs.portal.importacao.leitores.LeitorDeWord;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Os leitores de texto do Atlas de entrada (E8), com o corpus fictício: o localizador de cada pedaço é o que a prévia abre. */
class LeitorDeTextosTest {

    private static Path corpus(String nome) throws Exception {
        try (InputStream entrada = new ClassPathResource("importacao/corpus/" + nome).getInputStream()) {
            Path arquivo = Files.createTempFile("corpus-", "-" + nome);
            Files.write(arquivo, entrada.readAllBytes());
            return arquivo;
        }
    }

    @Test
    void oWordLeOsParagrafosEAsLinhasDaTabela() throws Exception {
        ConteudoLido word = new LeitorDeWord().ler(corpus("radio92-outubro.docx"));
        assertThat(word.tipoDeArquivo()).isEqualTo("DOCX");
        assertThat(word.pedacos()).hasSize(13);
        assertThat(word.pedacos().get(0).localizador()).isEqualTo("parágrafo 1");
        assertThat(word.pedacos().get(0).texto()).contains("Relatório de veiculação");
        assertThat(word.pedacos().get(2).localizador()).isEqualTo("parágrafo 3");
        assertThat(word.pedacos().get(2).texto()).contains("3 chamadas assinadas", "Cliente Amarelo");

        Path comTabela = Files.createTempFile("word-", ".docx");
        try (XWPFDocument documento = new XWPFDocument()) {
            documento.createParagraph().createRun().setText("Plano do mês");
            XWPFTable tabela = documento.createTable(2, 2);
            tabela.getRow(0).getCell(0).setText("Veículo");
            tabela.getRow(0).getCell(1).setText("Quantidade");
            tabela.getRow(1).getCell(0).setText("Rádio 92");
            tabela.getRow(1).getCell(1).setText("2");
            try (var saida = Files.newOutputStream(comTabela)) {
                documento.write(saida);
            }
        }
        ConteudoLido lido = new LeitorDeWord().ler(comTabela);
        assertThat(lido.pedacos()).anySatisfy(p -> {
            assertThat(p.localizador()).isEqualTo("tabela 1, linha 2");
            assertThat(p.texto()).isEqualTo("Rádio 92 | 2");
        });
    }

    @Test
    void oPowerPointLeAsFormasEOsSlides() throws Exception {
        Path apresentacao = Files.createTempFile("slides-", ".pptx");
        try (XMLSlideShow show = new XMLSlideShow()) {
            XSLFSlide slide = show.createSlide();
            XSLFTextBox forma = slide.createTextBox();
            forma.setText("Entrega de outubro");
            XSLFTable tabela = slide.createTable(2, 2);
            tabela.getCell(0, 0).setText("Veículo");
            tabela.getCell(0, 1).setText("Quantidade");
            tabela.getCell(1, 0).setText("Rádio 92");
            tabela.getCell(1, 1).setText("2");
            try (var saida = Files.newOutputStream(apresentacao)) {
                show.write(saida);
            }
        }
        ConteudoLido lido = new LeitorDePowerPoint().ler(apresentacao);
        assertThat(lido.tipoDeArquivo()).isEqualTo("PPTX");
        assertThat(lido.pedacos()).anySatisfy(p -> {
            assertThat(p.localizador()).isEqualTo("slide 1");
            assertThat(p.texto()).isEqualTo("Entrega de outubro");
        });
        assertThat(lido.pedacos()).anySatisfy(p -> {
            assertThat(p.localizador()).isEqualTo("slide 1, tabela 1, linha 2");
            assertThat(p.texto()).isEqualTo("Rádio 92 | 2");
        });
    }

    @Test
    void oPdfComTextoLeCadaLinhaComAPagina() throws Exception {
        ConteudoLido pdf = new LeitorDePdf(new ExtratorDeTexto()).ler(corpus("numeros-da-edicao.pdf"));
        assertThat(pdf.tipoDeArquivo()).isEqualTo("PDF");
        assertThat(pdf.pedacos()).isNotEmpty();
        assertThat(pdf.pedacos().get(0).localizador()).isEqualTo("página 1, linha 1");
        assertThat(pdf.pedacos()).anySatisfy(p -> assertThat(p.texto()).contains("39"));
    }

    @Test
    void oPdfSemTextoFalhaParaAImportacaoTentarAImagem() throws Exception {
        Path semTexto = Files.createTempFile("pdf-", ".pdf");
        try (PDDocument documento = new PDDocument()) {
            documento.addPage(new PDPage());
            documento.save(semTexto.toFile());
        }
        assertThatThrownBy(() -> new LeitorDePdf(new ExtratorDeTexto()).ler(semTexto))
                .isInstanceOf(LeituraFalhouException.class)
                .hasMessageContaining(ExtratorDeTexto.SEM_TEXTO);
    }

    @Test
    void aConversaDoWhatsAppViraMensagensComAsLinhasDeContinuacao() {
        ConteudoLido conversa = new LeitorDeTexto().ler(corpusSemCheckedException());
        assertThat(conversa.pedacos()).hasSize(12);
        assertThat(conversa.pedacos().get(0).localizador()).isEqualTo("mensagem 1");
        assertThat(conversa.pedacos().get(2).localizador()).isEqualTo("mensagem 3");
        assertThat(conversa.pedacos().get(2).texto()).contains("4 comerciais", "Rádio 102.3");
        assertThat(conversa.pedacos()).allSatisfy(p -> assertThat(p.localizador()).startsWith("mensagem "));
    }

    @Test
    void oTextoComumContinuaComALinha() throws Exception {
        Path arquivo = Files.createTempFile("texto-", ".txt");
        Files.writeString(arquivo, "Primeira linha\n\nSegunda linha\n", StandardCharsets.UTF_8);
        ConteudoLido lido = new LeitorDeTexto().ler(arquivo);
        assertThat(lido.pedacos()).hasSize(2);
        assertThat(lido.pedacos().get(0).localizador()).isEqualTo("linha 1");
        assertThat(lido.pedacos().get(1).localizador()).isEqualTo("linha 3");
    }

    private static Path corpusSemCheckedException() {
        try {
            return corpus("conversa-whatsapp.txt");
        } catch (Exception erro) {
            throw new IllegalStateException(erro);
        }
    }
}
