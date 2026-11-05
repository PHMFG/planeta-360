package br.com.rbs.portal.relatorio;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.comum.ApoioDeDireitos;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * R8.1 e R5.3.d (rotas 96 a 99): o anexo "Pós-venda da edição" do relatório. [RELATORIO] A RBS publica uma versão por parceria (a nova
 * substitui a anterior, retirar não apaga); a marca só a vê com o relatório DISPONIBILIZADO; o relatório em si (6 seções, 17 indicadores)
 * não muda com ou sem anexo (E2.6). [PERMISSAO] [ISOLAMENTO] Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class PosVendaTest extends ApoioDeDireitos {

    private static final String ANEXO = "/api/parcerias/%d/posvenda";
    private static final String TEXTO = "Trinta anos do maior festival de música do Sul.";
    private static final String CORPO = """
            {"texto":"%s",
             "links":[{"tipo":"AFTERMOVIE","titulo":"Aftermovie 2027","url":"https://exemplo.example/aftermovie"},
                      {"tipo":"CLIPPING","titulo":"Matéria no jornal","url":"https://exemplo.example/clipping?id=1"}],
             "audiencias":[{"veiculo":"ATL TV","metrica":"pessoas alcançadas","numero":1600000,"unidade":"pessoas"},
                           {"veiculo":"Rádio Atlântida","metrica":"ouvintes","numero":1100000.5,"unidade":"pessoas"}]}
            """.formatted(TEXTO);

    private String uri(long parceria) {
        return ANEXO.formatted(parceria);
    }

    private MvcTestResult publicar(SessaoDeApi rbs, long parceria, String json) {
        return rbs.post(uri(parceria), json);
    }

    private long relatorioDaAmostra(long parceria) {
        return jdbc.sql("SELECT id FROM relatorio WHERE parceria_id = :p AND versao = 1").param("p", parceria).query(Long.class).single();
    }

    /** Valida e disponibiliza o relatório 1 da amostra pela rota 34, como a RBS faz. */
    private long disponibilizar(SessaoDeApi rbs, long parceria) {
        long relatorio = relatorioDaAmostra(parceria);
        assertThat(rbs.patch("/api/relatorios/" + relatorio + "/status", "{\"status\":\"VALIDADO\"}")).hasStatus(HttpStatus.OK);
        assertThat(rbs.patch("/api/relatorios/" + relatorio + "/status", "{\"status\":\"DISPONIBILIZADO\"}")).hasStatus(HttpStatus.OK);
        return relatorio;
    }

    private void veiculacaoComLink(long parceria, String exibidoEm, String link) {
        long aproveitamento = jdbc.sql("SELECT id FROM aproveitamento_previsto WHERE parceria_id = :p AND categoria = 'MIDIA' ORDER BY id LIMIT 1")
                .param("p", parceria).query(Long.class).single();
        jdbc.sql("INSERT INTO veiculacao (aproveitamento_id, exibido_em, quantidade, link) VALUES (:a, CAST(:em AS timestamptz), 1, :link)")
                .param("a", aproveitamento).param("em", exibidoEm).param("link", link).update();
    }

    // ---------------------------------------------------------------------
    // R8.1 · a RBS publica, a marca vê com o relatório disponibilizado e não vê sem ele
    // ---------------------------------------------------------------------

    @Test
    void rbsPublicaEAMarcaVeSoComORelatorioDisponibilizado() {
        SessaoDeApi rbs = entrar(RBS);
        SessaoDeApi marca = entrar(AMARELO);
        long amarelo = amarelo();

        MvcTestResult publicado = publicar(rbs, amarelo, CORPO);
        assertThat(publicado).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(publicado, "$.versao")).isEqualTo(1);
        assertThat(lerJson(publicado, "$.situacao")).isEqualTo("PUBLICADO");
        assertThat(lerJson(publicado, "$.publicadoPor.perfil")).isEqualTo("ADMIN_RBS");

        // Sem o relatório disponibilizado a marca não vê o anexo: a frase é a mesma de "não há anexo".
        MvcTestResult antes = marca.get(uri(amarelo));
        assertThat(antes).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(antes, "$.detail")).isEqualTo("O pós-venda desta edição ainda não está disponível.");

        // A RBS vê sempre, com o aviso de que a marca ainda não o vê.
        MvcTestResult daRbs = rbs.get(uri(amarelo));
        assertThat(daRbs).hasStatus(HttpStatus.OK);
        assertThat(lerJson(daRbs, "$.relatorioDisponibilizado")).isEqualTo(false);
        assertThat(lerJson(daRbs, "$.anexo.texto")).isEqualTo(TEXTO);

        disponibilizar(rbs, amarelo);

        MvcTestResult depois = marca.get(uri(amarelo));
        assertThat(depois).hasStatus(HttpStatus.OK);
        assertThat(lerJson(depois, "$.relatorioDisponibilizado")).isEqualTo(true);
        assertThat(lerJson(depois, "$.anexo.versao")).isEqualTo(1);
        assertThat(lerJson(depois, "$.anexo.texto")).isEqualTo(TEXTO);
        assertThat(lerLista(depois, "$.anexo.links[*].tipo")).containsExactly("AFTERMOVIE", "CLIPPING");
        assertThat(lerLista(depois, "$.anexo.links[*].url")).containsExactly("https://exemplo.example/aftermovie", "https://exemplo.example/clipping?id=1");
        assertThat(lerLista(depois, "$.anexo.audiencias[*].veiculo")).containsExactly("ATL TV", "Rádio Atlântida");
        assertThat(lerLista(depois, "$.anexo.audiencias[*].numero")).containsExactly(1600000, 1100000.5);
        assertThat(lerJson(depois, "$.anexo.publicadoPor")).as("quem publicou é dado interno da RBS").isNull();
        assertThat(corpo(depois)).doesNotContain("b2b@rbs.example").doesNotContain("retirado");
    }

    @Test
    void relatorioDisponibilizadoSemAnexoDa404AMarcaEAnexoNuloARbs() {
        SessaoDeApi rbs = entrar(RBS);
        SessaoDeApi marca = entrar(AMARELO);
        long amarelo = amarelo();
        disponibilizar(rbs, amarelo);

        assertThat(marca.get(uri(amarelo))).hasStatus(HttpStatus.NOT_FOUND);
        MvcTestResult daRbs = rbs.get(uri(amarelo));
        assertThat(daRbs).hasStatus(HttpStatus.OK);
        assertThat(lerJson(daRbs, "$.anexo")).isNull();
        assertThat(lerJson(daRbs, "$.relatorioDisponibilizado")).isEqualTo(true);
    }

    @Test
    void umaVersaoNovaSubstituiAAnteriorERetirarNaoApaga() {
        SessaoDeApi rbs = entrar(RBS);
        SessaoDeApi marca = entrar(AMARELO);
        long amarelo = amarelo();
        disponibilizar(rbs, amarelo);

        assertThat(publicar(rbs, amarelo, "{\"texto\":\"Primeira versão.\"}")).hasStatus(HttpStatus.CREATED);
        MvcTestResult segunda = publicar(rbs, amarelo, "{\"texto\":\"Segunda versão.\"}");
        assertThat(segunda).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(segunda, "$.versao")).isEqualTo(2);
        assertThat(lerJson(marca.get(uri(amarelo)), "$.anexo.texto")).isEqualTo("Segunda versão.");

        long idDaSegunda = ((Number) lerJson(segunda, "$.id")).longValue();
        assertThat(rbs.delete("/api/posvenda/" + idDaSegunda)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(rbs.delete("/api/posvenda/" + idDaSegunda)).as("retirar de novo também é 204").hasStatus(HttpStatus.NO_CONTENT);

        // Retirar não apaga: a linha continua, com quem e quando, e a marca deixa de ver o anexo (não volta para a versão 1).
        assertThat(contar("SELECT count(*) FROM evento_posvenda WHERE parceria_id = ?", amarelo)).isEqualTo(2);
        assertThat(contar("SELECT count(*) FROM evento_posvenda WHERE id = ? AND situacao = 'RETIRADO' AND retirado_em IS NOT NULL AND retirado_por IS NOT NULL",
                idDaSegunda)).isEqualTo(1);
        assertThat(marca.get(uri(amarelo))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(rbs.get(uri(amarelo)), "$.anexo")).isNull();

        MvcTestResult lista = rbs.get("/api/eventos/" + eventoDe(amarelo) + "/posvenda");
        assertThat(lista).hasStatus(HttpStatus.OK);
        assertThat(lerLista(lista, "$[?(@.parceriaId==" + amarelo + ")].versao")).containsExactly(2, 1);
        assertThat(lerLista(lista, "$[?(@.parceriaId==" + amarelo + ")].situacao")).containsExactly("RETIRADO", "PUBLICADO");
        assertThat(lerLista(lista, "$[?(@.id==" + idDaSegunda + ")].retiradoPor.perfil")).containsExactly("ADMIN_RBS");
    }

    @Test
    void retirarUmIdQueNaoExisteDa404() {
        assertThat(entrar(RBS).delete("/api/posvenda/999999999")).hasStatus(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------------
    // R5.3.d · os links de posts da parceria entram no anexo
    // ---------------------------------------------------------------------

    @Test
    void osLinksDePostsDaCargaEDoEnvioAparecemNoAnexoSoComHttp() {
        SessaoDeApi rbs = entrar(RBS);
        SessaoDeApi marca = entrar(AMARELO);
        long amarelo = amarelo();
        veiculacaoComLink(amarelo, "2030-01-01T12:00:00Z", "https://www.instagram.com/p/abc/");
        veiculacaoComLink(amarelo, "2030-01-02T12:00:00Z", "javascript:alert(1)");
        veiculacaoComLink(amarelo, "2030-01-03T12:00:00Z", "https://www.instagram.com@malicioso.example.invalid/p/x/");
        long entrega = ((Number) lerJson(rbs.post("/api/parcerias/" + amarelo + "/entregas", "{\"abaCodigo\":\"MIDIA\",\"titulo\":\"Post da marca\"}"),
                "$.id")).longValue();
        assertThat(marca.post("/api/entregas/" + entrega + "/conteudos",
                "{\"tipo\":\"LINK\",\"url\":\"https://www.tiktok.com/@planeta/video/1\",\"titulo\":\"Vídeo da marca\"}")).hasStatus(HttpStatus.CREATED);
        publicar(rbs, amarelo, CORPO);
        disponibilizar(rbs, amarelo);

        MvcTestResult resposta = marca.get(uri(amarelo));

        assertThat(resposta).hasStatus(HttpStatus.OK);
        List<String> links = lerLista(resposta, "$.linksDePosts[*].url");
        assertThat(links).contains("https://www.instagram.com/p/abc/", "https://www.tiktok.com/@planeta/video/1");
        assertThat(links).as("só http e https, sem usuario@ antes do host").noneMatch(l -> l.startsWith("javascript:") || l.contains("malicioso"));
        assertThat(lerLista(resposta, "$.linksDePosts[?(@.url=='https://www.instagram.com/p/abc/')].origem")).containsExactly("CARGA");
        assertThat(lerLista(resposta, "$.linksDePosts[?(@.url=='https://www.instagram.com/p/abc/')].host")).containsExactly("instagram.com");
        assertThat(lerLista(resposta, "$.linksDePosts[?(@.origem=='ENVIO')].titulo")).containsExactly("Vídeo da marca");
        assertThat(corpo(resposta)).as("o anexo não repete quem enviou").doesNotContain("enviadoPor");
    }

    @Test
    void osLinksDePostsDeOutraParceriaNaoVazam() {
        SessaoDeApi rbs = entrar(RBS);
        SessaoDeApi marca = entrar(AMARELO);
        long amarelo = amarelo();
        veiculacaoComLink(roxo(), "2030-02-01T12:00:00Z", "https://exemplo.example/so-do-roxo");
        publicar(rbs, amarelo, CORPO);
        disponibilizar(rbs, amarelo);

        assertThat(corpo(marca.get(uri(amarelo)))).doesNotContain("so-do-roxo");
    }

    // ---------------------------------------------------------------------
    // E2.6 · o relatório sem anexo continua igual, e com anexo também
    // ---------------------------------------------------------------------

    @Test
    void oRelatorioContinuaIgualSemEComAnexoComAsSeisSecoesEOs17Indicadores() {
        SessaoDeApi rbs = entrar(RBS);
        SessaoDeApi marca = entrar(AMARELO);
        long amarelo = amarelo();
        disponibilizar(rbs, amarelo);
        String sem = corpo(marca.get("/api/parcerias/" + amarelo + "/relatorio"));
        assertThat(lerLista(marca.get("/api/parcerias/" + amarelo + "/relatorio"), "$.secoes")).hasSize(6);
        assertThat(lerLista(marca.get("/api/parcerias/" + amarelo + "/relatorio"), "$.secoes[*].indicadores[*].codigo").stream().distinct())
                .hasSize(17);

        publicar(rbs, amarelo, CORPO);

        assertThat(corpo(marca.get("/api/parcerias/" + amarelo + "/relatorio"))).as("a rota 35 não muda com o anexo").isEqualTo(sem);
        assertThat(corpo(marca.get("/api/parcerias/" + amarelo + "/relatorio"))).doesNotContain(TEXTO);
        assertThat(contar("SELECT count(*) FROM indicador")).as("o catálogo continua com 17").isEqualTo(17);
    }

    // ---------------------------------------------------------------------
    // Permissão e isolamento
    // ---------------------------------------------------------------------

    @Test
    void marcaNasRotasDaRbsRecebe403AntesDoCorpo() {
        SessaoDeApi marca = entrar(AMARELO);
        long amarelo = amarelo();
        long evento = eventoDe(amarelo);
        String frase = "Esta operação é restrita à equipe da RBS.";

        for (String corpo : List.of("", "{}", "isto nao e json", CORPO)) {
            MvcTestResult resposta = marca.post(uri(amarelo), corpo);
            assertThat(resposta).as("POST com corpo [" + corpo + "]").hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo(frase);
        }
        assertThat(marca.get("/api/eventos/" + evento + "/posvenda")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(marca.delete("/api/posvenda/1")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(marca.get("/api/eventos/" + evento + "/resultados")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(marca.put("/api/eventos/" + evento + "/resultados", "{}")).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void marcaDeOutraParceriaRecebe404NoAnexo() {
        SessaoDeApi rbs = entrar(RBS);
        long amarelo = amarelo();
        publicar(rbs, amarelo, CORPO);
        disponibilizar(rbs, amarelo);

        assertThat(entrar(ROXO).get(uri(amarelo))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(SessaoDeApi.anonima(mvc).get(uri(amarelo))).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void oAnexoDeUmaMarcaNaoApareceNaOutra() {
        SessaoDeApi rbs = entrar(RBS);
        publicar(rbs, amarelo(), "{\"texto\":\"Só do Amarelo.\"}");
        disponibilizar(rbs, amarelo());
        disponibilizar(rbs, roxo());

        MvcTestResult doRoxo = entrar(ROXO).get(uri(roxo()));

        assertThat(doRoxo).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(corpo(doRoxo)).doesNotContain("Só do Amarelo");
    }

    @Test
    void parceriaEncerradaRecusaPublicarERetirar() {
        SessaoDeApi rbs = entrar(RBS);
        long amarelo = amarelo();
        MvcTestResult publicado = publicar(rbs, amarelo, CORPO);
        long id = ((Number) lerJson(publicado, "$.id")).longValue();
        encerrar(amarelo);

        assertThat(publicar(rbs, amarelo, CORPO)).hasStatus(HttpStatus.CONFLICT);
        assertThat(rbs.delete("/api/posvenda/" + id)).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void historicoDoPosVendaFicaSemParceriaEAMarcaNaoOVe() {
        SessaoDeApi rbs = entrar(RBS);
        SessaoDeApi marca = entrar(AMARELO);
        long amarelo = amarelo();
        MvcTestResult publicado = publicar(rbs, amarelo, CORPO);
        long id = ((Number) lerJson(publicado, "$.id")).longValue();
        rbs.delete("/api/posvenda/" + id);

        assertThat(contar("SELECT count(*) FROM historico WHERE entidade = 'evento_posvenda' AND entidade_id = ? AND parceria_id IS NULL AND acao = 'CRIACAO'", id))
                .isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM historico WHERE entidade = 'evento_posvenda' AND entidade_id = ? AND parceria_id IS NULL AND acao = 'EXCLUSAO'", id))
                .isEqualTo(1);
        assertThat(corpo(marca.get("/api/parcerias/" + amarelo + "/historico"))).doesNotContain("evento_posvenda");
    }

    // ---------------------------------------------------------------------
    // Validação (422, todos os campos numa resposta)
    // ---------------------------------------------------------------------

    @Test
    void corposInvalidosDao422ComOCampoErrado() {
        SessaoDeApi rbs = entrar(RBS);
        long amarelo = amarelo();

        assertThat(lerJson(publicar(rbs, amarelo, "{}"), "$.campos.texto")).isEqualTo("Informe o texto, ao menos um link ou ao menos uma audiência.");
        assertThat(publicar(rbs, amarelo, "{}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(publicar(rbs, amarelo, "{\"links\":[{\"tipo\":\"OUTRO\",\"titulo\":\"x\",\"url\":\"javascript:alert(1)\"}]}"), "$.campos['links[0].url']"))
                .asString().startsWith("O endereço precisa começar com http");
        assertThat(lerJson(publicar(rbs, amarelo, "{\"links\":[{\"tipo\":\"OUTRO\",\"titulo\":\"x\",\"url\":\"https://www.instagram.com@malicioso.example.invalid/\"}]}"),
                "$.campos['links[0].url']")).isNotNull();
        assertThat(lerJson(publicar(rbs, amarelo, "{\"links\":[{\"tipo\":\"OUTRO\",\"titulo\":\"x\",\"url\":\"https://exemplo.example/com espaco\"}]}"),
                "$.campos['links[0].url']")).isNotNull();
        assertThat(lerJson(publicar(rbs, amarelo, "{\"links\":[{\"tipo\":\"VIDEO\",\"titulo\":\"x\",\"url\":\"https://exemplo.example/\"}]}"),
                "$.campos['links[0].tipo']")).isEqualTo("Use AFTERMOVIE, CLIPPING ou OUTRO.");
        assertThat(lerJson(publicar(rbs, amarelo, "{\"links\":[{\"tipo\":\"OUTRO\",\"titulo\":\"\",\"url\":\"https://exemplo.example/\"}]}"),
                "$.campos['links[0].titulo']")).isEqualTo("Informe o título do link.");
        assertThat(lerJson(publicar(rbs, amarelo, "{\"audiencias\":[{\"veiculo\":\"ATL TV\",\"metrica\":\"pessoas\",\"numero\":-5,\"unidade\":\"pessoas\"}]}"),
                "$.campos['audiencias[0].numero']")).isEqualTo("O número tem de ser maior que zero.");
        assertThat(lerJson(publicar(rbs, amarelo, "{\"audiencias\":[{\"veiculo\":\"ATL TV\",\"metrica\":\"pessoas\",\"numero\":1.234,\"unidade\":\"pessoas\"}]}"),
                "$.campos['audiencias[0].numero']")).isEqualTo("O número tem no máximo 2 casas decimais.");
        assertThat(lerJson(publicar(rbs, amarelo, "{\"audiencias\":[{\"metrica\":\"pessoas\",\"numero\":5,\"unidade\":\"pessoas\"}]}"),
                "$.campos['audiencias[0].veiculo']")).isEqualTo("Informe o veículo.");
        assertThat(lerJson(publicar(rbs, amarelo, "{\"texto\":\"" + "a".repeat(5001) + "\"}"), "$.campos.texto")).asString().contains("5000");
        assertThat(contar("SELECT count(*) FROM evento_posvenda")).as("nada foi gravado").isZero();
    }

    @Test
    void textoComNulOuControleDa422NaoDa500() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult nul = publicar(rbs, amarelo(), "{\"texto\":\"antes\\u0000depois\"}");
        MvcTestResult sino = publicar(rbs, amarelo(), "{\"links\":[{\"tipo\":\"OUTRO\",\"titulo\":\"a\\u0007b\",\"url\":\"https://exemplo.example/\"}]}");

        assertThat(nul).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(nul, "$.campos.texto")).isEqualTo("O texto tem caracteres inválidos.");
        assertThat(sino).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(sino, "$.campos['links[0].titulo']")).isEqualTo("Há caracteres inválidos neste campo.");
    }

    @Test
    void maisDe30LinksOuDe20AudienciasDa422() {
        SessaoDeApi rbs = entrar(RBS);
        String link = "{\"tipo\":\"OUTRO\",\"titulo\":\"x\",\"url\":\"https://exemplo.example/\"}";
        String audiencia = "{\"veiculo\":\"ATL TV\",\"metrica\":\"pessoas\",\"numero\":5,\"unidade\":\"pessoas\"}";

        MvcTestResult links = publicar(rbs, amarelo(), "{\"links\":[" + String.join(",", java.util.Collections.nCopies(31, link)) + "]}");
        MvcTestResult audiencias = publicar(rbs, amarelo(), "{\"audiencias\":[" + String.join(",", java.util.Collections.nCopies(21, audiencia)) + "]}");

        assertThat(links).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(links, "$.campos.links")).isEqualTo("Use no máximo 30 links.");
        assertThat(audiencias).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(audiencias, "$.campos.audiencias")).isEqualTo("Use no máximo 20 audiências.");
    }

    @Test
    void parceriaQueNaoExisteDa404() {
        assertThat(publicar(entrar(RBS), 999999999L, CORPO)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(entrar(RBS).get("/api/eventos/999999999/posvenda")).hasStatus(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------------
    // O banco: só a retirada muda uma linha publicada
    // ---------------------------------------------------------------------

    @Test
    void oBancoRecusaUpdateEDeleteNoQueFoiPublicadoMasAceitaARetirada() {
        SessaoDeApi rbs = entrar(RBS);
        long amarelo = amarelo();
        long id = ((Number) lerJson(publicar(rbs, amarelo, CORPO), "$.id")).longValue();

        assertThatThrownBy(() -> jdbc.sql("UPDATE evento_posvenda SET texto = 'alterado' WHERE id = :id").param("id", id).update())
                .hasMessageContaining("não pode ser alterado nem apagado");
    }

    @Test
    void oBancoRecusaDeleteNoQueFoiPublicado() {
        SessaoDeApi rbs = entrar(RBS);
        long id = ((Number) lerJson(publicar(rbs, amarelo(), CORPO), "$.id")).longValue();

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM evento_posvenda WHERE id = :id").param("id", id).update())
                .hasMessageContaining("não pode ser alterado nem apagado");
    }
}
