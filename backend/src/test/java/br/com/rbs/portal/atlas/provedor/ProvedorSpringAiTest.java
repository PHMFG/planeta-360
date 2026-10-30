package br.com.rbs.portal.atlas.provedor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.ChamadaDeFerramenta;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.DefinicaoDeFerramenta;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.MensagemDeChat;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.PedidoAoLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * [ATLAS] O {@link ProvedorSpringAi} contra um servidor HTTP falso e local, com a configuração REAL do application.yml e a
 * autoconfiguração REAL do Spring AI 2.0.2 (sem chamar o provedor de verdade, sem chave de verdade). Confere o que sai pela rede:
 * o {@code thinking} aninhado ({@code "thinking": {"type": "enabled"}}, ou {@code "disabled"} com {@code atlas.raciocinio=false}),
 * a resposta em fluxo (os pedaços do raciocínio e do texto, a chamada de ferramenta montada), o {@code User-Agent} próprio (um só valor), o
 * {@code x-opencode-session} com o id da conversa, o {@code reasoning_content} gravado voltando na mensagem do assistente, as
 * ferramentas em JSON Schema e a mensagem de ferramenta; e o que vem de volta: texto, {@code tool_calls}, raciocínio, erro sem
 * chave. Também prova que {@code ATLAS_LLM=none} (o padrão) e a chave em branco deixam o portal sem modelo.
 */
class ProvedorSpringAiTest {

    private static final String CHAVE = "chave-de-mentira-so-do-teste";
    private static final String ESQUEMA = "{\"type\":\"object\",\"properties\":{\"modelo\":{\"type\":\"string\",\"enum\":[\"ATIVACAO_EVENTO\"]}},"
            + "\"required\":[\"modelo\"],\"additionalProperties\":false}";

    ServidorDeLlmFalso servidor;

    @BeforeEach
    void subir() {
        servidor = ServidorDeLlmFalso.iniciar();
    }

    @AfterEach
    void derrubar() {
        servidor.close();
    }

