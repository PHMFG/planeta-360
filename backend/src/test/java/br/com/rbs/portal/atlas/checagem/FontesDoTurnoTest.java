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

    @Test
    void desfazerVoltaAsFontesAsNumeracoesEOsNumerosLivresMasNaoAsTentativasDeBusca() {
        FontesDoTurno turno = turno();
        turno.contarBusca();
        turno.registrarTrecho(10, 1, "Manual", 2, 3, "S", "O prazo é 04/12/2026.");
        turno.concluirBusca(false);
        turno.adicionarNumeroLivre("2 + 2 = 4");
        FontesDoTurno.Marca antes = turno.marcar();

        // uma ferramenta que registra e depois falha na gravação
        turno.contarBusca();
        FontesDoTurno.Fonte fantasma = turno.registrarTrecho(11, 1, "Manual", 2, 4, "S", "A multa é R$ 999,00.");
        turno.concluirBusca(false);
        turno.adicionarNumeroLivre("3 + 3 = 6");
        assertThat(fantasma.marcador()).isEqualTo("T2");
        assertThat(turno.existe("T2")).isTrue();

        turno.desfazer(antes);

        assertThat(turno.existe("T2")).as("o modelo nunca recebeu o T2").isFalse();
        assertThat(turno.existe("T1")).isTrue();
        assertThat(turno.numerosLivres()).containsExactly("2 + 2 = 4");
        assertThat(turno.buscas()).as("a busca que falhou continua contando no limite de três").isEqualTo(2);
        // a numeração volta junto: o próximo trecho que o modelo receber é o T2 de verdade, e o mesmo trecho não ganha dois marcadores
        assertThat(turno.registrarTrecho(12, 1, "Manual", 2, 5, "S", "Outro trecho.").marcador()).isEqualTo("T2");
        assertThat(turno.registrarTrecho(10, 1, "Manual", 2, 3, "S", "O prazo é 04/12/2026.").marcador()).isEqualTo("T1");
    }

    @Test
    void desfazerTambemVoltaOQueABuscaTrouxe() {
        FontesDoTurno turno = turno();
        turno.contarBusca();
        FontesDoTurno.Marca antes = turno.marcar();
        turno.registrarTrecho(10, 1, "Manual", 2, 3, "S", "Texto.");
        turno.concluirBusca(false);
        assertThat(turno.nenhumaBuscaTrouxeTrecho()).isFalse();

        turno.desfazer(antes);

        assertThat(turno.nenhumaBuscaTrouxeTrecho()).as("o resultado dessa busca não chegou ao modelo").isTrue();
        assertThat(turno.fontes()).isEmpty();
    }

    @Test
    void todasAsBuscasFalharamSoQuandoNenhumaChegouAUmResultadoNemQueVazio() {
        FontesDoTurno turno = turno();
        assertThat(turno.todasAsBuscasFalharam()).as("sem buscar").isFalse();

        turno.contarBusca();   // caiu
        assertThat(turno.todasAsBuscasFalharam()).isTrue();
        turno.contarBusca();   // caiu
        assertThat(turno.todasAsBuscasFalharam()).as("duas caíram").isTrue();

        turno.contarBusca();
        turno.concluirBusca(true);   // voltou vazia
        assertThat(turno.todasAsBuscasFalharam()).as("uma voltou vazia: é o L2, e não falha").isFalse();
        assertThat(turno.nenhumaBuscaTrouxeTrecho()).isTrue();
    }

    @Test
    void umaBuscaVaziaMaisUmaQueCaiuNaoEFalhaTotal() {
        FontesDoTurno turno = turno();
        turno.contarBusca();
        turno.concluirBusca(true);
        turno.contarBusca();   // caiu

        assertThat(turno.todasAsBuscasFalharam()).isFalse();
        assertThat(turno.nenhumaBuscaTrouxeTrecho()).isTrue();
    }

    @Test
    void desfazerTambemVoltaAContagemDasBuscasQueChegaramAoResultado() {
        FontesDoTurno turno = turno();
        turno.contarBusca();
        FontesDoTurno.Marca antes = turno.marcar();
        turno.registrarTrecho(10, 1, "Manual", 2, 3, "S", "Texto.");
        turno.concluirBusca(false);
        assertThat(turno.todasAsBuscasFalharam()).isFalse();

        turno.desfazer(antes);

        assertThat(turno.todasAsBuscasFalharam()).as("o resultado dessa busca não chegou ao modelo: para ele, a busca falhou").isTrue();
    }
}
