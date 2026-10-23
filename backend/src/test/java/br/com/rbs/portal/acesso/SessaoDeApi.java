package br.com.rbs.portal.acesso;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.ApoioDeTeste;
import br.com.rbs.portal.ApoioDeTeste.Csrf;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Uma pessoa usando a API nos testes da etapa 4: entra pela rota de login (sessão de verdade) e manda cada chamada com o
 * cookie da sessão e o token CSRF, como o front faz. Atende o item 3 da Entrega 2. Não faz parte da API.
 */
public final class SessaoDeApi {

    public static final String SENHA = "senha123";   // a senha de todos os usuários da amostra

    private final MockMvcTester mvc;
    private final MockHttpSession sessao = new MockHttpSession();
    private final Csrf csrf;

    private SessaoDeApi(MockMvcTester mvc) {
        this.mvc = mvc;
        this.csrf = ApoioDeTeste.pedirCsrf(mvc);
    }

    /** Alguém sem login (para as rotas públicas e para provar o 401). */
    public static SessaoDeApi anonima(MockMvcTester mvc) {
        return new SessaoDeApi(mvc);
    }

    /** Entra pelo POST /api/auth/login e confere que deu certo. */
    public static SessaoDeApi entrar(MockMvcTester mvc, String email) {
        SessaoDeApi pessoa = new SessaoDeApi(mvc);
        MvcTestResult login = pessoa.post("/api/auth/login", "{\"email\":\"" + email + "\",\"senha\":\"" + SENHA + "\"}");
        assertThat(login).as("login de " + email).hasStatus(HttpStatus.OK);
        return pessoa;
    }

    public MvcTestResult get(String uri, Object... variaveis) {
        return mvc.get().uri(uri, variaveis).session(sessao).exchange();
    }

    public MvcTestResult post(String uri, String json) {
        return enviar(HttpMethod.POST, uri, json, true);
    }

    public MvcTestResult patch(String uri, String json) {
        return enviar(HttpMethod.PATCH, uri, json, true);
    }

    public MvcTestResult delete(String uri) {
        return enviar(HttpMethod.DELETE, uri, null, true);
    }

    /** O mesmo envio, mas sem o cabeçalho X-XSRF-TOKEN (a API tem de recusar com 403). */
    public MvcTestResult postSemCsrf(String uri, String json) {
        return enviar(HttpMethod.POST, uri, json, false);
    }

    private MvcTestResult enviar(HttpMethod metodo, String uri, String json, boolean comCsrf) {
        var pedido = mvc.method(metodo).uri(uri).session(sessao).cookie(csrf.cookie());
        if (comCsrf) {
            pedido = pedido.header("X-XSRF-TOKEN", csrf.token());
        }
        if (json != null) {
            pedido = pedido.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return pedido.exchange();
    }

    public MockHttpSession sessao() {
        return sessao;
    }

    /** O id da parceria de 2027 de um cliente da amostra ("Cliente Amarelo", "Cliente Roxo"...). */
    public static long parceriaDe2027(JdbcClient jdbc, String cliente) {
        return jdbc.sql("""
                        SELECT pa.id FROM parceria pa JOIN patrocinador p ON p.id = pa.patrocinador_id
                        JOIN evento e ON e.id = pa.evento_id WHERE p.nome_canonico = :cliente AND e.edicao = 2027
                        """).param("cliente", cliente).query(Long.class).single();
    }
}
