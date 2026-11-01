package br.com.rbs.portal.atlas.ferramentas;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.atlas.ferramentas.Feriados.Feriado;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * [ATLAS] Os feriados do {@code contar_dias} (M5, bloqueante do plano): a Páscoa pelo algoritmo de Meeus e a tabela de 2026, 2027 e
 * 2028 escrita à mão, sem usar a classe para montá-la. Nacionais: 01/01, 21/04, 01/05, 07/09, 12/10, 02/11, 15/11, 20/11 (Lei
 * 14.759/2023), 25/12 e a Sexta-feira Santa; estadual: 20/09 (RS). Carnaval e Corpus Christi só com os pontos facultativos ligados.
 * Unitário.
 */
class FeriadosTest {

    private static LocalDate dia(int ano, int mes, int dia) {
        return LocalDate.of(ano, mes, dia);
    }

    @Test
    void aPascoaPeloAlgoritmoDeMeeus() {
        assertThat(Feriados.pascoa(2026)).isEqualTo(dia(2026, 4, 5));
        assertThat(Feriados.pascoa(2027)).isEqualTo(dia(2027, 3, 28));
        assertThat(Feriados.pascoa(2028)).isEqualTo(dia(2028, 4, 16));
        // anos conhecidos de outros séculos, para o algoritmo não ser ajustado só aos três do plano
        assertThat(Feriados.pascoa(2000)).isEqualTo(dia(2000, 4, 23));
        assertThat(Feriados.pascoa(2038)).isEqualTo(dia(2038, 4, 25));
        assertThat(Feriados.pascoa(2100)).isEqualTo(dia(2100, 3, 28));
    }

    @Test
    void osFeriadosDe2026() {
        assertThat(datas(new Feriados(false).doAno(2026))).containsExactly(
                dia(2026, 1, 1), dia(2026, 4, 3), dia(2026, 4, 21), dia(2026, 5, 1), dia(2026, 9, 7), dia(2026, 9, 20),
                dia(2026, 10, 12), dia(2026, 11, 2), dia(2026, 11, 15), dia(2026, 11, 20), dia(2026, 12, 25));
    }

    @Test
    void osFeriadosDe2027() {
        assertThat(datas(new Feriados(false).doAno(2027))).containsExactly(
                dia(2027, 1, 1), dia(2027, 3, 26), dia(2027, 4, 21), dia(2027, 5, 1), dia(2027, 9, 7), dia(2027, 9, 20),
                dia(2027, 10, 12), dia(2027, 11, 2), dia(2027, 11, 15), dia(2027, 11, 20), dia(2027, 12, 25));
    }

    @Test
    void osFeriadosDe2028() {
        assertThat(datas(new Feriados(false).doAno(2028))).containsExactly(
                dia(2028, 1, 1), dia(2028, 4, 14), dia(2028, 4, 21), dia(2028, 5, 1), dia(2028, 9, 7), dia(2028, 9, 20),
                dia(2028, 10, 12), dia(2028, 11, 2), dia(2028, 11, 15), dia(2028, 11, 20), dia(2028, 12, 25));
    }

    @Test
    void carnavalECorpusChristiSoComOsPontosFacultativos() {
        Feriados com = new Feriados(true);
        assertThat(datas(com.doAno(2026))).contains(dia(2026, 2, 16), dia(2026, 2, 17), dia(2026, 6, 4)).hasSize(14);
        assertThat(datas(com.doAno(2027))).contains(dia(2027, 2, 8), dia(2027, 2, 9), dia(2027, 5, 27)).hasSize(14);
        assertThat(datas(com.doAno(2028))).contains(dia(2028, 2, 28), dia(2028, 2, 29), dia(2028, 6, 15)).hasSize(14);

        Feriados sem = new Feriados(false);
        assertThat(sem.em(dia(2026, 2, 16))).isEmpty();
        assertThat(sem.em(dia(2026, 2, 17))).isEmpty();
        assertThat(sem.em(dia(2026, 6, 4))).isEmpty();
    }

    @Test
    void cadaFeriadoTemONomeEBuscaPorData() {
        Feriados feriados = new Feriados(true);

        assertThat(feriados.em(dia(2026, 11, 20))).map(Feriado::nome).contains("Dia da Consciência Negra");
        assertThat(feriados.em(dia(2026, 4, 3))).map(Feriado::nome).contains("Sexta-feira Santa");
        assertThat(feriados.em(dia(2026, 9, 20))).map(Feriado::nome).contains("Revolução Farroupilha (RS)");
        assertThat(feriados.em(dia(2026, 6, 4))).map(Feriado::nome).contains("Corpus Christi");
        assertThat(feriados.em(dia(2026, 2, 16))).map(Feriado::nome).contains("Carnaval (segunda-feira)");
        assertThat(feriados.em(dia(2026, 11, 3))).isEmpty();
    }

    @Test
    void diaUtilNaoEFimDeSemanaNemFeriado() {
        Feriados feriados = new Feriados(false);

        assertThat(feriados.diaUtil(dia(2026, 11, 3))).as("terça comum").isTrue();
        assertThat(feriados.diaUtil(dia(2026, 11, 7))).as("sábado").isFalse();
        assertThat(feriados.diaUtil(dia(2026, 11, 8))).as("domingo").isFalse();
        assertThat(feriados.diaUtil(dia(2026, 11, 20))).as("sexta, feriado").isFalse();
        assertThat(feriados.diaUtil(dia(2026, 2, 16))).as("segunda de carnaval, sem o ponto facultativo").isTrue();
        assertThat(new Feriados(true).diaUtil(dia(2026, 2, 16))).as("com o ponto facultativo").isFalse();
    }

    private static List<LocalDate> datas(List<Feriado> feriados) {
        return feriados.stream().map(Feriado::data).toList();
    }
}
