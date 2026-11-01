package br.com.rbs.portal.atlas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.rbs.portal.atlas.loop.TetoDiario;
import br.com.rbs.portal.atlas.loop.VagasDoTeto.Reserva;
import br.com.rbs.portal.comum.LimiteDeUsoException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * [ATLAS] O teto diário em turnos paralelos. O contador é o das mensagens já gravadas, e um turno só grava a primeira depois de
 * começar: conferir e começar eram dois passos, e N pedidos juntos, com uma vaga só, passavam todos. A reserva fecha a conta: quem
 * confere já deixa a vaga marcada até o turno acabar. Sem banco: o repositório responde um contador fixo, que é o estado do começo
 * de todos os pedidos.
 */
class TetoDiarioEmParaleloTest {

    private static final long PARCERIA = 7;

    private static TetoDiario tetoDe(int teto, long usadas) {
        AtlasRepositorio repositorio = mock(AtlasRepositorio.class);
        when(repositorio.mensagensDoModeloHoje(PARCERIA)).thenReturn(usadas);
        return new TetoDiario(repositorio, teto, new ModoDoAtlas(true, false, false, false));
    }

    @Test
    void comUmaVagaSoUmDosPedidosEmParaleloPassa() throws Exception {
        TetoDiario teto = tetoDe(200, 199);
        int pedidos = 24;
        ExecutorService pool = Executors.newFixedThreadPool(pedidos);
        CountDownLatch largada = new CountDownLatch(1);
        AtomicInteger passaram = new AtomicInteger();
        AtomicInteger barrados = new AtomicInteger();
        try {
            List<Future<?>> futuros = new ArrayList<>();
            for (int i = 0; i < pedidos; i++) {
                futuros.add(pool.submit(() -> {
                    largada.await();
                    try {
                        teto.reservar(PARCERIA);
                        passaram.incrementAndGet();
                    } catch (LimiteDeUsoException atingido) {
                        barrados.incrementAndGet();
                    }
                    return null;
                }));
            }
            largada.countDown();
            for (Future<?> futuro : futuros) {
                futuro.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(passaram.get()).as("pedidos que passaram com uma vaga só").isEqualTo(1);
        assertThat(barrados.get()).isEqualTo(pedidos - 1);
    }

    @Test
    void comNVagasPassamNExatamente() throws Exception {
        TetoDiario teto = tetoDe(200, 195);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch largada = new CountDownLatch(1);
        AtomicInteger passaram = new AtomicInteger();
        try {
            List<Future<?>> futuros = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                futuros.add(pool.submit(() -> {
                    largada.await();
                    if (teto.tentarReservar(PARCERIA).isPresent()) {
                        passaram.incrementAndGet();
                    }
                    return null;
                }));
            }
            largada.countDown();
            for (Future<?> futuro : futuros) {
                futuro.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(passaram.get()).isEqualTo(5);
    }

    @Test
    void aVagaSoltaVoltaParaOProximoPedidoESoltarDuasVezesNaoLiberaDuas() {
        TetoDiario teto = tetoDe(200, 199);

        Reserva primeira = teto.reservar(PARCERIA);
        assertThatThrownBy(() -> teto.reservar(PARCERIA)).isInstanceOf(LimiteDeUsoException.class);
        primeira.soltar();
        primeira.soltar();   // idempotente: a segunda chamada não devolve uma vaga que não existe

        Reserva segunda = teto.reservar(PARCERIA);
        assertThatThrownBy(() -> teto.reservar(PARCERIA)).isInstanceOf(LimiteDeUsoException.class);
        assertThat(segunda.usadas()).isEqualTo(199);
    }

    @Test
    void asReservasSaoPorParceria() {
        AtlasRepositorio repositorio = mock(AtlasRepositorio.class);
        when(repositorio.mensagensDoModeloHoje(1L)).thenReturn(199L);
        when(repositorio.mensagensDoModeloHoje(2L)).thenReturn(199L);
        TetoDiario teto = new TetoDiario(repositorio, 200, new ModoDoAtlas(true, false, false, false));

        teto.reservar(1L);
        teto.reservar(2L);   // a vaga da outra parceria não é tocada

        assertThatThrownBy(() -> teto.reservar(1L)).isInstanceOf(LimiteDeUsoException.class);
        assertThatThrownBy(() -> teto.reservar(2L)).isInstanceOf(LimiteDeUsoException.class);
    }

    @Test
    void oTetoJaAtingidoNemReservaENaoDeixaReservaPendurada() {
        TetoDiario teto = tetoDe(200, 200);

        assertThat(teto.tentarReservar(PARCERIA)).isEmpty();
        assertThatThrownBy(() -> teto.reservar(PARCERIA)).isInstanceOf(LimiteDeUsoException.class);
        assertThat(teto.reservadas(PARCERIA)).isZero();
    }
}
