package br.com.rbs.portal;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Apaga e recria o banco portal_teste no início dos testes: o Flyway faz clean e depois migrate (tabelas, regras,
 * dados base e a amostra). Atende o item 3 da Entrega 2. Fica em src/test de propósito: esse código nunca vai para produção,
 * onde apagar o banco seria um desastre. O clean só é permitido no perfil test (clean-disabled: false no application.yml).
 */
@TestConfiguration(proxyBeanMethods = false)
public class BancoDeTesteConfig {

    @Bean
    FlywayMigrationStrategy limparEMigrar() {
        return flyway -> {
            flyway.clean();
            flyway.migrate();
        };
    }
}
