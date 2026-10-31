package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] As rotas 44 e 46 com o modelo de linguagem ligado (roteirizado), como o front as usa: login de verdade, sessão e CSRF.
 * O texto livre roda o loop, o cartão pausa o turno e a resposta dele retoma; os erros de sempre (403 da RBS, 404 do cartão e da
 * conversa alheios, 409 de turno e 422 de texto) continuam valendo, e {@code llmDisponivel} sai verdadeiro em todas. O formato é
 * o de frontend/src/api/atlas.ts. Cada teste é desfeito no fim (@Transactional).
 */
@TesteComLlmRoteirizado
@Transactional
class AtlasControllerComLlmTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String AGENCIA = "atendimento@agencia-x.example";
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

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    SessaoDeApi entrar(String email) {
        return SessaoDeApi.entrar(mvc, email);
    }

    long numero(MvcTestResult resposta, String caminho) {
        return ((Number) lerJson(resposta, caminho)).longValue();
    }

    long abrirConversa(SessaoDeApi pessoa) {
        MvcTestResult resposta = pessoa.post("/api/parcerias/" + amarelo() + "/atlas/conversas", "{}");
        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(resposta, "$.llmDisponivel")).isEqualTo(true);
        return numero(resposta, "$.conversaId");
    }

    @Test
    void textoLivreRodaOLoopEDevolveOsItensDoTurno() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);
        modelo.texto("Sou o Atlas. Posso montar o relatório de ativação de {{parc:patrocinador}}.");

        MvcTestResult resposta = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"  Quem é você?  \"}");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(resposta.getResponse().getContentType()).startsWith("application/json");
        assertThat(lerJson(resposta, "$.llmDisponivel")).isEqualTo(true);
        assertThat(numero(resposta, "$.conversaId")).isEqualTo(conversa);
        assertThat(lerLista(resposta, "$.itens[*].tipo")).containsExactly("usuario", "texto");
        assertThat(lerJson(resposta, "$.itens[0].texto")).isEqualTo("Quem é você?");
        assertThat(lerJson(resposta, "$.itens[1].texto")).isEqualTo("Sou o Atlas. Posso montar o relatório de ativação de Cliente Amarelo.");
    }

    @Test
    void fluxoCompletoPelaApiAteORelatorioPronto() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);
        modelo.chamada(LoopDoAgenteTest.INICIAR, LoopDoAgenteTest.ARGS_INICIAR).chamada(LoopDoAgenteTest.SOLICITAR, LoopDoAgenteTest.ARGS_CARTAO)
                .chamada(LoopDoAgenteTest.EDITAR, LoopDoAgenteTest.edicao("b-resumo", LoopDoAgenteTest.RESUMO, "b-publico", LoopDoAgenteTest.PUBLICO))
                .chamada(LoopDoAgenteTest.FINALIZAR, "{}");

        MvcTestResult aberta = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Quero o relatório da ativação.\"}");

        assertThat(aberta).hasStatus(HttpStatus.OK);
        assertThat(lerLista(aberta, "$.itens[*].tipo")).containsExactly("usuario", "cartao");
        assertThat(lerJson(aberta, "$.itens[1].status")).isEqualTo("PENDENTE");
        assertThat(lerLista(aberta, "$.itens[1].campos[*].chave"))
                .containsExactly("ativacao.visitantes_total", "ativacao.cadastros_consentidos");

        MvcTestResult pronta = pessoa.post("/api/atlas/solicitacoes/" + numero(aberta, "$.itens[1].solicitacaoId") + "/respostas", ACCEPT);

        assertThat(pronta).hasStatus(HttpStatus.OK);
        assertThat(lerLista(pronta, "$.itens[*].tipo")).containsExactly("relatorio_pronto");
        assertThat(lerJson(pronta, "$.llmDisponivel")).isEqualTo(true);
        MvcTestResult relatorio = pessoa.get("/api/atlas/relatorios/" + numero(pronta, "$.itens[0].relatorioId"));
        assertThat(lerJson(relatorio, "$.status")).isEqualTo("PRONTO");
        assertThat(lerLista(relatorio, "$.secoes[*].blocos[?(@.tipo=='paragrafo')].redigidoPor")).containsExactly("ATLAS", "ATLAS");

        // a conversa inteira (rota 45) mostra os mesmos itens, o cartão no estado de agora
        MvcTestResult inteira = pessoa.get("/api/atlas/conversas/" + conversa);
        assertThat(lerLista(inteira, "$.itens[*].tipo")).containsExactly("usuario", "cartao", "relatorio_pronto");
        assertThat(lerJson(inteira, "$.itens[1].status")).isEqualTo("RESPONDIDA");
        assertThat(lerJson(inteira, "$.llmDisponivel")).isEqualTo(true);
    }

    @Test
    void textoVazioOuGrandeDemais422SemChamarOModelo() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);

        assertThat(pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"   \"}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        MvcTestResult grande = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"" + "a".repeat(1001) + "\"}");
        assertThat(grande).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(grande, "$.campos.texto")).isEqualTo("A mensagem tem no máximo 1.000 caracteres.");
        assertThat(modelo.pedidos()).isEmpty();
    }

    @Test
    void textoComCaractereNuloDa422SemGravarNemChamarOModelo() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);
        long mensagensAntes = jdbc.sql("SELECT count(*) FROM atlas_mensagem WHERE conversa_id = :c").param("c", conversa)
                .query(Long.class).single();

        // o PostgreSQL não guarda o caractere nulo: antes, o INSERT falhava e a rota respondia 500
        for (String texto : List.of("ola\\u0000mundo", "\\u0000", "  \\u0000  ", "a".repeat(10) + "\\u0000")) {
            String json = "{\"texto\":\"" + texto + "\"}";
            MvcTestResult resposta = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", json);
            assertThat(resposta).as(json).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(lerJson(resposta, "$.campos.texto")).isEqualTo("A mensagem tem caracteres inválidos.");
            // a resposta em fluxo dá o mesmo erro, em JSON, antes de o fluxo abrir
            MvcTestResult emFluxo = pessoa.postAceitando("/api/atlas/conversas/" + conversa + "/mensagens", json, "text/event-stream, application/json");
            assertThat(emFluxo).as("em fluxo: " + json).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(lerJson(emFluxo, "$.campos.texto")).isEqualTo("A mensagem tem caracteres inválidos.");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_mensagem WHERE conversa_id = :c").param("c", conversa).query(Long.class).single())
                .as("nenhuma mensagem foi gravada").isEqualTo(mensagensAntes);
        assertThat(modelo.pedidos()).isEmpty();
        // a conversa continua livre: a mensagem seguinte, sem o caractere, entra
        modelo.texto("Posso ajudar com os dados da parceria.");
        assertThat(pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"ola mundo\"}")).hasStatus(HttpStatus.OK);
    }

    @Test
    void turnoEmAndamentoDa409() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);
        jdbc.sql("UPDATE atlas_conversa SET turno_desde = now() WHERE id = :id").param("id", conversa).update();

        MvcTestResult resposta = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Oi\"}");

        assertThat(resposta).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("O Atlas ainda está respondendo nesta conversa. Espere a resposta.");
        assertThat(modelo.pedidos()).isEmpty();
    }

    @Test
    void rbsRecebe403AntesDeQualquerCoisaMesmoComModelo() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);
        modelo.chamada(LoopDoAgenteTest.INICIAR, LoopDoAgenteTest.ARGS_INICIAR).chamada(LoopDoAgenteTest.SOLICITAR, LoopDoAgenteTest.ARGS_CARTAO);
        MvcTestResult aberta = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Monte.\"}");
        SessaoDeApi rbs = entrar(RBS);

        assertThat(rbs.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Oi\"}")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(rbs.post("/api/atlas/solicitacoes/" + numero(aberta, "$.itens[1].solicitacaoId") + "/respostas", ACCEPT)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(modelo.pedidos()).hasSize(2);   // a RBS não fez o modelo ser chamado de novo
    }

    @Test
    void conversaECartaoDeOutroUsuarioDaMesmaParceriaDao404() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);
        modelo.chamada(LoopDoAgenteTest.INICIAR, LoopDoAgenteTest.ARGS_INICIAR).chamada(LoopDoAgenteTest.SOLICITAR, LoopDoAgenteTest.ARGS_CARTAO);
        MvcTestResult aberta = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Monte.\"}");
        SessaoDeApi agencia = entrar(AGENCIA);

        MvcTestResult mensagem = agencia.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Oi\"}");
        assertThat(mensagem).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(mensagem, "$.detail")).isEqualTo("Não encontramos esta conversa.");
        MvcTestResult resposta = agencia.post("/api/atlas/solicitacoes/" + numero(aberta, "$.itens[1].solicitacaoId") + "/respostas", ACCEPT);
        assertThat(resposta).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("Não encontramos esta solicitação.");
        assertThat(modelo.pedidos()).hasSize(2);
        assertThat(corpo(pessoa.get("/api/atlas/conversas/" + conversa))).contains("PENDENTE");
    }

    @Test
    void semTokenCsrfDa403() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);

        assertThat(pessoa.postSemCsrf("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Oi\"}")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(modelo.pedidos()).isEmpty();
    }

    @Test
    void cartaoComValorInvalidoDa422ENaoRetomaOModelo() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);
        modelo.chamada(LoopDoAgenteTest.INICIAR, LoopDoAgenteTest.ARGS_INICIAR).chamada(LoopDoAgenteTest.SOLICITAR, LoopDoAgenteTest.ARGS_CARTAO);
        MvcTestResult aberta = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Monte.\"}");

        MvcTestResult invalido = pessoa.post("/api/atlas/solicitacoes/" + numero(aberta, "$.itens[1].solicitacaoId") + "/respostas",
                "{\"acao\":\"accept\",\"valores\":[{\"chave\":\"ativacao.visitantes_total\",\"valor\":10},"
                        + "{\"chave\":\"ativacao.cadastros_consentidos\",\"valor\":99}]}");

        assertThat(invalido).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(modelo.pedidos()).hasSize(2);   // nada foi gravado, e o modelo não foi chamado
        assertThat(lerLista(pessoa.get("/api/atlas/conversas/" + conversa), "$.itens[*].status")).contains((Object) "PENDENTE");
        List<?> semResposta = jdbc.sql("SELECT id FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'tool' AND tool_call_id IN "
                + "(SELECT tool_call_id FROM atlas_solicitacao)").param("c", conversa).query(Long.class).list();
        assertThat(semResposta).isEmpty();
    }
}
