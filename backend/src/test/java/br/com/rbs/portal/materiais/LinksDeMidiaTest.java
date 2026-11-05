package br.com.rbs.portal.materiais;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * R4.3, R5.3.b e R5.3.c (rota 87): "Posts e links" junta os links da veiculação (carga da RBS) e os enviados como conteúdo, só com http(s)
 * válido. [ISOLAMENTO] [PERMISSAO] Um link inválido que esteja no banco nunca sai. Cada teste é desfeito no fim.
 */
@TesteDeIntegracao
@Transactional
class LinksDeMidiaTest extends ApoioDeMateriais {

    private String uri(long parceria) {
        return "/api/parcerias/" + parceria + "/links-de-midia";
    }

    private List<Map<String, Object>> itens(MvcTestResult resposta) {
        List<Map<String, Object>> itens = new java.util.ArrayList<>();
        lerLista(resposta, "$.itens").forEach(i -> itens.add(castMapa(i)));
        return itens;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMapa(Object valor) {
        return (Map<String, Object>) valor;
    }

    private long uma(String sql, Object... parametros) {
        var consulta = jdbc.sql(sql);
        for (Object p : parametros) {
            consulta = consulta.param(p);
        }
        return consulta.query(Long.class).single();
    }

    private long exibicaoDaMidiaDoRoxo() {
        return uma("""
                SELECT v.id FROM veiculacao v JOIN aproveitamento_previsto ap ON ap.id = v.aproveitamento_id
                WHERE ap.parceria_id = ? AND ap.categoria = 'MIDIA' AND v.link IS NULL ORDER BY v.id LIMIT 1
                """, roxo());
    }

    // ---------------------------------------------------------------------
    // Os links da amostra do Roxo
    // ---------------------------------------------------------------------

    @Test
    void osLinksDaAmostraDoRoxoSaemParaAMarcaEParaARbsComOrigemHostEData() {
        long esperados = uma("""
                SELECT count(*) FROM veiculacao v JOIN aproveitamento_previsto ap ON ap.id = v.aproveitamento_id
                WHERE ap.parceria_id = ? AND ap.categoria = 'MIDIA' AND v.link IS NOT NULL
                """, roxo());
        assertThat(esperados).as("a amostra grava link nas redes sociais do Roxo").isPositive();

        for (String email : List.of(ROXO, RBS)) {
            MvcTestResult resposta = entrar(email).get(uri(roxo()));

            assertThat(resposta).as(email).hasStatus(HttpStatus.OK);
            List<Map<String, Object>> itens = itens(resposta);
            assertThat(itens).hasSize((int) Math.min(esperados, ConteudoRepositorio.LIMITE_POR_ORIGEM));
            assertThat(itens).allSatisfy(i -> {
                assertThat(i.get("origem")).isEqualTo("CARGA");
                assertThat((String) i.get("url")).startsWith("https://");
                assertThat(i.get("host")).isIn("instagram.com", "tiktok.com");
                assertThat(i.get("veiculo")).isNotNull();
                assertThat(i.get("formato")).isNotNull();
                assertThat(i.get("data")).isNotNull();
            });
            assertThat(itens).anyMatch(i -> "instagram.com".equals(i.get("host"))).anyMatch(i -> "tiktok.com".equals(i.get("host")));
            List<String> datas = itens.stream().map(i -> (String) i.get("data")).toList();
            assertThat(datas).as("do mais recente para o mais antigo").isSortedAccordingTo(java.util.Comparator.<String>reverseOrder());
        }
    }

    // ---------------------------------------------------------------------
    // O link enviado como conteúdo
    // ---------------------------------------------------------------------

    @Test
    void oLinkEnviadoEmMidiaApareceComAEntregaQuemEnviouEADataEOTextoNao() {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "Post de abertura");
        SessaoDeApi rbs = entrar(RBS);
        String url = "https://www.tiktok.com/@planetaatlantida/video/7300000000000000009";
        assertThat(rbs.post("/api/entregas/" + entrega + "/conteudos", "{\"tipo\":\"LINK\",\"url\":\"" + url + "\",\"titulo\":\"Abertura\"}"))
                .hasStatus(HttpStatus.CREATED);
        assertThat(rbs.post("/api/entregas/" + entrega + "/conteudos", "{\"tipo\":\"TEXTO\",\"texto\":\"texto não é link\"}"))
                .hasStatus(HttpStatus.CREATED);

        for (String email : List.of(AMARELO, RBS)) {
            MvcTestResult resposta = entrar(email).get(uri(amarelo()));

            assertThat(resposta).as(email).hasStatus(HttpStatus.OK);
            List<Map<String, Object>> enviados = itens(resposta).stream().filter(i -> "ENVIO".equals(i.get("origem"))).toList();
            assertThat(enviados).hasSize(1);
            Map<String, Object> link = enviados.getFirst();
            assertThat(link.get("url")).isEqualTo(url);
            assertThat(link.get("host")).isEqualTo("tiktok.com");
            assertThat(link.get("titulo")).isEqualTo("Abertura");
            assertThat(((Number) link.get("entregaId")).longValue()).isEqualTo(entrega);
            assertThat(link.get("entregaTitulo")).isEqualTo("Post de abertura");
            assertThat(castMapa(link.get("enviadoPor")).get("nome")).isEqualTo("Equipe B2B RBS");
            assertThat((String) link.get("data")).endsWith("-03:00");
            assertThat(itens(resposta)).noneMatch(i -> String.valueOf(i.get("url")).contains("texto"));
        }
    }

