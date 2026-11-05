package br.com.rbs.portal.pesquisa;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * R5.6.d · Rotas 92 a 95: a RBS abre a pesquisa da edição pela tela, edita e vê a fila das perguntas a aprovar. [PERMISSAO]
 * [VALIDACAO] [HISTORICO] Os testes de abertura usam uma edição nova, criada aqui e sem pesquisa; o teste da aba da Master apaga a
 * pesquisa da amostra de 2027 e a abre de novo pela rota. Cada teste é desfeito no fim (@Transactional); um 409 ou 422 da API não
 * aborta a transação, porque as regras são conferidas antes de gravar.
 */
@TesteDeIntegracao
@Transactional
class AberturaDaPesquisaTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvcTester mvc;

    long edicaoNova() {
        return jdbc.sql("INSERT INTO evento (nome, edicao) VALUES ('Planeta Atlântida', 2031) RETURNING id")
                .query(Long.class).single();
    }

    long evento2027() {
        return jdbc.sql("SELECT id FROM evento WHERE edicao = 2027").query(Long.class).single();
    }

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    long contar(String sql, Object... params) {
        var consulta = jdbc.sql(sql);
        for (int i = 0; i < params.length; i++) {
            consulta = consulta.param(i + 1, params[i]);
        }
        return consulta.query(Long.class).single();
    }

    static String corpo(String nome) {
        return "{\"nome\":\"" + nome + "\",\"metodologia\":\"Entrevistas nos espaços\",\"baseRespondentes\":400,\"nivelConfianca\":95}";
    }

    MvcTestResult abrir(SessaoDeApi sessao, long evento, String json) {
        return sessao.post("/api/eventos/" + evento + "/pesquisas", json);
    }

    // ---------------------------------------------------------------------
    // Abrir
    // ---------------------------------------------------------------------

    @Test
    void rbsAbreAPesquisaDaAtivacaoDeUmaEdicaoSemPesquisaELista() {
        long evento = edicaoNova();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        assertThat(lerLista(rbs.get("/api/eventos/" + evento + "/pesquisas"), "$")).isEmpty();

        MvcTestResult aberta = abrir(rbs, evento, corpo("Pesquisa nos espaços de ativação 2031"));

        assertThat(aberta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(aberta, "$.escopo")).isEqualTo("ATIVACAO");
        assertThat(lerJson(aberta, "$.eventoId")).isEqualTo((int) evento);
        assertThat(lerJson(aberta, "$.nome")).isEqualTo("Pesquisa nos espaços de ativação 2031");
        assertThat(lerJson(aberta, "$.metodologia")).isEqualTo("Entrevistas nos espaços");
        assertThat(lerJson(aberta, "$.baseRespondentes")).isEqualTo(400);
        assertThat(((Number) lerJson(aberta, "$.nivelConfianca")).doubleValue()).isEqualTo(95.0);
        assertThat(lerJson(aberta, "$.perguntas")).isEqualTo(Map.of("rascunho", 0, "enviadas", 0, "aprovadas", 0));
        assertThat(lerJson(aberta, "$.resultados")).isEqualTo(0);
        assertThat(lerJson(aberta, "$.perguntasLigadas")).isEqualTo(0);
        assertThat(aberta.getResponse().getHeader("Location")).isEqualTo("/api/eventos/" + evento + "/pesquisas");

        MvcTestResult lista = rbs.get("/api/eventos/" + evento + "/pesquisas");
        assertThat(lista).hasStatus(HttpStatus.OK);
        assertThat(lerLista(lista, "$")).hasSize(1);
        assertThat(lerJson(lista, "$[0].escopo")).isEqualTo("ATIVACAO");
        assertThat(lerJson(lista, "$[0].perguntasLigadas")).isNull();

        var historico = jdbc.sql("SELECT acao, parceria_id, detalhe FROM historico WHERE entidade = 'pesquisa' AND entidade_id = :id")
                .param("id", ((Number) lerJson(aberta, "$.id")).longValue()).query().singleRow();
        assertThat(historico.get("acao")).isEqualTo("CRIACAO");
        assertThat(historico.get("parceria_id")).isNull();
        assertThat((String) historico.get("detalhe")).contains("ATIVACAO");
    }

    @Test
    void escopoOmitidoValeAtivacaoEMetodologiaEBaseSaoOpcionais() {
        long evento = edicaoNova();

        MvcTestResult aberta = abrir(SessaoDeApi.entrar(mvc, RBS), evento, "{\"nome\":\"Pesquisa mínima\"}");

        assertThat(aberta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(aberta, "$.escopo")).isEqualTo("ATIVACAO");
        assertThat(lerJson(aberta, "$.metodologia")).isNull();
        assertThat(lerJson(aberta, "$.baseRespondentes")).isNull();
        assertThat(lerJson(aberta, "$.nivelConfianca")).isNull();
    }

    @Test
    void asegundaAberturaDaAtivacaoNaMesmaEdicaoDa409ComFraseClaraENadaNovoNoBanco() {
        long evento = edicaoNova();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        assertThat(abrir(rbs, evento, corpo("Primeira"))).hasStatus(HttpStatus.CREATED);

        MvcTestResult segunda = abrir(rbs, evento, corpo("Segunda"));

        assertThat(segunda).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(segunda, "$.detail")).isEqualTo(
                "A edição 2031 já tem a pesquisa de ativação aberta (\"Primeira\"). Edite essa pesquisa em vez de abrir outra.");
        assertThat(contar("SELECT count(*) FROM pesquisa WHERE evento_id = ?", evento)).isEqualTo(1);
    }

    @Test
    void nomeRepetidoNaEdicaoDa409() {
        long evento = edicaoNova();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        assertThat(abrir(rbs, evento, "{\"nome\":\"Pesquisa geral\",\"escopo\":\"GERAL\"}")).hasStatus(HttpStatus.CREATED);

        MvcTestResult repetido = abrir(rbs, evento, "{\"nome\":\"Pesquisa geral\"}");

        assertThat(repetido).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(repetido, "$.detail")).isEqualTo("Já existe uma pesquisa com este nome na edição 2031. Escolha outro nome.");
    }

    @Test
    void pesquisaGeralAbreSemLigarPerguntaEUmaAtivacaoPodeVirDepoisDela() {
        long evento = edicaoNova();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult geral = abrir(rbs, evento, "{\"nome\":\"Pesquisa geral do festival\",\"escopo\":\"GERAL\"}");
        assertThat(geral).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(geral, "$.escopo")).isEqualTo("GERAL");
        assertThat(lerJson(geral, "$.perguntasLigadas")).isEqualTo(0);

        assertThat(abrir(rbs, evento, corpo("Ativação depois da geral"))).hasStatus(HttpStatus.CREATED);
        MvcTestResult lista = rbs.get("/api/eventos/" + evento + "/pesquisas");
        assertThat(lerLista(lista, "$[*].escopo")).containsExactly("ATIVACAO", "GERAL");
    }

    @Test
    void camposInvalidosDao422ComTodosOsErrosDeUmaVez() {
        long evento = edicaoNova();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult ruim = abrir(rbs, evento,
                "{\"nome\":\"ab\",\"escopo\":\"OUTRO\",\"metodologia\":\"%s\",\"baseRespondentes\":0,\"nivelConfianca\":100}"
                        .formatted("m".repeat(161)));

        assertThat(ruim).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(ruim, "$.campos.nome")).isEqualTo("O nome da pesquisa precisa ter de 3 a 120 caracteres.");
        assertThat(lerJson(ruim, "$.campos.escopo")).isEqualTo("Escopo inválido. Use ATIVACAO ou GERAL.");
        assertThat(lerJson(ruim, "$.campos.metodologia")).isEqualTo("A metodologia pode ter até 160 caracteres.");
        assertThat(lerJson(ruim, "$.campos.baseRespondentes")).isNotNull();
        assertThat(lerJson(ruim, "$.campos.nivelConfianca")).isNotNull();
        assertThat(contar("SELECT count(*) FROM pesquisa WHERE evento_id = ?", evento)).isZero();
    }

    @Test
    void nivelDeConfiancaZeroECemNaoValemEBaseGiganteTambemNao() {
        long evento = edicaoNova();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        for (String nivel : List.of("0", "100", "-5", "95.123")) {
            MvcTestResult ruim = abrir(rbs, evento, "{\"nome\":\"Pesquisa\",\"nivelConfianca\":" + nivel + "}");
            assertThat(ruim).as("nível " + nivel).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(lerJson(ruim, "$.campos.nivelConfianca")).isNotNull();
        }
        MvcTestResult gigante = abrir(rbs, evento, "{\"nome\":\"Pesquisa\",\"baseRespondentes\":99999999999}");
        assertThat(gigante).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(gigante, "$.campos.baseRespondentes")).isNotNull();
        assertThat(abrir(rbs, evento, "{\"nome\":\"Pesquisa\",\"nivelConfianca\":99.99}")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void nulNoTextoDa422EnaoErro500() {
        long evento = edicaoNova();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult comNul = abrir(rbs, evento, "{\"nome\":\"Pesq\\u0000uisa\",\"metodologia\":\"a\\u0000b\"}");

        assertThat(comNul).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(comNul, "$.campos.nome")).isNotNull();
        assertThat(lerJson(comNul, "$.campos.metodologia")).isNotNull();
    }

    @Test
    void nomeEmFaltaDa422() {
        MvcTestResult ruim = abrir(SessaoDeApi.entrar(mvc, RBS), edicaoNova(), "{}");

        assertThat(ruim).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(ruim, "$.campos.nome")).isEqualTo("Informe o nome da pesquisa.");
    }

    @Test
    void edicaoQueNaoExisteDa404NaListaENaAbertura() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        assertThat(rbs.get("/api/eventos/999999/pesquisas")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(abrir(rbs, 999999, corpo("Pesquisa"))).hasStatus(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------------
    // A Master vê a aba pronta; as perguntas já enviadas passam a valer
    // ---------------------------------------------------------------------

    @Test
    void depoisDeAbertaAMasterVeAAbaProntaEAsPerguntasAntigasFicamLigadas() {
        long evento = evento2027();
        long parceria = amarelo();
        SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        // Uma edição sem pesquisa, com as 3 perguntas do Amarelo já enviadas: tira-se a pesquisa da amostra.
        jdbc.sql("DELETE FROM pesquisa_resultado WHERE pesquisa_id IN (SELECT id FROM pesquisa WHERE evento_id = :e AND escopo = 'ATIVACAO')")
                .param("e", evento).update();
        jdbc.sql("UPDATE pesquisa_pergunta SET pesquisa_id = NULL, status = 'ENVIADA' WHERE parceria_id = :p").param("p", parceria).update();
        jdbc.sql("DELETE FROM pesquisa WHERE evento_id = :e AND escopo = 'ATIVACAO'").param("e", evento).update();
        MvcTestResult antes = amarelo.get("/api/parcerias/" + parceria + "/pesquisa");
        assertThat(antes).hasStatus(HttpStatus.OK);
        assertThat(lerJson(antes, "$.pesquisa")).isNull();
        assertThat(lerJson(antes, "$.abaLiberada")).isEqualTo(true);

        MvcTestResult aberta = abrir(rbs, evento, corpo("Pesquisa nos espaços de ativação 2027"));

        assertThat(aberta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(aberta, "$.perguntasLigadas")).isEqualTo(3);
        assertThat(lerJson(aberta, "$.perguntas.enviadas")).isEqualTo(3);
        MvcTestResult depois = amarelo.get("/api/parcerias/" + parceria + "/pesquisa");
        assertThat(depois).hasStatus(HttpStatus.OK);
        assertThat(lerJson(depois, "$.pesquisa.nome")).isEqualTo("Pesquisa nos espaços de ativação 2027");
        assertThat(lerJson(depois, "$.pesquisa.baseRespondentes")).isEqualTo(400);
        assertThat(lerLista(depois, "$.perguntas")).hasSize(3);
        assertThat(contar("SELECT count(*) FROM pesquisa_pergunta WHERE parceria_id = ? AND pesquisa_id IS NULL", parceria)).isZero();
    }

    // ---------------------------------------------------------------------
    // Editar
    // ---------------------------------------------------------------------

    @Test
    void rbsEditaAPesquisaEOHistoricoGuardaUmaLinhaPorCampoQueMudou() {
        long evento = edicaoNova();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long id = ((Number) lerJson(abrir(rbs, evento, corpo("Pesquisa original")), "$.id")).longValue();

        MvcTestResult editada = rbs.patch("/api/pesquisas/" + id, "{\"nome\":\"Pesquisa renomeada\",\"baseRespondentes\":420,"
                + "\"nivelConfianca\":95,\"escopo\":\"GERAL\"}");

        assertThat(editada).hasStatus(HttpStatus.OK);
        assertThat(lerJson(editada, "$.nome")).isEqualTo("Pesquisa renomeada");
        assertThat(lerJson(editada, "$.baseRespondentes")).isEqualTo(420);
        assertThat(lerJson(editada, "$.escopo")).as("o escopo não muda").isEqualTo("ATIVACAO");
        assertThat(lerJson(editada, "$.metodologia")).as("o que não veio fica como está").isEqualTo("Entrevistas nos espaços");
        List<String> campos = jdbc.sql("SELECT detalhe FROM historico WHERE entidade = 'pesquisa' AND entidade_id = :id AND acao = 'EDICAO' ORDER BY id")
                .param("id", id).query(String.class).list();
        assertThat(campos).containsExactly("nome", "baseRespondentes");

        MvcTestResult limpa = rbs.patch("/api/pesquisas/" + id, "{\"metodologia\":\"   \"}");
        assertThat(limpa).hasStatus(HttpStatus.OK);
        assertThat(lerJson(limpa, "$.metodologia")).isNull();
    }

    @Test
    void edicaoComNomeDeOutraPesquisaDa409ECorpoVazioDa422EPesquisaInexistenteDa404() {
        long evento = edicaoNova();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long ativacao = ((Number) lerJson(abrir(rbs, evento, corpo("Ativação")), "$.id")).longValue();
        abrir(rbs, evento, "{\"nome\":\"Geral\",\"escopo\":\"GERAL\"}");

        MvcTestResult repetido = rbs.patch("/api/pesquisas/" + ativacao, "{\"nome\":\"Geral\"}");
        assertThat(repetido).hasStatus(HttpStatus.CONFLICT);
        assertThat(rbs.patch("/api/pesquisas/" + ativacao, "{\"nome\":\"Ativação\"}")).as("o próprio nome não conflita")
                .hasStatus(HttpStatus.OK);
        MvcTestResult vazio = rbs.patch("/api/pesquisas/" + ativacao, "{}");
        assertThat(vazio).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(vazio, "$.campos.corpo")).isNotNull();
        assertThat(rbs.patch("/api/pesquisas/" + ativacao, "{\"nivelConfianca\":0}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(rbs.patch("/api/pesquisas/999999", "{\"nome\":\"Qualquer\"}")).hasStatus(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------------
    // A fila das perguntas
    // ---------------------------------------------------------------------

    @Test
    void filaListaAsEnviadasDeTodasAsParceriasEAAprovacaoPelaRota25TiraDaFila() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long parceria = amarelo();
        jdbc.sql("UPDATE pesquisa_pergunta SET status = 'ENVIADA' WHERE parceria_id = :p AND ordem IN (1, 2)").param("p", parceria).update();
        long total = contar("SELECT count(*) FROM pesquisa_pergunta WHERE status = 'ENVIADA'");

        MvcTestResult fila = rbs.get("/api/pesquisa/perguntas");

        assertThat(fila).hasStatus(HttpStatus.OK);
        assertThat(((Number) lerJson(fila, "$.total")).longValue()).isEqualTo(total).isGreaterThanOrEqualTo(2);
        assertThat(lerLista(fila, "$.itens[*].status")).containsOnly("ENVIADA");
        assertThat(lerLista(fila, "$.itens[?(@.parceriaId==" + parceria + ")].ordem")).containsExactly(1, 2);
        assertThat(lerLista(fila, "$.itens[?(@.parceriaId==" + parceria + ")].patrocinador")).containsOnly("Cliente Amarelo");
        assertThat(lerLista(fila, "$.itens[?(@.parceriaId==" + parceria + ")].edicao")).containsOnly(2027);
        assertThat(lerLista(fila, "$.itens[?(@.parceriaId==" + parceria + ")].enviadaPor.perfil")).containsOnly("ADMIN_PATROCINADOR");

        long perguntaId = ((Number) lerLista(fila, "$.itens[?(@.parceriaId==" + parceria + " && @.ordem==1)].id").get(0)).longValue();
        assertThat(rbs.patch("/api/pesquisa/perguntas/" + perguntaId + "/status", "{\"decisao\":\"APROVAR\"}")).hasStatus(HttpStatus.OK);
        MvcTestResult depois = rbs.get("/api/pesquisa/perguntas");
        assertThat(((Number) lerJson(depois, "$.total")).longValue()).isEqualTo(total - 1);
        assertThat(lerLista(depois, "$.itens[?(@.id==" + perguntaId + ")]")).isEmpty();
    }

    @Test
    void filaFiltraPorStatusEdicaoEPagina() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long parceria = amarelo();
        jdbc.sql("UPDATE pesquisa_pergunta SET status = 'ENVIADA' WHERE parceria_id = :p").param("p", parceria).update();

        MvcTestResult aprovadas = rbs.get("/api/pesquisa/perguntas?status=APROVADA&eventoId=" + evento2027());
        assertThat(lerLista(aprovadas, "$.itens[?(@.parceriaId==" + parceria + ")]")).isEmpty();
        MvcTestResult daEdicaoNova = rbs.get("/api/pesquisa/perguntas?eventoId=" + edicaoNova());
        assertThat(daEdicaoNova).hasStatus(HttpStatus.OK);
        assertThat(lerLista(daEdicaoNova, "$.itens")).isEmpty();
        assertThat(lerJson(daEdicaoNova, "$.total")).isEqualTo(0);
        MvcTestResult pagina = rbs.get("/api/pesquisa/perguntas?eventoId=" + evento2027() + "&tamanho=1&pagina=1");
        assertThat(lerLista(pagina, "$.itens")).hasSize(1);
        assertThat(lerJson(pagina, "$.pagina")).isEqualTo(1);
        assertThat(lerJson(pagina, "$.tamanho")).isEqualTo(1);

        assertThat(rbs.get("/api/pesquisa/perguntas?status=FEITA")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(rbs.get("/api/pesquisa/perguntas?tamanho=0")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(rbs.get("/api/pesquisa/perguntas?eventoId=999999")).hasStatus(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------------
    // Permissão
    // ---------------------------------------------------------------------

    @Test
    void marcaRecebe403NasQuatroRotasAntesDoCorpoESemSessaoRecebe401() {
        long evento = edicaoNova();
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);
        SessaoDeApi anonima = SessaoDeApi.anonima(mvc);

        for (SessaoDeApi sessao : List.of(marca)) {
            assertThat(sessao.get("/api/eventos/" + evento + "/pesquisas")).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(sessao.post("/api/eventos/" + evento + "/pesquisas", "isto nao e json")).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(sessao.patch("/api/pesquisas/1", "isto nao e json")).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(sessao.get("/api/pesquisa/perguntas?status=lixo")).hasStatus(HttpStatus.FORBIDDEN);
        }
        assertThat(anonima.get("/api/eventos/" + evento + "/pesquisas")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(anonima.get("/api/pesquisa/perguntas")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(contar("SELECT count(*) FROM pesquisa WHERE evento_id = ?", evento)).isZero();
    }
}
