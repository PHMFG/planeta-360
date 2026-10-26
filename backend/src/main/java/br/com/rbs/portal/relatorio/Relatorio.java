package br.com.rbs.portal.relatorio;

import java.time.OffsetDateTime;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Entidade da tabela relatorio: uma versão do relatório de uma parceria. Atende o item 1 da Entrega 2.
 * [RELATORIO] O status só avança RASCUNHO, VALIDADO, DISPONIBILIZADO, nessa ordem (o banco recusa o resto), e o patrocinador
 * só vê o disponibilizado. Os números de cada versão ficam na tabela relatorio_indicador.
 */
@Table("relatorio")
public record Relatorio(
        @Id Long id,
        Long parceriaId,
        Integer versao,
        String status,
        Long geradoPor,
        OffsetDateTime geradoEm,
        Long validadoPor,
        OffsetDateTime validadoEm,
        Long disponibilizadoPor,
        OffsetDateTime disponibilizadoEm,
        Long arquivoId) {
}
