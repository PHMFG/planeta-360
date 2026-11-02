package br.com.rbs.portal.atlas.loop;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * As vagas marcadas de um teto diário (M8). [ATLAS] O contador dos tetos é o das mensagens do assistente já gravadas, e um turno só
 * grava a primeira depois de começar: conferir o teto e começar o turno eram dois passos, e N pedidos juntos, com uma vaga só,
 * passavam todos. Aqui a conferência e a marcação da vaga são um passo só, com uma trava por chave (a parceria, no
 * {@link TetoDiario}; a pessoa da RBS, no {@link TetoDaRbs}), e o turno solta a vaga quando termina. Enquanto está marcada, a vaga
 * conta junto das mensagens gravadas. Na memória: some quando a API reinicia, e então não há turno em andamento para segurá-la.
 * <p>
 * Cada teto tem a sua instância, então chaves de tetos diferentes (uma parceria e uma pessoa com o mesmo número) nunca se cruzam.
 */
public final class VagasDoTeto {

    private static final int TRAVAS = 64;

    /** Os turnos que reservaram uma vaga e ainda não acabaram, por chave. */
    private final ConcurrentMap<Long, Integer> reservadas = new ConcurrentHashMap<>();
    private final Object[] travas = new Object[TRAVAS];

    /**
     * Uma vaga do teto, marcada por {@link #tentar} até o turno acabar. {@link #usadas} é o que a chave tinha gasto quando a vaga foi
     * marcada (o traço do turno guarda). Soltar é seguro de repetir: só a primeira vale.
     */
    public final class Reserva {

        private final long chave;
        private final long usadas;
        private final AtomicBoolean solta = new AtomicBoolean();

        private Reserva(long chave, long usadas) {
            this.chave = chave;
            this.usadas = usadas;
        }

        public long usadas() {
            return usadas;
        }

        public void soltar() {
            if (solta.compareAndSet(false, true)) {
                reservadas.compute(chave, (k, quantas) -> quantas == null || quantas <= 1 ? null : quantas - 1);
            }
        }
    }

    public VagasDoTeto() {
        for (int i = 0; i < TRAVAS; i++) {
            travas[i] = new Object();
        }
    }

    /** Quantos turnos desta chave têm uma vaga marcada agora. */
    public int reservadas(long chave) {
        return reservadas.getOrDefault(chave, 0);
    }

    /**
     * Confere o teto e, havendo folga, marca uma vaga, tudo no mesmo passo (por chave): dois pedidos juntos não passam os dois com
     * uma vaga só. Conta o que {@code usadasHoje} diz (as mensagens já gravadas) mais os turnos que têm vaga marcada. Sem folga, vazio.
     */
    public Optional<Reserva> tentar(long chave, LongSupplier usadasHoje, long teto) {
        synchronized (travas[(int) Math.floorMod(chave, (long) TRAVAS)]) {
            long usadas = usadasHoje.getAsLong();
            if (usadas + reservadas(chave) >= teto) {
                return Optional.empty();
            }
            reservadas.merge(chave, 1, Integer::sum);
            return Optional.of(new Reserva(chave, usadas));
        }
    }
}
