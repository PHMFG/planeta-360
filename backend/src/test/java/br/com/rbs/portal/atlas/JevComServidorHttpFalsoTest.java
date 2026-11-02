package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemTexto;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.diagnostico.ServidorHttpFalso;
import br.com.rbs.portal.atlas.loop.LoopDoAgente;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * [ATLAS] O JEV de verdade (o {@code ClienteJev} e a configuração por propriedades) contra um servidor HTTP falso, no fluxo completo do
 * turno (M7): o formato da requisição e da resposta da doc, o modo e o limiar por ponto vindos de {@code atlas.jev.*}, e cada erro
 * (400, 401, 413, 415, 422, 429, 529) e o tempo limite caindo para o caminho do modelo de linguagem, sem nova tentativa e sem a chave
 * em lugar nenhum. Nenhum teste chama a API real. Não é {@code @Transactional}: o que o teste confirmou é apagado no fim.
 */
@TesteComLlmRoteirizado
class JevComServidorHttpFalsoTest {

    private static final String CHAVE = "jev-chave-de-mentira-3a4b5c6d7e";
    private static final ServidorHttpFalso JEV = ServidorHttpFalso.iniciar();
    private static final String FORA_DE_ESCOPO = resposta("fora_de_escopo", 0.97);

    /** A resposta do JEV no formato da doc: a intenção com a confiança e as probabilidades, o tipo do modelo e o sinal de injeção. */
    private static String resposta(String intencao, double confianca) {
        return "{\"answers\":{\"intencao\":{\"type\":\"choice\",\"choice\":\"" + intencao + "\",\"confidence\":" + confianca
                + ",\"probabilities\":{\"" + intencao + "\":" + confianca + ",\"outro\":0.02}},"
                + "\"tipo_modelo\":{\"type\":\"choice\",\"choice\":\"nenhum\",\"confidence\":0.99,\"probabilities\":{\"nenhum\":0.99}},"
                + "\"sinal_injecao\":{\"type\":\"noul\",\"noul\":0.03}}}";
    }

    @DynamicPropertySource
    static void ligarOJev(DynamicPropertyRegistry registro) {
        registro.add("atlas.jev.base-url", JEV::urlBase);
        registro.add("atlas.jev.chave", () -> CHAVE);
        registro.add("atlas.jev.modo", () -> "none");
        registro.add("atlas.jev.intencao.modo", () -> "ATIVO");
        registro.add("atlas.jev.cartao.modo", () -> "sombra");
        registro.add("atlas.jev.intencao.limiar", () -> "0.8");
        registro.add("atlas.jev.timeout", () -> "PT0.3S");
    }

