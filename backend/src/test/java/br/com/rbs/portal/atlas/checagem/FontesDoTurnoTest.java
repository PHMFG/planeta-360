package br.com.rbs.portal.atlas.checagem;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

    // ---------------------------------------------------------------------
    // M5: registros do portal [R#] e contas [C#]
    // ---------------------------------------------------------------------

    @Test
    void oMesmoRegistroDoPortalMantemOMarcador() {
        FontesDoTurno turno = turno();

        FontesDoTurno.Fonte primeiro = turno.registrarRegistro("entrega", 7, "Cortesias", "Cortesias › 12", "Entregas de cortesias", Map.of("unidade", "INSERCAO"));
        FontesDoTurno.Fonte outro = turno.registrarRegistro("entrega", 8, "Cortesias", "Cortesias › 13", "Outra entrega", Map.of());
        FontesDoTurno.Fonte repetido = turno.registrarRegistro("entrega", 7, "Cortesias", "Cortesias › 12", "Entregas de cortesias", Map.of());

        assertThat(primeiro.marcador()).isEqualTo("R1");
        assertThat(primeiro.tipo()).isEqualTo(FontesDoTurno.Tipo.REGISTRO);
        assertThat(primeiro.registroEntidade()).isEqualTo("entrega");
        assertThat(primeiro.registroId()).isEqualTo(7L);
        assertThat(primeiro.dados()).containsEntry("unidade", "INSERCAO");
        assertThat(outro.marcador()).isEqualTo("R2");
        assertThat(repetido.marcador()).as("o mesmo (entidade, id) mantém o marcador").isEqualTo("R1");
        assertThat(turno.fontes()).hasSize(2);
        // a mesma linha em outra entidade é outro registro
        assertThat(turno.registrarRegistro("prazo", 7, "Prazo", null, "Texto", Map.of()).marcador()).isEqualTo("R3");
    }

    @Test
    void contaEContagemDeDiasDividemOContadorDeC() {
        FontesDoTurno turno = turno();

        FontesDoTurno.Fonte conta = turno.registrarCalculo("tarifa", "tarifa", "tarifa: preco = 312", Map.of("nome", "tarifa"));
        FontesDoTurno.Fonte dias = turno.registrarCalculo(null, "Contagem de dias", "18 dias úteis depois de 03/11/2026", Map.of());
        FontesDoTurno.Fonte outra = turno.registrarCalculo("seguro", "seguro", "seguro: x = 2", Map.of());

        assertThat(List.of(conta.marcador(), dias.marcador(), outra.marcador())).containsExactly("C1", "C2", "C3");
        assertThat(conta.tipo()).isEqualTo(FontesDoTurno.Tipo.CALCULO);
        assertThat(turno.existe("C2")).isTrue();
        assertThat(turno.conta("tarifa")).containsSame(conta);
        assertThat(turno.conta("seguro")).containsSame(outra);
        assertThat(turno.conta("contagem")).isEmpty();
        assertThat(turno.contas()).as("só as contas do calcular, na ordem").containsExactly(conta, outra);
    }

    @Test
    void desfazerVoltaOsRegistrosEAsContasTambem() {
        FontesDoTurno turno = turno();
        turno.registrarRegistro("entrega", 7, "Cortesias", null, "Texto", Map.of());
        turno.registrarCalculo("tarifa", "tarifa", "tarifa", Map.of());
        FontesDoTurno.Marca antes = turno.marcar();

        turno.registrarRegistro("entrega", 9, "Outra", null, "Texto", Map.of());
        turno.registrarCalculo("seguro", "seguro", "seguro", Map.of());
        turno.desfazer(antes);

        assertThat(turno.existe("R2")).isFalse();
        assertThat(turno.existe("C2")).isFalse();
        assertThat(turno.conta("seguro")).as("o nome volta a ficar livre").isEmpty();
        assertThat(turno.registrarRegistro("entrega", 9, "Outra", null, "Texto", Map.of()).marcador()).isEqualTo("R2");
        assertThat(turno.registrarCalculo("seguro", "seguro", "seguro", Map.of()).marcador()).isEqualTo("C2");
    }
}
