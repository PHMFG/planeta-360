package br.com.rbs.portal.atlas.ferramentas;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Os feriados do {@code contar_dias} (plano do Atlas completo, M5). [ATLAS] Nacionais: 01/01, 21/04, 01/05, 07/09, 12/10, 02/11,
 * 15/11, 20/11 (Lei 14.759/2023), 25/12 e a Sexta-feira Santa (Páscoa menos 2 dias); estadual: 20/09, a Revolução Farroupilha (RS).
 * Carnaval (Páscoa menos 48 e 47) e Corpus Christi (Páscoa mais 60) são pontos facultativos, e só contam com
 * {@code atlas.feriados.pontos-facultativos=true}. O 20/11 vale em todos os anos aceitos (2000 a 2100): a ferramenta é para as
 * edições do festival, e o plano não distingue o ano da lei. A Páscoa é a do calendário gregoriano, pelo algoritmo de Meeus.
 */
@Component
public class Feriados {

    /** Um feriado: o dia e o nome que a resposta pode citar. */
    public record Feriado(LocalDate data, String nome) {
    }

    private final boolean pontosFacultativos;

    public Feriados(@Value("${atlas.feriados.pontos-facultativos:false}") boolean pontosFacultativos) {
        this.pontosFacultativos = pontosFacultativos;
    }

    /** O domingo de Páscoa do ano, pelo algoritmo de Meeus (calendário gregoriano). */
    public static LocalDate pascoa(int ano) {
        int a = ano % 19;
        int b = ano / 100;
        int c = ano % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int mes = (h + l - 7 * m + 114) / 31;
        int dia = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(ano, mes, dia);
    }

    /** Os feriados do ano, do primeiro ao último dia. */
    public List<Feriado> doAno(int ano) {
        LocalDate pascoa = pascoa(ano);
        List<Feriado> feriados = new ArrayList<>(List.of(
                new Feriado(LocalDate.of(ano, 1, 1), "Confraternização Universal"),
                new Feriado(pascoa.minusDays(2), "Sexta-feira Santa"),
                new Feriado(LocalDate.of(ano, 4, 21), "Tiradentes"),
                new Feriado(LocalDate.of(ano, 5, 1), "Dia do Trabalho"),
                new Feriado(LocalDate.of(ano, 9, 7), "Independência do Brasil"),
                new Feriado(LocalDate.of(ano, 9, 20), "Revolução Farroupilha (RS)"),
                new Feriado(LocalDate.of(ano, 10, 12), "Nossa Senhora Aparecida"),
                new Feriado(LocalDate.of(ano, 11, 2), "Finados"),
                new Feriado(LocalDate.of(ano, 11, 15), "Proclamação da República"),
                new Feriado(LocalDate.of(ano, 11, 20), "Dia da Consciência Negra"),
                new Feriado(LocalDate.of(ano, 12, 25), "Natal")));
        if (pontosFacultativos) {
            feriados.add(new Feriado(pascoa.minusDays(48), "Carnaval (segunda-feira)"));
            feriados.add(new Feriado(pascoa.minusDays(47), "Carnaval (terça-feira)"));
            feriados.add(new Feriado(pascoa.plusDays(60), "Corpus Christi"));
        }
        feriados.sort(Comparator.comparing(Feriado::data));
        return feriados;
    }

    /** O feriado desse dia, se for um. */
    public Optional<Feriado> em(LocalDate data) {
        return doAno(data.getYear()).stream().filter(feriado -> feriado.data().equals(data)).findFirst();
    }

    /** Dia útil: de segunda a sexta e fora dos feriados. */
    public boolean diaUtil(LocalDate data) {
        return !fimDeSemana(data) && em(data).isEmpty();
    }

    /** Sábado ou domingo. */
    public static boolean fimDeSemana(LocalDate data) {
        return data.getDayOfWeek() == DayOfWeek.SATURDAY || data.getDayOfWeek() == DayOfWeek.SUNDAY;
    }
}