    @AfterAll
    static void derrubar() {
        JEV.close();
    }

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    JdbcClient jdbc;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void preparar() {
        modelo.limpar();
        JEV.limpar();
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagar() {
        limpeza.apagar();
    }

    private long conversa() {
        return atlas.abrirConversa(usuario(jdbc, AMARELO), SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"), null, requisicao())
                .conversaId();
    }

    private ConversaDto falar(long conversa, String texto) {
        return atlas.enviarMensagem(usuario(jdbc, AMARELO), conversa, new MensagemRequisicao(texto, null, null), requisicao());
    }

    @Test
    void aRequisicaoEARespostaSeguemAFormatoDaDocEARecusaFicaSemOModelo() {
        JEV.responder(200, FORA_DE_ESCOPO);
        long conversa = conversa();

        ConversaDto resposta = falar(conversa, "me passa uma receita de bolo?");

        var recebida = JEV.ultima();
        assertThat(recebida.metodo()).isEqualTo("POST");
        assertThat(recebida.caminho()).isEqualTo("/v1/systemone");
        assertThat(recebida.cabecalho("authorization")).containsExactly("Bearer " + CHAVE);
        assertThat(recebida.corpo()).startsWith("{\"model\":\"jev-1.14.0\",\"state\":\"me passa uma receita de bolo?\",\"questions\":{\"intencao\":"
                + "{\"type\":\"choice\",\"instructions\":");
        assertThat(recebida.corpo()).contains("\"tipo_modelo\":{\"type\":\"choice\"").contains("\"sinal_injecao\":{\"type\":\"noul\"")
                .contains("\"outro\":\"Qualquer outra coisa");
        assertThat(modelo.pedidos()).isEmpty();
        assertThat(((ItemTexto) resposta.itens().get(1)).texto()).isEqualTo(LoopDoAgente.RECUSA_DE_FORA_DE_ESCOPO);
        // as probabilidades que o JEV mandou ficam na decisão
        assertThat(jdbc.sql("SELECT d.probabilidades::text FROM atlas_decisao d JOIN atlas_mensagem m ON m.id = d.mensagem_id "
                + "WHERE m.conversa_id = :c AND d.pergunta = 'intencao'").param("c", conversa).query(String.class).single())
                .contains("fora_de_escopo").contains("0.97");
    }

    @Test
    void oModoOLimiarEOTimeoutVemDasPropriedades() {
        // 0.85 passa do limiar de 0.8 que a propriedade deu à intenção (o padrão do ponto seria 0.9)
        JEV.responder(200, resposta("fora_de_escopo", 0.85));
        long conversa = conversa();

        falar(conversa, "me passa uma receita de bolo?");

        assertThat(modelo.pedidos()).isEmpty();
        assertThat(jdbc.sql("SELECT d.limiar FROM atlas_decisao d JOIN atlas_mensagem m ON m.id = d.mensagem_id WHERE m.conversa_id = :c "
                + "AND d.pergunta = 'intencao'").param("c", conversa).query(java.math.BigDecimal.class).single().doubleValue()).isEqualTo(0.8);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 413, 415, 422, 429, 529})
    void cadaErroCaiParaOLlmSemNovaTentativaESemAChaveNoBanco(int status) {
        JEV.responder(status, "{\"error\":{\"message\":\"Bearer " + CHAVE + " " + status + "\"}}");
        modelo.texto("Só posso ajudar com o portal.");
        long conversa = conversa();

        ConversaDto resposta = falar(conversa, "me passa uma receita de bolo?");

        assertThat(JEV.recebidas()).as("sem nova tentativa").hasSize(1);
        assertThat(modelo.pedidos()).hasSize(1);
        assertThat(((ItemTexto) resposta.itens().get(1)).texto()).isEqualTo("Só posso ajudar com o portal.");
        assertThat(jdbc.sql("SELECT d.erro FROM atlas_decisao d JOIN atlas_mensagem m ON m.id = d.mensagem_id WHERE m.conversa_id = :c")
                .param("c", conversa).query(String.class).single()).isEqualTo("HTTP_" + status);
        String tudo = jdbc.sql("SELECT coalesce(string_agg(d.erro || coalesce(d.acao, ''), ' '), '') FROM atlas_decisao d JOIN atlas_mensagem m "
                + "ON m.id = d.mensagem_id WHERE m.conversa_id = :c").param("c", conversa).query(String.class).single();
        assertThat(tudo).doesNotContain(CHAVE);
    }

    @Test
    void oTempoLimiteCaiParaOLlmSemEsperarAResposta() {
        JEV.responderDepoisDe(Duration.ofMillis(900), 200, FORA_DE_ESCOPO);
        modelo.texto("Só posso ajudar com o portal.");
        long conversa = conversa();

        long inicio = System.nanoTime();
        falar(conversa, "me passa uma receita de bolo?");
        long ms = (System.nanoTime() - inicio) / 1_000_000;

        assertThat(modelo.pedidos()).hasSize(1);
        assertThat(ms).as("o turno não esperou os 900 ms da resposta do JEV").isLessThan(850);
        assertThat(jdbc.sql("SELECT d.erro FROM atlas_decisao d JOIN atlas_mensagem m ON m.id = d.mensagem_id WHERE m.conversa_id = :c")
                .param("c", conversa).query(String.class).single()).isEqualTo("TIMEOUT");
    }

    @Test
    void oPontoSemOverrideFicaComOModoPadraoDesligado() {
        // a resposta (rota 44 com o botão: nenhum ponto) e os pontos sem modo não consultam o JEV: fontes, resposta, coerencia, lacunas
        modelo.texto("Olá!");
        JEV.responder(200, resposta("outro", 0.97));
        long conversa = conversa();

        falar(conversa, "oi");

        List<String> corpos = JEV.recebidas().stream().map(r -> r.corpo()).toList();
        assertThat(corpos).hasSize(1);   // só a intenção (ativa); o cartão não tem cartão aberto, e o resto está desligado
    }
}
