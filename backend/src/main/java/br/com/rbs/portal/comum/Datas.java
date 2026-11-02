package br.com.rbs.portal.comum;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Datas no horário de Brasília, como o contrato da API pede (docs/api.md: ISO 8601 com fuso, "-03:00").
 * Atende o item 6 da Entrega 2. O driver do PostgreSQL devolve todo timestamptz em UTC ("+00:00"), então quem monta
 * a resposta passa o valor por aqui antes de entregá-lo ao front.
 */
public final class Datas {

    public static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    /** A frase do 422 de um dia de filtro (de, ate) que não vale. */
    public static final String DATA_INVALIDA = "Data inválida. Use o formato AAAA-MM-DD, por exemplo 2026-10-23.";

    /** O primeiro e o último ano que o formato AAAA-MM-DD escreve (quatro dígitos). */
    private static final int PRIMEIRO_ANO = 1;
    private static final int ULTIMO_ANO = 9999;

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

    /**
     * [VALIDACAO] O dia de um filtro de período ({@code de} ou {@code ate}, AAAA-MM-DD). Em branco devolve nulo sem erro. Se o
     * texto não é uma data de verdade (30 de fevereiro, mês 13, outro formato), ou se o ano não cabe nos quatro dígitos do formato
     * (o {@link LocalDate#parse} aceita "+300000-01-01", e o banco, depois de somar o dia e passar para o fuso, estoura a partir do
     * ano 294276 e a rota respondia 500), registra a frase {@link #DATA_INVALIDA} em {@code campos[campo]} e devolve nulo. Todo filtro
     * de período passa por aqui, para a regra ser uma só.
     */
    public static LocalDate diaDoFiltro(String campo, String valor, Map<String, String> campos) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            LocalDate dia = LocalDate.parse(valor.trim());
            if (dia.getYear() >= PRIMEIRO_ANO && dia.getYear() <= ULTIMO_ANO) {
                return dia;
            }
        } catch (DateTimeParseException invalida) {
            // cai na frase abaixo
        }
        campos.put(campo, DATA_INVALIDA);
        return null;
    }
}
