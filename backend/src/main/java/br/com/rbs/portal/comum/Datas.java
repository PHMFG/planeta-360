package br.com.rbs.portal.comum;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Datas no horário de Brasília, como o contrato da API pede (docs/api.md: ISO 8601 com fuso, "-03:00").
 * Atende o item 6 da Entrega 2. O driver do PostgreSQL devolve todo timestamptz em UTC ("+00:00"), então quem monta
 * a resposta passa o valor por aqui antes de entregá-lo ao front.
 */
public final class Datas {

    public static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    private Datas() {
    }

    /** O mesmo instante, escrito com o fuso de Brasília. Aceita nulo (devolve nulo). */
    public static OffsetDateTime emBrasilia(OffsetDateTime instante) {
        return instante == null ? null : instante.atZoneSameInstant(BRASILIA).toOffsetDateTime();
    }

    /** O dia de hoje em Brasília (usado no nome dos arquivos CSV). */
    public static LocalDate hoje() {
        return LocalDate.now(BRASILIA);
    }
}
