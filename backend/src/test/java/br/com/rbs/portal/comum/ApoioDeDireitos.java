package br.com.rbs.portal.comum;

import br.com.rbs.portal.ApoioDeTeste;
import br.com.rbs.portal.ApoioDeTeste.Csrf;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Ajudas dos testes do contrato e do credenciamento (rotas 74 a 79): entrar, achar as parcerias da amostra, abrir e fechar abas,
 * encerrar a parceria e enviar o PDF do contrato como o front faz (multipart com sessão e token CSRF). Não faz parte da API.
 */
public abstract class ApoioDeDireitos {

    protected static final String RBS = "b2b@rbs.example";
    protected static final String AMARELO = "marketing@amarelo.example";
    protected static final String ROXO = "marketing@roxo.example";
    protected static final String FECHADA = "Esta aba ainda não foi liberada para a sua parceria.";

    @Autowired
    protected MockMvcTester mvc;

    @Autowired
    protected JdbcClient jdbc;

    protected SessaoDeApi entrar(String email) {
        return SessaoDeApi.entrar(mvc, email);
    }

    protected long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    protected long roxo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Roxo");
    }

    protected long eventoDe(long parceriaId) {
        return jdbc.sql("SELECT evento_id FROM parceria WHERE id = :id").param("id", parceriaId).query(Long.class).single();
    }

    /** Um PDF mínimo: começa com %PDF- (é o que o servidor confere). */
    protected static byte[] pdf(String texto) {
        return ("%PDF-1.4\n" + texto + "\n%%EOF").getBytes(StandardCharsets.UTF_8);
    }

    protected void fechar(long parceriaId, String aba) {
        jdbc.sql("UPDATE parceria_aba SET liberada = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = :a)")
                .param("p", parceriaId).param("a", aba).update();
    }

    protected void esconder(long parceriaId, String aba) {
        jdbc.sql("UPDATE parceria_aba SET visivel = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = :a)")
                .param("p", parceriaId).param("a", aba).update();
    }

    protected void encerrar(long parceriaId) {
        jdbc.sql("UPDATE parceria SET situacao = 'ENCERRADA' WHERE id = :id").param("id", parceriaId).update();
    }

    protected long contar(String sql, Object... parametros) {
        var consulta = jdbc.sql(sql);
        for (Object parametro : parametros) {
            consulta = consulta.param(parametro);
        }
        return consulta.query(Long.class).single();
    }

    /** POST multipart da rota 76: o arquivo (se houver), a data da assinatura e a observação (se houver). */
    protected MvcTestResult enviarContrato(SessaoDeApi pessoa, long parceriaId, String nome, byte[] bytes, String assinadoEm, String observacao) {
        return pedirContrato(pessoa, parceriaId, true, nome, bytes, assinadoEm, observacao);
    }

    protected MvcTestResult enviarContratoSemCsrf(SessaoDeApi pessoa, long parceriaId, String nome, byte[] bytes) {
        return pedirContrato(pessoa, parceriaId, false, nome, bytes, null, null);
    }

    private MvcTestResult pedirContrato(SessaoDeApi pessoa, long parceriaId, boolean comCsrf, String nome, byte[] bytes, String assinadoEm,
                                        String observacao) {
        Csrf csrf = ApoioDeTeste.pedirCsrf(mvc);
        var pedido = mvc.post().uri("/api/parcerias/{id}/contrato/arquivo", parceriaId).multipart()
                .header("User-Agent", "TesteDeDireitos/1.0").cookie(csrf.cookie()).session(pessoa.sessao());
        if (comCsrf) {
            pedido = pedido.header("X-XSRF-TOKEN", csrf.token());
        }
        if (bytes != null) {
            pedido = pedido.file(new MockMultipartFile("arquivo", nome, "application/octet-stream", bytes));
        }
        if (assinadoEm != null) {
            pedido.param("assinadoEm", assinadoEm);
        }
        if (observacao != null) {
            pedido.param("observacao", observacao);
        }
        return pedido.exchange();
    }
}
