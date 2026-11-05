package br.com.rbs.portal.cortesias;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;

import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Ajudas dos testes da aba Cortesias (rotas 80 a 85): os usuários da amostra, os corpos em JSON e a leitura da tabela da resposta.
 * Números da amostra que os testes usam (R__amostra.sql): a Verde tem Arena e Camarote, sexta e sábado; Arena de sexta 3 de 3
 * destinadas, Arena de sábado 3 com 2 destinadas (1 disponível), Camarote de sexta 1 de 1 e Camarote de sábado 1 com 0 destinadas.
 * Não faz parte da API.
 */
final class ApoioDeCortesias {

    static final String RBS = "b2b@rbs.example";
    static final String AMARELO = "marketing@amarelo.example";
    static final String ROXO = "marketing@roxo.example";
    static final String VERDE = "marketing@verde.example";

    private ApoioDeCortesias() {
    }

    static String uri(long parceria) {
        return "/api/parcerias/" + parceria + "/cortesias";
    }

    static String uriDestinacoes(long parceria) {
        return uri(parceria) + "/destinacoes";
    }

    static String uriCotas(long parceria) {
        return uri(parceria) + "/cotas";
    }

    static long verde(JdbcClient jdbc) {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Verde");
    }

    static long amarelo(JdbcClient jdbc) {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    static long eventoDe(JdbcClient jdbc, long parceria) {
        return jdbc.sql("SELECT evento_id FROM parceria WHERE id = :id").param("id", parceria).query(Long.class).single();
    }

    /** O corpo da rota 83. */
    static String destinacao(String setor, String dia, Object quantidade, String destinatario) {
        return "{\"setor\":\"" + setor + "\",\"dia\":\"" + dia + "\",\"quantidade\":" + quantidade + ",\"destinatario\":"
                + (destinatario == null ? "null" : "\"" + destinatario + "\"") + "}";
    }

    /** Uma célula do corpo da rota 81. */
    static String celula(String setor, String dia, Object quantidade, boolean shuttle, String acesso) {
        return "{\"setor\":\"" + setor + "\",\"dia\":\"" + dia + "\",\"quantidade\":" + quantidade + ",\"comShuttle\":" + shuttle
                + ",\"acesso\":" + (acesso == null ? "null" : "\"" + acesso + "\"") + "}";
    }

    static String cotas(String... celulas) {
        return "{\"cotas\":[" + String.join(",", celulas) + "]}";
    }

    /** A linha da tabela (setor e dia) de uma resposta no formato da rota 80. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> linha(MvcTestResult resposta, String setor, String dia) {
        List<Map<String, Object>> achadas = (List<Map<String, Object>>) lerJson(resposta,
                "$.cotas[?(@.setor=='" + setor + "' && @.dia=='" + dia + "')]");
        return achadas.isEmpty() ? null : achadas.get(0);
    }

    /** O que o banco tem destinado numa cota da parceria (a soma das quantidades). */
    static long destinadasNoBanco(JdbcClient jdbc, long parceria, String setor, String dia) {
        return jdbc.sql("""
                        SELECT CAST(coalesce(sum(d.quantidade), 0) AS bigint) FROM cortesia_cota cc
                        LEFT JOIN cortesia_destinacao d ON d.cortesia_cota_id = cc.id
                        WHERE cc.parceria_id = :p AND cc.setor = :s AND cc.dia = :d
                        """).param("p", parceria).param("s", setor).param("d", dia).query(Long.class).single();
    }

    static long totalDeDestinacoes(JdbcClient jdbc, long parceria) {
        return jdbc.sql("""
                        SELECT count(*) FROM cortesia_destinacao d JOIN cortesia_cota cc ON cc.id = d.cortesia_cota_id
                        WHERE cc.parceria_id = :p
                        """).param("p", parceria).query(Long.class).single();
    }
}
