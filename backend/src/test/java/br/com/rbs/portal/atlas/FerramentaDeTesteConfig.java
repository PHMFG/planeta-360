package br.com.rbs.portal.atlas;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Registra a {@link FerramentaDeTeste} no registro de ferramentas do contexto de teste: o {@code RegistroDeFerramentas} recebe todo
 * bean {@code Ferramenta}. Só existe em {@code src/test}. Não faz parte da API.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FerramentaDeTesteConfig {

    @Bean
    FerramentaDeTeste ferramentaDeTeste() {
        return new FerramentaDeTeste();
    }
}
