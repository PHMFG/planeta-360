package br.com.rbs.portal.banco;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * [R1.2] A amostra bate com os PDFs dos planos comerciais que a RBS enviou. O {@code planos-da-rbs.json} traz os números transcritos
 * à mão dos PDFs (investimento, base, tipo de cota, cortesias e credenciais por dia); este teste os compara com o banco e não depende
 * do kit de documentos do Atlas, de modo que, se o kit e a amostra errarem juntos, ele ainda acusa.
 */
@TesteDeIntegracao
class AmostraBateComOsPdfsDaRbsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    JdbcClient jdbc;

    private static JsonNode planos() throws IOException {
        return JSON.readTree(new ClassPathResource("dev/planos-da-rbs.json").getInputStream()).path("planos");
    }

    private static Map<String, Integer> mapa(JsonNode no) {
        Map<String, Integer> mapa = new LinkedHashMap<>();
        no.properties().forEach(e -> mapa.put(e.getKey(), e.getValue().asInt()));
        return mapa;
    }

    @Test
    void osTresPlanosSaoOsDosPdfs() throws IOException {
        JsonNode planos = planos();
        assertThat(planos.size()).isEqualTo(3);
        for (JsonNode plano : planos) {
            String cliente = plano.path("cliente").asString();
            long parceria = SessaoDeApi.parceriaDe2027(jdbc, cliente);

            Map<String, Object> parceriaNoBanco = jdbc.sql("""
                    SELECT ct.codigo AS cota, pa.valor_investimento AS valor, pa.base_valor AS base
                    FROM parceria pa JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id WHERE pa.id = :p
                    """).param("p", parceria).query().singleRow();
            assertThat(parceriaNoBanco.get("cota")).as(cliente + ": tipo de cota").isEqualTo(plano.path("cota").asString());
            assertThat((BigDecimal) parceriaNoBanco.get("valor")).as(cliente + ": investimento")
                    .isEqualByComparingTo(BigDecimal.valueOf(plano.path("investimento").asLong()));
            assertThat(parceriaNoBanco.get("base")).as(cliente + ": base do valor").isEqualTo(plano.path("base").asString());
        }
    }

    @Test
    void asCortesiasPorSetorEDiaSaoAsDosPdfs() throws IOException {
        for (JsonNode plano : planos()) {
            String cliente = plano.path("cliente").asString();
            long parceria = SessaoDeApi.parceriaDe2027(jdbc, cliente);
            Map<String, Integer> esperado = mapa(plano.path("cortesiasPorDia"));

            for (String dia : List.of("SEXTA", "SABADO")) {
                Map<String, Integer> noBanco = new LinkedHashMap<>();
                jdbc.sql("SELECT setor, quantidade FROM cortesia_cota WHERE parceria_id = :p AND dia = :d ORDER BY id")
                        .param("p", parceria).param("d", dia)
                        .query((rs, n) -> noBanco.put(rs.getString("setor"), rs.getInt("quantidade"))).list();
                assertThat(noBanco).as(cliente + ": cortesias de " + dia).containsExactlyInAnyOrderEntriesOf(esperado);
            }
        }
    }

    @Test
    void asCredenciaisPorSetorEBeneficiarioSaoAsDosPdfs() throws IOException {
        for (JsonNode plano : planos()) {
            String cliente = plano.path("cliente").asString();
            long parceria = SessaoDeApi.parceriaDe2027(jdbc, cliente);
            Map<String, Integer> noBanco = new LinkedHashMap<>();
            jdbc.sql("SELECT setor, beneficiario, quantidade_por_dia FROM credencial_cota WHERE parceria_id = :p ORDER BY id")
                    .param("p", parceria)
                    .query((rs, n) -> noBanco.put(rs.getString("setor") + "/" + rs.getString("beneficiario"), rs.getInt("quantidade_por_dia")))
                    .list();
            assertThat(noBanco).as(cliente + ": credenciais por dia").containsExactlyInAnyOrderEntriesOf(mapa(plano.path("credenciaisPorDia")));
        }
    }
}
