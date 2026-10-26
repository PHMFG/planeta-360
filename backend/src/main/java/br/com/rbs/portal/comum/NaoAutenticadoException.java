package br.com.rbs.portal.comum;

/**
 * Pedido sem identidade válida: o TratadorDeErros devolve 401. Usada no login errado e na sessão que deixou de valer.
 * [PERMISSAO] A mensagem é a mesma para e-mail inexistente, senha errada e acesso revogado, para não revelar quais e-mails existem.
 */
public class NaoAutenticadoException extends RuntimeException {

    public NaoAutenticadoException(String mensagem) {
        super(mensagem);
    }
}