    @Test
    void linkEnviadoEmEntregaDeOutraAbaNaoSaiPelaAbaMidia() {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "PERFIL", "Material do perfil");
        assertThat(entrar(RBS).post("/api/entregas/" + entrega + "/conteudos", "{\"tipo\":\"LINK\",\"url\":\"https://exemplo.com/perfil\"}"))
                .hasStatus(HttpStatus.CREATED);

        assertThat(itens(entrar(AMARELO).get(uri(amarelo())))).noneMatch(i -> "https://exemplo.com/perfil".equals(i.get("url")));
    }

    // ---------------------------------------------------------------------
    // Só http(s) válido
    // ---------------------------------------------------------------------

    @Test
    void javascriptEDataSaoRecusadosNoEnvio() {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "Post perigoso");
        SessaoDeApi marca = entrar(AMARELO);

        for (String url : List.of("javascript:alert(document.cookie)", "data:text/html,<script>alert(1)</script>")) {
            assertThat(marca.post("/api/entregas/" + entrega + "/conteudos", "{\"tipo\":\"LINK\",\"url\":\"" + url + "\"}"))
                    .as(url).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        assertThat(itens(marca.get(uri(amarelo())))).noneMatch(i -> String.valueOf(i.get("url")).startsWith("javascript:"));
    }

    @Test
    void linkInvalidoGravadoAForcaPorSqlNaoSai() {
        long exibicao = exibicaoDaMidiaDoRoxo();
        // veiculacao.link não tem CHECK: é o caso de um CSV com lixo. Todos estes devem ficar de fora.
        List<String> lixo = List.of("javascript:alert(1)", "data:text/html,oi", "ftp://exemplo.com/a", "https://", "https://usuario@exemplo.com/a",
                "https://exemplo.com/a b", "https://exemplo.com/‮fdp", "//exemplo.com/a", "HTTPS://EXEMPLO.COM/A");
        int antes = itens(entrar(ROXO).get(uri(roxo()))).size();

        for (String link : lixo) {
            jdbc.sql("UPDATE veiculacao SET link = :l WHERE id = :id").param("l", link).param("id", exibicao).update();

            MvcTestResult resposta = entrar(ROXO).get(uri(roxo()));
            assertThat(resposta).hasStatus(HttpStatus.OK);
            assertThat(itens(resposta)).as("link [" + link + "]").hasSize(antes).noneMatch(i -> link.equals(i.get("url")));
        }

        // Controle positivo: o mesmo caminho, com um endereço válido, passa a sair.
        jdbc.sql("UPDATE veiculacao SET link = 'https://www.instagram.com/p/CONTROLE/' WHERE id = :id").param("id", exibicao).update();
        assertThat(itens(entrar(ROXO).get(uri(roxo())))).hasSize(antes + 1)
                .anyMatch(i -> "https://www.instagram.com/p/CONTROLE/".equals(i.get("url")) && "instagram.com".equals(i.get("host")));
    }

    // ---------------------------------------------------------------------
    // Quem vê
    // ---------------------------------------------------------------------

    @Test
    void marcaDeOutraParceriaRecebe404EOsLinksDeUmaNaoVazamParaAOutra() {
        assertThat(entrar(AMARELO).get(uri(roxo()))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(entrar(ROXO).get(uri(amarelo()))).hasStatus(HttpStatus.NOT_FOUND);

        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "Post do Amarelo");
        entrar(RBS).post("/api/entregas/" + entrega + "/conteudos", "{\"tipo\":\"LINK\",\"url\":\"https://exemplo.com/do-amarelo\"}");
        assertThat(itens(entrar(ROXO).get(uri(roxo())))).noneMatch(i -> "https://exemplo.com/do-amarelo".equals(i.get("url")));
    }

    @Test
    void abaMidiaFechadaOuOcultaDa403ParaAMarcaEARbsVe() {
        for (String coluna : List.of("liberada", "visivel")) {
            jdbc.sql("UPDATE parceria_aba SET " + coluna + " = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'MIDIA')")
                    .param("p", roxo()).update();

            MvcTestResult marca = entrar(ROXO).get(uri(roxo()));
            assertThat(marca).as(coluna).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(marca, "$.detail")).isEqualTo("Esta aba ainda não foi liberada para a sua parceria.");
            assertThat(entrar(RBS).get(uri(roxo()))).as(coluna).hasStatus(HttpStatus.OK);

            jdbc.sql("UPDATE parceria_aba SET " + coluna + " = TRUE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'MIDIA')")
                    .param("p", roxo()).update();
        }
    }

    @Test
    void semSessaoRecebe401() {
        assertThat(SessaoDeApi.anonima(mvc).get(uri(roxo()))).hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
