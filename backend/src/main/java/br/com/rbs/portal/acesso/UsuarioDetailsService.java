package br.com.rbs.portal.acesso;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Busca o usuário no banco pelo e-mail para o Spring Security conferir a senha. Atende o item 3 da Entrega 2.
 * [PERMISSAO] O usuário ativo é lido do banco a cada login, e quem teve o e-mail revogado não entra.
 */
@Service
public class UsuarioDetailsService implements UserDetailsService {

    private final UsuarioRepositorio usuarios;
    private final AcessoRepositorio acesso;

    public UsuarioDetailsService(UsuarioRepositorio usuarios, AcessoRepositorio acesso) {
        this.usuarios = usuarios;
        this.acesso = acesso;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        Usuario usuario = usuarios.buscarPorEmail(email.trim())
                .orElseThrow(() -> new UsernameNotFoundException("Usuário não encontrado"));
        return new UsuarioDetalhes(usuario, acesso.temAcesso(usuario.id()));
    }
}
