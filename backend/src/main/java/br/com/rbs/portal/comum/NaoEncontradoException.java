package br.com.rbs.portal.comum;

/**
 * Recurso que não existe OU que pertence a outra parceria: o TratadorDeErros devolve 404 nos dois casos.
 * [ISOLAMENTO] A resposta igual impede que um patrocinador descubra, pelo código do erro, quais ids existem em outras parcerias.
 */
public class NaoEncontradoException extends RuntimeException {

    public NaoEncontradoException(String mensagem) {
        super(mensagem);
    }

    /** Mensagem padrão, usada quando a rota não precisa dizer qual recurso faltou. */
    public NaoEncontradoException() {
        super("Não encontramos o que você pediu.");
    }
}
