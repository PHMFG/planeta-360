package br.com.rbs.portal.acesso;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.ApoioDeTeste;
import br.com.rbs.portal.ApoioDeTeste.Csrf;
import br.com.rbs.portal.TesteDeIntegracao;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Testa as rotas /api/auth como o front as usa: pede o token CSRF, entra com e-mail e senha, guarda a sessão e sai.
 * Atende os itens 3 e 5 da Entrega 2. Cada teste é desfeito no fim (@Transactional), então o histórico e as
 * alterações de dados não vazam de um teste para o outro.
 */
@TesteDeIntegracao
@Transactional
class AuthControllerTest {

    private static final String SENHA = "senha123";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    Csrf pedirCsrf() {
        return ApoioDeTeste.pedirCsrf(mvc);
    }

    MvcTestResult entrar(String email, String senha, MockHttpSession sessao) {
        Csrf csrf = pedirCsrf();
        var pedido = mvc.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-XSRF-TOKEN", csrf.token())
                .header("User-Agent", "TesteAutomatico/1.0")
                .cookie(csrf.cookie())
                .content("{\"email\":\"" + email + "\",\"senha\":\"" + senha + "\"}");
        if (sessao != null) {
            pedido.session(sessao);
        }
        return pedido.exchange();
    }

    MvcTestResult sair(MockHttpSession sessao) {
        Csrf csrf = pedirCsrf();
        return mvc.post().uri("/api/auth/logout")
                .header("X-XSRF-TOKEN", csrf.token()).cookie(csrf.cookie()).session(sessao).exchange();
    }

    long historicoDoEmail(String acao, String email) {
        return jdbc.sql("SELECT count(*) FROM historico WHERE acao = :acao AND detalhe = 'e-mail tentado: ' || :email")
                .param("acao", acao).param("email", email).query(Long.class).single();
    }

    // ---------------------------------------------------------------------
    // GET /api/auth/csrf
    // ---------------------------------------------------------------------

