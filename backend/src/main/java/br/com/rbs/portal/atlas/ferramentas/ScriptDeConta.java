package br.com.rbs.portal.atlas.ferramentas;

import br.com.rbs.portal.comum.DadosInvalidosException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
 *   <li><b>sem fabricar número com a própria variável</b>: cada variável aparece <b>no máximo duas vezes</b>, e nenhuma parte do
 *       script pode dar <b>sempre o mesmo resultado</b>, qualquer que seja o valor das variáveis ({@code a / a}, {@code a - a},
 *       {@code (a + b) / (b + a)}, {@code a / (a + 0)}, {@code pct(a, a + 0)}): dariam 1, 0 ou 100 sem origem nenhuma (somados,
 *       dariam qualquer número). A conferência é por <i>significado</i>, e não pela forma do texto: a árvore do script é avaliada
 *       com três atribuições fixas de valores não redondos (veja {@link #conferirSignificado}), e a parte que não muda é recusada;</li>
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
    private static final int TAMANHO_DA_PARTE_NA_RECUSA = 80;

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
        No raiz = analise.expressao(0);
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
        for (String declarada : new TreeSet<>(variaveis)) {
            if (!analise.usadas.containsKey(declarada)) {
                throw recusado("A variável " + declarada + " foi declarada, mas o script não a usa. Tire-a de variaveis ou use-a na conta.");
            }
        }
        conferirSignificado(raiz, variaveis);
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
    // A árvore do script (a gramática é fechada: nomes, constantes, pct, min, max, + - * / e parênteses)
    // ---------------------------------------------------------------------

    private sealed interface No permits Constante, Variavel, Negativo, Agrupado, Conta, Funcao {
    }

    private record Constante(String texto) implements No {
    }

    private record Variavel(String nome) implements No {
    }

    /** O sinal de menos na frente de um valor ({@code -a}). */
    private record Negativo(No dentro) implements No {
    }

    /** Uma parte entre parênteses. */
    private record Agrupado(No dentro) implements No {
    }

    /** {@code esquerda sinal direita}, com o sinal um de {@code + - * /}. */
    private record Conta(char sinal, No esquerda, No direita) implements No {
    }

    /** {@code pct}, {@code min} ou {@code max}, sempre com dois argumentos. */
    private record Funcao(String nome, No primeiro, No segundo) implements No {
    }

    // ---------------------------------------------------------------------
    // A gramática: expressao = termo (("+" | "-") termo)*; termo = sinal (("*" | "/") sinal)*;
    // sinal = ("-" | "+") sinal | valor; valor = NUMERO | VARIAVEL | FUNCAO "(" expressao "," expressao ")" | "(" expressao ")"
    // ---------------------------------------------------------------------

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

        No expressao(int nivel) {
            No esquerda = termo(nivel);
            while (sinal("+") || sinal("-")) {
                char sinal = atual().texto().charAt(0);
                posicao++;
                esquerda = new Conta(sinal, esquerda, termo(nivel));
            }
            return esquerda;
        }

        private No termo(int nivel) {
            No esquerda = comSinal(nivel);
            while (sinal("*") || sinal("/")) {
                char sinal = atual().texto().charAt(0);
                posicao++;
                No direita = comSinal(nivel);
                if (sinal == '/' && temVariavel(direita)) {
                    dividePorNaoConstante = true;
                }
                esquerda = new Conta(sinal, esquerda, direita);
            }
            return esquerda;
        }

        private No comSinal(int nivel) {
            if (sinal("-")) {
                posicao++;
                return new Negativo(comSinal(nivel));
            }
            if (sinal("+")) {
                posicao++;
                return comSinal(nivel);
            }
            return valor(nivel);
        }

        private No valor(int nivel) {
            if (fim()) {
                throw recusado("O script termina antes da hora: falta um valor depois do último sinal.");
            }
            Token token = atual();
            switch (token.tipo()) {
                case NUMERO -> {
                    constantes++;
                    temCem |= token.texto().equals("100");
                    posicao++;
                    return new Constante(token.texto());
                }
                case NOME -> {
                    return nome(token, nivel);
                }
                default -> {
                    if (!token.texto().equals("(")) {
                        throw recusado("O sinal \"" + token.texto() + "\" está fora do lugar: falta um valor antes dele.");
                    }
                    posicao++;
                    No dentro = entreParenteses(nivel);
                    exigir(")", "Falta fechar o parêntese.");
                    return new Agrupado(dentro);
                }
            }
        }

        private No nome(Token token, int nivel) {
            String nome = token.texto();
            boolean funcao = FUNCOES.contains(nome);
            boolean chamada = posicao + 1 < tokens.size() && tokens.get(posicao + 1).tipo() == Tipo.SINAL
                    && tokens.get(posicao + 1).texto().equals("(");
            if (funcao) {
                if (!chamada) {
                    throw recusado("A função " + nome + " é chamada com parênteses: " + nome + "(a, b).");
                }
                posicao += 2;
                No primeiro = entreParenteses(nivel);
                exigir(",", "A função " + nome + " leva dois argumentos, separados por vírgula: " + nome + "(a, b).");
                No segundo = expressao(nivel + 1);
                exigir(")", "A função " + nome + " leva dois argumentos: " + nome + "(a, b).");
                return new Funcao(nome, primeiro, segundo);
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
            return new Variavel(nome);
        }

        private No entreParenteses(int nivel) {
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

    // ---------------------------------------------------------------------
    // O significado: parte do script que dá sempre o mesmo resultado
    // ---------------------------------------------------------------------

    /** Algum pedaço da árvore tem variável? Sem variável, é uma constante do script. */
    private static boolean temVariavel(No no) {
        return switch (no) {
            case Constante constante -> false;
            case Variavel variavel -> true;
            case Negativo negativo -> temVariavel(negativo.dentro());
            case Agrupado agrupado -> temVariavel(agrupado.dentro());
            case Conta conta -> temVariavel(conta.esquerda()) || temVariavel(conta.direita());
            case Funcao funcao -> temVariavel(funcao.primeiro()) || temVariavel(funcao.segundo());
        };
    }

    /** Os números primos de onde saem os valores de teste: a raiz quadrada de cada um não é redonda nem tem relação com a de outro. */
    private static final int[] PRIMOS = {2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47, 53, 59, 61, 67, 71, 73, 79, 83, 89, 97, 101,
            103, 107, 109, 113, 127};
    /** As três atribuições: a variável de ordem {@code i} recebe a raiz do primo de índice {@code (i * passo + inicio) % 31}. */
    private static final int[] PASSO_DA_ATRIBUICAO = {1, 30, 7};
    private static final int[] INICIO_DA_ATRIBUICAO = {0, 11, 5};
    private static final int ATRIBUICOES = 3;
    private static final double TOLERANCIA = 1e-9;

    /**
     * [VALIDACAO] A conferência por <b>significado</b> (não pelo texto): cada parte do script que tem variável é avaliada com três
     * atribuições diferentes e fixas (as mesmas em toda execução, em {@code double}: a raiz quadrada de primos, que não é redonda e
     * muda de uma variável para outra e de uma atribuição para outra). A parte cujo valor é o mesmo nas três (tolerância de 1e-9) não
     * depende das variáveis: {@code (a + b) / (b + a)} dá 1, {@code a / (a + 0)} dá 1, {@code a - (a + 0)} dá 0 e
     * {@code pct(a, a + 0)} dá 100, números sem origem que, somados, montariam qualquer valor. Uma divisão por zero numa atribuição
     * pula aquela atribuição. Duas exceções, que não fabricam número: {@code x + x} e {@code x * x} dependem de {@code x}, e o piso
     * ou o teto ({@code max(a - b, 0)}, {@code min(a, 1000)}) devolve a variável ou a constante que o script já contou; mas
     * {@code min} ou {@code max} com o mesmo valor nos dois argumentos ({@code min(a, a + 0)}) não é conta nenhuma.
     * Só mexe no que é decidido antes de rodar: a execução continua no JEXL.
     */
    private static void conferirSignificado(No raiz, Set<String> variaveis) {
        Map<String, double[]> valores = new HashMap<>();
        int ordem = 0;
        for (String nome : new TreeSet<>(variaveis)) {
            double[] daVariavel = new double[ATRIBUICOES];
            for (int k = 0; k < ATRIBUICOES; k++) {
                daVariavel[k] = Math.sqrt(PRIMOS[(ordem * PASSO_DA_ATRIBUICAO[k] + INICIO_DA_ATRIBUICAO[k]) % PRIMOS.length]);
            }
            valores.put(nome, daVariavel);
            ordem++;
        }
        avaliar(raiz, valores);
    }

    /** Avalia a parte nas três atribuições (NaN onde não dá, como na divisão por zero), recusando a que não muda de uma para outra. */
    private static double[] avaliar(No no, Map<String, double[]> valores) {
        return switch (no) {
            case Constante constante -> {
                double[] fixo = new double[ATRIBUICOES];
                java.util.Arrays.fill(fixo, Double.parseDouble(constante.texto()));
                yield fixo;
            }
            case Variavel variavel -> valores.get(variavel.nome());
            case Negativo negativo -> {
                double[] dentro = avaliar(negativo.dentro(), valores);
                double[] resultado = new double[ATRIBUICOES];
                for (int k = 0; k < ATRIBUICOES; k++) {
                    resultado[k] = -dentro[k];
                }
                yield resultado;
            }
            case Agrupado agrupado -> avaliar(agrupado.dentro(), valores);
            case Conta conta -> {
                double[] esquerda = avaliar(conta.esquerda(), valores);
                double[] direita = avaliar(conta.direita(), valores);
                double[] resultado = new double[ATRIBUICOES];
                for (int k = 0; k < ATRIBUICOES; k++) {
                    resultado[k] = switch (conta.sinal()) {
                        case '+' -> esquerda[k] + direita[k];
                        case '-' -> esquerda[k] - direita[k];
                        case '*' -> esquerda[k] * direita[k];
                        default -> direita[k] == 0 ? Double.NaN : esquerda[k] / direita[k];
                    };
                }
                recusarSeNaoMuda(no, resultado);
                yield resultado;
            }
            case Funcao funcao -> {
                double[] primeiro = avaliar(funcao.primeiro(), valores);
                double[] segundo = avaliar(funcao.segundo(), valores);
                boolean pct = funcao.nome().equals("pct");
                boolean doisComVariavel = temVariavel(funcao.primeiro()) && temVariavel(funcao.segundo());
                double[] resultado = new double[ATRIBUICOES];
                for (int k = 0; k < ATRIBUICOES; k++) {
                    resultado[k] = switch (funcao.nome()) {
                        case "pct" -> segundo[k] == 0 ? Double.NaN : cortarNaPrimeiraCasa(100 * primeiro[k] / segundo[k]);
                        case "min" -> Math.min(primeiro[k], segundo[k]);
                        default -> Math.max(primeiro[k], segundo[k]);
                    };
                }
                if (!pct && doisComVariavel && mesmoNasAtribuicoes(primeiro, segundo)) {
                    throw recusado(funcao.nome() + "(" + parte(funcao.primeiro()) + ", " + parte(funcao.segundo()) + ") com o mesmo valor nos "
                            + "dois argumentos não é uma conta: dá sempre o mesmo resultado, sem origem. Use valores diferentes.");
                }
                // o piso e o teto (max(a - b, 0), min(a, 1000)) devolvem a variável ou a constante que o script já contou
                boolean pisoOuTeto = !pct && !doisComVariavel;
                if (!pisoOuTeto) {
                    recusarSeNaoMuda(no, resultado);
                }
                yield resultado;
            }
        };
    }

    /**
     * O corte do {@code pct} do portal (primeira casa, para zero), em {@code double}. O ruído da vírgula flutuante não pode virar
     * degrau: 100 × x ÷ x dá 99,99999999999999 em alguns valores, e o corte daria 99,9 onde a conta exata dá 100.
     */
    private static double cortarNaPrimeiraCasa(double valor) {
        double dezenas = valor * 10;
        double inteiro = Math.rint(dezenas);
        if (Math.abs(dezenas - inteiro) < 1e-7) {
            dezenas = inteiro;
        }
        return (dezenas < 0 ? Math.ceil(dezenas) : Math.floor(dezenas)) / 10;
    }

    private static void recusarSeNaoMuda(No no, double[] resultado) {
        if (temVariavel(no) && naoMuda(resultado)) {
            throw recusado("Uma parte do script dá sempre o mesmo resultado, qualquer que seja o valor (" + parte(no) + "): isso não é uma "
                    + "conta. Cada número tem de vir de uma variável com a fonte dele.");
        }
    }

    /** O valor é o mesmo em todas as atribuições em que existe (há ao menos duas)? */
    private static boolean naoMuda(double[] valores) {
        int existentes = 0;
        double base = Double.NaN;
        for (double valor : valores) {
            if (Double.isNaN(valor)) {
                continue;
            }
            if (existentes > 0 && !proximos(base, valor)) {
                return false;
            }
            base = existentes == 0 ? valor : base;
            existentes++;
        }
        return existentes >= 2;
    }

    /** Os dois argumentos dão o mesmo valor em todas as atribuições em que os dois existem (há ao menos duas)? */
    private static boolean mesmoNasAtribuicoes(double[] a, double[] b) {
        int comparadas = 0;
        for (int k = 0; k < ATRIBUICOES; k++) {
            if (Double.isNaN(a[k]) || Double.isNaN(b[k])) {
                continue;
            }
            if (!proximos(a[k], b[k])) {
                return false;
            }
            comparadas++;
        }
        return comparadas >= 2;
    }

    private static boolean proximos(double x, double y) {
        return Math.abs(x - y) <= TOLERANCIA * Math.max(1, Math.max(Math.abs(x), Math.abs(y)));
    }

    /** A parte do script escrita de novo, para a recusa mostrar onde está o problema. */
    private static String parte(No no) {
        String texto = escrever(no);
        return texto.length() > TAMANHO_DA_PARTE_NA_RECUSA ? texto.substring(0, TAMANHO_DA_PARTE_NA_RECUSA) + "…" : texto;
    }

    private static String escrever(No no) {
        return switch (no) {
            case Constante constante -> constante.texto();
            case Variavel variavel -> variavel.nome();
            case Negativo negativo -> "-" + escrever(negativo.dentro());
            case Agrupado agrupado -> "(" + escrever(agrupado.dentro()) + ")";
            case Conta conta -> escrever(conta.esquerda()) + " " + conta.sinal() + " " + escrever(conta.direita());
            case Funcao funcao -> funcao.nome() + "(" + escrever(funcao.primeiro()) + ", " + escrever(funcao.segundo()) + ")";
        };
    }

    private static DadosInvalidosException recusado(String frase) {
        return new DadosInvalidosException("O script da conta não passou: " + frase, Map.of("script", frase));
    }
}
