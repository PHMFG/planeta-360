package br.com.rbs.portal;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.ApoioDeTeste.Csrf;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Faz as chamadas dos testes como o front faz: login pela rota (sessão de verdade) e o token CSRF em toda alteração.
 * Atende o item 3 da Entrega 2 (testes das rotas). Não faz parte da API. Os testes da pesquisa e da consolidação usam esta classe.
 */
public final class ClienteDeTeste {

    public static final String SENHA = "senha123";
    public static final String AGENTE = "ClienteDeTeste/1.0";

    private final MockMvcTester mvc;

    public ClienteDeTeste(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    /** Entra pela rota de login e devolve a sessão (o cookie JSESSIONID de verdade). */
    public MockHttpSession entrar(String email) {
        MockHttpSession sessao = new MockHttpSession();
        Csrf csrf = ApoioDeTeste.pedirCsrf(mvc);
        MvcTestResult login = mvc.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("X-XSRF-TOKEN", csrf.token()).header("User-Agent", AGENTE).cookie(csrf.cookie())
                .session(sessao).content("{\"email\":\"" + email + "\",\"senha\":\"" + SENHA + "\"}").exchange();
        assertThat(login).hasStatus(HttpStatus.OK);
        return sessao;
    }

    /** GET. Sem sessão (nulo) a chamada vai como visitante, para testar o 401. */
    public MvcTestResult ler(MockHttpSession sessao, String uri) {
        var pedido = mvc.get().uri(uri).header("User-Agent", AGENTE);
        if (sessao != null) {
            pedido.session(sessao);
        }
        return pedido.exchange();
    }

    /** POST, PUT ou PATCH com o token CSRF (cookie XSRF-TOKEN e cabeçalho X-XSRF-TOKEN). Sessão nula = visitante. */
    public MvcTestResult enviar(MockHttpSession sessao, HttpMethod metodo, String uri, String json) {
        Csrf csrf = ApoioDeTeste.pedirCsrf(mvc);
        var pedido = mvc.method(metodo).uri(uri).header("X-XSRF-TOKEN", csrf.token()).header("User-Agent", AGENTE)
                .cookie(csrf.cookie());
        if (sessao != null) {
            pedido.session(sessao);
        }
        if (json != null) {
            pedido.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return pedido.exchange();
    }

    /** O mesmo envio, mas SEM o cabeçalho X-XSRF-TOKEN: a API deve recusar com 403. */
    public MvcTestResult semToken(MockHttpSession sessao, HttpMethod metodo, String uri, String json) {
        var pedido = mvc.method(metodo).uri(uri).header("User-Agent", AGENTE).session(sessao);
        if (json != null) {
            pedido.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return pedido.exchange();
    }
}
