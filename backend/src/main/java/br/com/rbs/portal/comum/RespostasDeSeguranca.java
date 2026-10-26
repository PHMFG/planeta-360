package br.com.rbs.portal.comum;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Respostas JSON dos filtros de segurança: 401 quando não há sessão e 403 quando falta permissão ou o token CSRF.
 * Atende o item 3 da Entrega 2. A API nunca redireciona para uma página de login: só responde JSON (ProblemDetail).
 */
@Component
public class RespostasDeSeguranca implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper json;

    public RespostasDeSeguranca(JsonMapper json) {
        this.json = json;
    }

    /** Sem sessão (ou sessão vencida) em rota que exige login. */
    @Override
    public void commence(HttpServletRequest requisicao, HttpServletResponse resposta, AuthenticationException ex)
            throws IOException {
        escrever(requisicao, resposta, Problemas.de(HttpStatus.UNAUTHORIZED, "Entre no portal para continuar."));
    }

    /**
     * Logado, mas sem permissão. O caso mais comum de 403 é o token CSRF ausente ou errado: o front deve chamar
     * GET /api/auth/csrf e repetir o pedido.
     */
    @Override
    public void handle(HttpServletRequest requisicao, HttpServletResponse resposta, AccessDeniedException ex)
            throws IOException {
        ProblemDetail problema = ex instanceof CsrfException
                ? Problemas.de(HttpStatus.FORBIDDEN,
                        "O token de segurança (CSRF) está ausente ou venceu. Recarregue a página e tente de novo.")
                : Problemas.padrao(HttpStatus.FORBIDDEN);
        escrever(requisicao, resposta, problema);
    }

    private void escrever(HttpServletRequest requisicao, HttpServletResponse resposta, ProblemDetail problema)
            throws IOException {
        resposta.setStatus(problema.getStatus());
        resposta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        resposta.setCharacterEncoding("UTF-8");
        json.writeValue(resposta.getOutputStream(), Problemas.comoMapa(problema, requisicao.getRequestURI()));
    }
}
