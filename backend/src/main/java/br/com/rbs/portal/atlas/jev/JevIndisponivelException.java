package br.com.rbs.portal.atlas.jev;

/**
 * O JEV não respondeu como se esperava: sem chave, rede, tempo limite, status diferente de 200 (400, 401, 413, 415, 422, 429, 529 e
 * os outros) ou resposta que não se entende. [ATLAS] Não é falha do turno: o ponto de decisão cai para o caminho do modelo de
 * linguagem, sem nova tentativa. O {@link #motivo} é um código curto e sem segredo ({@code HTTP_429}, {@code TIMEOUT}, ...), o que
 * a decisão e o traço guardam; a mensagem nunca leva a chave nem o corpo da resposta do JEV.
 */
public class JevIndisponivelException extends RuntimeException {

    private final String motivo;

    public JevIndisponivelException(String motivo) {
        super("O JEV não respondeu: " + motivo + ".");
        this.motivo = motivo;
    }

    public String motivo() {
        return motivo;
    }
}
