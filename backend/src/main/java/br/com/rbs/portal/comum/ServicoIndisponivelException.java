package br.com.rbs.portal.comum;

/**
 * Uma parte de que a operação depende não está disponível agora (por exemplo, o modelo de linguagem do Atlas): o TratadorDeErros
 * devolve 503 com a frase abaixo. Diferente de 500 (erro inesperado) e de 409 (estado que não deixa): aqui a falta é conhecida e a
 * frase diz o que fazer no lugar.
 */
public class ServicoIndisponivelException extends RuntimeException {

    public ServicoIndisponivelException(String mensagem) {
        super(mensagem);
    }
}
