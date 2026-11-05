package br.com.rbs.portal.atlas.busca;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.EmbeddingsControlavel;
import br.com.rbs.portal.atlas.EmbeddingsControlavelConfig;
import br.com.rbs.portal.atlas.TesteComLlmRoteirizado;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.Escopo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * [ATLAS] [ISOLAMENTO] A busca de teste da RBS (rota 61, M4; critério bloqueante pelo isolamento): a RBS escolhe uma parceria e uma
 * consulta e vê, sem o modelo, as duas listas com as posições, o RRF e os documentos que a parceria enxerga. Passa pelo mesmo caminho
 * do modelo (a ConsultaAosDocumentos e a BuscaHibrida, como {@code atlas_ro}, com a RLS), então confere o que cada marca veria: nenhum
 * trecho de fora do escopo da parceria escolhida aparece, em nenhuma das listas. Só a RBS chama; é leitura e não grava nada. Usa o
 * kit confirmado no banco de teste. Não é {@code @Transactional}: a busca roda numa transação nova e só enxerga o que está confirmado.
 */
@TesteComLlmRoteirizado
@Import(EmbeddingsControlavelConfig.class)
@TestPropertySource(properties = {"atlas.diagnostico.ligado=true", "atlas.diagnostico.marca=true", "atlas.prazo-do-resumo=PT0.5S",
        "atlas.relatorio.ligado=false"})
class BuscaDeTesteRotaTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String ROXO = "marketing@roxo.example";
    private static final String VERDE = "marketing@verde.example";
    private static final String SO_RBS = "Esta operação é restrita à equipe da RBS.";
    private static final String ROTA = "/api/atlas/busca-teste";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    EmbeddingsControlavel embeddings;

    @Autowired
    ConsultaAosDocumentos consultas;

    @BeforeEach
    void zerar() {
        embeddings.limpar();
    }

    long parceria(String cliente) {
        return SessaoDeApi.parceriaDe2027(jdbc, cliente);
    }

    static String corpoDa(Object parceriaId, Object consulta, String tipo) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        if (parceriaId != null) {
            corpo.put("parceriaId", parceriaId);
        }
        if (consulta != null) {
            corpo.put("consulta", consulta);
        }
        if (tipo != null) {
            corpo.put("tipo", tipo);
        }
        return AtlasJson.escrever(corpo);
    }

    MvcTestResult buscar(SessaoDeApi pessoa, long parceriaId, String consulta) {
        return pessoa.post(ROTA, corpoDa(parceriaId, consulta, null));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> lista(MvcTestResult resposta, String campo) {
        return (List<Map<String, Object>>) lerLista(resposta, "$." + campo).stream().map(o -> (Map<String, Object>) o).toList();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mapa(MvcTestResult resposta, String caminho) {
        return (Map<String, Object>) lerJson(resposta, caminho);
    }

    static List<String> titulos(MvcTestResult resposta) {
        return lista(resposta, "visiveis").stream().map(d -> (String) d.get("titulo") + " v" + d.get("versao")).toList();
    }

    /** Todo trecho de toda lista é de um documento que a parceria enxerga: indexado, da edição, e do escopo dela. */
    void exigirSoDoEscopoDa(long parceriaId, MvcTestResult resposta) {
        Set<Object> ids = new java.util.LinkedHashSet<>();
        for (String campo : List.of("vetorial", "textual", "escolhidos")) {
            lista(resposta, campo).forEach(c -> ids.add(c.get("trechoId")));
        }
        for (Object id : ids) {
            Map<String, Object> linha = jdbc.sql("""
                            SELECT d.status, d.escopo, d.parceria_id, d.cota_tipo_id, d.evento_id, t.ativo
                            FROM documento_trecho t JOIN documento d ON d.id = t.documento_id WHERE t.id = :t
                            """).param("t", ((Number) id).longValue()).query().singleRow();
            Map<String, Object> parceria = jdbc.sql("SELECT evento_id, cota_tipo_id FROM parceria WHERE id = :p").param("p", parceriaId)
                    .query().singleRow();
            assertThat(linha.get("status")).as("trecho " + id).isEqualTo("INDEXADO");
            assertThat(linha.get("ativo")).as("trecho " + id).isEqualTo(true);
            assertThat(linha.get("evento_id")).as("trecho " + id + ": edição").isEqualTo(parceria.get("evento_id"));
            switch ((String) linha.get("escopo")) {
                case "COTA" -> assertThat(linha.get("cota_tipo_id")).as("trecho " + id + ": cota").isEqualTo(parceria.get("cota_tipo_id"));
                case "PARCERIA" -> assertThat(((Number) linha.get("parceria_id")).longValue()).as("trecho " + id + ": parceria")
                        .isEqualTo(parceriaId);
                default -> {
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // O que a RBS vê
    // ---------------------------------------------------------------------

    @Test
    void aRbsVeAsDuasListasOsEscolhidosEOQueAParceriaEnxerga() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long amarelo = parceria("Cliente Amarelo");

        MvcTestResult resposta = buscar(rbs, amarelo, "prazo para informar os geradores");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(mapa(resposta, "$").keySet()).containsExactlyInAnyOrder("parceria", "consulta", "tipo", "k", "degradado",
                "motivoDoDegradado", "erroDoEmbedding", "semTermoUtil", "embeddingMs", "sqlMs", "trechosDeOutroModelo", "vetorial",
                "textual", "escolhidos", "visiveis");
        assertThat(((Number) lerJson(resposta, "$.parceria.id")).longValue()).isEqualTo(amarelo);
        assertThat(lerJson(resposta, "$.parceria.patrocinador")).isEqualTo("Cliente Amarelo");
        assertThat(lerJson(resposta, "$.parceria.edicao")).isEqualTo(2027);
        assertThat(lerJson(resposta, "$.parceria.cota")).isEqualTo("MASTER");
        assertThat(lerJson(resposta, "$.consulta")).isEqualTo("prazo para informar os geradores");
        assertThat(lerJson(resposta, "$.k")).isEqualTo(6);
        assertThat(lerJson(resposta, "$.degradado")).isEqualTo(false);
        assertThat(lerJson(resposta, "$.motivoDoDegradado")).isNull();
        assertThat(lerJson(resposta, "$.semTermoUtil")).isEqualTo(false);

        List<Map<String, Object>> vetorial = lista(resposta, "vetorial");
        List<Map<String, Object>> textual = lista(resposta, "textual");
        List<Map<String, Object>> escolhidos = lista(resposta, "escolhidos");
        assertThat(vetorial).isNotEmpty().hasSizeLessThanOrEqualTo(30);
        assertThat(textual).isNotEmpty().hasSizeLessThanOrEqualTo(30);
        assertThat(escolhidos).isNotEmpty().hasSizeLessThanOrEqualTo(6);
        assertThat(vetorial).extracting(c -> c.get("posicaoVetorial")).containsExactlyElementsOf(posicoes(vetorial.size()));
        assertThat(textual).extracting(c -> c.get("posicaoTextual")).containsExactlyElementsOf(posicoes(textual.size()));
        assertThat(vetorial).allSatisfy(c -> assertThat(c.get("distancia")).isNotNull());
        assertThat(textual).allSatisfy(c -> assertThat(c.get("nota")).isNotNull());
        assertThat(escolhidos).extracting(c -> ((Number) c.get("rrf")).doubleValue()).isSortedAccordingTo((a, b) -> Double.compare(b, a));
        assertThat(escolhidos.get(0)).containsKeys("trechoId", "documentoId", "documento", "versao", "tipo", "pagina", "secao",
                "posicaoVetorial", "distancia", "posicaoTextual", "nota", "rrf", "inicioDoTexto");
        assertThat(escolhidos.get(0).get("documento")).isEqualTo("Manual de Operações 2027");
        assertThat(escolhidos).allSatisfy(c -> assertThat((String) c.get("inicioDoTexto")).hasSizeLessThanOrEqualTo(240));
        exigirSoDoEscopoDa(amarelo, resposta);
    }

    private static List<Integer> posicoes(int total) {
        List<Integer> posicoes = new ArrayList<>();
        for (int i = 1; i <= total; i++) {
            posicoes.add(i);
        }
        return posicoes;
    }

    @Test
    void oQueCadaParceriaEnxergaMudaComAMarcaEBateComAMatrizDeDocumentos() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        List<String> doAmarelo = titulos(buscar(rbs, parceria("Cliente Amarelo"), "energia"));
        List<String> doRoxo = titulos(buscar(rbs, parceria("Cliente Roxo"), "energia"));
        List<String> doVerde = titulos(buscar(rbs, parceria("Cliente Verde"), "energia"));

        assertThat(doAmarelo).contains("Manual de Operações 2027 v2", "Plano comercial · Cota Master 2027 v1", "Anexo da parceria · Cliente Amarelo v1",
                "FAQ do patrocinador 2027 v1", "Comunicado 12/2026 · Janela extra de abastecimento v1")
                .doesNotContain("Manual de Operações 2027 v1", "Manual de Operações 2026 v1", "Circular 07/2026 · Pulseiras prata v1",
                        "Plano comercial · Subcota G 2027 v1", "Plano comercial · Subcota 2027 v1", "Anexo da parceria · Cliente Roxo v1",
                        "Lista de credenciados · Cliente Verde v1", "Aviso digitalizado · pontos de apoio v1");
        assertThat(doRoxo).contains("Manual de Operações 2027 v2", "Plano comercial · Subcota G 2027 v1", "Anexo da parceria · Cliente Roxo v1")
                .doesNotContain("Plano comercial · Cota Master 2027 v1", "Anexo da parceria · Cliente Amarelo v1");
        assertThat(doVerde).contains("Manual de Operações 2027 v2", "Plano comercial · Subcota 2027 v1")
                .doesNotContain("Anexo da parceria · Cliente Amarelo v1", "Anexo da parceria · Cliente Roxo v1",
                        "Lista de credenciados · Cliente Verde v1");
    }

    @Test
    void nenhumaListaTrazTrechoDeForaDoEscopoDaParceriaEscolhidaParaNenhumaConsulta() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        List<String> consultasDeCanario = List.of("Girassol Elétrico", "Tempestade Violeta", "tema do espaço", "cortesias credenciais prazo",
                "janela extra de abastecimento", "pulseiras prata circular", "manual 2026 geradores", "lista de credenciados CPF");

        for (String cliente : List.of("Cliente Amarelo", "Cliente Roxo", "Cliente Verde")) {
            long parceria = parceria(cliente);
            for (String consulta : consultasDeCanario) {
                MvcTestResult resposta = buscar(rbs, parceria, consulta);
                assertThat(resposta).as(cliente + " / " + consulta).hasStatus(HttpStatus.OK);
                exigirSoDoEscopoDa(parceria, resposta);
            }
        }
        // o canário de cada marca aparece na busca dela e na de mais ninguém
        String doAmarelo = corpo(buscar(rbs, parceria("Cliente Amarelo"), "Girassol Elétrico")).toLowerCase();
        String doRoxo = corpo(buscar(rbs, parceria("Cliente Roxo"), "Girassol Elétrico")).toLowerCase();
        assertThat(doAmarelo).contains("anexo da parceria · cliente amarelo");
        assertThat(doRoxo).doesNotContain("anexo da parceria · cliente amarelo");
    }

    @Test
    void usaOMesmoCaminhoDoModeloEPorIssoDevolveOsMesmosEscolhidos() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long amarelo = parceria("Cliente Amarelo");
        Map<String, Object> parceriaLinha = jdbc.sql("SELECT evento_id, cota_tipo_id FROM parceria WHERE id = :p").param("p", amarelo).query()
                .singleRow();
        Escopo escopo = new Escopo(((Number) parceriaLinha.get("evento_id")).longValue(), ((Number) parceriaLinha.get("cota_tipo_id")).intValue(), amarelo);

        MvcTestResult resposta = buscar(rbs, amarelo, "tarifa de energia por kVA");
        var direto = consultas.consultar(escopo, "tarifa de energia por kVA", null).resultado();

        assertThat(lista(resposta, "escolhidos")).extracting(c -> ((Number) c.get("trechoId")).longValue())
                .containsExactlyElementsOf(direto.escolhidos().stream().map(c -> c.trechoId()).toList());
    }

    @Test
    void oTipoFiltraOsDocumentosDasListasMasNaoAListaDoQueAParceriaEnxerga() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long amarelo = parceria("Cliente Amarelo");

        MvcTestResult resposta = rbs.post(ROTA, corpoDa(amarelo, "cortesias credenciais prazo", "FAQ"));

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.tipo")).isEqualTo("FAQ");
        assertThat(lista(resposta, "escolhidos")).isNotEmpty().allSatisfy(c -> assertThat(c.get("tipo")).isEqualTo("FAQ"));
        assertThat(lista(resposta, "textual")).allSatisfy(c -> assertThat(c.get("tipo")).isEqualTo("FAQ"));
        assertThat(lista(resposta, "vetorial")).allSatisfy(c -> assertThat(c.get("tipo")).isEqualTo("FAQ"));
        assertThat(lista(resposta, "visiveis").size()).as("o que a parceria enxerga não depende do tipo pedido").isGreaterThan(1);
    }

    @Test
    void semEmbeddingsAbuscaSegueSoComATextualEDizPorQue() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long amarelo = parceria("Cliente Amarelo");

        embeddings.desligado(true);
        MvcTestResult desligados = buscar(rbs, amarelo, "prazo para informar os geradores");
        embeddings.limpar();
        embeddings.falharNaConsulta(true);
        MvcTestResult falhou = buscar(rbs, amarelo, "prazo para informar os geradores");

        assertThat(desligados).hasStatus(HttpStatus.OK);
        assertThat(lerJson(desligados, "$.degradado")).isEqualTo(true);
        assertThat(lerJson(desligados, "$.motivoDoDegradado")).isEqualTo("EMBEDDINGS_DESLIGADOS");
        assertThat(lista(desligados, "vetorial")).isEmpty();
        assertThat(lista(desligados, "textual")).isNotEmpty();
        assertThat(lista(desligados, "escolhidos")).isNotEmpty();
        assertThat(falhou).hasStatus(HttpStatus.OK);
        assertThat(lerJson(falhou, "$.degradado")).isEqualTo(true);
        assertThat(lerJson(falhou, "$.motivoDoDegradado")).isEqualTo("EMBEDDING_FALHOU");
        assertThat((String) lerJson(falhou, "$.erroDoEmbedding")).isNotBlank().matches("[A-Za-z]+");
        assertThat(lista(falhou, "vetorial")).isEmpty();
        exigirSoDoEscopoDa(amarelo, falhou);
    }

    @Test
    void consultaSoDePalavrasVaziasVolta200ComAsListasVaziasEOAvisoDeSemTermoUtil() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult resposta = buscar(rbs, parceria("Cliente Amarelo"), "de a o que para");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.semTermoUtil")).isEqualTo(true);
        assertThat(lista(resposta, "vetorial")).isEmpty();
        assertThat(lista(resposta, "textual")).isEmpty();
        assertThat(lista(resposta, "escolhidos")).isEmpty();
        assertThat(lista(resposta, "visiveis")).as("o que a parceria enxerga vem mesmo assim").isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"geradores' OR '1'='1", "a & b | !c", "geradores:*", "<->", "'; DROP TABLE documento; --", "\"energia\"", "\\", "ração", "ａｂｃ"})
    void consultaHostilaVolta200SemEstourarEOEscopoSeMantem(String consulta) {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long roxo = parceria("Cliente Roxo");

        MvcTestResult resposta = buscar(rbs, roxo, consulta);

        assertThat(resposta).as(consulta).hasStatus(HttpStatus.OK);
        exigirSoDoEscopoDa(roxo, resposta);
        assertThat(contar("SELECT count(*) FROM documento")).isGreaterThanOrEqualTo(13);
    }

    private long contar(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    @Test
    void aBuscaDeTesteNaoGravaNada() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long amarelo = parceria("Cliente Amarelo");
        List<String> tabelas = List.of("historico", "atlas_conversa", "atlas_mensagem", "atlas_tool_call", "atlas_traco", "atlas_fonte",
                "documento", "documento_trecho", "arquivo");
        Map<String, Long> antes = new LinkedHashMap<>();
        tabelas.forEach(t -> antes.put(t, contar("SELECT count(*) FROM " + t)));

        buscar(rbs, amarelo, "prazo para informar os geradores");
        buscar(rbs, amarelo, "de a o que para");
        rbs.post(ROTA, corpoDa(amarelo, "x".repeat(301), null));

        Map<String, Long> depois = new LinkedHashMap<>();
        tabelas.forEach(t -> depois.put(t, contar("SELECT count(*) FROM " + t)));
        assertThat(depois).isEqualTo(antes);
    }

    // ---------------------------------------------------------------------
    // Quem pode chamar, e o que ela exige
    // ---------------------------------------------------------------------

    @Test
    void aMarcaRecebe403QualquerQueSejaOCorpoEAntesDeLerEleEAsemSessaoRecebe401() {
        long amarelo = parceria("Cliente Amarelo");
        long roxo = parceria("Cliente Roxo");
        for (String email : List.of(AMARELO, ROXO, VERDE)) {
            SessaoDeApi marca = SessaoDeApi.entrar(mvc, email);
            for (String corpoDoPedido : List.of(corpoDa(amarelo, "geradores", null), corpoDa(roxo, "geradores", null), "{}", "", "isto nao e json",
                    corpoDa(999999, "geradores", null), corpoDa(amarelo, "x".repeat(301), "SEGREDO"))) {
                MvcTestResult resposta = marca.post(ROTA, corpoDoPedido);
                assertThat(resposta).as(email + " [" + corpoDoPedido + "]").hasStatus(HttpStatus.FORBIDDEN);
                assertThat(lerJson(resposta, "$.detail")).isEqualTo(SO_RBS);
            }
        }
        assertThat(SessaoDeApi.anonima(mvc).post(ROTA, corpoDa(amarelo, "geradores", null))).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void semOTokenCsrfARbsRecebe403ComoEmTodaRotaDeEscrita() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        assertThat(rbs.postSemCsrf(ROTA, corpoDa(parceria("Cliente Amarelo"), "geradores", null))).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void corpoQuebradoOuAusenteDa400() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        assertThat(rbs.post(ROTA, "isto nao e json")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(rbs.post(ROTA, "")).hasStatus(HttpStatus.BAD_REQUEST);
        // JSON válido com texto num campo numérico é erro do campo: 422 com o caminho dele
        assertThat(rbs.post(ROTA, "{\"parceriaId\":\"abc\",\"consulta\":\"x\"}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void camposAusentesOuInvalidosDao422ComOCampoCerto() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long amarelo = parceria("Cliente Amarelo");

        MvcTestResult vazio = rbs.post(ROTA, "{}");
        assertThat(vazio).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(vazio, "$.campos.parceriaId")).isEqualTo("Informe a parceria (parceriaId).");
        assertThat(lerJson(vazio, "$.campos.consulta")).isEqualTo("Escreva a consulta.");

        MvcTestResult inexistente = rbs.post(ROTA, corpoDa(999999, "geradores", null));
        assertThat(inexistente).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(inexistente, "$.campos.parceriaId")).isEqualTo("Esta parceria não existe.");

        MvcTestResult emBranco = rbs.post(ROTA, corpoDa(amarelo, "   ", null));
        assertThat(lerJson(emBranco, "$.campos.consulta")).isEqualTo("Escreva a consulta.");

        MvcTestResult longa = rbs.post(ROTA, corpoDa(amarelo, "g".repeat(301), null));
        assertThat(longa).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(longa, "$.campos.consulta")).isEqualTo("A consulta tem no máximo 300 caracteres.");
        assertThat(rbs.post(ROTA, corpoDa(amarelo, "geradores ".repeat(30).strip(), null))).hasStatus(HttpStatus.OK);

        MvcTestResult nulo = rbs.post(ROTA, corpoDa(amarelo, "gera\u0000dores", null));
        assertThat(nulo).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(nulo, "$.campos.consulta")).isEqualTo("A consulta tem caracteres inválidos.");

        MvcTestResult tipo = rbs.post(ROTA, corpoDa(amarelo, "geradores", "faq"));
        assertThat(tipo).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) lerJson(tipo, "$.campos.tipo")).startsWith("Tipo inválido. Use ");
        assertThat(rbs.post(ROTA, corpoDa(amarelo, "geradores", "   "))).as("tipo em branco é sem tipo").hasStatus(HttpStatus.OK);
    }

    @Test
    void todosOsErrosJuntosVoltamNaMesmaResposta() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult resposta = rbs.post(ROTA, corpoDa(999999, "g".repeat(301), "SEGREDO"));

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(mapa(resposta, "$.campos").keySet()).containsExactlyInAnyOrder("parceriaId", "consulta", "tipo");
    }
}
