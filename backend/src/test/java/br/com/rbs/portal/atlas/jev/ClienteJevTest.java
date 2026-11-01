package br.com.rbs.portal.atlas.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.atlas.diagnostico.ConfiguracaoDeProvedores;
import br.com.rbs.portal.atlas.diagnostico.ServidorHttpFalso;
import br.com.rbs.portal.atlas.jev.ProvedorDeJev.PedidoAoJev;
import br.com.rbs.portal.atlas.jev.ProvedorDeJev.PerguntaAoJev;
import br.com.rbs.portal.atlas.jev.ProvedorDeJev.RespostaDaPergunta;
import br.com.rbs.portal.atlas.jev.ProvedorDeJev.RespostaDoJev;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * [ATLAS] O cliente HTTP do JEV (M7) contra um servidor falso: o formato da requisição (caminho, cabeçalho da chave, corpo, a ordem das
 * perguntas e das opções) e o da resposta ({@code choice} com {@code confidence} e {@code probabilities}, {@code noul}), o tempo
 * limite e cada erro (400, 401, 413, 415, 422, 429, 529 e os outros) virando {@link JevIndisponivelException} sem nova tentativa e
 * sem a chave na mensagem. Nenhum teste chama a API de verdade.
 */
class ClienteJevTest {

    private static final String CHAVE = "jev-chave-de-mentira-9f8e7d6c";
    private static final ServidorHttpFalso JEV = ServidorHttpFalso.iniciar();

    @AfterAll
    static void derrubar() {
        JEV.close();
    }

    @BeforeEach
    void limpar() {
        JEV.limpar();
    }

    private static ClienteJev cliente(String chave, String urlBase, Duration prazo) {
        return new ClienteJev(new ConfiguracaoDeProvedores(chave, urlBase, "jev-1.14.0", "sombra", "", "", ""), prazo,
                Duration.ofMillis(300));
    }

    private ClienteJev cliente() {
        return cliente(CHAVE, JEV.urlBase(), Duration.ofMillis(800));
    }

    private static PedidoAoJev pedido() {
        Map<String, String> criterios = new LinkedHashMap<>();
        criterios.put("prazo", "Pergunta sobre um prazo");
        criterios.put("relatorio", "Pedido de relatório");
        criterios.put("outro", "Qualquer outro assunto");
        Map<String, PerguntaAoJev> perguntas = new LinkedHashMap<>();
        perguntas.put("assunto", PerguntaAoJev.escolha("Sobre o que é a mensagem?", criterios));
        perguntas.put("relevante", PerguntaAoJev.nota("A mensagem pergunta sobre um prazo?"));
        return new PedidoAoJev("Até quando informo os geradores?", perguntas);
    }

    private static final String RESPOSTA_BOA = """
            {"answers":{"assunto":{"type":"choice","choice":"prazo","confidence":0.93,
                                   "probabilities":{"prazo":0.93,"relatorio":0.04,"outro":0.03}},
                        "relevante":{"type":"noul","noul":0.81}}}""";

    @Test
    void mandaOCorpoNoFormatoDaDocDoJev() {
        JEV.responder(200, RESPOSTA_BOA);

        cliente().perguntar(pedido());

        var recebida = JEV.ultima();
        assertThat(recebida.metodo()).isEqualTo("POST");
        assertThat(recebida.caminho()).isEqualTo("/v1/systemone");
        assertThat(recebida.cabecalho("authorization")).containsExactly("Bearer " + CHAVE);
        assertThat(recebida.cabecalho("content-type")).containsExactly("application/json");
        assertThat(recebida.corpo()).isEqualTo("{\"model\":\"jev-1.14.0\",\"state\":\"Até quando informo os geradores?\",\"questions\":{"
                + "\"assunto\":{\"type\":\"choice\",\"instructions\":\"Sobre o que é a mensagem?\","
                + "\"criteria\":{\"prazo\":\"Pergunta sobre um prazo\",\"relatorio\":\"Pedido de relatório\",\"outro\":\"Qualquer outro assunto\"}},"
                + "\"relevante\":{\"type\":\"noul\",\"instructions\":\"A mensagem pergunta sobre um prazo?\"}}}");
    }

    @Test
    void leAEscolhaComAConfiancaEAsProbabilidadesEOValorDaNoul() {
        JEV.responder(200, RESPOSTA_BOA);

        RespostaDoJev resposta = cliente().perguntar(pedido());

        RespostaDaPergunta assunto = resposta.respostas().get("assunto");
        assertThat(assunto.escolha()).isEqualTo("prazo");
        assertThat(assunto.confianca()).isEqualTo(0.93);
        assertThat(assunto.noul()).isNull();
        assertThat(assunto.probabilidades()).isEqualTo(Map.of("prazo", 0.93, "relatorio", 0.04, "outro", 0.03));
        RespostaDaPergunta relevante = resposta.respostas().get("relevante");
        assertThat(relevante.noul()).isEqualTo(0.81);
        assertThat(relevante.escolha()).isNull();
        assertThat(relevante.confianca()).isNull();
        assertThat(resposta.latenciaMs()).isBetween(0L, 800L);
    }

    @Test
    void perguntaSemRespostaOuForaDoFormatoNaoApareceNoResultado() {
        JEV.responder(200, "{\"answers\":{\"assunto\":{\"type\":\"choice\",\"choice\":\"prazo\"},\"relevante\":{\"type\":\"noul\",\"noul\":\"alto\"}}}");

        RespostaDoJev resposta = cliente().perguntar(pedido());

        assertThat(resposta.respostas()).isEmpty();   // sem confidence a escolha não vale, e a noul não é número
    }

