package br.com.rbs.portal.acesso;

import br.com.rbs.portal.comum.RespostasDeSeguranca;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Confere, a cada requisição autenticada, se a conta da sessão ainda vale: ativa, com senha e, para o patrocinador, com
 * pelo menos um vínculo com parceria (a mesma regra do login e do GET /api/auth/eu, em AcessoRepositorio.temAcesso).
 * Atende o item 3 da Entrega 2. [PERMISSAO] Se a RBS revogou o e-mail ou desativou a conta depois do login, a sessão é
 * encerrada na hora e a resposta é 401 ("Sua sessão terminou. Entre de novo.") em qualquer rota, em vez de cada rota
 * descobrir sozinha, com outro código (404, 403 ou até 200).
 * Custa UMA consulta leve por requisição, e só para quem tem sessão; as rotas públicas (login, csrf e ativação) ficam de
 * fora, para quem perdeu o acesso conseguir entrar de novo com outra conta. Não é um @Component de propósito: um filtro
 * que é bean seria registrado também fora da cadeia do Spring Security, antes de a sessão ser lida.
 */
public class ConfereContaDaSessao extends OncePerRequestFilter {

    private final AcessoRepositorio acesso;
    private final RespostasDeSeguranca respostas;
    private final RequestMatcher rotasPublicas;

    public ConfereContaDaSessao(AcessoRepositorio acesso, RespostasDeSeguranca respostas, RequestMatcher rotasPublicas) {
        this.acesso = acesso;
        this.respostas = respostas;
        this.rotasPublicas = rotasPublicas;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest requisicao) {
        return rotasPublicas.matches(requisicao);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacao != null && autenticacao.getPrincipal() instanceof UsuarioAutenticado usuario
                && !acesso.temAcesso(usuario.id())) {
            // Invalida a sessão e limpa o contexto: a próxima chamada já chega como anônima.
            new SecurityContextLogoutHandler().logout(requisicao, resposta, autenticacao);
            respostas.sessaoEncerrada(requisicao, resposta);
            return;
        }
        cadeia.doFilter(requisicao, resposta);
    }
}
