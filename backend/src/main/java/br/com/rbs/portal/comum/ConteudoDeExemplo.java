package br.com.rbs.portal.comum;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;

/**
 * O conteúdo de verdade dos arquivos da amostra de demonstração: pequeno, válido para a extensão e dizendo que é um arquivo
 * de exemplo. Atende o item 4 da Entrega 2. [UPLOAD] Cada conteúdo passa pela mesma conferência de assinatura que o envio
 * de arquivos usa (TipoDeArquivo#conteudoConfere). PDF e CSV são montados na hora, com o nome do arquivo dentro; os outros
 * formatos (imagens, vídeo, áudio, Office e zip) vêm prontos de {@code dev/exemplos/}, na pasta de recursos.
 * Só o perfil dev usa esta classe (ArquivosDaAmostra); em produção ela nunca é chamada.
 */
final class ConteudoDeExemplo {

    private static final String PASTA = "dev/exemplos/";

    private ConteudoDeExemplo() {
    }

    /** O conteúdo para o arquivo, pela extensão do nome original; vazio se a extensão não está na lista de tipos aceitos. */
    static Optional<byte[]> para(String nomeOriginal) {
        return TipoDeArquivo.daExtensao(nomeOriginal).map(tipo -> switch (tipo) {
            case PDF -> pdf(nomeOriginal);
            case CSV -> csv(nomeOriginal);
            case PNG -> pronto("exemplo.png");
            case JPG, JPEG -> pronto("exemplo.jpg");
            case MP4 -> pronto("exemplo.mp4");
            case MP3 -> pronto("exemplo.mp3");
            case DOCX -> pronto("exemplo.docx");
            case XLSX -> pronto("exemplo.xlsx");
            case PPTX -> pronto("exemplo.pptx");
            case ZIP -> pronto("exemplo.zip");
        });
    }

    private static byte[] pronto(String recurso) {
        try {
            return new ClassPathResource(PASTA + recurso).getContentAsByteArray();
        } catch (IOException erro) {
            throw new UncheckedIOException("Não consegui ler o arquivo de exemplo " + recurso, erro);
        }
    }

    static byte[] csv(String nomeOriginal) {
        String nome = nomeOriginal.replace("\"", "\"\"");
        return ("arquivo,observacao\n\"" + nome + "\",Arquivo de exemplo da demonstração com dados fictícios\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Um PDF de uma página, escrito à mão (sem biblioteca): título, o nome do arquivo e um aviso de que é um exemplo. A tabela
     * de referências cruzadas (xref) usa os deslocamentos reais dos objetos, então qualquer leitor abre sem reclamar.
     * O texto sai em ISO-8859-1 (a codificação WinAnsi do PDF); o que não cabe nela vira "?".
     */
    static byte[] pdf(String nomeOriginal) {
        String[] linhas = {"Arquivo de exemplo", nomeOriginal,
                "Documento fictício da demonstração do Portal do Patrocinador.", "Não tem valor comercial nem contratual."};
        int[] tamanhos = {24, 14, 12, 12};
        int[] alturas = {760, 725, 695, 678};

        StringBuilder conteudo = new StringBuilder();
        for (int i = 0; i < linhas.length; i++) {
            conteudo.append("BT /F1 ").append(tamanhos[i]).append(" Tf 72 ").append(alturas[i]).append(" Td (")
                    .append(escapar(linhas[i])).append(") Tj ET\n");
        }
        String[] objetos = {
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length " + conteudo.length() + " >>\nstream\n" + conteudo + "endstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"};

        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        int[] deslocamentos = new int[objetos.length];
        for (int i = 0; i < objetos.length; i++) {
            deslocamentos[i] = pdf.length();
            pdf.append(i + 1).append(" 0 obj\n").append(objetos[i]).append("\nendobj\n");
        }
        int inicioDaTabela = pdf.length();
        pdf.append("xref\n0 ").append(objetos.length + 1).append("\n0000000000 65535 f \n");
        for (int deslocamento : deslocamentos) {
            pdf.append(String.format("%010d 00000 n \n", deslocamento));
        }
        pdf.append("trailer\n<< /Size ").append(objetos.length + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(inicioDaTabela).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Texto seguro dentro de um "( )" do PDF: barra e parênteses ganham barra, e o que não é ISO-8859-1 vira "?". */
    private static String escapar(String texto) {
        StringBuilder saida = new StringBuilder();
        for (char c : Normalizer.normalize(texto, Normalizer.Form.NFC).toCharArray()) {
            if (c == '\\' || c == '(' || c == ')') {
                saida.append('\\').append(c);
            } else if (c < 32 || c > 255) {
                saida.append('?');
            } else {
                saida.append(c);
            }
        }
        return saida.toString();
    }
}
