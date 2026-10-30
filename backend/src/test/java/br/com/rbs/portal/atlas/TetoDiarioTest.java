package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.loop.TetoDiario;
import br.com.rbs.portal.comum.LimiteDeUsoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] O teto diário (plano, seção 6.7): as mensagens do assistente gravadas no dia (Brasília), por parceria, no limite de
 * {@code atlas.teto-diario-por-parceria} (padrão 200), fazem a rota 44 com texto responder 429 em ProblemDetail. O botão (plano B,
 * mensagens com id {@code botao_...}) não conta, nem as de outra parceria, nem as de ontem. A resposta do cartão acima do teto
 * segue o plano B em vez de dar 429. Cada teste é desfeito no fim (@Transactional).
 */
@TesteComLlmRoteirizado
@Transactional
class TetoDiarioTest {

    private static final String AMARELO = "marketing@amarelo.example";
    private static final String ROXO = "marketing@roxo.example";
    private static final String TEXTO = "{\"texto\":\"Oi, Atlas.\"}";
    private static final String FRASE_DO_429 =
            "O Atlas atingiu o limite de uso de hoje desta parceria. Use o botão Montar relatório ou tente amanhã.";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    TetoDiario teto;

    @Autowired
    AtlasRepositorio repositorio;

    @Value("${atlas.teto-diario-por-parceria}")
    int tetoConfigurado;

    @BeforeEach
    void zerarORoteiro() {
        modelo.limpar();
    }

    long parceria(String nome) {
        return SessaoDeApi.parceriaDe2027(jdbc, nome);
    }

    SessaoDeApi entrar(String email) {
        return SessaoDeApi.entrar(mvc, email);
    }

    long conversaDe(SessaoDeApi pessoa, long parceria) {
        MvcTestResult aberta = pessoa.post("/api/parcerias/" + parceria + "/atlas/conversas", "{}");
        assertThat(aberta).hasStatus(HttpStatus.CREATED);
        return ((Number) lerJson(aberta, "$.conversaId")).longValue();
    }

    /** Põe {@code quantas} mensagens do assistente na conversa, como se o modelo as tivesse escrito {@code horasAtras} horas atrás. */
    void gravarMensagensDoModelo(long conversa, int quantas, int horasAtras, String toolCalls) {
        jdbc.sql("""
                INSERT INTO atlas_mensagem (conversa_id, ordem, papel, conteudo, tool_calls, criada_em)
                SELECT :conversa, coalesce((SELECT max(ordem) FROM atlas_mensagem WHERE conversa_id = :conversa), 0) + g,
                       'assistant', 'Texto do assistente.', CAST(:calls AS jsonb), now() - make_interval(hours => :horas)
                FROM generate_series(1, :quantas) g
                """).param("conversa", conversa).param("quantas", quantas).param("horas", horasAtras).param("calls", toolCalls).update();
    }

    @Test
    void oPadraoEDuzentasMensagensPorParceriaPorDia() {
        assertThat(TetoDiario.PADRAO).isEqualTo(200);
        assertThat(tetoConfigurado).isEqualTo(200);
        assertThat(teto.teto()).isEqualTo(200);
    }

    @Test
    void noLimiteATextoRecebe429EmProblemDetailESemGravarNada() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = conversaDe(pessoa, parceria("Cliente Amarelo"));
        gravarMensagensDoModelo(conversa, 200, 0, null);
        modelo.texto("Isto não deve ser dito.");

        MvcTestResult resposta = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", TEXTO);

