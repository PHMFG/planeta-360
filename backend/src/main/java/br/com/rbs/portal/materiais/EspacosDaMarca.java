package br.com.rbs.portal.materiais;

import java.util.Map;
import java.util.Set;

/**
 * Os espaços de documento da marca (plano final, seção 5.5): para cada papel de entrega, a aba e a categoria do arquivo são fixas.
 * O papel DOCUMENTO_INGRESSOS fica no Perfil; MIV e LOGO ficam em Aplicações. A categoria nunca vem do corpo do envio.
 */
final class EspacosDaMarca {

    static final String DOCUMENTO_INGRESSOS = "DOCUMENTO_INGRESSOS";
    static final String MIV = "MIV";
    static final String LOGO = "LOGO";

    /** Os papéis com UMA entrega por parceria (uq_entrega_papel), criada no primeiro envio. */
    static final Set<String> UNICOS = Set.of(MIV, LOGO);

    static final String ABA_PERFIL = "PERFIL";
    static final String ABA_APLICACOES = "APLICACOES";

    private static final Map<String, String> ABA = Map.of(DOCUMENTO_INGRESSOS, ABA_PERFIL, MIV, ABA_APLICACOES, LOGO, ABA_APLICACOES);
    private static final Map<String, String> CATEGORIA = Map.of(DOCUMENTO_INGRESSOS, "DOCUMENTO_INGRESSOS", MIV, "MATERIAL_MARCA",
            LOGO, "MATERIAL_MARCA");
    private static final Map<String, String> TITULO = Map.of(MIV, "Manual de identidade visual da marca (MIV)", LOGO, "Logo da marca");

    private EspacosDaMarca() {
    }

    static boolean papelValido(String papel) {
        return papel != null && ABA.containsKey(papel);
    }

    static String abaDoPapel(String papel) {
        return ABA.get(papel);
    }

    static String categoriaDoPapel(String papel) {
        return CATEGORIA.get(papel);
    }

    /** O título da entrega única criada no primeiro envio (MIV ou logo). */
    static String tituloDoPapel(String papel) {
        return TITULO.get(papel);
    }

    static String nomeDaAba(String codigo) {
        return ABA_PERFIL.equals(codigo) ? "Perfil" : "Aplicações";
    }
}
