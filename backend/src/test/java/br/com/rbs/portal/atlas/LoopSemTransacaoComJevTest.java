package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.CADASTROS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.VISITANTES;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.FontesNoChatTest.BUSCAR;
import static br.com.rbs.portal.atlas.FontesNoChatTest.CONSULTA;
import static br.com.rbs.portal.atlas.FontesNoChatTest.PRAZO;
import static br.com.rbs.portal.atlas.FontesNoChatTest.busca;
import static br.com.rbs.portal.atlas.FontesNoChatTest.frasePrazo;
import static br.com.rbs.portal.atlas.FontesNoChatTest.marcador;
import static br.com.rbs.portal.atlas.LoopDoAgenteTest.ARGS_INICIAR;
import static br.com.rbs.portal.atlas.LoopDoAgenteTest.EDITAR;
import static br.com.rbs.portal.atlas.LoopDoAgenteTest.FINALIZAR;
import static br.com.rbs.portal.atlas.LoopDoAgenteTest.SOLICITAR;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemCartao;
import br.com.rbs.portal.atlas.AtlasDtos.ItemRelatorioPronto;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.AtlasDtos.RespostaDoCartaoRequisicao;
import br.com.rbs.portal.atlas.jev.ModoDoJev;
import br.com.rbs.portal.atlas.jev.PontoDoJev;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * [ATLAS] {@code LoopSemTransacaoTest} também com o JEV roteirizado (critério B do M7): nenhum ponto roda com transação aberta, inclusive
 * {@code lacunas} e {@code coerencia} (que consultam o JEV na fase 1 de uma ferramenta, antes da transação do loop). Uma conversa de
 * ponta a ponta com os SEIS pontos em ATIVO: a pergunta de documentos (intenção, busca com a relevância dos trechos, resposta
 * conferida), o relatório (intenção, {@code relatorio_iniciar} em código, lacunas do cartão), a mensagem com o cartão aberto, a resposta
 * do cartão e a coerência do parágrafo. A cada requisição ao JEV e a cada ida ao modelo, nenhuma transação aberta e nenhuma conexão do
 * pool em uso. Não é {@code @Transactional}: tudo é confirmado de verdade (por isso a transação, se existisse, apareceria), e o teste
 * apaga o que confirmou.
 */
@TesteComJevRoteirizado
@TestPropertySource(properties = {"atlas.diagnostico.ligado=true", "atlas.diagnostico.marca=true"})
class LoopSemTransacaoComJevTest {

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    JevRoteirizado jev;

    @Autowired
    ConfiguracaoDoJevDeTeste config;

