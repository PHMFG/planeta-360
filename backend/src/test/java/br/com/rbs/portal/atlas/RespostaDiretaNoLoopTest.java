package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.CADASTROS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.VISITANTES;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.valores;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.exigirHistoricoValido;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.pedido;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemCartao;
import br.com.rbs.portal.atlas.AtlasDtos.ItemRelatorioPronto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemTexto;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.AtlasDtos.RespostaDoCartaoRequisicao;
import br.com.rbs.portal.atlas.FerramentaDeTeste.Comportamento;
import br.com.rbs.portal.atlas.loop.LoopDoAgente;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.MensagemDeChat;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.dados.DadoInformadoService;
import br.com.rbs.portal.dados.OrigemDoDado;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] A resposta direta no loop (M5, o {@code returnDirect} feito no nosso loop), com uma ferramenta de teste que responde direto:
 * o turno acaba sem nova ida ao modelo; as outras chamadas da rodada rodam e têm resposta gravada; o histórico segue válido para a
 * mensagem seguinte (sem o erro "não chegou a responder"); o cartão e o relatório pronto da mesma rodada mandam, como sempre; e o
 * limite de 8 chamadas por turno continua valendo. As ferramentas reais de resposta direta estão em GerarModeloNoChatTest e
 * PedidoDeRelatorioNoChatTest. Cada teste é desfeito no fim (@Transactional).
 */
@TesteComLlmRoteirizado
@Import(FerramentaDeTesteConfig.class)
@Transactional
class RespostaDiretaNoLoopTest {

    static final String DIRETA = FerramentaDeTeste.NOME;

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    FerramentaDeTeste ferramenta;

    @Autowired
    DadoInformadoService dados;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void zerar() {
        modelo.limpar();
        ferramenta.limpar();
        ferramenta.comportamento(Comportamento.RESPOSTA_DIRETA);
    }

    UsuarioAutenticado marca() {
        return usuario(jdbc, AMARELO);
    }

