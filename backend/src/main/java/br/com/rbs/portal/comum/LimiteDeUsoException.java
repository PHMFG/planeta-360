package br.com.rbs.portal.comum;

/**
 * Um limite de uso foi atingido (hoje, o teto diário de mensagens do Atlas por parceria): o TratadorDeErros devolve 429 com a
 * frase abaixo, que diz o que fazer no lugar. Diferente de 409 (estado que não deixa) e de 503 (parte de fora do ar).
 */
public class LimiteDeUsoException extends RuntimeException {

    public LimiteDeUsoException(String mensagem) {
        super(mensagem);
    }
}
