package br.com.rbs.portal.atlas.ferramentas;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.atlas.relatorio.Calculadora;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] [VALIDACAO] O {@code pct} do {@code calcular} é o mesmo {@code fn_pct_truncado} do banco (M5, bloqueante do plano): 17 de
 * 19 dá 89,4 e 459.900 de 460.000 dá 99,9, cortados na primeira casa, nunca arredondados (89,47 e 99,978). A {@link Calculadora},
 * a função {@code pct} dentro do motor JEXL e o banco dão o mesmo número, também num lote de pares sorteados. Integração.
 */
@TesteDeIntegracao
@Transactional
class PctIgualAoBancoTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MotorDeContas motor;

    BigDecimal doBanco(long parte, long todo) {
        return jdbc.sql("SELECT fn_pct_truncado((100 * CAST(:parte AS numeric)) / CAST(:todo AS numeric))")
                .param("parte", parte).param("todo", todo).query(BigDecimal.class).single();
    }

    BigDecimal doMotor(long parte, long todo) {
        return motor.avaliar("pct(a, b)", Map.of("a", BigDecimal.valueOf(parte), "b", BigDecimal.valueOf(todo))).valor();
    }

    @Test
    void osDoisExemplosDoPlano() {
        assertThat(doBanco(17, 19)).isEqualByComparingTo("89.4");
        assertThat(Calculadora.pct(BigDecimal.valueOf(17), BigDecimal.valueOf(19))).isEqualByComparingTo("89.4");
        assertThat(doMotor(17, 19)).isEqualByComparingTo("89.4");

        assertThat(doBanco(459_900, 460_000)).isEqualByComparingTo("99.9");
        assertThat(Calculadora.pct(BigDecimal.valueOf(459_900), BigDecimal.valueOf(460_000))).isEqualByComparingTo("99.9");
        assertThat(doMotor(459_900, 460_000)).isEqualByComparingTo("99.9");
    }

    @Test
    void oMotorDaOMesmoNumeroDoBancoEmParesSorteados() {
        Random sorteio = new Random(30_10_2026L);
        for (int i = 0; i < 300; i++) {
            long todo = 1 + sorteio.nextInt(2_000_000);
            long parte = sorteio.nextInt((int) Math.min(Integer.MAX_VALUE, todo * 2));
            BigDecimal esperado = doBanco(parte, todo);

            assertThat(doMotor(parte, todo)).as(parte + " de " + todo).isEqualByComparingTo(esperado);
            assertThat(Calculadora.pct(BigDecimal.valueOf(parte), BigDecimal.valueOf(todo))).as(parte + " de " + todo)
                    .isEqualByComparingTo(esperado);
        }
    }

    @Test
    void cortaEmVezDeArredondar() {
        // 1.072 de 1.200 é 89,333; 100 de 3 é 3.333,3; 2 de 3 é 66,666 (arredondado daria 66,7)
        assertThat(doMotor(1_072, 1_200)).isEqualByComparingTo("89.3");
        assertThat(doMotor(2, 3)).isEqualByComparingTo("66.6");
        assertThat(doBanco(2, 3)).isEqualByComparingTo("66.6");
    }

    @Test
    void totalZeroNaoDaZeroNemEmNenhumDosDois() {
        assertThat(Calculadora.pct(BigDecimal.ONE, BigDecimal.ZERO)).isNull();
        assertThat(motor.avaliar("pct(a, b)", Map.of("a", BigDecimal.ONE, "b", BigDecimal.ZERO)).disponivel()).isFalse();
    }
}
