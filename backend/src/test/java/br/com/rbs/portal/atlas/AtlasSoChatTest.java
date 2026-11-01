package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.CADASTROS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.RBS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.ROXO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.VISITANTES;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.valores;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.nomesDasFerramentas;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.contexto.AtlasContextoFabrica;
import br.com.rbs.portal.atlas.loop.LoopDoAgente;
import br.com.rbs.portal.atlas.loop.PlanoBSemLlm;
import br.com.rbs.portal.atlas.loop.TetoDiario;
import br.com.rbs.portal.atlas.provedor.DisponibilidadeDoLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.PedidoAoLlm;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.AcaoDoCartao;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] O Atlas só chat ({@code atlas.relatorio.ligado=false}, o padrão fora do perfil de teste): o modelo (roteirizado) recebe
 * só {@code parceria_consultar} e o prompt do chat; uma ferramenta {@code relatorio_*} chamada à força volta como erro e nada é
 * criado; {@code atalho} e {@code modelo} dão 422 nas rotas 42 e 44; as rotas 46 a 48 dão 404 como rota que não existe; a conversa
 * não devolve {@code cartao}, {@code relatorio_pronto} nem {@code aviso}, inclusive numa conversa antiga que os tenha, e o
 * {@code relatorio} das listas vem nulo; nenhuma frase do servidor fala do botão. O debug das ferramentas fica desligado aqui.
 * Contexto próprio por causa das propriedades; cada teste é desfeito no fim (@Transactional).
 */
@TesteComLlmRoteirizado
@Transactional
@TestPropertySource(properties = {"atlas.relatorio.ligado=false", "atlas.debug.ferramentas=false"})
class AtlasSoChatTest {

    private static final String FRASE_422 = "O relatório não está disponível nesta versão.";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    PlanoBSemLlm planoB;

