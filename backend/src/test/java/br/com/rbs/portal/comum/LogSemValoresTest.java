package br.com.rbs.portal.comum;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import br.com.rbs.portal.TesteDeIntegracao;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * O log não pode guardar os valores de uma linha que o banco recusou. A mensagem do PostgreSQL numa violação cita a linha
 * ("Key (...)=(valor) already exists"), então o TratadorDeErros registra só o SQLSTATE e o nome da restrição.
 *
 * O teste: (1) provoca a violação por JDBC direto, sem service, e prova que o PostgreSQL de fato devolve o canário na mensagem;
 * (2) controle positivo: o ListAppender captura o canário quando ele é logado de propósito; (3) passa a mesma violação pelo
 * TratadorDeErros, como uma requisição faria, e exige que o canário não esteja em nenhum evento de log, mas que o nome da
 * restrição esteja. Sem @Transactional, como o TratadorDeErrosTest: a recusa aborta a transação.
 */
@TesteDeIntegracao
class LogSemValoresTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    private final Logger raiz = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final Logger doTratador = (Logger) LoggerFactory.getLogger(TratadorDeErros.class);
    private ListAppender<ILoggingEvent> captura;
    private Level nivelAnterior;

    @BeforeEach
    void ligarCaptura() {
        captura = new ListAppender<>();
        captura.start();
        raiz.addAppender(captura);
        nivelAnterior = doTratador.getLevel();
        doTratador.setLevel(Level.INFO);
    }

    @AfterEach
    void desligarCaptura() {
        raiz.detachAppender(captura);
        doTratador.setLevel(nivelAnterior);
    }

    private static String canario() {
        return "canario-" + UUID.randomUUID();
    }

    private boolean logouCanario(String canario) {
        return captura.list.stream().anyMatch(e -> e.getFormattedMessage().contains(canario)
                || (e.getThrowableProxy() != null && e.getThrowableProxy().getMessage() != null
                        && e.getThrowableProxy().getMessage().contains(canario)));
    }

    RequestPostProcessor comoRbs() {
        var u = jdbc.sql("SELECT id, nome, perfil FROM usuario WHERE email = 'b2b@rbs.example'").query().singleRow();
        Perfil perfil = Perfil.valueOf((String) u.get("perfil"));
        var principal = new UsuarioAutenticado(((Number) u.get("id")).longValue(), (String) u.get("nome"), "b2b@rbs.example", perfil);
        return authentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + perfil.name()))));
    }

    private static void inserir(Connection c, String nome) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO patrocinador (nome_canonico) VALUES (?)")) {
            ps.setString(1, nome);
            ps.executeUpdate();
        }
    }

    @Test
    void oPostgresCitaOValorNaMensagemDaViolacao() throws SQLException {
        // Por JDBC direto, sem service nem Spring: é isto que o log guardaria se gravasse a mensagem.
        String canario = canario();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                inserir(c, canario);
                assertThatThrownBy(() -> inserir(c, canario)).isInstanceOfSatisfying(SQLException.class, e -> {
                    assertThat(e.getSQLState()).isEqualTo("23505");
                    assertThat(e.getMessage()).as("a mensagem do PostgreSQL traz o valor da linha").contains(canario);
                });
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void controlePositivoOAppenderCapturaOCanario() {
        String canario = canario();
        LoggerFactory.getLogger(TratadorDeErros.class).info("registro de propósito: {}", canario);

        assertThat(logouCanario(canario)).as("sem esta prova, 'não achou' não quereria dizer nada").isTrue();
    }

    @Test
    void violacaoPeloTratadorNaoLevaOValorAoLogNemAResposta() {
        String canario = canario();

        MvcTestResult resposta = mvc.get().uri("/api/teste/violacao-com-valor").queryParam("valor", canario).with(comoRbs()).exchange();

        assertThat(resposta).hasStatus(409);
        assertThat(corpo(resposta)).doesNotContain(canario);
        assertThat(captura.list).as("o tratador registrou a violação com o SQLSTATE e a restrição")
                .anyMatch(e -> e.getFormattedMessage().contains("23505") && e.getFormattedMessage().contains("uq_patrocinador_nome"));
        assertThat(logouCanario(canario)).as("o valor da linha não pode estar em nenhum log").isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM patrocinador WHERE nome_canonico = :n").param("n", canario).query(Long.class).single())
                .as("a transação da rota desfez a primeira gravação").isZero();
    }
}
