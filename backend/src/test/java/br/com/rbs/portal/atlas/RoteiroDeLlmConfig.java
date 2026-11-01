package br.com.rbs.portal.atlas;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Registra o {@link ProvedorRoteirizado} como o {@code ProvedorDeLlm} do contexto de teste: com ele, {@code llmDisponivel} é
 * verdadeiro e o loop do agente usa o roteiro. Só existe em {@code src/test}. Não faz parte da API.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RoteiroDeLlmConfig {

    @Bean
    ProvedorRoteirizado provedorRoteirizado() {
        return new ProvedorRoteirizado();
    }
}
