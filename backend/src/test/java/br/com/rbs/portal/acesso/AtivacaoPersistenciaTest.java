package br.com.rbs.portal.acesso;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Prova que o erro de digitação do código fica contado de verdade no banco, mesmo com a resposta sendo 422. Atende o item 3
 * da Entrega 2. [PERMISSAO] Sem @Transactional de propósito: aqui cada chamada confirma a sua transação, como na API real.
 * (Nos outros testes tudo roda numa transação só, que esconderia um rollback indevido.) Os dados nascem e somem por SQL.
 * Só há tentativas que falham: uma ativação que desse certo gravaria no histórico, que não permite apagar linhas.
 */
@TesteDeIntegracao
class AtivacaoPersistenciaTest {

    private static final String EMAIL = "persistencia@teste.example";
    private static final String CODIGO = "ABCD2345";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void criarOsDados() throws Exception {
        long patrocinador = jdbc.sql("INSERT INTO patrocinador (nome_canonico) VALUES ('Cliente Persistência') RETURNING id")
                .query(Long.class).single();
        long parceria = jdbc.sql("""
                        INSERT INTO parceria (evento_id, patrocinador_id, cota_tipo_id)
                        VALUES ((SELECT id FROM evento WHERE edicao = 2027), :p, 3) RETURNING id
                        """).param("p", patrocinador).query(Long.class).single();
        long email = jdbc.sql("""
                        INSERT INTO email_autorizado (parceria_id, email, vinculo, autorizado_por)
                        VALUES (:p, :e, 'MARCA', (SELECT id FROM usuario WHERE email = 'b2b@rbs.example')) RETURNING id
                        """).param("p", parceria).param("e", EMAIL).query(Long.class).single();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CODIGO.getBytes(StandardCharsets.UTF_8)));
        jdbc.sql("INSERT INTO codigo_ativacao (email_autorizado_id, codigo_hash, expira_em) VALUES (:e, :h, now() + interval '7 days')")
                .param("e", email).param("h", hash).update();
    }

    @AfterEach
    void apagarOsDados() {
        // A parceria leva junto os e-mails e os códigos (ON DELETE CASCADE). Nenhum usuário nem histórico foi criado.
        jdbc.sql("DELETE FROM parceria WHERE patrocinador_id IN (SELECT id FROM patrocinador WHERE nome_canonico = 'Cliente Persistência')").update();
        jdbc.sql("DELETE FROM patrocinador WHERE nome_canonico = 'Cliente Persistência'").update();
    }

    int tentativas() {
        return jdbc.sql("""
                        SELECT ca.tentativas FROM codigo_ativacao ca JOIN email_autorizado ea ON ea.id = ca.email_autorizado_id
                        WHERE ea.email = :e
                        """).param("e", EMAIL).query(Integer.class).single();
    }

    @Test
    void errosDeCodigoFicamContadosEOLimiteBloqueiaDeVerdade() {
        SessaoDeApi anonima = SessaoDeApi.anonima(mvc);
        String corpoErrado = "{\"email\":\"" + EMAIL + "\",\"codigo\":\"ZZZZ-9999\",\"senha\":\"senha123\"}";

        for (int erro = 1; erro <= 5; erro++) {
            assertThat(anonima.post("/api/auth/ativar", corpoErrado)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(tentativas()).as("depois do erro %d, já confirmado no banco", erro).isEqualTo(erro);
        }

        assertThat(anonima.post("/api/auth/ativar", "{\"email\":\"" + EMAIL + "\",\"codigo\":\"ABCD-2345\",\"senha\":\"senha123\"}"))
                .as("o código certo, mas já bloqueado").hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(jdbc.sql("SELECT count(*) FROM usuario WHERE email = :e").param("e", EMAIL).query(Long.class).single()).isZero();
    }
}