    @Test
    void csrfDevolveCabecalhoETokenEGravaOCookie() {
        MvcTestResult resposta = mvc.get().uri("/api/auth/csrf").exchange();

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.cabecalho")).isEqualTo("X-XSRF-TOKEN");
        String token = (String) lerJson(resposta, "$.token");
        assertThat(token).isNotBlank();
        Cookie cookie = resposta.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).as("o cookie tem o mesmo token do JSON").isEqualTo(token);
        assertThat(cookie.isHttpOnly()).as("o JavaScript do front precisa ler este cookie").isFalse();
    }

    // ---------------------------------------------------------------------
    // POST /api/auth/login
    // ---------------------------------------------------------------------

    @Test
    void loginCertoDevolveOUsuarioLogadoComAParceriaDoAmarelo() {
        MvcTestResult resposta = entrar("marketing@amarelo.example", SENHA, null);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(corpo(resposta)).as("o hash da senha nunca sai na resposta").doesNotContain("$2a$").doesNotContain("senha");
        assertThat(lerJson(resposta, "$.nome")).isEqualTo("Marketing Amarelo");
        assertThat(lerJson(resposta, "$.email")).isEqualTo("marketing@amarelo.example");
        assertThat(lerJson(resposta, "$.perfil")).isEqualTo("ADMIN_PATROCINADOR");

        List<Object> parcerias = lerLista(resposta, "$.parcerias");
        assertThat(parcerias).as("o Amarelo só tem a parceria de 2027").hasSize(1);
        assertThat(lerJson(resposta, "$.parcerias[0].patrocinador")).isEqualTo("Cliente Amarelo");
        assertThat(lerJson(resposta, "$.parcerias[0].cota.codigo")).isEqualTo("MASTER");
        assertThat(lerJson(resposta, "$.parcerias[0].cota.nome")).isEqualTo("Master");
        assertThat(lerJson(resposta, "$.parcerias[0].evento.nome")).isEqualTo("Planeta Atlântida");
        assertThat(lerJson(resposta, "$.parcerias[0].evento.edicao")).isEqualTo(2027);

        List<Map<String, Object>> abas = lerLista(resposta, "$.parcerias[0].abas");
        assertThat(abas).as("as 7 abas do menu, em ordem").hasSize(7);
        assertThat(abas).extracting(a -> a.get("codigo")).containsExactly(
                "PERFIL", "APLICACOES", "MIDIA", "CORTESIAS", "CREDENCIAMENTO", "PESQUISA", "CONTRATO");
        assertThat(abas).allSatisfy(a -> assertThat(a).containsKeys("codigo", "nome", "ordem", "liberada"));
        assertThat(abas).as("Master libera a Pesquisa").anySatisfy(a ->
                assertThat(a).containsEntry("codigo", "PESQUISA").containsEntry("liberada", true));
    }

    @Test
    void loginAbreSessaoTrocaOIdEGravaOHistorico() {
        MockHttpSession sessaoAntiga = new MockHttpSession();
        String idAntigo = sessaoAntiga.getId();

        MvcTestResult resposta = entrar("marketing@amarelo.example", SENHA, sessaoAntiga);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(sessaoAntiga.getId()).as("a sessão que já existia ganha id novo (fixação de sessão)").isNotEqualTo(idAntigo);
        assertThat(sessaoAntiga.isInvalid()).isFalse();

        var linha = jdbc.sql("""
                        SELECT usuario_id, entidade, host(ip) AS ip, user_agent FROM historico
                        WHERE acao = 'LOGIN' AND usuario_id = (SELECT id FROM usuario WHERE email = 'marketing@amarelo.example')
                        ORDER BY id DESC LIMIT 1
                        """).query().singleRow();   // a amostra já tem LOGINs antigos; o último é o deste teste
        assertThat(linha.get("entidade")).isEqualTo("usuario");
        assertThat(linha.get("ip")).as("IP da requisição").isEqualTo("127.0.0.1");
        assertThat(linha.get("user_agent")).isEqualTo("TesteAutomatico/1.0");
    }

    @Test
    void loginDoAdminRbsDevolveParceriasVazio() {
        MvcTestResult resposta = entrar("b2b@rbs.example", SENHA, null);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.perfil")).isEqualTo("ADMIN_RBS");
        List<Object> parcerias = lerLista(resposta, "$.parcerias");
        assertThat(parcerias).isEmpty();
    }

    @Test
    void roxoSubcotaGTemAbaDePesquisaTrancada() {
        MvcTestResult resposta = entrar("marketing@roxo.example", SENHA, null);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.parcerias[0].cota.codigo")).isEqualTo("SUBCOTA_G");
        List<Map<String, Object>> abas = lerLista(resposta, "$.parcerias[0].abas");
        assertThat(abas).hasSize(7);
        assertThat(abas).anySatisfy(a -> assertThat(a).containsEntry("codigo", "PESQUISA").containsEntry("liberada", false));
        assertThat(abas).anySatisfy(a -> assertThat(a).containsEntry("codigo", "PERFIL").containsEntry("liberada", true));
    }

    // ---------------------------------------------------------------------
    // Login que não entra: sempre 401 com a mesma frase, e LOGIN_FALHOU no histórico
    // ---------------------------------------------------------------------

    @Test
    void senhaErradaDevolve401EGravaLoginFalhou() {
        long antes = historicoDoEmail("LOGIN_FALHOU", "marketing@amarelo.example");

        MvcTestResult resposta = entrar("marketing@amarelo.example", "senha-errada", null);

        assertThat(resposta).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(resposta.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("E-mail ou senha incorretos.");
        assertThat(lerJson(resposta, "$.status")).isEqualTo(401);
        assertThat(lerJson(resposta, "$.title")).isEqualTo("Não autenticado");
        assertThat(lerJson(resposta, "$.instance")).isEqualTo("/api/auth/login");
        assertThat(historicoDoEmail("LOGIN_FALHOU", "marketing@amarelo.example")).isEqualTo(antes + 1);

        // E-mail que existe: o histórico guarda também o id do usuário (para contar as tentativas).
        Long usuarioId = jdbc.sql("SELECT usuario_id FROM historico WHERE acao = 'LOGIN_FALHOU' AND detalhe = 'e-mail tentado: marketing@amarelo.example'")
                .query(Long.class).single();
        assertThat(usuarioId).isNotNull();
    }

    @Test
    void emailInexistenteDevolveAMesmaMensagemEGravaLoginFalhouSemUsuario() {
        MvcTestResult resposta = entrar("ninguem@exemplo.test", SENHA, null);

        assertThat(resposta).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("E-mail ou senha incorretos.");
        var linha = jdbc.sql("SELECT usuario_id, entidade_id FROM historico WHERE acao = 'LOGIN_FALHOU' AND detalhe = 'e-mail tentado: ninguem@exemplo.test'")
                .query().singleRow();
        assertThat(linha.get("usuario_id")).isNull();
        assertThat(linha.get("entidade_id")).isNull();
    }

    @Test
    void emailRevogadoNaoEntra() {
        // [PERMISSAO] A RBS revoga o e-mail do Roxo: o gatilho do banco apaga o vínculo com a parceria.
        jdbc.sql("""
                        UPDATE email_autorizado SET revogado_em = now(),
                               revogado_por = (SELECT id FROM usuario WHERE email = 'b2b@rbs.example')
                        WHERE email = 'marketing@roxo.example'
                        """).update();

        MvcTestResult resposta = entrar("marketing@roxo.example", SENHA, null);

        assertThat(resposta).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(lerJson(resposta, "$.detail")).as("a mesma frase dos outros casos").isEqualTo("E-mail ou senha incorretos.");
        assertThat(historicoDoEmail("LOGIN_FALHOU", "marketing@roxo.example")).isEqualTo(1);
    }

    @Test
    void usuarioDesativadoNaoEntra() {
        jdbc.sql("UPDATE usuario SET ativo = FALSE WHERE email = 'marketing@verde.example'").update();

        assertThat(entrar("marketing@verde.example", SENHA, null)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void emailComMaiusculasEEspacosNaBordaEntra() {
        assertThat(entrar("  Marketing@Amarelo.EXAMPLE ", SENHA, null)).hasStatus(HttpStatus.OK);
    }

    @Test
    void corpoInvalidoDevolve422ComCampos() {
        Csrf csrf = pedirCsrf();
        MvcTestResult resposta = mvc.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("X-XSRF-TOKEN", csrf.token()).cookie(csrf.cookie())
                .content("{\"email\":\"isto-nao-e-email\",\"senha\":\"\"}").exchange();

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(resposta.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(resposta, "$.title")).isEqualTo("Dados inválidos");
        assertThat(lerJson(resposta, "$.campos.email")).isEqualTo("E-mail inválido.");
        assertThat(lerJson(resposta, "$.campos.senha")).isEqualTo("Informe a senha.");
    }

    @Test
    void corpoQueNaoEJsonDevolve400() {
        Csrf csrf = pedirCsrf();
        MvcTestResult resposta = mvc.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("X-XSRF-TOKEN", csrf.token()).cookie(csrf.cookie()).content("isto nao e json").exchange();

        assertThat(resposta).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("O corpo da requisição está vazio ou não é um JSON válido.");
    }

    // ---------------------------------------------------------------------
    // CSRF
    // ---------------------------------------------------------------------

    @Test
    void postSemTokenCsrfERecusadoEmJson() {
        MvcTestResult resposta = mvc.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"marketing@amarelo.example\",\"senha\":\"" + SENHA + "\"}").exchange();

        assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(resposta.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(resposta, "$.title")).isEqualTo("Acesso negado");
        assertThat((String) lerJson(resposta, "$.detail")).contains("CSRF");
    }

    @Test
    void postComTokenErradoERecusado() {
        Csrf csrf = pedirCsrf();
        MvcTestResult resposta = mvc.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("X-XSRF-TOKEN", "token-inventado").cookie(csrf.cookie())
                .content("{\"email\":\"marketing@amarelo.example\",\"senha\":\"" + SENHA + "\"}").exchange();

        assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------------
    // GET /api/auth/eu e POST /api/auth/logout
    // ---------------------------------------------------------------------

    @Test
    void euSemSessaoDevolve401() {
        MvcTestResult resposta = mvc.get().uri("/api/auth/eu").exchange();

        assertThat(resposta).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(resposta.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(resposta, "$.title")).isEqualTo("Não autenticado");
        assertThat(lerJson(resposta, "$.instance")).isEqualTo("/api/auth/eu");
        assertThat(resposta.getResponse().getHeader("Location")).as("a API nunca redireciona para uma página de login").isNull();
    }

    @Test
    void euComSessaoDevolveOMesmoUsuarioLogado() {
        MockHttpSession sessao = new MockHttpSession();
        MvcTestResult login = entrar("marketing@amarelo.example", SENHA, sessao);
        assertThat(login).hasStatus(HttpStatus.OK);

        MvcTestResult eu = mvc.get().uri("/api/auth/eu").session(sessao).exchange();

        assertThat(eu).hasStatus(HttpStatus.OK);
        assertThat(corpo(eu)).isEqualTo(corpo(login));
    }

    @Test
    void logoutEncerraASessaoERegistraNoHistorico() {
        MockHttpSession sessao = new MockHttpSession();
        assertThat(entrar("marketing@amarelo.example", SENHA, sessao)).hasStatus(HttpStatus.OK);

        MvcTestResult resposta = sair(sessao);

        assertThat(resposta).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(sessao.isInvalid()).as("a sessão foi invalidada").isTrue();
        assertThat(mvc.get().uri("/api/auth/eu").session(sessao).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        long logouts = jdbc.sql("SELECT count(*) FROM historico WHERE acao = 'LOGOUT' AND usuario_id = "
                + "(SELECT id FROM usuario WHERE email = 'marketing@amarelo.example')").query(Long.class).single();
        assertThat(logouts).isEqualTo(1);
    }

    @Test
    void logoutExigeTokenCsrf() {
        MockHttpSession sessao = new MockHttpSession();
        assertThat(entrar("marketing@amarelo.example", SENHA, sessao)).hasStatus(HttpStatus.OK);

        assertThat(mvc.post().uri("/api/auth/logout").session(sessao).exchange()).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri("/api/auth/eu").session(sessao).exchange()).as("a sessão continua valendo").hasStatus(HttpStatus.OK);
    }

    @Test
    void revogacaoDerrubaQuemJaEstavaLogado() {
        MockHttpSession sessao = new MockHttpSession();
        assertThat(entrar("marketing@verde.example", SENHA, sessao)).hasStatus(HttpStatus.OK);

        jdbc.sql("""
                        UPDATE email_autorizado SET revogado_em = now(),
                               revogado_por = (SELECT id FROM usuario WHERE email = 'b2b@rbs.example')
                        WHERE email = 'marketing@verde.example'
                        """).update();

        MvcTestResult eu = mvc.get().uri("/api/auth/eu").session(sessao).exchange();
        assertThat(eu).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(sessao.isInvalid()).as("a sessão foi encerrada na hora").isTrue();
    }
}
