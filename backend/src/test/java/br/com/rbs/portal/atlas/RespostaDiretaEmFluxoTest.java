package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoFluxo.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.conversa;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.eventos;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.falarEmFluxo;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.fim;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.juntar;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.sequencia;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.turnoSolto;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.exigirHistoricoValido;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.pedido;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.ApoioDoFluxo.Evento;
import br.com.rbs.portal.atlas.ferramentas.GerarModelo;
import br.com.rbs.portal.atlas.ferramentas.PedidoDeRelatorio;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.MensagemDeChat;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * [ATLAS] A resposta direta (M5) na resposta em fluxo (SSE) da rota 44: uma ida só ao modelo, a frase da etapa da ferramenta, o texto
 * que veio na mesma rodada, o {@code fim} com o turno solto, e o histórico válido para a mensagem seguinte. Não é
 * {@code @Transactional}: o turno em fluxo roda em outra thread (veja o ApoioDoFluxo).
 */
@TesteComLlmRoteirizado
class RespostaDiretaEmFluxoTest {

    static final String BRIEFING = "{\"tipo\":\"BRIEFING_CONTEUDO\"}";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProvedorRoteirizado modelo;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void guardarOQueJaExiste() {
        modelo.limpar();
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagarOQueOTesteConfirmou() {
        limpeza.apagar();
    }

    @Test
    void gerarModeloEmFluxoFazUmaIdaSoETerminaNoFim() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        modelo.chamada(GerarModelo.NOME, BRIEFING);   // o roteiro só tem este passo: uma segunda ida derruba o teste

        List<Evento> eventos = eventos(falarEmFluxo(pessoa, conversa, "Me dá um modelo de briefing."));

        assertThat(modelo.pedidos()).hasSize(1);
        assertThat(modelo.idasEmFluxo()).isEqualTo(1);
        assertThat(modelo.sobraDoRoteiro()).isZero();
        assertThat(sequencia(eventos)).containsExactly("etapa", "fim");
        assertThat(eventos.get(0).<String>ler("$.texto")).isEqualTo("Preparando o modelo…");
        fim(eventos);
        assertThat(turnoSolto(jdbc, conversa)).isTrue();
    }

    @Test
    void pedidoDeRelatorioEmFluxoFazUmaIdaSoComAEtapaDaResposta() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        modelo.chamada(PedidoDeRelatorio.NOME, "{}");

        List<Evento> eventos = eventos(falarEmFluxo(pessoa, conversa, "Quero o relatório da minha parceria."));

        assertThat(modelo.pedidos()).hasSize(1);
        assertThat(modelo.idasEmFluxo()).isEqualTo(1);
        assertThat(modelo.sobraDoRoteiro()).isZero();
        assertThat(sequencia(eventos)).containsExactly("etapa", "fim");
        assertThat(eventos.get(0).<String>ler("$.texto")).isEqualTo("Preparando a resposta…");
        assertThat(turnoSolto(jdbc, conversa)).isTrue();
    }

    @Test
    void oTextoDaMesmaRodadaVaiAntesDaEtapaEFicaNaTela() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        modelo.chamadaComTexto("Segue o modelo.", GerarModelo.NOME, BRIEFING);

        List<Evento> eventos = eventos(falarEmFluxo(pessoa, conversa, "Me dá um modelo de briefing."));

        assertThat(modelo.pedidos()).hasSize(1);
        assertThat(sequencia(eventos)).containsExactly("texto", "etapa", "fim");
        assertThat(juntar(eventos, "texto", "delta")).isEqualTo("Segue o modelo.");
    }

    @Test
    void historicoSegueValidoNaMensagemSeguinteEmFluxoComOutraChamadaNaRodada() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        modelo.chamadas(null, null, pedido(GerarModelo.NOME, BRIEFING), pedido(LoopDoAgenteTest.CONSULTAR, "{}"));
        falarEmFluxo(pessoa, conversa, "Me dá um modelo e os números.");
        assertThat(modelo.pedidos()).hasSize(1);

        modelo.texto("Posso ajudar com mais alguma coisa.");
        falarEmFluxo(pessoa, conversa, "Obrigado.");

        exigirHistoricoValido(modelo.ultimoPedido());
        assertThat(modelo.ultimoPedido().mensagens().stream().map(MensagemDeChat::papel).toList())
                .containsExactly("system", "user", "assistant", "tool", "tool", "user");
        assertThat(modelo.ultimoPedido().mensagens().stream().filter(m -> "tool".equals(m.papel())).map(MensagemDeChat::conteudo))
                .noneMatch(conteudo -> conteudo.contains("não chegou a responder"));
    }
}
