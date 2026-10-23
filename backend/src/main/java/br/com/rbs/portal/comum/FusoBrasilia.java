package br.com.rbs.portal.comum;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Converte um instante para o horário de Brasília antes de ir para o JSON. Atende o item 3 da Entrega 2.
 * O contrato (docs/api.md) mostra as datas com "-03:00", e o banco entrega tudo em UTC; o instante é o mesmo.
 * O Brasil não tem horário de verão desde 2019, então um deslocamento fixo de -03:00 basta.
 */
public final class FusoBrasilia {

    public static final ZoneOffset DESLOCAMENTO = ZoneOffset.ofHours(-3);

    private FusoBrasilia() {
    }

    public static OffsetDateTime em(OffsetDateTime instante) {
        return instante == null ? null : instante.withOffsetSameInstant(DESLOCAMENTO);
    }
}
