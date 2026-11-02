package br.com.rbs.portal.comum;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * A lista de tipos de arquivo que o portal aceita (extensão, tipo MIME e como conferir o conteúdo). Atende o item 4 da
 * Entrega 2. [UPLOAD] A extensão sozinha não vale: o servidor confere os primeiros bytes (a "assinatura" do formato), então
 * um executável renomeado para .pdf é recusado. SVG fica de fora de propósito (pode carregar script) e não há executáveis.
 * O tipo MIME gravado e devolvido no download vem DESTA lista, nunca do que o navegador disse no envio.
 *
 * <p>[ATLAS] {@link #TXT} existe só para os documentos do Atlas (rota 54, que aceita PDF e TXT): as outras rotas de envio (a 17 das
 * entregas, a 26 das cargas, a 38 das fotos) não o aceitam, e o Armazenamento o recusa quando o envio não é de documento.
 * [DADOS] {@link #XLS} (a planilha do Excel antigo) existe só para a planilha da agência (rota 66, que aceita XLSX, XLS e CSV): as
 * outras rotas não o aceitam. O CSV dessa rota é conferido só por {@link #semBytesNulos} (qualquer codificação); nas outras, é UTF-8.
 */
public enum TipoDeArquivo {
    PDF("application/pdf"),
    PNG("image/png"),
    JPG("image/jpeg"),
    JPEG("image/jpeg"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    CSV("text/csv"),
    MP4("video/mp4"),
    MP3("audio/mpeg"),
    ZIP("application/zip"),
    TXT("text/plain"),
    XLS("application/vnd.ms-excel");

    private final String mime;

    TipoDeArquivo(String mime) {
        this.mime = mime;
    }

    public String extensao() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String mime() {
        return mime;
    }

    /** O tipo pela extensão do nome (sem diferenciar maiúsculas); vazio se não tiver extensão ou se não estiver na lista. */
    public static Optional<TipoDeArquivo> daExtensao(String nomeDoArquivo) {
        String extensao = extensaoDe(nomeDoArquivo);
        for (TipoDeArquivo tipo : values()) {
            if (tipo.extensao().equals(extensao)) {
                return Optional.of(tipo);
            }
        }
        return Optional.empty();
    }

    /** O que vem depois do último ponto, em minúsculas; vazio se não há ponto. */
    public static String extensaoDe(String nomeDoArquivo) {
        int ponto = nomeDoArquivo == null ? -1 : nomeDoArquivo.lastIndexOf('.');
        return ponto < 0 ? "" : nomeDoArquivo.substring(ponto + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * As extensões aceitas nos envios comuns, para a frase de erro: "pdf, png, jpg...". O {@link #TXT} é só dos documentos, e o
     * {@link #XLS} só da planilha da agência.
     */
    public static String listaDeExtensoes() {
        StringBuilder lista = new StringBuilder();
        for (TipoDeArquivo tipo : values()) {
            if (tipo != TXT && tipo != XLS) {
                lista.append(lista.isEmpty() ? "" : ", ").append(tipo.extensao());
            }
        }
        return lista.toString();
    }

    /**
     * [UPLOAD] O conteúdo é mesmo deste tipo? pdf começa com %PDF; png com a assinatura de 8 bytes; jpeg com FF D8 FF;
     * zip, docx, xlsx e pptx (que são zip por dentro) com PK; mp4 tem "ftyp" a partir do 5º byte; mp3 começa com ID3 ou com
     * um quadro de áudio (FF e os 3 bits mais altos do byte seguinte ligados); csv e txt são texto UTF-8 válido, sem bytes nulos; xls
     * começa com a assinatura de 8 bytes do contêiner OLE2 (D0 CF 11 E0 A1 B1 1A E1).
     */
    public boolean conteudoConfere(byte[] b) {
        return switch (this) {
            case PDF -> comecaCom(b, '%', 'P', 'D', 'F');
            case PNG -> comecaCom(b, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
            case JPG, JPEG -> comecaCom(b, 0xFF, 0xD8, 0xFF);
            case DOCX, XLSX, PPTX, ZIP -> comecaCom(b, 'P', 'K', 3, 4) || comecaCom(b, 'P', 'K', 5, 6);
            case MP4 -> b.length >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p';
            case MP3 -> comecaCom(b, 'I', 'D', '3') || (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0);
            case CSV, TXT -> ehTextoUtf8SemNulos(b);
            case XLS -> comecaCom(b, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1);
        };
    }

    /**
     * [DADOS] A conferência do CSV da planilha da agência (rota 66): nenhum byte nulo, em qualquer codificação. Ninguém lê essa
     * planilha no servidor (ela só é guardada e baixada pela rota 18), então o Latin-1 e o windows-1252 que o Excel em português grava
     * por padrão entram; o UTF-16, que tem byte nulo em todo caractere ASCII, e o binário renomeado continuam recusados.
     */
    public static boolean semBytesNulos(byte[] b) {
        for (byte valor : b) {
            if (valor == 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * O conteúdo é texto, mas não UTF-8? Serve para dizer ao usuário como salvar a planilha de novo. É texto quando não há
     * byte nulo nem caractere de controle além de tabulação e quebra de linha (um texto em Latin-1 passa; um PNG ou um
     * executável não), ou quando o arquivo começa com a marca de UTF-16 (FF FE ou FE FF) que o Excel grava em "Texto Unicode".
     * Texto que já é UTF-8 válido devolve falso: para ele não há o que corrigir.
     */
    public static boolean ehTextoQueNaoEUtf8(byte[] b) {
        if (ehTextoUtf8SemNulos(b)) {
            return false;
        }
        if (comecaCom(b, 0xFF, 0xFE) || comecaCom(b, 0xFE, 0xFF)) {
            return true;
        }
        for (byte valor : b) {
            int caractere = valor & 0xFF;
            if (caractere == 0 || (caractere < 0x20 && caractere != '\t' && caractere != '\n' && caractere != '\r') || caractere == 0x7F) {
                return false;
            }
        }
        return true;
    }

    private static boolean comecaCom(byte[] b, int... assinatura) {
        if (b.length < assinatura.length) {
            return false;
        }
        for (int i = 0; i < assinatura.length; i++) {
            if ((b[i] & 0xFF) != assinatura[i]) {
                return false;
            }
        }
        return true;
    }

    /** Decodifica aos poucos (sem montar o texto todo na memória): qualquer byte que não seja UTF-8 válido, ou nulo, reprova. */
    private static boolean ehTextoUtf8SemNulos(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return false;
            }
        }
        CharsetDecoder decodificador = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer entrada = ByteBuffer.wrap(bytes);
        CharBuffer saida = CharBuffer.allocate(8192);
        while (true) {
            var resultado = decodificador.decode(entrada, saida, true);
            if (resultado.isError()) {
                return false;
            }
            if (resultado.isUnderflow()) {
                break;
            }
            saida.clear();
        }
        while (decodificador.flush(saida).isOverflow()) {
            saida.clear();
        }
        return true;
    }
}
