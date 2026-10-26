package br.com.rbs.portal.comum;

import java.time.OffsetDateTime;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Entidade da tabela historico: a trilha de auditoria (quem fez o quê, quando e de onde). Atende o item 1 da Entrega 2.
 * [HISTORICO] A tabela só aceita INSERT (o banco recusa UPDATE e DELETE), então a gravação é feita pelo HistoricoRepositorio
 * com SQL próprio. O campo ip é texto aqui, mas é do tipo inet no banco: leia sempre com host(ip) na consulta.
 */
@Table("historico")
public record Historico(
        @Id Long id,
        Long parceriaId,
        String entidade,
        Long entidadeId,
        String acao,
        String valorAnterior,
        String valorNovo,
        String detalhe,
        Long usuarioId,
        OffsetDateTime ocorridoEm,
        String ip,
        String userAgent) {
}
