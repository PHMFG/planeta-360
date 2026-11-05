package br.com.rbs.portal.parceria;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasRepositorio;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * R6.d3, a troca de cota depois do cadastro (rotas 102 e 104). Cada teste cria a própria parceria, então os números do Amarelo,
 * do Roxo e do Verde da amostra não mudam; e tudo é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class TrocaDeCotaTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AtlasRepositorio atlas;

    long criar(String cota) {
        long evento2027 = jdbc.sql("SELECT id FROM evento WHERE edicao = 2027").query(Long.class).single();
        MvcTestResult criada = SessaoDeApi.entrar(mvc, RBS).post("/api/parcerias",
                "{\"patrocinador\":{\"nome\":\"Marca da Troca\"},\"eventoId\":%d,\"cotaCodigo\":\"%s\"}".formatted(evento2027, cota));
        assertThat(criada).hasStatus(HttpStatus.CREATED);
        return ((Number) lerJson(criada, "$.id")).longValue();
    }

    String abasLiberadas(long parceriaId) {
        return jdbc.sql("""
                        SELECT string_agg(a.codigo, ',' ORDER BY a.ordem) FROM parceria_aba pab JOIN aba a ON a.id = pab.aba_id
                        WHERE pab.parceria_id = :p AND pab.liberada
                        """).param("p", parceriaId).query(String.class).single();
    }

    String cotaGravada(long parceriaId) {
        return jdbc.sql("SELECT ct.codigo FROM parceria pa JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id WHERE pa.id = :p")
                .param("p", parceriaId).query(String.class).single();
    }

    long contar(String sql, Object... parametros) {
        var consulta = jdbc.sql(sql);
        for (int i = 0; i < parametros.length; i++) {
            consulta = consulta.param(parametros[i]);
        }
        return consulta.query(Long.class).single();
    }

    void disponibilizarRelatorio(long parceriaId) {
        long rbs = jdbc.sql("SELECT id FROM usuario WHERE email = :e").param("e", RBS).query(Long.class).single();
        jdbc.sql("INSERT INTO relatorio (parceria_id, versao, gerado_por) VALUES (:p, 2, :u)").param("p", parceriaId).param("u", rbs).update();
        jdbc.sql("UPDATE relatorio SET status = 'VALIDADO', validado_por = :u, validado_em = now() WHERE parceria_id = :p")
                .param("u", rbs).param("p", parceriaId).update();
        jdbc.sql("UPDATE relatorio SET status = 'DISPONIBILIZADO', disponibilizado_por = :u, disponibilizado_em = now() WHERE parceria_id = :p")
                .param("u", rbs).param("p", parceriaId).update();
    }

    String trocaEm(long parceriaId) {
        return "/api/parcerias/" + parceriaId + "/cota";
    }

    // ---------------------------------------------------------------------
    // A prévia
    // ---------------------------------------------------------------------

    @Test
    void previaDeMasterParaSubcotaListaOQueFechaENadaGrava() {
        long parceria = criar("MASTER");
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);   // o login grava histórico: conta-se depois dele
        long historicoAntes = contar("SELECT count(*) FROM historico");
        String abasAntes = abasLiberadas(parceria);

        MvcTestResult previa = rbs.get(trocaEm(parceria) + "/previa?para=SUBCOTA");

        assertThat(previa).hasStatus(HttpStatus.OK);
        assertThat(lerJson(previa, "$.cotaAtual.codigo")).isEqualTo("MASTER");
        assertThat(lerJson(previa, "$.cotaNova.codigo")).isEqualTo("SUBCOTA");
        assertThat(lerJson(previa, "$.mudaACota")).isEqualTo(true);
        assertThat(lerLista(previa, "$.abasQueFecham[*].codigo")).containsExactly("PESQUISA");
        assertThat(lerLista(previa, "$.abasQuePassamAPoderAbrir")).isEmpty();
        assertThat(lerJson(previa, "$.pesquisa")).isEqualTo("DEIXA_DE_VALER");
        assertThat(lerJson(previa, "$.comprovacaoMensal")).isEqualTo("DEIXA_DE_VALER");
        assertThat(lerJson(previa, "$.relatorioDisponibilizado")).isNull();
        assertThat(lerJson(previa, "$.exigeCiencia")).isEqualTo(false);
        assertThat(lerLista(previa, "$.naoMudamSozinhos[*].aba")).containsExactly("CORTESIAS", "CREDENCIAMENTO", "PERFIL");
        assertThat(lerLista(previa, "$.avisos")).isNotEmpty();
        assertThat(abasLiberadas(parceria)).isEqualTo(abasAntes);
        assertThat(cotaGravada(parceria)).isEqualTo("MASTER");
        assertThat(contar("SELECT count(*) FROM historico")).as("a prévia não grava").isEqualTo(historicoAntes);
    }

    @Test
    void previaDeSubcotaParaMasterMostraAPesquisaQuePassaAPoderAbrirEORelatorioQueExigeCiencia() {
        long parceria = criar("SUBCOTA");
        disponibilizarRelatorio(parceria);

        MvcTestResult previa = SessaoDeApi.entrar(mvc, RBS).get(trocaEm(parceria) + "/previa?para=MASTER");

        assertThat(previa).hasStatus(HttpStatus.OK);
        assertThat(lerLista(previa, "$.abasQueFecham")).isEmpty();
        assertThat(lerLista(previa, "$.abasQuePassamAPoderAbrir[*].codigo")).containsExactly("PESQUISA");
        assertThat(lerJson(previa, "$.pesquisa")).isEqualTo("PASSA_A_VALER");
        assertThat(lerJson(previa, "$.comprovacaoMensal")).isEqualTo("PASSA_A_VALER");
        assertThat(lerJson(previa, "$.relatorioDisponibilizado.versao")).isEqualTo(2);
        assertThat(lerJson(previa, "$.exigeCiencia")).isEqualTo(true);
    }

    @Test
    void previaParaAMesmaCotaNaoMudaNada() {
        long parceria = criar("SUBCOTA_G");

        MvcTestResult previa = SessaoDeApi.entrar(mvc, RBS).get(trocaEm(parceria) + "/previa?para=SUBCOTA_G");

        assertThat(previa).hasStatus(HttpStatus.OK);
        assertThat(lerJson(previa, "$.mudaACota")).isEqualTo(false);
        assertThat(lerLista(previa, "$.abasQueFecham")).isEmpty();
    }

    @Test
    void previaSemCotaOuComCotaQueNaoExisteDa422() {
        long parceria = criar("MASTER");
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        for (String uri : List.of(trocaEm(parceria) + "/previa", trocaEm(parceria) + "/previa?para=PLATINA")) {
            MvcTestResult resposta = rbs.get(uri);
            assertThat(resposta).as(uri).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(lerJson(resposta, "$.campos.para")).isNotNull();
        }
    }

    // ---------------------------------------------------------------------
    // A troca
    // ---------------------------------------------------------------------

    @Test
    void masterParaSubcotaFechaAPesquisaEDeVoltaElaFicaFechadaAteARbsAbrir() {
        long parceria = criar("MASTER");
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        assertThat(abasLiberadas(parceria)).contains("PESQUISA");
        MvcTestResult previa = rbs.get(trocaEm(parceria) + "/previa?para=SUBCOTA");

        MvcTestResult troca = rbs.put(trocaEm(parceria), "{\"cotaCodigo\":\"SUBCOTA\"}");

        assertThat(troca).hasStatus(HttpStatus.OK);
        assertThat(cotaGravada(parceria)).isEqualTo("SUBCOTA");
        assertThat(lerJson(troca, "$.parceria.cota.codigo")).isEqualTo("SUBCOTA");
        assertThat(lerJson(troca, "$.parceria.cota.nome")).isEqualTo("Subcota");
        assertThat(lerJson(troca, "$.impacto.abasQueFecham[0].codigo")).isEqualTo("PESQUISA");
        // A troca fez exatamente o que a prévia disse.
        assertThat(lerLista(troca, "$.impacto.abasQueFecham[*].codigo")).isEqualTo(lerLista(previa, "$.abasQueFecham[*].codigo"));
        assertThat(abasLiberadas(parceria)).isEqualTo("PERFIL,APLICACOES,MIDIA,CORTESIAS,CREDENCIAMENTO,CONTRATO");
        assertThat(lerLista(troca, "$.parceria.abas[?(@.codigo=='PESQUISA')].liberada")).containsExactly(false);
        assertThat(lerLista(troca, "$.parceria.abas[?(@.codigo=='PESQUISA')].incluidaNaCota")).containsExactly(false);
        // A RBS não reabre a aba que a cota nova não inclui.
        assertThat(rbs.patch("/api/parcerias/" + parceria, "{\"abas\":[{\"codigo\":\"PESQUISA\",\"liberada\":true}]}"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);

        // De volta: a Pesquisa passa a poder abrir, mas continua fechada (a RBS abre pelo Perfil).
        MvcTestResult volta = rbs.put(trocaEm(parceria), "{\"cotaCodigo\":\"MASTER\"}");
        assertThat(volta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(volta, "$.impacto.pesquisa")).isEqualTo("PASSA_A_VALER");
        assertThat(lerLista(volta, "$.impacto.abasQuePassamAPoderAbrir[*].codigo")).containsExactly("PESQUISA");
        assertThat(lerLista(volta, "$.parceria.abas[?(@.codigo=='PESQUISA')].liberada")).containsExactly(false);
        assertThat(lerLista(volta, "$.parceria.abas[?(@.codigo=='PESQUISA')].incluidaNaCota")).containsExactly(true);
        assertThat(rbs.patch("/api/parcerias/" + parceria, "{\"abas\":[{\"codigo\":\"PESQUISA\",\"liberada\":true}]}"))
                .hasStatus(HttpStatus.OK);
        assertThat(abasLiberadas(parceria)).contains("PESQUISA");
    }

    @Test
    void aMarcaPerdeAAbaAoSairDaMasterEOAtlasLeAColaViva() {
        long parceria = criar("MASTER");
        assertThat(atlas.codigoDaCota(parceria)).isEqualTo("MASTER");
        assertThat(atlas.escopoDaParceria(parceria).cotaCodigo()).isEqualTo("MASTER");

        assertThat(SessaoDeApi.entrar(mvc, RBS).put(trocaEm(parceria), "{\"cotaCodigo\":\"SUBCOTA_G\"}")).hasStatus(HttpStatus.OK);

        // Nada guarda a cota antiga: o Atlas e a verificação de aba leem a parceria a cada chamada.
        assertThat(atlas.codigoDaCota(parceria)).isEqualTo("SUBCOTA_G");
        assertThat(atlas.escopoDaParceria(parceria).cotaCodigo()).isEqualTo("SUBCOTA_G");
        assertThat(atlas.escopoDaParceria(parceria).cotaTipoId())
                .isEqualTo(jdbc.sql("SELECT id FROM cota_tipo WHERE codigo = 'SUBCOTA_G'").query(Integer.class).single());
    }

    @Test
    void aTrocaGravaAEdicaoDaCotaEUmaLiberacaoPorAbaQueFechou() {
        long parceria = criar("MASTER");
        long usuario = jdbc.sql("SELECT id FROM usuario WHERE email = :e").param("e", RBS).query(Long.class).single();

        assertThat(SessaoDeApi.entrar(mvc, RBS).put(trocaEm(parceria), "{\"cotaCodigo\":\"SUBCOTA_G\"}")).hasStatus(HttpStatus.OK);

        var edicao = jdbc.sql("""
                        SELECT valor_anterior, valor_novo, usuario_id, entidade FROM historico
                        WHERE parceria_id = :p AND acao = 'EDICAO' AND detalhe = 'cota'
                        """).param("p", parceria).query().listOfRows();
        assertThat(edicao).hasSize(1);
        assertThat(edicao.get(0)).containsEntry("valor_anterior", "Master").containsEntry("valor_novo", "Subcota G")
                .containsEntry("usuario_id", usuario).containsEntry("entidade", "parceria");
        var liberacoes = jdbc.sql("SELECT valor_anterior, valor_novo, detalhe FROM historico WHERE parceria_id = :p AND acao = 'LIBERACAO_ABA'")
                .param("p", parceria).query().listOfRows();
        assertThat(liberacoes).hasSize(1);
        assertThat(liberacoes.get(0)).containsEntry("valor_anterior", "liberada").containsEntry("valor_novo", "fechada");
        assertThat((String) liberacoes.get(0).get("detalhe")).contains("Master").contains("Subcota G");
    }

    @Test
    void cortesiasECredenciaisFicamComoEstavam() {
        long parceria = criar("MASTER");
        jdbc.sql("INSERT INTO cortesia_cota (parceria_id, setor, dia, quantidade, com_shuttle, acesso) VALUES (:p, 'ARENA', 'SEXTA', 12, TRUE, 'Portão 1')")
                .param("p", parceria).update();
        jdbc.sql("INSERT INTO credencial_cota (parceria_id, setor, beneficiario, quantidade_por_dia) VALUES (:p, 'ARENA', 'MARCA', 7)")
                .param("p", parceria).update();
        String cortesias = "SELECT string_agg(setor || dia || quantidade || com_shuttle || acesso, ';') FROM cortesia_cota WHERE parceria_id = " + parceria;
        String credenciais = "SELECT string_agg(setor || beneficiario || quantidade_por_dia, ';') FROM credencial_cota WHERE parceria_id = " + parceria;
        String cortesiasAntes = jdbc.sql(cortesias).query(String.class).single();
        String credenciaisAntes = jdbc.sql(credenciais).query(String.class).single();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        assertThat(rbs.put(trocaEm(parceria), "{\"cotaCodigo\":\"SUBCOTA\"}")).hasStatus(HttpStatus.OK);
        assertThat(rbs.put(trocaEm(parceria), "{\"cotaCodigo\":\"MASTER\"}")).hasStatus(HttpStatus.OK);

        assertThat(jdbc.sql(cortesias).query(String.class).single()).isEqualTo(cortesiasAntes);
        assertThat(jdbc.sql(credenciais).query(String.class).single()).isEqualTo(credenciaisAntes);
    }

    @Test
    void abaOcultaContinuaOcultaAoFechar() {
        long parceria = criar("MASTER");
        jdbc.sql("UPDATE parceria_aba SET visivel = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'PESQUISA')")
                .param("p", parceria).update();

        MvcTestResult troca = SessaoDeApi.entrar(mvc, RBS).put(trocaEm(parceria), "{\"cotaCodigo\":\"SUBCOTA\"}");

        assertThat(troca).hasStatus(HttpStatus.OK);
        assertThat(lerLista(troca, "$.parceria.abas[?(@.codigo=='PESQUISA')].visivel")).containsExactly(false);
        assertThat(lerLista(troca, "$.parceria.abas[?(@.codigo=='PESQUISA')].liberada")).containsExactly(false);
    }

    // ---------------------------------------------------------------------
    // Relatório disponibilizado: a ciência
    // ---------------------------------------------------------------------

    @Test
    void comRelatorioDisponibilizadoATrocaSemCienciaDa409EComCienciaPassa() {
        long parceria = criar("MASTER");
        disponibilizarRelatorio(parceria);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long historicoAntes = contar("SELECT count(*) FROM historico");

        for (String corpo : List.of("{\"cotaCodigo\":\"SUBCOTA\"}", "{\"cotaCodigo\":\"SUBCOTA\",\"ciente\":false}")) {
            MvcTestResult recusada = rbs.put(trocaEm(parceria), corpo);
            assertThat(recusada).as(corpo).hasStatus(HttpStatus.CONFLICT);
            assertThat((String) lerJson(recusada, "$.detail")).contains("relatório v2 disponibilizado").contains("ciente");
        }
        assertThat(cotaGravada(parceria)).isEqualTo("MASTER");
        assertThat(abasLiberadas(parceria)).contains("PESQUISA");
        assertThat(contar("SELECT count(*) FROM historico")).isEqualTo(historicoAntes);

        MvcTestResult aceita = rbs.put(trocaEm(parceria), "{\"cotaCodigo\":\"SUBCOTA\",\"ciente\":true}");
        assertThat(aceita).hasStatus(HttpStatus.OK);
        assertThat(cotaGravada(parceria)).isEqualTo("SUBCOTA");
        assertThat(lerJson(aceita, "$.impacto.relatorioDisponibilizado.versao")).isEqualTo(2);
    }

    @Test
    void relatorioEmRascunhoOuValidadoNaoExigeCiencia() {
        long parceria = criar("MASTER");
        long rbsId = jdbc.sql("SELECT id FROM usuario WHERE email = :e").param("e", RBS).query(Long.class).single();
        jdbc.sql("INSERT INTO relatorio (parceria_id, versao, gerado_por) VALUES (:p, 1, :u)").param("p", parceria).param("u", rbsId).update();

        assertThat(SessaoDeApi.entrar(mvc, RBS).put(trocaEm(parceria), "{\"cotaCodigo\":\"SUBCOTA_G\"}")).hasStatus(HttpStatus.OK);
    }

    // ---------------------------------------------------------------------
    // Recusas
    // ---------------------------------------------------------------------

    @Test
    void mesmaCotaCotaQueNaoExisteECotaEmBrancoDao422() {
        long parceria = criar("SUBCOTA_G");
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long historicoAntes = contar("SELECT count(*) FROM historico");

        for (String corpo : List.of("{\"cotaCodigo\":\"SUBCOTA_G\"}", "{\"cotaCodigo\":\"PLATINA\"}", "{\"cotaCodigo\":\"  \"}", "{}")) {
            MvcTestResult resposta = rbs.put(trocaEm(parceria), corpo);
            assertThat(resposta).as(corpo).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(lerJson(resposta, "$.campos.cotaCodigo")).as(corpo).isNotNull();
        }
        assertThat(cotaGravada(parceria)).isEqualTo("SUBCOTA_G");
        assertThat(contar("SELECT count(*) FROM historico")).as("a recusa não grava").isEqualTo(historicoAntes);
    }

    @Test
    void parceriaEncerradaDa409() {
        long parceria = criar("MASTER");
        jdbc.sql("UPDATE parceria SET situacao = 'ENCERRADA' WHERE id = :p").param("p", parceria).update();

        MvcTestResult resposta = SessaoDeApi.entrar(mvc, RBS).put(trocaEm(parceria), "{\"cotaCodigo\":\"SUBCOTA\"}");

        assertThat(resposta).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("Esta parceria está encerrada e não aceita alterações.");
        assertThat(cotaGravada(parceria)).isEqualTo("MASTER");
    }

    @Test
    void parceriaQueNaoExisteDa404NaPreviaENaTroca() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        assertThat(rbs.get("/api/parcerias/999999/cota/previa?para=MASTER")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(rbs.put("/api/parcerias/999999/cota", "{\"cotaCodigo\":\"MASTER\"}")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(rbs.put("/api/parcerias/abc/cota", "{\"cotaCodigo\":\"MASTER\"}")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void marcaRecebe403AntesDeLerOCorpoSejaQualForOCorpo() {
        long amarelo = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);
        String cotaAntes = cotaGravada(amarelo);

        for (String corpo : List.of("", "{}", "isto nao e json", "{\"cotaCodigo\":\"SUBCOTA\",\"ciente\":true}")) {
            MvcTestResult resposta = marca.put(trocaEm(amarelo), corpo);
            assertThat(resposta).as("[" + corpo + "]").hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo("Esta operação é restrita à equipe da RBS.");
        }
        MvcTestResult previa = marca.get(trocaEm(amarelo) + "/previa?para=SUBCOTA");
        assertThat(previa).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(previa, "$.detail")).isEqualTo("Esta operação é restrita à equipe da RBS.");
        assertThat(cotaGravada(amarelo)).isEqualTo(cotaAntes);
    }

    @Test
    void semSessaoRecebe401() {
        long parceria = criar("MASTER");
        SessaoDeApi anonima = SessaoDeApi.anonima(mvc);

        assertThat(anonima.put(trocaEm(parceria), "{\"cotaCodigo\":\"SUBCOTA\"}")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(anonima.get(trocaEm(parceria) + "/previa?para=SUBCOTA")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(cotaGravada(parceria)).isEqualTo("MASTER");
    }
}
