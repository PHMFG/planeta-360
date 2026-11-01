package br.com.rbs.portal.atlas.disjuntor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.atlas.RelogioDeTeste;
import br.com.rbs.portal.atlas.provedor.ProvedorIndisponivelException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * [ATLAS] O disjuntor de um provedor (M8) sem o contexto do Spring: abre com 5 falhas seguidas ou com 50% das 10 últimas, não chama o
 * provedor enquanto está aberto, deixa uma chamada de prova passar depois de 60 s (que fecha ou reabre), e com o interruptor desligado
 * não existe. O tempo é o de um {@link RelogioDeTeste}, que o teste adianta.
 */
class DisjuntorDoProvedorTest {

    private RelogioDeTeste relogio;
    private DisjuntorDoProvedor disjuntor;
    private AtomicInteger chamadas;

    @BeforeEach
    void preparar() {
        relogio = new RelogioDeTeste();
        disjuntor = novo(true);
        chamadas = new AtomicInteger();
    }

    private DisjuntorDoProvedor novo(boolean ligado) {
        return new DisjuntorDoProvedor("llm", ligado, 5, 50f, 10, Duration.ofSeconds(60), relogio);
    }

    private String ok() {
        return disjuntor.executar(() -> {
            chamadas.incrementAndGet();
            return "ok";
        });
    }

    private void falhar() {
        assertThatThrownBy(() -> disjuntor.executar(() -> {
            chamadas.incrementAndGet();
            throw new ProvedorIndisponivelException("caiu");
        })).isInstanceOf(ProvedorIndisponivelException.class).hasMessage("caiu");
    }

    @Test
    void comecaFechadoEPassaAsChamadas() {
        assertThat(disjuntor.estado()).isEqualTo("FECHADO");
        assertThat(ok()).isEqualTo("ok");
        assertThat(disjuntor.bloqueado()).isFalse();
    }

    @Test
    void cincoFalhasSeguidasAbremEAChamadaSeguinteNemSai() {
        for (int i = 0; i < 4; i++) {
            falhar();
        }
        assertThat(disjuntor.estado()).as("quatro falhas ainda não abrem").isEqualTo("FECHADO");
        falhar();
        assertThat(disjuntor.estado()).isEqualTo("ABERTO");
        assertThat(disjuntor.bloqueado()).isTrue();
        assertThat(disjuntor.falta()).isEqualTo(Duration.ofSeconds(60));
        int antes = chamadas.get();

        assertThatThrownBy(this::ok).isInstanceOf(DisjuntorAbertoException.class).isInstanceOf(ProvedorIndisponivelException.class)
                .hasMessageContaining("llm").hasMessageContaining("60 s");
        assertThat(chamadas.get()).as("o provedor não foi chamado com o disjuntor aberto").isEqualTo(antes);
    }

    @Test
    void umaChamadaBoaNoMeioZeraAsFalhasSeguidas() {
        for (int i = 0; i < 4; i++) {
            falhar();
        }
        ok();
        for (int i = 0; i < 4; i++) {
            falhar();
        }
        assertThat(disjuntor.estado()).isEqualTo("FECHADO");
    }

    @Test
    void metadeDasDezUltimasAbreMesmoSemCincoSeguidas() {
        // F S F S F S F S F S: nunca cinco seguidas, mas a décima chamada fecha a janela com 50% de falha
        for (int i = 0; i < 5; i++) {
            falhar();
            if (i < 4) {
                ok();
            }
        }
        assertThat(disjuntor.estado()).as("9 chamadas: a janela ainda não fechou").isEqualTo("FECHADO");
        ok();
        assertThat(disjuntor.estado()).isEqualTo("ABERTO");
    }

    @Test
    void menosDeMetadeNaJanelaNaoAbre() {
        for (int i = 0; i < 4; i++) {
            falhar();
            ok();
            ok();
        }
        assertThat(disjuntor.estado()).isEqualTo("FECHADO");
    }

