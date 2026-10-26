package br.com.rbs.portal.acesso;

import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Lê e grava usuários (Spring Data JDBC). Atende o item 3 da Entrega 2.
 * O e-mail é comparado sem diferenciar maiúsculas, como o índice único uq_usuario_email do banco.
 */
public interface UsuarioRepositorio extends ListCrudRepository<Usuario, Long> {

    @Query("SELECT * FROM usuario WHERE lower(email) = lower(:email)")
    Optional<Usuario> buscarPorEmail(@Param("email") String email);
}
