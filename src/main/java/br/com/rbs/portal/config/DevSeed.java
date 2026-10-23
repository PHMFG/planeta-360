package br.com.rbs.portal.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Desenvolvimento: a amostra traz hashes simulados; aqui eles viram BCrypt de "senha123". */
@Component
@ConditionalOnProperty(name = "portal.dev-seed", havingValue = "true")
public class DevSeed implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DevSeed.class);
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public DevSeed(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        int n = jdbc.update("UPDATE usuario SET senha_hash = ? WHERE senha_hash LIKE '$2a$10$hash.simulado%'",
                encoder.encode("senha123"));
        if (n > 0) {
            log.warn("DEV: {} usuário(s) da amostra receberam a senha 'senha123'. Desative com DEV_SEED=false.", n);
        }
    }
}
