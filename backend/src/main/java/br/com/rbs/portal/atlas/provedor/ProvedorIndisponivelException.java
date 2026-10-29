package br.com.rbs.portal.atlas.provedor;

/**
 * O modelo de linguagem não respondeu: rede, tempo esgotado, limite do provedor, resposta que não se entende. [ATLAS] A mensagem
 * é segura para o log (sem chave, sem cabeçalho); o loop a troca por uma frase para a pessoa e segue com o histórico válido.
 */
public class ProvedorIndisponivelException extends RuntimeException {

    public ProvedorIndisponivelException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }

    public ProvedorIndisponivelException(String mensagem) {
        super(mensagem);
    }
}
