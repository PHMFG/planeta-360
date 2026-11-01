package br.com.rbs.portal.atlas.ferramentas;

/**
 * O que uma ferramenta fez: o JSON do resultado; {@code pausa} se abriu o cartão (sem resultado até a pessoa responder);
 * {@code pronto} se fechou o relatório. [ATLAS]
 */
public record Execucao(String resultadoJson, boolean pausa, boolean pronto) {

    public static Execucao resultado(String json) {
        return new Execucao(json, false, false);
    }
}
