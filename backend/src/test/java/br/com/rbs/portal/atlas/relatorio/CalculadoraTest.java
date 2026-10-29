package br.com.rbs.portal.atlas.relatorio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.atlas.relatorio.Calculadora.Resultado;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * [RELATORIO] A conta das fórmulas fixas: {@code pct(parte, todo)} corta na primeira casa (não arredonda), nunca devolve zero no
 * lugar de "sem dado" e dá o mesmo número que a função fn_pct_truncado do banco, que é a conta de todo percentual do portal.
 * Atende o item 5 do pedido da segunda parte do Atlas. A comparação com o banco é o único teste que precisa do Spring.
 */
@TesteDeIntegracao
@Transactional
class CalculadoraTest {

    @Autowired
    Calculadora calculadora;

    @Autowired
    JdbcClient jdbc;

    private static BigDecimal n(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    void cortaNaPrimeiraCasaEmVezDeArredondar() {
        assertThat(Calculadora.pct(n("1072"), n("1200"))).isEqualByComparingTo("89.3");   // 89,333...
        assertThat(Calculadora.pct(n("17"), n("19"))).isEqualByComparingTo("89.4");       // 89,473...: arredondado seria 89,5
        assertThat(Calculadora.pct(n("459900"), n("460000"))).isEqualByComparingTo("99.9");   // 99,978...: arredondado seria 100
        assertThat(Calculadora.pct(n("1"), n("3"))).isEqualByComparingTo("33.3");
        assertThat(Calculadora.pct(n("2"), n("3"))).isEqualByComparingTo("66.6");
    }

    @Test
    void soChegaACemQuemEstaCompleto() {
        assertThat(Calculadora.pct(n("1200"), n("1200"))).isEqualByComparingTo("100");
        assertThat(Calculadora.pct(n("0"), n("1200"))).isEqualByComparingTo("0");
    }

    @Test
    void denominadorZeroOuValorFaltandoNaoViraZero() {
        assertThat(Calculadora.pct(n("5"), n("0"))).isNull();
        assertThat(Calculadora.pct(null, n("10"))).isNull();
        assertThat(Calculadora.pct(n("5"), null)).isNull();
    }

    @Test
    void calcularPelaFormulaDizOMotivoQuandoNaoDa() {
        Map<String, BigDecimal> semVisitantes = new HashMap<>();
        semVisitantes.put("cadastros", n("10"));
        semVisitantes.put("visitantes", null);
        Resultado faltando = calculadora.calcular("pct(cadastros, visitantes)", semVisitantes);
        assertThat(faltando.disponivel()).isFalse();
        assertThat(faltando.semValor()).containsExactly("visitantes");

        Resultado zero = calculadora.calcular("pct(cadastros, visitantes)", Map.of("cadastros", n("0"), "visitantes", n("0")));
        assertThat(zero.disponivel()).isFalse();
        assertThat(zero.denominadorZero()).isEqualTo("visitantes");

        Resultado ok = calculadora.calcular("pct( cadastros , visitantes )", Map.of("cadastros", n("1072"), "visitantes", n("1200")));
        assertThat(ok.disponivel()).isTrue();
        assertThat(ok.valor()).isEqualByComparingTo("89.3");
    }

    @Test
    void soAsFormulasFixasRodam() {
        assertThatThrownBy(() -> calculadora.calcular("1 + 1", Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculadora.calcular("exec('ls')", Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculadora.calcular("pct(a, b)", Map.of("a", n("1")))).isInstanceOf(IllegalArgumentException.class);
        assertThat(Calculadora.variaveisDe("pct(distribuidos, disponiveis)")).containsExactly("distribuidos", "disponiveis");
    }

    @Test
    void daOMesmoNumeroQueFnPctTruncadoDoBanco() {
        List<String[]> pares = List.of(new String[]{"1072", "1200"}, new String[]{"17", "19"}, new String[]{"459900", "460000"},
                new String[]{"1", "3"}, new String[]{"2", "3"}, new String[]{"1200", "1200"}, new String[]{"0", "1200"},
                new String[]{"7", "9"}, new String[]{"1", "7"}, new String[]{"999999", "1000000"}, new String[]{"2", "1"},
                new String[]{"907300", "460000"}, new String[]{"12.5", "50"}, new String[]{"333", "1000"}, new String[]{"666", "1000"});

        for (String[] par : pares) {
            BigDecimal doBanco = jdbc.sql("SELECT fn_pct_truncado(100 * CAST(:parte AS numeric) / CAST(:todo AS numeric))")
                    .param("parte", n(par[0])).param("todo", n(par[1])).query(BigDecimal.class).single();
            assertThat(Calculadora.pct(n(par[0]), n(par[1]))).as("pct(" + par[0] + ", " + par[1] + ")").isEqualByComparingTo(doBanco);
        }
    }
}
