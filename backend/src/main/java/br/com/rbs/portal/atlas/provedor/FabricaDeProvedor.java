package br.com.rbs.portal.atlas.provedor;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfigurationUtil;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfigurationUtil.ResolvedConnectionProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Monta, em tempo de execução, o {@link ProvedorSpringAi} de uma chave posta pela tela de debug. [ATLAS] Faz o mesmo caminho da
 * autoconfiguração do Spring AI 2.0.2 ({@code OpenAiChatAutoConfiguration}), mas com a chave, a base URL e o modelo informados:
 * lê as propriedades {@code spring.ai.openai.*} e {@code spring.ai.openai.chat.*} do application.yml (temperatura, o
 * {@code extra-body} com o {@code thinking} desligado, {@code max-retries}, {@code timeout}), monta os clientes HTTP com o
 * {@code User-Agent} próprio do {@link LlmConfig} e embrulha o {@code OpenAiChatModel} no {@link ProvedorSpringAi} de sempre (o
 * {@code x-opencode-session} por chamada e a chave riscada das mensagens de erro). Funciona também com
 * {@code spring.ai.model.chat=none}, porque não depende dos beans da autoconfiguração.
 */
@Component
public class FabricaDeProvedor {

    private static final String PREFIXO = "spring.ai.openai";
    private static final String PREFIXO_DO_CHAT = "spring.ai.openai.chat";

    private final Environment ambiente;
    private final ObjectProvider<ObservationRegistry> observacao;

    public FabricaDeProvedor(Environment ambiente, ObjectProvider<ObservationRegistry> observacao) {
        this.ambiente = ambiente;
        this.observacao = observacao;
    }

    /** A base URL do application.yml (ou de {@code ATLAS_LLM_BASE_URL}): o padrão quando a tela não informa outra. */
    public String baseUrlPadrao() {
        return resolvidas().getBaseUrl();
    }

    /** O modelo do application.yml (ou de {@code ATLAS_LLM_MODELO}): o padrão quando a tela não informa outro. */
    public String modeloPadrao() {
        return chat().getModel();
    }

    /** A chave das variáveis de ambiente ({@code OPENCODE_API_KEY}), ou nula. Só para mostrar o final dela. */
    public String chaveDoAmbiente() {
        String chave = resolvidas().getApiKey();
        return chave == null || chave.isBlank() ? null : chave;
    }

    /** Um provedor novo com esta chave, base URL e modelo, e as demais opções do application.yml. Não chama o provedor. */
    public ProvedorDeLlm montar(String chave, String baseUrl, String modelo) {
        ResolvedConnectionProperties conexao = resolvidas();
        ObservationRegistry registro = observacao.getIfUnique(() -> ObservationRegistry.NOOP);
        List<OpenAiHttpClientBuilderCustomizer> personalizacoes = List.of(LlmConfig.trocaDoUserAgent());

        OpenAIClient cliente = OpenAiSetup.setupSyncClient(baseUrl, chave, null, null, null, conexao.getOrganizationId(),
                false, false, modelo, conexao.getTimeout(), conexao.getMaxRetries(), conexao.getProxy(),
                conexao.getCustomHeaders(), registro, null, personalizacoes);
        OpenAIClientAsync clienteAssincrono = OpenAiSetup.setupAsyncClient(baseUrl, chave, null, null, null,
                conexao.getOrganizationId(), false, false, modelo, conexao.getTimeout(), conexao.getMaxRetries(),
                conexao.getProxy(), conexao.getCustomHeaders(), registro, null, personalizacoes);

        OpenAiChatModel modeloDeChat = OpenAiChatModel.builder()
                .openAiClient(cliente)
                .openAiClientAsync(clienteAssincrono)
                .options(chat().toOptions().mutate().model(modelo).build())
                .observationRegistry(registro)
                .build();
        return new ProvedorSpringAi(modeloDeChat, chave);
    }

    private OpenAiChatProperties chat() {
        return Binder.get(ambiente).bindOrCreate(PREFIXO_DO_CHAT, OpenAiChatProperties.class);
    }

    private ResolvedConnectionProperties resolvidas() {
        OpenAiCommonProperties comuns = Binder.get(ambiente).bindOrCreate(PREFIXO, OpenAiCommonProperties.class);
        return OpenAiAutoConfigurationUtil.resolveCommonProperties(comuns, chat());
    }
}
