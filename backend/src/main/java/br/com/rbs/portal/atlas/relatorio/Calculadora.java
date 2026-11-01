package br.com.rbs.portal.atlas.relatorio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * As contas das fórmulas fixas do modelo-base (plano, seção 6.4). [ATLAS] [RELATORIO] No P0 só existe {@code pct(parte, todo)},
 * sem script do modelo e sem JEXL: o texto da fórmula vem do arquivo do modelo, que é nosso, e é lido por uma regex. O percentual
 * é cortado na primeira casa, como em todo o portal (mesma conta de {@code fn_pct_truncado}), nunca arredondado: 1.072 de 1.200
 * é 89,3 e não 89,4. Sem valor numa variável, ou com o denominador zero, a conta não dá zero: dá "indisponível", com o motivo.
 */
@Component
public class Calculadora {

    /** A fórmula {@code pct(parte, todo)}: dois nomes de variável. */
    private static final Pattern PCT = Pattern.compile("^pct\\(\\s*([a-z_][a-z0-9_]*)\\s*,\\s*([a-z_][a-z0-9_]*)\\s*\\)$");

    private static final BigDecimal CEM = BigDecimal.valueOf(100);

    /**
     * O resultado de uma conta. {@code valor} é nulo quando indisponível; então {@code semValor} lista as variáveis que não
     * tinham valor (na ordem da fórmula) ou {@code denominadorZero} diz qual variável era zero. Um dos dois explica a ausência.
     */
    public record Resultado(BigDecimal valor, List<String> semValor, String denominadorZero) {

        public boolean disponivel() {
            return valor != null;
        }
    }

    /**
     * {@code (100 × parte) ÷ todo}, cortado na primeira casa. Nulo se faltar uma das duas ou se {@code todo} for zero.
     * O denominador negativo não existe nos campos do portal (mínimo 0), mas a conta não o impede.
     */
    public static BigDecimal pct(BigDecimal parte, BigDecimal todo) {
        if (parte == null || todo == null || todo.signum() == 0) {
            return null;
        }
        return CEM.multiply(parte).divide(todo, 1, RoundingMode.DOWN);
    }

    /** Os nomes das variáveis da fórmula, na ordem em que ela os usa. Fórmula que não é uma das fixas é erro de programação. */
    public static List<String> variaveisDe(String script) {
        Matcher m = PCT.matcher(script == null ? "" : script.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Fórmula desconhecida: " + script);
        }
        return new ArrayList<>(List.of(m.group(1), m.group(2)));
    }

    /**
     * Roda a fórmula com os valores dados (nome da variável para número; nulo = a marca não informou). [VALIDACAO] A fórmula
     * tem de ser uma das fixas e todas as variáveis dela têm de estar no mapa (com valor ou nulo).
     */
    public Resultado calcular(String script, Map<String, BigDecimal> variaveis) {
        List<String> nomes = variaveisDe(script);
        List<String> semValor = new ArrayList<>();
        for (String nome : nomes) {
            if (!variaveis.containsKey(nome)) {
                throw new IllegalArgumentException("A fórmula " + script + " usa a variável " + nome + ", que não foi definida.");
            }
            if (variaveis.get(nome) == null) {
                semValor.add(nome);
            }
        }
        if (!semValor.isEmpty()) {
            return new Resultado(null, semValor, null);
        }
        String todo = nomes.get(1);
        if (variaveis.get(todo).signum() == 0) {
            return new Resultado(null, List.of(), todo);
        }
        return new Resultado(pct(variaveis.get(nomes.get(0)), variaveis.get(todo)), List.of(), null);
    }
}
