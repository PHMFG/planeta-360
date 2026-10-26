package br.com.rbs.portal.acesso;

import br.com.rbs.portal.acesso.AcessoDtos.AtivarRequisicao;
import br.com.rbs.portal.acesso.AcessoDtos.CsrfResposta;
import br.com.rbs.portal.acesso.AcessoDtos.LoginRequisicao;
import br.com.rbs.portal.acesso.AcessoDtos.UsuarioLogadoDto;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DeferredCsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas de entrada no portal: GET /csrf, POST /login, POST /ativar (primeiro acesso) e GET /eu.
 * Atende os itens 3 e 5 da Entrega 2. O logout (POST /api/auth/logout, 204) é tratado pelo Spring Security,
 * configurado em SegurancaConfig.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService servico;
    private final AtivacaoService ativacao;

    public AuthController(AuthService servico, AtivacaoService ativacao) {
        this.servico = servico;
        this.ativacao = ativacao;
    }

    /**
     * O front chama antes do primeiro POST se ainda não tiver o cookie XSRF-TOKEN. Esta chamada também grava o cookie.
     * Devolve o token BRUTO (o mesmo valor do cookie), que é o que o cabeçalho X-XSRF-TOKEN aceita no modo SPA.
     * O parâmetro CsrfToken do Spring traz a versão embaralhada (contra o ataque BREACH), que serve só para formulários.
     */
    @GetMapping("/csrf")
    public CsrfResposta csrf(HttpServletRequest requisicao) {
        // O CsrfFilter deixa o token da requisição neste atributo (carrega do cookie ou gera um novo e grava o cookie).
        DeferredCsrfToken adiado = (DeferredCsrfToken) requisicao.getAttribute(DeferredCsrfToken.class.getName());
        if (adiado == null) {
            throw new IllegalStateException("A proteção CSRF não está ligada nesta rota.");
        }
        CsrfToken token = adiado.get();
        return new CsrfResposta(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/login")
    public UsuarioLogadoDto login(@Valid @RequestBody LoginRequisicao corpo, HttpServletRequest requisicao,
                                  HttpServletResponse resposta) {
        return servico.login(corpo, requisicao, resposta);
    }

    /**
     * Primeiro acesso: o patrocinador define a senha com o código que a RBS repassou. Público (exige o token CSRF).
     * Responde 204 e NÃO abre sessão; o front chama o login em seguida.
     */
    @PostMapping("/ativar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ativar(@Valid @RequestBody AtivarRequisicao corpo, HttpServletRequest requisicao) {
        ativacao.ativar(corpo, requisicao);
    }

    @GetMapping("/eu")
    public UsuarioLogadoDto eu(@AuthenticationPrincipal UsuarioAutenticado usuario, HttpServletRequest requisicao,
                               HttpServletResponse resposta) {
        return servico.eu(usuario, requisicao, resposta);
    }
}
