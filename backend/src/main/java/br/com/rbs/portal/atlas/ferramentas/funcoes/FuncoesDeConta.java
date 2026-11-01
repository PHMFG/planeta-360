package br.com.rbs.portal.atlas.ferramentas.funcoes;

import br.com.rbs.portal.atlas.relatorio.Calculadora;
import java.math.BigDecimal;

/**
 * As três funções que uma conta do {@code calcular} pode chamar: {@code pct(parte, todo)}, {@code min(a, b)} e {@code max(a, b)}
 * (plano do Atlas completo, M5; seção 6.4 do plano do MVP). [ATLAS] [VALIDACAO] Esta é a ÚNICA classe que o motor deixa o script
 * chamar: o {@code JexlSandbox} nega todo o resto, e o pacote é o único que as permissões do JEXL acrescentam ao {@code SECURE}.
 * Por isso mora num pacote só dela, sem mais nada ao lado. Os argumentos chegam como o JEXL os entrega (números de qualquer tipo) e
 * viram {@link BigDecimal}.
 * <p>
 * {@code pct} é a mesma conta de sempre ({@link Calculadora#pct}): {@code (100 × parte) ÷ todo}, cortado na primeira casa como o
 * {@code fn_pct_truncado} do banco (17 de 19 dá 89,4; 459.900 de 460.000 dá 99,9). Com {@code todo} zero levanta
 * {@link ArithmeticException}, e a conta sai indisponível: nunca zero.
 */
public final class FuncoesDeConta {

    public BigDecimal pct(Object parte, Object todo) {
        BigDecimal resultado = Calculadora.pct(numero(parte), numero(todo));
        if (resultado == null) {
            throw new ArithmeticException("pct com o total zero");
        }
        return resultado;
    }

    public BigDecimal min(Object a, Object b) {
        return numero(a).min(numero(b));
    }

    public BigDecimal max(Object a, Object b) {
        return numero(a).max(numero(b));
    }

    private static BigDecimal numero(Object valor) {
        if (valor instanceof BigDecimal decimal) {
            return decimal;
        }
        if (valor instanceof Number numero) {
            return new BigDecimal(numero.toString());
        }
        throw new IllegalArgumentException("A função espera um número.");
    }
}
