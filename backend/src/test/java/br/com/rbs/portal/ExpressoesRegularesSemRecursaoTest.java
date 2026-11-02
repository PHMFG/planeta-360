package br.com.rbs.portal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Nenhuma expressão regular do projeto estoura a pilha com milhares de repetições (M8, CI no Linux). O {@code java.util.regex} repete
 * um grupo que não é determinístico com recursão, uma volta de pilha por repetição: com a pilha de 1 MB do Linux x64 (a do Mac arm64
 * é de 2 MB) um texto do modelo, da pessoa ou de um documento com poucos milhares de repetições derruba a checagem com
 * {@link StackOverflowError}, que não é exceção e passa por tudo. Este teste acha todo {@code Pattern} estático do código de produção
 * e o roda, numa thread de pilha de 256 KB (estoura nos dois sistemas), contra entradas de 5.000 repetições dos formatos que os
 * padrões do projeto repetem (lista de citações, e-mail e domínio com muitos rótulos, número com muitos grupos, data com muitos dias,
 * numerais em sequência, título numerado, chave com hífens). Quem estoura aqui estourava no CI. A correção é o quantificador possessivo
 * ({@code *+}), quando o que vem depois do grupo nunca precisa que ele devolva repetições, ou um limite alto e fixo ({@code {0,30}}),
 * quando precisa. O significado para o texto normal não muda: os testes de cada padrão seguem como estavam.
 */
class ExpressoesRegularesSemRecursaoTest {

    private static final int REPETICOES = 5_000;

    static Map<String, String> entradas() {
        int n = REPETICOES;
        Map<String, String> m = new LinkedHashMap<>();
        m.put("citações", "[T1" + ", T2".repeat(n));
        m.put("citações fechadas", "[T1" + ", T2".repeat(n) + "]");
        m.put("e-mail com muitos rótulos", "a@b" + ".c".repeat(n));
        m.put("milhar com ponto", "1" + ".000".repeat(n));
        m.put("decimais encadeados", "1" + ".1".repeat(n));
        m.put("milhar com espaço", "1" + " 000".repeat(n));
        m.put("dias separados por vírgula", "1" + ", 1".repeat(n) + " de janeiro");
        m.put("dias separados por e", "1" + " e 1".repeat(n) + " de janeiro");
        m.put("numerais com e", "dois" + " e dois".repeat(n) + " de janeiro");
        m.put("numerais em sequência", "mil" + " mil".repeat(n));
        m.put("hifens", "a" + "-a".repeat(n));
        m.put("domínio com muitos rótulos", "a" + ".b".repeat(n) + ".com");
        m.put("chave com muitos pontos", "dado:a" + ".b".repeat(n));
        m.put("título numerado", "1" + ".1".repeat(n) + " A");
        m.put("letras", "x".repeat(n * 5));
        m.put("dígitos soltos", "1 2 3 4 ".repeat(n));
        m.put("vírgulas", "a,".repeat(n));
        m.put("pares", "ab".repeat(n));
        m.put("telefones", "(51) 9" + "9999-9999 ".repeat(n));
        return m;
    }

    private static Path pastaDasClasses() throws URISyntaxException {
        return Path.of(PortalApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static List<Class<?>> classesDoProjeto() throws Exception {
        Path raiz = pastaDasClasses();
        List<Class<?>> classes = new ArrayList<>();
        try (Stream<Path> arquivos = Files.walk(raiz)) {
            for (Path arquivo : arquivos.filter(a -> a.toString().endsWith(".class")).toList()) {
                String relativo = raiz.relativize(arquivo).toString();
                String nome = relativo.substring(0, relativo.length() - ".class".length()).replace('/', '.');
                if (nome.endsWith("package-info") || nome.endsWith("module-info")) {
                    continue;
                }
                try {
                    classes.add(Class.forName(nome, false, PortalApplication.class.getClassLoader()));
                } catch (Throwable naoCarrega) {
                    // classe que não carrega sozinha (depende do Spring): não tem padrão estático que importe
                }
            }
        }
        return classes;
    }

    private static void coletar(Class<?> classe, Map<String, Pattern> destino) {
        for (Field campo : classe.getDeclaredFields()) {
            if (!Modifier.isStatic(campo.getModifiers())) {
                continue;
            }
            try {
                campo.setAccessible(true);
                Object valor = campo.get(null);
                String nome = classe.getSimpleName() + "." + campo.getName();
                if (valor instanceof Pattern p) {
                    destino.put(nome, p);
                } else if (valor instanceof Iterable<?> lista) {
                    int i = 0;
                    for (Object o : lista) {
                        if (o instanceof Pattern p) {
                            destino.put(nome + "[" + i++ + "]", p);
                        }
                    }
                } else if (valor instanceof Map<?, ?> mapa) {
                    for (Map.Entry<?, ?> e : mapa.entrySet()) {
                        if (e.getValue() instanceof Pattern p) {
                            destino.put(nome + "{" + e.getKey() + "}", p);
                        }
                    }
                }
            } catch (Throwable semAcesso) {
                // campo que não se lê (a classe não inicializa sozinha): nada a conferir
            }
        }
    }

    @Test
    void nenhumaExpressaoRegularDoProjetoEstouraAPilhaComMilharesDeRepeticoes() throws Exception {
        Map<String, Pattern> padroes = new LinkedHashMap<>();
        for (Class<?> classe : classesDoProjeto()) {
            coletar(classe, padroes);
        }
        assertThat(padroes.size()).as("padrões estáticos achados no código de produção").isGreaterThan(80);

        Map<String, String> entradas = entradas();
        List<String> estouraram = new ArrayList<>();
        padroes.forEach((nome, padrao) -> entradas.forEach((rotulo, entrada) -> {
            boolean estourou = PilhaPequena.estoura(() -> {
                padrao.matcher(entrada).find();
                padrao.matcher(entrada).matches();
            });
            if (estourou) {
                estouraram.add(nome + " com " + rotulo + ": " + padrao.pattern());
            }
        }));

        assertThat(estouraram).as("expressões regulares que estouram a pilha de 256 KB (use quantificador possessivo ou um limite fixo)")
                .isEmpty();
    }
}
