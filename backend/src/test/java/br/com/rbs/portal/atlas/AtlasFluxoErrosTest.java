package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.loop.TetoDiario;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] A resposta em fluxo das rotas 44 e 46: tudo o que dá erro dá o MESMO erro HTTP antes de o fluxo abrir, em ProblemDetail
 * (JSON), com {@code Accept: text/event-stream} sozinho e com {@code text/event-stream, application/json} (o que o front manda):
 * 403 da RBS, 404 da conversa e do cartão alheios, 409 do turno e do cartão já respondido, 422 do texto e dos valores do cartão,
 * 429 do teto. Nenhum desses chama o modelo. Os erros acontecem na thread do pedido, então o teste pode ser desfeito no fim
 * (@Transactional); o fluxo de verdade, em outra thread, está no AtlasFluxoTest.
 */
@TesteComLlmRoteirizado
@Transactional
class AtlasFluxoErrosTest {

    private static final String AMARELO = "marketing@amarelo.example";
    private static final String AGENCIA = "atendimento@agencia-x.example";
    private static final String RBS = "b2b@rbs.example";
    private static final String ACCEPT = "{\"acao\":\"accept\",\"valores\":[{\"chave\":\"ativacao.visitantes_total\",\"valor\":1200},"
            + "{\"chave\":\"ativacao.cadastros_consentidos\",\"valor\":1072}]}";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProvedorRoteirizado modelo;

    @BeforeEach
    void zerarORoteiro() {
        modelo.limpar();
    }

    long conversa(SessaoDeApi pessoa) {
        MvcTestResult aberta = pessoa.post("/api/parcerias/" + SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo") + "/atlas/conversas", "{}");
        return ((Number) lerJson(aberta, "$.conversaId")).longValue();
    }

    /** Abre um cartão pela rota 44 (JSON) e devolve o id dele. */
    long cartao(SessaoDeApi pessoa, long conversa) {
        modelo.chamada(LoopDoAgenteTest.INICIAR, LoopDoAgenteTest.ARGS_INICIAR).chamada(LoopDoAgenteTest.SOLICITAR, LoopDoAgenteTest.ARGS_CARTAO);
        MvcTestResult aberta = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Monte.\"}");
        return ((Number) lerJson(aberta, "$.itens[1].solicitacaoId")).longValue();
    }

