package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.CADASTROS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.VISITANTES;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.valores;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.exigirHistoricoValido;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.nomesDasFerramentas;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.MensagemDeChat;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.PedidoAoLlm;
import br.com.rbs.portal.dados.DadoInformadoService;
import br.com.rbs.portal.dados.OrigemDoDado;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

/**
 * [ATLAS] A conversa depois da rota 65 (M6), com o modelo de linguagem roteirizado: o relatório que o modelo abriu e deixou num cartão
 * é descartado pela tela, a chamada de ferramenta que o cartão deixou aberta recebe {@code cancel} (o histórico que vai ao modelo
 * fica válido) e a conversa não fica presa esperando resposta: a mensagem seguinte roda o loop, o modelo recebe de novo
 * {@code relatorio_iniciar} e começa outro relatório. Cada teste é desfeito no fim (@Transactional).
 */
@TesteComLlmRoteirizado
@Transactional
class DescarteDoRelatorioComLlmTest {

    private static final String AMARELO = "marketing@amarelo.example";
    /** O começo da nota que o prompt do turno ganha enquanto o relatório mais novo da conversa está descartado. */
    private static final String NOTA_DO_DESCARTE = "O relatório desta conversa foi descartado. Ele não segue mais: nunca diga que está "
            + "aberto nem que pode ser retomado. Se a pessoa quiser, comece outro com relatorio_iniciar.";
    private static final String AVISO_DO_DESCARTE = "Relatório descartado. Você pode pedir um novo quando quiser.";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    DadoInformadoService dados;

    @BeforeEach
    void zerarORoteiro() {
        modelo.limpar();
    }

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    long numero(MvcTestResult resposta, String caminho) {
        return ((Number) lerJson(resposta, caminho)).longValue();
    }

    long contar(String sql, Object... parametros) {
        var consulta = jdbc.sql(sql);
        for (Object parametro : parametros) {
            consulta = consulta.param(parametro);
        }
        return consulta.query(Long.class).single();
    }

    long abrirConversa(SessaoDeApi pessoa) {
        MvcTestResult resposta = pessoa.post("/api/parcerias/" + amarelo() + "/atlas/conversas", "{}");
        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        return numero(resposta, "$.conversaId");
    }

