package br.com.rbs.portal.materiais;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Entidade da tabela entrega: cada item que a marca ou a RBS precisa cumprir, com o status. Atende o item 1 da Entrega 2.
 * [PERMISSAO] As transições de status (NAO_INICIADO, ENVIADO, EM_ANALISE, APROVADO...) não são limitadas pelo banco:
 * a regra de quem pode mudar para qual status fica no MateriaisService (etapa 5).
 */
@Table("entrega")
public record Entrega(
        @Id Long id,
        Long parceriaId,
        Integer abaId,
        String titulo,
        String descricao,
        String status,
        LocalDate prazo,
        OffsetDateTime criadoEm,
        OffsetDateTime atualizadoEm) {
}
