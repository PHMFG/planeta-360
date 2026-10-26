package br.com.rbs.portal.comum;

import org.springframework.http.HttpStatus;

/**
 * Um arquivo enviado que o portal não aceita: 413 (acima do limite de tamanho) ou 415 (extensão ou conteúdo fora da lista).
 * O TratadorDeErros devolve o status guardado aqui com a frase em português. Atende o item 4 da Entrega 2. [UPLOAD]
 */
public class ArquivoRecusadoException extends RuntimeException {

    private final HttpStatus status;

    public ArquivoRecusadoException(HttpStatus status, String mensagem) {
        super(mensagem);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
