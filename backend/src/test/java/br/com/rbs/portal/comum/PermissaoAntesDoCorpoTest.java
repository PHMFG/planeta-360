package br.com.rbs.portal.comum;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * [PERMISSAO] O perfil (e, no envio das perguntas, a parceria e a aba liberada) é conferido ANTES de o Spring ler o corpo e
 * as validações: o patrocinador recebe 403 numa rota da RBS qualquer que seja o corpo, e não 422 (ou 400) de campo.
 * Atende o item 3 da Entrega 2. Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class PermissaoAntesDoCorpoTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String ROXO = "marketing@roxo.example";
    private static final String SO_RBS = "Esta operação é restrita à equipe da RBS.";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapeamento;

    record Chamada(HttpMethod metodo, String uri) {
    }

    MvcTestResult chamar(SessaoDeApi sessao, Chamada chamada, String corpo) {
        return switch (chamada.metodo().name()) {
            case "POST" -> sessao.post(chamada.uri(), corpo);
            case "PUT" -> sessao.put(chamada.uri(), corpo);
            case "PATCH" -> sessao.patch(chamada.uri(), corpo);
            default -> throw new IllegalArgumentException(chamada.metodo().name());
        };
    }

    long um(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    // ---------------------------------------------------------------------
    // Rotas da RBS com corpo: 403 antes de qualquer validação do corpo
    // ---------------------------------------------------------------------

    @Test
    void patrocinadorRecebe403NasRotasDaRbsComCorpoQualquerQueSejaOCorpo() {
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        long entrega = um("SELECT min(id) FROM entrega WHERE parceria_id = " + parceria);
        long relatorio = um("SELECT min(id) FROM relatorio WHERE parceria_id = " + parceria);
        long pergunta = um("SELECT min(id) FROM pesquisa_pergunta WHERE parceria_id = " + parceria);
        long inexistente = 999_999;
        List<Chamada> chamadas = List.of(
                new Chamada(HttpMethod.POST, "/api/parcerias"),
                new Chamada(HttpMethod.PATCH, "/api/parcerias/" + parceria),
                new Chamada(HttpMethod.PATCH, "/api/parcerias/" + inexistente),
                new Chamada(HttpMethod.POST, "/api/parcerias/" + parceria + "/emails"),
                new Chamada(HttpMethod.POST, "/api/parcerias/" + parceria + "/entregas"),
                new Chamada(HttpMethod.POST, "/api/parcerias/" + inexistente + "/entregas"),
                new Chamada(HttpMethod.PATCH, "/api/entregas/" + entrega + "/status"),
                new Chamada(HttpMethod.PATCH, "/api/entregas/" + inexistente + "/status"),
                new Chamada(HttpMethod.PATCH, "/api/relatorios/" + relatorio + "/status"),
                new Chamada(HttpMethod.PATCH, "/api/pesquisa/perguntas/" + pergunta + "/status"),
                new Chamada(HttpMethod.PATCH, "/api/pesquisa/perguntas/" + inexistente + "/status"));
        List<String> corpos = List.of("", "{}", "isto nao e json", "[]", "{\"status\":\"INVENTADO\",\"decisao\":\"TALVEZ\"}",
                "{\"abaCodigo\":\"NAO_EXISTE\",\"titulo\":\"\"}");

        SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);
        for (Chamada chamada : chamadas) {
            for (String corpo : corpos) {
                MvcTestResult resposta = chamar(amarelo, chamada, corpo);
                assertThat(resposta).as(chamada + " com corpo [" + corpo + "]").hasStatus(HttpStatus.FORBIDDEN);
                assertThat(lerJson(resposta, "$.detail")).isEqualTo(SO_RBS);
            }
        }
    }

    @Test
    void casosDaRodadaDeTestesDevolvem403AoPatrocinadorComCorpoVazioOuInvalido() {
        // Os casos exatos da rodada de testes: corpo vazio ou inválido, até com id que não existe.
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);

        assertThat(amarelo.post("/api/parcerias/" + parceria + "/entregas", "")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(amarelo.post("/api/parcerias/999999/entregas", "{}")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(amarelo.patch("/api/pesquisa/perguntas/1/status", "")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(amarelo.patch("/api/pesquisa/perguntas/1/status", "{\"decisao\":\"TALVEZ\"}")).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void rbsContinuaRecebendoAValidacaoDoCorpo() {
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        assertThat(rbs.post("/api/parcerias/" + parceria + "/entregas", "{}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(rbs.patch("/api/pesquisa/perguntas/1/status", "{\"decisao\":\"TALVEZ\"}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(rbs.post("/api/parcerias/" + parceria + "/entregas", "")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void patrocinadorRecebe403NasListasDaRbsMesmoComFiltroInvalido() {
        SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);

        for (String uri : List.of("/api/entregas?tamanho=999", "/api/entregas?pagina=-1", "/api/cargas?pagina=-1",
                "/api/cargas/1/rejeicoes?tamanho=0", "/api/historico?pagina=-1", "/api/historico.csv?q=" + "x".repeat(200),
                "/api/relatorios?status=INVENTADO", "/api/relatorios.csv?status=INVENTADO", "/api/painel?eventoId=abc")) {
            MvcTestResult resposta = amarelo.get(uri);
            assertThat(resposta).as(uri).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo(SO_RBS);
        }
    }

    // ---------------------------------------------------------------------
    // PUT das perguntas: perfil, parceria e aba liberada antes da validação
    // ---------------------------------------------------------------------

    private static final String PERGUNTAS_INVALIDAS = "{\"perguntas\":[{\"ordem\":1,\"texto\":\"curta\"},{\"ordem\":1,\"texto\":\"\"}]}";

    @Test
    void abaPesquisaFechadaVemAntesDaValidacaoDasPerguntas() {
        long roxo = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Roxo");   // Subcota G: Pesquisa não liberada
        SessaoDeApi sessao = SessaoDeApi.entrar(mvc, ROXO);

        for (String corpo : List.of(PERGUNTAS_INVALIDAS, "{}", "", "isto nao e json")) {
            MvcTestResult resposta = sessao.put("/api/parcerias/" + roxo + "/pesquisa/perguntas", corpo);
            assertThat(resposta).as("corpo [" + corpo + "]").hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo("Esta aba não faz parte da cota da sua parceria.");
        }
    }

    @Test
    void parceriaDeOutroVemAntesDaValidacaoDasPerguntas() {
        long roxo = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Roxo");
        SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);

        assertThat(amarelo.put("/api/parcerias/" + roxo + "/pesquisa/perguntas", PERGUNTAS_INVALIDAS)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(amarelo.put("/api/parcerias/999999/pesquisa/perguntas", PERGUNTAS_INVALIDAS)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void rbsRecebe403AntesDeQualquerOutraConferenciaNoEnvioDasPerguntas() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        for (String uri : List.of("/api/parcerias/999999/pesquisa/perguntas",
                "/api/parcerias/" + SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo") + "/pesquisa/perguntas")) {
            MvcTestResult resposta = rbs.put(uri, PERGUNTAS_INVALIDAS);
            assertThat(resposta).as(uri).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo("Só o Admin Patrocinador da parceria envia as perguntas da pesquisa.");
        }
    }

    @Test
    void comAbaLiberadaAValidacaoDasPerguntasContinuaValendo() {
        long amarelo = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        SessaoDeApi sessao = SessaoDeApi.entrar(mvc, AMARELO);

        MvcTestResult invalido = sessao.put("/api/parcerias/" + amarelo + "/pesquisa/perguntas", PERGUNTAS_INVALIDAS);
        assertThat(invalido).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(invalido, "$.campos['perguntas[0].texto']")).isEqualTo("A pergunta precisa ter de 10 a 300 caracteres.");
        assertThat(sessao.put("/api/parcerias/" + amarelo + "/pesquisa/perguntas", "")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    // ---------------------------------------------------------------------
    // Nenhuma rota fica sem decisão: ou é só da RBS (e está na lista), ou o patrocinador pode chamá-la
    // ---------------------------------------------------------------------

    /**
     * As rotas que o patrocinador pode chamar e a RBS também. Rota nova: decida aqui, em PermissaoAntesDoCorpo.ROTAS_SO_DA_RBS ou em
     * PermissaoAntesDoCorpo.ROTAS_SO_DO_PATROCINADOR.
     */
    private static final Set<String> ROTAS_DO_PATROCINADOR = Set.of(
            "GET /api/auth/csrf", "POST /api/auth/login", "POST /api/auth/ativar", "GET /api/auth/eu",
            "GET /api/eventos", "GET /api/parcerias", "GET /api/parcerias/{id}", "GET /api/parcerias/{id}/entregas",
            "POST /api/entregas/{id}/arquivos", "GET /api/arquivos/{id}", "GET /api/parcerias/{id}/historico",
            "GET /api/parcerias/{id}/pesquisa", "PUT /api/parcerias/{id}/pesquisa/perguntas",
            "GET /api/parcerias/{id}/consolidacao", "GET /api/parcerias/{id}/relatorio", "GET /api/relatorios/{id}/csv",
            "GET /api/parcerias/{id}/aplicacoes", "GET /api/campos", "GET /api/parcerias/{id}/dados",
            "POST /api/parcerias/{id}/dados",
            "GET /api/atlas/relatorios/{id}", "GET /api/parcerias/{id}/atlas/relatorios");

    @Test
    void todaRotaDaApiEstaClassificadaEAListaDaRbsSoTemRotasQueExistem() {
        Set<String> mapeadas = new TreeSet<>();
        mapeamento.getHandlerMethods().keySet().forEach(info -> {
            for (String padrao : info.getPathPatternsCondition().getPatternValues()) {
                if (!padrao.startsWith("/api/") || padrao.startsWith("/api/teste/")) {
                    continue;
                }
                for (RequestMethod metodo : info.getMethodsCondition().getMethods()) {
                    mapeadas.add(metodo + " " + padrao);
                }
            }
        });

        assertThat(mapeadas).as("a lista de rotas da RBS tem rota que não existe (nome mudou?)")
                .containsAll(PermissaoAntesDoCorpo.ROTAS_SO_DA_RBS);
        assertThat(mapeadas).as("a lista de rotas só do patrocinador tem rota que não existe (nome mudou?)")
                .containsAll(PermissaoAntesDoCorpo.ROTAS_SO_DO_PATROCINADOR);
        assertThat(mapeadas).as("rota nova sem decisão: é só da RBS (PermissaoAntesDoCorpo.ROTAS_SO_DA_RBS), só do patrocinador "
                + "(PermissaoAntesDoCorpo.ROTAS_SO_DO_PATROCINADOR) ou dos dois (ROTAS_DO_PATROCINADOR neste teste)?")
                .containsExactlyInAnyOrderElementsOf(unir(PermissaoAntesDoCorpo.ROTAS_SO_DA_RBS,
                        PermissaoAntesDoCorpo.ROTAS_SO_DO_PATROCINADOR, ROTAS_DO_PATROCINADOR));
        assertThat(PermissaoAntesDoCorpo.ROTAS_SO_DA_RBS).as("uma rota não é das duas listas")
                .doesNotContainAnyElementsOf(PermissaoAntesDoCorpo.ROTAS_SO_DO_PATROCINADOR);
        assertThat(PermissaoAntesDoCorpo.ROTAS_SO_DO_PATROCINADOR).hasSize(5);
    }

    @SafeVarargs
    private static Set<String> unir(Set<String>... conjuntos) {
        Set<String> todas = new TreeSet<>();
        for (Set<String> conjunto : conjuntos) {
            todas.addAll(conjunto);
        }
        return todas;
    }

    // ---------------------------------------------------------------------
    // Atlas: a conversa é só da marca; o relatório é da marca e da RBS
    // ---------------------------------------------------------------------

    private static final String SO_PATROCINADOR_ATLAS = "Só o Admin Patrocinador da parceria conversa com o Atlas.";

    @Test
    void rbsRecebe403NasRotasDaConversaComOAtlasQualquerQueSejaOCorpoOuOId() {
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        List<Chamada> chamadas = List.of(
                new Chamada(HttpMethod.POST, "/api/parcerias/" + parceria + "/atlas/conversas"),
                new Chamada(HttpMethod.POST, "/api/parcerias/999999/atlas/conversas"),
                new Chamada(HttpMethod.POST, "/api/atlas/conversas/1/mensagens"),
                new Chamada(HttpMethod.POST, "/api/atlas/conversas/999999/mensagens"),
                new Chamada(HttpMethod.POST, "/api/atlas/solicitacoes/1/respostas"),
                new Chamada(HttpMethod.POST, "/api/atlas/solicitacoes/999999/respostas"));
        List<String> corpos = List.of("", "{}", "isto nao e json", "[]", "{\"texto\":\"\"}", "{\"acao\":\"talvez\"}",
                "{\"atalho\":\"MONTAR_RELATORIO\",\"modelo\":\"ATIVACAO_EVENTO\"}");

        for (Chamada chamada : chamadas) {
            for (String corpo : corpos) {
                MvcTestResult resposta = chamar(rbs, chamada, corpo);
                assertThat(resposta).as(chamada + " com corpo [" + corpo + "]").hasStatus(HttpStatus.FORBIDDEN);
                assertThat(lerJson(resposta, "$.detail")).isEqualTo(SO_PATROCINADOR_ATLAS);
            }
        }
        for (String uri : List.of("/api/parcerias/" + parceria + "/atlas/conversas", "/api/parcerias/999999/atlas/conversas",
                "/api/atlas/conversas/1", "/api/atlas/conversas/999999")) {
            MvcTestResult resposta = rbs.get(uri);
            assertThat(resposta).as(uri).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo(SO_PATROCINADOR_ATLAS);
        }
    }

    @Test
    void patrocinadorContinuaRecebendoAValidacaoDoCorpoNaConversaComOAtlas() {
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);

        assertThat(amarelo.post("/api/parcerias/" + parceria + "/atlas/conversas", "isto nao e json")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(amarelo.post("/api/parcerias/" + parceria + "/atlas/conversas", "{\"atalho\":\"OUTRO\",\"modelo\":\"X\"}"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void rbsLeORelatorioDoAtlasEAListaDaParceria() {
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        assertThat(rbs.get("/api/parcerias/" + parceria + "/atlas/relatorios")).hasStatus(HttpStatus.OK);
        assertThat(rbs.get("/api/atlas/relatorios/999999")).hasStatus(HttpStatus.NOT_FOUND);
    }
}