    /** O erro em ProblemDetail, JSON, e nada de fluxo. */
    static void problema(MvcTestResult resposta, HttpStatus status, String detalhe) {
        assertThat(resposta).hasStatus(status);
        assertThat(resposta.getRequest().isAsyncStarted()).as("o fluxo não abriu").isFalse();
        assertThat(resposta.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(resposta, "$.status")).isEqualTo(status.value());
        if (detalhe != null) {
            assertThat(lerJson(resposta, "$.detail")).isEqualTo(detalhe);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/event-stream", "text/event-stream, application/json"})
    void rota44ErrosAntesDoFluxo(String aceita) {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = conversa(pessoa);
        String uri = "/api/atlas/conversas/" + conversa + "/mensagens";

        problema(entrar(RBS).postAceitando(uri, "{\"texto\":\"Oi\"}", aceita), HttpStatus.FORBIDDEN,
                "Só o Admin Patrocinador da parceria conversa com o Atlas.");
        problema(entrar(AGENCIA).postAceitando(uri, "{\"texto\":\"Oi\"}", aceita), HttpStatus.NOT_FOUND, "Não encontramos esta conversa.");
        problema(pessoa.postAceitando("/api/atlas/conversas/999999999/mensagens", "{\"texto\":\"Oi\"}", aceita), HttpStatus.NOT_FOUND,
                "Não encontramos esta conversa.");
        MvcTestResult vazio = pessoa.postAceitando(uri, "{\"texto\":\"   \"}", aceita);
        problema(vazio, HttpStatus.UNPROCESSABLE_CONTENT, null);
        assertThat(lerJson(vazio, "$.campos.texto")).isEqualTo("Escreva a mensagem.");
        problema(pessoa.postAceitando(uri, "{\"atalho\":\"OUTRO\",\"modelo\":\"ATIVACAO_EVENTO\"}", aceita),
                HttpStatus.UNPROCESSABLE_CONTENT, null);

        jdbc.sql("UPDATE atlas_conversa SET turno_desde = now() WHERE id = :id").param("id", conversa).update();
        problema(pessoa.postAceitando(uri, "{\"texto\":\"Oi\"}", aceita), HttpStatus.CONFLICT, AtlasService.TURNO_EM_ANDAMENTO);
        jdbc.sql("UPDATE atlas_conversa SET turno_desde = NULL WHERE id = :id").param("id", conversa).update();

        jdbc.sql("""
                INSERT INTO atlas_mensagem (conversa_id, ordem, papel, conteudo)
                SELECT :conversa, coalesce((SELECT max(ordem) FROM atlas_mensagem WHERE conversa_id = :conversa), 0) + g,
                       'assistant', 'Texto do assistente.'
                FROM generate_series(1, :quantas) g
                """).param("conversa", conversa).param("quantas", TetoDiario.PADRAO).update();
        problema(pessoa.postAceitando(uri, "{\"texto\":\"Oi\"}", aceita), HttpStatus.TOO_MANY_REQUESTS, TetoDiario.MENSAGEM);

        assertThat(modelo.pedidos()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'user'").param("c", conversa)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT turno_desde IS NULL FROM atlas_conversa WHERE id = :c").param("c", conversa)
                .query(Boolean.class).single()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/event-stream", "text/event-stream, application/json"})
    void rota46ErrosAntesDoFluxo(String aceita) {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = conversa(pessoa);
        long cartao = cartao(pessoa, conversa);
        String uri = "/api/atlas/solicitacoes/" + cartao + "/respostas";

        problema(entrar(RBS).postAceitando(uri, ACCEPT, aceita), HttpStatus.FORBIDDEN, null);
        problema(entrar(AGENCIA).postAceitando(uri, ACCEPT, aceita), HttpStatus.NOT_FOUND, "Não encontramos esta solicitação.");
        MvcTestResult acao = pessoa.postAceitando(uri, "{\"acao\":\"talvez\"}", aceita);
        problema(acao, HttpStatus.UNPROCESSABLE_CONTENT, null);
        assertThat(lerJson(acao, "$.campos.acao")).isEqualTo("Use accept, decline ou cancel.");
        // o 422 dos valores vem de dentro do turno (depende do estado), e ainda antes do fluxo: nada gravado, turno solto
        MvcTestResult valores = pessoa.postAceitando(uri, "{\"acao\":\"accept\",\"valores\":[{\"chave\":\"ativacao.visitantes_total\","
                + "\"valor\":10},{\"chave\":\"ativacao.cadastros_consentidos\",\"valor\":99}]}", aceita);
        problema(valores, HttpStatus.UNPROCESSABLE_CONTENT, null);
        assertThat(jdbc.sql("SELECT turno_desde IS NULL FROM atlas_conversa WHERE id = :c").param("c", conversa)
                .query(Boolean.class).single()).isTrue();

        jdbc.sql("UPDATE atlas_conversa SET turno_desde = now() WHERE id = :id").param("id", conversa).update();
        problema(pessoa.postAceitando(uri, ACCEPT, aceita), HttpStatus.CONFLICT, AtlasService.TURNO_EM_ANDAMENTO);
        jdbc.sql("UPDATE atlas_conversa SET turno_desde = NULL WHERE id = :id").param("id", conversa).update();

        jdbc.sql("UPDATE atlas_solicitacao SET status = 'RECUSADA' WHERE id = :id").param("id", cartao).update();
        problema(pessoa.postAceitando(uri, ACCEPT, aceita), HttpStatus.CONFLICT, "Este cartão já foi respondido.");

        assertThat(modelo.pedidos()).hasSize(2);   // só as duas idas que abriram o cartão
        List<Long> respostasDoCartao = jdbc.sql("SELECT id FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'tool' AND tool_call_id IN "
                + "(SELECT tool_call_id FROM atlas_solicitacao WHERE id = :s)").param("c", conversa).param("s", cartao).query(Long.class).list();
        assertThat(respostasDoCartao).isEmpty();
    }

    SessaoDeApi entrar(String email) {
        return SessaoDeApi.entrar(mvc, email);
    }
}
