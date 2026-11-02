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
    JevRoteirizado jevRoteirizado(javax.sql.DataSource fonteDeDados) {
        JevRoteirizado jev = new JevRoteirizado();
        if (fonteDeDados instanceof com.zaxxer.hikari.HikariDataSource hikari) {
            jev.medirConexoesCom(() -> hikari.getHikariPoolMXBean() == null ? 0 : hikari.getHikariPoolMXBean().getActiveConnections());
        }
        return jev;
    }

    @Bean
    @Primary
    ConfiguracaoDoJevDeTeste configuracaoDoJevDeTeste() {
        return new ConfiguracaoDoJevDeTeste();
    }
}
