package br.com.rbs.portal.atlas;

import br.com.rbs.portal.TesteDeIntegracao;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * Um {@link TesteDeIntegracao} com o modelo de linguagem do Atlas LIGADO, mas roteirizado ({@link ProvedorRoteirizado}): o
 * {@code llmDisponivel} é verdadeiro e o loop do agente roda de ponta a ponta contra o banco, sem chamar provedor nenhum e sem
 * chave. Todos os testes que usam esta anotação compartilham um contexto do Spring. Não faz parte da API.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@TesteDeIntegracao
@Import(RoteiroDeLlmConfig.class)
public @interface TesteComLlmRoteirizado {
}
