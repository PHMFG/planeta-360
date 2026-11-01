package br.com.rbs.portal.comum;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Teste unitário (sem Spring e sem banco) da lista de tipos de arquivo aceitos e da conferência pela assinatura.
 * Atende o item 4 da Entrega 2. [UPLOAD] Cada formato reprova com os bytes de outro e o CSV só vale como UTF-8 sem nulos.
 */
class TipoDeArquivoTest {

    private static byte[] bytes(int... valores) {
        byte[] resultado = new byte[valores.length];
        for (int i = 0; i < valores.length; i++) {
            resultado[i] = (byte) valores[i];
        }
        return resultado;
    }

    @Test
    void listaAceitaExatamenteAsExtensoesCombinadasComARbs() {
        assertThat(TipoDeArquivo.listaDeExtensoes()).isEqualTo("pdf, png, jpg, jpeg, docx, xlsx, pptx, csv, mp4, mp3, zip");
        for (String nome : new String[] {"a.exe", "a.svg", "a.html", "a.js", "a.sh", "a.bat", "a.docm", "a.xlsm", "a.ai", "a", "a."}) {
            assertThat(TipoDeArquivo.daExtensao(nome)).as(nome).isEmpty();
        }
    }

    @Test
    void extensaoNaoDiferenciaMaiusculasEUsaSoOUltimoPonto() {
        assertThat(TipoDeArquivo.daExtensao("Logo.PDF")).contains(TipoDeArquivo.PDF);
        assertThat(TipoDeArquivo.daExtensao("foto.final.JpEg")).contains(TipoDeArquivo.JPEG);
        assertThat(TipoDeArquivo.daExtensao("arquivo.pdf.exe")).isEqualTo(Optional.empty());
    }

