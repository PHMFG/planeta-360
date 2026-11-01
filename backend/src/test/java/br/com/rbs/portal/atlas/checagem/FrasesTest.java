package br.com.rbs.portal.atlas.checagem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * [ATLAS] [VALIDACAO] Onde a frase termina, para a regra "o número está na fonte citada na mesma frase" e para o filtro do fluxo
 * (M4). Unitário, sem Spring: ponto seguido de espaço ou quebra de linha; {@code 1.250,00}, {@code 3.2} e {@code p. 6} não cortam; a
 * citação depois do ponto fica com a frase anterior; e o fluxo só dá a frase por completa quando o que vem depois do ponto não
 * pode ser mais uma citação.
 */
class FrasesTest {

    @Test
    void dividePorPontoSeguidoDeEspacoEPorQuebraDeLinha() {
        assertThat(Frases.dividir("Primeira. Segunda! Terceira? Quarta…")).containsExactly("Primeira.", "Segunda!", "Terceira?", "Quarta…");
        assertThat(Frases.dividir("Linha um\nLinha dois\n\nLinha três")).containsExactly("Linha um", "Linha dois", "Linha três");
        assertThat(Frases.dividir("Quanto?! Sim... Talvez.")).containsExactly("Quanto?!", "Sim...", "Talvez.");
    }

    @Test
    void numeroComPontoOuVirgulaNaoCorta() {
        assertThat(Frases.dividir("Custa R$ 1.250,00 por dia. Depois muda.")).containsExactly("Custa R$ 1.250,00 por dia.", "Depois muda.");
        assertThat(Frases.dividir("Veja a seção 3.2 do manual.")).containsExactly("Veja a seção 3.2 do manual.");
        assertThat(Frases.dividir("Vale até 04/12/2026. Fim.")).containsExactly("Vale até 04/12/2026.", "Fim.");
        assertThat(Frases.dividir("O IP é 10.0.0.1 e pronto.")).containsExactly("O IP é 10.0.0.1 e pronto.");
    }

    @Test
    void aCitacaoDepoisDoPontoFicaComAFraseAnterior() {
        assertThat(Frases.dividir("O prazo é 04/12/2026. [T1] Depois disso, não.")).containsExactly("O prazo é 04/12/2026. [T1]", "Depois disso, não.");
        assertThat(Frases.dividir("Vale R$ 312. [T1] [T2] Mais uma.")).containsExactly("Vale R$ 312. [T1] [T2]", "Mais uma.");
        assertThat(Frases.dividir("Vale R$ 312. [T1, T2] Mais uma.")).containsExactly("Vale R$ 312. [T1, T2]", "Mais uma.");
        assertThat(Frases.dividir("Vale R$ 312 [T1]. Mais uma.")).containsExactly("Vale R$ 312 [T1].", "Mais uma.");
        assertThat(Frases.dividir("Vale R$ 312.[T1] Mais uma.")).as("colada ao ponto, sem espaço: o ponto não termina nada")
                .containsExactly("Vale R$ 312.[T1] Mais uma.");
    }

    @Test
    void colchetePosteriorQueNaoEUmaCitacaoComecaOutraFrase() {
        assertThat(Frases.dividir("Vale. [nota] Depois.")).containsExactly("Vale.", "[nota] Depois.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Veja a p. 6 do manual.", "Veja o art. 5 do contrato.", "O Sr. Silva assina.", "Fale com a Dra. Ana hoje.",
            "Ligue para o tel. 5550 e pronto.", "Obs. importante aqui.", "Consulte pp. 6 e 7."})
    void abreviaturaComPontoNaoTerminaAFrase(String frase) {
        assertThat(Frases.dividir(frase)).hasSize(1);
    }

    @Test
    void numeracaoDeListaNoComecoDaLinhaNaoCorta() {
        assertThat(Frases.dividir("Passos:\n1. Primeiro\n2. Segundo")).containsExactly("Passos:", "1. Primeiro", "2. Segundo");
        assertThat(Frases.dividir("**1.** Primeiro. Segundo.")).containsExactly("**1.** Primeiro.", "Segundo.");
        assertThat(Frases.dividir("### 1. Prazo\n2) Horário")).containsExactly("### 1. Prazo", "2) Horário");
    }

    @Test
    void numeroNoComecoDaLinhaForaDeSequenciaNaoEListaEFechaAFrase() {
        assertThat(Frases.dividir("312. É o valor da tarifa em reais.")).containsExactly("312.", "É o valor da tarifa em reais.");
        assertThat(Frases.dividir("1. Primeiro\n5. Quinto")).containsExactly("1. Primeiro", "5.", "Quinto");
    }

