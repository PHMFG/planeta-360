package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.exigirHistoricoValido;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.nomesDasFerramentas;
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
 * [ATLAS] A conversa depois da rota 65 (M6), com o modelo de linguagem roteirizado: o relatório que o modelo abriu e deixou num cartão
 * é descartado pela tela, a chamada de ferramenta que o cartão deixou aberta recebe {@code cancel} (o histórico que vai ao modelo
 * fica válido) e a conversa não fica presa esperando resposta: a mensagem seguinte roda o loop, o modelo recebe de novo
 * {@code relatorio_iniciar} e começa outro relatório. Cada teste é desfeito no fim (@Transactional).
 */
@TesteComLlmRoteirizado
@Transactional
class DescarteDoRelatorioComLlmTest {

    private static final String AMARELO = "marketing@amarelo.example";

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
    }
}