    @Test
    void mimeVemDaListaENuncaDoQueOCelularDisse() {
        assertThat(TipoDeArquivo.PDF.mime()).isEqualTo("application/pdf");
        assertThat(TipoDeArquivo.JPG.mime()).isEqualTo(TipoDeArquivo.JPEG.mime()).isEqualTo("image/jpeg");
        assertThat(TipoDeArquivo.CSV.mime()).isEqualTo("text/csv");
        assertThat(TipoDeArquivo.MP3.mime()).isEqualTo("audio/mpeg");
        assertThat(TipoDeArquivo.XLSX.mime()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @Test
    void assinaturasCertasPassam() {
        assertThat(TipoDeArquivo.PDF.conteudoConfere("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII))).isTrue();
        assertThat(TipoDeArquivo.PNG.conteudoConfere(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0))).isTrue();
        assertThat(TipoDeArquivo.JPG.conteudoConfere(bytes(0xFF, 0xD8, 0xFF, 0xE1))).isTrue();
        assertThat(TipoDeArquivo.JPEG.conteudoConfere(bytes(0xFF, 0xD8, 0xFF, 0xDB))).isTrue();
        for (TipoDeArquivo zip : new TipoDeArquivo[] {TipoDeArquivo.ZIP, TipoDeArquivo.DOCX, TipoDeArquivo.XLSX, TipoDeArquivo.PPTX}) {
            assertThat(zip.conteudoConfere(bytes('P', 'K', 3, 4, 0))).as(zip.name()).isTrue();
            assertThat(zip.conteudoConfere(bytes('P', 'K', 5, 6, 0))).as(zip.name() + " vazio").isTrue();
        }
        assertThat(TipoDeArquivo.MP4.conteudoConfere(bytes(0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'))).isTrue();
        assertThat(TipoDeArquivo.MP3.conteudoConfere(bytes('I', 'D', '3', 4, 0))).isTrue();
        assertThat(TipoDeArquivo.MP3.conteudoConfere(bytes(0xFF, 0xFB, 0x90))).isTrue();
        assertThat(TipoDeArquivo.MP3.conteudoConfere(bytes(0xFF, 0xF3, 0x40))).isTrue();
    }

    @Test
    void assinaturasErradasOuCurtasDemaisReprovam() {
        byte[] executavel = bytes('M', 'Z', 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0);
        for (TipoDeArquivo tipo : TipoDeArquivo.values()) {
            if (tipo != TipoDeArquivo.CSV && tipo != TipoDeArquivo.TXT) {
                assertThat(tipo.conteudoConfere(executavel)).as(tipo.name() + " com executável").isFalse();
                assertThat(tipo.conteudoConfere(new byte[0])).as(tipo.name() + " vazio").isFalse();
                assertThat(tipo.conteudoConfere(bytes(0x00))).as(tipo.name() + " curto").isFalse();
            }
        }
        assertThat(TipoDeArquivo.PDF.conteudoConfere("PDF-1.7".getBytes(StandardCharsets.US_ASCII))).as("sem o %").isFalse();
        assertThat(TipoDeArquivo.PNG.conteudoConfere(bytes(0x89, 'P', 'N', 'G'))).as("assinatura PNG incompleta").isFalse();
        assertThat(TipoDeArquivo.MP4.conteudoConfere(bytes(0, 0, 0, 0x20, 'f', 't', 'y'))).as("curto demais").isFalse();
        assertThat(TipoDeArquivo.MP3.conteudoConfere(bytes(0xFF, 0x1B))).as("FF sem os 3 bits de sincronia").isFalse();
        assertThat(TipoDeArquivo.ZIP.conteudoConfere(bytes('P', 'K', 9, 9))).isFalse();
    }

    @Test
    void csvEhTextoUtf8SemBytesNulos() {
        assertThat(TipoDeArquivo.CSV.conteudoConfere("a;b\n1;2\n".getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(TipoDeArquivo.CSV.conteudoConfere("ação;coração;日本\n".getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(TipoDeArquivo.CSV.conteudoConfere("\uFEFFid;nome\n1;Ana\n".getBytes(StandardCharsets.UTF_8))).as("com BOM").isTrue();
        assertThat(TipoDeArquivo.CSV.conteudoConfere("sem quebra de linha no fim".getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(TipoDeArquivo.CSV.conteudoConfere(bytes('a', 0, 'b'))).as("byte nulo").isFalse();
        assertThat(TipoDeArquivo.CSV.conteudoConfere(bytes('a', ';', 0xE7, 0xE3, 'o'))).as("Latin-1 não é UTF-8").isFalse();
        assertThat(TipoDeArquivo.CSV.conteudoConfere(bytes('a', 0xC3))).as("UTF-8 cortado no meio de uma letra").isFalse();
        assertThat(TipoDeArquivo.CSV.conteudoConfere(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A))).isFalse();
    }

    @Test
    void xlsEOTipoDaPlanilhaDaAgenciaEFicaForaDaListaDosEnviosComuns() {
        assertThat(TipoDeArquivo.XLS.mime()).isEqualTo("application/vnd.ms-excel");
        assertThat(TipoDeArquivo.daExtensao("contagem.XLS")).contains(TipoDeArquivo.XLS);
        assertThat(TipoDeArquivo.listaDeExtensoes()).as("a frase do 415 dos envios comuns não cita o xls").doesNotContain("xls,")
                .endsWith("zip");
        byte[] ole2 = bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1, 0, 0);
        assertThat(TipoDeArquivo.XLS.conteudoConfere(ole2)).isTrue();
        assertThat(TipoDeArquivo.XLS.conteudoConfere(bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A))).as("assinatura incompleta").isFalse();
        assertThat(TipoDeArquivo.XLS.conteudoConfere(bytes('P', 'K', 3, 4, 0))).as("um zip não é xls").isFalse();
        assertThat(TipoDeArquivo.XLSX.conteudoConfere(ole2)).as("um xls não é xlsx").isFalse();
        assertThat(TipoDeArquivo.CSV.conteudoConfere(ole2)).as("nem csv").isFalse();
    }

    @Test
    void txtEOTipoDosDocumentosDoAtlasEFicaForaDaListaDosEnviosComuns() {
        assertThat(TipoDeArquivo.TXT.mime()).isEqualTo("text/plain");
        assertThat(TipoDeArquivo.daExtensao("faq.TXT")).contains(TipoDeArquivo.TXT);
        assertThat(TipoDeArquivo.listaDeExtensoes()).as("a frase do 415 dos envios comuns não cita o txt").doesNotContain("txt");
        assertThat(TipoDeArquivo.TXT.conteudoConfere("Pergunta frequente: horário dos portões.\n".getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(TipoDeArquivo.TXT.conteudoConfere("ação".getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(TipoDeArquivo.TXT.conteudoConfere(bytes('a', 0, 'b'))).as("byte nulo").isFalse();
        assertThat(TipoDeArquivo.TXT.conteudoConfere(bytes('a', ';', 0xE7, 0xE3, 'o'))).as("Latin-1 não é UTF-8").isFalse();
        assertThat(TipoDeArquivo.TXT.conteudoConfere(bytes('M', 'Z', 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0))).as("executável").isFalse();
    }

    @Test
    void textoQueNaoEUtf8SeDistingueDeArquivoQueNaoEhTexto() {
        // Latin-1 e ANSI do Excel: texto, só que não UTF-8. UTF-16 do "Texto Unicode" também (marca FF FE ou FE FF).
        assertThat(TipoDeArquivo.ehTextoQueNaoEUtf8("patrocinador;veículo\nJoão;Rádio\r\n".getBytes(StandardCharsets.ISO_8859_1))).isTrue();
        assertThat(TipoDeArquivo.ehTextoQueNaoEUtf8(bytes('a', ';', 0xE7, 0xE3, 'o', '\t', 'b'))).isTrue();
        assertThat(TipoDeArquivo.ehTextoQueNaoEUtf8(bytes(0xFF, 0xFE, 'a', 0, ';', 0))).as("UTF-16 com BOM").isTrue();
        assertThat(TipoDeArquivo.ehTextoQueNaoEUtf8(bytes('a', 0xC3))).as("UTF-8 cortado: ainda é texto").isTrue();
        // UTF-8 válido não tem o que corrigir.
        assertThat(TipoDeArquivo.ehTextoQueNaoEUtf8("ação;coração\n".getBytes(StandardCharsets.UTF_8))).isFalse();
        // Binário: byte nulo ou caractere de controle (PNG, PDF compactado, executável).
        assertThat(TipoDeArquivo.ehTextoQueNaoEUtf8(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A))).as("PNG").isFalse();
        assertThat(TipoDeArquivo.ehTextoQueNaoEUtf8(bytes('M', 'Z', 0x90, 0, 3, 0, 0, 0))).as("executável").isFalse();
        assertThat(TipoDeArquivo.ehTextoQueNaoEUtf8(bytes('a', 0xE7, 0x01, 'b'))).as("Latin-1 com caractere de controle").isFalse();
    }

    @Test
    void csvGrandeComTextoValidoAte20MbNaoEstouraAMemoria() {
        byte[] grande = new byte[20 * 1024 * 1024];
        java.util.Arrays.fill(grande, (byte) 'a');
        grande[grande.length - 1] = '\n';

        assertThat(TipoDeArquivo.CSV.conteudoConfere(grande)).isTrue();
        grande[grande.length / 2] = (byte) 0xFF;
        assertThat(TipoDeArquivo.CSV.conteudoConfere(grande)).isFalse();
    }
}
