package br.com.rbs.portal.atlas;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Põe o {@link EmbeddingsControlavel} no lugar dos embeddings de teste do contexto (é o {@code @Primary}). Só existe em
 * {@code src/test}. Não faz parte da API.
 */
@TestConfiguration(proxyBeanMethods = false)
public class EmbeddingsControlavelConfig {

    @Bean
    @Primary
    EmbeddingsControlavel embeddingsControlavel() {
        return new EmbeddingsControlavel();
    }
}
