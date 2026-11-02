package br.com.rbs.portal.atlas.checagem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Onde a frase da resposta do chat termina (plano do Atlas completo, M4). [ATLAS] [VALIDACAO] A regra "todo número aparece na fonte
 * citada na MESMA frase" depende de uma só definição de frase, e o filtro do fluxo e a checagem do texto inteiro usam esta:
 * <ul>
 *   <li>a frase termina em {@code . ! ? …} seguido de espaço ({@code FIM_DE_FRASE} da {@code ChecagemDeTexto}), ou numa quebra de linha;
 *       aspas, parêntese e o {@code *} ou {@code _} do Markdown podem fechar entre o ponto e o espaço ({@code **fim.** });
 *       {@code 1.250,00}, {@code 3.2} e {@code 04/12/2026.} (sem espaço depois do ponto) não cortam;</li>
 *   <li>as citações que vêm logo depois do ponto ({@code ...2026. [T1]}) ficam presas à frase anterior, que é a que elas apoiam;</li>
 *   <li>o ponto de {@code p.}, {@code art.}, {@code Sr.} e dos outros apelidos de abreviatura não termina a frase;</li>
 *   <li>o ponto dentro de um marcador {@code {{ref}}} não conta, e a numeração de lista no começo da linha ({@code 1. }, {@code **1.**},
 *       {@code ### 1.}) não corta, mas só em sequência ({@link NormalizadorDeNumeros#numeracoesDeLista}): {@code 312. } é um número
 *       seguido do fim da frase.</li>
 * </ul>
 * Para o fluxo, {@link #fimDaPrimeiraFraseCompleta} diz até onde um texto que ainda está chegando já tem uma frase de que não vai
 * mudar mais: enquanto o que vem depois do ponto pode ser o começo de uma citação, a frase fica em aberto.
 */
public final class Frases {

    /** {@code [T1]}, {@code [R2]}, {@code [C3]} e {@code [T1, T2]}: a citação de uma fonte do turno. */
    public static final Pattern CITACAO = Pattern.compile("\\[\\s*[TRC]\\d{1,3}(?:\\s*,\\s*[TRC]\\d{1,3})*+\\s*]");

    private static final Set<String> ABREVIATURAS = Set.of("p", "pp", "pág", "págs", "pag", "pags", "art", "arts", "sr", "sra", "dr", "dra",
            "prof", "av", "tel", "cel", "ex", "obs", "cf", "vol", "fl", "fls", "cap", "inc", "sec", "seç", "nº", "n");
    /** O que pode fechar a frase depois do ponto, antes do espaço: aspas, parêntese e o negrito ou itálico ({@code **fim.** }). */
    private static final String FECHAMENTOS = "\"')»”’*_";

    private Frases() {
    }

    /** O começo e o fim (exclusivo) de uma frase dentro do texto. */
    public record Trecho(int inicio, int fim) {
    }

    /** As frases do texto, na ordem; a última pode estar incompleta. Frases em branco não entram. */
    public static List<String> dividir(String texto) {
        List<String> frases = new ArrayList<>();
        int desde = 0;
        while (desde < texto.length()) {
            int fim = fimDaPrimeiraFraseCompleta(texto, desde);
            if (fim < 0) {
                fim = texto.length();
            }
            String frase = texto.substring(desde, fim).strip();
            if (!frase.isEmpty()) {
                frases.add(frase);
            }
            desde = fim;
            while (desde < texto.length() && Character.isWhitespace(texto.charAt(desde))) {
                desde++;
            }
        }
        return frases;
    }

    /**
     * Onde termina (exclusivo) a primeira frase completa que começa em {@code desde}, ou -1 se ainda não dá para saber: o texto parou
     * logo depois do ponto, ou depois dele pode vir uma citação que ainda não chegou inteira, ou há um marcador {@code {{} aberto.
     * Com o texto inteiro na mão (o {@code fechar} do fluxo), quem chama trata o -1 como "o resto é a última frase".
     */
    public static int fimDaPrimeiraFraseCompleta(String texto, int desde) {
        int tamanho = texto.length();
        for (int i = desde; i < tamanho; i++) {
            char c = texto.charAt(i);
            if (c == '{' && texto.startsWith("{{", i)) {
                int fecha = texto.indexOf("}}", i + 2);
                if (fecha < 0) {
                    return -1;   // marcador ainda não fechou
                }
                i = fecha + 1;
                continue;
            }
            if (c == '\n') {
                if (texto.substring(desde, i).isBlank()) {
                    desde = i + 1;   // linhas em branco seguidas não são frase
                    continue;
                }
                return i;
            }
            if (c != '.' && c != '!' && c != '?' && c != '…') {
                continue;
            }
            int depois = i + 1;
            while (depois < tamanho && ".!?…".indexOf(texto.charAt(depois)) >= 0) {
                depois++;   // "...", "?!"
            }
            while (depois < tamanho && FECHAMENTOS.indexOf(texto.charAt(depois)) >= 0) {
                depois++;
            }
            if (depois >= tamanho) {
                return -1;   // o texto parou no ponto: o que vem depois decide
            }
            if (!Character.isWhitespace(texto.charAt(depois))) {
                i = depois - 1;
                continue;   // 1.250,00, 3.2, 04/12/2026.[T1]
            }
            if (c == '.' && ehAbreviaturaOuLista(texto, desde, i)) {
                i = depois - 1;
                continue;
            }
            return absorverCitacoes(texto, depois);
        }
        return -1;
    }

    /**
     * Depois do ponto, as citações ({@code [T1]}) ainda são da frase. Devolve o fim da frase: o do ponto, ou o da última citação.
     * -1 se o texto parou onde ainda dá para vir uma citação.
     */
    private static int absorverCitacoes(String texto, int depoisDoPonto) {
        int tamanho = texto.length();
        int fim = depoisDoPonto;
        int j = depoisDoPonto;
        while (true) {
            while (j < tamanho && Character.isWhitespace(texto.charAt(j)) && texto.charAt(j) != '\n') {
                j++;
            }
            if (j >= tamanho) {
                return -1;   // só espaços depois: pode vir uma citação ou a frase seguinte
            }
            if (texto.charAt(j) != '[') {
                return fim;
            }
            Matcher citacao = CITACAO.matcher(texto);
            citacao.region(j, tamanho);
            if (citacao.lookingAt()) {
                fim = citacao.end();
                j = fim;
                continue;
            }
            if (aindaPodeSerCitacao(texto, j)) {
                return -1;   // "[T" ou "[ T1 , " ainda pode virar citação
            }
            return fim;   // "[nota]" e afins: começa outra frase
        }
    }

    /**
     * O colchete que abre em {@code abre} e não fechou ainda pode virar uma citação ({@code [T1]}, {@code [ T1 , T2 ]})? Sim se, até
     * onde o texto chegou, ele só tem espaços, letras T, R ou C com 1 a 3 algarismos, e vírgulas entre elas ({@code "["}, {@code "[T"},
     * {@code "[T1,"}, {@code "[ T1 , T"}); não se já saiu do formato ({@code "[T1 T2"}, {@code "[T1234"}, {@code "[1"}). Sem limite de
     * tamanho: a citação com muitas fontes e espaços chega aos poucos, e cortar a frase no meio dela tiraria a citação da frase.
     * Uma passada só, sem expressão regular, para o custo não passar do tamanho do texto.
     */
    private static boolean aindaPodeSerCitacao(String texto, int abre) {
        int tamanho = texto.length();
        int i = abre + 1;
        while (true) {
            i = depoisDosEspacos(texto, i);
            if (i >= tamanho) {
                return true;
            }
            if ("TRC".indexOf(texto.charAt(i)) < 0) {
                return false;
            }
            i++;
            int algarismos = 0;
            while (i < tamanho && texto.charAt(i) >= '0' && texto.charAt(i) <= '9') {
                algarismos++;
                i++;
                if (algarismos > 3) {
                    return false;
                }
            }
            if (i >= tamanho) {
                return true;
            }
            if (algarismos == 0) {
                return false;
            }
            i = depoisDosEspacos(texto, i);
            if (i >= tamanho) {
                return true;
            }
            if (texto.charAt(i) != ',') {
                return false;   // o "]" que fecharia uma citação inteira já foi visto antes; qualquer outra coisa não é citação
            }
            i++;
        }
    }

    private static int depoisDosEspacos(String texto, int desde) {
        int i = desde;
        while (i < texto.length() && Character.isWhitespace(texto.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean ehAbreviaturaOuLista(String texto, int inicioDaFrase, int posicaoDoPonto) {
        int fimDaPalavra = posicaoDoPonto;
        int inicioDaPalavra = fimDaPalavra;
        while (inicioDaPalavra > inicioDaFrase && (Character.isLetter(texto.charAt(inicioDaPalavra - 1)) || texto.charAt(inicioDaPalavra - 1) == 'º')) {
            inicioDaPalavra--;
        }
        String palavra = texto.substring(inicioDaPalavra, fimDaPalavra).toLowerCase(Locale.ROOT);
        if (!palavra.isEmpty() && ABREVIATURAS.contains(palavra)) {
            return true;
        }
        // "1. " no começo da linha, em sequência, é numeração de lista, não o fim de uma frase; "312. " é um número
        for (int[] numeracao : NormalizadorDeNumeros.numeracoesDeLista(texto)) {
            if (posicaoDoPonto >= numeracao[0] && posicaoDoPonto < numeracao[1]) {
                return true;
            }
        }
        return false;
    }

    /** As citações ({@code T1}, {@code R2}, {@code C3}) de um texto, na ordem em que aparecem, repetidas incluídas. */
    public static List<String> citacoes(String texto) {
        List<String> marcadores = new ArrayList<>();
        Matcher m = CITACAO.matcher(texto);
        Matcher cada = Pattern.compile("[TRC]\\d{1,3}").matcher("");
        while (m.find()) {
            cada.reset(m.group());
            while (cada.find()) {
                marcadores.add(cada.group());
            }
        }
        return marcadores;
    }

    /** O texto sem as citações. */
    public static String semCitacoes(String texto) {
        return CITACAO.matcher(texto).replaceAll(" ");
    }
}
