package br.com.rbs.portal.atlas.checagem;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Acha os números e as datas de um texto e os deixa numa forma comparável (plano do Atlas completo, M4). [ATLAS] [VALIDACAO] É a
 * regra de "número com origem" do chat: {@code "1.250.000,00"} e {@code "1250000"}, {@code "R$ 312"} e {@code "312"},
 * {@code "04/12/2026"}, {@code "4/12"}, {@code "4 de dezembro"}, {@code "2026-12-04"} e {@code "04-12-2026"}, {@code "6h30"} e
 * {@code "06:30"}, {@code "89,4%"} e {@code "89,4"} são iguais. É a mesma conta do avaliador de conversas
 * ({@code ferramentas/avaliacao-atlas/numeros.mjs}).
 * <p>
 * <b>O texto antes da conta</b> ({@link #paraConferir}): saem os invisíveis (os de formato do Unicode e os que escondem um número
 * sem ser de formato: U+034F, os seletores de variação, o preenchimento do hangul), o texto vai a NFC, todo algarismo de outro
 * alfabeto, de largura total, sobrescrito ou fração vira o comum, e todo espaço do Unicode e toda tabulação viram um espaço comum
 * (uma corrida deles, um só). A resposta, a fonte e a mensagem da pessoa passam pela mesma dobra.
 * <p>
 * <b>Numeral por extenso.</b> Todo numeral conta como número, na resposta e na fonte, menos "um" e "uma", que são artigo e nunca
 * contam (nem como origem), e "dois" e "duas", que na resposta só contam com unidade ou escala ("os dois manuais" não é número; na
 * fonte, "duas rodadas" é a origem de "2 rodadas"). Numeral seguido de "de &lt;mês&gt;" é data ("dez de dezembro", "vinte e cinco
 * de dezembro", "primeiro de dezembro"; "sete e oito de dezembro" são duas). "Por cento" depois de algarismo não é o numeral
 * cento. Os numerais só somam em ordem decrescente de grandeza (centena, dezena de 20 a 90, unidade de 1 a 19): "trezentos e doze"
 * é 312, mas "três e quatro" são o 3 e o 4, e "dez e trinta", o 10 e o 30. Cada número separado é conferido por si. A escala é mil,
 * milhão e bilhão.
 * <p>
 * <b>Grupos e anos.</b> Grupos de três dígitos separados por espaço são um número só na resposta ({@code R$ 2 220,00} é 2220); numa
 * fonte valem o inteiro e as partes, porque tabela tem colunas. Quatro dígitos são um <i>ano</i> se não têm separador, estão entre
 * 1900 e 2099, não vêm depois de {@code R$} e não vêm antes de uma unidade da lista: {@code Para 2027}, {@code (2027)} e
 * {@code Manual de Operações 2027} são anos; {@code R$ 2027}, {@code 2026 credenciais} e {@code 1250 pessoas} são valores. A
 * palavra de antes não importa. Um ano vale só como ano ({@code ano:2027}), nunca como valor: nem o de uma data da fonte, nem o do
 * título dela, nem o da edição ou o de hoje ({@link Origens#adicionarAnoLivre}).
 * <p>
 * <b>Datas e horas</b> são consumidas antes dos números, para {@code 04/12/2026} não virar três números. Uma barra só é data se o
 * dia está entre 1 e 31 e o mês entre 1 e 12 ({@code 40/20} e {@code 10/15 kVA} são dois números). A hora sem minutos
 * ({@code 18h}, {@code 18hs}, {@code 18 h}, {@code 18h00min}, {@code 18:00}, {@code 18:00h}, {@code 18 horas}) vale como a hora e
 * como o número da hora, na fonte e na resposta; com mais de 24 horas ({@code 48h}) é só o número. Cada dígito conta: o que vem
 * dentro de uma palavra ({@code planeta360}, {@code D09}) também. "Planeta 360", o nome do festival, em qualquer caixa, não é
 * número. Sem estado: tudo estático.
 */
public final class NormalizadorDeNumeros {

    private static final List<String> MESES = List.of("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto",
            "setembro", "outubro", "novembro", "dezembro");
    private static final String NOME_DO_MES = String.join("|", MESES);

    private static final Map<String, Integer> EXTENSO = new LinkedHashMap<>();

    static {
        String[] nomes = {"zero", "um", "uma", "dois", "duas", "três", "tres", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez", "onze",
                "doze", "treze", "quatorze", "catorze", "quinze", "dezesseis", "dezessete", "dezoito", "dezenove", "vinte", "trinta",
                "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa", "cem", "cento", "duzentos", "duzentas",
                "trezentos", "trezentas", "quatrocentos", "quatrocentas", "quinhentos", "quinhentas", "seiscentos", "seiscentas",
                "setecentos", "setecentas", "oitocentos", "oitocentas", "novecentos", "novecentas"};
        int[] valores = {0, 1, 1, 2, 2, 3, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 14, 15, 16, 17, 18, 19, 20, 30, 40, 50, 60, 70, 80, 90,
                100, 100, 200, 200, 300, 300, 400, 400, 500, 500, 600, 600, 700, 700, 800, 800, 900, 900};
        for (int i = 0; i < nomes.length; i++) {
            EXTENSO.put(nomes[i], valores[i]);
        }
    }

    private static final String PALAVRA = String.join("|", EXTENSO.keySet());
    private static final String ESCALA = "mil|milh(?:ão|ões|ao|oes)|bilh(?:ão|ões|ao|oes)";
    private static final String UNIDADES = "reais|real|dias?|horas?|minutos?|kva|kw|por\\s+cento|porcento|%|credenciais|credencial|cortesias?"
            + "|inserç(?:ão|ões)|ingressos?|pulseiras?|metros?|semanas?|meses|aplicaç(?:ão|ões)|pessoas?|litros?|visitantes?|cadastros?"
            + "|rodadas?|peças?|vezes";
    private static final String NAO_ACABA_A_PALAVRA = "(?![\\p{L}\\p{N}])";

    /**
     * Uma corrida de numerais ("trezentos e doze", "três e quatro", "dois milhões"), com a unidade que vem depois, se houver. "Por
     * cento" não é o numeral cento: depois de "por", a corrida não pode começar por ele. A corrida pode ter mais de um número (veja
     * {@link #porExtenso}).
     */
    private static final Pattern CORRIDA_POR_EXTENSO = Pattern.compile("(?<![\\p{L}\\p{N}])(?:(?<!\\bpor\\s{1,8})|(?!cento"
            + NAO_ACABA_A_PALAVRA + "))((?:" + PALAVRA + "|" + ESCALA + ")(?:\\s+(?:e\\s+)?(?:" + PALAVRA + "|" + ESCALA + "))*)"
            + NAO_ACABA_A_PALAVRA + "(\\s+(?:de\\s+)?(?:" + UNIDADES + ")" + NAO_ACABA_A_PALAVRA + ")?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /**
     * "dez de dezembro", "vinte e cinco de dezembro de 2026", "sete e oito de dezembro", "primeiro de dezembro": o numeral seguido de
     * "de mês" é data. "Primeiro" só é número assim, antes de "de mês": "a primeira vez" nunca é número.
     */
    private static final Pattern DATA_COM_NUMERAL = Pattern.compile("(?<![\\p{L}\\p{N}])(primeiro|(?:" + PALAVRA + ")(?:\\s+(?:e\\s+)?(?:" + PALAVRA
            + "))*)\\s+de\\s+(" + NOME_DO_MES + ")(?:\\s+de\\s+(\\d{4}))?" + NAO_ACABA_A_PALAVRA,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** O nome do festival, em qualquer caixa: não é o número 360. */
    private static final Pattern NOME_DO_FESTIVAL = Pattern.compile("(?<![\\p{L}\\p{N}])planeta\\s+360(?!\\p{N})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern MILHAR_BR = Pattern.compile("^\\d{1,3}(\\.\\d{3})+(,\\d+)?$");
    private static final Pattern ISO = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    /** "15-01-2027": a mesma data que "15/01/2027". */
    private static final Pattern DATA_COM_TRACO = Pattern.compile("\\b(\\d{1,2})-(\\d{1,2})-(\\d{4})\\b");
    private static final Pattern DATA_COM_ANO = Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})\\b");
    private static final Pattern DATA_COM_ANO_CURTO = Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})/(\\d{2})\\b");
    private static final Pattern DATA_SEM_ANO = Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})\\b");
    // "29 e 30 de janeiro", "de 25 a 28 de janeiro" e "4 de dezembro de 2026".
    private static final Pattern DATA_POR_EXTENSO = Pattern.compile("\\b(\\d{1,2})[º°]?((?:\\s*(?:,|\\be\\b|\\ba\\b|até(?![\\p{L}]))\\s*\\d{1,2}[º°]?)*)\\s+de\\s+("
            + NOME_DO_MES + ")(?:\\s+de\\s+(\\d{4}))?", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    // "18:00", "18:00h", "05:00hs": a hora com dois pontos, com o "h" (e o "s" ou "oras") que a segue, se houver.
    private static final Pattern HORA_DOIS_PONTOS = Pattern.compile("\\b(\\d{1,2}):(\\d{2})(?: ?h(?:s|oras?)?)?" + NAO_ACABA_A_PALAVRA,
            Pattern.CASE_INSENSITIVE);
    // "18h", "18hs", "18 h", "18 horas", "6h30", "18h00min", "48h": o "h" vem logo depois (ou com um espaço); os minutos são opcionais.
    private static final Pattern HORA_COM_H = Pattern.compile("\\b(\\d{1,3}) ?h(?:s|oras?)?(?:(\\d{2})(?: ?min(?:utos?)?)?)?" + NAO_ACABA_A_PALAVRA,
            Pattern.CASE_INSENSITIVE);
    // "2 milhões", "1,5 milhão", "3 mil": o algarismo com a escala por extenso vale o número inteiro.
    private static final Pattern ALGARISMO_COM_ESCALA = Pattern.compile("(?<![\\p{L}\\p{N}.,])(\\d+(?:,\\d+)?)\\s+(" + ESCALA + ")(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** O espaço que separa os grupos de milhar: "2 220,00". Todo outro espaço do Unicode já virou este em {@link #paraConferir}. */
    private static final String ESPACO_DE_GRUPO = "[ \u00A0\u202F]";
    /**
     * Um número. Três dígitos depois de um espaço, com até três antes, são o mesmo número ({@code 2 220,00} é 2220): na resposta
     * valem juntos, e é o inteiro que precisa de origem. {@code 12 3456}, {@code 220 2} e {@code 2 22} são números separados.
     */
    private static final Pattern NUMERO = Pattern.compile("\\d{1,3}(?:" + ESPACO_DE_GRUPO + "\\d{3})+(?!\\d)(?:[.,]\\d+)?|\\d+(?:[.,]\\d+)*");
    private static final Pattern ANO = Pattern.compile("^\\d{4}$");
    private static final Pattern DIA_DO_MES = Pattern.compile("[1-9]|[12][0-9]|3[01]");
    /** A palavra "dia" ou "dias" (inteira) no fim do trecho antes de um número. */
    private static final Pattern DIA_ANTES = Pattern.compile("(?<![\\p{L}\\p{N}])dias?\\s+$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** O valor extra de um número da resposta que vem logo depois de "dia"/"dias": só uma fonte citada o confirma ({@code dia:N}). */
    private static final String PREFIXO_DEPOIS_DE_DIA = "depoisDeDia:";
    /**
     * O dia solto de um trecho de documento: o número de 1 ou 2 algarismos que vem logo depois de um dia da semana ou da palavra "dia",
     * com a vírgula ou os dois pontos opcionais ({@code sexta-feira, 29}, {@code sábado 30}, {@code dia 29}, {@code Dia: 29}). Não é o
     * dia o número depois de {@code Portão}, {@code seção}, {@code item} ou de outro número, nem o que é parte de um número maior
     * ({@code 29,5}, {@code 29/12}, {@code 29:30}, {@code 29h}). {@code meio-dia} não é "dia".
     */
    private static final Pattern DIA_SOLTO_DEPOIS_DE_DIA_DA_SEMANA = Pattern.compile("(?<![\\p{L}\\p{N}-])(?:(?:domingo|segunda|ter[çc]a|quarta|quinta"
            + "|sexta|s[áa]bado)(?:[- ]feira)?|(?:dom|seg|ter|qua|qui|sex|s[áa]b)\\.|dias?)\\s*[,:]?\\s*(\\d{1,2})(?![\\p{L}\\p{N}]|[/:.,]\\d)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SEPARADOR_DE_GRUPO = Pattern.compile(ESPACO_DE_GRUPO);
    private static final Pattern REAL_ANTES = Pattern.compile("R\\$\\s*$");
    private static final Pattern UNIDADE_DEPOIS = Pattern.compile("\\s*(?:de\\s+)?(?:" + UNIDADES + ")" + NAO_ACABA_A_PALAVRA,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Um número de 1 a 3 dígitos no começo da linha, nos formatos de lista: {@code 1.}, {@code 1)}, {@code **1.**}, {@code ### 1.}, {@code 1 - }. */
    private static final Pattern MARCADOR_DE_LISTA = Pattern.compile(
            "(?m)^([ \\t]*)(?:#{1,6}[ \\t]+)?(?:\\*\\*)?(\\d{1,3})(?:[.)]|[ \\t]+-)(?:\\*\\*)?[ \\t]");

    /**
     * O que não aparece na tela mas parte ou reordena um número: os caracteres de formato do Unicode (categoria Cf: espaço de largura
     * zero, hífen opcional, os de junção, os de direção da escrita) e os que também são invisíveis sem ser de formato: U+034F (junção
     * de grafema), os seletores de variação (U+FE00 a U+FE0F e U+E0100 a U+E01EF), U+180B a U+180D (seletores mongóis), U+115F e
     * U+1160 (preenchimento do hangul), U+3164 e U+FFA0 (preenchimento do hangul, largura total e parcial).
     */
    private static final Pattern INVISIVEL = Pattern.compile("[\\p{Cf}\\u034F\\uFE00-\\uFE0F\\u180B-\\u180D\\u115F\\u1160\\u3164\\uFFA0\\x{E0100}-\\x{E01EF}]");
    /** Todo espaço do Unicode (separadores de espaço, como U+00A0, U+2003, U+2007, U+2009, U+202F, U+3000) e a tabulação; uma corrida é um só. */
    private static final Pattern ESPACOS = Pattern.compile("[\\p{Zs}\\t\\x0B\\f]+");

    private NormalizadorDeNumeros() {
    }

    // ---------------------------------------------------------------------
    // O texto antes da conta
    // ---------------------------------------------------------------------

    /**
     * O texto sem os caracteres invisíveis ({@link #INVISIVEL}). Não aparecem na tela, mas partem um número ({@code 3<ZWSP>12} lê-se
     * 312) ou reordenam os algarismos ({@code R$ <RLO>213} aparece como 312). O texto que se confere e o que vai à tela e ao
     * histórico é este. Nulo continua nulo.
     */
    public static String semInvisiveis(String texto) {
        return texto == null ? null : INVISIVEL.matcher(texto).replaceAll("");
    }

    /**
     * Todo algarismo vira o comum, 0 a 9: o de outro alfabeto (árabe-índico, devanágari), o de largura total e o sobrescrito ou
     * fração ({@code ²}, {@code ½}). O resto do texto fica como está (a letra com acento, a vírgula, o "º").
     */
    public static String dobrarAlgarismos(String texto) {
        StringBuilder dobrado = null;
        for (int i = 0; i < texto.length(); ) {
            int c = texto.codePointAt(i);
            int tamanho = Character.charCount(c);
            String troca = null;
            int tipo = Character.getType(c);
            if (tipo == Character.DECIMAL_DIGIT_NUMBER && c > 127) {
                troca = String.valueOf(Character.digit(c, 10));
            } else if (tipo == Character.OTHER_NUMBER || tipo == Character.LETTER_NUMBER) {
                troca = Normalizer.normalize(new String(Character.toChars(c)), Normalizer.Form.NFKC);
            }
            if (troca != null && dobrado == null) {
                dobrado = new StringBuilder(texto.length() + 8).append(texto, 0, i);
            }
            if (dobrado != null) {
                dobrado.append(troca != null ? troca : texto.substring(i, i + tamanho));
            }
            i += tamanho;
        }
        return dobrado == null ? texto : dobrado.toString();
    }

    /**
     * O texto como a conta o lê: sem os invisíveis, em NFC (a letra com acento solta, "e" + "^", é a mesma que a composta: o numeral
     * "três" em NFD não escapa), com os algarismos dobrados para os comuns e com todo espaço do Unicode e toda tabulação trocados
     * por um espaço comum (uma corrida deles, por um só; a quebra de linha fica). A resposta, a fonte e a mensagem da pessoa passam
     * por aqui. Idempotente. Nulo vira o texto vazio.
     */
    public static String paraConferir(String texto) {
        if (texto == null) {
            return "";
        }
        String limpo = Normalizer.normalize(INVISIVEL.matcher(texto).replaceAll(""), Normalizer.Form.NFC);
        return ESPACOS.matcher(dobrarAlgarismos(limpo)).replaceAll(" ");
    }

    /**
     * Um número ou data achado: o trecho do texto onde estava e os valores que o representam. Uma data leva {@link #dia} ("MM-DD") e,
     * se o texto disse o ano, {@link #ano}; os outros levam os valores em {@link #valores}.
     */
    public record Achado(String trecho, List<String> valores, String dia, String ano) {

        public boolean ehData() {
            return dia != null;
        }
    }

    // ---------------------------------------------------------------------
    // Extrair
    // ---------------------------------------------------------------------

    /** "1.250,50" vira "1250.5"; "312" fica "312". Ponto só é milhar quando separa grupos de 3. */
    public static String normalizarNumero(String bruto) {
        String t = bruto.replaceAll("[\\s\u00A0\u202F]", "");
        if (MILHAR_BR.matcher(t).matches()) {
            t = t.replace(".", "").replace(',', '.');
        } else {
            t = t.replace(',', '.');
        }
        try {
            return new BigDecimal(t).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException naoEhNumero) {
            return t;   // "3.2.1": não é número, fica como está
        }
    }

    private static String duasCasas(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    /**
     * Um consumidor devolve o achado (ou os achados); nulo deixa o trecho como está. Recebe o texto todo, para quem precisa do que vem
     * antes ou depois do trecho.
     */
    @FunctionalInterface
    private interface Montador {

        List<Achado> montar(Matcher m, String todo);
    }

    private static String consumir(String texto, Pattern padrao, List<Achado> achados, Montador montador) {
        Matcher m = padrao.matcher(texto);
        StringBuilder saida = new StringBuilder();
        int fim = 0;
        while (m.find()) {
            List<Achado> novos = montador.montar(m, texto);
            if (novos == null) {
                continue;
            }
            achados.addAll(novos);
            saida.append(texto, fim, m.start()).append(" ".repeat(m.end() - m.start()));
            fim = m.end();
        }
        return saida.append(texto.substring(fim)).toString();
    }

    private static Achado data(String trecho, int dia, int mes, String ano) {
        return new Achado(trecho, List.of(), duasCasas(mes) + "-" + duasCasas(dia), ano);
    }

    private static Achado valor(String trecho, String... valores) {
        return new Achado(trecho, List.of(valores), null, null);
    }

    private static Achado hora(String trecho, int h, int m) {
        return valor(trecho, "hora:" + duasCasas(h) + ":" + duasCasas(m));
    }

    private static int mes(String nome) {
        return MESES.indexOf(nome.toLowerCase(Locale.ROOT)) + 1;
    }

    /** Dia de 1 a 31 e mês de 1 a 12: fora disso, a barra ou o traço separam dois números ("40/20", "10/15 kVA"). */
    private static boolean diaEMesPossiveis(int dia, int mes) {
        return dia >= 1 && dia <= 31 && mes >= 1 && mes <= 12;
    }

    /** A data de dia, mês e ano dados como texto, ou nulo (o trecho fica como está) se o dia ou o mês não são possíveis. */
    private static List<Achado> dataValida(String trecho, String dia, String mes, String ano) {
        int d = Integer.parseInt(dia);
        int m = Integer.parseInt(mes);
        return diaEMesPossiveis(d, m) ? List.of(data(trecho, d, m, ano)) : null;
    }

    /**
     * Uma hora escrita com "h" ou com dois pontos. Sem minutos (ou com minutos 00), é a hora cheia e também o número da hora
     * ({@code 18h} vale {@code hora:18:00} e {@code 18}), na fonte e na resposta. Com minutos, é só a hora com eles. Acima de 24
     * horas ou com minuto impossível ({@code 48h}, {@code 99:99}) não é hora do dia: são só os números.
     */
    private static List<Achado> horario(String trecho, int h, int minutos, boolean comMinutos) {
        if (h > 24 || minutos > 59) {
            List<Achado> numeros = new ArrayList<>(List.of(valor(trecho, String.valueOf(h))));
            if (comMinutos) {
                numeros.add(valor(trecho, String.valueOf(minutos)));
            }
            return numeros;
        }
        if (minutos == 0) {
            return List.of(valor(trecho, "hora:" + duasCasas(h) + ":00", String.valueOf(h)));
        }
        return List.of(hora(trecho, h, minutos));
    }

    /** A escala por extenso: mil, milhão ou bilhão (com ou sem acento, no singular ou no plural). */
    private static long escala(String palavra) {
        String p = palavra.toLowerCase(Locale.ROOT);
        return p.equals("mil") ? 1_000L : p.startsWith("milh") ? 1_000_000L : 1_000_000_000L;
    }

    /**
     * Os números e as datas do texto. O texto passa antes por {@link #paraConferir}. Em uma {@code fonte}, todo numeral por extenso
     * conta, menos "um" e "uma"; na resposta, "dois" e "duas" só contam com unidade ou escala.
     */
    public static List<Achado> extrair(String textoOriginal, boolean fonte) {
        List<Achado> achados = new ArrayList<>();
        String texto = NOME_DO_FESTIVAL.matcher(" " + paraConferir(textoOriginal) + " ").replaceAll(nome -> " ".repeat(nome.group().length()));

        texto = consumir(texto, ISO, achados, (m, todo) -> dataValida(m.group(), m.group(3), m.group(2), m.group(1)));
        texto = consumir(texto, DATA_COM_ANO, achados, (m, todo) -> dataValida(m.group(), m.group(1), m.group(2), m.group(3)));
        texto = consumir(texto, DATA_COM_TRACO, achados, (m, todo) -> dataValida(m.group(), m.group(1), m.group(2), m.group(3)));
        texto = consumir(texto, DATA_COM_ANO_CURTO, achados, (m, todo) -> dataValida(m.group(), m.group(1), m.group(2), "20" + m.group(3)));
        texto = consumir(texto, DATA_SEM_ANO, achados, (m, todo) -> dataValida(m.group(), m.group(1), m.group(2), null));
        texto = consumir(texto, DATA_POR_EXTENSO, achados, (m, todo) -> {
            List<Achado> datas = new ArrayList<>();
            List<String> dias = new ArrayList<>(List.of(m.group(1)));
            Matcher outros = Pattern.compile("\\d{1,2}").matcher(m.group(2));
            while (outros.find()) {
                dias.add(outros.group());
            }
            for (String dia : dias) {
                datas.add(data(m.group(), Integer.parseInt(dia), mes(m.group(3)), m.group(4)));
            }
            return datas;
        });

        texto = consumir(texto, HORA_DOIS_PONTOS, achados, (m, todo) -> horario(m.group(), Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)), true));
        texto = consumir(texto, HORA_COM_H, achados, (m, todo) -> horario(m.group(), Integer.parseInt(m.group(1)),
                m.group(2) == null ? 0 : Integer.parseInt(m.group(2)), m.group(2) != null));

        texto = consumir(texto, ALGARISMO_COM_ESCALA, achados, (m, todo) -> {
            BigDecimal valor = new BigDecimal(m.group(1).replace(',', '.')).multiply(BigDecimal.valueOf(escala(m.group(2))));
            return List.of(valor(m.group(), valor.stripTrailingZeros().toPlainString()));
        });
        // "dez de dezembro" é uma data; "sete e oito de dezembro", duas. Vem antes dos numerais soltos.
        texto = consumir(texto, DATA_COM_NUMERAL, achados, (m, todo) -> {
            List<Achado> datas = new ArrayList<>();
            if (m.group(1).equalsIgnoreCase("primeiro")) {
                datas.add(data(m.group(), 1, mes(m.group(2)), m.group(3)));
                return datas;
            }
            for (Grupo grupo : porExtenso(m.group(1))) {
                datas.add(data(m.group(), (int) grupo.valor(), mes(m.group(2)), m.group(3)));
            }
            return datas;
        });
        texto = consumir(texto, CORRIDA_POR_EXTENSO, achados, (m, todo) -> {
            boolean comUnidade = m.group(2) != null;
            List<Achado> numeros = new ArrayList<>();
            for (Grupo grupo : porExtenso(m.group(1))) {
                if (contaComoNumero(grupo, comUnidade, fonte)) {
                    numeros.add(valor(m.group(), String.valueOf(grupo.valor())));
                }
            }
            return numeros.isEmpty() ? null : numeros;
        });

        // O resto: números com milhar e decimal. A vírgula de uma lista ("19, 17") não é decimal, porque vem seguida de espaço.
        consumir(texto, NUMERO, achados, (m, todo) -> {
            String trecho = m.group();
            if (SEPARADOR_DE_GRUPO.matcher(trecho).find()) {
                // "2 220,00": o inteiro. Numa fonte, as partes também valem, porque tabela tem colunas de números.
                List<String> valores = new ArrayList<>(List.of(normalizarNumero(trecho)));
                if (fonte) {
                    for (String parte : SEPARADOR_DE_GRUPO.split(trecho)) {
                        valores.add(normalizarNumero(parte));
                    }
                }
                return List.of(valor(trecho, valores.toArray(new String[0])));
            }
            if (ehAno(m, todo)) {
                return List.of(valor(trecho, normalizarNumero(trecho), "ano:" + trecho));
            }
            String numero = normalizarNumero(trecho);
            if (!fonte && Origens.ehDiaDoMes(numero) && depoisDaPalavraDia(todo, m.start())) {
                // "dia 4", "no dia 4": na resposta, o número logo depois de "dia" ou "dias" também pode ser o dia de uma data da fonte
                return List.of(valor(trecho, numero, PREFIXO_DEPOIS_DE_DIA + numero));
            }
            return List.of(valor(trecho, numero));
        });
        return achados;
    }

    /**
     * Quatro dígitos são um ano se: não têm separador, estão entre 1900 e 2099, não vêm depois de "R$" e não vêm antes de uma
     * unidade da lista ({@link #UNIDADES}). A palavra de antes não importa: "Para 2027", "(2027)", "Manual de Operações 2027" e "São
     * 2027 geradores" são anos (este último é artificial, e passa de propósito); "R$ 2027", "2026 credenciais", "1250 pessoas" e
     * "2.027" são valores.
     */
    private static boolean ehAno(Matcher numero, String todo) {
        if (!ANO.matcher(numero.group()).matches()) {
            return false;
        }
        int valor = Integer.parseInt(numero.group());
        if (valor < 1900 || valor > 2099) {
            return false;
        }
        String antes = todo.substring(Math.max(0, numero.start() - 8), numero.start());
        if (REAL_ANTES.matcher(antes).find()) {
            return false;
        }
        return !UNIDADE_DEPOIS.matcher(todo).region(numero.end(), todo.length()).lookingAt();
    }

    /** A palavra "dia" ou "dias" logo antes (só espaços entre ela e o número)? A janela de 7 caracteres basta: "dias " tem 5. */
    private static boolean depoisDaPalavraDia(String todo, int inicioDoNumero) {
        return DIA_ANTES.matcher(todo.substring(Math.max(0, inicioDoNumero - 7), inicioDoNumero)).find();
    }

    /** Um número escrito por extenso: o valor e se teve escala (mil, milhão, bilhão). */
    private record Grupo(long valor, boolean temEscala) {
    }

    /** Centena (3), dezena de 20 a 90 (2) ou unidade de 1 a 19 (1): os numerais só somam se a grandeza cair. */
    private static int grandeza(int valor) {
        return valor >= 100 ? 3 : valor >= 20 ? 2 : 1;
    }

    /**
     * Os números de uma corrida de numerais. Eles só somam em ordem decrescente de grandeza ("trezentos e doze", "vinte e cinco",
     * "dois mil e quinhentos"); quando a grandeza não cai, começa outro número: "três e quatro" são o 3 e o 4, "dez e trinta" o 10
     * e o 30. A escala (mil, milhão, bilhão) multiplica o que veio antes dela e recomeça a ordem.
     */
    private static List<Grupo> porExtenso(String palavras) {
        List<Grupo> grupos = new ArrayList<>();
        long total = 0;
        long parcial = 0;
        boolean temEscala = false;
        int ultima = 4;
        for (String palavra : palavras.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (palavra.isEmpty() || palavra.equals("e")) {
                continue;
            }
            if (palavra.equals("mil") || palavra.startsWith("milh") || palavra.startsWith("bilh")) {
                total += (parcial == 0 ? 1 : parcial) * escala(palavra);
                parcial = 0;
                temEscala = true;
                ultima = 4;
                continue;
            }
            int valor = EXTENSO.get(palavra);
            if (grandeza(valor) >= ultima) {
                grupos.add(new Grupo(total + parcial, temEscala));
                total = 0;
                parcial = 0;
                temEscala = false;
            }
            parcial += valor;
            ultima = grandeza(valor);
        }
        grupos.add(new Grupo(total + parcial, temEscala));
        return grupos;
    }

    /**
     * "Um" e "uma" são artigo e nunca contam, nem na fonte. "Dois" e "duas" só contam na resposta com unidade (ou escala): "os dois
     * manuais" não é número, e "duas rodadas" na fonte é a origem de "2 rodadas". O resto conta sempre.
     */
    private static boolean contaComoNumero(Grupo grupo, boolean comUnidade, boolean fonte) {
        return grupo.temEscala() || (grupo.valor() != 1 && (fonte || grupo.valor() != 2 || comUnidade));
    }

    // ---------------------------------------------------------------------
    // As origens: o que um conjunto de textos permite afirmar
    // ---------------------------------------------------------------------

    /**
     * Tudo o que um conjunto de textos permite afirmar. Data com ano só vale no ano certo, se a fonte disser o ano. Se ela não disser
     * (data sem ano), vale com qualquer ano na resposta, a menos que {@link #anosAceitos} limite quais.
     * <p>
     * <b>O ano não é valor, na resposta.</b> O ano de uma data da fonte e os quatro dígitos que são ano (título, "Para 2027") valem só
     * como ano ({@code ano:2027}); "R$ 2.026,00" e "2026 credenciais" precisam do número escrito como número. O literal do
     * {@code calcular} é outra regra ({@link #aceitaComoValor}): o número escrito solto no texto vale como valor, e só o ano de uma data
     * não.
     * <p>
     * <b>A fonte citada diz mais</b> ({@link #adicionarFonteCitada}): o dia de uma de suas datas vale como o número que vem logo
     * depois de "dia" ou "dias" na resposta ({@code dia 4}, {@code no dia 4} com a fonte em 04/12/2026; {@code 4 geradores} e {@code
     * R$ 4} são números comuns e precisam estar na fonte como número), e uma data da resposta vale se o <i>texto</i> da fonte tem o dia solto e alguma data
     * do mesmo mês (e do mesmo ano, se a resposta o disse): "29 e 30 de janeiro de 2027" com a fonte "sexta-feira, 29, e no sábado, 30
     * de janeiro de 2027". <b>O dia solto</b> é o número que vem logo depois de um dia da semana ou da palavra "dia", com a vírgula
     * opcional ("sexta-feira, 29", "sábado 30", "dia 29"): o 5 de "Portão 5", de "seção 5" ou de "item 5" não é o dia 5, e "05/12/2026" não
     * vale só porque o trecho tem um 5 solto e a data 04/12/2026. O número da página, da seção e da versão da fonte ({@link #adicionar}) não é o dia: com a fonte na página
     * 5, "05/12/2026" não vale só porque a fonte tem a data 04/12/2026. A mensagem da pessoa e a data de hoje também não dizem isso.
     * <p>
     * <b>O registro e a conta citados dizem o dia, e só o dia</b> ({@link #adicionarRegistro}): "O prazo é o dia 4 [R1]" vale com
     * R1 em 04/12/2026, e "Cai no dia 30 [C1]" com a conta em 30/11/2026. A montagem de data em pedaços (o dia solto de um número
     * mais o mês de outra data) continua só no trecho de documento: com R1 "até 30 segundos, atualizada em 27/10/2026",
     * "30/10/2026" não vale.
     */
    public static final class Origens {

        private final Set<String> valores = new HashSet<>();
        /**
         * Os quatro dígitos entre 1900 e 2099 escritos soltos no texto ({@code Diária | 1950}, {@code 10% de 2000}): na resposta valem só
         * como ano, e para o literal do {@code calcular} ({@link #aceitaComoValor}) valem como o número que são. O ano de uma data não
         * entra aqui: a data é consumida antes.
         */
        private final Set<String> numerosSoltosComoAno = new HashSet<>();
        private Set<String> anosAceitos;

        public Origens(String... textos) {
            for (String texto : textos) {
                adicionar(texto);
            }
        }

        /** Um texto que vale como está escrito: a mensagem da pessoa, os metadados de uma fonte. */
        public Origens adicionar(String texto) {
            return somar(texto, false, false);
        }

        /**
         * O texto de um registro do portal ou de uma conta citados na frase: vale por inteiro, e o dia de uma data que ele traz inteira
         * vale como o número depois de "dia" ("Prazo: 04/12/2026" diz "dia 4"). A data em pedaços (o dia solto mais o mês de outra
         * data) fica só no trecho de documento ({@link #adicionarFonteCitada}).
         */
        public Origens adicionarRegistro(String texto) {
            return somar(texto, false, true);
        }

        /** O texto de um trecho de documento citado na frase: vale como está escrito e também em pedaços (veja o resumo da classe). */
        public Origens adicionarFonteCitada(String texto) {
            return somar(texto, true, true);
        }

        private Origens somar(String texto, boolean emPedacos, boolean comODiaDaData) {
            for (Achado achado : extrair(texto == null ? "" : texto, true)) {
                if (achado.ehData()) {
                    valores.add("data:" + achado.dia());
                    String mes = achado.dia().substring(0, 2);
                    if (achado.ano() != null) {
                        valores.add("data:" + achado.ano() + "-" + achado.dia());
                        valores.add("dataComAno:" + achado.dia());
                        valores.add("ano:" + achado.ano());
                    }
                    if (comODiaDaData) {
                        valores.add("dia:" + Integer.parseInt(achado.dia().substring(3)));
                    }
                    if (emPedacos) {
                        valores.add("mes:" + mes);
                        valores.add(achado.ano() != null ? "mesAno:" + achado.ano() + "-" + mes : "mesSemAno:" + mes);
                    }
                } else {
                    boolean ehAno = achado.valores().stream().anyMatch(v -> v.startsWith("ano:"));
                    if (ehAno) {
                        numerosSoltosComoAno.add(achado.valores().get(0));   // o primeiro valor é o número; o segundo, "ano:AAAA"
                    }
                    for (String valor : achado.valores()) {
                        if (!ehAno || valor.startsWith("ano:")) {   // o ano vale como ano, e não como o número
                            valores.add(valor);
                        }
                    }
                }
            }
            if (emPedacos) {
                // o dia solto: só o número que vem logo depois de dia da semana ou de "dia" (o 5 de "Portão 5" não é o dia 5)
                Matcher diaSolto = DIA_SOLTO_DEPOIS_DE_DIA_DA_SEMANA.matcher(paraConferir(texto));
                while (diaSolto.find()) {
                    String dia = normalizarNumero(diaSolto.group(1));
                    if (ehDiaDoMes(dia)) {
                        valores.add("numeroDoTexto:" + dia);
                    }
                }
            }
            return this;
        }

        /**
         * Os anos que uma data sem ano (nesta origem) libera na resposta: na checagem do chat, o ano da edição, o anterior a ela e o
         * de hoje. Sem a lista, qualquer ano.
         */
        public Origens anosAceitos(Set<String> anos) {
            this.anosAceitos = anos;
            return this;
        }

        /** O ano da edição ou de hoje: vale como ano ("em 2027"), nunca como valor ("R$ 2.027,00", "2026 credenciais"). */
        public Origens adicionarAnoLivre(int ano) {
            valores.add("ano:" + ano);
            return this;
        }

        /** A data de hoje, em qualquer formato, sem liberar o ano como valor. */
        public Origens adicionarData(LocalDate data) {
            String dia = duasCasas(data.getMonthValue()) + "-" + duasCasas(data.getDayOfMonth());
            valores.add("data:" + dia);
            valores.add("data:" + data.getYear() + "-" + dia);
            valores.add("dataComAno:" + dia);
            return this;
        }

        /** Alguma origem disse esta data ({@code "MM-DD"}) <b>com ano</b>? Então só esse ano vale para ela. */
        public boolean trazDataComAno(String dia) {
            return valores.contains("dataComAno:" + dia);
        }

        /** Esta origem trouxe exatamente esta data, com este ano ("04/12/2027" escrito por quem a trouxe)? */
        public boolean temDataExata(Achado achado) {
            return achado.ehData() && achado.ano() != null && valores.contains("data:" + achado.ano() + "-" + achado.dia());
        }

        public boolean aceita(Achado achado) {
            if (!achado.ehData()) {
                for (String valor : achado.valores()) {
                    if (valores.contains(valor)
                            || (valor.startsWith(PREFIXO_DEPOIS_DE_DIA) && valores.contains("dia:" + valor.substring(PREFIXO_DEPOIS_DE_DIA.length())))) {
                        return true;
                    }
                }
                return false;
            }
            if (achado.ano() == null ? valores.contains("data:" + achado.dia()) : aceitaDataComAno(achado)) {
                return true;
            }
            return aceitaPeloDiaESoltoEPeloMes(achado);
        }

        /**
         * O número como <b>valor</b>: só vale se a origem o trouxe como número, e um número escrito solto no texto vale mesmo entre
         * 1900 e 2099 ({@code 10% de 2000}, {@code Diária | 1950}). Nunca pela regra do ano ({@code ano:2027} não faz {@code 2027}
         * valer como valor, nem o ano que está dentro de uma data, nem o da edição ou o de hoje), pelo dia solto depois de "dia" nem
         * por pedaço de data. É o que o literal do {@code calcular} exige; a regra da resposta ({@link #aceita}) não muda.
         */
        public boolean aceitaComoValor(Achado achado) {
            if (achado.ehData()) {
                return false;
            }
            for (String valor : achado.valores()) {
                if (!valor.startsWith("ano:") && !valor.startsWith(PREFIXO_DEPOIS_DE_DIA)
                        && (valores.contains(valor) || numerosSoltosComoAno.contains(valor))) {
                    return true;
                }
            }
            return false;
        }

        private boolean aceitaDataComAno(Achado achado) {
            if (valores.contains("data:" + achado.ano() + "-" + achado.dia())) {
                return true;
            }
            return valores.contains("data:" + achado.dia()) && !trazDataComAno(achado.dia())
                    && (anosAceitos == null || anosAceitos.contains(achado.ano()));
        }

        /**
         * A fonte citada tem o dia da data como número e alguma data do mesmo mês (e, se a resposta disse o ano, do mesmo ano, ou sem
         * ano mas num ano que {@link #anosAceitos} libera).
         */
        private boolean aceitaPeloDiaESoltoEPeloMes(Achado achado) {
            String mes = achado.dia().substring(0, 2);
            String dia = String.valueOf(Integer.parseInt(achado.dia().substring(3)));
            if (!valores.contains("numeroDoTexto:" + dia) || !valores.contains("mes:" + mes)) {
                return false;
            }
            if (achado.ano() == null) {
                return true;
            }
            return valores.contains("mesAno:" + achado.ano() + "-" + mes)
                    || (valores.contains("mesSemAno:" + mes) && (anosAceitos == null || anosAceitos.contains(achado.ano())));
        }

        /** Um número inteiro de 1 a 31, escrito como {@link #normalizarNumero} o escreve (sem zero à esquerda). */
        static boolean ehDiaDoMes(String valor) {
            return DIA_DO_MES.matcher(valor).matches();
        }
    }

    // ---------------------------------------------------------------------
    // Apoio do texto
    // ---------------------------------------------------------------------

    /**
     * Onde estão, no texto, as numerações de lista: o começo da linha com {@code 1.}, {@code 1)}, {@code **1.**}, {@code ### 1.} ou
     * {@code 1 - }, <b>só em sequência</b>. A lista começa em 1 e cresce de um em um; um novo 1 recomeça a conta. Um número fora da
     * sequência ({@code 312. É o valor}, {@code 99) kVA}, {@code **500.** reais}) não é numeração: é um número, e precisa de origem.
     * Cada item é {@code [início, fim)}, sem o recuo da linha.
     */
    public static List<int[]> numeracoesDeLista(String texto) {
        List<int[]> numeracoes = new ArrayList<>();
        int esperado = 1;
        Matcher m = MARCADOR_DE_LISTA.matcher(texto);
        while (m.find()) {
            int numero = Integer.parseInt(m.group(2));
            if (numero == 1) {
                esperado = 1;
            }
            if (numero != esperado) {
                continue;
            }
            esperado++;
            numeracoes.add(new int[] {m.end(1), m.end()});
        }
        return numeracoes;
    }

    /** O texto sem a numeração de lista do começo da linha ({@link #numeracoesDeLista}); o recuo da linha fica. */
    public static String semNumeracaoDeLista(String texto) {
        StringBuilder saida = new StringBuilder(texto.length());
        int desde = 0;
        for (int[] numeracao : numeracoesDeLista(texto)) {
            saida.append(texto, desde, numeracao[0]);
            desde = numeracao[1];
        }
        return saida.append(texto, desde, texto.length()).toString();
    }

    /** O que sobra do texto sem os números: "Portão 5" vira "Portão". Serve para conferir as palavras em volta do número. */
    public static String palavrasFora(String texto) {
        String resto = paraConferir(texto);
        for (Achado achado : extrair(texto, true)) {
            resto = resto.replace(achado.trecho(), " ");
        }
        return resto;
    }

    /** O texto em minúsculas e sem acento, com os espaços normalizados. */
    public static String semAcento(String texto) {
        return Normalizer.normalize(texto == null ? "" : texto, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
