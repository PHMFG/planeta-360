package br.com.rbs.portal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ponto de partida da API do portal: sobe o Spring Boot e liga o Flyway, que cria o banco na primeira execução.
 * Atende o item 3 da Entrega 2 (back-end). Cada área do portal tem o seu pacote (comum, acesso, parceria, ...).
 */
@SpringBootApplication
public class PortalApplication {

    public static void main(String[] args) {
        SpringApplication.run(PortalApplication.class, args);
    }
}
