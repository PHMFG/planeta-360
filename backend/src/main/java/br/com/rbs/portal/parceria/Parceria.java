package br.com.rbs.portal.parceria;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Entidade da tabela parceria: uma marca numa edição do festival, com um tipo de cota. Atende o item 1 da Entrega 2.
 * [ISOLAMENTO] A parceria é a unidade de isolamento: tudo o que o patrocinador vê depende dela.
 */
@Table("parceria")
public record Parceria(
        @Id Long id,
        Long eventoId,
        Long patrocinadorId,
        Integer cotaTipoId,
        String nomeProduto,
        BigDecimal valorInvestimento,
        String baseValor,
        String situacao,
        OffsetDateTime criadoEm) {
}
