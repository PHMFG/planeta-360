package br.com.rbs.portal.atlas.embeddings;

import br.com.rbs.portal.atlas.diagnostico.ConfiguracaoDeProvedores;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Escolhe os embeddings da API por {@code atlas.embeddings.provedor} ({@code ATLAS_EMBEDDINGS}): {@code gemini} (o Gemini de
 * verdade, com {@code GEMINI_API_KEY}), {@code teste} (determinístico, só para banco descartável) ou {@code none} (o padrão).
 * [ATLAS] Um valor que não é nenhum dos três deixa a API subir com os embeddings desligados e avisa no log: um erro de digitação
 * não derruba o portal.
 */
@Configuration(proxyBeanMethods = false)
public class EmbeddingsConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingsConfig.class);

    @Bean
    ProvedorDeEmbeddings provedorDeEmbeddings(ConfiguracaoDeProvedores config,
                                              @Value("${atlas.embeddings.provedor:none}") String provedor,
                                              @Value("${atlas.embeddings.espera-no-429:PT5S}") Duration esperaNo429) {
        return switch (provedor == null ? "" : provedor.strip().toLowerCase(Locale.ROOT)) {
            case "gemini" -> new EmbeddingsGemini(config.geminiBaseUrl(), config.geminiChave(), config.geminiModelo(),
                    ProvedorDeEmbeddings.DIMENSOES, esperaNo429);
            case "teste" -> new EmbeddingsDeTeste();
            case "none", "" -> new EmbeddingsDesligado();
            default -> {
                log.warn("Atlas: ATLAS_EMBEDDINGS={} não existe (use gemini, teste ou none). Os embeddings ficam desligados.", provedor);
                yield new EmbeddingsDesligado();
            }
        };
    }
}
