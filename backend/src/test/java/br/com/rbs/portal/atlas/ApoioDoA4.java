package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.atlas.checagem.NormalizadorDeNumeros;
import br.com.rbs.portal.atlas.relatorio.Referencias;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.core.type.TypeReference;

/**
 * [ATLAS] [RELATORIO] As duas conferências do M6 sobre o relatório do Atlas (o "A4" que a marca imprime), sem o modelo de verdade:
 * <ul>
 *   <li>{@link #exigirTextoSemAlgarismoForaDeMarcador}: o texto de todo parágrafo guardado na especificação (o que o modelo escreveu
 *       e a frase-modelo) não tem algarismo, nem número ou data por extenso, fora de marcador {@code {{ref}}};</li>
 *   <li>{@link #exigirTodoNumeroEmFontesECalculos}: todo número que o A4 mostra (KPI, campo informado, parágrafo) está na seção
 *       "Fontes e cálculos", e toda lista (tabela, galeria) tem a fonte dela lá.</li>
 * </ul>
 * Não faz parte da API.
 */
final class ApoioDoA4 {

    /** Um número como o A4 o escreve: {@code 17}, {@code 1.200}, {@code 89,3} (o {@code %} fica de fora). */
    private static final Pattern NUMERO = Pattern.compile("\\d+(?:[.,]\\d+)*");

    private ApoioDoA4() {
    }

    // ---------------------------------------------------------------------
    // Nenhum algarismo fora de marcador
    // ---------------------------------------------------------------------

    /** Os textos de parágrafo da especificação guardada do relatório: {@code texto} (do modelo) e {@code frase_modelo}. */
    static List<String> textosDeParagrafo(JdbcClient jdbc, long relatorioId) {
        String json = jdbc.sql("SELECT CAST(especificacao AS text) FROM atlas_relatorio WHERE id = :id").param("id", relatorioId)
                .query(String.class).single();
        Map<String, Object> especificacao = AtlasJson.ler(json, new TypeReference<Map<String, Object>>() {
        });
        List<String> textos = new ArrayList<>();
        coletar(especificacao, textos);
        return textos;
    }

    @SuppressWarnings("unchecked")
    private static void coletar(Object no, List<String> textos) {
        if (no instanceof Map<?, ?> mapa) {
            if ("paragrafo".equals(mapa.get("tipo"))) {
                for (String chave : List.of("texto", "frase_modelo")) {
                    if (mapa.get(chave) instanceof String texto) {
                        textos.add(texto);
                    }
                }
            }
            ((Map<String, Object>) mapa).values().forEach(valor -> coletar(valor, textos));
        } else if (no instanceof List<?> lista) {
            lista.forEach(item -> coletar(item, textos));
        }
    }

    /** Falha se algum parágrafo guardado tem algarismo ou número por extenso fora de marcador. Devolve quantos textos conferiu. */
    static int exigirTextoSemAlgarismoForaDeMarcador(JdbcClient jdbc, long relatorioId) {
        List<String> textos = textosDeParagrafo(jdbc, relatorioId);
        assertThat(textos).as("o relatório tem parágrafos redigíveis").isNotEmpty();
        for (String texto : textos) {
            String foraDeMarcador = Referencias.semMarcadores(texto);
            assertThat(foraDeMarcador).as("algarismo fora de marcador em: " + texto).doesNotContainPattern("\\p{N}");
            assertThat(NormalizadorDeNumeros.extrair(foraDeMarcador, false)).as("número ou data por extenso em: " + texto).isEmpty();
            assertThat(foraDeMarcador).as("chave solta em: " + texto).doesNotContain("{").doesNotContain("}");
        }
        return textos.size();
    }

    // ---------------------------------------------------------------------
    // Todo número do A4 está em "Fontes e cálculos"
    // ---------------------------------------------------------------------

    /**
     * Falha se o relatório (a resposta da rota 47) mostra um número que a seção "Fontes e cálculos" não explica. Os números das
     * fontes são os de {@code valor} e os que a {@code origem} traz (a conta com os valores que entraram). Devolve quantos números
     * do A4 conferiu.
     */
    static int exigirTodoNumeroEmFontesECalculos(MvcTestResult relatorio) {
        assertThat(lerJson(relatorio, "$.status")).isEqualTo("PRONTO");
        List<Map<String, Object>> fontes = lerLista(relatorio, "$.secoes[?(@.id=='fontes')].blocos[0].itens[*]");
        assertThat(fontes).as("a seção Fontes e cálculos tem itens").isNotEmpty();
        Set<String> refsDasFontes = new LinkedHashSet<>();
        Set<String> numerosDasFontes = new LinkedHashSet<>();
        for (Map<String, Object> fonte : fontes) {
            refsDasFontes.add(String.valueOf(fonte.get("ref")));
            numerosDasFontes.addAll(numeros(fonte.get("valor")));
            numerosDasFontes.addAll(numeros(fonte.get("origem")));
        }

        int conferidos = 0;
        // cada KPI e cada campo informado: o valor é uma fonte e a referência dele também
        for (Map<String, Object> item : mapas(relatorio, "$.secoes[*].blocos[?(@.tipo=='kpi_grade')].itens[*]")) {
            if (item.get("valor") != null) {
                assertThat(refsDasFontes).as("fonte do KPI " + item.get("rotulo")).contains(String.valueOf(item.get("ref")));
                assertThat(numerosDasFontes).as("número do KPI " + item.get("rotulo")).containsAll(numeros(item.get("valor")));
                conferidos += numeros(item.get("valor")).size();
            }
        }
        for (Map<String, Object> campo : mapas(relatorio, "$.secoes[*].blocos[?(@.tipo=='campo_informado')]")) {
            assertThat(numerosDasFontes).as("número do campo " + campo.get("rotulo")).containsAll(numeros(campo.get("valor")));
            conferidos += numeros(campo.get("valor")).size();
        }
        // cada parágrafo: os números que aparecem no texto resolvido vêm de marcadores, e estão nas fontes
        for (Map<String, Object> paragrafo : mapas(relatorio, "$.secoes[*].blocos[?(@.tipo=='paragrafo')]")) {
            Set<String> doTexto = numeros(paragrafo.get("texto"));
            assertThat(numerosDasFontes).as("número do parágrafo " + paragrafo.get("id") + ": " + paragrafo.get("texto")).containsAll(doTexto);
            conferidos += doTexto.size();
        }
        // as listas: a tabela e a galeria só valem com a fonte delas
        for (Map<String, Object> tabela : mapas(relatorio, "$.secoes[*].blocos[?(@.tipo=='tabela')]")) {
            assertThat(refsDasFontes).as("fonte da tabela " + tabela.get("id")).contains(String.valueOf(tabela.get("ref")));
        }
        if (!lerLista(relatorio, "$.secoes[*].blocos[?(@.tipo=='galeria_comprovacoes')]").isEmpty()) {
            assertThat(refsDasFontes).as("fonte da galeria").contains("lista:comprovacoes");
        }
        return conferidos;
    }

    private static List<Map<String, Object>> mapas(MvcTestResult relatorio, String caminho) {
        return lerLista(relatorio, caminho);
    }

    /** Os números de um texto, no formato do A4, sem o {@code %}. */
    static Set<String> numeros(Object texto) {
        Set<String> numeros = new LinkedHashSet<>();
        if (texto != null) {
            Matcher achado = NUMERO.matcher(String.valueOf(texto));
            while (achado.find()) {
                numeros.add(achado.group());
            }
        }
        return numeros;
    }
}
