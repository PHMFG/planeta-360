package br.com.rbs.portal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Pequenas ajudas dos testes: pedir o token CSRF como o front faz e ler campos do JSON da resposta.
 * Atende o item 3 da Entrega 2 (testes das rotas). Não faz parte da API.
 */
public final class ApoioDeTeste {

    private ApoioDeTeste() {
    }

    /** O par que o front guarda: o cookie XSRF-TOKEN e o token que vai no cabeçalho X-XSRF-TOKEN. */
    public record Csrf(Cookie cookie, String token) {
    }

    public static Csrf pedirCsrf(MockMvcTester mvc) {
        MvcTestResult resposta = mvc.get().uri("/api/auth/csrf").exchange();
        assertThat(resposta).hasStatus(HttpStatus.OK);
        Cookie cookie = resposta.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).as("a rota /csrf grava o cookie XSRF-TOKEN").isNotNull();
        return new Csrf(cookie, lerTexto(resposta, "$.token"));
    }

    /** Lê um campo do JSON da resposta pelo caminho JsonPath (por exemplo "$.parcerias[0].cota.codigo"). */
    public static Object lerJson(MvcTestResult resposta, String caminho) {
        try {
            return JsonPath.read(corpo(resposta), caminho);
        } catch (Exception erro) {
            throw new AssertionError("Não consegui ler " + caminho + " em: " + corpo(resposta), erro);
        }
    }

    public static String lerTexto(MvcTestResult resposta, String caminho) {
        return (String) lerJson(resposta, caminho);
    }

    @SuppressWarnings("unchecked")
    public static <T> List<T> lerLista(MvcTestResult resposta, String caminho) {
        return (List<T>) lerJson(resposta, caminho);
    }

    public static String corpo(MvcTestResult resposta) {
        try {
            return resposta.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception erro) {
            throw new IllegalStateException(erro);
        }
    }
}
