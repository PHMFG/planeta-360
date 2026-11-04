package br.com.rbs.portal.comum;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.util.UriUtils;

/**
 * Transforma qualquer erro da API em ProblemDetail (application/problem+json), em português. Atende o item 3 da Entrega 2.
 * Mapa: 401 sem sessão, 403 sem permissão, 404 não existe OU é de outra parceria, 409 regra do banco (SQLSTATE 23),
 * 422 validação com "campos", 500 erro inesperado. Nunca devolve mensagem crua do PostgreSQL nem rastro de pilha.
 */
@RestControllerAdvice
public class TratadorDeErros extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(TratadorDeErros.class);

    /**
     * Regras do banco que NÃO são gatilhos nossos (únicos, checks e chaves estrangeiras) e a frase que o usuário deve ler.
     * Os gatilhos "rn_*" já trazem a frase pronta em português e passam direto. Cada etapa acrescenta aqui o que precisar.
     */
    private static final Map<String, String> REGRAS_CONHECIDAS = Map.ofEntries(
            Map.entry("uq_parceria", "Este patrocinador já tem parceria nesta edição do evento."),
            Map.entry("uq_email_autorizado", "Este e-mail já está autorizado nesta parceria."),
            Map.entry("uq_usuario_email", "Já existe um usuário com este e-mail."),
            Map.entry("uq_patrocinador_nome", "Já existe um patrocinador com este nome."),
            Map.entry("fk_parceria_usuario_so_admin_patrocinador", "Só usuários do perfil Admin Patrocinador podem ser vinculados a uma parceria."),
            Map.entry("uq_documento_grupo_versao", "Já existe um documento com esta chave e esta versão neste escopo. Envie de novo."),
            Map.entry("uq_documento_arquivo", "Este arquivo já pertence a outro documento."),
            Map.entry("fk_documento_aprovador", "Só a equipe da RBS aprova um documento."));

    // ---------------------------------------------------------------------
    // Erros do próprio portal
    // ---------------------------------------------------------------------

    @ExceptionHandler(NaoAutenticadoException.class)
    ResponseEntity<Object> naoAutenticado(NaoAutenticadoException ex, WebRequest request) {
        return resposta(Problemas.de(HttpStatus.UNAUTHORIZED, ex.getMessage()), request);
    }

    @ExceptionHandler(SemPermissaoException.class)
    ResponseEntity<Object> semPermissao(SemPermissaoException ex, WebRequest request) {
        return resposta(Problemas.de(HttpStatus.FORBIDDEN, ex.getMessage()), request);
    }

    /** [ISOLAMENTO] Mesmo texto para "não existe" e "é de outra parceria". */
    @ExceptionHandler(NaoEncontradoException.class)
    ResponseEntity<Object> naoEncontrado(NaoEncontradoException ex, WebRequest request) {
        return resposta(Problemas.de(HttpStatus.NOT_FOUND, ex.getMessage()), request);
    }

    // Se algum código lançar as exceções do Spring Security dentro de um controller, a resposta é a mesma dos filtros.
    // Sem estes dois métodos o tratador genérico as transformaria em 500.
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> acessoNegado(AccessDeniedException ex, WebRequest request) {
        return resposta(Problemas.padrao(HttpStatus.FORBIDDEN), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> autenticacao(AuthenticationException ex, WebRequest request) {
        return resposta(Problemas.padrao(HttpStatus.UNAUTHORIZED), request);
    }

    /** [VALIDACAO] Regra de negócio que a API conferiu e recusou, com o campo errado em "campos" (etapa 4). */
    @ExceptionHandler(DadosInvalidosException.class)
    ResponseEntity<Object> dadosInvalidos(DadosInvalidosException ex, WebRequest request) {
        ProblemDetail problema = Problemas.de(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problema.setProperty("campos", ex.campos());
        return resposta(problema, request);
    }

    /** Estado atual que não deixa a operação (duplicado, já revogado...), conferido antes de gravar (etapa 4). */
    @ExceptionHandler(ConflitoException.class)
    ResponseEntity<Object> conflito(ConflitoException ex, WebRequest request) {
        return resposta(Problemas.de(HttpStatus.CONFLICT, ex.getMessage()), request);
    }

    /** Uma parte de que a operação depende está fora do ar (o modelo de linguagem do Atlas): 503 com a frase do que fazer no lugar. */
    @ExceptionHandler(ServicoIndisponivelException.class)
    ResponseEntity<Object> servicoIndisponivel(ServicoIndisponivelException ex, WebRequest request) {
        return resposta(Problemas.de(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage()), request);
    }

    /** Um limite de uso foi atingido (o teto diário do Atlas por parceria): 429 com a frase do que fazer no lugar. */
    @ExceptionHandler(LimiteDeUsoException.class)
    ResponseEntity<Object> limiteDeUso(LimiteDeUsoException ex, WebRequest request) {
        return resposta(Problemas.de(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage()), request);
    }

    /** [UPLOAD] Arquivo recusado pelo Armazenamento: 413 (acima do limite) ou 415 (extensão ou conteúdo fora da lista). */
    @ExceptionHandler(ArquivoRecusadoException.class)
    ResponseEntity<Object> arquivoRecusado(ArquivoRecusadoException ex, WebRequest request) {
        return resposta(Problemas.de(ex.status(), ex.getMessage()), request);
    }

    // ---------------------------------------------------------------------
    // [VALIDACAO] Corpo ou parâmetros inválidos
    // ---------------------------------------------------------------------

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        Map<String, String> campos = new LinkedHashMap<>();
        for (FieldError erro : ex.getBindingResult().getFieldErrors()) {
            campos.putIfAbsent(erro.getField(), mensagem(erro.getDefaultMessage()));
        }
        return createResponseEntity(Problemas.validacao(campos), headers, HttpStatus.UNPROCESSABLE_CONTENT, request);
    }

    /** Validação de parâmetros soltos (por exemplo ?aba= com @Pattern) e de objetos validados dentro do método. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        Map<String, String> campos = new LinkedHashMap<>();
        ex.getParameterValidationResults().forEach(resultado -> {
            if (resultado instanceof ParameterErrors erros) {
                erros.getFieldErrors().forEach(e -> campos.putIfAbsent(e.getField(), mensagem(e.getDefaultMessage())));
            } else {
                String nome = resultado.getMethodParameter().getParameterName();
                String texto = resultado.getResolvableErrors().stream().findFirst()
                        .map(e -> e.getDefaultMessage()).orElse(null);
                campos.putIfAbsent(nome != null ? nome : "parametro", mensagem(texto));
            }
        });
        return createResponseEntity(Problemas.validacao(campos), headers, HttpStatus.UNPROCESSABLE_CONTENT, request);
    }

    /**
     * Valor que não vira o tipo do parâmetro. Antes saía o 400 cru do Spring, em inglês ("Failed to convert 'id' with value:
     * 'abc'"). [ISOLAMENTO] Id da URL que não é número é um id que não existe: 404, com a frase de "não encontrado" da rota
     * (a mesma de um id numérico sem registro). Filtro da URL com o tipo errado (?pagina=abc, ?eventoId=abc) segue 400, como
     * o contrato já dizia para o painel, mas com a frase em português dizendo qual parâmetro está errado.
     */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status,
                                                        WebRequest request) {
        if (ex instanceof MethodArgumentTypeMismatchException deArgumento) {
            if (deArgumento.getParameter().hasParameterAnnotation(PathVariable.class)) {
                String frase = fraseDeNaoEncontrado(request, deArgumento.getValue());
                return createResponseEntity(Problemas.de(HttpStatus.NOT_FOUND, frase), headers, HttpStatus.NOT_FOUND, request);
            }
            String frase = "O parâmetro " + deArgumento.getName() + " " + exigenciaDoTipo(deArgumento.getRequiredType());
            return createResponseEntity(Problemas.de(HttpStatus.BAD_REQUEST, frase), headers, HttpStatus.BAD_REQUEST, request);
        }
        return createResponseEntity(Problemas.padrao(HttpStatus.BAD_REQUEST), headers, HttpStatus.BAD_REQUEST, request);
    }

    /** A frase de 404 do recurso, pelo trecho da URL logo antes do valor (/parcerias/abc, /arquivos/abc...). */
    private static String fraseDeNaoEncontrado(WebRequest request, Object valor) {
        if (request instanceof ServletWebRequest servlet && valor != null) {
            String[] trechos = servlet.getRequest().getRequestURI().split("/");
            for (int i = 1; i < trechos.length; i++) {
                if (UriUtils.decode(trechos[i], StandardCharsets.UTF_8).equals(valor.toString())) {
                    return switch (trechos[i - 1]) {
                        case "parcerias" -> "Não encontramos esta parceria.";
                        case "emails" -> "Não encontramos este e-mail nesta parceria.";
                        case "entregas" -> "Não encontramos esta entrega.";
                        case "arquivos" -> "Não encontramos este arquivo.";
                        case "perguntas" -> "Não encontramos esta pergunta.";
                        case "relatorios" -> "Não encontramos este relatório.";
                        case "cargas" -> "Não encontramos esta carga.";
                        case "aplicacoes" -> "Não encontramos esta aplicação.";
                        case "conversas" -> "Não encontramos esta conversa.";
                        case "solicitacoes" -> "Não encontramos esta solicitação.";
                        // O id de um trecho na URL (rota 60) não é número: a mesma frase do documento, para o 404 não dizer qual dos dois falhou.
                        case "documentos", "trechos" -> "Não encontramos este documento.";
                        default -> Problemas.detalhePadrao(HttpStatus.NOT_FOUND);
                    };
                }
            }
        }
        return Problemas.detalhePadrao(HttpStatus.NOT_FOUND);
    }

    /** O fim da frase do 400 de parâmetro: o que o parâmetro precisa ser, pelo tipo que o controller espera. */
    private static String exigenciaDoTipo(Class<?> tipo) {
        if (tipo != null && (Number.class.isAssignableFrom(tipo) || tipo == int.class || tipo == long.class || tipo == short.class)) {
            return "precisa ser um número inteiro.";
        }
        if (tipo != null && (tipo == Boolean.class || tipo == boolean.class)) {
            return "precisa ser true ou false.";
        }
        return "tem um valor inválido.";
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        return createResponseEntity(
                Problemas.de(HttpStatus.BAD_REQUEST, "O corpo da requisição está vazio ou não é um JSON válido."),
                headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
                                                                     HttpHeaders headers, HttpStatusCode status,
                                                                     WebRequest request) {
        return createResponseEntity(Problemas.padrao(HttpStatus.UNSUPPORTED_MEDIA_TYPE), headers,
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, request);
    }

    /** [UPLOAD] Arquivo acima do limite do application.yml (20 MB). */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        return createResponseEntity(
                Problemas.de(HttpStatus.CONTENT_TOO_LARGE, "O arquivo passa do limite de 20 MB. Envie um arquivo menor."),
                headers, HttpStatus.CONTENT_TOO_LARGE, request);
    }

    /** Os demais erros do Spring MVC (rota inexistente, método errado...) saem com título e texto em português. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object corpo, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        Object corpoEmPortugues = corpo != null ? corpo : Problemas.padrao(status);
        return super.handleExceptionInternal(ex, corpoEmPortugues, headers, status, request);
    }

    // ---------------------------------------------------------------------
    // Banco de dados e erros inesperados
    // ---------------------------------------------------------------------

    /**
     * Qualquer outro erro. Se por baixo houver uma recusa do banco (SQLSTATE da classe 23), vira 409 com a frase da regra;
     * o resto vira 500 com texto genérico, e o detalhe fica só no log do servidor.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> inesperado(Exception ex, WebRequest request) {
        SQLException violacao = procurarViolacaoDeRegra(ex);
        if (violacao != null) {
            // A mensagem do PostgreSQL traz os VALORES da linha (nome, e-mail...): ela nunca vai ao log, só o nome da restrição.
            log.info("Regra do banco recusou a operação (SQLSTATE {}, restrição {})", violacao.getSQLState(), nomeDaRestricao(violacao));
            return resposta(Problemas.de(HttpStatus.CONFLICT, frase(violacao)), request);
        }
        log.error("Erro inesperado ao tratar a requisição", ex);
        return resposta(Problemas.padrao(HttpStatus.INTERNAL_SERVER_ERROR), request);
    }

    /** O nome da restrição violada, sem valor nenhum; "desconhecida" quando o driver não o informa. */
    private static String nomeDaRestricao(SQLException violacao) {
        if (violacao instanceof PSQLException psql && psql.getServerErrorMessage() != null
                && psql.getServerErrorMessage().getConstraint() != null) {
            return psql.getServerErrorMessage().getConstraint();
        }
        return "desconhecida";
    }

    /** Percorre as causas do erro atrás de um SQLException cujo SQLSTATE comece com 23 (violação de integridade). */
    private static SQLException procurarViolacaoDeRegra(Throwable erro) {
        for (Throwable atual = erro; atual != null; atual = atual.getCause()) {
            if (atual instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("23")) {
                return sql;
            }
            if (atual.getCause() == atual) {
                break;
            }
        }
        return null;
    }

    /**
     * A frase que o usuário lê. Gatilho "rn_*": a mensagem foi escrita por nós, em português, então passa direto.
     * Os outros casos nunca expõem o texto do PostgreSQL (que é em inglês e cita tabelas e colunas).
     */
    private static String frase(SQLException violacao) {
        if (violacao instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
            ServerErrorMessage servidor = psql.getServerErrorMessage();
            String regra = servidor.getConstraint();
            if (regra != null && regra.startsWith("rn_") && servidor.getMessage() != null) {
                return servidor.getMessage();
            }
            if (regra != null && REGRAS_CONHECIDAS.containsKey(regra)) {
                return REGRAS_CONHECIDAS.get(regra);
            }
        }
        return switch (violacao.getSQLState()) {
            case "23505" -> "Já existe um registro com estes dados.";
            case "23503" -> "Este registro depende de outro que não existe, ou ainda é usado por outros dados.";
            case "23502" -> "Falta um dado obrigatório.";
            case "23514" -> "Os dados enviados não respeitam uma regra do portal.";
            default -> "Esta operação conflita com uma regra do portal.";
        };
    }

    // ---------------------------------------------------------------------
    // Apoio
    // ---------------------------------------------------------------------

    private ResponseEntity<Object> resposta(ProblemDetail problema, WebRequest request) {
        return createResponseEntity(problema, new HttpHeaders(), HttpStatusCode.valueOf(problema.getStatus()), request);
    }

    private static String mensagem(String texto) {
        return texto != null ? texto : "Valor inválido.";
    }
}
