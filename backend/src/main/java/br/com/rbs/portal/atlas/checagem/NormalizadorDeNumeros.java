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
 * {@code "04/12/2026"}, {@code "4/12"}, {@code "4 de dezembro"} e {@code "2026-12-04"}, {@code "6h30"} e {@code "06:30"},
 * {@code "89,4%"} e {@code "89,4"} são iguais. É a mesma conta do avaliador de conversas
 * ({@code ferramentas/avaliacao-atlas/numeros.mjs}).
 * <p>
 * <b>Numeral por extenso.</b> Todo numeral conta como número, na resposta e na fonte, menos "um" e "uma", que são artigo e nunca
 * contam (nem como origem), e "dois" e "duas", que na resposta só contam com unidade ou escala ("os dois manuais" não é número; na
 * fonte, "duas rodadas" é a origem de "2 rodadas"). Numeral seguido de "de &lt;mês&gt;" é data ("dez de dezembro", "vinte e cinco
 * de dezembro"; "sete e oito de dezembro" são duas). "Por cento" depois de algarismo não é o numeral cento. Os numerais só somam em
 * ordem decrescente de grandeza (centena, dezena de 20 a 90, unidade de 1 a 19): "trezentos e doze" é 312, mas "três e quatro" são
 * o 3 e o 4, e "dez e trinta", o 10 e o 30. Cada número separado é conferido por si.
 * <p>
 * Datas e horas são consumidas antes dos números, para {@code 04/12/2026} não virar três números. Cada dígito conta: o que vem
 * dentro de uma palavra ({@code planeta360}, {@code D09}) também. "Planeta 360", o nome do festival, não é número. Sem estado: tudo
 * estático.
 */
public final class NormalizadorDeNumeros {

    private static final List<String> MESES = List.of("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto",
            "setembro", "outubro", "novembro", "dezembro");
    private static final String NOME_DO_MES = String.join("|", MESES);

    private static final Map<String, Integer> EXTENSO = new LinkedHashMap<>();

    static {
        String[] nomes = {"um", "uma", "dois", "duas", "três", "tres", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez", "onze",
                "doze", "treze", "quatorze", "catorze", "quinze", "dezesseis", "dezessete", "dezoito", "dezenove", "vinte", "trinta",
                "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa", "cem", "cento", "duzentos", "duzentas",
                "trezentos", "trezentas", "quatrocentos", "quatrocentas", "quinhentos", "quinhentas", "seiscentos", "seiscentas",
                "setecentos", "setecentas", "oitocentos", "oitocentas", "novecentos", "novecentas"};
        int[] valores = {1, 1, 2, 2, 3, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 14, 15, 16, 17, 18, 19, 20, 30, 40, 50, 60, 70, 80, 90,
                100, 100, 200, 200, 300, 300, 400, 400, 500, 500, 600, 600, 700, 700, 800, 800, 900, 900};
        for (int i = 0; i < nomes.length; i++) {
            EXTENSO.put(nomes[i], valores[i]);
        }
    }

    private static final String PALAVRA = String.join("|", EXTENSO.keySet());
    private static final String ESCALA = "mil|milh(?:ão|ões|ao|oes)";
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
    /** "dez de dezembro", "vinte e cinco de dezembro de 2026", "sete e oito de dezembro": o numeral seguido de "de mês" é data. */
    private static final Pattern DATA_COM_NUMERAL = Pattern.compile("(?<![\\p{L}\\p{N}])((?:" + PALAVRA + ")(?:\\s+(?:e\\s+)?(?:" + PALAVRA
            + "))*)\\s+de\\s+(" + NOME_DO_MES + ")(?:\\s+de\\s+(\\d{4}))?" + NAO_ACABA_A_PALAVRA,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern MILHAR_BR = Pattern.compile("^\\d{1,3}(\\.\\d{3})+(,\\d+)?$");
    private static final Pattern ISO = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    private static final Pattern DATA_COM_ANO = Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})\\b");
    private static final Pattern DATA_COM_ANO_CURTO = Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})/(\\d{2})\\b");
    private static final Pattern DATA_SEM_ANO = Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})\\b");
    // "29 e 30 de janeiro", "de 25 a 28 de janeiro" e "4 de dezembro de 2026".
    private static final Pattern DATA_POR_EXTENSO = Pattern.compile("\\b(\\d{1,2})º?((?:\\s*(?:,|\\be\\b|\\ba\\b|até(?![\\p{L}]))\\s*\\d{1,2}º?)*)\\s+de\\s+("
            + NOME_DO_MES + ")(?:\\s+de\\s+(\\d{4}))?", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern HORA_DOIS_PONTOS = Pattern.compile("\\b(\\d{1,2}):(\\d{2})\\b");
    private static final Pattern HORA_COM_H = Pattern.compile("\\b(\\d{1,2})h(\\d{2})?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HORAS_POR_EXTENSO = Pattern.compile("\\b(\\d{1,3})\\s*horas?\\b", Pattern.CASE_INSENSITIVE);
    // "2 milhões", "1,5 milhão", "3 mil": o algarismo com a escala por extenso vale o número inteiro.
    private static final Pattern ALGARISMO_COM_ESCALA = Pattern.compile("(?<![\\p{L}\\p{N}.,])(\\d+(?:,\\d+)?)\\s+(mil|milh(?:ão|ões|ao|oes))(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NUMERO = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final Pattern ANO = Pattern.compile("^\\d{4}$");
    private static final Pattern REAL_ANTES = Pattern.compile("R\\$\\s*$");
    /** A palavra que, logo antes de quatro dígitos, faz deles um ano: "edição 2027", "em 2026", "Planeta Atlântida 2027". */
    private static final Pattern PALAVRA_DE_ANO_ANTES = Pattern.compile("(?<![\\p{L}\\p{N}])(?:edi[çc][ãa]o|edi[çc][õo]es|ano|em|de|desde|at[ée]"
            + "|atl[âa]ntida)(?::\\s*|\\s+)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern UNIDADE_DEPOIS = Pattern.compile("\\s*(?:de\\s+)?(?:" + UNIDADES + ")" + NAO_ACABA_A_PALAVRA,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Um número de 1 a 3 dígitos no começo da linha, nos formatos de lista: {@code 1.}, {@code 1)}, {@code **1.**}, {@code ### 1.}, {@code 1 - }. */
    private static final Pattern MARCADOR_DE_LISTA = Pattern.compile(
            "(?m)^([ \\t]*)(?:#{1,6}[ \\t]+)?(?:\\*\\*)?(\\d{1,3})(?:[.)]|[ \\t]+-)(?:\\*\\*)?[ \\t]");

    private NormalizadorDeNumeros() {
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
        String t = bruto.replaceAll("\\s", "");
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

    /**
     * Os números e as datas do texto. Em uma {@code fonte}, todo numeral por extenso conta, menos "um" e "uma"; na resposta, "dois" e
     * "duas" só contam com unidade ou escala.
     */
    public static List<Achado> extrair(String textoOriginal, boolean fonte) {
        List<Achado> achados = new ArrayList<>();
        String texto = " " + (textoOriginal == null ? "" : textoOriginal) + " ";
        texto = texto.replace("Planeta 360", "           ");

        texto = consumir(texto, ISO, achados, (m, todo) -> List.of(data(m.group(), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)),
                m.group(1))));
        texto = consumir(texto, DATA_COM_ANO, achados, (m, todo) -> List.of(data(m.group(), Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)), m.group(3))));
        texto = consumir(texto, DATA_COM_ANO_CURTO, achados, (m, todo) -> List.of(data(m.group(), Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)), "20" + m.group(3))));
        texto = consumir(texto, DATA_SEM_ANO, achados, (m, todo) -> List.of(data(m.group(), Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)), null)));
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

        texto = consumir(texto, HORA_DOIS_PONTOS, achados, (m, todo) -> List.of(hora(m.group(), Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)))));
        texto = consumir(texto, HORA_COM_H, achados, (m, todo) -> List.of(hora(m.group(), Integer.parseInt(m.group(1)),
                m.group(2) == null ? 0 : Integer.parseInt(m.group(2)))));
        texto = consumir(texto, HORAS_POR_EXTENSO, achados, (m, todo) -> {
            int horas = Integer.parseInt(m.group(1));
            return List.of(horas <= 24 ? valor(m.group(), "hora:" + duasCasas(horas) + ":00", String.valueOf(horas))
                    : valor(m.group(), String.valueOf(horas)));
        });

        texto = consumir(texto, ALGARISMO_COM_ESCALA, achados, (m, todo) -> {
            BigDecimal escala = m.group(2).equalsIgnoreCase("mil") ? BigDecimal.valueOf(1000) : BigDecimal.valueOf(1_000_000);
            BigDecimal valor = new BigDecimal(m.group(1).replace(',', '.')).multiply(escala);
            return List.of(valor(m.group(), valor.stripTrailingZeros().toPlainString()));
        });
        // "dez de dezembro" é uma data; "sete e oito de dezembro", duas. Vem antes dos numerais soltos.
        texto = consumir(texto, DATA_COM_NUMERAL, achados, (m, todo) -> {
            List<Achado> datas = new ArrayList<>();
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
            return List.of(ehAno(m, todo) ? valor(trecho, normalizarNumero(trecho), "ano:" + trecho)
                    : valor(trecho, normalizarNumero(trecho)));
        });
        return achados;
    }

    /**
     * Quatro dígitos são um ano só se: não têm separador, não vêm depois de "R$", não vêm antes de uma unidade ("2026 credenciais") e
     * a palavra de antes é de ano ({@link #PALAVRA_DE_ANO_ANTES}: edição, edições, ano, em, de, desde, até, Atlântida). "R$ 2027",
     * "São 2027 geradores" e "2026 credenciais" são valores, e nunca um ano.
     */
    private static boolean ehAno(Matcher numero, String todo) {
        if (!ANO.matcher(numero.group()).matches()) {
            return false;
        }
        String antes = todo.substring(0, numero.start());
        if (REAL_ANTES.matcher(antes).find() || !PALAVRA_DE_ANO_ANTES.matcher(antes).find()) {
            return false;
        }
        return !UNIDADE_DEPOIS.matcher(todo).region(numero.end(), todo.length()).lookingAt();
    }

    /** Um número escrito por extenso: o valor e se teve escala (mil, milhão). */
    private record Grupo(long valor, boolean temEscala) {
    }

    /** Centena (3), dezena de 20 a 90 (2) ou unidade de 1 a 19 (1): os numerais só somam se a grandeza cair. */
    private static int grandeza(int valor) {
        return valor >= 100 ? 3 : valor >= 20 ? 2 : 1;
    }

    /**
     * Os números de uma corrida de numerais. Eles só somam em ordem decrescente de grandeza ("trezentos e doze", "vinte e cinco",
     * "dois mil e quinhentos"); quando a grandeza não cai, começa outro número: "três e quatro" são o 3 e o 4, "dez e trinta" o 10
     * e o 30. A escala (mil, milhão) multiplica o que veio antes dela e recomeça a ordem.
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
            if (palavra.equals("mil") || palavra.startsWith("milh")) {
                total += (parcial == 0 ? 1 : parcial) * (palavra.equals("mil") ? 1000L : 1_000_000L);
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
     */
    public static final class Origens {

        private final Set<String> valores = new HashSet<>();
        private Set<String> anosAceitos;

        public Origens(String... textos) {
            for (String texto : textos) {
                adicionar(texto);
            }
        }

        public Origens adicionar(String texto) {
            for (Achado achado : extrair(texto == null ? "" : texto, true)) {
                if (achado.ehData()) {
                    valores.add("data:" + achado.dia());
                    if (achado.ano() != null) {
                        valores.add("data:" + achado.ano() + "-" + achado.dia());
                        valores.add("dataComAno:" + achado.dia());
                        valores.add("ano:" + achado.ano());
                        valores.add(achado.ano());
                    }
                } else {
                    valores.addAll(achado.valores());
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

        public boolean aceita(Achado achado) {
            if (!achado.ehData()) {
                return achado.valores().stream().anyMatch(valores::contains);
            }
            if (achado.ano() == null) {
                return valores.contains("data:" + achado.dia());
            }
            if (valores.contains("data:" + achado.ano() + "-" + achado.dia())) {
                return true;
            }
            return valores.contains("data:" + achado.dia()) && !trazDataComAno(achado.dia())
                    && (anosAceitos == null || anosAceitos.contains(achado.ano()));
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
        String resto = texto;
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
