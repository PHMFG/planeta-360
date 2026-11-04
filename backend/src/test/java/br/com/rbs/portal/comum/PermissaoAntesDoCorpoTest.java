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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * [PERMISSAO] O perfil (e, no envio das perguntas, a parceria e a aba liberada) é conferido ANTES de o Spring ler o corpo e
 * as validações: o patrocinador recebe 403 numa rota da RBS qualquer que seja o corpo, e não 422 (ou 400) de campo.
 * Atende o item 3 da Entrega 2. Cada teste é desfeito no fim (@Transactional). As rotas de debug da chave do modelo (só da RBS) só
 * existem com {@code atlas.debug.chaves=true}, que no perfil test vem desligado: por isso o teste de classificação liga a propriedade
 * (é o mesmo contexto do DebugDoLlmTest).
 */
@TesteDeIntegracao
@TestPropertySource(properties = "atlas.debug.chaves=true")
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

    @Test
    void headContaComoGetEPatrocinadorRecebe403NasRotasDaRbsMesmoComFiltroInvalido() {
        SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);

        for (String uri : List.of("/api/atlas/diagnostico/turnos?tamanho=999", "/api/atlas/diagnostico/turnos?de=ontem",
                "/api/historico?tamanho=999", "/api/historico?pagina=-1", "/api/historico.csv?q=" + "x".repeat(200),
                "/api/entregas?tamanho=999", "/api/cargas?pagina=-1", "/api/relatorios?status=INVENTADO", "/api/atlas/provedores")) {
            assertThat(amarelo.head(uri)).as("HEAD " + uri).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(amarelo.get(uri)).as("GET " + uri).hasStatus(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void headDaRbsContinuaComAValidacaoDoGet() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        assertThat(rbs.head("/api/historico?tamanho=999")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(rbs.head("/api/historico")).hasStatus(HttpStatus.OK);
    }

    @Test
    void rbsRecebe403NoHeadDoDiagnosticoDaConversa() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        assertThat(rbs.head("/api/atlas/conversas/1/diagnostico")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(rbs.head("/api/parcerias/1/atlas/conversas")).hasStatus(HttpStatus.FORBIDDEN);
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
            "POST /api/parcerias/{id}/dados", "POST /api/parcerias/{id}/dados-informados/{chave}/arquivo",
            "GET /api/atlas/relatorios/{id}", "GET /api/parcerias/{id}/atlas/relatorios",
            // Rota 56: a RBS vê o documento inteiro; a marca, pelo escopo dela, só o que a rota mostra. Rotas 59 e 60: o visualizador
            // (o arquivo e um trecho inteiro), com a mesma regra de acesso.
            "GET /api/documentos/{id}", "GET /api/documentos/{id}/conteudo", "GET /api/documentos/{id}/trechos/{trechoId}",
            // As cortesias (rotas 80, 83, 84 e 85): a marca da parceria (com a aba aberta) e a RBS; a cota e o prazo (81 e 82) são só da RBS.
            "GET /api/parcerias/{id}/cortesias", "POST /api/parcerias/{id}/cortesias/destinacoes",
            "PATCH /api/parcerias/{id}/cortesias/destinacoes/{destId}", "DELETE /api/parcerias/{id}/cortesias/destinacoes/{destId}",
            // O contrato e as credenciais (rotas 74, 75 e 77): a marca e a RBS; a escrita da RBS (76, 78 e 79) está na lista dela.
            "GET /api/parcerias/{id}/contrato", "PUT /api/parcerias/{id}/contrato/signatario", "GET /api/parcerias/{id}/credenciais");

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
                        PermissaoAntesDoCorpo.ROTAS_SO_DO_PATROCINADOR, PermissaoAntesDoCorpo.ROTAS_DA_CONVERSA_DO_ATLAS,
                        ROTAS_DO_PATROCINADOR));
        assertThat(PermissaoAntesDoCorpo.ROTAS_SO_DA_RBS).as("uma rota não é das listas do patrocinador")
                .doesNotContainAnyElementsOf(PermissaoAntesDoCorpo.ROTAS_SO_DO_PATROCINADOR)
                .doesNotContainAnyElementsOf(PermissaoAntesDoCorpo.ROTAS_DA_CONVERSA_DO_ATLAS);
        assertThat(PermissaoAntesDoCorpo.ROTAS_SO_DO_PATROCINADOR).doesNotContainAnyElementsOf(PermissaoAntesDoCorpo.ROTAS_DA_CONVERSA_DO_ATLAS);
        assertThat(PermissaoAntesDoCorpo.ROTAS_SO_DO_PATROCINADOR).hasSize(4);
        assertThat(PermissaoAntesDoCorpo.ROTAS_DA_CONVERSA_DO_ATLAS).hasSize(3);
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
    // Documentos do Atlas (rotas 54, 55, 57 e 58): só da RBS, qualquer que seja o corpo, o filtro ou o id; a 56 é das duas pontas
    // ---------------------------------------------------------------------

    @Test
    void patrocinadorRecebe403NasRotasDeDocumentoDaRbsQualquerQueSejaOCorpoOFiltroOuOId() {
        SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);

        for (String corpo : List.of("", "{}", "isto nao e json", "{\"acao\":\"APAGAR\"}", "{\"acao\":\"REVOGAR\"}")) {
            for (String uri : List.of("/api/documentos/1/status", "/api/documentos/999999/status", "/api/documentos/abc/status")) {
                MvcTestResult resposta = amarelo.patch(uri, corpo);
                assertThat(resposta).as("PATCH " + uri + " [" + corpo + "]").hasStatus(HttpStatus.FORBIDDEN);
                assertThat(lerJson(resposta, "$.detail")).isEqualTo(SO_RBS);
            }
        }
        for (String uri : List.of("/api/documentos", "/api/documentos?status=TALVEZ", "/api/documentos?tamanho=999", "/api/documentos?eventoId=abc",
                "/api/documentos/1/trechos", "/api/documentos/999999/trechos", "/api/documentos/abc/trechos", "/api/documentos/1/trechos?tamanho=999")) {
            MvcTestResult resposta = amarelo.get(uri);
            assertThat(resposta).as("GET " + uri).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo(SO_RBS);
            assertThat(amarelo.head(uri)).as("HEAD " + uri).hasStatus(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void aRota56EDasDuasPontasEAMarcaDe404NoQueNaoEDela() {
        SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        assertThat(amarelo.get("/api/documentos/999999")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(rbs.get("/api/documentos/999999")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(amarelo.get("/api/documentos")).hasStatus(HttpStatus.FORBIDDEN);
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
    void rbsRecebe403NoDiagnosticoDaConversaQualquerQueSejaOId() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        for (String uri : List.of("/api/atlas/conversas/1/diagnostico", "/api/atlas/conversas/999999/diagnostico")) {
            MvcTestResult resposta = rbs.get(uri);
            assertThat(resposta).as(uri).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo(SO_PATROCINADOR_ATLAS);
        }
    }

    // ---------------------------------------------------------------------
    // O diagnóstico (rotas 50 a 53) e o debug da chave: só da RBS, qualquer que seja o corpo, o filtro ou o id
    // ---------------------------------------------------------------------

    @Test
    void patrocinadorRecebe403NoDiagnosticoDaRbsEmNoDebugDaChaveMesmoComFiltroOuCorpoInvalido() {
        SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);

        for (String uri : List.of("/api/atlas/diagnostico/turnos", "/api/atlas/diagnostico/turnos?tamanho=999",
                "/api/atlas/diagnostico/turnos?pagina=-1", "/api/atlas/diagnostico/turnos?parceriaId=abc",
                "/api/atlas/diagnostico/turnos/" + java.util.UUID.randomUUID(), "/api/atlas/diagnostico/turnos/nao-e-um-uuid",
                "/api/atlas/provedores", "/api/debug/llm")) {
            MvcTestResult resposta = amarelo.get(uri);
            assertThat(resposta).as(uri).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo(SO_RBS);
        }
        for (String corpo : List.of("", "{}", "isto nao e json", "{\"chave\":\"x\"}")) {
            assertThat(amarelo.post("/api/atlas/provedores/llm/teste", corpo)).as("teste do llm com [" + corpo + "]").hasStatus(HttpStatus.FORBIDDEN);
            assertThat(amarelo.post("/api/atlas/provedores/inexistente/teste", corpo)).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(amarelo.put("/api/debug/llm", corpo)).as("PUT do debug com [" + corpo + "]").hasStatus(HttpStatus.FORBIDDEN);
            assertThat(amarelo.post("/api/debug/llm/teste", corpo)).hasStatus(HttpStatus.FORBIDDEN);
        }
        assertThat(amarelo.delete("/api/debug/llm")).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void rbsLeORelatorioDoAtlasEAListaDaParceria() {
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        assertThat(rbs.get("/api/parcerias/" + parceria + "/atlas/relatorios")).hasStatus(HttpStatus.OK);
        assertThat(rbs.get("/api/atlas/relatorios/999999")).hasStatus(HttpStatus.NOT_FOUND);
    }
}
