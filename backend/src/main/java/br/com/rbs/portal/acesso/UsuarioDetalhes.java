package br.com.rbs.portal.acesso;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * O usuário do banco visto pelo Spring Security durante a conferência de e-mail e senha. Atende o item 3 da Entrega 2.
 * [PERMISSAO] "habilitado" = a conta tem acesso vigente (ativa, com senha e, para patrocinador, com vínculo).
 * Só vive durante o login: o que vai para a sessão é o UsuarioAutenticado, sem o hash da senha.
 */
public record UsuarioDetalhes(Usuario usuario, boolean acessoVigente) implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + usuario.perfil().name()));
    }

    @Override
    public String getPassword() {
        // Conta que ainda não definiu a senha: string vazia (nunca confere), e isEnabled() já barra antes disso.
        return usuario.senhaHash() != null ? usuario.senhaHash() : "";
    }

    @Override
    public String getUsername() {
        return usuario.email();
    }

    @Override
    public boolean isEnabled() {
        return acessoVigente;
    }
}
