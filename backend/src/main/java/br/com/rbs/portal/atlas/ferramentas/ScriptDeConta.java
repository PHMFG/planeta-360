package br.com.rbs.portal.atlas.ferramentas;

import br.com.rbs.portal.comum.DadosInvalidosException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A lista de tokens permitidos que o script do {@code calcular} atravessa ANTES do JEXL (plano do Atlas completo, M5). [ATLAS]
 * [VALIDACAO] É a trava que impede um número escrito no script: o JEXL 3.7.1 não desliga texto nem regex, e {@code '312'},
 * {@code "31" + "2"} e {@code 100+100+100+2*2*2*2-2-2} dariam 312 com as outras travas ligadas (conferido em 30/10/2026). Todo valor
 * entra por uma variável, que o {@link Calcular} confere contra a fonte; o script é só a forma da conta.
 * <p>
 * O que o script pode ter, e só isto:
 * <ul>
 *   <li>os nomes das variáveis declaradas, o nome de uma variável vem de {@code [a-z][a-z0-9_]{0,39}} e nunca é palavra reservada
 *       do JEXL nem {@code pct}, {@code min} ou {@code max};</li>
 *   <li>as funções {@code pct(a, b)}, {@code min(a, b)} e {@code max(a, b)}, sempre com dois argumentos;</li>
 *   <li>os sinais {@code + - * / ( ) ,} (menos como sinal do número também);</li>
 *   <li>as constantes <b>exatas</b> {@code 0}, {@code 1}, {@code 2}, {@code 100} e {@code 1000}, <b>no máximo duas</b> no script
 *       (algarismo colado em letra, como {@code 1e3} ou {@code 2x}, e zero à esquerda, como {@code 0100}, são recusados);</li>
 *   <li>pelo menos uma variável, e toda variável declarada é usada;</li>
 *   <li><b>sem fabricar número com a própria variável</b>: cada variável aparece <b>no máximo duas vezes</b>; {@code x / x} e
 *       {@code x - x} (a mesma variável dos dois lados, também entre parênteses) e {@code pct}, {@code min} e {@code max} com a mesma
 *       variável nos dois argumentos são recusados, porque dão 1, 0 ou 100 sem origem nenhuma (somados, dariam qualquer número);</li>
 *   <li><b>percentual só com {@code pct}</b>: a constante {@code 100} junto de uma divisão por algo que não é constante
 *       ({@code a * 100 / b}, {@code (a / b) * 100}) é recusada. A divisão do motor arredonda meio para cima e 459.999 de 460.000 daria
 *       "100%", o que o {@code fn_pct_truncado} do portal evita. {@code a * b / 100} continua valendo.</li>
 * </ul>
 * Sem aspas, sem {@code =~}, sem ponto, sem quebra de linha, sem os pares de sinais de incremento e de comentário, sem caractere
 * fora do ASCII, com até 2.000 caracteres e no máximo {@value #MAXIMO_DE_ANINHAMENTO} parênteses aninhados. A forma é conferida por uma gramática
 * (expressão, termo, sinal, valor): o que chega ao JEXL é uma expressão aritmética bem formada.
 */
final class ScriptDeConta {

    static final int LIMITE_DE_CARACTERES = 2_000;
    static final int MAXIMO_DE_CONSTANTES = 2;
    static final int MAXIMO_DE_ANINHAMENTO = 8;
    /** Quantas vezes uma mesma variável pode aparecer no script. */
    static final int MAXIMO_DE_USOS_DA_VARIAVEL = 2;
    static final Set<String> CONSTANTES = Set.of("0", "1", "2", "100", "1000");
    static final Set<String> FUNCOES = Set.of("pct", "min", "max");
    /** As palavras que o JEXL reserva (e as três funções): nenhuma serve de nome de variável. */
    static final Set<String> RESERVADAS = Set.of("new", "var", "if", "else", "for", "while", "do", "true", "false", "null", "empty",
            "size", "and", "or", "not", "eq", "ne", "lt", "le", "gt", "ge", "div", "mod", "in", "function", "return", "continue",
            "break", "let", "const", "default", "case", "try", "catch", "finally", "throw", "switch", "instanceof", "class", "pct",
            "min", "max");
    private static final Pattern NOME_DE_VARIAVEL = Pattern.compile("[a-z][a-z0-9_]{0,39}");
    private static final List<String> JUNTOS_PROIBIDOS = List.of("++", "--", "//", "/*", "*/");

    private enum Tipo { NUMERO, NOME, SINAL }

    private record Token(Tipo tipo, String texto) {
    }

    private ScriptDeConta() {
    }

    /** O nome serve de variável: no padrão e fora das palavras reservadas e das funções. */
    static boolean nomeDeVariavelValido(String nome) {
        return nome != null && NOME_DE_VARIAVEL.matcher(nome).matches() && !RESERVADAS.contains(nome);
    }

    /**
     * Confere o script contra as variáveis declaradas; sobe {@link DadosInvalidosException} (campo {@code script}) se não passa.
     * Devolve quantas constantes o script tem (0 a 2): o {@link Calcular} soma as das contas {@code calc:} que ele usa.
     */
    static int conferir(String script, Set<String> variaveis) {
        if (script == null || script.isBlank()) {
            throw recusado("Escreva o script da conta.");
        }
        if (script.length() > LIMITE_DE_CARACTERES) {
            throw recusado("O script tem " + script.length() + " caracteres; o máximo é 2.000.");
        }
        for (String junto : JUNTOS_PROIBIDOS) {
            if (script.contains(junto)) {
                throw recusado("O script não pode ter \"" + junto + "\". Use só + - * / ( ) , e escreva uma conta comum.");
            }
        }
        List<Token> tokens = separar(script);
        Analise analise = new Analise(tokens, variaveis);
        analise.expressao(0);
        if (!analise.fim()) {
            throw recusado("O script não é uma conta: sobrou \"" + analise.atual().texto() + "\" depois da expressão.");
        }
        if (analise.constantes > MAXIMO_DE_CONSTANTES) {
            throw recusado("O script tem " + analise.constantes + " constantes; o máximo é " + MAXIMO_DE_CONSTANTES
                    + ". Os valores entram como variáveis, com a fonte de cada um.");
        }
        if (variaveis.isEmpty() || analise.usadas.isEmpty()) {
            throw recusado("O script precisa usar pelo menos uma variável. Todo valor entra como variável, com a fonte dele.");
        }
        for (String declarada : new java.util.TreeSet<>(variaveis)) {
            if (!analise.usadas.containsKey(declarada)) {
                throw recusado("A variável " + declarada + " foi declarada, mas o script não a usa. Tire-a de variaveis ou use-a na conta.");
            }
        }
        if (analise.temCem && analise.dividePorNaoConstante) {
            throw recusado("Percentual se calcula com pct(parte, todo), que corta na primeira casa como o portal. Não use a constante 100 "
                    + "junto de uma divisão por variável (a * 100 / b, a / b * 100): a divisão arredondaria 99,9998% para 100%. A divisão "
                    + "por uma constante, como a * b / 100, continua valendo.");
        }
        return analise.constantes;
    }

    // ---------------------------------------------------------------------
    // Os tokens
    // ---------------------------------------------------------------------

    private static List<Token> separar(String script) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < script.length()) {
            char c = script.charAt(i);
            if (c == ' ' || c == '\t') {
                i++;
            } else if (c == '\n' || c == '\r') {
                throw recusado("Escreva a conta numa linha só.");
            } else if (ehLetraOuSublinhado(c)) {
                int fim = i;
                while (fim < script.length() && (ehLetraOuSublinhado(script.charAt(fim)) || ehAlgarismo(script.charAt(fim)))) {
                    fim++;
                }
                tokens.add(new Token(Tipo.NOME, script.substring(i, fim)));
                i = fim;
            } else if (ehAlgarismo(c)) {
                int fim = i;
                while (fim < script.length() && ehAlgarismo(script.charAt(fim))) {
                    fim++;
                }
                if (fim < script.length() && (ehLetraOuSublinhado(script.charAt(fim)) || script.charAt(fim) == '.')) {
                    throw recusado("O número \"" + script.substring(i, fim + 1) + "…\" não vale: sem algarismo colado em letra, sem "
                            + "decimal e sem ponto. As constantes são só 0, 1, 2, 100 e 1000.");
                }
                String numero = script.substring(i, fim);
                if (!CONSTANTES.contains(numero)) {
                    throw recusado("O número " + numero + " não pode estar no script: todo valor entra como variável, com a fonte "
                            + "dele. No script só valem as constantes 0, 1, 2, 100 e 1000.");
                }
                tokens.add(new Token(Tipo.NUMERO, numero));
                i = fim;
            } else if ("+-*/(),".indexOf(c) >= 0) {
                tokens.add(new Token(Tipo.SINAL, String.valueOf(c)));
                i++;
            } else {
                throw recusado(c == '\'' || c == '"' || c == '`' ? "Sem aspas: nada de texto no script, só variáveis e números."
                        : "O caractere \"" + c + "\" não é permitido no script. Use só variáveis, + - * / ( ) , e as funções pct, min e max.");
            }
        }
        return tokens;
    }

    private static boolean ehLetraOuSublinhado(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean ehAlgarismo(char c) {
        return c >= '0' && c <= '9';
    }

    // ---------------------------------------------------------------------
    // A gramática: expressao = termo (("+" | "-") termo)*; termo = sinal (("*" | "/") sinal)*;
    // sinal = ("-" | "+") sinal | valor; valor = NUMERO | VARIAVEL | FUNCAO "(" expressao "," expressao ")" | "(" expressao ")"
    // ---------------------------------------------------------------------

    /**
     * Um pedaço da expressão, para as recusas de {@code x / x}, {@code x - x} e da divisão por constante: a {@code assinatura} é o
     * texto dele sem os parênteses (então {@code (a)} e {@code a} são o mesmo), e {@code constante} diz que é só um número.
     */
    private record Operando(String assinatura, boolean constante) {
    }

    private static final class Analise {

        private final List<Token> tokens;
        private final Set<String> variaveis;
        private int posicao;
        int constantes;
        boolean temCem;
        boolean dividePorNaoConstante;
        final Map<String, Integer> usadas = new HashMap<>();

        Analise(List<Token> tokens, Set<String> variaveis) {
            this.tokens = tokens;
            this.variaveis = variaveis;
        }

        boolean fim() {
            return posicao >= tokens.size();
        }

        Token atual() {
            return tokens.get(posicao);
        }

        private boolean sinal(String texto) {
            return !fim() && atual().tipo() == Tipo.SINAL && atual().texto().equals(texto);
        }

        /** O texto dos tokens de {@code de} (inclusive) até {@code ate} (exclusive), sem parênteses nem espaços. */
        private String assinatura(int de, int ate) {
            StringBuilder texto = new StringBuilder();
            for (Token token : tokens.subList(de, ate)) {
                if (!token.texto().equals("(") && !token.texto().equals(")")) {
                    texto.append(token.texto());
                }
            }
            return texto.toString();
        }

        private static boolean mesmoOperando(Operando esquerda, Operando direita) {
            return !esquerda.constante() && esquerda.assinatura().equals(direita.assinatura());
        }

        Operando expressao(int nivel) {
            int inicio = posicao;
            Operando esquerda = termo(nivel);
            while (sinal("+") || sinal("-")) {
                boolean menos = sinal("-");
                posicao++;
                Operando direita = termo(nivel);
                if (menos && mesmoOperando(esquerda, direita)) {
                    throw recusado("\"" + esquerda.assinatura() + " - " + direita.assinatura() + "\" dá sempre zero: o mesmo valor não se "
                            + "subtrai de si mesmo. Use valores diferentes.");
                }
                esquerda = new Operando(assinatura(inicio, posicao), false);
            }
            return esquerda;
        }

        private Operando termo(int nivel) {
            int inicio = posicao;
            Operando esquerda = comSinal(nivel);
            while (sinal("*") || sinal("/")) {
                boolean dividir = sinal("/");
                posicao++;
                Operando direita = comSinal(nivel);
                if (dividir) {
                    if (mesmoOperando(esquerda, direita)) {
                        throw recusado("\"" + esquerda.assinatura() + " / " + direita.assinatura() + "\" dá sempre 1: o mesmo valor não se "
                                + "divide por si mesmo. Use valores diferentes.");
                    }
                    if (!direita.constante()) {
                        dividePorNaoConstante = true;
                    }
                }
                esquerda = new Operando(assinatura(inicio, posicao), false);
            }
            return esquerda;
        }

        private Operando comSinal(int nivel) {
            if (sinal("-") || sinal("+")) {
                posicao++;
                return comSinal(nivel);
            }
            return valor(nivel);
        }

        private Operando valor(int nivel) {
            if (fim()) {
                throw recusado("O script termina antes da hora: falta um valor depois do último sinal.");
            }
            Token token = atual();
            switch (token.tipo()) {
                case NUMERO -> {
                    constantes++;
                    temCem |= token.texto().equals("100");
                    posicao++;
                    return new Operando(token.texto(), true);
                }
                case NOME -> {
                    return nome(token, nivel);
                }
                default -> {
                    if (!token.texto().equals("(")) {
                        throw recusado("O sinal \"" + token.texto() + "\" está fora do lugar: falta um valor antes dele.");
                    }
                    posicao++;
                    Operando dentro = entreParenteses(nivel);
                    exigir(")", "Falta fechar o parêntese.");
                    return dentro;
                }
            }
        }

        private Operando nome(Token token, int nivel) {
            String nome = token.texto();
            boolean funcao = FUNCOES.contains(nome);
            boolean chamada = posicao + 1 < tokens.size() && tokens.get(posicao + 1).tipo() == Tipo.SINAL
                    && tokens.get(posicao + 1).texto().equals("(");
            if (funcao) {
                if (!chamada) {
                    throw recusado("A função " + nome + " é chamada com parênteses: " + nome + "(a, b).");
                }
                int inicio = posicao;
                posicao += 2;
                Operando primeiro = entreParenteses(nivel);
                exigir(",", "A função " + nome + " leva dois argumentos, separados por vírgula: " + nome + "(a, b).");
                Operando segundo = expressao(nivel + 1);
                exigir(")", "A função " + nome + " leva dois argumentos: " + nome + "(a, b).");
                if (mesmoOperando(primeiro, segundo)) {
                    throw recusado(nome + "(" + primeiro.assinatura() + ", " + segundo.assinatura() + ") com o mesmo valor nos dois "
                            + "argumentos não é uma conta: dá sempre o mesmo resultado, sem origem. Use valores diferentes.");
                }
                return new Operando(assinatura(inicio, posicao), false);
            }
            if (!variaveis.contains(nome)) {
                throw recusado("A palavra \"" + nome + "\" não é uma variável declarada nem uma das funções pct, min e max.");
            }
            if (chamada) {
                throw recusado("\"" + nome + "\" é uma variável, não uma função: não pode ser chamada com parênteses.");
            }
            int usos = usadas.merge(nome, 1, Integer::sum);
            if (usos > MAXIMO_DE_USOS_DA_VARIAVEL) {
                throw recusado("A variável " + nome + " aparece mais de " + MAXIMO_DE_USOS_DA_VARIAVEL + " vezes no script: o máximo é "
                        + MAXIMO_DE_USOS_DA_VARIAVEL + ". Repetir a mesma variável para montar um número não vale; todo valor entra "
                        + "como variável, com a fonte dele.");
            }
            posicao++;
            return new Operando(nome, false);
        }

        private Operando entreParenteses(int nivel) {
            if (nivel + 1 > MAXIMO_DE_ANINHAMENTO) {
                throw recusado("Parênteses demais, um dentro do outro: o máximo é " + MAXIMO_DE_ANINHAMENTO + ".");
            }
            return expressao(nivel + 1);
        }

        private void exigir(String sinal, String frase) {
            if (!sinal(sinal)) {
                throw recusado(frase);
            }
            posicao++;
        }
    }

    private static DadosInvalidosException recusado(String frase) {
        return new DadosInvalidosException("O script da conta não passou: " + frase, Map.of("script", frase));
    }
}
