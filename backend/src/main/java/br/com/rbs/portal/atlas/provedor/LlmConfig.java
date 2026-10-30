package br.com.rbs.portal.atlas.provedor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Liga o modelo de linguagem do Atlas. [ATLAS] Só liga com {@code ATLAS_LLM=openai} (que o application.yml repassa a
 * {@code spring.ai.model.chat}) E com a chave em {@code OPENCODE_API_KEY}: sem a chave, o Spring AI montaria um cliente sem
 * autorização, que só falharia na primeira conversa; aqui o portal sobe sem o {@link ProvedorDeLlm}, o Atlas cai no plano B
 * ({@code llmDisponivel} falso, o botão "Montar relatório") e o log avisa por quê. Sem o bean não há o que o loop chamar, e a
 * suíte (perfil test, {@code chat=none}) nunca chega aqui.
 */
@Configuration(proxyBeanMethods = false)
public class LlmConfig {

    /** O user agent que o OpenCode Go pede (a documentação deles quer um próprio, e não o do SDK). */
    public static final String USER_AGENT = "PlanetaAtlas/0.1";

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    /** O modelo ligado: chat=openai e chave presente. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "openai")
    @ConditionalOnExpression("!'${spring.ai.openai.api-key:}'.isBlank()")
    static class Ligado {

        @Bean
        ProvedorDeLlm provedorDeLlm(ChatModel modelo, @Value("${spring.ai.openai.api-key}") String chave) {
            log.info("Atlas: modelo de linguagem ligado (ATLAS_LLM=openai).");
            return new ProvedorSpringAi(modelo, chave);
        }

        /**
         * Troca o User-Agent de toda chamada ao provedor. O Spring AI já manda "spring-ai-openai" e o {@code custom-headers} só
         * ACRESCENTA um segundo valor (o provedor recebia dois), então o cabeçalho é reescrito no cliente HTTP, por onde o
         * Spring AI 2.0 deixa personalizar o OkHttp.
         */
        @Bean
        OpenAiHttpClientBuilderCustomizer agenteDoAtlas() {
            return trocaDoUserAgent();
        }
    }

    /**
     * O personalizador do cliente HTTP que reescreve o {@code User-Agent} para {@link #USER_AGENT}. É o mesmo no modelo das
     * variáveis de ambiente (o bean acima) e no que a chave de debug monta em tempo de execução ({@link FabricaDeProvedor}).
     */
    public static OpenAiHttpClientBuilderCustomizer trocaDoUserAgent() {
        return construtor -> construtor.interceptor(
                cadeia -> cadeia.proceed(cadeia.request().newBuilder().header("User-Agent", USER_AGENT).build()));
    }

    /** ATLAS_LLM=openai sem chave: avisa e deixa o Atlas no plano B. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "openai")
    @ConditionalOnExpression("'${spring.ai.openai.api-key:}'.isBlank()")
    static class SemChave {

        @Bean
        ApplicationRunner avisoDeModeloSemChave() {
            return argumentos -> log.warn("Atlas: ATLAS_LLM=openai, mas a variável OPENCODE_API_KEY está vazia. "
                    + "O modelo de linguagem fica desligado e o Atlas só oferece o botão Montar relatório.");
        }
    }
}
