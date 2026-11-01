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
 * {@code "89,4%"} e {@code "89,4"} são iguais. Numeral por extenso seguido de unidade ({@code "dezessete aplicações"},
 * {@code "trezentos reais"}) e com escala ({@code "três mil"}) conta como número; {@code "os dois manuais"} não. É a mesma conta do
 * avaliador de conversas ({@code ferramentas/avaliacao-atlas/numeros.mjs}).
 * <p>
 * Datas e horas são consumidas antes dos números, para {@code 04/12/2026} não virar três números. Numa FONTE, todo numeral por
 * extenso conta (porque "duas rodadas" é a origem de "2 rodadas"); na RESPOSTA, só com unidade, e "um dia" não é número: é artigo.
 * Cada dígito conta: o que vem dentro de uma palavra ({@code planeta360}, {@code D09}) também. "Planeta 360", o nome do festival,
 * não é número. Sem estado: tudo estático.
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
    private static final String ESCALA = "mil|milh(?:ão|ões)";
    private static final String UNIDADES = "reais|real|dias?|horas?|minutos?|kva|kw|por cento|credenciais|credencial|cortesias?|inserç(?:ão|ões)"
            + "|ingressos?|pulseiras?|metros?|semanas?|meses|aplicaç(?:ão|ões)|pessoas?|litros?|visitantes?|cadastros?|rodadas?|peças?|vezes";

    private static final Pattern CORRIDA_POR_EXTENSO = Pattern.compile("(?<![\\p{L}\\p{N}])((?:" + PALAVRA + "|" + ESCALA
            + ")(?:\\s+(?:e\\s+)?(?:" + PALAVRA + "|" + ESCALA + "))*)(?![\\p{L}\\p{N}])(\\s+(?:de\\s+)?(?:" + UNIDADES
            + ")(?![\\p{L}\\p{N}]))?", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
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

    /** A numeração de lista no começo da linha ("1. ", "2) ", "**1.** ") não conta como número da resposta. */
    private static final Pattern NUMERACAO_DE_LISTA = Pattern.compile("(?m)^([ \\t]*)(?:\\*\\*)?\\d{1,3}[.)](?:\\*\\*)?[ \\t]");

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

    /** Um consumidor devolve o achado (ou os achados) e se o trecho deve ser apagado; nulo deixa o trecho como está. */
    @FunctionalInterface
    private interface Montador {

        List<Achado> montar(Matcher m);
    }

    private static String consumir(String texto, Pattern padrao, List<Achado> achados, Montador montador) {
        Matcher m = padrao.matcher(texto);
        StringBuilder saida = new StringBuilder();
        int fim = 0;
        while (m.find()) {
            List<Achado> novos = montador.montar(m);
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
     * Os números e as datas do texto. Em uma {@code fonte}, todo numeral por extenso conta; na resposta, só com unidade (ou escala),
     * e "um dia" não é número.
     */
    public static List<Achado> extrair(String textoOriginal, boolean fonte) {
        List<Achado> achados = new ArrayList<>();
        String texto = " " + (textoOriginal == null ? "" : textoOriginal) + " ";
        texto = texto.replace("Planeta 360", "           ");

        texto = consumir(texto, ISO, achados, m -> List.of(data(m.group(), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)),
                m.group(1))));
        texto = consumir(texto, DATA_COM_ANO, achados, m -> List.of(data(m.group(), Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)), m.group(3))));
        texto = consumir(texto, DATA_COM_ANO_CURTO, achados, m -> List.of(data(m.group(), Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)), "20" + m.group(3))));
        texto = consumir(texto, DATA_SEM_ANO, achados, m -> List.of(data(m.group(), Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)), null)));
        texto = consumir(texto, DATA_POR_EXTENSO, achados, m -> {
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

        texto = consumir(texto, HORA_DOIS_PONTOS, achados, m -> List.of(hora(m.group(), Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)))));
        texto = consumir(texto, HORA_COM_H, achados, m -> List.of(hora(m.group(), Integer.parseInt(m.group(1)),
                m.group(2) == null ? 0 : Integer.parseInt(m.group(2)))));
        texto = consumir(texto, HORAS_POR_EXTENSO, achados, m -> {
            int horas = Integer.parseInt(m.group(1));
            return List.of(horas <= 24 ? valor(m.group(), "hora:" + duasCasas(horas) + ":00", String.valueOf(horas))
                    : valor(m.group(), String.valueOf(horas)));
        });

        texto = consumir(texto, ALGARISMO_COM_ESCALA, achados, m -> {
            BigDecimal escala = m.group(2).equalsIgnoreCase("mil") ? BigDecimal.valueOf(1000) : BigDecimal.valueOf(1_000_000);
            BigDecimal valor = new BigDecimal(m.group(1).replace(',', '.')).multiply(escala);
            return List.of(valor(m.group(), valor.stripTrailingZeros().toPlainString()));
        });
        texto = consumir(texto, CORRIDA_POR_EXTENSO, achados, m -> {
            Extenso extenso = porExtenso(m.group(1));
            if (fonte || extenso.temEscala()) {
                return List.of(valor(m.group(), String.valueOf(extenso.valor())));
            }
            return m.group(2) != null && extenso.valor() != 1 ? List.of(valor(m.group(), String.valueOf(extenso.valor()))) : null;
        });

        // O resto: números com milhar e decimal. A vírgula de uma lista ("19, 17") não é decimal, porque vem seguida de espaço.
        consumir(texto, NUMERO, achados, m -> {
            String trecho = m.group();
            return List.of(ANO.matcher(trecho).matches() ? valor(trecho, normalizarNumero(trecho), "ano:" + trecho)
                    : valor(trecho, normalizarNumero(trecho)));
        });
        return achados;
    }

    private record Extenso(long valor, boolean temEscala) {
    }

    private static Extenso porExtenso(String palavras) {
        long total = 0;
        long parcial = 0;
        boolean temEscala = false;
        for (String palavra : palavras.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (palavra.isEmpty() || palavra.equals("e")) {
                continue;
            }
            if (palavra.equals("mil")) {
                total += (parcial == 0 ? 1 : parcial) * 1000;
                parcial = 0;
                temEscala = true;
            } else if (palavra.startsWith("milh")) {
                total += (parcial == 0 ? 1 : parcial) * 1_000_000L;
                parcial = 0;
                temEscala = true;
            } else {
                parcial += EXTENSO.getOrDefault(palavra, 0);
            }
        }
        return new Extenso(total + parcial, temEscala);
    }

    // ---------------------------------------------------------------------
    // As origens: o que um conjunto de textos permite afirmar
    // ---------------------------------------------------------------------

    /**
     * Tudo o que um conjunto de textos permite afirmar. Data com ano só vale no ano certo, se a fonte disser o ano; data sem ano na
     * fonte vale com qualquer ano na resposta.
     */
    public static final class Origens {

        private final Set<String> valores = new HashSet<>();

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

        /** Uma data de hoje ("01/11/2026"), aceita em qualquer formato. */
        public Origens adicionarData(LocalDate data) {
            return adicionar(String.format("%02d/%02d/%d", data.getDayOfMonth(), data.getMonthValue(), data.getYear()));
        }

        public boolean aceita(Achado achado) {
            if (!achado.ehData()) {
                return achado.valores().stream().anyMatch(valores::contains);
            }
            if (achado.ano() == null) {
                return valores.contains("data:" + achado.dia());
            }
            return valores.contains("data:" + achado.ano() + "-" + achado.dia())
                    || (valores.contains("data:" + achado.dia()) && !valores.contains("dataComAno:" + achado.dia()));
        }
    }

    // ---------------------------------------------------------------------
    // Apoio do texto
    // ---------------------------------------------------------------------

    /** O texto sem a numeração de lista do começo da linha ({@code 1. }, {@code 2) }, {@code **1.** }). */
    public static String semNumeracaoDeLista(String texto) {
        return NUMERACAO_DE_LISTA.matcher(texto).replaceAll("$1");
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
