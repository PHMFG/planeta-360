package br.com.rbs.portal.atlas.ferramentas;

import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.comum.DadosInvalidosException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;

/**
 * A validação dos argumentos de uma ferramenta, em Java (o modo {@code strict} dos provedores é beta e não vale aqui): tipo, tamanho
 * e argumento desconhecido. [ATLAS] O JSON Schema que vai ao modelo só o orienta. Erro de argumento sobe como
 * {@link DadosInvalidosException} e volta ao modelo como mensagem de ferramenta. Toda ferramenta nova recusa argumento fora do
 * esquema com {@link #desconhecidos}: assim um {@code parceria_id} que o modelo invente nunca chega a lugar nenhum.
 */
public final class Argumentos {

    private Argumentos() {
    }

    /** Os argumentos como mapa. Texto vazio vale como nenhum argumento; o que não é um objeto JSON é erro. */
    public static Map<String, Object> ler(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> argumentos = AtlasJson.ler(json, new TypeReference<Map<String, Object>>() {
            });
            return argumentos == null ? new LinkedHashMap<>() : argumentos;
        } catch (JacksonException | ClassCastException formatoInvalido) {
            throw new DadosInvalidosException("Os argumentos da ferramenta não são um objeto JSON válido.", Map.of());
        }
    }

    /** Anota como erro cada argumento que o esquema da ferramenta não tem. */
    public static void desconhecidos(Map<String, Object> argumentos, Set<String> aceitos, Map<String, String> erros) {
        for (String nome : argumentos.keySet()) {
            if (!aceitos.contains(nome)) {
                erros.put(nome, "Argumento desconhecido: esta ferramenta não aceita " + nome + ".");
            }
        }
    }

    /** Um argumento de texto. Falta ou texto em branco é erro quando {@code obrigatorio}. */
    public static String texto(Map<String, Object> argumentos, String nome, boolean obrigatorio, Map<String, String> erros) {
        Object valor = argumentos.get(nome);
        if (valor == null) {
            if (obrigatorio) {
                erros.put(nome, "Informe " + nome + ".");
            }
            return null;
        }
        if (!(valor instanceof String texto)) {
            erros.put(nome, nome + " tem de ser um texto.");
            return null;
        }
        if (obrigatorio && texto.isBlank()) {
            erros.put(nome, "Informe " + nome + ".");
            return null;
        }
        return texto;
    }

    /** Um argumento booleano; falta vale como falso. Outro tipo é erro. */
    public static boolean booleano(Map<String, Object> argumentos, String nome, Map<String, String> erros) {
        Object valor = argumentos.get(nome);
        if (valor == null) {
            return false;
        }
        if (!(valor instanceof Boolean booleano)) {
            erros.put(nome, nome + " tem de ser verdadeiro ou falso.");
            return false;
        }
        return booleano;
    }

    /** Um argumento que é uma lista de textos. */
    public static List<String> lista(Map<String, Object> argumentos, String nome, Map<String, String> erros) {
        Object valor = argumentos.get(nome);
        if (!(valor instanceof List<?> itens)) {
            erros.put(nome, nome + " tem de ser uma lista de textos.");
            return List.of();
        }
        List<String> textos = new ArrayList<>();
        for (Object item : itens) {
            if (item instanceof String texto) {
                textos.add(texto);
            } else {
                erros.put(nome, nome + " tem de ser uma lista de textos.");
                return List.of();
            }
        }
        return textos;
    }

    /** Sobe o erro de argumentos, se houve algum. */
    public static void exigirSemErros(Map<String, String> erros) {
        if (!erros.isEmpty()) {
            throw new DadosInvalidosException("Os argumentos da ferramenta estão errados.", erros);
        }
    }
}