    @Test
    void oPontoDentroDeUmMarcadorNaoConta() {
        assertThat(Frases.dividir("Visitantes: {{dado:ativacao.visitantes_total}}. Fim.")).containsExactly("Visitantes: {{dado:ativacao.visitantes_total}}.", "Fim.");
        assertThat(Frases.dividir("Dado {{ref. com ponto}} ok. Outra.")).containsExactly("Dado {{ref. com ponto}} ok.", "Outra.");
    }

    @Test
    void textoSemPontuacaoEUmaFraseSo() {
        assertThat(Frases.dividir("sem pontuação nenhuma")).containsExactly("sem pontuação nenhuma");
        assertThat(Frases.dividir("")).isEmpty();
        assertThat(Frases.dividir("   \n  ")).isEmpty();
    }

    // ---------------------------------------------------------------------
    // O fluxo: a frase só está completa quando nada que chegue depois a muda
    // ---------------------------------------------------------------------

    @Test
    void oFluxoEsperaOQueVemDepoisDoPonto() {
        assertThat(Frases.fimDaPrimeiraFraseCompleta("Texto sem fim", 0)).isEqualTo(-1);
        assertThat(Frases.fimDaPrimeiraFraseCompleta("Texto.", 0)).as("parou no ponto: pode ser 1.250").isEqualTo(-1);
        assertThat(Frases.fimDaPrimeiraFraseCompleta("Texto. ", 0)).as("parou no espaço: pode vir uma citação").isEqualTo(-1);
        assertThat(Frases.fimDaPrimeiraFraseCompleta("Texto. Outra", 0)).isEqualTo(6);
        assertThat(Frases.fimDaPrimeiraFraseCompleta("Texto.\n", 0)).as("ponto e quebra de linha: já terminou").isEqualTo(6);
        assertThat(Frases.fimDaPrimeiraFraseCompleta("Texto\nOutra", 0)).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Texto. [", "Texto. [T", "Texto. [T1", "Texto. [T1,", "Texto. [T1, T", "Texto. [T1]", "Texto. [T1] ", "Texto. [T1] [",
            "Texto. [T1] [T2"})
    void citacaoQueAindaEstaChegandoSegura(String parcial) {
        assertThat(Frases.fimDaPrimeiraFraseCompleta(parcial, 0)).as(parcial).isEqualTo(-1);
    }

    @Test
    void quandoACitacaoFechaAFraseIncluiEla() {
        assertThat(Frases.fimDaPrimeiraFraseCompleta("Texto. [T1] Outra", 0)).isEqualTo("Texto. [T1]".length());
        assertThat(Frases.fimDaPrimeiraFraseCompleta("Texto. [T1] [T2] Outra", 0)).isEqualTo("Texto. [T1] [T2]".length());
        assertThat(Frases.fimDaPrimeiraFraseCompleta("Texto. [nota] Outra", 0)).as("[nota] não é citação").isEqualTo(6);
    }

    @Test
    void marcadorQueNaoFechouSegura() {
        assertThat(Frases.fimDaPrimeiraFraseCompleta("Valor {{dado:x. Fim. Outra", 0)).isEqualTo(-1);
    }

    @Test
    void asCitacoesSaemNaOrdemEComAsRepetidas() {
        assertThat(Frases.citacoes("A [T1] e [T2, T3]; de novo [T1] e a conta [C1] e o registro [R2].")).containsExactly("T1", "T2", "T3", "T1", "C1", "R2");
        assertThat(Frases.citacoes("sem citação, só [nota] e [1] e [t1]")).isEmpty();
        assertThat(Frases.semCitacoes("Vale [T1] e [T2, T3] ok").replaceAll("\\s+", " ")).isEqualTo("Vale e ok");
    }

    @Test
    void dividirNaoPerdeNemInventaTexto() {
        String texto = "A tarifa é R$ 312,00 [T1]. O prazo é 04/12/2026. [T2] Veja p. 6.\nSegunda linha sem ponto\n1. Item";
        String juntas = String.join(" ", Frases.dividir(texto));
        assertThat(juntas.replaceAll("\\s+", "")).isEqualTo(texto.replaceAll("\\s+", ""));
        List<String> frases = Frases.dividir(texto);
        assertThat(frases).hasSize(5);
    }
}
