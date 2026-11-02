package br.com.rbs.portal.atlas;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Um {@link Clock} que o teste adianta à mão: com ele, "quanto falta para liberar" (os limites por usuário) e "tenta de novo
 * depois de 60 s" (os disjuntores) se conferem sem esperar. Só existe em {@code src/test}. Não faz parte da API.
 */
public class RelogioDeTeste extends Clock {

    private final AtomicReference<Instant> agora = new AtomicReference<>(Instant.parse("2026-11-01T12:00:00Z"));

    public void avancar(Duration tempo) {
        agora.updateAndGet(instante -> instante.plus(tempo));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zona) {
        return this;
    }

    @Override
    public Instant instant() {
        return agora.get();
    }
}
