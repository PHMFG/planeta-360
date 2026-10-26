package br.com.rbs.portal.comum;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

/**
 * Grava LOGOUT no histórico na hora do logout. Atende o item 5 da Entrega 2.
 * [HISTORICO] Roda antes de a sessão ser encerrada, porque depois dela já não se sabe quem era o usuário.
 */
@Component
public class RegistraLogout implements LogoutHandler {

    private final HistoricoService historico;

    public RegistraLogout(HistoricoService historico) {
        this.historico = historico;
    }

    @Override
    public void logout(HttpServletRequest requisicao, HttpServletResponse resposta, Authentication autenticacao) {
        // Logout sem sessão não é erro (responde 204 do mesmo jeito), mas também não há de quem registrar.
        if (autenticacao != null && autenticacao.getPrincipal() instanceof UsuarioAutenticado usuario) {
            historico.registrarLogout(usuario, requisicao);
        }
    }
}
