package br.com.rbs.portal.atlas;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * Um {@link TesteComLlmRoteirizado} com o Atlas da RBS LIGADO ({@code atlas.rbs.ligado=true}) e o diagnóstico ligado, também para a
 * marca (o mesmo contexto dos testes do diagnóstico, mais a RBS). Todos os testes do Atlas da RBS usam esta anotação e compartilham
 * um contexto do Spring só. Não faz parte da API.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@TesteComLlmRoteirizado
@TestPropertySource(properties = {"atlas.rbs.ligado=true", "atlas.diagnostico.ligado=true", "atlas.diagnostico.marca=true",
        "atlas.prazo-do-resumo=PT0.5S"})
public @interface TesteDoAtlasDaRbs {
}
