package br.com.rbs.portal.acesso;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.ApoioDeTeste;
import br.com.rbs.portal.ApoioDeTeste.Csrf;
import br.com.rbs.portal.TesteDeIntegracao;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [PERMISSAO] Acesso revogado ou conta desativada perde a sessão em QUALQUER rota, não só em GET /api/auth/eu.
 * Atende o item 3 da Entrega 2 (contrato: a sessão é encerrada na hora). Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class SessaoRevogadaTest {

    private static final String SESSAO_ENCERRADA = "Sua sessão terminou. Entre de novo.";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    void revogar(String email) {
        jdbc.sql("""
                        UPDATE email_autorizado SET revogado_em = now(),
                               revogado_por = (SELECT id FROM usuario WHERE email = 'b2b@rbs.example')
                        WHERE email = :email
                        """).param("email", email).update();
    }

    void desativar(String email) {
        jdbc.sql("UPDATE usuario SET ativo = FALSE WHERE email = :email").param("email", email).update();
    }

    void confereSessaoEncerrada(MvcTestResult resposta, String instancia) {
        assertThat(resposta).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(resposta.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(resposta, "$.title")).isEqualTo("Não autenticado");
        assertThat(lerJson(resposta, "$.detail")).isEqualTo(SESSAO_ENCERRADA);
        assertThat(lerJson(resposta, "$.instance")).isEqualTo(instancia);
    }

    /** Uma chamada por tipo de rota: leitura da parceria, listas, escrita com CSRF, rota só da RBS e a de outro módulo. */
    Map<String, Function<SessaoDeApi, MvcTestResult>> chamadas(long parceriaId) {
        Map<String, Function<SessaoDeApi, MvcTestResult>> chamadas = new LinkedHashMap<>();
        chamadas.put("/api/parcerias/" + parceriaId, s -> s.get("/api/parcerias/{id}", parceriaId));
        chamadas.put("/api/parcerias", s -> s.get("/api/parcerias"));
        chamadas.put("/api/eventos", s -> s.get("/api/eventos"));
        chamadas.put("/api/parcerias/" + parceriaId + "/entregas", s -> s.get("/api/parcerias/{id}/entregas", parceriaId));
        chamadas.put("/api/parcerias/" + parceriaId + "/historico", s -> s.get("/api/parcerias/{id}/historico", parceriaId));
        chamadas.put("/api/parcerias/" + parceriaId + "/pesquisa", s -> s.get("/api/parcerias/{id}/pesquisa", parceriaId));
        chamadas.put("/api/parcerias/" + parceriaId + "/emails", s -> s.get("/api/parcerias/{id}/emails", parceriaId));
        chamadas.put("/api/parcerias/" + parceriaId + "/pesquisa/perguntas",
                s -> s.put("/api/parcerias/" + parceriaId + "/pesquisa/perguntas",
                        "{\"perguntas\":[{\"ordem\":1,\"texto\":\"Você visitou o espaço da marca?\"}]}"));
        return chamadas;
    }

    @Test
    void revogacaoDerrubaASessaoEmQualquerRota() {
        long verde = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Verde");
        var chamadas = chamadas(verde);
        Map<String, SessaoDeApi> sessoes = new LinkedHashMap<>();
        chamadas.keySet().forEach(rota -> sessoes.put(rota, SessaoDeApi.entrar(mvc, "marketing@verde.example")));

        revogar("marketing@verde.example");

        chamadas.forEach((rota, chamada) -> {
            SessaoDeApi sessao = sessoes.get(rota);
            confereSessaoEncerrada(chamada.apply(sessao), rota);
            assertThat(sessao.sessao().isInvalid()).as("a sessão de " + rota + " foi encerrada na hora").isTrue();
        });
    }

    @Test
    void contaDesativadaDerrubaASessaoDoPatrocinador() {
        long amarelo = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        SessaoDeApi sessao = SessaoDeApi.entrar(mvc, "marketing@amarelo.example");

        desativar("marketing@amarelo.example");

        confereSessaoEncerrada(sessao.get("/api/parcerias/{id}", amarelo), "/api/parcerias/" + amarelo);
        assertThat(sessao.sessao().isInvalid()).isTrue();
    }

    @Test
    void contaDesativadaDaRbsTambemPerdeASessao() {
        SessaoDeApi sessao = SessaoDeApi.entrar(mvc, "pesquisa@rbs.example");
        assertThat(sessao.get("/api/parcerias")).hasStatus(HttpStatus.OK);

        desativar("pesquisa@rbs.example");

        confereSessaoEncerrada(sessao.get("/api/parcerias"), "/api/parcerias");
        assertThat(sessao.sessao().isInvalid()).isTrue();
    }

    @Test
    void escritaComCsrfDeSessaoRevogadaTambemDevolve401() {
        SessaoDeApi sessao = SessaoDeApi.entrar(mvc, "marketing@verde.example");
        revogar("marketing@verde.example");

        MvcTestResult resposta = sessao.patch("/api/entregas/1/status", "{\"status\":\"APROVADO\"}");

        confereSessaoEncerrada(resposta, "/api/entregas/1/status");
    }

    @Test
    void contaQueAindaValeContinuaNormalmente() {
        long amarelo = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        SessaoDeApi sessao = SessaoDeApi.entrar(mvc, "marketing@amarelo.example");
        revogar("marketing@verde.example");   // a revogação de OUTRA pessoa não mexe nesta sessão

        assertThat(sessao.get("/api/parcerias/{id}", amarelo)).hasStatus(HttpStatus.OK);
        assertThat(sessao.get("/api/parcerias")).hasStatus(HttpStatus.OK);
        assertThat(sessao.get("/api/auth/eu")).hasStatus(HttpStatus.OK);
        assertThat(sessao.sessao().isInvalid()).isFalse();
    }

    @Test
    void quemAindaTemOutraParceriaSoPerdeAParceriaRevogada() {
        // A agência do Amarelo também é autorizada no Verde. Ao perder o Amarelo, a sessão continua: só aquela parceria some.
        String agencia = "atendimento@agencia-x.example";
        long amarelo = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        long verde = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Verde");
        long usuario = jdbc.sql("SELECT id FROM usuario WHERE email = :e").param("e", agencia).query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO email_autorizado (parceria_id, email, vinculo, autorizado_por)
                        VALUES (:p, :e, 'AGENCIA', (SELECT id FROM usuario WHERE email = 'b2b@rbs.example'))
                        """).param("p", verde).param("e", agencia).update();
        jdbc.sql("INSERT INTO parceria_usuario (parceria_id, usuario_id, vinculo) VALUES (:p, :u, 'AGENCIA')")
                .param("p", verde).param("u", usuario).update();
        SessaoDeApi sessao = SessaoDeApi.entrar(mvc, agencia);
        assertThat(sessao.get("/api/parcerias/{id}", amarelo)).hasStatus(HttpStatus.OK);

        jdbc.sql("""
                        UPDATE email_autorizado SET revogado_em = now(),
                               revogado_por = (SELECT id FROM usuario WHERE email = 'b2b@rbs.example')
                        WHERE email = :e AND parceria_id = :p
                        """).param("e", agencia).param("p", amarelo).update();

        assertThat(sessao.get("/api/parcerias/{id}", amarelo)).as("a parceria revogada some").hasStatus(HttpStatus.NOT_FOUND);
        assertThat(sessao.get("/api/parcerias/{id}", verde)).as("a outra continua").hasStatus(HttpStatus.OK);
        assertThat(sessao.get("/api/auth/eu")).hasStatus(HttpStatus.OK);
        assertThat(sessao.sessao().isInvalid()).isFalse();
    }

    @Test
    void rotasPublicasContinuamAbertasParaQuemTemSessaoRevogada() {
        // Quem perdeu o acesso precisa conseguir pedir o CSRF e entrar de novo (com outra conta) sem cair num 401 de sessão.
        SessaoDeApi sessao = SessaoDeApi.entrar(mvc, "marketing@verde.example");
        revogar("marketing@verde.example");

        assertThat(sessao.get("/api/auth/csrf")).hasStatus(HttpStatus.OK);

        Csrf csrf = ApoioDeTeste.pedirCsrf(mvc);
        MvcTestResult login = mvc.post().uri("/api/auth/login").session(sessao.sessao()).cookie(csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"marketing@amarelo.example\",\"senha\":\"" + SessaoDeApi.SENHA + "\"}").exchange();
        assertThat(login).hasStatus(HttpStatus.OK);
        assertThat(corpo(login)).contains("marketing@amarelo.example");
    }

    @Test
    void logoutDeSessaoRevogadaResponde204() {
        SessaoDeApi sessao = SessaoDeApi.entrar(mvc, "marketing@verde.example");
        revogar("marketing@verde.example");

        assertThat(sessao.post("/api/auth/logout", null)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void euDeSessaoRevogadaContinuaDevolvendo401ComAMesmaFrase() {
        SessaoDeApi sessao = SessaoDeApi.entrar(mvc, "marketing@verde.example");
        revogar("marketing@verde.example");

        confereSessaoEncerrada(sessao.get("/api/auth/eu"), "/api/auth/eu");
    }
}