    MvcTestResult falar(SessaoDeApi pessoa, long conversa, String texto) {
        return pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"" + texto + "\"}");
    }

    long relatorioDaConversa(long conversa, String status) {
        return contar("SELECT id FROM atlas_relatorio WHERE conversa_id = ? AND status = ?", conversa, status);
    }

    static String prompt(PedidoAoLlm pedido) {
        return pedido.mensagens().get(0).conteudo();
    }

    /** O que a ferramenta do cartão devolveu ao modelo, como o histórico a guarda, lido do pedido ao modelo. */
    static Map<String, Object> respostaDoCartao(PedidoAoLlm pedido) {
        MensagemDeChat resposta = pedido.mensagens().stream()
                .filter(m -> "tool".equals(m.papel()) && LoopDoAgenteTest.SOLICITAR.equals(m.ferramenta())).findFirst().orElseThrow();
        return AtlasJson.ler(resposta.conteudo(), new TypeReference<Map<String, Object>>() {
        });
    }

    @Test
    void depoisDeDescartarOCartaoAbertoPeloModeloAConversaContinuaEOModeloPodeIniciarOutroRelatorio() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = abrirConversa(pessoa);
        modelo.chamada(LoopDoAgenteTest.INICIAR, LoopDoAgenteTest.ARGS_INICIAR).chamada(LoopDoAgenteTest.SOLICITAR, LoopDoAgenteTest.ARGS_CARTAO);
        MvcTestResult aberta = falar(pessoa, conversa, "Quero o relatório da ativação.");
        assertThat(lerLista(aberta, "$.itens[*].tipo")).containsExactly("usuario", "cartao");
        long primeiro = relatorioDaConversa(conversa, "AGUARDANDO_RESPOSTA");

        MvcTestResult descartado = pessoa.post("/api/atlas/relatorios/" + primeiro + "/descartar", null);

        assertThat(descartado).hasStatus(HttpStatus.OK);
        assertThat(lerJson(descartado, "$.status")).isEqualTo("DESCARTADO");
        assertThat(contar("SELECT count(*) FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id WHERE m.conversa_id = ? "
                + "AND t.resultado IS NULL AND t.erro IS NULL", conversa)).isZero();
        // a conversa não espera mais o cartão: outra mensagem roda o loop, e o modelo pode abrir o relatório de novo
        modelo.chamada(LoopDoAgenteTest.INICIAR, LoopDoAgenteTest.ARGS_INICIAR).texto("Comecei outro relatório da ativação.");

        MvcTestResult seguinte = falar(pessoa, conversa, "Pode começar de novo.");

        assertThat(seguinte).hasStatus(HttpStatus.OK);
        assertThat(lerLista(seguinte, "$.itens[*].tipo")).containsExactly("usuario", "texto");
        assertThat(lerJson(seguinte, "$.itens[1].texto")).isEqualTo("Comecei outro relatório da ativação.");
        assertThat(modelo.pedidos()).hasSize(4);   // 2 do primeiro turno (iniciar, cartão) e 2 do segundo (iniciar, texto)
        var primeiroPedidoDoSegundoTurno = modelo.pedidos().get(2);
        assertThat(nomesDasFerramentas(primeiroPedidoDoSegundoTurno)).contains(LoopDoAgenteTest.INICIAR)
                .doesNotContain(LoopDoAgenteTest.SOLICITAR, LoopDoAgenteTest.EDITAR, LoopDoAgenteTest.FINALIZAR);
        assertThat(modelo.pedidos()).allSatisfy(pedido -> exigirHistoricoValido(pedido));
        // o histórico que o modelo recebeu tem a chamada do cartão fechada com cancel
        var historico = primeiroPedidoDoSegundoTurno.mensagens();
        assertThat(historico.stream().filter(m -> "tool".equals(m.papel()) && LoopDoAgenteTest.SOLICITAR.equals(m.ferramenta())).toList())
                .singleElement().satisfies(m -> assertThat(m.conteudo()).contains("\"acao\":\"cancel\""));
        // dois relatórios na conversa: o antigo descartado, o novo em montagem
        assertThat(contar("SELECT count(*) FROM atlas_relatorio WHERE conversa_id = ?", conversa)).isEqualTo(2);
        assertThat(contar("SELECT count(*) FROM atlas_relatorio WHERE id = ? AND status = 'DESCARTADO'", primeiro)).isEqualTo(1);
        assertThat(relatorioDaConversa(conversa, "MONTANDO")).isGreaterThan(primeiro);
        assertThat(contar("SELECT count(*) FROM atlas_conversa WHERE id = ? AND turno_desde IS NULL", conversa)).isEqualTo(1);
        // o modelo foi avisado do descarte enquanto o relatório estava descartado, e só enquanto: com o outro relatório aberto, a nota sai
        assertThat(prompt(modelo.pedidos().get(0))).doesNotContain("foi descartado");
        assertThat(prompt(modelo.pedidos().get(1))).doesNotContain("foi descartado");
        assertThat(prompt(primeiroPedidoDoSegundoTurno)).contains(NOTA_DO_DESCARTE);
        assertThat(prompt(modelo.pedidos().get(3))).doesNotContain("foi descartado");
        assertThat(modelo.sobraDoRoteiro()).isZero();
    }

    @Test
    void depoisDeDescartarORelatorioEmMontagemOModeloNaoRecebeMaisAsFerramentasDeEdicaoMasPodeIniciarDeNovo() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = abrirConversa(pessoa);
        modelo.chamada(LoopDoAgenteTest.INICIAR, LoopDoAgenteTest.ARGS_INICIAR).texto("Comecei o relatório. Quer que eu continue?");
        falar(pessoa, conversa, "Comece o relatório da ativação.");
        long primeiro = relatorioDaConversa(conversa, "MONTANDO");
        modelo.limpar();
        modelo.texto("Posso começar outro quando você quiser.");

        assertThat(pessoa.post("/api/atlas/relatorios/" + primeiro + "/descartar", null)).hasStatus(HttpStatus.OK);
        MvcTestResult seguinte = falar(pessoa, conversa, "Obrigado.");

        assertThat(seguinte).hasStatus(HttpStatus.OK);
        List<String> ferramentas = nomesDasFerramentas(modelo.pedidos().get(0));
        assertThat(ferramentas).contains(LoopDoAgenteTest.INICIAR).doesNotContain(LoopDoAgenteTest.EDITAR, LoopDoAgenteTest.FINALIZAR);
        assertThat(ferramentas).containsAll(LoopDoAgenteTest.DO_CHAT);   // as ferramentas do chat continuam
        // em montagem não havia chamada aberta para fechar, e o histórico não ganha nada: quem avisa o modelo é a nota do prompt
        assertThat(prompt(modelo.pedidos().get(0))).contains(NOTA_DO_DESCARTE);
        assertThat(modelo.pedidos().get(0).mensagens().stream().filter(m -> "tool".equals(m.papel()))
                .map(MensagemDeChat::ferramenta)).containsExactly(LoopDoAgenteTest.INICIAR);
    }

    @Test
    void oModeloSabeQueORelatorioFoiDescartadoEAConversaMostraOAvisoDoDescarte() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = abrirConversa(pessoa);
        modelo.chamada(LoopDoAgenteTest.INICIAR, LoopDoAgenteTest.ARGS_INICIAR).chamada(LoopDoAgenteTest.SOLICITAR, LoopDoAgenteTest.ARGS_CARTAO);
        falar(pessoa, conversa, "Quero o relatório da ativação.");
        long relatorio = relatorioDaConversa(conversa, "AGUARDANDO_RESPOSTA");
        assertThat(pessoa.post("/api/atlas/relatorios/" + relatorio + "/descartar", null)).hasStatus(HttpStatus.OK);
        modelo.texto("Posso começar outro relatório quando você quiser.");

        MvcTestResult seguinte = falar(pessoa, conversa, "O relatório ainda está aberto?");

        assertThat(seguinte).hasStatus(HttpStatus.OK);
        // o modelo lê a nota no prompt do turno e, na resposta da chamada que o cartão deixou aberta, que o relatório foi descartado
        PedidoAoLlm pedido = modelo.ultimoPedido();
        assertThat(prompt(pedido)).contains(NOTA_DO_DESCARTE);
        assertThat(respostaDoCartao(pedido)).containsEntry("acao", "cancel").containsEntry("relatorioDescartado", true)
                .containsEntry("campos", List.of());
        exigirHistoricoValido(pedido);
        // a tela: o aviso de descarte, e não o de cartão fechado, na conversa relida (rota 45) e no histórico gravado
        MvcTestResult lida = pessoa.get("/api/atlas/conversas/" + conversa);
        assertThat(lerLista(lida, "$.itens[?(@.tipo=='aviso')].texto")).containsExactly(AVISO_DO_DESCARTE);
        assertThat(lerLista(lida, "$.itens[?(@.tipo=='cartao')].status")).containsExactly("CANCELADA");
        assertThat(contar("SELECT count(*) FROM atlas_mensagem WHERE conversa_id = ? AND papel = 'tool' "
                + "AND conteudo LIKE '%relatorioDescartado%'", conversa)).isEqualTo(1);
    }

    @Test
    void fecharOCartaoSemDescartarNaoAvisaDoDescarteNemMudaOAvisoDeCartaoFechado() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = abrirConversa(pessoa);
        modelo.chamada(LoopDoAgenteTest.INICIAR, LoopDoAgenteTest.ARGS_INICIAR).chamada(LoopDoAgenteTest.SOLICITAR, LoopDoAgenteTest.ARGS_CARTAO);
        MvcTestResult aberta = falar(pessoa, conversa, "Quero o relatório da ativação.");
        long cartao = numero(aberta, "$.itens[1].solicitacaoId");
        modelo.texto("Tudo bem, retomamos quando você tiver os números.");

        MvcTestResult fechada = pessoa.post("/api/atlas/solicitacoes/" + cartao + "/respostas", "{\"acao\":\"cancel\"}");

        assertThat(fechada).hasStatus(HttpStatus.OK);
        PedidoAoLlm pedido = modelo.ultimoPedido();
        assertThat(prompt(pedido)).doesNotContain("foi descartado");
        assertThat(respostaDoCartao(pedido)).containsEntry("acao", "cancel").doesNotContainKey("relatorioDescartado");
        assertThat(lerLista(pessoa.get("/api/atlas/conversas/" + conversa), "$.itens[?(@.tipo=='aviso')].texto"))
                .containsExactly("Cartão fechado. Quando tiver os números, peça para continuar.");
    }

    @Test
    void relatorioProntoDepoisDeUmDescartadoNaoLevaANotaDoDescarte() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = abrirConversa(pessoa);
        modelo.chamada(LoopDoAgenteTest.INICIAR, LoopDoAgenteTest.ARGS_INICIAR).texto("Comecei o relatório.");
        falar(pessoa, conversa, "Comece o relatório da ativação.");
        long primeiro = relatorioDaConversa(conversa, "MONTANDO");
        assertThat(pessoa.post("/api/atlas/relatorios/" + primeiro + "/descartar", null)).hasStatus(HttpStatus.OK);
        // com os dois números já informados, o modelo abre outro relatório e o deixa pronto sem cartão
        dados.gravar(usuario(jdbc, AMARELO), amarelo(), valores(VISITANTES, 1200L, CADASTROS, 1072L), OrigemDoDado.ATLAS, requisicao());
        modelo.limpar();
        modelo.chamada(LoopDoAgenteTest.INICIAR, LoopDoAgenteTest.ARGS_INICIAR)
                .chamada(LoopDoAgenteTest.EDITAR, LoopDoAgenteTest.edicao("b-resumo", LoopDoAgenteTest.RESUMO, "b-publico", LoopDoAgenteTest.PUBLICO))
                .chamada(LoopDoAgenteTest.FINALIZAR, "{}");
        falar(pessoa, conversa, "Monte o relatório da ativação.");
        assertThat(relatorioDaConversa(conversa, "PRONTO")).isGreaterThan(primeiro);
        modelo.limpar();
        modelo.texto("Foi um prazer ajudar.");

        falar(pessoa, conversa, "Obrigado.");

        assertThat(prompt(modelo.ultimoPedido())).doesNotContain("foi descartado");
    }
}
