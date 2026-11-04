package br.com.rbs.portal.credenciamento;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Objetos das rotas 77 e 78. [CREDENCIAMENTO] A rota 79 devolve o EventoCompletoDto. */
public final class CredenciamentoDtos {

    private CredenciamentoDtos() {
    }

    /** Uma linha da grade: o setor, para quem é e quantas credenciais por dia. */
    public record CredencialDto(Long id, String setor, String beneficiario, Integer quantidadePorDia) {
    }

    /**
     * A resposta das rotas 77 e 78. {@code totalPorDia} é a soma das linhas. O endereço do Open Staff e o período de cadastro são da
     * edição da parceria ({@code eventoId}); nulos até a RBS informar.
     */
    public record CredenciaisDto(Long parceriaId, Long eventoId, int totalPorDia, List<CredencialDto> credenciais, String openStaffUrl,
                                 LocalDate credenciamentoInicio, LocalDate credenciamentoFim) {
    }

    /** Corpo da rota 78. A quantidade vem como número qualquer: o service recusa o que não cabe (nunca um 500 do banco). */
    public record ItemRequisicao(String setor, String beneficiario, BigDecimal quantidadePorDia) {
    }

    public record CredenciaisRequisicao(List<ItemRequisicao> credenciais) {
    }
}
