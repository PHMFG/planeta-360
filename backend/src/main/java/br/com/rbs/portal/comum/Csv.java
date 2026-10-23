package br.com.rbs.portal.comum;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/**
 * Gera os arquivos CSV que a API exporta (lista de relatórios, relatório, histórico). Atende os itens 5 e 6 da Entrega 2.
 * [CARGA] Formato pensado para o Excel em português: separador ponto e vírgula, UTF-8 com BOM, números decimais com
 * vírgula, aspas no padrão RFC 4180 (feito pelo commons-csv) e células neutralizadas contra injeção de fórmula.
 *
 * <p>Uso: {@code Csv.resposta("historico-2026-10-23.csv", List.of("id", "acao"), linhas)}, em que cada linha é uma lista
 * de células; {@link #linha(Object...)} monta uma linha que aceita células nulas.
 */
public final class Csv {

    /** Separador de colunas. O Excel em português usa ponto e vírgula, porque a vírgula é o separador decimal. */
    public static final char SEPARADOR = ';';

    private static final byte[] BOM_UTF8 = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    // Padrão RFC 4180 (aspas só onde precisa, aspa dobrada dentro do texto, linhas terminadas em CRLF), com ";".
    private static final CSVFormat FORMATO = CSVFormat.RFC4180.builder().setDelimiter(SEPARADOR).get();

    private Csv() {
    }

    /** Monta uma linha com as células dadas; ao contrário de List.of, aceita {@code null} (sai como célula vazia). */
    public static List<Object> linha(Object... celulas) {
        return Arrays.asList(celulas);
    }

    /**
     * O conteúdo do CSV em bytes: BOM UTF-8, a linha de cabeçalho e uma linha por item de {@code linhas}.
     * Todo o arquivo fica na memória; serve para as exportações limitadas da API (por exemplo, até 50 mil linhas).
     */
    public static byte[] gerar(List<String> cabecalho, Iterable<? extends Iterable<?>> linhas) {
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        // O BOM vai direto nos bytes, antes do texto, para o Excel reconhecer o UTF-8 e os acentos saírem certos.
        saida.writeBytes(BOM_UTF8);
        try (Writer escritor = new OutputStreamWriter(saida, StandardCharsets.UTF_8);
             CSVPrinter impressora = new CSVPrinter(escritor, FORMATO)) {
            impressora.printRecord(cabecalho.stream().map(Csv::celula).toList());
            for (Iterable<?> linha : linhas) {
                for (Object valor : linha) {
                    impressora.print(celula(valor));
                }
                impressora.println();
            }
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }
        return saida.toByteArray();
    }

    /**
     * A resposta HTTP pronta: {@code Content-Type: text/csv; charset=UTF-8} e {@code Content-Disposition: attachment}
     * com o nome do arquivo (use só letras, números, ponto e hífen no nome). [UPLOAD] O "attachment" faz o navegador
     * baixar o arquivo em vez de abri-lo na página.
     */
    public static ResponseEntity<byte[]> resposta(String nomeDoArquivo, List<String> cabecalho,
                                                  Iterable<? extends Iterable<?>> linhas) {
        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.set(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8");
        cabecalhos.setContentDisposition(ContentDisposition.attachment().filename(nomeDoArquivo).build());
        return ResponseEntity.ok().headers(cabecalhos).body(gerar(cabecalho, linhas));
    }

    /**
     * [CARGA] Neutraliza a injeção de fórmula: texto que começa com =, +, - ou @ ganha um apóstrofo na frente, e o Excel
     * o trata como texto em vez de executá-lo. A tabulação e o retorno de carro no começo também são recusados, porque
     * alguns programas de planilha os ignoram e leem a fórmula que vem depois (recomendação da OWASP).
     */
    public static String neutralizar(String texto) {
        if (texto == null || texto.isEmpty()) {
            return "";
        }
        return switch (texto.charAt(0)) {
            case '=', '+', '-', '@', '\t', '\r' -> "'" + texto;
            default -> texto;
        };
    }

    /**
     * Texto de uma célula. Número e data são formatados aqui e NÃO são neutralizados (um "-5" numérico é um número, não
     * uma fórmula); todo o resto vira texto e passa por {@link #neutralizar}. Nulo vira célula vazia.
     * Decimais (BigDecimal, Double, Float) saem com VÍRGULA e sem zeros sobrando ("90,9"), que é como o Excel em português
     * lê um número; com ponto ele o trataria como texto (ou como data). Inteiros saem como estão.
     */
    private static String celula(Object valor) {
        return switch (valor) {
            case null -> "";
            case BigDecimal decimal -> decimalComVirgula(decimal);
            case Double real -> real.isNaN() || real.isInfinite() ? "" : decimalComVirgula(BigDecimal.valueOf(real));
            case Float real -> real.isNaN() || real.isInfinite() ? "" : decimalComVirgula(new BigDecimal(real.toString()));
            case Number numero -> numero.toString();
            case OffsetDateTime data -> DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Datas.emBrasilia(data));
            case Enum<?> constante -> constante.name();
            default -> neutralizar(valor.toString());
        };
    }

    /** Sem notação científica, sem zeros à direita e com a vírgula no lugar do ponto: 100.00 vira "100", -12.50 vira "-12,5". */
    private static String decimalComVirgula(BigDecimal decimal) {
        if (decimal.signum() == 0) {
            return "0";
        }
        return decimal.stripTrailingZeros().toPlainString().replace('.', ',');
    }
}
