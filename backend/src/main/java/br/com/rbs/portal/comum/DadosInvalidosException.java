package br.com.rbs.portal.comum;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dados que passaram pelo formato mas não pelas regras do portal (cota que não existe, valor sem base, código errado...):
 * o TratadorDeErros devolve 422 com a lista "campos". Atende o item 3 da Entrega 2. [VALIDACAO]
 * A frase de "detail" é a que o usuário lê; "campos" diz qual campo do corpo está errado (chave = nome do campo).
 */
public class DadosInvalidosException extends RuntimeException {

    private final Map<String, String> campos;

    public DadosInvalidosException(String detalhe, Map<String, String> campos) {
        super(detalhe);
        this.campos = new LinkedHashMap<>(campos);
    }

    /** Frase padrão do contrato: "Confira os campos destacados e tente de novo." */
    public DadosInvalidosException(Map<String, String> campos) {
        this("Confira os campos destacados e tente de novo.", campos);
    }

    public Map<String, String> campos() {
        return campos;
    }
}
