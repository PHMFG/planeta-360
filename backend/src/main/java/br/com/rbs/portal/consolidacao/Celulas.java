package br.com.rbs.portal.consolidacao;

import br.com.rbs.portal.comum.Datas;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Converte o texto de uma célula do CSV no tipo da coluna da área de passagem (data, decimal, inteiro). Atende o item 5
 * da Entrega 2. [CARGA] [VALIDACAO] Quando o texto não converte, devolve {@code null}: a linha vai para a área de passagem
 * com o campo vazio e a função do banco a rejeita, com o nome do campo e o motivo, em vez de o arquivo inteiro falhar.
 */
final class Celulas {

    // Só dígitos, sinal e ponto decimal. Notação científica ("1E999999999") ficaria de fora de propósito: o BigDecimal
    // tentaria montar um número gigantesco ao arredondar.
    private static final Pattern DECIMAL = Pattern.compile("[+-]?\\d+(\\.\\d+)?");
    private static final Pattern INTEIRO = Pattern.compile("[+-]?\\d{1,9}");
    private static final int TAMANHO_MAXIMO_DO_NUMERO = 40;

    private Celulas() {
    }

    /**
     * Data e hora ISO 8601 com o fuso ("2026-11-20T10:15:30-03:00" ou com "Z"). Sem fuso, vale o horário de Brasília.
     * Aceita um espaço no lugar do "T" (é assim que algumas planilhas gravam). Qualquer outro formato vira nulo.
     */
    static OffsetDateTime data(String texto) {
        if (texto == null) {
            return null;
        }
        String iso = texto.length() > 10 && texto.charAt(10) == ' ' ? texto.substring(0, 10) + "T" + texto.substring(11) : texto;
        try {
            return OffsetDateTime.parse(iso);
        } catch (DateTimeParseException semFuso) {
            try {
                return LocalDateTime.parse(iso).atZone(Datas.BRASILIA).toOffsetDateTime();
            } catch (DateTimeParseException invalida) {
                return null;
            }
        }
    }

    /**
     * Decimal com ponto ("8.4"), arredondado para 2 casas como as colunas numeric(n,2). Vírgula decimal, texto ou um número
     * com mais de {@code digitos} algarismos (a coluna não comporta) viram nulo.
     */
    static BigDecimal decimal(String texto, int digitos) {
        if (texto == null || texto.length() > TAMANHO_MAXIMO_DO_NUMERO || !DECIMAL.matcher(texto).matches()) {
            return null;
        }
        BigDecimal valor = new BigDecimal(texto).setScale(2, RoundingMode.HALF_UP);
        return valor.precision() > digitos ? null : valor;
    }

    /** Inteiro dentro de [minimo, maximo] ("2"). Decimal, texto ou fora da faixa viram nulo. */
    static Integer inteiro(String texto, int minimo, int maximo) {
        if (texto == null || !INTEIRO.matcher(texto).matches()) {
            return null;
        }
        int valor = Integer.parseInt(texto.startsWith("+") ? texto.substring(1) : texto);
        return valor < minimo || valor > maximo ? null : valor;
    }

    /** A unidade da pesquisa em maiúsculas ("percentual" vale como PERCENTUAL); a função do banco confere se é uma das três. */
    static String unidade(String texto) {
        return texto == null ? null : texto.toUpperCase(Locale.ROOT);
    }
}
