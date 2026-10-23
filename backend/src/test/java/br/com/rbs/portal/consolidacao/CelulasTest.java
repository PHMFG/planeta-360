package br.com.rbs.portal.consolidacao;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Testa a conversão do texto das células do CSV (data, decimal, inteiro) sem banco nem API. Atende o item 5 da Entrega 2.
 * [CARGA] [VALIDACAO] O que não converte vira nulo, e a função do banco rejeita a linha com o campo.
 */
class CelulasTest {

    @Test
    void dataIsoComFusoMantemOInstante() {
        assertThat(Celulas.data("2026-11-20T10:15:30-03:00").toInstant().toString()).isEqualTo("2026-11-20T13:15:30Z");
        assertThat(Celulas.data("2026-11-20T13:15:30Z").toInstant().toString()).isEqualTo("2026-11-20T13:15:30Z");
        assertThat(Celulas.data("2026-11-20T10:15-03:00").toInstant().toString()).isEqualTo("2026-11-20T13:15:00Z");
    }

    @Test
    void dataSemFusoValeNoHorarioDeBrasiliaEAceitaEspacoNoLugarDoT() {
        assertThat(Celulas.data("2026-11-20T10:15:30").toInstant().toString()).isEqualTo("2026-11-20T13:15:30Z");
        assertThat(Celulas.data("2026-11-20 10:15:30").toInstant().toString()).isEqualTo("2026-11-20T13:15:30Z");
        assertThat(Celulas.data("2026-11-20 10:15:30-03:00").toInstant().toString()).isEqualTo("2026-11-20T13:15:30Z");
    }

    @Test
    void dataIlegivelViraNulo() {
        assertThat(Celulas.data(null)).isNull();
        assertThat(Celulas.data("ontem")).isNull();
        assertThat(Celulas.data("20/11/2026 10:15")).isNull();
        assertThat(Celulas.data("2026-11-20")).isNull();
        assertThat(Celulas.data("2026-13-45T10:15:30-03:00")).isNull();
    }

    @Test
    void decimalComPontoFicaComDuasCasas() {
        assertThat(Celulas.decimal("8.4", 10)).isEqualByComparingTo("8.40").extracting(BigDecimal::scale).isEqualTo(2);
        assertThat(Celulas.decimal("12500", 14)).isEqualByComparingTo("12500");
        assertThat(Celulas.decimal("-3", 14)).isEqualByComparingTo("-3");
        assertThat(Celulas.decimal("+2.345", 14)).isEqualByComparingTo("2.35");
    }

    @Test
    void decimalQueNaoServeViraNulo() {
        assertThat(Celulas.decimal(null, 10)).isNull();
        assertThat(Celulas.decimal("8,4", 10)).as("vírgula decimal").isNull();
        assertThat(Celulas.decimal("muitas", 10)).isNull();
        assertThat(Celulas.decimal("1e3", 10)).as("notação científica").isNull();
        assertThat(Celulas.decimal("1E999999999", 10)).isNull();
        assertThat(Celulas.decimal("NaN", 10)).isNull();
        assertThat(Celulas.decimal("123456789.5", 10)).as("passa dos 10 algarismos da coluna").isNull();
        assertThat(Celulas.decimal("12345678.99", 10)).isEqualByComparingTo("12345678.99");
        assertThat(Celulas.decimal("1".repeat(41), 100)).as("texto enorme").isNull();
    }

    @Test
    void inteiroDentroDaFaixa() {
        assertThat(Celulas.inteiro("2", 1, 3)).isEqualTo(2);
        assertThat(Celulas.inteiro("+412", Integer.MIN_VALUE, Integer.MAX_VALUE)).isEqualTo(412);
        assertThat(Celulas.inteiro("-5", -10, 10)).isEqualTo(-5);
        assertThat(Celulas.inteiro("40000", Short.MIN_VALUE, Short.MAX_VALUE)).as("não cabe em smallint").isNull();
        assertThat(Celulas.inteiro("1.0", 1, 3)).isNull();
        assertThat(Celulas.inteiro("dois", 1, 3)).isNull();
        assertThat(Celulas.inteiro("99999999999", Integer.MIN_VALUE, Integer.MAX_VALUE)).isNull();
        assertThat(Celulas.inteiro(null, 1, 3)).isNull();
    }

    @Test
    void unidadeSobeParaMaiusculas() {
        assertThat(Celulas.unidade("percentual")).isEqualTo("PERCENTUAL");
        assertThat(Celulas.unidade(null)).isNull();
    }
}
