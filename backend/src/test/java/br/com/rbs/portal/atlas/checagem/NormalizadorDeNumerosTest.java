package br.com.rbs.portal.atlas.checagem;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.atlas.checagem.NormalizadorDeNumeros.Achado;
import br.com.rbs.portal.atlas.checagem.NormalizadorDeNumeros.Origens;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * [ATLAS] [VALIDACAO] O normalizador de números e datas do chat (M4, critério bloqueante: pelo menos 30 casos). É a mesma conta do
 * avaliador de conversas ({@code ferramentas/avaliacao-atlas/numeros.mjs}), em Java: a mesma coisa em formatos diferentes é igual, e
 * o que só PARECE igual não é. Unitário, sem Spring.
 */
class NormalizadorDeNumerosTest {

    /** Os valores do texto numa lista só, na forma que o JS usa: data vira {@code data:MM-DD} (e {@code ano:AAAA}). */
    static List<String> valores(String texto) {
        return valores(texto, false);
    }

    static List<String> valores(String texto, boolean fonte) {
        List<String> todos = new ArrayList<>();
        for (Achado achado : NormalizadorDeNumeros.extrair(texto, fonte)) {
            if (achado.ehData()) {
                todos.add("data:" + achado.dia());
                if (achado.ano() != null) {
                    todos.add("ano:" + achado.ano());
                }
            } else {
                todos.addAll(achado.valores());
            }
        }
        return todos;
    }