    @Autowired
    JdbcClient jdbc;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void preparar() {
        modelo.limpar();
        jev.limpar();
        config.limpar().ligarTodos(ModoDoJev.ATIVO);
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagar() {
        config.limpar();
        limpeza.apagar();
    }

    private UsuarioAutenticado marca() {
        return usuario(jdbc, AMARELO);
    }

    private ConversaDto falar(long conversa, String texto) {
        return atlas.enviarMensagem(marca(), conversa, new MensagemRequisicao(texto, null, null), requisicao());
    }

    @Test
    void osSeisPontosAtivosNuncaRodamComTransacaoAbertaNemSegurandoConexao() {
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        long conversa = atlas.abrirConversa(marca(), parceria, null, requisicao()).conversaId();

        // 1. Pergunta de documentos: intenção (ramo), busca com a relevância dos trechos (em paralelo) e a resposta conferida
        jev.intencao("pergunta_documentos", 0.95).fontes(estado -> estado.contains(PRAZO) ? 0.9 : 0.4).resposta(frase -> "apoia", 0.97);
        modelo.chamada(BUSCAR, busca(CONSULTA)).passo(pedido -> new RespostaDoLlm(frasePrazo(marcador(pedido, PRAZO, true)), List.of(), null));
        falar(conversa, "Qual o prazo para informar os geradores próprios?");

        // 2. O relatório: a intenção abre o relatorio_iniciar em código; o modelo pede o cartão e o JEV julga as lacunas opcionais
        jev.intencao("montar_relatorio", 0.98).lacunas(estado -> estado.contains("Brindes disponíveis") ? 0.05 : 0.9);
        modelo.chamada(SOLICITAR, AtlasJson.escrever(Map.of("mensagem", "Preciso destes números.", "campos",
                List.of(VISITANTES, CADASTROS, "ativacao.brindes_disponiveis", "ativacao.brindes_distribuidos"))));
        ConversaDto comCartao = falar(conversa, "monta o relatório da ativação");
        ItemCartao cartao = comCartao.itens().stream().filter(ItemCartao.class::isInstance).map(ItemCartao.class::cast).findFirst().orElseThrow();
        assertThat(cartao.campos()).extracting(c -> c.chave()).containsExactly(VISITANTES, CADASTROS, "ativacao.brindes_distribuidos");

        // 3. Escreve com o cartão aberto: o JEV diz que é sobre os números dele, e a resposta fixa sai sem o modelo
        jev.cartao("responde_ao_cartao", 0.98);
        falar(conversa, "os brindes eu informo onde?");

        // 4. Responde o cartão (rota 46) e o modelo escreve o parágrafo: o JEV confere a coerência com os selos e o relatório fecha
        jev.coerencia("coerente", 0.96);
        String edicao = AtlasJson.escrever(Map.of("operacoes", List.of(
                Map.of("op", "definir_texto", "bloco", "b-resumo", "texto", LoopDoAgenteTest.RESUMO),
                Map.of("op", "definir_texto", "bloco", "b-publico", "texto", LoopDoAgenteTest.PUBLICO))));
        modelo.chamada(EDITAR, edicao).chamada(FINALIZAR, "{}");
        ConversaDto pronta = atlas.responderCartao(marca(), cartao.solicitacaoId(), new RespostaDoCartaoRequisicao("accept",
                ApoioDoAtlas.valores(VISITANTES, 1200L, CADASTROS, 1072L)), requisicao());
        assertThat(pronta.itens()).anyMatch(ItemRelatorioPronto.class::isInstance);

        // todos os pontos foram consultados, e nunca com transação aberta nem com conexão em uso
        assertThat(jev.recebidas().stream().map(JevRoteirizado.Recebida::ponto).distinct().toList())
                .containsExactlyInAnyOrder(PontoDoJev.values());
        assertThat(jev.algumaComTransacaoAberta()).as("transação aberta durante uma requisição ao JEV").isFalse();
        assertThat(jev.algumaComConexaoEmUso()).as("conexão do pool em uso durante uma requisição ao JEV").isFalse();
        assertThat(modelo.transacaoAtivaEmCadaPedido()).as("transação aberta em cada ida ao modelo").containsOnly(false);
        modelo.pedidos().forEach(ProvedorRoteirizado::exigirHistoricoValido);
        // as decisões foram ao banco depois, numa transação curta, e estão lá (confirmadas)
        assertThat(jdbc.sql("SELECT DISTINCT d.ponto FROM atlas_decisao d JOIN atlas_mensagem m ON m.id = d.mensagem_id WHERE m.conversa_id = :c")
                .param("c", conversa).query(String.class).list()).containsExactlyInAnyOrder("intencao", "cartao", "fontes", "resposta",
                "coerencia", "lacunas");
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_traco WHERE conversa_id = :c AND etapa IN ('J1_INTENCAO','J1_CARTAO','R3_FONTES',"
                + "'R5_RESPOSTA','V2_COERENCIA','LACUNAS')").param("c", conversa).query(Long.class).single()).isGreaterThanOrEqualTo(6);
    }

    @Autowired
    org.springframework.transaction.support.TransactionTemplate transacao;

    @Test
    void osDetectoresDoTesteEnxergamATransacaoEAConexaoQuandoElasExistem() {
        // a sentinela do teste: se o JEV fosse chamado dentro de uma transação, as duas medidas acusariam
        jev.fontes(estado -> 0.5);
        var pedido = new br.com.rbs.portal.atlas.jev.ProvedorDeJev.PedidoAoJev("estado", Map.of("relevante",
                br.com.rbs.portal.atlas.jev.ProvedorDeJev.PerguntaAoJev.nota("pergunta")));

        transacao.executeWithoutResult(estado -> {
            jdbc.sql("SELECT 1").query(Integer.class).single();
            jev.perguntar(pedido);
        });

        assertThat(jev.recebidas()).hasSize(1);
        assertThat(jev.algumaComTransacaoAberta()).isTrue();
        assertThat(jev.algumaComConexaoEmUso()).isTrue();
    }
}
