package br.com.rbs.portal.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ErrosHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> validacao(MethodArgumentNotValidException ex) {
        Map<String, String> campos = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(f -> campos.put(f.getField(), f.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("erro", "Campos inválidos", "campos", campos));
    }

    /** CHECKs e UNIQUEs do banco viram resposta clara em vez de erro 500. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, Object>> integridade(DataIntegrityViolationException ex) {
        String causa = ex.getMostSpecificCause().getMessage();
        String detalhe = causa == null ? "" : causa.split("\n")[0];
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("erro", "Violação de regra de integridade", "detalhe", detalhe));
    }
}
