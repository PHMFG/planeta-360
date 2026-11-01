package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.lerLista;
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
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

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
    void oFimTrazOsItensDaRespostaDiretaEARotaQuarentaECincoOsRelEComOMesmoFormato() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        modelo.chamadas(null, null, pedido(PedidoDeRelatorio.NOME, "{}"), pedido(GerarModelo.NOME, BRIEFING));

        Evento fim = fim(eventos(falarEmFluxo(pessoa, conversa, "Quero o relatório e um modelo de briefing.")));

        assertThat(fim.<List<String>>ler("$.itens[*].tipo")).containsExactly("usuario", "pedido_relatorio", "modelo");
        Map<String, Object> pedidoDoFim = fim.<List<Map<String, Object>>>ler("$.itens[?(@.tipo=='pedido_relatorio')]").get(0);
        assertThat(pedidoDoFim.keySet()).containsExactly("tipo", "texto", "relatorioOficial", "modelos");
        assertThat(pedidoDoFim).containsEntry("relatorioOficial", false);
        assertThat((String) pedidoDoFim.get("texto")).isEqualTo(PedidoDeRelatorio.texto(false));
        assertThat(pedidoDoFim.get("modelos")).isEqualTo(List.of(
                Map.of("tipo", "BRIEFING_CONTEUDO", "titulo", "Briefing de conteúdo"),
                Map.of("tipo", "PERGUNTAS_PESQUISA", "titulo", "Perguntas da pesquisa"),
                Map.of("tipo", "PLANO_OPERACIONAL_ATIVACAO", "titulo", "Plano operacional da ativação")));
        Map<String, Object> modeloDoFim = fim.<List<Map<String, Object>>>ler("$.itens[?(@.tipo=='modelo')]").get(0);
        assertThat(modeloDoFim.keySet()).containsExactly("tipo", "titulo", "conteudo", "nomeArquivo");
        assertThat(modeloDoFim).containsEntry("titulo", "Briefing de conteúdo").containsEntry("nomeArquivo", "briefing-de-conteudo.md");
        assertThat((String) modeloDoFim.get("conteudo")).startsWith("# Briefing de conteúdo");

        // a rota 45 devolve os mesmos dois itens
        MvcTestResult inteira = pessoa.get("/api/atlas/conversas/" + conversa);
        assertThat(lerLista(inteira, "$.itens[?(@.tipo=='pedido_relatorio')]")).containsExactly(pedidoDoFim);
        assertThat(lerLista(inteira, "$.itens[?(@.tipo=='modelo')]")).containsExactly(modeloDoFim);
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
        modelo.chamadas(null, null, pedido(GerarModelo.NOME, BRIEFING), pedido(LoopDoAgenteTest.CONSULTAR, "{}")).texto("Aqui estão os números.");
        falarEmFluxo(pessoa, conversa, "Me dá um modelo e os números.");
        assertThat(modelo.pedidos()).as("a direta e a consulta na mesma rodada: o turno segue").hasSize(2);

        modelo.texto("Posso ajudar com mais alguma coisa.");
        falarEmFluxo(pessoa, conversa, "Obrigado.");

        exigirHistoricoValido(modelo.ultimoPedido());
        assertThat(modelo.ultimoPedido().mensagens().stream().map(MensagemDeChat::papel).toList())
                .containsExactly("system", "user", "assistant", "tool", "tool", "assistant", "user");
        assertThat(modelo.ultimoPedido().mensagens().stream().filter(m -> "tool".equals(m.papel())).map(MensagemDeChat::conteudo))
                .noneMatch(conteudo -> conteudo.contains("não chegou a responder"));
    }

    @Test
    void relatorioPosEventoEUmaPerguntaNaMesmaMensagemMostraOCartaoEResponde() {
        // "quero o relatório pós-evento, e me fala até quando informo os geradores": a resposta direta e a outra pergunta
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        modelo.chamadas(null, null, pedido(PedidoDeRelatorio.NOME, "{}"), pedido(LoopDoAgenteTest.CONSULTAR, "{}"))
                .textoEmPedacos(null, "Os geradores próprios ", "precisam ser informados ", "antes do evento.");

        List<Evento> eventos = eventos(falarEmFluxo(pessoa, conversa, "Quero o relatório pós-evento, e me fala até quando informo os geradores?"));

        assertThat(modelo.pedidos()).hasSize(2);
        assertThat(modelo.idasEmFluxo()).isEqualTo(2);
        assertThat(sequencia(eventos)).as("as duas etapas seguidas, o texto da segunda ida e o fim").containsExactly("etapa", "texto", "fim");
        assertThat(eventos.stream().filter(e -> e.nome().equals("etapa")).map(e -> e.<String>ler("$.texto")).toList())
                .containsExactly("Preparando a resposta…", "Consultando os números da parceria…");
        assertThat(juntar(eventos, "texto", "delta")).isEqualTo("Os geradores próprios precisam ser informados antes do evento.");
        Evento fim = fim(eventos);
        assertThat(fim.<List<String>>ler("$.itens[*].tipo")).containsExactly("usuario", "pedido_relatorio", "texto");
        // o modelo recebeu o aviso de que o servidor já mostrou a resposta, e as diretas saíram da oferta
        MensagemDeChat doPedido = modelo.ultimoPedido().mensagens().stream()
                .filter(m -> "tool".equals(m.papel()) && PedidoDeRelatorio.NOME.equals(m.ferramenta())).findFirst().orElseThrow();
        assertThat(doPedido.conteudo()).contains("O servidor já mostrou esta resposta à pessoa", "Não a repita");
        assertThat(ProvedorRoteirizado.nomesDasFerramentas(modelo.ultimoPedido())).doesNotContain(PedidoDeRelatorio.NOME, GerarModelo.NOME);
        assertThat(turnoSolto(jdbc, conversa)).isTrue();
    }
}
