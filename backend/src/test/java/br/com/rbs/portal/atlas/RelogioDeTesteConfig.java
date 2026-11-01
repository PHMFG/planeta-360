package br.com.rbs.portal.atlas;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Põe o {@link RelogioDeTeste} como o {@code Clock} do contexto: os limites por usuário e os disjuntores o usam no lugar do relógio
 * do sistema. Só existe em {@code src/test}, e só nas classes que o importam (cada uma ganha um contexto do Spring próprio). Não
 * faz parte da API.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RelogioDeTesteConfig {

    @Bean
    @Primary
    RelogioDeTeste relogioDeTeste() {
        return new RelogioDeTeste();
    }
}
