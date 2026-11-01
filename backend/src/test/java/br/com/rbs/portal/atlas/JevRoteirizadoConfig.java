package br.com.rbs.portal.atlas;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Registra o {@link JevRoteirizado} como o {@code ProvedorDeJev} do contexto de teste e a {@link ConfiguracaoDoJevDeTeste} como a
 * configuração dele: os testes ligam cada ponto no modo que querem e roteiram o que o JEV responde, sem chamar a API. Só existe em
 * {@code src/test}. Não faz parte da API.
 */
@TestConfiguration(proxyBeanMethods = false)
public class JevRoteirizadoConfig {

    @Bean
    @Primary
    JevRoteirizado jevRoteirizado() {
        return new JevRoteirizado();
    }

    @Bean
    @Primary
    ConfiguracaoDoJevDeTeste configuracaoDoJevDeTeste() {
        return new ConfiguracaoDoJevDeTeste();
    }
}
