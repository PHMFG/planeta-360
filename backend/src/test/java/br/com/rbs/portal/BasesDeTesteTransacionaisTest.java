package br.com.rbs.portal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teste de teste (M8, CI no Linux): uma classe de teste {@code @Transactional} não pode herdar teste de uma classe-base que o declara e
 * não é {@code @Transactional}. O Spring procura o {@code @Transactional} de um método na classe que o declara, então o da subclasse
 * não vale para o teste herdado: ele roda sem transação, grava com commit e deixa o banco sujo para o teste que vier depois. Foi o que
 * aconteceu com {@code DebugDeFerramentasBase} e {@code RespostasDiretasNaConversaBase}, que deixavam 5 e 12 conversas no banco e
 * derrubaram o {@code AtlasControllerTest} no CI, onde a ordem das classes é outra. A ordem das classes muda entre sistemas, então a
 * sujeira de uma só aparece na outra quando o contexto do Spring já estava em cache.
 */
class BasesDeTesteTransacionaisTest {

    @Test
    void classeTransacionalNaoHerdaTesteDeBaseQueNaoETransacional() throws Exception {
        Path raiz = Path.of(BasesDeTesteTransacionaisTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> problemas = new ArrayList<>();
        int concretas = 0;
        try (Stream<Path> arquivos = Files.walk(raiz)) {
            for (Path arquivo : arquivos.filter(a -> a.toString().endsWith(".class")).toList()) {
                String relativo = raiz.relativize(arquivo).toString();
                String nome = relativo.substring(0, relativo.length() - ".class".length()).replace('/', '.');
                Class<?> classe;
                try {
                    classe = Class.forName(nome, false, BasesDeTesteTransacionaisTest.class.getClassLoader());
                } catch (Throwable naoCarrega) {
                    continue;
                }
                if (Modifier.isAbstract(classe.getModifiers()) || !AnnotatedElementUtils.hasAnnotation(classe, Transactional.class)) {
                    continue;
                }
                concretas++;
                for (Class<?> base = classe.getSuperclass(); base != null && base != Object.class; base = base.getSuperclass()) {
                    boolean declaraTestes = Arrays.stream(base.getDeclaredMethods())
                            .anyMatch(m -> AnnotatedElementUtils.hasAnnotation(m, Test.class)
                                    || AnnotatedElementUtils.hasAnnotation(m, ParameterizedTest.class));
                    if (declaraTestes && !AnnotatedElementUtils.hasAnnotation(base, Transactional.class)) {
                        problemas.add(classe.getName() + " herda testes de " + base.getName() + ", que não é @Transactional");
                    }
                }
            }
        }
        assertThat(concretas).as("classes de teste @Transactional achadas").isGreaterThan(30);
        assertThat(problemas).as("ponha @Transactional na classe-base que declara os testes, ou limpe o que eles gravam").isEmpty();
    }
}
