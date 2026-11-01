package br.com.rbs.portal.atlas.ferramentas;

/**
 * O que uma ferramenta fez: o JSON do resultado; {@code pausa} se abriu o cartão (sem resultado até a pessoa responder);
 * {@code pronto} se fechou o relatório; {@code direta} se o resultado já é a resposta à pessoa (o {@code returnDirect} do plano):
 * o loop executa as outras chamadas da rodada, grava a resposta de todas e encerra o turno sem outra ida ao modelo. [ATLAS]
 * Resultado que é erro nunca é direto: o erro volta ao modelo, que explica à pessoa.
 */
public record Execucao(String resultadoJson, boolean pausa, boolean pronto, boolean direta) {

    /** Uma execução comum: sem pausa, sem relatório pronto e sem resposta direta. */
    public Execucao(String resultadoJson, boolean pausa, boolean pronto) {
        this(resultadoJson, pausa, pronto, false);
    }

    public static Execucao resultado(String json) {
        return new Execucao(json, false, false);
    }

    /** O resultado é a resposta à pessoa: o modelo não escreve nada depois dele. */
    public static Execucao direta(String json) {
        return new Execucao(json, false, false, true);
    }
}