    // ---------------------------------------------------------------------
    // Números iguais em formatos diferentes
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "\"{0}\" e \"{1}\" são o mesmo número")
    @CsvSource(delimiter = '|', value = {
            "1.250.000,00 | 1250000", "R$ 312 | 312", "89,4% | 89,4", "10.608,00 | 10608", "312,00 | 312", "1.250 | 1250",
            "R$ 1.250,00 | 1250", "3,2 | 3.2", "0,5 | 0.5", "1.000.000 | 1000000", "007 | 7", "15,0 | 15", "12.5 | 12,5"})
    void omesmoNumeroEmFormatosDiferentes(String a, String b) {
        assertThat(valores(a)).isNotEmpty();
        // uma fonte com "a" dá origem a "b" e o inverso (1250 também vale como ano, e 1.250 não: por isso não se compara a lista inteira)
        assertThat(aceita(new Origens(a), b)).as(b + " tem origem em " + a).isTrue();
        assertThat(aceita(new Origens(b), a)).as(a + " tem origem em " + b).isTrue();
    }

    @ParameterizedTest(name = "\"{0}\" não é \"{1}\"")
    @CsvSource(delimiter = '|', value = {"312 | 321", "1.250 | 1,25", "1.250 | 125", "89,4 | 89,5", "10.608 | 10.806", "34 | 340", "3,2 | 32",
            "R$ 450 | R$ 4500", "17 | 71"})
    void numeroSoParecidoNaoEOMesmo(String a, String b) {
        assertThat(aceita(new Origens(a), b)).as(b + " não tem origem em " + a).isFalse();
    }

    @Test
    void algarismoComAEscalaPorExtensoVaiAoNumeroInteiro() {
        assertThat(valores("2 milhões de reais")).isEqualTo(valores("R$ 2.000.000,00")).containsExactly("2000000");
        assertThat(valores("1,5 milhão")).containsExactly("1500000");
        assertThat(valores("3 mil reais")).isEqualTo(valores("R$ 3.000")).containsExactly("3000");
        assertThat(valores("em 3 mil casos")).containsExactly("3000");
        assertThat(valores("2 milhoes")).as("sem acento").containsExactly("2000000");
        assertThat(valores("12 mil")).containsExactly("12000");
    }

    @Test
    void oPontoDeMilharEOPontoDecimalNaoSeConfundem() {
        assertThat(valores("1.250")).containsExactly("1250");
        assertThat(valores("1,250")).containsExactly("1.25");
        assertThat(valores("3.2")).containsExactly("3.2");
        assertThat(valores("1.250.000,5")).containsExactly("1250000.5");
    }

    @Test
    void umaListaComVirgulaNaoViraDecimal() {
        assertThat(valores("previstas: 19, comprovadas: 17")).containsExactly("19", "17");
    }

    @Test
    void todoDigitoContaTambemDentroDeUmaPalavra() {
        assertThat(valores("o documento D09")).containsExactly("9");
        assertThat(valores("operacoes@planeta360.invalid")).containsExactly("360");
        assertThat(valores("Planeta 360 em 2027")).as("o nome do festival não é número").containsExactly("2027", "ano:2027");
    }

    @Test
    void anoDeQuatroDigitosCarregaOValorEOAno() {
        assertThat(valores("2027")).containsExactly("2027", "ano:2027");
    }

    // ---------------------------------------------------------------------
    // Datas e horas
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "\"{0}\" é 4 de dezembro")
    @ValueSource(strings = {"04/12/2026", "4/12", "4 de dezembro", "2026-12-04", "04/12/26", "4 de Dezembro de 2026", "o dia 04/12"})
    void aMesmaDataEmFormatosDiferentes(String texto) {
        assertThat(valores(texto)).contains("data:12-04");
    }

    @Test
    void umaDataNaoViraVariosNumeros() {
        assertThat(NormalizadorDeNumeros.extrair("até 04/12", false)).hasSize(1);
        assertThat(NormalizadorDeNumeros.extrair("em 04/12/2026 às 18h", false)).hasSize(2);
        assertThat(valores("04/12/2026")).containsExactly("data:12-04", "ano:2026");
    }

    @Test
    void diasEmSequenciaComUmMesSoViramVariasDatas() {
        assertThat(valores("29 e 30 de janeiro de 2027")).containsExactly("data:01-29", "ano:2027", "data:01-30", "ano:2027");
        assertThat(valores("de 25 a 28 de janeiro")).containsExactly("data:01-25", "data:01-28");
        assertThat(valores("1º de novembro")).containsExactly("data:11-01");
    }

    @ParameterizedTest(name = "\"{0}\" e \"{1}\" são a mesma hora")
    @CsvSource(delimiter = '|', value = {"6h30 | 06:30", "6h | 06:00", "18h | 18:00", "14h00 | 14:00", "23h30 | 23:30", "9:05 | 09h05"})
    void aMesmaHoraEmFormatosDiferentes(String a, String b) {
        assertThat(valores(a)).isNotEmpty().isEqualTo(valores(b));
    }

    @Test
    void horasPorExtensoContamComoHoraEComoNumero() {
        assertThat(valores("18 horas")).containsExactly("hora:18:00", "18");
        assertThat(valores("30 horas")).as("30 não é hora do dia").containsExactly("30");
        assertThat(valores("48 horas")).containsExactly("48");
    }

    // ---------------------------------------------------------------------
    // Por extenso
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "\"{0}\" vale {1}")
    @CsvSource(delimiter = '|', value = {"trezentos e doze reais | 312", "vinte dias | 20", "dezessete aplicações | 17", "quarenta cortesias | 40",
            "duzentos e cinquenta kVA | 250", "três mil reais | 3000", "dois milhões de reais | 2000000", "cem credenciais | 100",
            "cento e vinte pessoas | 120", "mil ingressos | 1000", "duas horas | 2", "trinta por cento | 30", "oitenta e cinco metros | 85"})
    void numeralPorExtensoComUnidadeContaComoNumero(String texto, String esperado) {
        assertThat(valores(texto)).contains(esperado);
    }

    @ParameterizedTest(name = "\"{0}\" não é número")
    @ValueSource(strings = {"os dois manuais", "um dia", "uma hora", "a primeira vez", "uma ativação grande", "dois lados", "tenho um plano",
            "só um", "uma de nós", "as duas partes"})
    void artigoEDoisSemUnidadeNaoContamNaResposta(String texto) {
        assertThat(valores(texto)).isEmpty();
    }

    @ParameterizedTest(name = "\"{0}\" vale {1} mesmo sem unidade")
    @CsvSource(delimiter = '|', value = {"dez coisas | 10", "cem | 100", "doze convites | 12", "vinte | 20", "são trezentos e doze | 312",
            "sete | 7", "noventa | 90", "cento e vinte | 120", "dois mil | 2000", "mil | 1000", "um mil | 1000"})
    void todoNumeralPorExtensoContaNaRespostaMenosUmEDois(String texto, String esperado) {
        assertThat(valores(texto)).containsExactly(esperado);
    }

    @ParameterizedTest(name = "\"{0}\" vale {1}: dois e duas contam com unidade ou escala")
    @CsvSource(delimiter = '|', value = {"dois reais | 2", "duas inserções | 2", "dois por cento | 2", "dois % | 2", "duas horas | 2",
            "dois milhões de reais | 2000000", "dois milhoes | 2000000", "dois mil | 2000", "vinte porcento | 20", "vinte % | 20"})
    void doisComUnidadeOuEscalaContam(String texto, String esperado) {
        assertThat(valores(texto)).contains(esperado);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"89,4 por cento", "89,4 por  cento", "89,4 Por Cento", "89,4 porcento", "89,4 %"})
    void porCentoDepoisDeAlgarismoNaoEONumeralCento(String texto) {
        assertThat(valores(texto)).containsExactly("89.4");
    }

    @Test
    void cemEPorCentoSeparados() {
        assertThat(valores("cem por cento")).containsExactly("100");
        assertThat(valores("por cento")).isEmpty();
    }

    @Test
    void numeralSeguidoDeDeMesEData() {
        assertThat(NormalizadorDeNumeros.extrair("dez de dezembro", false)).singleElement().satisfies(a -> {
            assertThat(a.ehData()).isTrue();
            assertThat(a.dia()).isEqualTo("12-10");
            assertThat(a.ano()).isNull();
        });
        assertThat(valores("vinte e cinco de dezembro")).containsExactly("data:12-25");
        assertThat(valores("quatro de dezembro")).containsExactly("data:12-04");
        assertThat(valores("Quatro de Dezembro de 2026")).containsExactly("data:12-04", "ano:2026");
        assertThat(valores("sete e oito de dezembro")).as("duas datas").containsExactly("data:12-07", "data:12-08");
        assertThat(valores("trinta e um de janeiro")).containsExactly("data:01-31");
        assertThat(valores("o prazo vai até dez de dezembro, às 18h")).contains("data:12-10").doesNotContain("10");
    }

    @Test
    void numeraisSoSomamEmOrdemDecrescenteDeGrandeza() {
        assertThat(valores("trezentos e doze")).containsExactly("312");
        assertThat(valores("vinte e cinco")).containsExactly("25");
        assertThat(valores("cento e vinte e três")).containsExactly("123");
        assertThat(valores("dois mil e quinhentos")).containsExactly("2500");
        assertThat(valores("mil e duzentos")).containsExactly("1200");
        assertThat(valores("cem mil")).containsExactly("100000");
        assertThat(valores("vinte e cinco mil")).containsExactly("25000");
        assertThat(valores("duzentos e trinta e quatro mil reais")).containsExactly("234000");
    }

    @ParameterizedTest(name = "\"{0}\" são {1}, e não a soma")
    @CsvSource(delimiter = '|', value = {"três e quatro | 3,4", "dez e trinta | 10,30", "dezessete e dezoito | 17,18", "vinte e trinta | 20,30",
            "duzentos e trezentos | 200,300", "cinco e sete | 5,7", "vinte e dois e trinta | 22,30", "trinta e cinco e dez | 35,10"})
    void numeraisForaDeOrdemSaoNumerosSeparados(String texto, String esperados) {
        assertThat(valores(texto, true)).containsExactly(esperados.split(","));
        assertThat(valores(texto)).containsExactly(esperados.split(","));
    }

    @Test
    void numaFonteTodoNumeralPorExtensoContaMenosUmEUmaQueSaoArtigo() {
        assertThat(valores("duas rodadas", true)).containsExactly("2");
        assertThat(valores("os dois manuais", true)).containsExactly("2");
        assertThat(valores("um dia", true)).as("artigo: nunca é a origem de 1").isEmpty();
        assertThat(valores("uma credencial por pessoa", true)).isEmpty();
        assertThat(aceita(new Origens("um dia"), "1 dia")).isFalse();
        assertThat(aceita(new Origens("uma credencial"), "R$ 1,00")).isFalse();
    }

    // ---------------------------------------------------------------------
    // As origens: o que um conjunto de textos permite afirmar
    // ---------------------------------------------------------------------

    private static boolean aceita(Origens origens, String texto) {
        return NormalizadorDeNumeros.extrair(texto, false).stream().allMatch(origens::aceita);
    }

    @Test
    void dataComAnoSoValeNoAnoCertoQuandoAFonteDizOAno() {
        Origens fonte = new Origens("prazo de 04/12/2026 para informar");

        assertThat(aceita(fonte, "04/12/2026")).isTrue();
        assertThat(aceita(fonte, "4 de dezembro de 2026")).isTrue();
        assertThat(aceita(fonte, "2026-12-04")).isTrue();
        assertThat(aceita(fonte, "4/12")).isTrue();
        assertThat(aceita(fonte, "04/12/2027")).as("o ano errado").isFalse();
        assertThat(aceita(fonte, "14/12/2026")).as("o dia errado").isFalse();
        assertThat(aceita(fonte, "04/11/2026")).as("o mês errado").isFalse();
        assertThat(aceita(fonte, "27/11/2026")).as("a data da v1").isFalse();
    }

    @Test
    void dataSemAnoNaFonteValeComQualquerAnoNaResposta() {
        Origens fonte = new Origens("até 04/12");

        assertThat(aceita(fonte, "04/12/2026")).isTrue();
        assertThat(aceita(fonte, "04/12/2031")).isTrue();
    }

    @Test
    void oAnoDeUmaDataDaFonteValeComoAnoSoltoNaResposta() {
        assertThat(aceita(new Origens("até 04/12/2026"), "em 2026")).isTrue();
        assertThat(aceita(new Origens("até 04/12/2026"), "em 2027")).isFalse();
    }

    @Test
    void numeroDaFonteEmOutroFormatoTemOrigem() {
        Origens fonte = new Origens("A tarifa é de R$ 312,00 por kVA, cobrada em 10.608 reais no total, às 18h.");

        assertThat(aceita(fonte, "312")).isTrue();
        assertThat(aceita(fonte, "R$ 312")).isTrue();
        assertThat(aceita(fonte, "10608")).isTrue();
        assertThat(aceita(fonte, "R$ 10.608,00")).isTrue();
        assertThat(aceita(fonte, "18:00")).isTrue();
        assertThat(aceita(fonte, "18 horas")).isTrue();
        assertThat(aceita(fonte, "321")).isFalse();
        assertThat(aceita(fonte, "31")).as("pedaço de número não é o número").isFalse();
        assertThat(aceita(fonte, "1.060")).isFalse();
    }

    @Test
    void ahoraSoValeComOMesmoMinuto() {
        Origens fonte = new Origens("das 15h às 23h30");

        assertThat(aceita(fonte, "15h")).isTrue();
        assertThat(aceita(fonte, "23:30")).isTrue();
        assertThat(aceita(fonte, "23h")).isFalse();
        assertThat(aceita(fonte, "15h30")).isFalse();
    }

    @Test
    void hojeVaiComoDataSemFonte() {
        Origens hoje = new Origens().adicionarData(LocalDate.of(2026, 11, 1));

        assertThat(aceita(hoje, "01/11/2026")).isTrue();
        assertThat(aceita(hoje, "1º de novembro de 2026")).isTrue();
        assertThat(aceita(hoje, "2026-11-01")).isTrue();
        assertThat(aceita(hoje, "02/11/2026")).isFalse();
        assertThat(aceita(hoje, "01/11/2025")).isFalse();
    }

    // ---------------------------------------------------------------------
    // Apoio do texto
    // ---------------------------------------------------------------------

    @Test
    void aNumeracaoDeListaNoComecoDaLinhaNaoContaComoNumero() {
        assertThat(valores(NormalizadorDeNumeros.semNumeracaoDeLista("1. Primeiro item\n2) Segundo\n  3. Terceiro"))).isEmpty();
        assertThat(valores(NormalizadorDeNumeros.semNumeracaoDeLista("**1.** Primeiro item"))).isEmpty();
        assertThat(valores(NormalizadorDeNumeros.semNumeracaoDeLista("Item 1. Primeiro"))).as("só no começo da linha").containsExactly("1");
        assertThat(valores(NormalizadorDeNumeros.semNumeracaoDeLista("1. São 17 aplicações"))).containsExactly("17");
        assertThat(valores(NormalizadorDeNumeros.semNumeracaoDeLista("2027. Um ano"))).as("quatro dígitos não é lista").containsExactly("2027", "ano:2027");
    }

    @ParameterizedTest(name = "\"{0}\" é numeração de lista")
    @ValueSource(strings = {"1. Primeiro", "1) Primeiro", "**1.** Primeiro", "### 1. Primeiro", "1 - Primeiro", "  1. Primeiro", "## 1) Primeiro"})
    void osFormatosDeNumeracaoDeLista(String linha) {
        assertThat(valores(NormalizadorDeNumeros.semNumeracaoDeLista(linha))).isEmpty();
        assertThat(NormalizadorDeNumeros.semNumeracaoDeLista(linha).strip()).isEqualTo("Primeiro");
    }

    @ParameterizedTest(name = "\"{0}\" é um número, e não numeração de lista")
    @ValueSource(strings = {"312. É o valor da tarifa", "99) kVA é o limite", "**500.** reais é a multa", "2. Sem o primeiro item", "10 - dez",
            "0. Zero"})
    void numeroNoComecoDaLinhaForaDeSequenciaContaComoNumero(String linha) {
        assertThat(NormalizadorDeNumeros.semNumeracaoDeLista(linha)).isEqualTo(linha);
        assertThat(valores(NormalizadorDeNumeros.semNumeracaoDeLista(linha))).isNotEmpty();
    }

    @Test
    void aListaSoValeEmSequenciaEOUmReiniciaAConta() {
        assertThat(NormalizadorDeNumeros.semNumeracaoDeLista("1. a\n2. b\n3. c")).isEqualTo("a\nb\nc");
        assertThat(NormalizadorDeNumeros.semNumeracaoDeLista("1. a\n2. b\n1. c\n2. d")).as("o 1 reinicia").isEqualTo("a\nb\nc\nd");
        assertThat(NormalizadorDeNumeros.semNumeracaoDeLista("1. a\n3. b")).as("pulou o 2").isEqualTo("a\n3. b");
        assertThat(NormalizadorDeNumeros.semNumeracaoDeLista("1. a\n2. b\n2. c")).as("repetiu o 2").isEqualTo("a\nb\n2. c");
        assertThat(NormalizadorDeNumeros.semNumeracaoDeLista("1. a\n### 2. b\n3 - c")).as("formatos misturados em sequência")
                .isEqualTo("a\nb\nc");
        assertThat(NormalizadorDeNumeros.semNumeracaoDeLista("5. a\n1. b\n2. c")).isEqualTo("5. a\nb\nc");
    }

    @Test
    void asPalavrasEmVoltaDoNumeroSobram() {
        assertThat(NormalizadorDeNumeros.palavrasFora("Portão 5").strip()).isEqualTo("Portão");
        assertThat(NormalizadorDeNumeros.semAcento("  Não Encontrei  ISSO ")).isEqualTo("nao encontrei isso");
    }

    @Test
    void textoNuloOuVazioNaoTemNumero() {
        assertThat(NormalizadorDeNumeros.extrair(null, false)).isEmpty();
        assertThat(NormalizadorDeNumeros.extrair("", false)).isEmpty();
        assertThat(NormalizadorDeNumeros.extrair("   ", true)).isEmpty();
        assertThat(valores("sem número nenhum aqui")).isEmpty();
    }
}