    ApplicationContextRunner contexto(String... propriedades) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())   // o application.yml de verdade
                .withConfiguration(AutoConfigurations.of(OpenAiChatAutoConfiguration.class, ToolCallingAutoConfiguration.class))
                .withUserConfiguration(LlmConfig.class)
                .withPropertyValues(propriedades);
    }

    ApplicationContextRunner ligado() {
        return contexto("spring.ai.model.chat=openai", "spring.ai.openai.api-key=" + CHAVE,
                "spring.ai.openai.base-url=" + servidor.urlBase(), "spring.ai.openai.max-retries=0");
    }

    PedidoAoLlm pedido() {
        List<ChamadaDeFerramenta> chamadas = List.of(new ChamadaDeFerramenta("call_1", "relatorio_iniciar", "{\"modelo\":\"ATIVACAO_EVENTO\"}"));
        return new PedidoAoLlm("42", List.of(
                MensagemDeChat.sistema("Você é o Atlas."),
                MensagemDeChat.usuario("Quero o relatório."),
                MensagemDeChat.assistente(null, chamadas, "Vou iniciar o relatório."),
                MensagemDeChat.resultado("call_1", "relatorio_iniciar", "{\"relatorioId\":7}"),
                MensagemDeChat.usuario("Pode seguir.")),
                List.of(new DefinicaoDeFerramenta("relatorio_iniciar", "Cria o relatório.", ESQUEMA)));
    }

    // ---------------------------------------------------------------------
    // O que sai pela rede
    // ---------------------------------------------------------------------

    @Test
    void corpoECabecalhosEnviadosAoProvedor() {
        servidor.responder(ServidorDeLlmFalso.texto("Certo."));

        ligado().run(contexto -> {
            RespostaDoLlm resposta = contexto.getBean(ProvedorDeLlm.class).responder(pedido());

            assertThat(resposta.texto()).isEqualTo("Certo.");
            var recebida = servidor.ultima();
            assertThat(recebida.metodo()).isEqualTo("POST");
            assertThat(recebida.caminho()).isEqualTo("/inference/go/openai/v1/chat/completions");

            // o thinking vai no topo do JSON, aninhado, e não achatado nem como texto; ligado por padrão (atlas.raciocinio)
            String corpo = recebida.corpo();
            assertThat(JsonPath.<Object>read(corpo, "$.thinking")).isEqualTo(Map.of("type", "enabled"));
            assertThat(corpo).contains("\"thinking\":{\"type\":\"enabled\"}");
            assertThat(JsonPath.<String>read(corpo, "$.model")).isEqualTo("deepseek-v4.1-flash");
            assertThat(JsonPath.<Double>read(corpo, "$.temperature")).isEqualTo(0.2);
            assertThat(corpo).doesNotContain("\"stream\":true");

            // as mensagens no formato de chat completions, com o reasoning_content gravado voltando no assistente
            assertThat(JsonPath.<List<String>>read(corpo, "$.messages[*].role")).containsExactly("system", "user", "assistant", "tool", "user");
            assertThat(JsonPath.<String>read(corpo, "$.messages[0].content")).isEqualTo("Você é o Atlas.");
            assertThat(JsonPath.<String>read(corpo, "$.messages[2].reasoning_content")).isEqualTo("Vou iniciar o relatório.");
            assertThat(JsonPath.<String>read(corpo, "$.messages[2].tool_calls[0].id")).isEqualTo("call_1");
            assertThat(JsonPath.<String>read(corpo, "$.messages[2].tool_calls[0].function.name")).isEqualTo("relatorio_iniciar");
            assertThat(JsonPath.<String>read(corpo, "$.messages[2].tool_calls[0].function.arguments")).isEqualTo("{\"modelo\":\"ATIVACAO_EVENTO\"}");
            assertThat(JsonPath.<String>read(corpo, "$.messages[3].tool_call_id")).isEqualTo("call_1");
            assertThat(JsonPath.<String>read(corpo, "$.messages[3].content")).isEqualTo("{\"relatorioId\":7}");

            // as ferramentas, em JSON Schema, sem o modo estrito
            assertThat(JsonPath.<String>read(corpo, "$.tools[0].function.name")).isEqualTo("relatorio_iniciar");
            assertThat(JsonPath.<Boolean>read(corpo, "$.tools[0].function.parameters.additionalProperties")).isFalse();
            assertThat(JsonPath.<List<String>>read(corpo, "$.tools[0].function.parameters.required")).containsExactly("modelo");

            // os cabeçalhos: user agent próprio (UM valor só), a sessão da conversa e a autorização
            assertThat(recebida.cabecalho("user-agent")).containsExactly("PlanetaAtlas/0.1");
            assertThat(recebida.cabecalho("x-opencode-session")).containsExactly("42");
            assertThat(recebida.cabecalho("authorization")).containsExactly("Bearer " + CHAVE);
        });
    }

    @Test
    void comAtlasRaciocinioFalsoOThinkingVaiDesligado() {
        servidor.responder(ServidorDeLlmFalso.texto("Certo."));

        contexto("spring.ai.model.chat=openai", "spring.ai.openai.api-key=" + CHAVE, "spring.ai.openai.base-url=" + servidor.urlBase(),
                "spring.ai.openai.max-retries=0", "atlas.raciocinio=false").run(contexto -> {
            ProvedorDeLlm provedor = contexto.getBean(ProvedorDeLlm.class);
            assertThat(((ProvedorSpringAi) provedor).raciocinio()).isFalse();
            provedor.responder(pedido());
            assertThat(JsonPath.<Object>read(servidor.ultima().corpo(), "$.thinking")).isEqualTo(Map.of("type", "disabled"));
        });
    }

    @Test
    void emFluxoOsPedacosChegamAoOuvinteEARespostaSaiMontada() {
        servidor.responderEmFluxo(new ServidorDeLlmFalso.Fluxo().raciocinio("Vou ").raciocinio("iniciar.")
                .chamada("call_9", "relatorio_iniciar").argumentosEmPedacos("{\"modelo\":\"ATIVACAO_EVENTO\"}", 5).fim("tool_calls"));
        servidor.responderEmFluxo(new ServidorDeLlmFalso.Fluxo().raciocinio("Respondo.").texto("Pron").texto("to.").fim("stop"));

        ligado().run(contexto -> {
            ProvedorDeLlm provedor = contexto.getBean(ProvedorDeLlm.class);
            List<String> raciocinio = new java.util.ArrayList<>();
            List<String> texto = new java.util.ArrayList<>();
            ProvedorDeLlm.OuvinteDoFluxo ouvinte = new ProvedorDeLlm.OuvinteDoFluxo() {
                @Override
                public void raciocinio(String pedaco) {
                    raciocinio.add(pedaco);
                }

                @Override
                public void texto(String pedaco) {
                    texto.add(pedaco);
                }
            };

            RespostaDoLlm chamada = provedor.responderEmFluxo(pedido(), ouvinte);

            assertThat(raciocinio).containsExactly("Vou ", "iniciar.");
            assertThat(texto).isEmpty();
            assertThat(chamada.chamadas()).containsExactly(new ChamadaDeFerramenta("call_9", "relatorio_iniciar", "{\"modelo\":\"ATIVACAO_EVENTO\"}"));
            assertThat(chamada.raciocinio()).isEqualTo("Vou iniciar.");
            assertThat(chamada.texto()).isNull();
            assertThat(JsonPath.<Boolean>read(servidor.ultima().corpo(), "$.stream")).isTrue();
            assertThat(servidor.ultima().cabecalho("user-agent")).containsExactly("PlanetaAtlas/0.1");
            assertThat(servidor.ultima().cabecalho("x-opencode-session")).containsExactly("42");

            raciocinio.clear();
            RespostaDoLlm resposta = provedor.responderEmFluxo(pedido(), ouvinte);

            assertThat(raciocinio).containsExactly("Respondo.");
            assertThat(texto).containsExactly("Pron", "to.");
            assertThat(resposta.texto()).isEqualTo("Pronto.");
            assertThat(resposta.chamadas()).isEmpty();
        });
    }

    @Test
    void emFluxoOErroDoProvedorTambemViraProvedorIndisponivelSemChave() {
        servidor.responderComStatus(401, "{\"error\":{\"message\":\"Incorrect API key provided: " + CHAVE + "\"}}");

        ligado().run(contexto -> assertThatThrownBy(() -> contexto.getBean(ProvedorDeLlm.class).responderEmFluxo(pedido(),
                new ProvedorDeLlm.OuvinteDoFluxo() {
                    @Override
                    public void raciocinio(String pedaco) {
                    }

                    @Override
                    public void texto(String pedaco) {
                    }
                })).isInstanceOf(ProvedorIndisponivelException.class).satisfies(erro -> assertThat(erro.getMessage()).doesNotContain(CHAVE)));
    }

    @Test
    void asChamadasSeguintesTrazemASessaoDelas() {
        servidor.responder(ServidorDeLlmFalso.texto("Um."));
        servidor.responder(ServidorDeLlmFalso.texto("Dois."));

        ligado().run(contexto -> {
            ProvedorDeLlm provedor = contexto.getBean(ProvedorDeLlm.class);
            provedor.responder(new PedidoAoLlm("7", List.of(MensagemDeChat.usuario("a")), List.of()));
            provedor.responder(new PedidoAoLlm("8", List.of(MensagemDeChat.usuario("b")), List.of()));

            assertThat(servidor.recebidas()).extracting(r -> r.cabecalho("x-opencode-session")).containsExactly(List.of("7"), List.of("8"));
            assertThat(servidor.recebidas()).allSatisfy(r -> assertThat(r.cabecalho("user-agent")).containsExactly("PlanetaAtlas/0.1"));
            assertThat(servidor.recebidas()).allSatisfy(r -> assertThat(r.corpo()).doesNotContain("\"tools\""));
        });
    }

    // ---------------------------------------------------------------------
    // O que volta
    // ---------------------------------------------------------------------

    @Test
    void devolveOsPedidosDeFerramentaEOReasoningContent() {
        servidor.responder(ServidorDeLlmFalso.chamada("call_9", "relatorio_iniciar", "{\"modelo\":\"ATIVACAO_EVENTO\"}", "Preciso iniciar."));

        ligado().run(contexto -> {
            RespostaDoLlm resposta = contexto.getBean(ProvedorDeLlm.class).responder(pedido());

            assertThat(resposta.texto()).isNull();
            assertThat(resposta.chamadas()).containsExactly(new ChamadaDeFerramenta("call_9", "relatorio_iniciar", "{\"modelo\":\"ATIVACAO_EVENTO\"}"));
            assertThat(resposta.raciocinio()).isEqualTo("Preciso iniciar.");
        });
    }

    @Test
    void respostaSemRaciocinioTemRaciocinioNulo() {
        servidor.responder(ServidorDeLlmFalso.texto("Oi."));

        ligado().run(contexto -> assertThat(contexto.getBean(ProvedorDeLlm.class).responder(pedido()).raciocinio()).isNull());
    }

    @Test
    void erroDoProvedorViraProvedorIndisponivelSemChaveNaMensagem() {
        servidor.responderComStatus(400, "{\"error\":{\"message\":\"The reasoning_content must be passed back\",\"type\":\"invalid_request_error\"}}");
        servidor.responderComStatus(401, "{\"error\":{\"message\":\"Incorrect API key provided: " + CHAVE + "\"}}");
        servidor.responderComStatus(503, "{\"error\":{\"message\":\"fora do ar\"}}");

        ligado().run(contexto -> {
            ProvedorDeLlm provedor = contexto.getBean(ProvedorDeLlm.class);
            for (int i = 0; i < 3; i++) {
                assertThatThrownBy(() -> provedor.responder(pedido())).isInstanceOf(ProvedorIndisponivelException.class)
                        .hasMessageStartingWith("O modelo de linguagem não respondeu").satisfies(erro -> {
                            assertThat(erro.getMessage()).doesNotContain(CHAVE).doesNotContain("Bearer");
                            assertThat(erro.getMessage().length()).isLessThan(400);
                        });
            }
        });
    }

    @Test
    void limparTiraChaveDaMensagem() {
        assertThat(ProvedorSpringAi.limpar("Incorrect API key provided: sk-abcdef123456. Veja api_key=xyz e Bearer abc.def", null))
                .doesNotContain("sk-abcdef").doesNotContain("abc.def").contains("[omitido]");
        assertThat(ProvedorSpringAi.limpar("A chave minha-chave-qualquer não vale", "minha-chave-qualquer"))
                .isEqualTo("A chave [omitido] não vale");
        assertThat(ProvedorSpringAi.limpar("a".repeat(500), "")).hasSize(303);
        assertThat(ProvedorSpringAi.limpar(null, null)).isEmpty();
    }

    // ---------------------------------------------------------------------
    // Ligar e desligar
    // ---------------------------------------------------------------------

    @Test
    void comNoneOPortalNaoTemModeloNemProvedor() {
        contexto("spring.ai.model.chat=none", "spring.ai.openai.api-key=" + CHAVE).run(contexto -> {
            assertThat(contexto).doesNotHaveBean(ChatModel.class);
            assertThat(contexto).doesNotHaveBean(ProvedorDeLlm.class);
        });
    }

    @Test
    void semAVariavelOPadraoDoApplicationYmlEDesligado() {
        contexto().run(contexto -> {
            assertThat(contexto.getEnvironment().getProperty("spring.ai.model.chat")).isEqualTo("none");
            assertThat(contexto).doesNotHaveBean(ChatModel.class);
            assertThat(contexto).doesNotHaveBean(ProvedorDeLlm.class);
        });
    }

    @Test
    void openaiComChaveEmBrancoDeixaOAtlasNoPlanoB() {
        contexto("spring.ai.model.chat=openai", "spring.ai.openai.api-key=").run(contexto -> {
            assertThat(contexto).doesNotHaveBean(ProvedorDeLlm.class);
            assertThat(contexto).hasBean("avisoDeModeloSemChave");
        });
    }

    @Test
    void openaiComChaveLigaOProvedor() {
        ligado().run(contexto -> {
            assertThat(contexto).hasSingleBean(ProvedorDeLlm.class);
            assertThat(contexto.getBean(ProvedorDeLlm.class)).isInstanceOf(ProvedorSpringAi.class);
            assertThat(contexto).doesNotHaveBean("avisoDeModeloSemChave");
        });
    }
}