    @Autowired
    AtlasContextoFabrica fabrica;

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
        return numero(resposta, "$.conversaId");
    }

    MvcTestResult falar(SessaoDeApi pessoa, long conversa, String texto) {
        return pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"" + texto + "\"}");
    }

    long contar(String tabela) {
        return jdbc.sql("SELECT count(*) FROM " + tabela).query(Long.class).single();
    }

    /** Uma conversa antiga, de quando o relatório existia: o botão rodou, com cartão aberto. */
    long conversaAntigaComCartao() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);
        planoB.montar(contextoDe(conversa), "ATIVACAO_EVENTO");
        return conversa;
    }

    AtlasContexto contextoDe(long conversa) {
        return fabrica.para(usuario(jdbc, AMARELO), amarelo(), conversa, requisicao());
    }

    long solicitacaoDe(long conversa) {
        return jdbc.sql("SELECT s.id FROM atlas_solicitacao s JOIN atlas_relatorio r ON r.id = s.relatorio_id WHERE r.conversa_id = :c "
                + "ORDER BY s.id DESC LIMIT 1").param("c", conversa)
                .query(Long.class).single();
    }

    // ---------------------------------------------------------------------
    // Rotas 42, 43 e 44
    // ---------------------------------------------------------------------

    @Test
    void aConversaAbreVaziaEDizQueORelatorioNaoEstaDisponivel() {
        SessaoDeApi pessoa = entrar(AMARELO);

        MvcTestResult semCorpo = pessoa.post("/api/parcerias/" + amarelo() + "/atlas/conversas", null);
        MvcTestResult vazio = pessoa.post("/api/parcerias/" + amarelo() + "/atlas/conversas", "{}");

        for (MvcTestResult aberta : List.of(semCorpo, vazio)) {
            assertThat(aberta).hasStatus(HttpStatus.CREATED);
            assertThat(lerJson(aberta, "$.llmDisponivel")).isEqualTo(true);
            assertThat(lerJson(aberta, "$.relatorioDisponivel")).isEqualTo(false);
            assertThat(lerJson(aberta, "$.debugDeFerramentas")).isEqualTo(false);
            assertThat(lerLista(aberta, "$.itens")).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"atalho\":\"MONTAR_RELATORIO\",\"modelo\":\"ATIVACAO_EVENTO\"}", "{\"atalho\":\"MONTAR_RELATORIO\"}",
            "{\"modelo\":\"ATIVACAO_EVENTO\"}", "{\"atalho\":\"qualquer coisa\"}"})
    void atalhoOuModeloNaRota42Da422SemAbrirConversa(String corpoDoPedido) {
        SessaoDeApi pessoa = entrar(AMARELO);
        long antes = contar("atlas_conversa");

        MvcTestResult resposta = pessoa.post("/api/parcerias/" + amarelo() + "/atlas/conversas", corpoDoPedido);

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(resposta, "$.campos.atalho")).isEqualTo(FRASE_422);
        assertThat(lerLista(resposta, "$.campos.*")).hasSize(1);
        assertThat(contar("atlas_conversa")).isEqualTo(antes);
        assertThat(corpo(resposta)).doesNotContain("Montar relatório");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"atalho\":\"MONTAR_RELATORIO\",\"modelo\":\"ATIVACAO_EVENTO\"}", "{\"atalho\":\"MONTAR_RELATORIO\"}",
            "{\"modelo\":\"ATIVACAO_EVENTO\"}", "{\"texto\":\"Oi\",\"atalho\":\"MONTAR_RELATORIO\",\"modelo\":\"ATIVACAO_EVENTO\"}"})
    void atalhoOuModeloNaRota44Da422SemChamarOModelo(String corpoDoPedido) {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);

        MvcTestResult resposta = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", corpoDoPedido);

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(resposta, "$.campos.atalho")).isEqualTo(FRASE_422);
        assertThat(modelo.pedidos()).isEmpty();
        assertThat(contar("atlas_relatorio")).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_mensagem WHERE conversa_id = :c").param("c", conversa).query(Long.class).single())
                .isZero();
    }

    @Test
    void asConferenciasDePermissaoContinuamAntesDoAtalho() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);
        String atalho = "{\"atalho\":\"MONTAR_RELATORIO\",\"modelo\":\"ATIVACAO_EVENTO\"}";

        assertThat(entrar(RBS).post("/api/atlas/conversas/" + conversa + "/mensagens", atalho)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(entrar(RBS).post("/api/parcerias/" + amarelo() + "/atlas/conversas", atalho)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(entrar(ROXO).post("/api/atlas/conversas/" + conversa + "/mensagens", atalho)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(entrar(ROXO).post("/api/parcerias/" + amarelo() + "/atlas/conversas", atalho)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void mensagemSemTextoNemAtalhoPedeSoAMensagem() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);

        MvcTestResult resposta = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", "{}");

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(resposta, "$.campos.texto")).isEqualTo("Escreva a mensagem.");
    }

    @Test
    void aListaDeConversasDizQueORelatorioNaoEstaDisponivel() {
        SessaoDeApi pessoa = entrar(AMARELO);
        abrirConversa(pessoa);

        MvcTestResult lista = pessoa.get("/api/parcerias/" + amarelo() + "/atlas/conversas");

        assertThat(lista).hasStatus(HttpStatus.OK);
        assertThat(lerJson(lista, "$.relatorioDisponivel")).isEqualTo(false);
        assertThat(lerJson(lista, "$.llmDisponivel")).isEqualTo(true);
    }

    // ---------------------------------------------------------------------
    // Rotas 46, 47, 48 e 65 não existem
    // ---------------------------------------------------------------------

    @Test
    void aRota65DescartarDa404ComoRotaQueNaoExisteENadaMuda() {
        long conversa = conversaAntigaComCartao();
        long relatorio = jdbc.sql("SELECT id FROM atlas_relatorio WHERE conversa_id = :c").param("c", conversa).query(Long.class).single();

        for (String email : List.of(AMARELO, RBS, ROXO)) {
            MvcTestResult resposta = entrar(email).post("/api/atlas/relatorios/" + relatorio + "/descartar", null);
            assertThat(resposta).as(email).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(lerJson(resposta, "$.status")).isEqualTo(404);
        }
        assertThat(jdbc.sql("SELECT status FROM atlas_relatorio WHERE id = :r").param("r", relatorio).query(String.class).single())
                .isEqualTo("AGUARDANDO_RESPOSTA");
    }

    @Test
    void asRotas46a48DaoNotFoundComoRotaQueNaoExiste() {
        long conversa = conversaAntigaComCartao();
        long solicitacao = solicitacaoDe(conversa);
        long relatorio = jdbc.sql("SELECT id FROM atlas_relatorio WHERE conversa_id = :c").param("c", conversa).query(Long.class).single();

        for (String email : List.of(AMARELO, RBS, ROXO)) {
            SessaoDeApi pessoa = entrar(email);
            MvcTestResult cartao = pessoa.post("/api/atlas/solicitacoes/" + solicitacao + "/respostas", "{\"acao\":\"cancel\"}");
            assertThat(cartao).as(email + " rota 46").hasStatus(HttpStatus.NOT_FOUND);
            assertThat(lerJson(cartao, "$.status")).isEqualTo(404);
            assertThat(pessoa.get("/api/atlas/relatorios/" + relatorio)).as(email + " rota 47").hasStatus(HttpStatus.NOT_FOUND);
            assertThat(pessoa.get("/api/parcerias/" + amarelo() + "/atlas/relatorios")).as(email + " rota 48")
                    .hasStatus(HttpStatus.NOT_FOUND);
        }
        assertThat(jdbc.sql("SELECT status FROM atlas_solicitacao WHERE id = :s").param("s", solicitacao).query(String.class).single())
                .isEqualTo("PENDENTE");
    }

    // ---------------------------------------------------------------------
    // O que o modelo recebe
    // ---------------------------------------------------------------------

    @Test
    void oModeloRecebeSoAConsultaEOPromptDoChat() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);
        modelo.chamada(LoopDoAgenteTest.CONSULTAR, "{}")
                .texto("Aplicações de marca previstas: {{cons:aplicacoes.previstas}}; comprovadas no evento: {{cons:aplicacoes.comprovadas}}.");

        MvcTestResult resposta = falar(pessoa, conversa, "Quantas aplicações já foram comprovadas?");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerLista(resposta, "$.itens[*].tipo")).containsExactly("usuario", "texto");
        assertThat(lerJson(resposta, "$.itens[1].texto")).isEqualTo("Aplicações de marca previstas: 19; comprovadas no evento: 17.");
        assertThat(lerJson(resposta, "$.itens[1].porModelo")).isEqualTo(true);
        assertThat(modelo.pedidos()).hasSize(2);
        for (PedidoAoLlm pedido : modelo.pedidos()) {
            assertThat(nomesDasFerramentas(pedido)).containsExactlyElementsOf(LoopDoAgenteTest.DO_CHAT);
            String prompt = pedido.mensagens().get(0).conteudo();
            assertThat(prompt).contains("Cliente Amarelo", "parceria_consultar", "chame pedido_de_relatorio", "chame gerar_modelo")
                    .doesNotContain("relatorio_", "O fluxo do relatório", "@MARCA@", "@DATA@");
            // o prompt é o do modo só chat: a base, as duas linhas do relatório desligado, a data de hoje e o nome da marca
            assertThat(prompt).isEqualTo(ApoioDosPrompts.esperado(false, "Cliente Amarelo"));
            assertThat(prompt.length()).as("o prompt do chat tem de ficar curto").isLessThan(10000);
            assertThat(ApoioDosPrompts.MARCADOR_QUE_SOBROU.matcher(prompt).find()).as("sobrou um marcador @...@").isFalse();
            assertThat(prompt).doesNotContain("Há dois caminhos para um pedido de relatório", "No texto de um bloco", "relatorio_iniciar")
                    .contains("chame pedido_de_relatorio", "A conversa pode ter mensagens antigas sobre um relatório");
            ProvedorRoteirizado.exigirHistoricoValido(pedido);
        }
        assertThat(contar("atlas_relatorio")).isZero();
    }

    @Test
    void ferramentaDoRelatorioChamadaAForcaVoltaComoErroENadaECriado() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);
        modelo.chamada(LoopDoAgenteTest.INICIAR, LoopDoAgenteTest.ARGS_INICIAR)
                .chamada(LoopDoAgenteTest.SOLICITAR, LoopDoAgenteTest.ARGS_CARTAO)
                .chamada(LoopDoAgenteTest.EDITAR, LoopDoAgenteTest.edicao("b-resumo", LoopDoAgenteTest.RESUMO))
                .chamada(LoopDoAgenteTest.FINALIZAR, "{}")
                .passo(pedido -> {
                    // o modelo viu o erro de cada uma e segue oferecido só a consulta
                    List<String> respostas = pedido.mensagens().stream().filter(m -> "tool".equals(m.papel())).map(m -> m.conteudo())
                            .toList();
                    assertThat(respostas).hasSize(4).allSatisfy(r -> assertThat(r).startsWith("{\"erro\":\"Não existe a ferramenta relatorio_"));
                    return new br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm(
                            "O relatório ainda não está disponível nesta versão. Posso responder perguntas sobre a parceria.", List.of(), null);
                });

        MvcTestResult resposta = falar(pessoa, conversa, "Monte o relatório da ativação.");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerLista(resposta, "$.itens[*].tipo")).containsExactly("usuario", "texto");
        assertThat(modelo.pedidos()).allSatisfy(p -> assertThat(nomesDasFerramentas(p)).containsExactlyElementsOf(LoopDoAgenteTest.DO_CHAT));
        assertThat(contar("atlas_relatorio")).isZero();
        assertThat(contar("atlas_solicitacao")).isZero();
        assertThat(contar("atlas_calculo")).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_tool_call WHERE erro IS NOT NULL").query(Long.class).single()).isEqualTo(4);
        assertThat(jdbc.sql("SELECT count(*) FROM dado_informado WHERE origem = 'ATLAS'").query(Long.class).single()).isZero();
        assertThat(modelo.sobraDoRoteiro()).isZero();
    }

    @Test
    void aConsultaContinuaComAChecagemDoTextoEOFiltro() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);
        // número solto duas vezes: a resposta vira a frase fixa do chat, sem falar do botão
        modelo.chamada(LoopDoAgenteTest.CONSULTAR, "{}").texto("Foram 17 aplicações.").texto("Foram 17 aplicações, de 19.");

        MvcTestResult resposta = falar(pessoa, conversa, "Quantas?");

        assertThat(lerLista(resposta, "$.itens[*].tipo")).containsExactly("usuario", "texto");
        assertThat(lerJson(resposta, "$.itens[1].texto")).isEqualTo(LoopDoAgente.FRASE_FIXA_NO_CHAT);
        assertThat(lerJson(resposta, "$.itens[1].porModelo")).isEqualTo(false);
    }

    // ---------------------------------------------------------------------
    // Conversa antiga
    // ---------------------------------------------------------------------

    @Test
    void conversaAntigaComCartaoNaoDevolveCartaoNemAvisoEOHistoricoVaiAoModeloComoEsta() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = conversaAntigaComCartao();
        long solicitacao = solicitacaoDe(conversa);
        assertThat(contar("atlas_relatorio")).isEqualTo(1);

        MvcTestResult inteira = pessoa.get("/api/atlas/conversas/" + conversa);

        assertThat(inteira).hasStatus(HttpStatus.OK);
        assertThat(lerLista(inteira, "$.itens[*].tipo")).containsExactly("usuario", "texto");
        assertThat(lerJson(inteira, "$.relatorioDisponivel")).isEqualTo(false);

        // a pessoa escreve de novo: o cartão aberto é fechado no histórico (cancel), sem aviso, e o modelo só tem a consulta
        modelo.texto("O relatório ainda não está disponível nesta versão. Posso responder perguntas sobre a parceria.");
        MvcTestResult turno = falar(pessoa, conversa, "E agora?");

        assertThat(turno).hasStatus(HttpStatus.OK);
        assertThat(lerLista(turno, "$.itens[*].tipo")).containsExactly("usuario", "texto");
        assertThat(nomesDasFerramentas(modelo.ultimoPedido())).containsExactlyElementsOf(LoopDoAgenteTest.DO_CHAT);
        ProvedorRoteirizado.exigirHistoricoValido(modelo.ultimoPedido());
        assertThat(modelo.ultimoPedido().mensagens().stream().filter(m -> "assistant".equals(m.papel()))
                .flatMap(m -> m.chamadas().stream()).map(c -> c.nome()).toList()).contains("relatorio_iniciar", "relatorio_solicitar_campos");
        assertThat(jdbc.sql("SELECT status FROM atlas_solicitacao WHERE id = :s").param("s", solicitacao).query(String.class).single())
                .isEqualTo("CANCELADA");
        assertThat(lerLista(pessoa.get("/api/atlas/conversas/" + conversa), "$.itens[*].tipo")).containsExactly("usuario", "texto", "usuario", "texto");
    }

    @Test
    void conversaAntigaComRelatorioProntoNaoDevolveRelatorioProntoEAListaVemSemRelatorio() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = conversaAntigaComCartao();
        planoB.responder(contextoDe(conversa), solicitacaoDe(conversa), AcaoDoCartao.ACCEPT, valores(VISITANTES, 1200L, CADASTROS, 1072L));
        assertThat(jdbc.sql("SELECT status FROM atlas_relatorio WHERE conversa_id = :c").param("c", conversa).query(String.class).single())
                .isEqualTo("PRONTO");

        MvcTestResult inteira = pessoa.get("/api/atlas/conversas/" + conversa);
        MvcTestResult lista = pessoa.get("/api/parcerias/" + amarelo() + "/atlas/conversas");

        assertThat(lerLista(inteira, "$.itens[*].tipo")).doesNotContain("cartao", "relatorio_pronto", "aviso").contains("usuario", "texto");
        assertThat(lerLista(lista, "$.conversas[*].id")).hasSize(1);
        assertThat(lerJson(lista, "$.conversas[0].relatorio")).isNull();
        assertThat(corpo(inteira)).doesNotContain("relatorio_pronto");
    }

    @Test
    void cartaoCanceladoNaConversaAntigaNaoViraAviso() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = conversaAntigaComCartao();
        planoB.responder(contextoDe(conversa), solicitacaoDe(conversa), AcaoDoCartao.CANCEL, List.of());

        MvcTestResult inteira = pessoa.get("/api/atlas/conversas/" + conversa);

        assertThat(lerLista(inteira, "$.itens[*].tipo")).doesNotContain("aviso", "cartao");
    }

    // ---------------------------------------------------------------------
    // 503 e 429 e as frases fixas
    // ---------------------------------------------------------------------

    @Test
    void oTetoDiarioDa429SemFalarDoBotao() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);
        jdbc.sql("""
                INSERT INTO atlas_mensagem (conversa_id, ordem, papel, conteudo)
                SELECT :conversa, g, 'assistant', 'Texto do assistente.' FROM generate_series(1, :n) g
                """).param("conversa", conversa).param("n", TetoDiario.PADRAO).update();

        MvcTestResult resposta = falar(pessoa, conversa, "Oi");

        assertThat(resposta).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("O Atlas atingiu o limite de uso de hoje desta parceria. Tente amanhã.");
        assertThat(modelo.pedidos()).isEmpty();
    }

    @Test
    void falhaDoModeloEResposta200ComAFraseSemBotao() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);
        modelo.falha();

        MvcTestResult resposta = falar(pessoa, conversa, "Oi");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.itens[1].texto")).isEqualTo(LoopDoAgente.FRASE_DA_FALHA_NO_CHAT);
        assertThat(lerJson(resposta, "$.itens[1].porModelo")).isEqualTo(false);
    }

    @Test
    void respostaVaziaDoModeloViraAFraseSemBotao() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);
        modelo.vazio();

        MvcTestResult resposta = falar(pessoa, conversa, "Oi");

        assertThat(lerJson(resposta, "$.itens[1].texto")).isEqualTo(LoopDoAgente.FRASE_SEM_RESPOSTA_NO_CHAT);
        assertThat(lerJson(resposta, "$.itens[1].porModelo")).isEqualTo(false);
    }

    @Test
    void nenhumaFraseFixaDoChatFalaDoBotao() {
        List<String> frases = List.of(LoopDoAgente.FRASE_FIXA_NO_CHAT, LoopDoAgente.FRASE_DO_LIMITE_NO_CHAT,
                LoopDoAgente.FRASE_DA_FALHA_NO_CHAT, LoopDoAgente.FRASE_DO_PRAZO_NO_CHAT, LoopDoAgente.FRASE_SEM_RESPOSTA_NO_CHAT,
                DisponibilidadeDoLlm.SEM_MODELO_NO_CHAT, TetoDiario.MENSAGEM_NO_CHAT);
        assertThat(frases).allSatisfy(f -> assertThat(f).doesNotContainIgnoringCase("botão").doesNotContainIgnoringCase("montar relatório")
                .doesNotContainIgnoringCase("monte o relatório"));
        assertThat(LoopDoAgente.FRASES_DO_SERVIDOR).containsAll(frases.subList(0, 5));
        assertThat(DisponibilidadeDoLlm.SEM_MODELO_NO_CHAT).isEqualTo("O Atlas está sem o modelo de linguagem agora. Tente de novo mais tarde.");
        assertThat(TetoDiario.MENSAGEM_NO_CHAT).isEqualTo("O Atlas atingiu o limite de uso de hoje desta parceria. Tente amanhã.");
    }
}
