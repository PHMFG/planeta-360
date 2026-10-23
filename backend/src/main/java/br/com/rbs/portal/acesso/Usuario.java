package br.com.rbs.portal.acesso;

import br.com.rbs.portal.comum.Perfil;
import java.time.OffsetDateTime;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Entidade da tabela usuario: quem entra no portal (Admin RBS ou Admin Patrocinador). Atende o item 1 da Entrega 2.
 * [PERMISSAO] senhaHash é nulo até o primeiro acesso e guarda só o hash BCrypt; a senha em si nunca é gravada.
 */
@Table("usuario")
public record Usuario(
        @Id Long id,
        String nome,
        String email,
        String senhaHash,
        Perfil perfil,
        boolean ativo,
        OffsetDateTime criadoEm) {
}
