package br.com.rbs.portal.importacao.extracao;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A regra de palavras da conferência de texto corrido (rodada A1): cada palavra do valor, sem caixa e sem acento, precisa casar
 * com uma palavra do trecho, ignorando as ligações ("de", "da", "do", "das", "dos", "e", "com", "para", "no", "na", "em"). Duas
 * palavras casam se forem iguais, se uma for a outra mais "s" ou "es", ou se uma for prefixo da outra com pelo menos cinco letras
 * em comum. Os números do valor precisam existir no trecho: "5\"", "5 segundos", "5 seg" e "5s" são o mesmo número 5.
 */
public final class PalavrasDaFonte {

    private static final Set<String> LIGACOES = Set.of("de", "da", "do", "das", "dos", "e", "com", "para", "no", "na", "em",
            "nas", "nos");
    private static final Set<String> SEGUNDOS = Set.of("s", "seg", "segs", "segundo", "segundos");
    private static final Pattern NUMERO_COM_UNIDADE = Pattern.compile("^(\\d+(?:\\.\\d+)?)([a-z]+)$");

    private PalavrasDaFonte() {
    }

    /** O valor aparece no trecho pela regra de palavras; vazio nunca aparece. */
    public static boolean aparece(String valor, String trecho) {
        Tokens doValor = tokens(valor);
        if (doValor.vazio()) {
            return false;
        }
        Tokens daFonte = tokens(trecho);
        for (BigDecimal numero : doValor.numeros()) {
            if (daFonte.numeros().stream().noneMatch(outro -> outro.compareTo(numero) == 0)) {
                return false;
            }
        }
        for (String palavra : doValor.palavras()) {
            if (daFonte.palavras().stream().noneMatch(outra -> casam(palavra, outra))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Os dois textos falam do mesmo formato: as palavras de um aparecem no outro e os números são exatamente os mesmos. É o
     * casamento do plano (E11), em que "Comercial 15&quot;" nunca pode virar "Comercial 30&quot;".
     */
    public static boolean parecido(String um, String outro) {
        Tokens a = tokens(um);
        Tokens b = tokens(outro);
        if (a.vazio() || b.vazio() || !numerosIguais(a, b)) {
            return false;
        }
        return contem(a, b) || contem(b, a);
    }

    /** Números diferentes nas duas descrições: o casamento nunca pode acontecer, nem pela semelhança de trigramas. */
    public static boolean numerosConflitantes(String um, String outro) {
        Tokens a = tokens(um);
        Tokens b = tokens(outro);
        return !a.numeros().isEmpty() && !b.numeros().isEmpty() && !numerosIguais(a, b);
    }

    private static boolean contem(Tokens valor, Tokens fonte) {
        for (BigDecimal numero : valor.numeros()) {
            if (fonte.numeros().stream().noneMatch(outro -> outro.compareTo(numero) == 0)) {
                return false;
            }
        }
        for (String palavra : valor.palavras()) {
            if (fonte.palavras().stream().noneMatch(outra -> casam(palavra, outra))) {
                return false;
            }
        }
        return true;
    }

    private static boolean numerosIguais(Tokens a, Tokens b) {
        if (a.numeros().size() != b.numeros().size()) {
            return false;
        }
        List<BigDecimal> restantes = new ArrayList<>(b.numeros());
        for (BigDecimal numero : a.numeros()) {
            if (restantes.stream().noneMatch(outro -> outro.compareTo(numero) == 0)) {
                return false;
            }
            restantes.removeIf(outro -> outro.compareTo(numero) == 0);
        }
        return restantes.isEmpty();
    }

    private static boolean casam(String a, String b) {
        if (a.equals(b) || (a + "s").equals(b) || (b + "s").equals(a) || (a + "es").equals(b) || (b + "es").equals(a)) {
            return true;
        }
        int comum = 0;
        int limite = Math.min(a.length(), b.length());
        while (comum < limite && a.charAt(comum) == b.charAt(comum)) {
            comum++;
        }
        return comum >= 5 && (comum == a.length() || comum == b.length());
    }

    private record Tokens(List<String> palavras, List<BigDecimal> numeros) {

        boolean vazio() {
            return palavras.isEmpty() && numeros.isEmpty();
        }
    }

    private static Tokens tokens(String texto) {
        String normalizado = Normalizer.normalize(texto == null ? "" : texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("(?<=\\d),(?=\\d)", "\u0001")
                .replaceAll("[^a-z0-9\u0001]+", " ")
                .replace('\u0001', '.');
        List<String> palavras = new ArrayList<>();
        List<BigDecimal> numeros = new ArrayList<>();
        boolean depoisDeNumero = false;
        for (String bruto : normalizado.strip().split("\\s+")) {
            if (bruto.isEmpty() || ".".equals(bruto)) {
                continue;
            }
            if (bruto.matches("\\d+(?:\\.\\d+)?")) {
                numeros.add(new BigDecimal(bruto));
                depoisDeNumero = true;
                continue;
            }
            Matcher comUnidade = NUMERO_COM_UNIDADE.matcher(bruto);
            if (comUnidade.matches() && SEGUNDOS.contains(comUnidade.group(2))) {
                numeros.add(new BigDecimal(comUnidade.group(1)));
                depoisDeNumero = true;
                continue;
            }
            if (depoisDeNumero && SEGUNDOS.contains(bruto)) {
                depoisDeNumero = false;
                continue;
            }
            depoisDeNumero = false;
            if (!LIGACOES.contains(bruto)) {
                palavras.add(bruto);
            }
        }
        return new Tokens(List.copyOf(palavras), List.copyOf(numeros));
    }
}