    @Test
    void depoisDeSessentaSegundosUmaChamadaDeProvaPassaEFechaSeDerCerto() {
        for (int i = 0; i < 5; i++) {
            falhar();
        }
        relogio.avancar(Duration.ofSeconds(60));
        assertThat(disjuntor.estado()).as("no prazo exato ainda não").isEqualTo("ABERTO");
        assertThatThrownBy(this::ok).isInstanceOf(DisjuntorAbertoException.class);

        relogio.avancar(Duration.ofSeconds(1));
        assertThat(disjuntor.estado()).as("passou dos sessenta segundos: a próxima chamada é a de prova").isEqualTo("MEIO_ABERTO");
        assertThat(disjuntor.bloqueado()).isFalse();
        int antes = chamadas.get();
        assertThat(ok()).isEqualTo("ok");

        assertThat(chamadas.get()).isEqualTo(antes + 1);
        assertThat(disjuntor.estado()).isEqualTo("FECHADO");
        // e volta a contar do zero: quatro falhas depois da prova não abrem
        for (int i = 0; i < 4; i++) {
            falhar();
        }
        assertThat(disjuntor.estado()).isEqualTo("FECHADO");
    }

    @Test
    void aChamadaDeProvaQueFalhaReabreOPrazoPorMaisSessentaSegundos() {
        for (int i = 0; i < 5; i++) {
            falhar();
        }
        relogio.avancar(Duration.ofSeconds(61));

        falhar();   // a de prova

        assertThat(disjuntor.estado()).isEqualTo("ABERTO");
        assertThat(disjuntor.falta()).isEqualTo(Duration.ofSeconds(60));
        relogio.avancar(Duration.ofSeconds(30));
        assertThatThrownBy(this::ok).isInstanceOf(DisjuntorAbertoException.class);
        relogio.avancar(Duration.ofSeconds(31));
        assertThat(ok()).isEqualTo("ok");
        assertThat(disjuntor.estado()).isEqualTo("FECHADO");
    }

    @Test
    void soUmaChamadaDeProvaPassaPorVez() {
        for (int i = 0; i < 5; i++) {
            falhar();
        }
        relogio.avancar(Duration.ofSeconds(61));
        AtomicInteger recusadas = new AtomicInteger();

        // a de prova ainda está rodando quando a outra chega
        disjuntor.executar(() -> {
            try {
                disjuntor.executar(() -> "outra");
            } catch (DisjuntorAbertoException aberto) {
                recusadas.incrementAndGet();
                assertThat(aberto.estado()).isEqualTo("MEIO_ABERTO");
            }
            return "prova";
        });

        assertThat(recusadas.get()).isEqualTo(1);
        assertThat(disjuntor.estado()).isEqualTo("FECHADO");
    }

    @Test
    void oQueNaoEFalhaDoProvedorNaoContaEDevolveAPermissao() {
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> disjuntor.executar(() -> {
                throw new IllegalArgumentException("erro nosso");
            }, falha -> !(falha instanceof IllegalArgumentException))).isInstanceOf(IllegalArgumentException.class);
        }
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> disjuntor.executar(() -> {
                throw new AssertionError("erro do teste");
            })).isInstanceOf(AssertionError.class);
        }
        assertThat(disjuntor.estado()).isEqualTo("FECHADO");
        assertThat(ok()).isEqualTo("ok");
    }

    @Test
    void reiniciarFechaODisjuntorAberto() {
        for (int i = 0; i < 5; i++) {
            falhar();
        }
        assertThat(disjuntor.estado()).isEqualTo("ABERTO");

        disjuntor.reiniciar();

        assertThat(disjuntor.estado()).isEqualTo("FECHADO");
        assertThat(disjuntor.falta()).isEqualTo(Duration.ZERO);
        assertThat(ok()).isEqualTo("ok");
        for (int i = 0; i < 4; i++) {
            falhar();
        }
        assertThat(disjuntor.estado()).as("a conta recomeçou do zero").isEqualTo("FECHADO");
    }

    @Test
    void desligadoNaoExisteEACadaChamadaPassaDireto() {
        disjuntor = novo(false);
        for (int i = 0; i < 50; i++) {
            falhar();
        }
        assertThat(disjuntor.estado()).isEqualTo("DESLIGADO");
        assertThat(disjuntor.bloqueado()).isFalse();
        assertThat(ok()).isEqualTo("ok");
        assertThat(chamadas.get()).isEqualTo(51);
    }
}
