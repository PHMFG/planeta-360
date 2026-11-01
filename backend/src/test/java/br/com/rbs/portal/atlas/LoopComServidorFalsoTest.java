package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.CADASTROS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.VISITANTES;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.valores;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemCartao;
import br.com.rbs.portal.atlas.AtlasDtos.ItemRelatorioPronto;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.AtlasDtos.RespostaDoCartaoRequisicao;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorSpringAi;
import br.com.rbs.portal.atlas.provedor.ServidorDeLlmFalso;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] O loop de ponta a ponta com o Spring AI de verdade: a API sobe com {@code spring.ai.model.chat=openai} apontando para
 * um servidor HTTP falso e local (nenhum provedor real é chamado, a chave é de mentira). O servidor guarda o que recebe, então
 * dá para ver, no corpo e nos cabeçalhos de cada ida ao modelo: o {@code thinking} aninhado, o {@code User-Agent} próprio, o
 * {@code x-opencode-session} com o id da conversa, as ferramentas do estado, os ids de pedido nossos e o
 * {@code reasoning_content} que o modelo mandou antes da pausa do cartão voltando na retomada, remontado do banco. Cada teste é
 * desfeito no fim (@Transactional). O resumo do raciocínio é pedido ao servidor falso, mas gravado por outra thread, que não
 * enxerga as linhas ainda não confirmadas do teste: aqui não há item {@code raciocinio} (ele, com o {@code resumo}, está no
 * AtlasFluxoTest), e o raciocínio cru nunca aparece nos itens.
 */
@TesteDeIntegracao
@Transactional
class LoopComServidorFalsoTest {

    private static final String CHAVE = "chave-de-mentira-so-do-teste";
    private static final ServidorDeLlmFalso SERVIDOR = ServidorDeLlmFalso.iniciar();

    @DynamicPropertySource
    static void ligarOModeloNoServidorFalso(DynamicPropertyRegistry registro) {
        registro.add("spring.ai.model.chat", () -> "openai");
        registro.add("spring.ai.openai.api-key", () -> CHAVE);
        registro.add("spring.ai.openai.base-url", SERVIDOR::urlBase);
        registro.add("spring.ai.openai.max-retries", () -> "0");
    }

    @AfterAll
    static void derrubarOServidor() {
        SERVIDOR.close();
    }

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorDeLlm provedor;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void limpar() {
        SERVIDOR.limpar();
    }

    @Test
    void oProvedorDaApiEODoSpringAi() {
        assertThat(provedor).isInstanceOf(ProvedorSpringAi.class);
    }

