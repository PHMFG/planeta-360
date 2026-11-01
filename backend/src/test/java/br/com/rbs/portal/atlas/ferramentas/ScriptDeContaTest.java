package br.com.rbs.portal.atlas.ferramentas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.comum.DadosInvalidosException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * [ATLAS] [VALIDACAO] A lista de tokens permitidos que o script do {@code calcular} atravessa ANTES do JEXL (M5, bloqueante do
 * plano): os nomes das variáveis declaradas, {@code pct}, {@code min}, {@code max}, {@code + - * / ( ) ,} e as constantes exatas
 * {@code 0 1 2 100 1000}, no máximo duas, com pelo menos uma variável. Sem aspas, sem {@code =~}, sem ponto. É o que impede um número
 * escrito no script: {@code '312'}, {@code "31" + "2"} e {@code 100+100+100+2*2*2*2-2-2} dariam 312 com as outras travas ligadas.
 * Unitário.
 */
class ScriptDeContaTest {

    private static final Set<String> AB = Set.of("a", "b");

    static void conferir(String script, Set<String> variaveis) {
        ScriptDeConta.conferir(script, variaveis);
    }

    /** As variáveis a e b que o script usa: declarar a que ele não usa seria outro motivo de recusa, e mascararia o que se testa. */
    static Set<String> declaradas(String script) {
        Set<String> usadas = new java.util.TreeSet<>();
        for (String nome : AB) {
            if (java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])" + nome + "(?![A-Za-z0-9_])").matcher(script).find()) {
                usadas.add(nome);
            }
        }
        return usadas;
    }

    // ---------------------------------------------------------------------
    // O que passa
    // ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "a / b",
            "a + b",
            "a - b",
            "a * b",
            "pct(a, b)",
            "pct(a,b)",
            "(a + b) * 2",
            "max(a, 0)",
            "min(a, b) / 1000",
            "-a + b",
            "a * -b",
            "( a + b )",
            "pct(a, b) * 100",
            "min(max(a, 0), b)",
    })
    void contasDeAritmeticaPassam(String script) {
        assertThatCode(() -> conferir(script, declaradas(script))).doesNotThrowAnyException();
    }

    @Test
    void asContasDoDiaADiaContinuamPassando() {
        Set<String> kvaTarifa = Set.of("kva", "tarifa");
        assertThatCode(() -> conferir("kva * tarifa", kvaTarifa)).doesNotThrowAnyException();
        assertThatCode(() -> conferir("pct(a, b)", AB)).doesNotThrowAnyException();
        assertThatCode(() -> conferir("(a + b) * c", Set.of("a", "b", "c"))).doesNotThrowAnyException();
        assertThatCode(() -> conferir("a * b + c * d", Set.of("a", "b", "c", "d"))).doesNotThrowAnyException();
        assertThatCode(() -> conferir("a - a * b / 100", AB)).as("a aparece duas vezes, e a divisão é por constante").doesNotThrowAnyException();
        assertThatCode(() -> conferir("min(a, b)", AB)).doesNotThrowAnyException();
        assertThatCode(() -> conferir("a * b / 100", AB)).doesNotThrowAnyException();
        assertThatCode(() -> conferir("a / b", AB)).doesNotThrowAnyException();
        assertThatCode(() -> conferir("(a - b) / b", AB)).as("variação como fração, sem o 100").doesNotThrowAnyException();
        assertThatCode(() -> conferir("pct(a - b, b)", AB)).doesNotThrowAnyException();
        assertThatCode(() -> conferir("a * a", Set.of("a"))).doesNotThrowAnyException();
        assertThatCode(() -> conferir("a * 100 / 100", Set.of("a"))).as("dividir por constante").doesNotThrowAnyException();
    }

    @Test
    void oConferirDevolveQuantasConstantesOScriptTem() {
        assertThat(conferirEContar("a * b", AB)).isZero();
        assertThat(conferirEContar("a * 2", Set.of("a"))).isEqualTo(1);
        assertThat(conferirEContar("a * 2 + 1", Set.of("a"))).isEqualTo(2);
        assertThat(conferirEContar("a * 100 / 1000", Set.of("a"))).isEqualTo(2);
    }

    static int conferirEContar(String script, Set<String> variaveis) {
        return ScriptDeConta.conferir(script, variaveis);
    }

    // ---------------------------------------------------------------------
    // Fabricar número com a própria variável (critério bloqueante: número sem origem)
    // ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "a + a + a",
            "a * a * a",
            "100*(a/a+a/a+a/a)+(a/a+a/a+a/a+a/a+a/a+a/a+a/a+a/a+a/a+a/a+a/a+a/a)",
            "1000*1000*(a/a)",
            "a / a",
            "a - a",
            "(a) / (a)",
            "a / (a)",
            "(a) - a",
            "-a / a",
            "a / -a",
            "(a) - (a) + b",
            "b * (a / a)",
            "pct(a, a)",
            "min(a, a)",
            "max(a, (a))",
            "pct((a), a)",
            "a + a - a",
            "min(a, b) / min(a, b)",
    })
    void aMesmaVariavelNaoFabricaNumero(String script) {
        assertThatThrownBy(() -> conferir(script, declaradas(script))).as(script).isInstanceOf(DadosInvalidosException.class);
    }

    @Test
    void amensagemDaMesmaVariavelDizOQueFazer() {
        assertThatThrownBy(() -> conferir("a / a", Set.of("a"))).hasMessageContaining("a / a").hasMessageContaining("sempre o mesmo resultado")
                .hasMessageContaining("isso não é uma conta");
        assertThatThrownBy(() -> conferir("a - a", Set.of("a"))).hasMessageContaining("a - a").hasMessageContaining("sempre o mesmo resultado");
        assertThatThrownBy(() -> conferir("a + a + a", Set.of("a"))).hasMessageContaining("aparece mais de 2 vezes");
        assertThatThrownBy(() -> conferir("pct(a, a)", Set.of("a"))).hasMessageContaining("pct(a, a)").hasMessageContaining("sempre o mesmo resultado");
        assertThatThrownBy(() -> conferir("min(a, a)", Set.of("a"))).hasMessageContaining("min(a, a)").hasMessageContaining("mesmo valor nos dois");
    }

    // ---------------------------------------------------------------------
    // A conferência por significado: parte do script que dá sempre o mesmo resultado
    // ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "(a + b) / (b + a)",
            "a / (a + 0)",
            "a / (a * 1)",
            "a - (a + 0)",
            "pct(a, a + 0)",
            "(a + b) - (b + a)",
            "(a * b) / (b * a)",
            "(a - b) + (b - a)",
            "a * 0",
            "(a + b) * 0 + a",
            "min(a, b) - min(b, a)",
            "min(a, a + 0)",
            "max(a + b, b + a)",
            "1000 * ((a + b) / (b + a))",
            "(a + b) / (a + b)",
            "pct(a + b, b + a)",
    })
    void partedoScriptQueDaSempreOMesmoResultadoERecusadaMesmoDisfarcada(String script) {
        assertThatThrownBy(() -> conferir(script, declaradas(script))).as(script).isInstanceOf(DadosInvalidosException.class);
    }

    @Test
    void oAtaqueDe2000ComQuatroVariaveisQueSeDesfazemDuasADuasNaoFecha() {
        // 1000 * ((a+b)/(b+a) + (c+d)/(d+c)) dava 2.000 com 3, 5, 7 e 9, com cada variável só duas vezes
        Set<String> abcd = Set.of("a", "b", "c", "d");
        assertThatThrownBy(() -> conferir("1000*((a+b)/(b+a)+(c+d)/(d+c))", abcd)).isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining("sempre o mesmo resultado").hasMessageContaining("(a + b) / (b + a)");
    }

    @Test
    void asContasDeVerdadeNaoSaoPegasPeloSignificado() {
        Set<String> abc = Set.of("a", "b", "c");
        Set<String> abcd = Set.of("a", "b", "c", "d");
        assertThatCode(() -> conferir("kva * tarifa", Set.of("kva", "tarifa"))).doesNotThrowAnyException();
        assertThatCode(() -> conferir("pct(a, b)", AB)).doesNotThrowAnyException();
        assertThatCode(() -> conferir("(a + b) * c", abc)).doesNotThrowAnyException();
        assertThatCode(() -> conferir("a * b + c * d", abcd)).doesNotThrowAnyException();
        assertThatCode(() -> conferir("a - a * b / 100", AB)).doesNotThrowAnyException();
        assertThatCode(() -> conferir("a + a", Set.of("a"))).as("x + x é o dobro, e vale").doesNotThrowAnyException();
        assertThatCode(() -> conferir("a * a", Set.of("a"))).doesNotThrowAnyException();
        assertThatCode(() -> conferir("min(a, b)", AB)).doesNotThrowAnyException();
        assertThatCode(() -> conferir("(a + b) / c", abc)).doesNotThrowAnyException();
        assertThatCode(() -> conferir("(a + b) / (a - b)", AB)).doesNotThrowAnyException();
        assertThatCode(() -> conferir("(a - b) / (a + b)", AB)).doesNotThrowAnyException();
        assertThatCode(() -> conferir("(a + b) * (a - b)", AB)).doesNotThrowAnyException();
        assertThatCode(() -> conferir("pct(a - b, a + b)", AB)).doesNotThrowAnyException();
        assertThatCode(() -> conferir("pct(a, a + b)", AB)).as("a parte e o total, com a parte dentro do total").doesNotThrowAnyException();
        assertThatCode(() -> conferir("(a * b) / (a + b)", AB)).doesNotThrowAnyException();
    }

    @Test
    void oPisoComMaxOuMinNaoEPegoPorDarSempreZeroNosValoresDeTeste() {
        Set<String> abc = Set.of("a", "b", "c");
        // max(0, a - b - c) pode dar 0 nos três valores de teste e continua sendo a conta de um piso em zero
        assertThatCode(() -> conferir("max(0, a - b - c)", abc)).doesNotThrowAnyException();
        assertThatCode(() -> conferir("max(a - b, 0)", AB)).doesNotThrowAnyException();
        assertThatCode(() -> conferir("min(a - b, 1000)", AB)).doesNotThrowAnyException();
        assertThatCode(() -> conferir("min(a, 0)", Set.of("a"))).doesNotThrowAnyException();
        // mas o piso de uma parte que já dá sempre o mesmo resultado continua recusado
        assertThatThrownBy(() -> conferir("max(a / a, 0)", Set.of("a"))).isInstanceOf(DadosInvalidosException.class);
    }

    @Test
    void duasVezesCadaVariavelPassaEATerceiraNao() {
        assertThatCode(() -> conferir("(a + b) * (a - b)", AB)).as("a e b duas vezes").doesNotThrowAnyException();
        assertThatCode(() -> conferir("a * b + a * b", AB)).doesNotThrowAnyException();
        assertThatThrownBy(() -> conferir("a * b + a * b + a", AB)).isInstanceOf(DadosInvalidosException.class).hasMessageContaining("a aparece");
    }

    // ---------------------------------------------------------------------
    // Percentual à mão escapa do corte da primeira casa
    // ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "a * 100 / b",
            "(a / b) * 100",
            "a / b * 100",
            "100 * a / b",
            "a * 100 / (a + b)",
            "100 * (a - b) / b",
    })
    void percentualFeitoAMaoComDivisaoPorVariavelEhRecusadoComAIndicacaoDoPct(String script) {
        assertThatThrownBy(() -> conferir(script, declaradas(script))).as(script).isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining("pct(parte, todo)");
    }

    @Test
    void aConstanteCemSemDivisaoPorVariavelContinuaValendo() {
        assertThatCode(() -> conferir("a * b / 100", AB)).doesNotThrowAnyException();
        assertThatCode(() -> conferir("pct(a, b) * 100", AB)).doesNotThrowAnyException();
        assertThatCode(() -> conferir("a * 100", Set.of("a"))).doesNotThrowAnyException();
        assertThatCode(() -> conferir("a / b * 2", AB)).as("divisão por variável, mas sem o 100").doesNotThrowAnyException();
    }

    @Test
    void variavelComAlgarismoNoNomeEUmNomeEnaoUmaConstante() {
        assertThatCode(() -> conferir("v1 + v2", Set.of("v1", "v2"))).doesNotThrowAnyException();
        assertThatCode(() -> conferir("custo_2027 / visitantes", Set.of("custo_2027", "visitantes"))).doesNotThrowAnyException();
    }

    @Test
    void zeroUmEDoisSaoConstantesELimiteDeDuas() {
        assertThatCode(() -> conferir("a * 2 + 1", Set.of("a"))).doesNotThrowAnyException();
        assertThatCode(() -> conferir("a / 1000 + 0", Set.of("a"))).doesNotThrowAnyException();
        assertThatCode(() -> conferir("a * 100 / 100", Set.of("a"))).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------
    // O que o plano manda recusar
    // ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "new('java.lang.ProcessBuilder', 'ls')",
            "new('java.lang.ProcessBuilder', a)",
            "''.class",
            "a.class",
            "a.getClass()",
            "a.toString()",
            "#pragma jexl.import java.lang",
            "while (a) { }",
            "for (i : a) { }",
            "(x) -> x",
            "function(x) { x }",
            "math:abs(a)",
            "sys:exit(1)",
            "Math.abs(a)",
            "a * 312",
            "'312'",
            "\"31\" + \"2\"",
            "a + '312'",
            "100+100+100+2*2*2*2-2-2",
            "a + 100+100+100+2*2*2*2-2-2",
            "a + 1 + 1 + 1",
            "100 + 1",
            "2 * 2",
            "'a' =~ 'a'",
            "a =~ b",
            "a * 1e3",
            "1e3 + a",
            "a * 0100",
            "a * 00",
            "a * 10",
            "a * 3",
            "a * 2x",
            "a * 1.5",
            "a * .5",
            "a++",
            "a--",
            "a // b",
            "a /* b */ + b",
            "a ? b : a",
            "a == b",
            "a = b",
            "!a",
            "a && b",
            "a || b",
            "a < b",
            "a % b",
            "a ^ b",
            "a; b",
            "a.b",
            "a[0]",
            "[a]",
            "{a}",
            "${a}",
            "a\nb",
            "а + b",
            "a + b ",
            "pct",
            "pct(a)",
            "a(b)",
            "x + a",
            "A + b",
            "a b",
            "a,b",
            "",
            "   ",
            "()",
            "(a + b",
            "a + b)",
            "((((((((((((a))))))))))))",
    })
    void asContasQueNaoSaoAritmeticaSobreAsVariaveisSaoRecusadas(String script) {
        if (script.equals("a + b ")) {
            // espaço no fim é inofensivo: o único desta lista que passa
            assertThatCode(() -> conferir(script, AB)).doesNotThrowAnyException();
            return;
        }
        assertThatThrownBy(() -> conferir(script, declaradas(script))).as(script).isInstanceOf(DadosInvalidosException.class);
    }

    @Test
    void scriptDeDuasMilEUmCaracteresERecusadoEODeDuasMilNao() {
        String duasMil = "a" + " ".repeat(1_999);
        String duasMilEUm = "a" + " ".repeat(2_000);

        assertThat(duasMil).hasSize(2_000);
        assertThatCode(() -> conferir(duasMil, Set.of("a"))).doesNotThrowAnyException();
        assertThat(duasMilEUm).hasSize(2_001);
        assertThatThrownBy(() -> conferir(duasMilEUm, Set.of("a"))).isInstanceOf(DadosInvalidosException.class).hasMessageContaining("2.000");
    }

    @Test
    void constanteQueNaoEUmaDasCincoExatasERecusadaMesmoSozinha() {
        for (String constante : new String[] {"3", "10", "12", "312", "99", "101", "999", "1001", "0100", "01", "007"}) {
            assertThatThrownBy(() -> conferir("a * " + constante, Set.of("a"))).as(constante).isInstanceOf(DadosInvalidosException.class);
        }
    }

    @Test
    void scriptSemVariavelERecusadoEVariavelDeclaradaQueNaoSeUsaTambem() {
        assertThatThrownBy(() -> conferir("100", Set.of())).isInstanceOf(DadosInvalidosException.class).hasMessageContaining("variável");
        assertThatThrownBy(() -> conferir("a * 2", Set.of("a", "b"))).isInstanceOf(DadosInvalidosException.class).hasMessageContaining("b");
        assertThatThrownBy(() -> conferir("a * 2", Set.of())).isInstanceOf(DadosInvalidosException.class);
    }

    @Test
    void aPalavraQueNaoEVariavelDeclaradaNemFuncaoEApontada() {
        assertThatThrownBy(() -> conferir("a * custo", Set.of("a"))).isInstanceOf(DadosInvalidosException.class).hasMessageContaining("custo");
    }

    // ---------------------------------------------------------------------
    // Nomes de variável
    // ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"new", "var", "if", "else", "for", "while", "do", "true", "false", "null", "empty", "size", "and", "or",
            "not", "eq", "ne", "lt", "le", "gt", "ge", "div", "mod", "in", "function", "return", "continue", "break", "let", "const",
            "pct", "min", "max", "default", "case", "try", "catch", "finally", "throw", "switch", "instanceof", "class"})
    void palavraReservadaDoJexlEAsFuncoesNaoSaoNomeDeVariavel(String nome) {
        assertThat(ScriptDeConta.nomeDeVariavelValido(nome)).as(nome).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "preco", "area_total", "v1", "custo_2027", "visitantes_total", "x_y_z"})
    void nomesComunsDeVariavelValem(String nome) {
        assertThat(ScriptDeConta.nomeDeVariavelValido(nome)).as(nome).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "A", "Preco", "1a", "_a", "a-b", "a.b", "a b", "a$", "preço", "a'", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", "ação"})
    void nomeForaDoPadraoNaoVale(String nome) {
        assertThat(ScriptDeConta.nomeDeVariavelValido(nome)).as(nome).isFalse();
    }

    @Test
    void erroTrazOMotivoNoCampoScript() {
        DadosInvalidosException erro = org.junit.jupiter.api.Assertions.assertThrows(DadosInvalidosException.class,
                () -> conferir("a * 312", Set.of("a")));

        assertThat(erro.campos()).containsKey("script");
        assertThat(erro.campos().get("script")).contains("312");
    }
}
