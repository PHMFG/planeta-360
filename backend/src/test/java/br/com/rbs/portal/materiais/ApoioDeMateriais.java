package br.com.rbs.portal.materiais;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.ApoioDeTeste;
import br.com.rbs.portal.ApoioDeTeste.Csrf;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.comum.Armazenamento;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Ajudas dos testes das rotas 14 a 22: entrar pela rota de login, criar entregas e enviar arquivos como o front faz
 * (multipart com sessão e token CSRF). Atende o item 4 da Entrega 2. Não faz parte da API.
 */
abstract class ApoioDeMateriais {

    static final String RBS = "b2b@rbs.example";
    static final String AMARELO = "marketing@amarelo.example";
    static final String ROXO = "marketing@roxo.example";
    static final String AGENTE = "TesteDeMateriais/1.0";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Armazenamento armazenamento;

    SessaoDeApi entrar(String email) {
        return SessaoDeApi.entrar(mvc, email);
    }

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    long roxo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Roxo");
    }

    long verde() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Verde");
    }

    long usuarioId(String email) {
        return jdbc.sql("SELECT id FROM usuario WHERE email = :email").param("email", email).query(Long.class).single();
    }

    static long id(MvcTestResult resposta) {
        return ((Number) lerJson(resposta, "$.id")).longValue();
    }

    /** Um PDF mínimo: começa com %PDF- (é o que o servidor confere); o resto é só para o arquivo ter o tamanho que o teste quer. */
    static byte[] pdf(String texto) {
        return ("%PDF-1.4\n" + texto + "\n%%EOF").getBytes(StandardCharsets.UTF_8);
    }

    /** A RBS cria uma entrega (rota 15) e o teste confere o 201; devolve o id. */
    long criarEntrega(SessaoDeApi rbs, long parceriaId, String aba, String titulo) {
        MvcTestResult resposta = rbs.post("/api/parcerias/" + parceriaId + "/entregas",
                "{\"abaCodigo\":\"%s\",\"titulo\":\"%s\"}".formatted(aba, titulo));
        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        return id(resposta);
    }

    /** Põe a entrega num status direto no banco, para montar o cenário sem passar pelas regras de transição. */
    void definirStatus(long entregaId, String status) {
        jdbc.sql("UPDATE entrega SET status = :status WHERE id = :id").param("status", status).param("id", entregaId).update();
    }

    long entregaDaAmostra(long parceriaId, String titulo) {
        return jdbc.sql("SELECT id FROM entrega WHERE parceria_id = :p AND titulo = :t").param("p", parceriaId).param("t", titulo)
                .query(Long.class).single();
    }

    String statusDaEntrega(long entregaId) {
        return jdbc.sql("SELECT status FROM entrega WHERE id = :id").param("id", entregaId).query(String.class).single();
    }

    long contar(String sql, Object... parametros) {
        var consulta = jdbc.sql(sql);
        for (Object parametro : parametros) {
            consulta = consulta.param(parametro);
        }
        return consulta.query(Long.class).single();
    }

    /** As linhas do histórico de uma ação sobre uma entidade, da mais antiga para a mais nova. */
    List<Map<String, Object>> historico(String acao, String entidade, long entidadeId) {
        return jdbc.sql("""
                        SELECT usuario_id, parceria_id, valor_anterior, valor_novo, detalhe, host(ip) AS ip, user_agent
                        FROM historico WHERE acao = :acao AND entidade = :entidade AND entidade_id = :id ORDER BY id
                        """)
                .param("acao", acao).param("entidade", entidade).param("id", entidadeId).query().listOfRows();
    }

    MvcTestResult enviar(SessaoDeApi pessoa, long entregaId, String nome, byte[] bytes) {
        return enviar(pessoa, entregaId, nome, bytes, null, null);
    }

    /** POST multipart como o front faz: cookie de sessão, cookie XSRF-TOKEN e cabeçalho X-XSRF-TOKEN. */
    MvcTestResult enviar(SessaoDeApi pessoa, long entregaId, String nome, byte[] bytes, String categoria, String observacao) {
        var pedido = pedidoDeEnvio(pessoa, entregaId, true).file(new MockMultipartFile("arquivo", nome, "application/octet-stream", bytes));
        if (categoria != null) {
            pedido.param("categoria", categoria);
        }
        if (observacao != null) {
            pedido.param("observacao", observacao);
        }
        return pedido.exchange();
    }

    /** O mesmo envio, mas sem a parte "arquivo" (para provar o 422). */
    MvcTestResult enviarSemArquivo(SessaoDeApi pessoa, long entregaId) {
        return pedidoDeEnvio(pessoa, entregaId, true).exchange();
    }

    /** O mesmo envio, mas sem o cabeçalho X-XSRF-TOKEN (a API tem de recusar com 403). */
    MvcTestResult enviarSemCsrf(SessaoDeApi pessoa, long entregaId, String nome, byte[] bytes) {
        return pedidoDeEnvio(pessoa, entregaId, false).file(new MockMultipartFile("arquivo", nome, "application/pdf", bytes)).exchange();
    }

    private org.springframework.test.web.servlet.assertj.MockMvcTester.MockMultipartMvcRequestBuilder pedidoDeEnvio(
            SessaoDeApi pessoa, long entregaId, boolean comCsrf) {
        Csrf csrf = ApoioDeTeste.pedirCsrf(mvc);
        var pedido = mvc.post().uri("/api/entregas/{id}/arquivos", entregaId).multipart()
                .header("User-Agent", AGENTE).cookie(csrf.cookie()).session(pessoa.sessao());
        return comCsrf ? pedido.header("X-XSRF-TOKEN", csrf.token()) : pedido;
    }
}
