package br.com.rbs.portal.materiais;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * [VALIDACAO] A regra do link de post (R4.3, R5.3): só {@code http} ou {@code https}, com host, sem "usuario@" antes do host, sem espaço,
 * controle nem bidi, até 500 caracteres. É a mesma regra do CHECK ck_conteudo_url (V16), conferida aqui antes de gravar (422, em vez
 * de uma violação do banco) e de novo na hora de listar, porque o link da carga (veiculacao.link) não tem CHECK: um endereço inválido
 * que esteja no banco nunca sai pela API. O {@code @} depois do host vale (o TikTok usa {@code /@usuario/video/...}). O servidor
 * nunca abre o endereço.
 */
public final class HostDoLink {

    public static final int LIMITE = 500;

    // \p{C} cobre os controles, os caracteres de formato (entre eles os bidi) e os não atribuídos; \p{Z} e \s, os espaços de todo tipo.
    private static final String PROIBIDOS = "\\s\\p{Z}\\p{C}";
    private static final Pattern FORMA = Pattern.compile(
            "https?://[^" + PROIBIDOS + "@/?#]+(?:[/?#][^" + PROIBIDOS + "]*)?");

    private HostDoLink() {
    }

    /** O endereço serve como link clicável? */
    public static boolean valido(String url) {
        return de(url) != null;
    }

    /** O domínio para mostrar ao lado do link (minúsculo, sem "www." nem porta), ou nulo se o endereço não é válido. */
    public static String de(String url) {
        if (url == null || url.length() > LIMITE || !FORMA.matcher(url).matches()) {
            return null;
        }
        String resto = url.substring(url.indexOf("://") + 3);
        int fim = resto.length();
        for (int i = 0; i < resto.length(); i++) {
            char c = resto.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                fim = i;
                break;
            }
        }
        String autoridade = resto.substring(0, fim);
        String host = autoridade.startsWith("[") ? autoridade : autoridade.replaceFirst(":.*$", "");
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        return host.isEmpty() ? null : host;
    }
}
