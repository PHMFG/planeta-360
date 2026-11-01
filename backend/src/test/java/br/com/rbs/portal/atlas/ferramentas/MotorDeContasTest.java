package br.com.rbs.portal.atlas.ferramentas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.atlas.ferramentas.MotorDeContas.Resultado;
import br.com.rbs.portal.comum.DadosInvalidosException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * [ATLAS] [VALIDACAO] O motor das contas do {@code calcular} SOZINHO, sem a lista de tokens (M5, bloqueante do plano): as travas do
 * JEXL (recursos desligados, permissões do {@code SECURE} mais a classe das funções, sandbox que nega tudo, nenhum namespace) seguram
 * por si {@code new}, chamada de método, laço, lambda, pragma, acesso a classe e efeito global. Também confere a aritmética em
 * {@code BigDecimal}, as funções {@code pct}, {@code min} e {@code max}, a divisão por zero (indisponível, nunca zero) e o prazo de
 * 200 ms. Unitário.
 */
class MotorDeContasTest {

    private final MotorDeContas motor = new MotorDeContas();

    static Map<String, BigDecimal> variaveis(Object... paresNomeValor) {
        Map<String, BigDecimal> mapa = new LinkedHashMap<>();
        for (int i = 0; i < paresNomeValor.length; i += 2) {
            mapa.put((String) paresNomeValor[i], new BigDecimal(paresNomeValor[i + 1].toString()));
        }
        return mapa;
    }

    BigDecimal valor(String script, Object... paresNomeValor) {
        Resultado resultado = motor.avaliar(script, variaveis(paresNomeValor));
        assertThat(resultado.disponivel()).as(script).isTrue();
        return resultado.valor();
    }

    // ---------------------------------------------------------------------
    // A aritmética
    // ---------------------------------------------------------------------

    @Test
    void asQuatroOperacoesEOsParenteses() {
        assertThat(valor("a + b", "a", 10, "b", 4)).isEqualByComparingTo("14");
        assertThat(valor("a - b", "a", 10, "b", 4)).isEqualByComparingTo("6");
        assertThat(valor("a * b", "a", 312, "b", 34)).isEqualByComparingTo("10608");
        assertThat(valor("a / b", "a", 10, "b", 4)).isEqualByComparingTo("2.5");
        assertThat(valor("(a + b) * 2", "a", 10, "b", 4)).isEqualByComparingTo("28");
        assertThat(valor("-a + b", "a", 10, "b", 4)).isEqualByComparingTo("-6");
    }

    @Test
    void aContaFicaEmBigDecimalSemErroDeBinario() {
        // 0,1 + 0,2 não dá 0,30000000000000004
        assertThat(valor("a + b", "a", "0.1", "b", "0.2")).isEqualByComparingTo("0.3");
        // uma terça parte de cem tem as 34 casas do DECIMAL128; o arredondamento é só na saída, em quem chama
        assertThat(valor("a * 100 / b", "a", 10, "b", 3)).isEqualByComparingTo("333.3333333333333333333333333333333");
        assertThat(valor("a / b", "a", 1, "b", 3).scale()).isGreaterThan(10);
    }

    @Test
    void divisaoDeInteirosTambemEDecimal() {
        assertThat(valor("a / 2", "a", 5)).isEqualByComparingTo("2.5");
        assertThat(valor("a / b", "a", 1, "b", 4)).isEqualByComparingTo("0.25");
    }

    @Test
    void pctMinEMaxSaoAsFuncoes() {
        assertThat(valor("pct(a, b)", "a", 17, "b", 19)).isEqualByComparingTo("89.4");
        assertThat(valor("pct(a, b)", "a", 459900, "b", 460000)).isEqualByComparingTo("99.9");
        assertThat(valor("min(a, b)", "a", 10, "b", 4)).isEqualByComparingTo("4");
        assertThat(valor("max(a, b)", "a", 10, "b", 4)).isEqualByComparingTo("10");
        assertThat(valor("max(a, 0)", "a", -3)).isEqualByComparingTo("0");
        assertThat(valor("pct(a, b) * 2", "a", 17, "b", 19)).as("o pct entra cortado em 89,4").isEqualByComparingTo("178.8");
    }

    // ---------------------------------------------------------------------
    // Denominador zero: indisponível, nunca zero
    // ---------------------------------------------------------------------