    @Test
    void oRaciocinioGravadoAntesDaPausaVoltaNaRetomadaEOCorpoSaiComoOGoPede() {
        UsuarioAutenticado marca = usuario(jdbc, AMARELO);
        long amarelo = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        long conversa = atlas.abrirConversa(marca, amarelo, null, requisicao()).conversaId();
        // o servidor repete o mesmo id de pedido ("call_do_servidor"): quem o torna único na conversa é o loop
        // como o DeepSeek com o raciocínio ligado: 400 se o reasoning_content de um assistente com ferramentas não voltar
        SERVIDOR.exigirRaciocinio(true);
        SERVIDOR.responder(ServidorDeLlmFalso.chamada("call_do_servidor", "relatorio_iniciar", LoopDoAgenteTest.ARGS_INICIAR, "Preciso criar o relatório."));
        SERVIDOR.responder(ServidorDeLlmFalso.chamada("call_do_servidor", "relatorio_solicitar_campos", LoopDoAgenteTest.ARGS_CARTAO,
                "Faltam dois números da agência."));

        ConversaDto aberta = atlas.enviarMensagem(marca, conversa, new MensagemRequisicao("Quero o relatório da ativação.", null, null), requisicao());

        ItemCartao cartao = aberta.itens().stream().filter(ItemCartao.class::isInstance).map(ItemCartao.class::cast).findFirst().orElseThrow();
        assertThat(SERVIDOR.recebidas()).hasSize(2);
        // 1ª ida: só a ferramenta do estado "sem relatório", o raciocínio ligado (thinking enabled) e os cabeçalhos próprios
        var primeira = SERVIDOR.recebidas().get(0);
        assertThat(JsonPath.<List<String>>read(primeira.corpo(), "$.tools[*].function.name")).containsExactly("parceria_consultar", "buscar_documentos", "contar_dias", "calcular", "relatorio_iniciar");
        assertThat(primeira.corpo()).contains("\"thinking\":{\"type\":\"enabled\"}");
        assertThat(JsonPath.<String>read(primeira.corpo(), "$.messages[0].role")).isEqualTo("system");
        assertThat(JsonPath.<String>read(primeira.corpo(), "$.messages[0].content")).contains("Cliente Amarelo");
        assertThat(JsonPath.<String>read(primeira.corpo(), "$.messages[1].content")).isEqualTo("Quero o relatório da ativação.");
        for (var recebida : SERVIDOR.recebidas()) {
            assertThat(recebida.caminho()).isEqualTo("/inference/go/openai/v1/chat/completions");
            assertThat(recebida.cabecalho("user-agent")).containsExactly("PlanetaAtlas/0.1");
            assertThat(recebida.cabecalho("x-opencode-session")).containsExactly(String.valueOf(conversa));
            assertThat(recebida.cabecalho("authorization")).containsExactly("Bearer " + CHAVE);
        }
        // 2ª ida: o resultado de relatorio_iniciar e o raciocínio da 1ª resposta, com o id que o loop deu ao pedido
        var segunda = SERVIDOR.recebidas().get(1);
        assertThat(JsonPath.<List<String>>read(segunda.corpo(), "$.tools[*].function.name"))
                .containsExactly("parceria_consultar", "buscar_documentos", "contar_dias", "calcular", "relatorio_solicitar_campos", "relatorio_editar", "relatorio_finalizar");
        assertThat(JsonPath.<List<String>>read(segunda.corpo(), "$.messages[*].role")).containsExactly("system", "user", "assistant", "tool");
        assertThat(JsonPath.<String>read(segunda.corpo(), "$.messages[2].reasoning_content")).isEqualTo("Preciso criar o relatório.");
        String idDoPrimeiro = JsonPath.read(segunda.corpo(), "$.messages[2].tool_calls[0].id");
        assertThat(idDoPrimeiro).startsWith("call_").isNotEqualTo("call_do_servidor");
        assertThat(JsonPath.<String>read(segunda.corpo(), "$.messages[3].tool_call_id")).isEqualTo(idDoPrimeiro);

        // o raciocínio das duas respostas ficou no banco
        assertThat(jdbc.sql("SELECT reasoning_content FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'assistant' ORDER BY ordem")
                .param("c", conversa).query(String.class).list()).containsExactly("Preciso criar o relatório.", "Faltam dois números da agência.");

        // a pessoa responde o cartão (rota 46): a retomada remonta tudo do banco, e o raciocínio da pausa volta
        String edicao = LoopDoAgenteTest.edicao("b-resumo", LoopDoAgenteTest.RESUMO, "b-publico", LoopDoAgenteTest.PUBLICO);
        SERVIDOR.responder(ServidorDeLlmFalso.chamada("call_do_servidor", "relatorio_editar", edicao, "Vou redigir."));
        SERVIDOR.responder(ServidorDeLlmFalso.chamada("call_do_servidor", "relatorio_finalizar", "{}", null));
        ConversaDto pronta = atlas.responderCartao(marca, cartao.solicitacaoId(),
                new RespostaDoCartaoRequisicao("accept", valores(VISITANTES, 1200L, CADASTROS, 1072L)), requisicao());

        assertThat(pronta.itens()).extracting(i -> i.getClass().getSimpleName()).containsExactly("ItemRelatorioPronto");
        assertThat(pronta.itens().get(0)).isInstanceOf(ItemRelatorioPronto.class);
        // o raciocínio cru não vira item (nem aqui, onde o resumo não chega a ser gravado, nem na rota 46)
        assertThat(aberta.itens()).extracting(i -> i.getClass().getSimpleName()).containsExactly("ItemUsuario", "ItemCartao");
        assertThat(aberta.toString() + pronta).doesNotContain("Preciso criar o relatório.").doesNotContain("Faltam dois números");
        assertThat(SERVIDOR.recebidas()).hasSize(4);
        // um resumo por mensagem do assistente com raciocínio (as duas do 1º turno e a 1ª da retomada; a última veio sem), à parte
        assertThat(SERVIDOR.resumosRecebidos()).hasSize(3);
        var retomada = SERVIDOR.recebidas().get(2).corpo();
        assertThat(JsonPath.<List<String>>read(retomada, "$.messages[*].role"))
                .containsExactly("system", "user", "assistant", "tool", "assistant", "tool");
        assertThat(JsonPath.<String>read(retomada, "$.messages[2].reasoning_content")).isEqualTo("Preciso criar o relatório.");
        assertThat(JsonPath.<String>read(retomada, "$.messages[4].reasoning_content")).isEqualTo("Faltam dois números da agência.");
        String idDoCartao = JsonPath.read(retomada, "$.messages[4].tool_calls[0].id");
        assertThat(JsonPath.<String>read(retomada, "$.messages[4].tool_calls[0].function.name")).isEqualTo("relatorio_solicitar_campos");
        assertThat(JsonPath.<String>read(retomada, "$.messages[5].tool_call_id")).isEqualTo(idDoCartao).isNotEqualTo(idDoPrimeiro);
        assertThat(JsonPath.<String>read(retomada, "$.messages[5].content")).contains("\"acao\":\"accept\"", "\"ref\":\"dado:ativacao.visitantes_total\"");
        assertThat(JsonPath.<List<String>>read(retomada, "$.tools[*].function.name"))
                .containsExactly("parceria_consultar", "buscar_documentos", "contar_dias", "calcular", "relatorio_solicitar_campos", "relatorio_editar", "relatorio_finalizar");
        for (var recebida : SERVIDOR.recebidas()) {
            assertThat(recebida.corpo()).contains("\"thinking\":{\"type\":\"enabled\"}");
            assertThat(recebida.cabecalho("user-agent")).containsExactly("PlanetaAtlas/0.1");
        }
    }

    @Test
    void falhaDoProvedorHttpTerminaOTurnoComAFraseFixaESemAChaveNoQueVoltaParaAPessoa() {
        UsuarioAutenticado marca = usuario(jdbc, AMARELO);
        long amarelo = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        long conversa = atlas.abrirConversa(marca, amarelo, null, requisicao()).conversaId();
        SERVIDOR.responderComStatus(401, "{\"error\":{\"message\":\"Incorrect API key provided: " + CHAVE + "\"}}");

        ConversaDto turno = atlas.enviarMensagem(marca, conversa, new MensagemRequisicao("Oi", null, null), requisicao());

        assertThat(turno.itens()).extracting(Object::toString).noneMatch(texto -> texto.contains(CHAVE));
        assertThat(jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'assistant'").param("c", conversa)
                .query(String.class).list()).containsExactly(br.com.rbs.portal.atlas.loop.LoopDoAgente.FRASE_DA_FALHA);
    }
}
