package br.com.rbs.portal.banco;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.documentos.KitDeDocumentos;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * [ATLAS] A amostra de cortesias e credenciais segue o plano comercial de cada cota, o mesmo que o kit de documentos do Atlas traz
 * (o Manual de Operações diz que o plano comercial prevalece quando os dois divergem). Os números vêm do {@code gabarito.json} do
 * kit, que o {@code GabaritoDosDocumentosTest} confere contra o texto dos PDFs: Master (D03, o Amarelo), Subcota G (D04, o Roxo) e
 * Subcota (D05, o Verde). Antes, o portal dizia 4 cortesias na Arena e 2 no Camarote para o Roxo e para o Verde, 5 e 10 credenciais
 * na Arena, e o Camarote "pelo Portão 6 ou 4", contra 6, 3 e 8 (Roxo), 3, 1 e 12 (Verde) e o Portão 4 nos planos. A soma das
 * destinações nunca passa da cota (o gatilho {@code rn_cortesia_limite} confere) e nenhuma destinação se perde.
 */
@TesteDeIntegracao
class AmostraAlinhadaAosPlanosComerciaisTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    JdbcClient jdbc;

    /** O trecho do gabarito do fato (o {@code fato} é o nome dele, como {@code g-cortesias}). */
    private static String trecho(String fato) throws IOException {
        for (JsonNode f : JSON.readTree(new ClassPathResource(KitDeDocumentos.GABARITO).getInputStream())) {
            if (fato.equals(f.path("fato").asString())) {
                return f.path("trecho").asString();
            }
        }
        throw new AssertionError("o gabarito não tem o fato " + fato);
    }

    private static int[] numeros(String trecho, String padrao) {
        Matcher m = Pattern.compile(padrao).matcher(trecho);
        assertThat(m.find()).as(trecho + " ~ " + padrao).isTrue();
        int[] achados = new int[m.groupCount()];
        for (int i = 0; i < achados.length; i++) {
            achados[i] = Integer.parseInt(m.group(i + 1));
        }
        return achados;
    }

    private Map<String, Integer> cortesiasPorDia(String cliente, String dia) {
        Map<String, Integer> porSetor = new java.util.LinkedHashMap<>();
        jdbc.sql("SELECT setor, quantidade FROM cortesia_cota WHERE parceria_id = :p AND dia = :d ORDER BY id")
                .param("p", SessaoDeApi.parceriaDe2027(jdbc, cliente)).param("d", dia)
                .query((rs, n) -> porSetor.put(rs.getString("setor"), rs.getInt("quantidade"))).list();
        return porSetor;
    }

    private Map<String, Integer> credenciaisPorDia(String cliente) {
        Map<String, Integer> porSetorEBeneficiario = new java.util.LinkedHashMap<>();
        jdbc.sql("SELECT setor, beneficiario, quantidade_por_dia FROM credencial_cota WHERE parceria_id = :p ORDER BY id")
                .param("p", SessaoDeApi.parceriaDe2027(jdbc, cliente))
                .query((rs, n) -> porSetorEBeneficiario.put(rs.getString("setor") + "/" + rs.getString("beneficiario"), rs.getInt("quantidade_por_dia")))
                .list();
        return porSetorEBeneficiario;
    }

    @Test
    void oRoxoTemAsCortesiasEAsCredenciaisDaSubcotaG() throws IOException {
        int[] cortesias = numeros(trecho("g-cortesias"), "(\\d+) cortesias por dia na Arena e (\\d+) por dia no Camarote");
        int[] credenciais = numeros(trecho("g-credenciais"), "(\\d+) credenciais de serviço por dia");

        for (String dia : List.of("SEXTA", "SABADO")) {
            assertThat(cortesiasPorDia("Cliente Roxo", dia)).as("Roxo, " + dia).containsEntry("ARENA", cortesias[0])
                    .containsEntry("CAMAROTE", cortesias[1]).hasSize(2);
        }
        assertThat(credenciaisPorDia("Cliente Roxo")).containsEntry("ARENA/GERAL", credenciais[0]).hasSize(1);
        assertThat(cortesias).containsExactly(6, 3);
        assertThat(credenciais).containsExactly(8);
    }

    @Test
    void oVerdeTemAsCortesiasEAsCredenciaisDaSubcota() throws IOException {
        int[] cortesias = numeros(trecho("s-cortesias"), "(\\d+) cortesias por dia na Arena e (\\d+) por dia no Camarote");
        int[] credenciais = numeros(trecho("s-credenciais"), "(\\d+) credenciais de serviço por dia");

        for (String dia : List.of("SEXTA", "SABADO")) {
            assertThat(cortesiasPorDia("Cliente Verde", dia)).as("Verde, " + dia).containsEntry("ARENA", cortesias[0])
                    .containsEntry("CAMAROTE", cortesias[1]).hasSize(2);
        }
        assertThat(credenciaisPorDia("Cliente Verde")).containsEntry("ARENA/GERAL", credenciais[0]).hasSize(1);
        assertThat(cortesias).containsExactly(3, 1);
        assertThat(credenciais).containsExactly(12);
    }

    @Test
    void oAmareloContinuaComOsNumerosDaMaster() throws IOException {
        int[] cortesias = numeros(trecho("master-cortesias-camarote"), "(\\d+) cortesias por dia na Arena, (\\d+) no Camarote e (\\d+) no Lounge");
        int[] arena = numeros(trecho("master-credenciais-arena"), "(\\d+) credenciais de serviço por dia para a marca e (\\d+) para a agência");
        int[] conteudo = numeros(trecho("master-credenciais-conteudo"), "recebe (\\d+) credenciais por dia");

        for (String dia : List.of("SEXTA", "SABADO")) {
            assertThat(cortesiasPorDia("Cliente Amarelo", dia)).as("Amarelo, " + dia).containsEntry("ARENA", cortesias[0])
                    .containsEntry("CAMAROTE", cortesias[1]).containsEntry("LOUNGE", cortesias[2]);
        }
        assertThat(credenciaisPorDia("Cliente Amarelo")).containsEntry("ARENA/MARCA", arena[0]).containsEntry("ARENA/AGENCIA", arena[1])
                .containsEntry("SALA_CONTEUDO/EQUIPE_CONTEUDO", conteudo[0]);
    }

    @Test
    void aArenaEntraPeloPortao6EOCamaroteEOLoungePeloPortao4ComoOGabaritoDiz() throws IOException {
        assertThat(trecho("portao-camarote")).contains("Arena é acessada pelo Portão 6").contains("Camarote e o Lounge, pelo Portão 4");

        List<Map<String, Object>> linhas = jdbc.sql("SELECT setor, acesso FROM cortesia_cota").query().listOfRows();

        assertThat(linhas).isNotEmpty();
        for (Map<String, Object> linha : linhas) {
            String acesso = String.valueOf(linha.get("acesso"));
            String portoes = acesso.replaceAll("\\D", "");
            if ("ARENA".equals(linha.get("setor"))) {
                assertThat(portoes).as("Arena: " + acesso).isEqualTo("6");
            } else {
                assertThat(portoes).as(linha.get("setor") + ": " + acesso).isNotEmpty().matches("4+");
            }
        }
    }

    @Test
    void nenhumaDestinacaoPassaDaCotaENenhumaSePerdeu() {
        List<Map<String, Object>> passaram = jdbc.sql("""
                SELECT c.id FROM cortesia_cota c JOIN cortesia_destinacao d ON d.cortesia_cota_id = c.id
                GROUP BY c.id, c.quantidade HAVING sum(d.quantidade) > c.quantidade
                """).query().listOfRows();

        assertThat(passaram).isEmpty();
        // 13 destinações: o Amarelo 6 (Arena, Camarote e Lounge nos dois dias), o Roxo 4 e o Verde 3 (sexta e sábado na Arena, sexta no Camarote)
        assertThat(jdbc.sql("SELECT count(*) FROM cortesia_destinacao").query(Long.class).single()).isEqualTo(13L);
        assertThat(jdbc.sql("""
                SELECT sum(d.quantidade) FROM cortesia_destinacao d JOIN cortesia_cota c ON c.id = d.cortesia_cota_id
                WHERE c.parceria_id = :p AND c.setor = 'ARENA' AND c.dia = 'SEXTA'
                """).param("p", SessaoDeApi.parceriaDe2027(jdbc, "Cliente Verde")).query(Long.class).single()).as("a Verde usa 3 de 3 na Arena de sexta")
                .isEqualTo(3L);
        assertThat(jdbc.sql("SELECT sum(quantidade) FROM cortesia_cota WHERE parceria_id = :p").param("p", SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"))
                .query(Long.class).single()).as("o Amarelo, que não mudou: 150 disponíveis").isEqualTo(150L);
    }
}
