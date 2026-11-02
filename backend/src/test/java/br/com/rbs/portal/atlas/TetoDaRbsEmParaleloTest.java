package br.com.rbs.portal.atlas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.rbs.portal.atlas.loop.TetoDaRbs;
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
 * [ATLAS] O teto diário da pessoa da RBS em turnos paralelos (M8), no molde do {@link TetoDiarioEmParaleloTest}: o contador é o das
 * mensagens já gravadas, e quem confere já deixa a vaga marcada até o turno acabar. Sem banco: o repositório responde um contador
 * fixo, que é o estado do começo de todos os pedidos. A prova de ponta a ponta, com o modelo e o banco, é o
 * {@link AtlasDaRbsTetoEmParaleloTest}.
 */
class TetoDaRbsEmParaleloTest {

    private static final long PESSOA = 11;

    private static TetoDaRbs tetoDe(int teto, long usadas) {
        AtlasRepositorio repositorio = mock(AtlasRepositorio.class);
        when(repositorio.mensagensDoModeloHojeDaRbs(PESSOA)).thenReturn(usadas);
        return new TetoDaRbs(repositorio, teto);
    }

    @Test
    void comUmaVagaSoUmDosPedidosEmParaleloPassa() throws Exception {
        TetoDaRbs teto = tetoDe(200, 199);
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
                        teto.reservar(PESSOA);
                        passaram.incrementAndGet();
                    } catch (LimiteDeUsoException atingido) {
                        barrados.incrementAndGet();
                        assertThat(atingido.getMessage()).isEqualTo(TetoDaRbs.MENSAGEM);
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
        assertThat(teto.reservadas(PESSOA)).isEqualTo(1);
    }

    @Test
    void comNVagasPassamNExatamente() throws Exception {
        TetoDaRbs teto = tetoDe(200, 195);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch largada = new CountDownLatch(1);
        AtomicInteger passaram = new AtomicInteger();
        try {
            List<Future<?>> futuros = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                futuros.add(pool.submit(() -> {
                    largada.await();
                    if (teto.tentarReservar(PESSOA).isPresent()) {
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
        TetoDaRbs teto = tetoDe(200, 199);

        Reserva primeira = teto.reservar(PESSOA);
        assertThatThrownBy(() -> teto.reservar(PESSOA)).isInstanceOf(LimiteDeUsoException.class).hasMessage(TetoDaRbs.MENSAGEM);
        primeira.soltar();
        primeira.soltar();   // idempotente: a segunda chamada não devolve uma vaga que não existe

        Reserva segunda = teto.reservar(PESSOA);
        assertThatThrownBy(() -> teto.reservar(PESSOA)).isInstanceOf(LimiteDeUsoException.class);
        assertThat(segunda.usadas()).isEqualTo(199);
    }

    @Test
    void asReservasSaoPorPessoa() {
        AtlasRepositorio repositorio = mock(AtlasRepositorio.class);
        when(repositorio.mensagensDoModeloHojeDaRbs(1L)).thenReturn(199L);
        when(repositorio.mensagensDoModeloHojeDaRbs(2L)).thenReturn(199L);
        TetoDaRbs teto = new TetoDaRbs(repositorio, 200);

        teto.reservar(1L);
        teto.reservar(2L);   // a vaga da outra pessoa não é tocada

        assertThatThrownBy(() -> teto.reservar(1L)).isInstanceOf(LimiteDeUsoException.class);
        assertThatThrownBy(() -> teto.reservar(2L)).isInstanceOf(LimiteDeUsoException.class);
    }

    @Test
    void oTetoJaAtingidoNemReservaENaoDeixaReservaPendurada() {
        TetoDaRbs teto = tetoDe(200, 200);

        assertThat(teto.tentarReservar(PESSOA)).isEmpty();
        assertThatThrownBy(() -> teto.reservar(PESSOA)).isInstanceOf(LimiteDeUsoException.class);
        assertThat(teto.reservadas(PESSOA)).isZero();
    }

    @Test
    void aReservaGuardaQuantasMensagensJaTinhamSidoGastasQuandoFoiMarcada() {
        TetoDaRbs teto = tetoDe(200, 42);

        assertThat(teto.reservar(PESSOA).usadas()).isEqualTo(42);
    }
}
