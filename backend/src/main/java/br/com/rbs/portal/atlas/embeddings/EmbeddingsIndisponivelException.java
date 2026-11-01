package br.com.rbs.portal.atlas.embeddings;

/**
 * Os embeddings não puderam ser gerados: o provedor está desligado, sem chave, fora do ar ou respondeu algo que o Atlas não aceita.
 * [ATLAS] A mensagem é em português e pronta para ir ao {@code documento.erro} e ao log: nunca leva a chave do provedor.
 */
public class EmbeddingsIndisponivelException extends RuntimeException {

    public EmbeddingsIndisponivelException(String mensagem) {
        super(mensagem);
    }

    public EmbeddingsIndisponivelException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