    @Test
    void divisaoPorZeroEIndisponivelNuncaZero() {
        for (String script : List.of("a / b", "a / 0", "pct(a, b)", "pct(a, 0)", "a / (b - b)", "a * 100 / b")) {
            Resultado resultado = motor.avaliar(script, variaveis("a", 10, "b", 0));
            assertThat(resultado.disponivel()).as(script).isFalse();
            assertThat(resultado.valor()).as(script).isNull();
            assertThat(resultado.motivo()).as(script).isNotBlank();
        }
    }

    @Test
    void zeroNoNumeradorEUmResultadoDeVerdade() {
        Resultado resultado = motor.avaliar("a / b", variaveis("a", 0, "b", 10));

        assertThat(resultado.disponivel()).isTrue();
        assertThat(resultado.valor()).isEqualByComparingTo("0");
    }

    // ---------------------------------------------------------------------
    // As travas do JEXL seguram sem a lista de tokens
    // ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "new('java.lang.ProcessBuilder', 'ls')",
            "new('java.io.File', 'x')",
            "''.class",
            "a.class",
            "a.getClass()",
            "a.toString()",
            "a.getClass().getClassLoader()",
            "'x'.length()",
            "Math.abs(a)",
            "java.lang.Runtime.getRuntime()",
            "#pragma jexl.import java.lang",
            "#pragma jexl.namespace.sys java.lang.System",
            "while (true) {}",
            "for (i : a) {}",
            "do { } while (true)",
            "(x) -> x",
            "function(x) { x }",
            "var x = 1; x",
            "a++",
            "a = 5",
            "x = 5",
            "math:abs(a)",
            "sys:exit(1)",
            "[a]",
            "{'a':b}",
            "a.b",
            "@synchronized a",
            "foo + a",
            "a +",
            "((a)",
    })
    void asTravasDoJexlRecusamOQueNaoEAritmetica(String script) {
        assertThatThrownBy(() -> motor.avaliar(script, variaveis("a", 10, "b", 4)))
                .as(script).isInstanceOf(DadosInvalidosException.class);
    }

    @Test
    void ofuncaoQueNaoEDasTresNaoResolve() {
        assertThatThrownBy(() -> motor.avaliar("abs(a)", variaveis("a", 10))).isInstanceOf(DadosInvalidosException.class);
        assertThatThrownBy(() -> motor.avaliar("pct(a)", variaveis("a", 10))).isInstanceOf(DadosInvalidosException.class);
        assertThatThrownBy(() -> motor.avaliar("min(a, b, a)", variaveis("a", 10, "b", 2))).isInstanceOf(DadosInvalidosException.class);
    }

    @Test
    void oErroNaoDevolveTextoInternoDoJexl() {
        DadosInvalidosException erro = org.junit.jupiter.api.Assertions.assertThrows(DadosInvalidosException.class,
                () -> motor.avaliar("new('java.lang.ProcessBuilder', 'ls')", variaveis("a", 10)));

        assertThat(erro.getMessage() + " " + erro.campos()).doesNotContain("org.apache", "JexlException", "ProcessBuilder");
    }

    @Test
    void somenteAClasseDasFuncoesEstaLiberadaNasPermissoes() throws Exception {
        assertThat(MotorDeContas.PACOTE_DAS_FUNCOES).isEqualTo("br.com.rbs.portal.atlas.ferramentas.funcoes");
        var classes = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath*:" + MotorDeContas.PACOTE_DAS_FUNCOES.replace('.', '/') + "/*.class");
        assertThat(java.util.Arrays.stream(classes).map(org.springframework.core.io.Resource::getFilename).toList())
                .as("nenhuma outra classe vive no pacote liberado").containsExactly("FuncoesDeConta.class");
    }

    // ---------------------------------------------------------------------
    // O prazo
    // ---------------------------------------------------------------------

    @Test
    void contaQueDemoraMaisQueOPrazoEInterrompidaERecusada() {
        long inicio = System.nanoTime();

        assertThatThrownBy(() -> MotorDeContas.comPrazo(50, TimeUnit.MILLISECONDS, () -> {
            Thread.sleep(5_000);
            return BigDecimal.ONE;
        })).isInstanceOf(DadosInvalidosException.class).hasMessageContaining("demorou");

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio)).as("não esperou os 5 s").isLessThan(2_000);
    }

    @Test
    void oPrazoPadraoEDe200Milissegundos() {
        assertThat(MotorDeContas.PRAZO_EM_MS).isEqualTo(200);
    }
}