        assertThat(resposta).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(resposta.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(resposta, "$.status")).isEqualTo(429);
        assertThat(lerJson(resposta, "$.title")).isEqualTo("Limite de uso atingido");
        assertThat(lerJson(resposta, "$.detail")).isEqualTo(FRASE_DO_429);
        assertThat(lerJson(resposta, "$.type")).isEqualTo("about:blank");
        assertThat(lerJson(resposta, "$.instance")).isEqualTo("/api/atlas/conversas/" + conversa + "/mensagens");
        assertThat(modelo.pedidos()).isEmpty();   // o modelo não foi chamado
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'user'").param("c", conversa)
                .query(Long.class).single()).isZero();   // a mensagem não foi gravada
        assertThat(jdbc.sql("SELECT turno_desde IS NULL FROM atlas_conversa WHERE id = :c").param("c", conversa)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void umaAbaixoDoTetoAindaPassa() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = conversaDe(pessoa, parceria("Cliente Amarelo"));
        gravarMensagensDoModelo(conversa, 199, 0, null);
        modelo.texto("Posso montar o relatório de ativação.");

        MvcTestResult resposta = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", TEXTO);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerLista(resposta, "$.itens[*].tipo")).containsExactly("usuario", "texto");
        // o turno que começou abaixo do teto termina, e a próxima mensagem já encontra o teto
        modelo.texto("Isto não deve ser dito.");
        assertThat(pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", TEXTO)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void oTetoVaiAteOFimDoDiaPorParceriaEOBotaoNaoConta() {
        SessaoDeApi doAmarelo = entrar(AMARELO);
        long amarelo = parceria("Cliente Amarelo");
        long conversaDoAmarelo = conversaDe(doAmarelo, amarelo);
        SessaoDeApi doRoxo = entrar(ROXO);
        long roxo = parceria("Cliente Roxo");
        long conversaDoRoxo = conversaDe(doRoxo, roxo);

        gravarMensagensDoModelo(conversaDoRoxo, 300, 0, null);                                  // outra parceria: não conta
        gravarMensagensDoModelo(conversaDoAmarelo, 300, 48, null);                              // há dois dias: não conta
        gravarMensagensDoModelo(conversaDoAmarelo, 300, 0, "[{\"id\":\"botao_abc\",\"type\":\"function\","
                + "\"function\":{\"name\":\"relatorio_finalizar\",\"arguments\":\"{}\"}}]");    // do botão: não conta
        assertThat(repositorio.mensagensDoModeloHoje(amarelo)).isZero();
        assertThat(repositorio.mensagensDoModeloHoje(roxo)).isEqualTo(300);
        modelo.texto("Posso ajudar.");

        MvcTestResult doAmareloPassa = doAmarelo.post("/api/atlas/conversas/" + conversaDoAmarelo + "/mensagens", TEXTO);

        assertThat(doAmareloPassa).hasStatus(HttpStatus.OK);
        modelo.texto("Isto não deve ser dito.");
        MvcTestResult doRoxoBarrado = doRoxo.post("/api/atlas/conversas/" + conversaDoRoxo + "/mensagens", TEXTO);
        assertThat(doRoxoBarrado).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(modelo.pedidos()).hasSize(1);
    }

    @Test
    void oBotaoContinuaFuncionandoComOTetoAtingido() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long amarelo = parceria("Cliente Amarelo");
        long conversa = conversaDe(pessoa, amarelo);
        gravarMensagensDoModelo(conversa, 250, 0, null);

        MvcTestResult botao = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens",
                "{\"atalho\":\"MONTAR_RELATORIO\",\"modelo\":\"ATIVACAO_EVENTO\"}");

        assertThat(botao).hasStatus(HttpStatus.OK);
        assertThat(lerLista(botao, "$.itens[*].tipo")).containsExactly("usuario", "texto", "cartao");
        assertThat(modelo.pedidos()).isEmpty();
        assertThat(repositorio.mensagensDoModeloHoje(amarelo)).isEqualTo(250);   // as do plano B não entraram na conta
    }

    @Test
    void oBotaoNaoMexeNoContador() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long amarelo = parceria("Cliente Amarelo");
        long conversa = conversaDe(pessoa, amarelo);

        MvcTestResult botao = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens",
                "{\"atalho\":\"MONTAR_RELATORIO\",\"modelo\":\"ATIVACAO_EVENTO\"}");
        assertThat(botao).hasStatus(HttpStatus.OK);

        // o plano B gravou duas mensagens do assistente (iniciar e o cartão) e nenhuma conta
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'assistant'").param("c", conversa)
                .query(Long.class).single()).isEqualTo(2);
        assertThat(repositorio.mensagensDoModeloHoje(amarelo)).isZero();
        assertThat(modelo.pedidos()).isEmpty();
    }

    @Test
    void cartaoRespondidoComOTetoAtingidoSegueOPlanoBEmVezDeDar429() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long amarelo = parceria("Cliente Amarelo");
        long conversa = conversaDe(pessoa, amarelo);
        modelo.chamada("relatorio_iniciar", "{\"modelo\":\"ATIVACAO_EVENTO\"}")
                .chamada("relatorio_solicitar_campos", LoopDoAgenteTest.ARGS_CARTAO);
        MvcTestResult aberta = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Monte o relatório.\"}");
        long cartao = ((Number) lerJson(aberta, "$.itens[1].solicitacaoId")).longValue();
        gravarMensagensDoModelo(conversa, 200, 0, null);

        MvcTestResult resposta = pessoa.post("/api/atlas/solicitacoes/" + cartao + "/respostas",
                "{\"acao\":\"accept\",\"valores\":[{\"chave\":\"ativacao.visitantes_total\",\"valor\":1200},"
                        + "{\"chave\":\"ativacao.cadastros_consentidos\",\"valor\":1072}]}");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerLista(resposta, "$.itens[*].tipo")).containsExactly("texto", "relatorio_pronto");   // o plano B
        assertThat(modelo.pedidos()).hasSize(2);   // o modelo não foi chamado de novo
        long relatorio = ((Number) lerJson(resposta, "$.itens[1].relatorioId")).longValue();
        assertThat(lerLista(pessoa.get("/api/atlas/relatorios/" + relatorio), "$.secoes[*].blocos[?(@.tipo=='paragrafo')].redigidoPor"))
                .containsExactly("MODELO", "MODELO");   // as frases-modelo
    }

    @Test
    void oTetoVemDaConfiguracao() {
        // O mesmo contador, outro limite: nenhum recurso novo, só o número da propriedade.
        SessaoDeApi pessoa = entrar(AMARELO);
        long amarelo = parceria("Cliente Amarelo");
        long conversa = conversaDe(pessoa, amarelo);
        gravarMensagensDoModelo(conversa, 3, 0, null);

        TetoDiario tetoDeTres = new TetoDiario(repositorio, 3, new ModoDoAtlas(true));
        TetoDiario tetoDeQuatro = new TetoDiario(repositorio, 4, new ModoDoAtlas(true));

        assertThat(tetoDeTres.atingido(amarelo)).isTrue();
        assertThatThrownBy(() -> tetoDeTres.exigirFolga(amarelo)).isInstanceOf(LimiteDeUsoException.class).hasMessage(FRASE_DO_429);
        assertThat(tetoDeQuatro.atingido(amarelo)).isFalse();
        tetoDeQuatro.exigirFolga(amarelo);
    }

    @Test
    void asConferenciasDePermissaoEFormatoVemAntesDoTeto() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long amarelo = parceria("Cliente Amarelo");
        long conversa = conversaDe(pessoa, amarelo);
        gravarMensagensDoModelo(conversa, 200, 0, null);

        assertThat(pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"   \"}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(entrar("b2b@rbs.example").post("/api/atlas/conversas/" + conversa + "/mensagens", TEXTO)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(entrar("atendimento@agencia-x.example").post("/api/atlas/conversas/" + conversa + "/mensagens", TEXTO))
                .hasStatus(HttpStatus.NOT_FOUND);
        jdbc.sql("UPDATE parceria_aba SET liberada = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'APLICACOES')")
                .param("p", amarelo).update();
        assertThat(pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", TEXTO)).hasStatus(HttpStatus.FORBIDDEN);
    }
}
