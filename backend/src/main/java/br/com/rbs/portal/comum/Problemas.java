package br.com.rbs.portal.comum;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Monta o corpo de todo erro da API (ProblemDetail, application/problem+json) com título e texto em português.
 * Atende o item 3 da Entrega 2. Fica em um lugar só para o erro do controller e o do filtro de segurança terem a mesma forma.
 */
public final class Problemas {

    private Problemas() {
    }

    /** Erro com o texto que o usuário vai ler em "detail". */
    public static ProblemDetail de(HttpStatusCode status, String detalhe) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalhe);
        // O Spring 7 deixa o type vazio; o contrato com o front diz "about:blank" (RFC 9457: o erro não tem página de explicação).
        problema.setType(URI.create("about:blank"));
        problema.setTitle(titulo(status));
        return problema;
    }

    /** Erro sem texto específico: usa o texto padrão do status. */
    public static ProblemDetail padrao(HttpStatusCode status) {
        return de(status, detalhePadrao(status));
    }

    /** [VALIDACAO] Erro 422 com a lista "campos": nome do campo -> o que está errado. */
    public static ProblemDetail validacao(Map<String, String> campos) {
        ProblemDetail problema = de(HttpStatus.UNPROCESSABLE_CONTENT, "Confira os campos destacados e tente de novo.");
        problema.setProperty("campos", campos);
        return problema;
    }

    /**
     * Converte para um mapa simples, para escrever o JSON direto na resposta dos filtros de segurança.
     * Os filtros rodam antes do Spring MVC, então não passam pelos conversores de mensagem.
     */
    public static Map<String, Object> comoMapa(ProblemDetail problema, String caminho) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("type", problema.getType() != null ? problema.getType().toString() : "about:blank");
        corpo.put("title", problema.getTitle());
        corpo.put("status", problema.getStatus());
        corpo.put("detail", problema.getDetail());
        corpo.put("instance", problema.getInstance() != null ? problema.getInstance() : URI.create(caminho));
        if (problema.getProperties() != null) {
            corpo.putAll(problema.getProperties());
        }
        return corpo;
    }

    private static String titulo(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "Requisição inválida";
            case 401 -> "Não autenticado";
            case 403 -> "Acesso negado";
            case 404 -> "Não encontrado";
            case 405 -> "Método não permitido";
            case 406 -> "Formato não disponível";
            case 409 -> "Conflito";
            case 413 -> "Arquivo grande demais";
            case 415 -> "Tipo de conteúdo não aceito";
            case 422 -> "Dados inválidos";
            case 500 -> "Erro interno";
            default -> status.value() >= 500 ? "Erro no servidor" : "Erro na requisição";
        };
    }

    static String detalhePadrao(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "Não foi possível entender a requisição.";
            case 401 -> "Entre no portal para continuar.";
            case 403 -> "Você não tem permissão para esta operação.";
            case 404 -> "Não encontramos o que você pediu.";
            case 405 -> "Esta rota não aceita esse tipo de chamada.";
            case 406 -> "A API não consegue responder no formato pedido.";
            case 409 -> "Esta operação conflita com uma regra do portal.";
            case 413 -> "O arquivo passa do tamanho permitido.";
            case 415 -> "A API não aceita esse tipo de conteúdo.";
            case 422 -> "Confira os dados enviados e tente de novo.";
            default -> "Algo deu errado do nosso lado. Tente de novo em instantes; se continuar, avise a equipe.";
        };
    }
}
