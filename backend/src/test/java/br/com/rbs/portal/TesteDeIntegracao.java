package br.com.rbs.portal;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Marca um teste que sobe a API inteira contra o banco portal_teste (perfil test) e chama as rotas com MockMvc.
 * Atende o item 3 da Entrega 2. Todos os testes usam a mesma configuração, então o Spring sobe uma vez só
 * e o Flyway limpa e recria o banco uma vez só por execução.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(BancoDeTesteConfig.class)
public @interface TesteDeIntegracao {
}
