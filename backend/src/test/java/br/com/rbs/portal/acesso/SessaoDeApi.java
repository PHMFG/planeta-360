package br.com.rbs.portal.acesso;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.ApoioDeTeste;
import br.com.rbs.portal.ApoioDeTeste.Csrf;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Uma pessoa usando a API nos testes: entra pela rota de login (sessão de verdade) e manda cada chamada com o cookie da
 * sessão e o token CSRF, como o front faz. É o único ajudante de login dos testes das rotas (o ClienteDeTeste da pesquisa e
 * da consolidação foi unido a este). Atende o item 3 da Entrega 2. Não faz parte da API.
 */
public final class SessaoDeApi {

    public static final String SENHA = "senha123";   // a senha de todos os usuários da amostra

    private final MockMvcTester mvc;
    private final MockHttpSession sessao = new MockHttpSession();
    private final Csrf csrf;
    private final String agente;   // o cabeçalho User-Agent de toda chamada; nulo = não manda

    private SessaoDeApi(MockMvcTester mvc, String agente) {
        this.mvc = mvc;
        this.agente = agente;
        this.csrf = ApoioDeTeste.pedirCsrf(mvc);
    }

    /** Alguém sem login (para as rotas públicas e para provar o 401). */
    public static SessaoDeApi anonima(MockMvcTester mvc) {
        return new SessaoDeApi(mvc, null);
    }

    /** Entra pelo POST /api/auth/login e confere que deu certo. */
    public static SessaoDeApi entrar(MockMvcTester mvc, String email) {
        return entrar(mvc, email, null);
    }

    /** Como {@link #entrar(MockMvcTester, String)}, mas toda chamada leva este User-Agent (para conferir o histórico). */
    public static SessaoDeApi entrar(MockMvcTester mvc, String email, String agente) {
        SessaoDeApi pessoa = new SessaoDeApi(mvc, agente);
        MvcTestResult login = pessoa.post("/api/auth/login", "{\"email\":\"" + email + "\",\"senha\":\"" + SENHA + "\"}");
        assertThat(login).as("login de " + email).hasStatus(HttpStatus.OK);
        return pessoa;
    }

    public MvcTestResult get(String uri, Object... variaveis) {
        var pedido = mvc.get().uri(uri, variaveis).session(sessao);
        if (agente != null) {
            pedido = pedido.header("User-Agent", agente);
        }
        return pedido.exchange();
    }

    /** Um GET com mais um cabeçalho (o {@code Range} do leitor de PDF, por exemplo). */
    public MvcTestResult getComCabecalho(String uri, String nome, String valor) {
        var pedido = mvc.get().uri(uri).session(sessao).header(nome, valor);
        if (agente != null) {
            pedido = pedido.header("User-Agent", agente);
        }
        return pedido.exchange();
    }

    /** Um HEAD: a mesma rota do GET, sem corpo na resposta. */
    public MvcTestResult head(String uri) {
        var pedido = mvc.method(HttpMethod.HEAD).uri(uri).session(sessao);
        if (agente != null) {
            pedido = pedido.header("User-Agent", agente);
        }
        return pedido.exchange();
    }

    public MvcTestResult post(String uri, String json) {
        return enviar(HttpMethod.POST, uri, json, true);
    }

    public MvcTestResult put(String uri, String json) {
        return enviar(HttpMethod.PUT, uri, json, true);
    }

    public MvcTestResult patch(String uri, String json) {
        return enviar(HttpMethod.PATCH, uri, json, true);
    }

    public MvcTestResult delete(String uri) {
        return enviar(HttpMethod.DELETE, uri, null, true);
    }

    /**
     * Um POST com este cabeçalho {@code Accept} (a resposta em fluxo das rotas 44 e 46 usa {@code text/event-stream}). Se a
     * resposta for assíncrona, espera ela terminar: o corpo traz todos os eventos.
     */
    public MvcTestResult postAceitando(String uri, String json, String aceita) {
        var pedido = mvc.post().uri(uri).session(sessao).cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token())
                .header(HttpHeaders.ACCEPT, aceita).contentType(MediaType.APPLICATION_JSON).content(json);
        if (agente != null) {
            pedido = pedido.header("User-Agent", agente);
        }
        return pedido.exchange();
    }

    /** O mesmo envio, mas sem o cabeçalho X-XSRF-TOKEN (a API tem de recusar com 403). */
    public MvcTestResult postSemCsrf(String uri, String json) {
        return semCsrf(HttpMethod.POST, uri, json);
    }

    /** Idem para qualquer método (PUT, PATCH...). */
    public MvcTestResult semCsrf(HttpMethod metodo, String uri, String json) {
        return enviar(metodo, uri, json, false);
    }

    private MvcTestResult enviar(HttpMethod metodo, String uri, String json, boolean comCsrf) {
        var pedido = mvc.method(metodo).uri(uri).session(sessao).cookie(csrf.cookie());
        if (agente != null) {
            pedido = pedido.header("User-Agent", agente);
        }
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