    @Test
    void oModeloVemDaConfiguracaoEVaiNoCorpo() {
        JEV.responder(200, RESPOSTA_BOA);
        ClienteJev outro = new ClienteJev(new ConfiguracaoDeProvedores(CHAVE, JEV.urlBase(), "jev-1.13.0", "sombra", "", "", ""),
                Duration.ofMillis(800), Duration.ofMillis(300));

        outro.perguntar(pedido());

        assertThat(outro.modelo()).isEqualTo("jev-1.13.0");
        assertThat(JEV.ultima().corpo()).startsWith("{\"model\":\"jev-1.13.0\",");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 413, 415, 422, 429, 500, 503, 529})
    void cadaStatusQueNaoE200CaiParaOCaminhoDoLlmSemNovaTentativaESemAChaveNaMensagem(int status) {
        JEV.responder(status, "{\"error\":{\"message\":\"Bearer " + CHAVE + " recusada\"}}");

        assertThatThrownBy(() -> cliente().perguntar(pedido()))
                .isInstanceOf(JevIndisponivelException.class)
                .satisfies(erro -> {
                    assertThat(((JevIndisponivelException) erro).motivo()).isEqualTo("HTTP_" + status);
                    assertThat(erro.getMessage()).doesNotContain(CHAVE).doesNotContain("recusada");
                });
        assertThat(JEV.recebidas()).as("nenhuma nova tentativa").hasSize(1);
    }

    @Test
    void passandoDoPrazoOTempoLimiteCaiSemEsperarAResposta() {
        JEV.responderDepoisDe(Duration.ofMillis(900), 200, RESPOSTA_BOA);
        ClienteJev lento = cliente(CHAVE, JEV.urlBase(), Duration.ofMillis(200));

        long inicio = System.nanoTime();
        assertThatThrownBy(() -> lento.perguntar(pedido())).isInstanceOf(JevIndisponivelException.class)
                .satisfies(erro -> assertThat(((JevIndisponivelException) erro).motivo()).isEqualTo("TIMEOUT"));
        long ms = (System.nanoTime() - inicio) / 1_000_000;

        assertThat(ms).as("esperou só o prazo, e não os 900 ms da resposta").isLessThan(700);
        assertThat(JEV.recebidas()).hasSize(1);
    }

    @Test
    void oPrazoPadraoDaRequisicaoE800MsEODaConexaoE300Ms() {
        assertThat(ClienteJev.PRAZO_DA_REQUISICAO).isEqualTo(Duration.ofMillis(800));
        assertThat(ClienteJev.PRAZO_DA_CONEXAO).isEqualTo(Duration.ofMillis(300));
    }

    @Test
    void servidorForaDoArCaiSemMostrarAChave() {
        String urlMorta;
        try (ServidorHttpFalso morto = ServidorHttpFalso.iniciar()) {
            urlMorta = morto.urlBase();
        }

        assertThatThrownBy(() -> cliente(CHAVE, urlMorta, Duration.ofMillis(800)).perguntar(pedido()))
                .isInstanceOf(JevIndisponivelException.class)
                .satisfies(erro -> {
                    assertThat(((JevIndisponivelException) erro).motivo()).matches("REDE_\\w+|TIMEOUT_DA_CONEXAO");
                    assertThat(erro.getMessage()).doesNotContain(CHAVE);
                });
    }

    @Test
    void respostaQueNaoEJsonOuSemAnswersEInvalida() {
        JEV.responder(200, "isto não é json");
        JEV.responder(200, "{\"resultado\":1}");
        JEV.responder(200, "");

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> cliente().perguntar(pedido())).isInstanceOf(JevIndisponivelException.class)
                    .satisfies(erro -> assertThat(((JevIndisponivelException) erro).motivo()).isEqualTo("RESPOSTA_INVALIDA"));
        }
    }

    @Test
    void semChaveNaoChamaNinguem() {
        assertThatThrownBy(() -> cliente("", JEV.urlBase(), Duration.ofMillis(800)).perguntar(pedido()))
                .isInstanceOf(JevIndisponivelException.class)
                .satisfies(erro -> assertThat(((JevIndisponivelException) erro).motivo()).isEqualTo("SEM_CHAVE"));
        assertThat(JEV.recebidas()).isEmpty();
    }

    @Test
    void redirecionamentoNaoESeguidoParaAChaveNaoIrParaOutroEndereco() {
        JEV.responderComCabecalho(302, "", "Location", "http://127.0.0.1:1/roubo");

        assertThatThrownBy(() -> cliente().perguntar(pedido())).isInstanceOf(JevIndisponivelException.class)
                .satisfies(erro -> assertThat(((JevIndisponivelException) erro).motivo()).isEqualTo("HTTP_302"));
        assertThat(JEV.recebidas()).hasSize(1);
    }

    @Test
    void aUrlBaseComBarraNoFimFunciona() {
        JEV.responder(200, RESPOSTA_BOA);

        cliente(CHAVE, JEV.urlBase() + "/", Duration.ofMillis(800)).perguntar(pedido());

        assertThat(JEV.ultima().caminho()).isEqualTo("/v1/systemone");
    }
}
