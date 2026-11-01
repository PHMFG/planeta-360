package br.com.rbs.portal.atlas.checagem;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.atlas.checagem.NormalizadorDeNumeros.Achado;
import br.com.rbs.portal.atlas.checagem.NormalizadorDeNumeros.Origens;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    @ParameterizedTest(name = "\"{0}\" é um número só: {1}")
    @CsvSource(delimiter = '|', value = {"R$ 2 220,00 | 2220", "2 220 | 2220", "1 234 567 | 1234567", "12 345 | 12345", "2 220,50 | 2220.5", "20 200 | 20200",
            "R$ 1 000 000,00 | 1000000"})
    void gruposDeTresDigitosSeparadosPorEspacoSaoUmNumeroSoNaResposta(String texto, String esperado) {
        assertThat(valores(texto)).containsExactly(esperado);
    }

    @Test
    void gruposSeparadosPorEspacoFinoOuInsecavelTambemSaoUmNumeroSo() {
        assertThat(valores("R$ 2\u00A0220,00")).containsExactly("2220");
        assertThat(valores("R$ 2\u202F220,00")).containsExactly("2220");
    }

    @Test
    void numaFonteValeOInteiroEAsPartesPorqueTabelaTemColunas() {
        assertThat(valores("2 220", true)).containsExactly("2220", "2", "220");
        assertThat(valores("R$ 1 234 567,50", true)).containsExactly("1234567.5", "1", "234", "567.5");
        Origens tabela = new Origens("Gerador 2 220,00");
        assertThat(aceita(tabela, "R$ 2 220,00")).isTrue();
        assertThat(aceita(tabela, "2")).isTrue();
        assertThat(aceita(tabela, "R$ 220,00")).isTrue();
        assertThat(aceita(tabela, "R$ 22 200")).isFalse();
    }

    @ParameterizedTest(name = "\"{0}\" não é um número só")
    @ValueSource(strings = {"220 2", "2 22", "12 3456", "2 geradores 220", "R$ 220,00 e 2 geradores", "1234 567", "2,5 220", "19 17", "5 12 30"})
    void numerosQueSoEstaoPertoNaoViramUmSo(String texto) {
        assertThat(valores(texto).size()).as(texto).isGreaterThanOrEqualTo(2);
        assertThat(valores(texto)).doesNotContain(texto.replaceAll("[^0-9]", ""));
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
        assertThat(valores("em 2027")).containsExactly("2027", "ano:2027");
        assertThat(valores("em 2027, a edição")).containsExactly("2027", "ano:2027");
    }

    @ParameterizedTest(name = "\"{0} 2027\" é um ano")
    @ValueSource(strings = {"a edição", "Edição", "as edições", "edicao", "o ano", "Ano:", "Edição:", "em", "Em", "de", "a edição de", "desde", "até",
            "ate", "Planeta Atlântida", "planeta atlantida", "(em", "“em"})
    void aPalavraAntesDeQuatroDigitosDizSeEUmAno(String antes) {
        assertThat(valores(antes + " 2027")).containsExactly("2027", "ano:2027");
    }

    @ParameterizedTest(name = "\"{0}\" é um ano, qualquer que seja a palavra de antes")
    @ValueSource(strings = {"2027", "São 2027 geradores", "A tarifa é 2027", "valor 2027", "o 2027", "Planeta 2027", "para 2027", "até2027",
            "mais de.2027", "Tarifa: 2027", "são 2027.", "(2027)", "Para 2027, o prazo", "Consulte o Manual de Operações 2027."})
    void quatroDigitosDe1900A2099SemRealNemUnidadeSaoAnoQualquerQueSejaAPalavraDeAntes(String texto) {
        assertThat(valores(texto)).containsExactly("2027", "ano:2027");
    }

    @Test
    void anosSeguidosNumaListaSaoCadaUmUmAno() {
        assertThat(valores("Nas edições 2026 e 2027")).containsExactly("2026", "ano:2026", "2027", "ano:2027");
    }

    @ParameterizedTest(name = "\"{0}\" é o limite da faixa de anos: {1}")
    @CsvSource(delimiter = '|', value = {"1900 | true", "1950 | true", "2099 | true", "1899 | false", "2100 | false", "1850 | false", "1250 | false",
            "1250 pessoas | false", "0999 | false"})
    void soDe1900A2099ESemUnidadeEAno(String texto, boolean ehAno) {
        assertThat(valores(texto).stream().anyMatch(v -> v.startsWith("ano:"))).isEqualTo(ehAno);
    }

    @Test
    void quatroDigitosComRealAntesOuUnidadeDepoisSaoValorEnuncaAno() {
        assertThat(valores("R$ 2027")).containsExactly("2027");
        assertThat(valores("R$2026")).containsExactly("2026");
        assertThat(valores("2026 credenciais")).containsExactly("2026");
        assertThat(valores("2026 reais")).containsExactly("2026");
        assertThat(valores("2027 por cento")).containsExactly("2027");
        assertThat(valores("2027%")).containsExactly("2027");
        assertThat(valores("2.027")).as("com separador não é ano").containsExactly("2027");
        assertThat(valores("R$ 2.027,00")).containsExactly("2027");
        assertThat(valores("a edição 2027 acontece em breve")).containsExactly("2027", "ano:2027");
        assertThat(valores("em R$ 2027")).as("o R$ vem antes do número, não a palavra de ano").containsExactly("2027");
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

    @ParameterizedTest(name = "\"{0}\" vale {1}")
    @CsvSource(delimiter = '|', value = {"zero geradores | 0", "zero | 0", "Zero | 0", "zero reais | 0", "nota zero | 0"})
    void zeroPorExtensoTambemEUmNumero(String texto, String esperado) {
        assertThat(valores(texto)).containsExactly(esperado);
        assertThat(aceita(new Origens("0 geradores"), texto)).isTrue();
        assertThat(aceita(new Origens(texto), "0")).as("a fonte com zero por extenso é a origem do 0").isTrue();
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
    void dataSemAnoNaFonteSoLiberaOsAnosAceitosQuandoHaLista() {
        Origens fonte = new Origens("até 04/12").anosAceitos(Set.of("2026", "2027"));

        assertThat(aceita(fonte, "04/12/2026")).isTrue();
        assertThat(aceita(fonte, "04/12/2027")).isTrue();
        assertThat(aceita(fonte, "04/12/2031")).as("ano que ninguém disse").isFalse();
        assertThat(aceita(fonte, "04/12/2099")).isFalse();
        assertThat(aceita(fonte, "04/12/2025")).isFalse();
        assertThat(aceita(fonte, "4/12")).isTrue();
        assertThat(aceita(new Origens("até 04/12/2026").anosAceitos(Set.of("2026", "2027")), "04/12/2027")).as("a fonte disse 2026").isFalse();
    }

    @Test
    void oAnoDeUmaDataDaFonteValeComoAnoSoltoNaResposta() {
        assertThat(aceita(new Origens("até 04/12/2026"), "em 2026")).isTrue();
        assertThat(aceita(new Origens("até 04/12/2026"), "em 2027")).isFalse();
    }

    @Test
    void oAnoLivreSoValeComoAnoNuncaComoValor() {
        Origens livre = new Origens().adicionarAnoLivre(2027).adicionarAnoLivre(2026);

        assertThat(aceita(livre, "a edição 2027")).isTrue();
        assertThat(aceita(livre, "em 2026")).isTrue();
        assertThat(aceita(livre, "Planeta Atlântida 2027")).isTrue();
        assertThat(aceita(livre, "São 2027 geradores")).as("a palavra de antes não conta mais: é um ano, e é artificial").isTrue();
        assertThat(aceita(livre, "Para 2027, o prazo")).isTrue();
        assertThat(aceita(livre, "(2027)")).isTrue();
        assertThat(aceita(livre, "Para 2028, o prazo")).isFalse();
        assertThat(aceita(livre, "2026")).as("sozinho, 2026 é um ano").isTrue();
        assertThat(aceita(livre, "2025")).isFalse();
        assertThat(aceita(livre, "em 2028")).isFalse();
        assertThat(aceita(livre, "R$ 2.027,00")).isFalse();
        assertThat(aceita(livre, "R$ 2027")).isFalse();
        assertThat(aceita(livre, "2027 credenciais")).isFalse();
        assertThat(aceita(livre, "2026 reais")).isFalse();
        assertThat(aceita(livre, "2.026")).isFalse();
    }

    @Test
    void aDataDeHojeNaoLiberaOAnoComoValor() {
        Origens hoje = new Origens().adicionarData(LocalDate.of(2026, 11, 1));

        assertThat(aceita(hoje, "01/11/2026")).isTrue();
        assertThat(aceita(hoje, "R$ 2026")).isFalse();
        assertThat(aceita(hoje, "2026 credenciais")).isFalse();
        assertThat(aceita(hoje, "em 2026")).as("o ano de hoje vem do ano livre, e não da data").isFalse();
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
    // M4, terceira volta: o ano não é valor, a hora cheia é o número da hora, a data só vale com dia e mês possíveis
    // ---------------------------------------------------------------------

    @Test
    void oAnoDeUmaDataEOAnoDeUmTituloSoValemComoAnoNuncaComoValor() {
        Origens data = new Origens("prazo até 04/12/2026");
        assertThat(aceita(data, "em 2026")).isTrue();
        assertThat(aceita(data, "2026")).as("sozinho, 2026 é um ano").isTrue();
        assertThat(aceita(data, "R$ 2.026,00")).isFalse();
        assertThat(aceita(data, "2026 credenciais")).isFalse();

        Origens titulo = new Origens("Manual de Operações 2027");
        assertThat(aceita(titulo, "em 2027")).isTrue();
        assertThat(aceita(titulo, "R$ 2.027,00")).as("quatro dígitos de 1900 a 2099 numa fonte são ano").isFalse();
        assertThat(aceita(titulo, "2027 credenciais")).isFalse();
        assertThat(aceita(new Origens("Cabem 1250 pessoas"), "R$ 1.250,00")).as("com unidade é número").isTrue();
        assertThat(aceita(new Origens("Capacidade 1850"), "1.850")).as("fora da faixa é número").isTrue();
        assertThat(aceita(new Origens("Edição de 2027: R$ 2.027,00"), "R$ 2.027,00")).as("com separador é número").isTrue();
    }

    @ParameterizedTest(name = "\"{0}\" é a hora cheia de {1}h")
    @CsvSource(delimiter = '|', value = {"18h | 18", "18hs | 18", "18 h | 18", "18 hs | 18", "18h00 | 18", "18h00min | 18", "18:00 | 18", "18:00h | 18",
            "18 horas | 18", "5h | 5", "05h | 5", "05:00h | 5", "5 hora | 5", "24h | 24"})
    void horaSemMinutosEAHoraEOnumeroDaHora(String texto, int hora) {
        assertThat(valores(texto)).containsExactly("hora:" + (hora < 10 ? "0" + hora : hora) + ":00", String.valueOf(hora));
    }

    @ParameterizedTest(name = "\"{0}\" é {1}")
    @CsvSource(delimiter = '|', value = {"18h30 | hora:18:30", "18h30min | hora:18:30", "18hs30 | hora:18:30", "6:30h | hora:06:30", "6h30 | hora:06:30"})
    void horaComMinutosEUmaHoraSo(String texto, String esperado) {
        assertThat(valores(texto)).containsExactly(esperado);
    }

    @ParameterizedTest(name = "\"{0}\" é o número {1}, e não uma hora")
    @CsvSource(delimiter = '|', value = {"48h | 48", "48 horas | 48", "48hs | 48", "100 horas | 100", "25h | 25"})
    void maisDe24HorasNaoEHoraDoDiaESoUmNumero(String texto, String esperado) {
        assertThat(valores(texto)).containsExactly(esperado);
    }

    @Test
    void horasEmFormatosDiferentesSaoOMesmoLadoAladoNaFonteENaResposta() {
        Origens fonte = new Origens("A montagem vai das 05h às 08h, e a mostra dura 48h.");
        assertThat(aceita(fonte, "das 5 às 8h")).isTrue();
        assertThat(aceita(fonte, "das 05:00h às 08:00h")).isTrue();
        assertThat(aceita(fonte, "48 horas")).isTrue();
        assertThat(aceita(fonte, "das 6 às 8h")).isFalse();
        assertThat(aceita(new Origens("das 5 às 8 horas"), "das 05h às 08h")).isTrue();
        assertThat(aceita(new Origens("às 18:00h"), "18hs")).isTrue();
        assertThat(aceita(new Origens("às 18:00h"), "18h30")).isFalse();
    }

    @Test
    void horaQueNaoEDoDiaOuComMinutoImpossivelSaoDoisNumeros() {
        assertThat(valores("99:99")).containsExactly("99", "99");
        assertThat(valores("25:00")).containsExactly("25", "0");
        assertThat(valores("12:75")).containsExactly("12", "75");
    }

    @ParameterizedTest(name = "\"{0}\" não é uma data: são os números {1}")
    @CsvSource(delimiter = '|', value = {"40/20 | 40,20", "10/15 kVA | 10,15", "32/01 | 32,1", "0/5 | 0,5", "99/99/26 | 99,99,26"})
    void barraComDiaOuMesImpossivelNaoEDataEViraDoisNumeros(String texto, String esperados) {
        assertThat(valores(texto)).containsExactly(esperados.split(","));
    }

    @Test
    void dataComAnoComDiaOuMesImpossivelNaoEDataMasOAnoContinuaSendoAno() {
        assertThat(valores("31/13/2026")).containsExactly("31", "13", "2026", "ano:2026");
        assertThat(valores("2026-13-45")).containsExactly("2026", "ano:2026", "13", "45");
        assertThat(valores("31/12/2026")).containsExactly("data:12-31", "ano:2026");
    }

    @Test
    void numerosDeUmaTabelaComBarraCasamComOsNumerosDaResposta() {
        Origens fonte = new Origens("Master 40/20 cortesias; gerador de 10/15 kVA");
        assertThat(aceita(fonte, "40 cortesias")).isTrue();
        assertThat(aceita(fonte, "15 kVA")).isTrue();
        assertThat(aceita(fonte, "20 cortesias")).isTrue();
        assertThat(aceita(fonte, "41 cortesias")).isFalse();
    }

    @ParameterizedTest(name = "\"{0}\" é 15 de janeiro de 2027")
    @ValueSource(strings = {"15/01/2027", "15-01-2027", "2027-01-15", "15 de janeiro de 2027", "15/1/2027", "15-1-2027"})
    void dataComTracoEIgualADataComBarra(String texto) {
        assertThat(valores(texto)).containsExactly("data:01-15", "ano:2027");
    }

    @ParameterizedTest(name = "\"{0}\" é 1º de novembro")
    @ValueSource(strings = {"1º de novembro", "1° de novembro", "01/11", "1 de novembro", "primeiro de novembro", "Primeiro de Novembro"})
    void primeiroDeMesEOGrauOrdinalSaoADataDoDiaUm(String texto) {
        assertThat(valores(texto)).containsExactly("data:11-01");
    }

    @Test
    void primeiroSemMesNaoEUmNumero() {
        assertThat(valores("o primeiro lote")).isEmpty();
        assertThat(valores("primeiro de dezembro de 2026")).containsExactly("data:12-01", "ano:2026");
        assertThat(valores("de 1° a 3° de dezembro")).containsExactly("data:12-01", "data:12-03");
    }

    @ParameterizedTest(name = "\"{0}\" vale {1}")
    @CsvSource(delimiter = '|', value = {"2 bilhões | 2000000000", "1,5 bilhão | 1500000000", "dois bilhões | 2000000000", "um bilhão | 1000000000",
            "3 bilhoes | 3000000000", "cem bilhões | 100000000000"})
    void bilhaoEBilhoesEntramNaEscala(String texto, String esperado) {
        assertThat(valores(texto)).contains(esperado);
    }

    @Test
    void planetaTrezentosESessentaEmQualquerCaixaENomeDoFestival() {
        assertThat(valores("Planeta 360")).isEmpty();
        assertThat(valores("PLANETA 360")).isEmpty();
        assertThat(valores("planeta 360 · operações do festival")).isEmpty();
        assertThat(valores("PLANETA  360")).isEmpty();
        assertThat(valores("PLANETA 3600")).as("só o nome inteiro").containsExactly("3600");
    }

    @ParameterizedTest(name = "U+{0} entre 2 e 220 é um espaço comum")
    @ValueSource(strings = {"0020", "00A0", "2007", "2003", "2009", "202F", "2002", "3000", "205F", "1680", "2000", "200A"})
    void todoEspacoDoUnicodeJuntaOsGruposDeMilhar(String codigo) {
        String espaco = new String(Character.toChars(Integer.parseInt(codigo, 16)));
        assertThat(valores("R$ 2" + espaco + "220,00")).containsExactly("2220");
        assertThat(valores("R$ 2" + espaco + "220,00", true)).containsExactly("2220", "2", "220");
    }

    @Test
    void espacosSeguidosETabulacaoViramUmEspacoSo() {
        assertThat(valores("R$ 2  220,00")).containsExactly("2220");
        assertThat(valores("R$ 2\t220,00")).containsExactly("2220");
        assertThat(valores("R$ 2 \t   220,00")).containsExactly("2220");
        assertThat(valores("2\n220")).as("a quebra de linha continua separando").containsExactly("2", "220");
    }

    @Test
    void aFonteEAMensagemDaPessoaPassamPelaMesmaDobraDeAlgarismos() {
        assertThat(valores("36 m²", true)).containsExactly("36", "2");
        assertThat(valores("36 m²")).isEqualTo(valores("36 m2"));
        assertThat(valores("½ kVA", true)).containsExactly("1", "2");
        assertThat(valores("nota¹", true)).containsExactly("1");
        assertThat(valores("٣١٢ reais", true)).containsExactly("312");
        assertThat(valores("３１２ reais", true)).containsExactly("312");
        assertThat(aceita(new Origens("O espaço tem 36 m²"), "36 m²")).isTrue();
        assertThat(aceita(new Origens("O espaço tem 36 m²"), "36 m2")).isTrue();
        assertThat(aceita(new Origens("O espaço tem 36 m²"), "37 m²")).isFalse();
    }

    @Test
    void oTextoEmNfdTemOsMesmosNumeraisDoTextoEmNfc() {
        String nfd = java.text.Normalizer.normalize("três dias", java.text.Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo("três dias");
        assertThat(valores(nfd)).containsExactly("3");
        assertThat(valores(java.text.Normalizer.normalize("4 de março", java.text.Normalizer.Form.NFD))).containsExactly("data:03-04");
    }

    @Test
    void osInvisiveisQueNaoSaoDeFormatoTambemSaemAntesDeTudo() {
        for (String invisivel : new String[] {"͏", "︀", "️", "᠋", "᠌", "᠍", "ᅟ", "ᅠ", "ㅤ", "ﾠ",
                "​", "­"}) {
            assertThat(valores("3" + invisivel + "12")).as("U+" + Integer.toHexString(invisivel.charAt(0))).containsExactly("312");
        }
        assertThat(NormalizadorDeNumeros.semInvisiveis("a͏b️cㅤd")).isEqualTo("abcd");
    }

    // --- a data da resposta que a fonte citada diz em pedaços -------------------------------------------------------

    private static boolean aceitaDaFonte(String fonte, String resposta) {
        return aceita(new Origens().adicionarFonteCitada(fonte), resposta);
    }

    private static final String MANUAL = "O festival acontece na sexta-feira, 29, e no sábado, 30 de janeiro de 2027.";

    @ParameterizedTest(name = "\"{0}\" tem origem no manual")
    @ValueSource(strings = {"29 e 30 de janeiro de 2027", "29/01/2027 e 30/01/2027", "29 de janeiro de 2027", "29 de janeiro", "29/01", "dia 29",
            "29/1/2027", "2027-01-29"})
    void dataDaRespostaValeSeAFonteTemODiaComoNumeroEUmaDataDoMesEDoAno(String resposta) {
        assertThat(aceitaDaFonte(MANUAL, resposta)).isTrue();
    }

    @ParameterizedTest(name = "\"{0}\" não tem origem no manual")
    @ValueSource(strings = {"28 de janeiro de 2027", "29 de fevereiro de 2027", "29 de janeiro de 2028", "29/01/2028", "28/01", "31 de janeiro",
            "29 de dezembro"})
    void dataDaRespostaComDiaMesOuAnoQueAFonteNaoTemEhRecusada(String resposta) {
        assertThat(aceitaDaFonte(MANUAL, resposta)).isFalse();
    }

    @Test
    void oFallbackDaDataSoValeParaFonteCitadaEOQueEstaEscritoContinuaValendoSempre() {
        assertThat(aceita(new Origens(MANUAL), "29 de janeiro de 2027")).as("Origens simples: só o que está escrito").isFalse();
        assertThat(aceita(new Origens(MANUAL), "dia 29")).as("29 está escrito como número").isTrue();
        assertThat(aceita(new Origens("A entrega é em 04/12/2026."), "dia 4")).as("o dia solto só com fonte citada").isFalse();
    }

    @Test
    void oDiaSozinhoCasaComODiaDeUmaDataDaFonteCitada() {
        assertThat(aceitaDaFonte("Informe até 04/12/2026, às 18h.", "dia 4")).isTrue();
        assertThat(aceitaDaFonte("Informe até 04/12/2026, às 18h.", "dia 04")).isTrue();
        assertThat(aceitaDaFonte("Informe até 04/12/2026, às 18h.", "dia 5")).isFalse();
        assertThat(aceitaDaFonte("Informe até 04/12/2026, às 18h.", "dia 12")).as("o mês não é o dia").isFalse();
        assertThat(aceitaDaFonte("Informe até 4 de dezembro.", "o dia 4")).isTrue();
        assertThat(aceitaDaFonte("Informe até 04/12/2026, às 18h.", "40 pessoas")).as("só dia de 1 a 31").isFalse();
    }

    @Test
    void oDiaSoCasaQuandoADiaOuDiasVemLogoAntesDoNumeroNaResposta() {
        String fonte = "Informe até 04/12/2026, às 18h.";

        assertThat(aceitaDaFonte(fonte, "no dia 4")).isTrue();
        assertThat(aceitaDaFonte(fonte, "Dia 4")).isTrue();
        assertThat(aceitaDaFonte(fonte, "nos dias 4")).isTrue();
        assertThat(aceitaDaFonte(fonte, "no dia  4")).isTrue();
        assertThat(aceitaDaFonte(fonte, "são 4 geradores")).as("número comum").isFalse();
        assertThat(aceitaDaFonte(fonte, "custa R$ 4")).as("número comum").isFalse();
        assertThat(aceitaDaFonte(fonte, "4")).isFalse();
        assertThat(aceitaDaFonte(fonte, "no dia 5")).isFalse();
        assertThat(aceitaDaFonte(fonte, "mediadia 4")).as("a palavra tem de ser inteira").isFalse();
        assertThat(aceitaDaFonte(fonte, "dia 4,5")).as("não é um dia inteiro").isFalse();
        assertThat(aceitaDaFonte(fonte, "dias 4 e 5")).as("só o primeiro número depois de dias").isFalse();
        // com o número escrito na fonte, é um número comum e vale
        assertThat(aceitaDaFonte("Informe até 04/12/2026 para 4 geradores.", "são 4 geradores")).isTrue();
    }

    @Test
    void dataSemAnoNaFonteComDiaSoltoSoLiberaOsAnosAceitos() {
        Origens fonte = new Origens().anosAceitos(Set.of("2026", "2027")).adicionarFonteCitada("Sexta, 29, e sábado, 30 de janeiro.");
        assertThat(aceita(fonte, "29 de janeiro de 2027")).isTrue();
        assertThat(aceita(fonte, "30 de janeiro de 2027")).isTrue();
        assertThat(aceita(fonte, "29 de janeiro de 2031")).isFalse();
        assertThat(aceita(fonte, "29 de janeiro")).isTrue();
    }

    @Test
    void aDataDaPessoaComAnoValeComoEstaEscrita() {
        Origens pessoa = new Origens("É 04/12/2027?");
        assertThat(pessoa.temDataExata(NormalizadorDeNumeros.extrair("04/12/2027", false).get(0))).isTrue();
        assertThat(pessoa.temDataExata(NormalizadorDeNumeros.extrair("04/12/2026", false).get(0))).isFalse();
        assertThat(new Origens("É 04/12?").temDataExata(NormalizadorDeNumeros.extrair("04/12/2027", false).get(0))).as("sem ano na pergunta")
                .isFalse();
        assertThat(pessoa.temDataExata(NormalizadorDeNumeros.extrair("04/12", false).get(0))).as("a data sem ano da resposta não é exata").isFalse();
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
        assertThat(valores(NormalizadorDeNumeros.semNumeracaoDeLista("2027. Um ano"))).as("quatro dígitos não é lista: é um ano")
                .containsExactly("2027", "ano:2027");
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

    @Test
    void oLiteralDoCalcularSoValeComoValorNuncaComoAnoDeUmaDataNemPorPedacoDeData() {
        Origens edicao = new Origens("Prazo: 04/12/2026.").adicionarAnoLivre(2027);
        Origens solto = new Origens("Edição: Planeta Atlântida 2027. Prazo: 04/12/2026.");
        Origens valor = new Origens("A multa é de R$ 2.027,00 e fica em 04/12.");

        // a regra da resposta aceita o ano ("em 2027"), e o literal do calcular só o número escrito solto no texto
        assertThat(edicao.aceita(NormalizadorDeNumeros.extrair("2027", false).get(0))).isTrue();
        assertThat(edicao.aceitaComoValor(NormalizadorDeNumeros.extrair("2027", false).get(0))).as("a edição não é texto da fonte").isFalse();
        assertThat(edicao.aceitaComoValor(NormalizadorDeNumeros.extrair("2026", false).get(0))).as("o ano de uma data").isFalse();
        assertThat(solto.aceitaComoValor(NormalizadorDeNumeros.extrair("2027", false).get(0))).as("escrito solto no texto").isTrue();
        assertThat(solto.aceitaComoValor(NormalizadorDeNumeros.extrair("2026", false).get(0))).as("o ano de uma data").isFalse();
        assertThat(valor.aceitaComoValor(NormalizadorDeNumeros.extrair("2027", false).get(0))).isTrue();
        assertThat(valor.aceitaComoValor(NormalizadorDeNumeros.extrair("2.027,00", false).get(0))).isTrue();
        // a regra da resposta não muda: "R$ 2.027,00" e "R$ 2027" não valem só porque o texto diz "Para 2027"
        assertThat(new Origens("Para 2027.").aceita(NormalizadorDeNumeros.extrair("R$ 2.027,00", false).get(0))).isFalse();
        assertThat(new Origens("Para 2027.").aceita(NormalizadorDeNumeros.extrair("R$ 2027", false).get(0))).isFalse();
        // data nunca é valor, e o dia solto depois de "dia" também não
        assertThat(valor.aceitaComoValor(NormalizadorDeNumeros.extrair("04/12", false).get(0))).isFalse();
        assertThat(new Origens("Vence 04/12/2026.").adicionarFonteCitada("Vence no dia 4.").aceitaComoValor(NormalizadorDeNumeros.extrair("4", false).get(0)))
                .as("o 4 está na fonte como número").isTrue();
        assertThat(new Origens("Vence 04/12/2026.").aceitaComoValor(NormalizadorDeNumeros.extrair("4", false).get(0))).isFalse();
    }
}
