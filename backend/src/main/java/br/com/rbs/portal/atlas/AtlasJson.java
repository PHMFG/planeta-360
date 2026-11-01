package br.com.rbs.portal.atlas;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * O JSON que o Atlas guarda no banco (a especificação do relatório, o snapshot, os pedidos de ferramenta) e lê de volta. [ATLAS]
 * Um mapeador só, com uma regra a mais que o da API: propriedade desconhecida é erro. A especificação é um catálogo fechado
 * (docs/api.md, rota 47): um bloco com campo que o catálogo não conhece não pode entrar calado. A saída das rotas continua pelo
 * mapeador do Spring; este é só para o que vai e volta do banco.
 */
public final class AtlasJson {

    private static final JsonMapper MAPA = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private AtlasJson() {
    }

    /** O objeto como texto JSON. */
    public static String escrever(Object valor) {
        return MAPA.writeValueAsString(valor);
    }

    /** O texto JSON como o tipo dado; o erro de formato sobe como exceção sem marcação (JacksonException). */
    public static <T> T ler(String json, Class<T> tipo) {
        return MAPA.readValue(json, tipo);
    }

    public static <T> T ler(String json, TypeReference<T> tipo) {
        return MAPA.readValue(json, tipo);
    }
}
