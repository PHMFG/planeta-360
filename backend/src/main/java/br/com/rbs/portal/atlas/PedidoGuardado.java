package br.com.rbs.portal.atlas;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * O pedido HTTP com o que o histórico lê dele (IP e navegador) copiado na hora. [ATLAS] Na resposta em fluxo, o turno roda em
 * outra thread e pode passar do fim da conexão (o cliente foi embora); o servidor reaproveita o objeto do pedido depois disso, e
 * o histórico gravado no meio do turno não pode depender dele.
 */
final class PedidoGuardado extends HttpServletRequestWrapper {

    private final String ip;
    private final String navegador;

    PedidoGuardado(HttpServletRequest pedido) {
        super(pedido);
        this.ip = pedido.getRemoteAddr();
        this.navegador = pedido.getHeader("User-Agent");
    }

    @Override
    public String getRemoteAddr() {
        return ip;
    }

    @Override
    public String getHeader(String nome) {
        return "User-Agent".equalsIgnoreCase(nome) ? navegador : null;
    }
}
