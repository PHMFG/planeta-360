package br.com.rbs.portal.comum;

/**
 * Uma regra do negócio recusou a operação porque o estado atual não deixa (e-mail já autorizado, parceria repetida...):
 * o TratadorDeErros devolve 409 com a frase abaixo. Atende o item 3 da Entrega 2.
 * Serve para as regras que a API confere ANTES de gravar, com texto claro; as que só o banco sabe (SQLSTATE 23) o
 * TratadorDeErros já traduz sozinho para o mesmo 409.
 */
public class ConflitoException extends RuntimeException {

    public ConflitoException(String mensagem) {
        super(mensagem);
    }
}
