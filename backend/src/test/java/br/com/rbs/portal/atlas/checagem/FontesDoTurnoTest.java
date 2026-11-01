package br.com.rbs.portal.atlas.checagem;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * [ATLAS] [VALIDACAO] O que o turno sabe das buscas (M4): "buscou e nenhuma busca trouxe trecho" é o que decide se uma resposta sem
 * citação e sem dado da parceria não tem de onde ter saído (o L2). A busca que caiu conta como busca que não trouxe trecho. Unitário.
 */
class FontesDoTurnoTest {

    FontesDoTurno turno() {
        return new FontesDoTurno("Pergunta.", 2027, LocalDate.of(2026, 11, 1));
    }

    @Test
    void semBuscarNaoHaOQueApoiar() {
        assertThat(turno().nenhumaBuscaTrouxeTrecho()).isFalse();
    }

    @Test
    void buscaQueVoltouVaziaSemTrechoNenhum() {
        FontesDoTurno turno = turno();
        turno.contarBusca();
        turno.concluirBusca(true);

        assertThat(turno.nenhumaBuscaTrouxeTrecho()).isTrue();
    }

    @Test
    void buscaQueCaiuAntesDeConcluirContaComoBuscaSemTrecho() {
        FontesDoTurno turno = turno();
        turno.contarBusca();   // a ferramenta falhou e nunca chegou a concluir

        assertThat(turno.nenhumaBuscaTrouxeTrecho()).isTrue();
        assertThat(turno.buscas()).isEqualTo(1);
    }

    @Test
    void todasAsBuscasCairamOuVoltaramVazias() {
        FontesDoTurno turno = turno();
        turno.contarBusca();
        turno.contarBusca();
        turno.concluirBusca(true);
        turno.contarBusca();

        assertThat(turno.nenhumaBuscaTrouxeTrecho()).isTrue();
    }

    @Test
    void umaBuscaComTrechoBastaMesmoQueAsOutrasTenhamCaidoOuVindoVazias() {
        FontesDoTurno turno = turno();
        turno.contarBusca();   // caiu
        turno.contarBusca();
        turno.concluirBusca(false);
        turno.contarBusca();
        turno.concluirBusca(true);

        assertThat(turno.nenhumaBuscaTrouxeTrecho()).isFalse();
    }
}
