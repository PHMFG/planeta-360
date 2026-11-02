package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.RBS;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * [ATLAS] [PERMISSAO] [ISOLAMENTO] As rotas 67 e 68 do Atlas da RBS (M8): abrir e listar as conversas da RBS por edição, sem parceria,
 * como o front as usa (login, sessão e CSRF). A conversa é da pessoa da RBS que a abriu, naquela edição; a lista traz só as dela; a
 * marca recebe 403 (antes de tudo) nas duas; a edição que não existe dá 404. Não é {@code @Transactional}: segue os outros testes do
 * Atlas com o provedor roteirizado; o que o teste confirmou é apagado no fim.
 */
@TesteDoAtlasDaRbs
class AtlasDaRbsRotasTest {

    private static final String SO_RBS = "Esta operação é restrita à equipe da RBS.";
    private static final String OUTRA_RBS = "pesquisa@rbs.example";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void guardarOQueJaExiste() {
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagarOQueOTesteConfirmou() {
        limpeza.apagar();
    }

    long evento(int edicao) {
        return jdbc.sql("SELECT id FROM evento WHERE edicao = :edicao").param("edicao", edicao).query(Long.class).single();
    }

    String abrir(long evento) {
        return "/api/eventos/" + evento + "/atlas/conversas";
    }

    long idDe(MvcTestResult resposta) {
        return ((Number) lerJson(resposta, "$.conversaId")).longValue();
    }

    long usuario(String email) {
        return jdbc.sql("SELECT id FROM usuario WHERE email = :email").param("email", email).query(Long.class).single();
    }

    // ---------------------------------------------------------------------
    // Rota 67: abrir
    // ---------------------------------------------------------------------

    @Test
    void aRbsAbreUmaConversaDoEventoSemParceriaEDevolve201ComOsItensVazios() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult aberta = rbs.post(abrir(evento(2027)), "");

        assertThat(aberta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(aberta, "$.llmDisponivel")).isEqualTo(true);
        assertThat(lerJson(aberta, "$.relatorioDisponivel")).as("a conversa da RBS não tem relatório").isEqualTo(false);
        assertThat(lerJson(aberta, "$.debugDeFerramentas")).isEqualTo(false);
        assertThat(lerJson(aberta, "$.diagnosticoDisponivel")).as("a RBS vê o diagnóstico da conversa dela").isEqualTo(true);
        assertThat(lerLista(aberta, "$.itens")).isEmpty();
        var linha = jdbc.sql("SELECT parceria_id, evento_id, usuario_id, turno_desde FROM atlas_conversa WHERE id = :id")
                .param("id", idDe(aberta)).query((rs, n) -> new Object[]{rs.getObject(1), rs.getLong(2), rs.getLong(3), rs.getObject(4)}).single();
        assertThat(linha[0]).as("sem parceria").isNull();
        assertThat(linha[1]).isEqualTo(evento(2027));
        assertThat(linha[2]).isEqualTo(usuario(RBS));
        assertThat(linha[3]).as("sem turno em andamento").isNull();
    }