    long abrir() {
        return atlas.abrirConversa(marca(), SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"), null, requisicao()).conversaId();
    }

    ConversaDto falar(long conversa, String texto) {
        return atlas.enviarMensagem(marca(), conversa, new MensagemRequisicao(texto, null, null), requisicao());
    }

    ConversaDto responder(long solicitacao, String acao) {
        return atlas.responderCartao(marca(), solicitacao, new RespostaDoCartaoRequisicao(acao, valores()), requisicao());
    }

    List<String> papeis(long conversa) {
        return jdbc.sql("SELECT papel FROM atlas_mensagem WHERE conversa_id = :c ORDER BY ordem").param("c", conversa).query(String.class).list();
    }

    /** O que as ferramentas devolveram ao modelo, na ordem do histórico. */
    List<String> respostas(long conversa) {
        return jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'tool' ORDER BY ordem")
                .param("c", conversa).query(String.class).list();
    }

    static List<String> papeis(List<MensagemDeChat> mensagens) {
        return mensagens.stream().map(MensagemDeChat::papel).toList();
    }

    ItemCartao cartaoDe(ConversaDto conversa) {
        return conversa.itens().stream().filter(ItemCartao.class::isInstance).map(ItemCartao.class::cast).findFirst().orElseThrow();
    }

    void iniciarORelatorio(long conversa) {
        modelo.chamada(LoopDoAgenteTest.INICIAR, LoopDoAgenteTest.ARGS_INICIAR).texto("Vamos montar o relatório.");
        falar(conversa, "Quero o relatório da ativação.");
        modelo.limpar();
    }

    // ---------------------------------------------------------------------
    // O turno acaba na resposta direta
    // ---------------------------------------------------------------------

    @Test
    void respostaDiretaEncerraOTurnoSemNovaIdaAoModelo() {
        long conversa = abrir();
        modelo.chamada(DIRETA, "{}");   // o roteiro só tem este passo: uma segunda ida ao modelo derruba o teste

        falar(conversa, "Preciso disto.");

        assertThat(modelo.pedidos()).hasSize(1);
        assertThat(modelo.sobraDoRoteiro()).isZero();
        assertThat(papeis(conversa)).containsExactly("user", "assistant", "tool");
        assertThat(respostas(conversa)).containsExactly("{\"direta\":true}");
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id "
                + "WHERE m.conversa_id = :c AND t.ferramenta = :f AND t.resultado IS NOT NULL AND t.erro IS NULL")
                .param("c", conversa).param("f", DIRETA).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void oTextoDaMesmaRodadaFicaQuandoAprovado() {
        long conversa = abrir();
        modelo.chamadaComTexto("Aqui vai o que você pediu.", DIRETA, "{}");

        ConversaDto turno = falar(conversa, "Preciso disto.");

        assertThat(modelo.pedidos()).hasSize(1);
        assertThat(turno.itens().stream().filter(ItemTexto.class::isInstance).map(ItemTexto.class::cast).map(ItemTexto::texto))
                .containsExactly("Aqui vai o que você pediu.");
    }

    @Test
    void rodadaSoComRespostasDiretasEncerraOTurno() {
        long conversa = abrir();
        modelo.chamadas(null, null, pedido(DIRETA, "{}"), pedido(DIRETA, "{}"));   // o roteiro só tem este passo

        falar(conversa, "Preciso disto, duas vezes.");

        assertThat(modelo.pedidos()).hasSize(1);
        assertThat(modelo.sobraDoRoteiro()).isZero();
        assertThat(respostas(conversa)).containsExactly("{\"direta\":true}", "{\"direta\":true}");
    }

    @Test
    void asOutrasChamadasDaRodadaRodamAntesEDepoisDaDiretaETemRespostaGravadaEOTurnoSegueComUmaNovaIda() {
        long conversa = abrir();
        modelo.chamadas(null, null, pedido(LoopDoAgenteTest.CONSULTAR, "{}"), pedido(DIRETA, "{}"), pedido(LoopDoAgenteTest.CONSULTAR, "{}"))
                .texto("Aqui está o resto.");

        ConversaDto turno = falar(conversa, "Preciso disto.");

        assertThat(modelo.pedidos()).as("a rodada tinha uma direta e outras ferramentas: segue com uma nova ida").hasSize(2);
        assertThat(modelo.sobraDoRoteiro()).isZero();
        assertThat(papeis(conversa)).containsExactly("user", "assistant", "tool", "tool", "tool", "assistant");
        List<String> respostas = respostas(conversa);
        assertThat(respostas.get(0)).contains("\"dados\"");
        assertThat(respostas.get(1)).isEqualTo("{\"direta\":true}");
        assertThat(respostas.get(2)).contains("\"dados\"");
        assertThat(ferramenta.registros()).extracting(FerramentaDeTeste.Registro::fase).containsExactly("preparar", "executar");
        assertThat(turno.itens().stream().filter(ItemTexto.class::isInstance).map(ItemTexto.class::cast).map(ItemTexto::texto))
                .containsExactly("Aqui está o resto.");
        // a nova ida leva o histórico inteiro, válido, com a resposta de cada chamada
        exigirHistoricoValido(modelo.ultimoPedido());
        assertThat(papeis(modelo.ultimoPedido().mensagens())).containsExactly("system", "user", "assistant", "tool", "tool", "tool");
    }

    @Test
    void asDiretasSaemDaOfertaNaNovaIdaENasSeguintesDoTurnoMasVoltamNoTurnoSeguinte() {
        long conversa = abrir();
        modelo.chamadas(null, null, pedido(DIRETA, "{}"), pedido(LoopDoAgenteTest.CONSULTAR, "{}"))
                .chamada(LoopDoAgenteTest.CONSULTAR, "{}").texto("Pronto.");

        falar(conversa, "Preciso disto e dos números.");

        assertThat(ProvedorRoteirizado.nomesDasFerramentas(modelo.pedidos().get(0))).contains(DIRETA, "gerar_modelo", "pedido_de_relatorio");
        for (int ida = 1; ida <= 2; ida++) {
            assertThat(ProvedorRoteirizado.nomesDasFerramentas(modelo.pedidos().get(ida))).as("ida " + (ida + 1))
                    .doesNotContain(DIRETA, "gerar_modelo", "pedido_de_relatorio").contains(LoopDoAgenteTest.CONSULTAR, "buscar_documentos");
        }
        // no turno seguinte elas voltam
        modelo.limpar();
        modelo.texto("Certo.");
        falar(conversa, "Obrigado.");
        assertThat(ProvedorRoteirizado.nomesDasFerramentas(modelo.ultimoPedido())).contains(DIRETA, "gerar_modelo", "pedido_de_relatorio");
    }

    @Test
    void seOModeloPedirDeNovoUmaDiretaQueSaiuDaOfertaRecebeErroENadaRoda() {
        long conversa = abrir();
        modelo.chamadas(null, null, pedido(DIRETA, "{}"), pedido(LoopDoAgenteTest.CONSULTAR, "{}")).chamada(DIRETA, "{}").texto("Pronto.");

        falar(conversa, "Preciso disto e dos números.");

        assertThat(modelo.pedidos()).hasSize(3);
        assertThat(ferramenta.registros()).as("a segunda direta não chegou a rodar").extracting(FerramentaDeTeste.Registro::fase)
                .containsExactly("preparar", "executar");
        assertThat(respostas(conversa).get(respostas(conversa).size() - 1)).contains(LoopDoAgente.ERRO_DA_DIRETA_JA_MOSTRADA);
        exigirHistoricoValido(modelo.ultimoPedido());
    }

    @Test
    void outraFerramentaNumaRodadaEDepoisSoDiretaNaoEncerraOTurnoSemResponderAoResultadoDaOutra() {
        long conversa = abrir();
        // [consulta] na primeira rodada e [direta] na segunda: o resultado da consulta ainda não foi respondido
        modelo.chamada(LoopDoAgenteTest.CONSULTAR, "{}").chamada(DIRETA, "{}").texto("Aqui está o resto.");

        ConversaDto turno = falar(conversa, "Me diz os números e me dá isto.");

        assertThat(modelo.pedidos()).as("segue com uma ida sem as diretas, como quando as duas vêm na mesma rodada").hasSize(3);
        assertThat(modelo.sobraDoRoteiro()).isZero();
        assertThat(papeis(conversa)).containsExactly("user", "assistant", "tool", "assistant", "tool", "assistant");
        assertThat(ProvedorRoteirizado.nomesDasFerramentas(modelo.ultimoPedido())).doesNotContain(DIRETA, "gerar_modelo", "pedido_de_relatorio")
                .contains(LoopDoAgenteTest.CONSULTAR);
        exigirHistoricoValido(modelo.ultimoPedido());
        assertThat(turno.itens().stream().filter(ItemTexto.class::isInstance).map(ItemTexto.class::cast).map(ItemTexto::texto))
                .containsExactly("Aqui está o resto.");
    }

    @Test
    void aDiretaSozinhaNoTurnoSemOutraFerramentaContinuaEncerrandoOTurno() {
        long conversa = abrir();
        modelo.chamada(DIRETA, "{}");

        falar(conversa, "Preciso disto.");

        assertThat(modelo.pedidos()).hasSize(1);
        assertThat(modelo.sobraDoRoteiro()).isZero();
        assertThat(papeis(conversa)).containsExactly("user", "assistant", "tool");
    }

    @Test
    void oErroDeOutraFerramentaEmUmaRodadaAnteriorTambemFazADiretaDaSeguinteNaoEncerrar() {
        long conversa = abrir();
        // relatorio_finalizar sem relatório em montagem dá erro; ele não é direta, e o modelo ainda não respondeu a ele
        modelo.chamada(LoopDoAgenteTest.FINALIZAR, "{}").chamada(DIRETA, "{}").texto("Não há relatório em montagem.");

        falar(conversa, "Finalize o relatório e me dá isto.");

        assertThat(modelo.pedidos()).hasSize(3);
        assertThat(modelo.sobraDoRoteiro()).isZero();
    }

    @Test
    void diretaPedidaDeNovoDepoisDeSairDaOfertaContaNoLimiteDeChamadasEOTurnoAcaba() {
        long conversa = abrir();
        // a primeira rodada tem uma consulta e uma direta (2 chamadas); o modelo insiste na direta em toda ida, 30 vezes
        modelo.chamadas(null, null, pedido(LoopDoAgenteTest.CONSULTAR, "{}"), pedido(DIRETA, "{}"));
        for (int i = 0; i < 30; i++) {
            modelo.chamada(DIRETA, "{}");
        }
        modelo.texto("Pronto.");

        ConversaDto turno = falar(conversa, "Preciso disto e dos números.");

        // 2 chamadas na primeira ida, mais uma por ida repetida: a oitava é a ida 7, e a da ida 8 estoura o limite
        assertThat(modelo.pedidos()).as("a direta repetida conta no limite: uma ida por chamada, e não uma por insistência do roteiro")
                .hasSize(LoopDoAgente.LIMITE_DE_CHAMADAS_POR_TURNO);
        assertThat(modelo.sobraDoRoteiro()).isGreaterThan(20);
        List<String> respostas = respostas(conversa);
        assertThat(respostas.get(respostas.size() - 1)).contains(LoopDoAgente.ERRO_DO_LIMITE);
        assertThat(respostas.get(respostas.size() - 2)).contains(LoopDoAgente.ERRO_DA_DIRETA_JA_MOSTRADA);
        assertThat(ferramenta.registros()).as("a direta só rodou uma vez").extracting(FerramentaDeTeste.Registro::fase)
                .containsExactly("preparar", "executar");
        assertThat(turno.itens().stream().filter(ItemTexto.class::isInstance).map(ItemTexto.class::cast).map(ItemTexto::texto))
                .containsExactly(LoopDoAgente.FRASE_DO_LIMITE);
    }

    @Test
    void outraFerramentaDaRodadaQueDaErroTambemFazOTurnoSeguirParaOModeloVerOErro() {
        long conversa = abrir();
        // relatorio_finalizar sem relatório em montagem dá erro: ele não é uma resposta direta, e volta ao modelo
        modelo.chamadas(null, null, pedido(DIRETA, "{}"), pedido(LoopDoAgenteTest.FINALIZAR, "{}")).texto("Não há relatório em montagem.");

        ConversaDto turno = falar(conversa, "Preciso disto e finalize o relatório.");

        assertThat(modelo.pedidos()).hasSize(2);
        assertThat(respostas(conversa).get(1)).contains("erro");
        assertThat(turno.itens().stream().filter(ItemTexto.class::isInstance).map(ItemTexto.class::cast).map(ItemTexto::texto))
                .containsExactly("Não há relatório em montagem.");
    }

    @Test
    void historicoSegueValidoParaAMensagemSeguinteSemOErroSinteticoDeFerramenta() {
        long conversa = abrir();
        modelo.chamadas(null, null, pedido(DIRETA, "{}"), pedido(LoopDoAgenteTest.CONSULTAR, "{}")).texto("Aqui está o resto.");
        falar(conversa, "Preciso disto.");

        modelo.texto("Posso ajudar com mais alguma coisa.");
        falar(conversa, "Obrigado.");

        exigirHistoricoValido(modelo.ultimoPedido());
        assertThat(papeis(modelo.ultimoPedido().mensagens())).containsExactly("system", "user", "assistant", "tool", "tool", "assistant", "user");
        assertThat(modelo.ultimoPedido().mensagens().stream().filter(m -> "tool".equals(m.papel())).map(MensagemDeChat::conteudo))
                .noneMatch(conteudo -> conteudo.contains("não chegou a responder"))
                .anyMatch(conteudo -> conteudo.equals("{\"direta\":true}"));
    }

    @Test
    void soUmaDiretaNaRodadaEncerraEOHistoricoSegueValidoNaMensagemSeguinte() {
        long conversa = abrir();
        modelo.chamada(DIRETA, "{}");
        falar(conversa, "Preciso disto.");

        modelo.texto("Posso ajudar com mais alguma coisa.");
        falar(conversa, "Obrigado.");

        exigirHistoricoValido(modelo.ultimoPedido());
        assertThat(papeis(modelo.ultimoPedido().mensagens())).containsExactly("system", "user", "assistant", "tool", "user");
    }

    // ---------------------------------------------------------------------
    // O cartão e o relatório pronto mandam
    // ---------------------------------------------------------------------

    @Test
    void comOCartaoNaMesmaRodadaOCartaoMandaEOQueRodouFicaGravado() {
        long conversa = abrir();
        iniciarORelatorio(conversa);
        modelo.chamadas(null, null, pedido(DIRETA, "{}"), pedido(LoopDoAgenteTest.SOLICITAR, LoopDoAgenteTest.ARGS_CARTAO));

        ConversaDto turno = falar(conversa, "Preciso disto e de uns números.");

        assertThat(modelo.pedidos()).hasSize(1);
        ItemCartao cartao = cartaoDe(turno);
        assertThat(cartao.status()).isEqualTo("PENDENTE");
        assertThat(respostas(conversa)).hasSize(2);   // a do relatório_iniciar (turno anterior) e a da direta; a do cartão está pendente
        assertThat(respostas(conversa).get(1)).isEqualTo("{\"direta\":true}");

        // depois de responder o cartão, o histórico que o modelo recebe é válido e tem a resposta da direta
        modelo.texto("Tudo bem, sigo sem esses números.");
        responder(cartao.solicitacaoId(), "decline");
        exigirHistoricoValido(modelo.ultimoPedido());
        assertThat(modelo.ultimoPedido().mensagens().stream().filter(m -> "tool".equals(m.papel())).map(MensagemDeChat::conteudo))
                .noneMatch(conteudo -> conteudo.contains("não chegou a responder")).contains("{\"direta\":true}");
    }

    @Test
    void diretaDepoisDoCartaoNaRodadaNaoRodaComoSempreFoi() {
        long conversa = abrir();
        iniciarORelatorio(conversa);
        modelo.chamadas(null, null, pedido(LoopDoAgenteTest.SOLICITAR, LoopDoAgenteTest.ARGS_CARTAO), pedido(DIRETA, "{}"));

        falar(conversa, "Preciso de uns números e disto.");

        assertThat(ferramenta.registros()).isEmpty();
        assertThat(respostas(conversa).get(1)).contains("Aguardando a resposta do cartão");
    }

    @Test
    void comORelatorioProntoNaMesmaRodadaOProntoMandaEADiretaRodou() {
        dados.gravar(marca(), SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"), valores(VISITANTES, 1200L, CADASTROS, 1072L),
                OrigemDoDado.ATLAS, requisicao());
        long conversa = abrir();
        modelo.chamada(LoopDoAgenteTest.INICIAR, LoopDoAgenteTest.ARGS_INICIAR)
                .chamada(LoopDoAgenteTest.EDITAR, LoopDoAgenteTest.edicao("b-resumo", LoopDoAgenteTest.RESUMO, "b-publico", LoopDoAgenteTest.PUBLICO))
                .chamadas(null, null, pedido(DIRETA, "{}"), pedido(LoopDoAgenteTest.FINALIZAR, "{}"));

        ConversaDto turno = falar(conversa, "Monte o relatório da ativação.");

        assertThat(turno.itens()).anyMatch(ItemRelatorioPronto.class::isInstance);
        assertThat(modelo.pedidos()).hasSize(3);
        assertThat(modelo.sobraDoRoteiro()).isZero();
        assertThat(ferramenta.registros()).extracting(FerramentaDeTeste.Registro::fase).containsExactly("preparar", "executar");
        assertThat(respostas(conversa)).contains("{\"direta\":true}");
    }

    // ---------------------------------------------------------------------
    // O limite de chamadas por turno continua valendo
    // ---------------------------------------------------------------------

    @Test
    void diretaQueJaRodouEncerraOTurnoMesmoQuandoUmaChamadaDepoisEstouraOLimite() {
        long conversa = abrir();
        for (int i = 0; i < 7; i++) {
            modelo.chamada(LoopDoAgenteTest.CONSULTAR, "{}");   // sete chamadas, uma por ida
        }
        modelo.chamadas(null, null, pedido(DIRETA, "{}"), pedido(LoopDoAgenteTest.CONSULTAR, "{}"));   // a oitava roda, a nona estoura

        falar(conversa, "Preciso de muita coisa.");

        assertThat(modelo.pedidos()).hasSize(8);
        assertThat(papeis(conversa).get(papeis(conversa).size() - 1)).isEqualTo("tool");   // sem a frase do limite no fim
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_mensagem WHERE conversa_id = :c AND conteudo = :f").param("c", conversa)
                .param("f", LoopDoAgente.FRASE_DO_LIMITE).query(Long.class).single()).isZero();
        List<String> respostas = respostas(conversa);
        assertThat(respostas.get(respostas.size() - 2)).isEqualTo("{\"direta\":true}");
        assertThat(respostas.get(respostas.size() - 1)).contains(LoopDoAgente.ERRO_DO_LIMITE);
    }

    @Test
    void diretaQueEstouraOLimiteNaoRodaEOTurnoTerminaComAFraseDoLimite() {
        long conversa = abrir();
        for (int i = 0; i < 7; i++) {
            modelo.chamada(LoopDoAgenteTest.CONSULTAR, "{}");
        }
        modelo.chamadas(null, null, pedido(LoopDoAgenteTest.CONSULTAR, "{}"), pedido(DIRETA, "{}"));   // a nona (a direta) estoura

        ConversaDto turno = falar(conversa, "Preciso de muita coisa.");

        assertThat(ferramenta.registros()).isEmpty();
        assertThat(turno.itens().stream().filter(ItemTexto.class::isInstance).map(ItemTexto.class::cast).map(ItemTexto::texto))
                .containsExactly(LoopDoAgente.FRASE_DO_LIMITE);
    }
}
