package br.com.rbs.portal.visita;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Corpos da rota 120 (as visitas e o selo "novo" de cada aba). Todos em record, camelCase. O contrato está em docs/api.md.
 */
public final class VisitaDtos {

    private VisitaDtos() {
    }

    /** O estado de uma aba para quem pergunta: há novidade desde a última visita? E quando foi a última visita? */
    public record NovidadeDaAbaDto(boolean novo, OffsetDateTime desde) {
    }

    /** A resposta da rota 120: as oito abas (as sete do menu mais o relatório), na ordem do menu. */
    public record NovidadesDto(Map<String, NovidadeDaAbaDto> abas) {
    }
}