    @Test
    void aRotaNaoLeOCorpo() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        for (String corpo : List.of("", "{}", "isto nao e json", "{\"atalho\":\"MONTAR_RELATORIO\",\"modelo\":\"ATIVACAO_EVENTO\"}")) {
            assertThat(rbs.post(abrir(evento(2027)), corpo)).as("corpo [" + corpo + "]").hasStatus(HttpStatus.CREATED);
        }
    }

    @Test
    void cadaChamadaAbreUmaConversaNova() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        long primeira = idDe(rbs.post(abrir(evento(2027)), ""));
        long segunda = idDe(rbs.post(abrir(evento(2027)), ""));

        assertThat(segunda).isGreaterThan(primeira);
    }

    @Test
    void edicaoQueNaoExisteDa404ComAFraseDaEdicaoSemGravarNada() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long antes = jdbc.sql("SELECT count(*) FROM atlas_conversa").query(Long.class).single();

        for (String edicao : List.of("999999", "0", "-3", "abc")) {
            MvcTestResult abrir = rbs.post("/api/eventos/" + edicao + "/atlas/conversas", "");
            assertThat(abrir).as("POST " + edicao).hasStatus(HttpStatus.NOT_FOUND);
            MvcTestResult listar = rbs.get("/api/eventos/" + edicao + "/atlas/conversas");
            assertThat(listar).as("GET " + edicao).hasStatus(HttpStatus.NOT_FOUND);
            if (!edicao.equals("abc")) {
                assertThat(lerJson(abrir, "$.detail")).isEqualTo("Não encontramos esta edição do festival.");
                assertThat(lerJson(listar, "$.detail")).isEqualTo("Não encontramos esta edição do festival.");
            }
        }
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_conversa").query(Long.class).single()).isEqualTo(antes);
    }

    // ---------------------------------------------------------------------
    // Rota 68: listar
    // ---------------------------------------------------------------------

    @Test
    void aListaTrazSoAsConversasDeQuemPedeNaquelaEdicaoDaMaisMexidaParaAMaisAntiga() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        SessaoDeApi outra = SessaoDeApi.entrar(mvc, OUTRA_RBS);
        long primeira = idDe(rbs.post(abrir(evento(2027)), ""));
        long segunda = idDe(rbs.post(abrir(evento(2027)), ""));
        long de2026 = idDe(rbs.post(abrir(evento(2026)), ""));
        long daOutra = idDe(outra.post(abrir(evento(2027)), ""));
        // uma mensagem na primeira a faz a mais mexida
        jdbc.sql("INSERT INTO atlas_mensagem (conversa_id, ordem, papel, conteudo) VALUES (:c, 1, 'user', :texto)")
                .param("c", primeira).param("texto", "Qual é o prazo para informar os geradores?").update();

        MvcTestResult lista = rbs.get(abrir(evento(2027)));

        assertThat(lista).hasStatus(HttpStatus.OK);
        assertThat(lerJson(lista, "$.llmDisponivel")).isEqualTo(true);
        assertThat(lerLista(lista, "$.conversas[*].id")).containsExactly((int) primeira, (int) segunda);
        assertThat(lerLista(lista, "$.conversas[*].id")).doesNotContain((int) de2026, (int) daOutra);
        assertThat((String) lerJson(lista, "$.conversas[0].criadaEm")).endsWith("-03:00");
        assertThat((String) lerJson(lista, "$.conversas[0].atualizadaEm")).endsWith("-03:00");
        assertThat(lerJson(lista, "$.conversas[0].previa")).isEqualTo("Qual é o prazo para informar os geradores?");
        assertThat(lerJson(lista, "$.conversas[1].previa")).as("conversa sem mensagem").isNull();
        // o que é de outra edição ou de outra pessoa fica no lugar certo
        assertThat(lerLista(rbs.get(abrir(evento(2026))), "$.conversas[*].id")).containsExactly((int) de2026);
        assertThat(lerLista(outra.get(abrir(evento(2027))), "$.conversas[*].id")).containsExactly((int) daOutra);
    }

    @Test
    void aPreviaVemCortadaEmOitentaCaracteres() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long conversa = idDe(rbs.post(abrir(evento(2027)), ""));
        jdbc.sql("INSERT INTO atlas_mensagem (conversa_id, ordem, papel, conteudo) VALUES (:c, 1, 'user', :texto)")
                .param("c", conversa).param("texto", "a".repeat(300)).update();

        MvcTestResult lista = rbs.get(abrir(evento(2027)));

        assertThat((String) lerJson(lista, "$.conversas[0].previa")).hasSize(80);
    }

    @Test
    void aListaTemNoMaximoVinteConversas() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        jdbc.sql("""
                INSERT INTO atlas_conversa (parceria_id, evento_id, usuario_id)
                SELECT NULL, :evento, :usuario FROM generate_series(1, 25)
                """).param("evento", evento(2027)).param("usuario", usuario(RBS)).update();

        assertThat(lerLista(rbs.get(abrir(evento(2027))), "$.conversas")).hasSize(20);
    }

    @Test
    void aListaDaRbsNaoTrazConversaDeMarcaENemAListaDaMarcaTrazADaRbs() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        long daMarca = idDe(marca.post("/api/parcerias/" + parceria + "/atlas/conversas", "{}"));
        long daRbs = idDe(rbs.post(abrir(evento(2027)), ""));

        assertThat(lerLista(rbs.get(abrir(evento(2027))), "$.conversas[*].id")).contains((int) daRbs).doesNotContain((int) daMarca);
        assertThat(lerLista(marca.get("/api/parcerias/" + parceria + "/atlas/conversas"), "$.conversas[*].id")).contains((int) daMarca)
                .doesNotContain((int) daRbs);
    }

    // ---------------------------------------------------------------------
    // Quem pode
    // ---------------------------------------------------------------------

    @Test
    void aMarcaRecebe403NasDuasRotasQualquerQueSejaOCorpoOuAEdicao() {
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);
        long antes = jdbc.sql("SELECT count(*) FROM atlas_conversa").query(Long.class).single();

        for (String edicao : List.of(String.valueOf(evento(2027)), "999999", "abc")) {
            for (String corpo : List.of("", "{}", "isto nao e json")) {
                MvcTestResult abrir = marca.post("/api/eventos/" + edicao + "/atlas/conversas", corpo);
                assertThat(abrir).as("POST " + edicao + " [" + corpo + "]").hasStatus(HttpStatus.FORBIDDEN);
                assertThat(lerJson(abrir, "$.detail")).isEqualTo(SO_RBS);
            }
            MvcTestResult listar = marca.get("/api/eventos/" + edicao + "/atlas/conversas");
            assertThat(listar).as("GET " + edicao).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(listar, "$.detail")).isEqualTo(SO_RBS);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_conversa").query(Long.class).single()).isEqualTo(antes);
    }

    @Test
    void semSessaoRecebe401ESemTokenCsrfRecebe403() {
        String uri = abrir(evento(2027));

        assertThat(SessaoDeApi.anonima(mvc).get(uri)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(SessaoDeApi.anonima(mvc).post(uri, "")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(SessaoDeApi.entrar(mvc, RBS).postSemCsrf(uri, "")).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void aRotaDeAbrirNaoGravaNadaNoHistoricoNemNoTraco() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long historico = jdbc.sql("SELECT count(*) FROM historico").query(Long.class).single();
        long traco = jdbc.sql("SELECT count(*) FROM atlas_traco").query(Long.class).single();

        MvcTestResult aberta = rbs.post(abrir(evento(2027)), "");

        assertThat(aberta).hasStatus(HttpStatus.CREATED);
        assertThat(jdbc.sql("SELECT count(*) FROM historico").query(Long.class).single()).isEqualTo(historico);
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_traco").query(Long.class).single()).isEqualTo(traco);
        assertThat(corpo(aberta)).doesNotContain("parceria");
    }
}
