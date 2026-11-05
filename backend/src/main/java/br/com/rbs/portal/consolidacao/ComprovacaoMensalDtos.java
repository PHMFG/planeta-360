package br.com.rbs.portal.consolidacao;

import java.math.BigDecimal;
import java.util.List;

/**
 * Objeto de saída da rota 88 (comprovação mensal de mídia). [RELATORIO] Unidades de mídia nunca são somadas entre si: cada
 * linha e cada total traz a sua unidade.
 */
public final class ComprovacaoMensalDtos {

    private ComprovacaoMensalDtos() {
    }

    /** Uma linha: mês (AAAA-MM) x tipo de mídia x unidade. {@code previsto} é o do plano inteiro para o tipo e a unidade. */
    public record LinhaMensalDto(String mes, String tipoMidia, String tipoMidiaNome, String unidade, String unidadeNome,
                                 BigDecimal exibido, BigDecimal previsto) {
    }

    /** O exibido do mês numa unidade (soma dos tipos de mídia daquele mês e daquela unidade). */
    public record TotalMensalDto(String mes, String unidade, String unidadeNome, BigDecimal exibido) {
    }

    public record ComprovacaoMensalDto(Long parceriaId, String patrocinador, List<LinhaMensalDto> linhas,
                                       List<TotalMensalDto> totais) {
    }
}
