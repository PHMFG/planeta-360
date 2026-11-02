package br.com.rbs.portal.atlas;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * Um {@link TesteComLlmRoteirizado} com o JEV também roteirizado ({@link JevRoteirizado}) e com o modo e o limiar de cada ponto à mão
 * do teste ({@link ConfiguracaoDoJevDeTeste}): o loop do agente roda de ponta a ponta contra o banco, sem chamar o modelo de
 * linguagem nem o JEV de verdade. Todos os testes que usam esta anotação compartilham um contexto do Spring. Não faz parte da API.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@TesteComLlmRoteirizado
@Import(JevRoteirizadoConfig.class)
public @interface TesteComJevRoteirizado {
}
