package br.com.rbs.portal.atlas.relatorio;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A gramática das referências do relatório do Atlas (plano, seção 10.3) e dos marcadores {@code {{ref}}} que o texto usa no
 * lugar de qualquer número. [ATLAS] [VALIDACAO] A gramática é conferida por regex e é fechada: só estes prefixos e estes nomes.
 * <ul>
 *   <li>{@code parc:patrocinador}, {@code parc:cota}: a parceria;</li>
 *   <li>{@code evento:edicao}: "Planeta Atlântida 2027";</li>
 *   <li>{@code cons:aplicacoes.previstas|comprovadas|fotos|percentual}: a consolidação (rota 29);</li>
 *   <li>{@code lista:aplicacoes} (tabela) e {@code lista:comprovacoes} (galeria): o plano de aplicações (rota 37);</li>
 *   <li>{@code dado:<chave>}: um dado informado vigente, com a chave do catálogo de campos;</li>
 *   <li>{@code calc:<nome>}: uma fórmula fixa do modelo-base ou uma conta do {@code calcular} no chat; o nome é
 *       {@code [a-z][a-z0-9_]{0,59}} (começa por letra e aceita algarismo: {@code energia_20kva}).</li>
 * </ul>
 * Que a chave de um {@code dado:} exista no catálogo e que o nome de um {@code calc:} exista no modelo quem confere é a resolução;
 * aqui só se confere a forma.
 */
public final class Referencias {

    private static final String PARC = "parc:(?:patrocinador|cota)";
    private static final String EVENTO = "evento:edicao";
    private static final String CONS = "cons:aplicacoes\\.(?:previstas|comprovadas|fotos|percentual)";
    private static final String LISTA = "lista:(?:aplicacoes|comprovacoes)";
    private static final String DADO = "dado:[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*";
    private static final String CALC = "calc:[a-z][a-z0-9_]{0,59}";

    /** A referência inteira. */
    public static final Pattern GRAMATICA =
            Pattern.compile("^(?:" + PARC + "|" + EVENTO + "|" + CONS + "|" + LISTA + "|" + DADO + "|" + CALC + ")$");

    /** Um marcador dentro de um texto: {@code {{ref}}}. O que está entre as chaves é conferido pela gramática. */
    public static final Pattern MARCADOR = Pattern.compile("\\{\\{([^{}]*)}}");

    private Referencias() {
    }

    /** A referência tem a forma de uma das seis famílias. */
    public static boolean valida(String ref) {
        return ref != null && GRAMATICA.matcher(ref).matches();
    }

    /** O prefixo da referência (parc, evento, cons, lista, dado ou calc). */
    public static String prefixo(String ref) {
        return ref.substring(0, ref.indexOf(':'));
    }

    /** O que vem depois do prefixo: a chave do dado, o nome do cálculo, o campo da consolidação. */
    public static String nome(String ref) {
        return ref.substring(ref.indexOf(':') + 1);
    }

    /** As referências dos marcadores do texto, na ordem em que aparecem (repetidas incluídas), sem conferir a gramática. */
    public static List<String> marcadores(String texto) {
        List<String> refs = new ArrayList<>();
        if (texto != null) {
            Matcher m = MARCADOR.matcher(texto);
            while (m.find()) {
                refs.add(m.group(1).trim());
            }
        }
        return refs;
    }

    /** O texto sem os marcadores: o que sobra é o que o autor escreveu por conta própria (e não pode ter algarismo). */
    public static String semMarcadores(String texto) {
        return texto == null ? "" : MARCADOR.matcher(texto).replaceAll("");
    }

    /** Troca cada marcador pelo que {@code substituto} devolve para a referência dele. */
    public static String substituir(String texto, Function<String, String> substituto) {
        Matcher m = MARCADOR.matcher(texto);
        StringBuilder saida = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(saida, Matcher.quoteReplacement(substituto.apply(m.group(1).trim())));
        }
        m.appendTail(saida);
        return saida.toString();
    }
}
