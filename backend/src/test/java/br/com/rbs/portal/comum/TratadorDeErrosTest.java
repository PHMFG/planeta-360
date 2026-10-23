package br.com.rbs.portal.comum;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import br.com.rbs.portal.ApoioDeTeste;
import br.com.rbs.portal.ApoioDeTeste.Csrf;
import br.com.rbs.portal.TesteDeIntegracao;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Confere o formato de cada erro da API (ProblemDetail em português) provocando-os pelas rotas só de teste.
 * Atende o item 3 da Entrega 2. [VALIDACAO] Sem @Transactional de propósito: a recusa do banco aborta a transação,
 * e aqui cada comando precisa terminar sozinho. Nenhum teste deixa dado gravado, e ninguém faz login (não grava histórico).
 */
@TesteDeIntegracao
class TratadorDeErrosTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    /** Entra como alguém sem passar pelo login, para não gravar LOGIN no histórico. */
    RequestPostProcessor como(String email) {
        var u = jdbc.sql("SELECT id, nome, perfil FROM usuario WHERE email = :email").param("email", email).query().singleRow();
        Perfil perfil = Perfil.valueOf((String) u.get("perfil"));
        var principal = new UsuarioAutenticado(((Number) u.get("id")).longValue(), (String) u.get("nome"), email, perfil);
        return authentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + perfil.name()))));
    }

    void confereProblema(MvcTestResult resposta, HttpStatus status, String titulo, String instancia) {
        assertThat(resposta).hasStatus(status);
        assertThat(resposta.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(resposta, "$.type")).isEqualTo("about:blank");
        assertThat(lerJson(resposta, "$.status")).isEqualTo(status.value());
        assertThat(lerJson(resposta, "$.title")).isEqualTo(titulo);
        assertThat(lerJson(resposta, "$.instance")).isEqualTo(instancia);
        assertThat((String) lerJson(resposta, "$.detail")).isNotBlank();
    }

    @Test
    void gatilhoDoBancoVira409ComAMensagemDoGatilho() {
        MvcTestResult resposta = mvc.get().uri("/api/teste/gatilho").with(como("b2b@rbs.example")).exchange();

        confereProblema(resposta, HttpStatus.CONFLICT, "Conflito", "/api/teste/gatilho");
        assertThat(lerJson(resposta, "$.detail"))
                .isEqualTo("O e-mail deste usuário não está autorizado nesta parceria (ou a autorização foi revogada).");
    }

    @Test
    void regraUnicaNativaVira409SemVazarOTextoDoPostgres() {
        MvcTestResult resposta = mvc.get().uri("/api/teste/unico").with(como("b2b@rbs.example")).exchange();

        confereProblema(resposta, HttpStatus.CONFLICT, "Conflito", "/api/teste/unico");
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("Já existe um patrocinador com este nome.");
        assertThat(corpo(resposta)).doesNotContain("duplicate key").doesNotContain("uq_patrocinador_nome").doesNotContain("violates");
    }

    @Test
    void chaveEstrangeiraVira409ComFraseGenerica() {
        MvcTestResult resposta = mvc.get().uri("/api/teste/chave").with(como("b2b@rbs.example")).exchange();

        confereProblema(resposta, HttpStatus.CONFLICT, "Conflito", "/api/teste/chave");
        assertThat(corpo(resposta)).doesNotContain("foreign key").doesNotContain("violates").doesNotContain("fkey");
    }

    @Test
    void erroInesperadoVira500GenericoSemVazarNada() {
        MvcTestResult resposta = mvc.get().uri("/api/teste/inesperado").with(como("b2b@rbs.example")).exchange();

        confereProblema(resposta, HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno", "/api/teste/inesperado");
        assertThat(corpo(resposta)).doesNotContain("jdbc").doesNotContain("senha").doesNotContain("IllegalState")
                .doesNotContain("br.com.rbs").doesNotContain("at ");
    }

    @Test
    void parceriaDeOutroEParceriaInexistenteDevolvemO404Igual() {
        var roxo = como("marketing@roxo.example");
        long amarelo = jdbc.sql("SELECT min(id) FROM parceria").query(Long.class).single();

        MvcTestResult deOutro = mvc.get().uri("/api/teste/parceria/" + amarelo).with(roxo).exchange();
        MvcTestResult inexistente = mvc.get().uri("/api/teste/parceria/999999").with(roxo).exchange();

        confereProblema(deOutro, HttpStatus.NOT_FOUND, "Não encontrado", "/api/teste/parceria/" + amarelo);
        confereProblema(inexistente, HttpStatus.NOT_FOUND, "Não encontrado", "/api/teste/parceria/999999");
        assertThat((String) lerJson(deOutro, "$.detail")).isEqualTo(lerJson(inexistente, "$.detail"));
    }

    @Test
    void naoEncontradoSemMensagemUsaOTextoPadrao() {
        MvcTestResult resposta = mvc.get().uri("/api/teste/inexistente").with(como("b2b@rbs.example")).exchange();

        confereProblema(resposta, HttpStatus.NOT_FOUND, "Não encontrado", "/api/teste/inexistente");
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("Não encontramos o que você pediu.");
    }

    @Test
    void rotaDaRbsParaPatrocinadorDevolve403() {
        MvcTestResult patrocinador = mvc.get().uri("/api/teste/so-rbs").with(como("marketing@amarelo.example")).exchange();
        MvcTestResult rbs = mvc.get().uri("/api/teste/so-rbs").with(como("b2b@rbs.example")).exchange();

        confereProblema(patrocinador, HttpStatus.FORBIDDEN, "Acesso negado", "/api/teste/so-rbs");
        assertThat(lerJson(patrocinador, "$.detail")).isEqualTo("Esta operação é restrita à equipe da RBS.");
        assertThat(rbs).hasStatus(HttpStatus.OK);
    }

    @Test
    void excecaoDoSpringSecurityDentroDoControllerVira403NaoVira500() {
        MvcTestResult resposta = mvc.get().uri("/api/teste/negado").with(como("b2b@rbs.example")).exchange();

        confereProblema(resposta, HttpStatus.FORBIDDEN, "Acesso negado", "/api/teste/negado");
        assertThat(corpo(resposta)).doesNotContain("lançada dentro do controller");
    }

    @Test
    void validacaoDoCorpoVira422ComCampos() {
        Csrf csrf = ApoioDeTeste.pedirCsrf(mvc);
        MvcTestResult resposta = mvc.post().uri("/api/teste/validacao").with(como("b2b@rbs.example"))
                .contentType(MediaType.APPLICATION_JSON).header("X-XSRF-TOKEN", csrf.token()).cookie(csrf.cookie())
                .content("{\"nome\":\"  \"}").exchange();

        confereProblema(resposta, HttpStatus.UNPROCESSABLE_CONTENT, "Dados inválidos", "/api/teste/validacao");
        assertThat(lerJson(resposta, "$.campos.nome")).isEqualTo("Informe o nome.");
    }

    @Test
    void validacaoDeParametroVira422ComCampos() {
        MvcTestResult resposta = mvc.get().uri("/api/teste/parametro?aba=minuscula").with(como("b2b@rbs.example")).exchange();

        confereProblema(resposta, HttpStatus.UNPROCESSABLE_CONTENT, "Dados inválidos", "/api/teste/parametro");
        assertThat(lerJson(resposta, "$.campos.aba")).isEqualTo("Use só letras maiúsculas.");
    }

    @Test
    void rotaInexistenteEMetodoErradoSaemEmPortugues() {
        var rbs = como("b2b@rbs.example");

        MvcTestResult semRota = mvc.get().uri("/api/nao-existe").with(rbs).exchange();
        confereProblema(semRota, HttpStatus.NOT_FOUND, "Não encontrado", "/api/nao-existe");
        assertThat(lerJson(semRota, "$.detail")).isEqualTo("Não encontramos o que você pediu.");

        MvcTestResult metodo = mvc.get().uri("/api/auth/login").with(rbs).exchange();
        confereProblema(metodo, HttpStatus.METHOD_NOT_ALLOWED, "Método não permitido", "/api/auth/login");
    }

    @Test
    void rotaDeForaDeApiENegadaEmJson() {
        MvcTestResult resposta = mvc.get().uri("/qualquer-coisa").exchange();

        confereProblema(resposta, HttpStatus.UNAUTHORIZED, "Não autenticado", "/qualquer-coisa");
    }
}
