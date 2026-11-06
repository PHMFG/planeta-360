package br.com.rbs.portal.importacao;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.ApoioDeTeste;
import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.ProvedorRoteirizado;
import br.com.rbs.portal.atlas.RoteiroDeLlmConfig;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.ChamadaDeFerramenta;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * As rotas da importação (109 a 116 e 112b) contra o banco de teste: a planilha da TV e a padrão da RBS batem com o gabarito,
 * a linha de total não vira linha, a marca fora do alcance é recusada, a conversa com dado pessoal não chama o modelo, a
 * confirmação grava as veiculações e a retirada as apaga.
 */
@TesteDeIntegracao
@Import(RoteiroDeLlmConfig.class)
class ImportacaoTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String CORPUS = "importacao/corpus/";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProvedorRoteirizado roteiro;

    @AfterEach
    void limpar() {
        jdbc.sql("DELETE FROM veiculacao WHERE importacao_linha_id IS NOT NULL").update();
        jdbc.sql("DELETE FROM importacao").update();
        jdbc.sql("DELETE FROM documento_trecho WHERE documento_id IN "
                + "(SELECT id FROM documento WHERE chave LIKE 'importacao-%')").update();
        jdbc.sql("DELETE FROM documento WHERE chave LIKE 'importacao-%'").update();
        jdbc.sql("DELETE FROM stg_veiculacao WHERE carga_id IN (SELECT id FROM carga WHERE arquivo_id IN "
                + "(SELECT id FROM arquivo WHERE categoria = 'IMPORTACAO'))").update();
        jdbc.sql("DELETE FROM carga WHERE arquivo_id IN (SELECT id FROM arquivo WHERE categoria = 'IMPORTACAO')").update();
        jdbc.sql("DELETE FROM arquivo WHERE categoria = 'IMPORTACAO'").update();
        jdbc.sql("DELETE FROM arquivo WHERE categoria = 'DOCUMENTO' AND nome_original IN "
                + "('tv-novembro.xlsx', 'padrao-rbs-outubro.xlsx', 'conversa-whatsapp.txt')").update();
    }

    private long evento2027() {
        return jdbc.sql("SELECT id FROM evento WHERE edicao = 2027").query(Long.class).single();
    }

    private MvcTestResult enviar(SessaoDeApi sessao, String nome, byte[] bytes, long eventoId, String alcance, String cota,
                                 Long parceriaId, String destino) {
        var csrf = ApoioDeTeste.pedirCsrf(mvc);
        var pedido = mvc.post().uri("/api/importacoes").multipart()
                .file(new MockMultipartFile("arquivo", nome, "application/octet-stream", bytes))
                .param("eventoId", String.valueOf(eventoId)).param("alcance", alcance)
                .session(sessao.sessao()).cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token());
        if (cota != null) {
            pedido.param("cota", cota);
        }
        if (parceriaId != null) {
            pedido.param("parceriaId", String.valueOf(parceriaId));
        }
        if (destino != null) {
            pedido.param("destino", destino);
        }
        return pedido.exchange();
    }

    private static byte[] corpus(String nome) throws IOException {
        try (InputStream entrada = new ClassPathResource(CORPUS + nome).getInputStream()) {
            return entrada.readAllBytes();
        }
    }

    private static JsonNode gabarito() throws IOException {
        try (InputStream entrada = new ClassPathResource(CORPUS + "gabarito.json").getInputStream()) {
            return JSON.readTree(entrada);
        }
    }

    private static JsonNode arquivoDoGabarito(String nome) throws IOException {
        for (JsonNode arquivo : gabarito().path("arquivos")) {
            if (nome.equals(arquivo.path("arquivo").asString())) {
                return arquivo;
            }
        }
        throw new IllegalArgumentException("Arquivo fora do gabarito: " + nome);
    }

    private void mapearTv() {
        roteiro.limpar().passo(pedido -> {
            String texto = pedido.mensagens().get(1).conteudo();
            String args = texto.contains("Contratado") ? """
                    {"cabecalho":5,"primeiraLinha":6,
                     "marcas":[{"coluna":"F","patrocinador":"Cliente Amarelo","linha":5},
                               {"coluna":"G","patrocinador":"Cliente Roxo","linha":5},
                               {"coluna":"H","patrocinador":"Cliente Verde","linha":5}],
                     "campos":{"veiculo":{"coluna":"A","linha":1},"formato":{"coluna":"B"},"espaco_programa":{"coluna":"A"},
                               "mes":{"coluna":"A","linha":3},"impacto":{"coluna":"E"}},
                     "ignoradas":[{"linha":1,"motivo":"Título"},{"linha":2,"motivo":"Título"},{"linha":3,"motivo":"Mês"},
                                  {"linha":4,"motivo":"Linha em branco"},{"linha":5,"motivo":"Cabeçalho"},
                                  {"linha":15,"motivo":"Linha de total"}]}
                    """ : """
                    {"cabecalho":1,"primeiraLinha":2,
                     "marcas":[{"coluna":"E","patrocinador":"Cliente Amarelo","linha":1},
                               {"coluna":"F","patrocinador":"Cliente Roxo","linha":1},
                               {"coluna":"G","patrocinador":"Cliente Verde","linha":1}],
                     "campos":{"mes":{"coluna":"A"},"veiculo":{"coluna":"B"},"formato":{"coluna":"C"},"impacto":{"coluna":"D"}},
                     "ignoradas":[{"linha":1,"motivo":"Cabeçalho"}]}
                    """;
            return new RespostaDoLlm(null, List.of(new ChamadaDeFerramenta("m1", "mapear_colunas", args)), null);
        });
    }

    private Map<String, Object> primeiraLinha(MvcTestResult detalhe) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itens = (List<Map<String, Object>>) (List<?>) lerLista(detalhe, "$.itens");
        assertThat(itens).as("itens da prévia").isNotEmpty();
        return itens.getFirst();
    }

    @Test
    void aPlanilhaDaTvBateComOGabaritoEALinhaDeTotalNaoViraLinha() throws IOException {
        mapearTv();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        MvcTestResult criada = enviar(rbs, "tv-novembro.xlsx", corpus("tv-novembro.xlsx"), evento2027(), "EDICAO", null, null,
                "VEICULACAO");
        assertThat(criada).hasStatus(HttpStatus.ACCEPTED);
        assertThat(lerJson(criada, "$.situacao")).isEqualTo("LENDO");
        long id = ((Number) lerJson(criada, "$.id")).longValue();
        assertThat(roteiro.pedidos()).as("o modelo foi chamado para mapear a planilha").hasSize(1);

        MvcTestResult detalhe = rbs.get("/api/importacoes/" + id);
        assertThat(detalhe).hasStatus(HttpStatus.OK);
        assertThat(lerJson(detalhe, "$.situacao")).isEqualTo("EM_PREVIA");
        assertThat(lerJson(detalhe, "$.documentoId")).as("o arquivo também é fonte").isNotNull();

        JsonNode esperado = arquivoDoGabarito("tv-novembro.xlsx");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itens = (List<Map<String, Object>>) (List<?>) lerLista(detalhe, "$.itens");
        assertThat(itens).as("linhas da tv").hasSize(esperado.path("linhas").size());
        for (int i = 0; i < esperado.path("linhas").size(); i++) {
            JsonNode linha = esperado.path("linhas").get(i);
            Map<String, Object> item = itens.get(i);
            @SuppressWarnings("unchecked")
            Map<String, Object> dados = (Map<String, Object>) item.get("dados");
            @SuppressWarnings("unchecked")
            Map<String, Map<String, String>> origem = (Map<String, Map<String, String>>) item.get("origem");
            for (var campo : linha.path("dados").properties()) {
                assertThat(dados.get(campo.getKey())).as("linha " + (i + 1) + ": " + campo.getKey())
                        .isEqualTo(valorEsperado(campo.getValue()));
            }
            for (var campo : linha.path("origem").properties()) {
                assertThat(origem.get(campo.getKey()).get("localizador")).as("origem de " + campo.getKey())
                        .isEqualTo(campo.getValue().asString());
            }
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> naoEntendidos = (List<Map<String, Object>>) (List<?>) lerLista(detalhe, "$.naoEntendidos");
        assertThat(naoEntendidos).anySatisfy(n -> assertThat(String.valueOf(n.get("motivo"))).containsIgnoringCase("total"));
    }

    @Test
    void aPlanilhaPadraoDaRbsBateComOGabarito() throws IOException {
        mapearTv();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        MvcTestResult criada = enviar(rbs, "padrao-rbs-outubro.xlsx", corpus("padrao-rbs-outubro.xlsx"), evento2027(), "EDICAO",
                null, null, "VEICULACAO");
        assertThat(criada).hasStatus(HttpStatus.ACCEPTED);
        long id = ((Number) lerJson(criada, "$.id")).longValue();
        MvcTestResult detalhe = rbs.get("/api/importacoes/" + id);
        assertThat(lerJson(detalhe, "$.situacao")).isEqualTo("EM_PREVIA");

        JsonNode esperado = arquivoDoGabarito("padrao-rbs-outubro.xlsx");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itens = (List<Map<String, Object>>) (List<?>) lerLista(detalhe, "$.itens");
        assertThat(itens).hasSize(esperado.path("linhas").size());
        for (int i = 0; i < esperado.path("linhas").size(); i++) {
            JsonNode linha = esperado.path("linhas").get(i);
            @SuppressWarnings("unchecked")
            Map<String, Object> dados = (Map<String, Object>) itens.get(i).get("dados");
            @SuppressWarnings("unchecked")
            Map<String, Map<String, String>> origem = (Map<String, Map<String, String>>) itens.get(i).get("origem");
            for (var campo : linha.path("dados").properties()) {
                assertThat(dados.get(campo.getKey())).as("linha " + (i + 1) + ": " + campo.getKey())
                        .isEqualTo(valorEsperado(campo.getValue()));
            }
            for (var campo : linha.path("origem").properties()) {
                assertThat(origem.get(campo.getKey()).get("localizador")).as("origem de " + campo.getKey())
                        .isEqualTo(campo.getValue().asString());
            }
        }
    }

    private static Object valorEsperado(JsonNode valor) {
        return valor.isNumber() ? valor.numberValue() : valor.asString();
    }

    @Test
    void aConversaComDadoPessoalNaoChamaOModelo() throws IOException {
        roteiro.limpar();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        MvcTestResult criada = enviar(rbs, "conversa-whatsapp.txt", corpus("conversa-whatsapp.txt"), evento2027(), "EDICAO", null,
                null, "SO_CONSULTA");
        assertThat(criada).hasStatus(HttpStatus.ACCEPTED);
        long id = ((Number) lerJson(criada, "$.id")).longValue();
        MvcTestResult detalhe = rbs.get("/api/importacoes/" + id);
        assertThat(lerJson(detalhe, "$.situacao")).isEqualTo("EM_PREVIA");
        assertThat(roteiro.pedidos()).as("o dado pessoal vem antes do modelo").isEmpty();
        assertThat(lerLista(detalhe, "$.suspeitas")).as("a prévia diz onde está a suspeita").isNotEmpty();
    }

    @Test
    void aMarcaForaDoAlcanceEhRecusada() throws IOException {
        mapearTv();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long amarelo = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        MvcTestResult criada = enviar(rbs, "padrao-rbs-outubro.xlsx", corpus("padrao-rbs-outubro.xlsx"), evento2027(), "PARCERIA",
                null, amarelo, "VEICULACAO");
        assertThat(criada).hasStatus(HttpStatus.ACCEPTED);
        long id = ((Number) lerJson(criada, "$.id")).longValue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itens = (List<Map<String, Object>>) (List<?>) lerLista(rbs.get("/api/importacoes/" + id), "$.itens");
        assertThat(itens).filteredOn(i -> "Cliente Roxo".equals(((Map<?, ?>) i.get("dados")).get("patrocinador")))
                .isNotEmpty()
                .allSatisfy(i -> {
                    assertThat(i.get("situacao")).isEqualTo("RECUSADA");
                    assertThat(String.valueOf(i.get("motivo"))).contains("fora do alcance");
                });
    }

    @Test
    void confirmarGravaAsVeiculacoesERetirarApaga() throws IOException {
        mapearTv();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        MvcTestResult criada = enviar(rbs, "tv-novembro.xlsx", corpus("tv-novembro.xlsx"), evento2027(), "EDICAO", null, null,
                "VEICULACAO");
        long id = ((Number) lerJson(criada, "$.id")).longValue();
        assertThat(rbs.patch("/api/importacoes/" + id + "/linhas", "{\"situacao\":\"ACEITA\",\"todas\":true}")).hasStatus(HttpStatus.OK);
        MvcTestResult confirmada = rbs.post("/api/importacoes/" + id + "/confirmar", "{}");
        assertThat(confirmada).hasStatus(HttpStatus.OK);
        assertThat(lerJson(confirmada, "$.situacao")).isEqualTo("CONFIRMADA");
        assertThat(lerJson(confirmada, "$.resultado.inseridas")).as("veiculações gravadas").isNotNull();
        long gravadas = jdbc.sql("""
                        SELECT count(*) FROM veiculacao v
                        JOIN importacao_linha l ON l.id = v.importacao_linha_id WHERE l.importacao_id = :id
                        """).param("id", id).query(Long.class).single();
        assertThat(gravadas).isPositive();

        MvcTestResult retirada = rbs.post("/api/importacoes/" + id + "/retirar", "{}");
        assertThat(retirada).hasStatus(HttpStatus.OK);
        assertThat(lerJson(retirada, "$.situacao")).isEqualTo("RETIRADA");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM veiculacao v
                        JOIN importacao_linha l ON l.id = v.importacao_linha_id WHERE l.importacao_id = :id
                        """).param("id", id).query(Long.class).single()).isZero();
    }

    @Test
    void asTransicoesProibidasDao409EAsPermissoesSeguemOContrato() throws IOException {
        mapearTv();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);
        MvcTestResult criada = enviar(rbs, "tv-novembro.xlsx", corpus("tv-novembro.xlsx"), evento2027(), "EDICAO", null, null,
                "VEICULACAO");
        long id = ((Number) lerJson(criada, "$.id")).longValue();
        assertThat(rbs.post("/api/importacoes/" + id + "/retirar", "{}")).hasStatus(HttpStatus.CONFLICT);
        var csrf = ApoioDeTeste.pedirCsrf(mvc);
        assertThat(mvc.post().uri("/api/importacoes").multipart().param("eventoId", String.valueOf(evento2027()))
                .param("alcance", "EDICAO").session(amarelo.sessao()).cookie(csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token()).exchange()).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(amarelo.get("/api/importacoes")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(amarelo.get("/api/importacoes/" + id)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(SessaoDeApi.anonima(mvc).get("/api/importacoes")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aOrigemDaLinhaAbreACelulaNoVisualizador() throws IOException {
        mapearTv();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        MvcTestResult criada = enviar(rbs, "tv-novembro.xlsx", corpus("tv-novembro.xlsx"), evento2027(), "EDICAO", null, null,
                "VEICULACAO");
        long id = ((Number) lerJson(criada, "$.id")).longValue();
        MvcTestResult detalhe = rbs.get("/api/importacoes/" + id);
        long linhaId = ((Number) lerJson(detalhe, "$.itens[0].id")).longValue();

        MvcTestResult origem = rbs.get("/api/importacoes/" + id + "/origem/" + linhaId + "?campo=quantidade");
        assertThat(origem).hasStatus(HttpStatus.OK);
        assertThat(lerJson(origem, "$.tipo")).isEqualTo("XLSX");
        assertThat(lerJson(origem, "$.localizador")).isEqualTo("Entregas Nov-26!F6");
        assertThat(lerJson(origem, "$.planilha.aba")).isEqualTo("Entregas Nov-26");
        assertThat(lerJson(origem, "$.planilha.celula")).isEqualTo("F6");
        assertThat(lerLista(origem, "$.planilha.grade")).isNotEmpty();
    }

    @Test
    void editarUmValorPassaALinhaAEditada() throws IOException {
        mapearTv();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        MvcTestResult criada = enviar(rbs, "tv-novembro.xlsx", corpus("tv-novembro.xlsx"), evento2027(), "EDICAO", null, null,
                "VEICULACAO");
        long id = ((Number) lerJson(criada, "$.id")).longValue();
        long linhaId = ((Number) lerJson(rbs.get("/api/importacoes/" + id), "$.itens[0].id")).longValue();

        MvcTestResult editada = rbs.patch("/api/importacoes/" + id + "/linhas/" + linhaId,
                "{\"dados\":{\"link\":\"https://example.com/post\"}}");
        assertThat(editada).hasStatus(HttpStatus.OK);
        assertThat(lerJson(editada, "$.situacao")).isEqualTo("EDITADA");
        assertThat(lerJson(editada, "$.dados.link")).isEqualTo("https://example.com/post");
        assertThat(lerJson(editada, "$.editadaPor.nome")).isEqualTo("Equipe B2B RBS");

        MvcTestResult recusada = rbs.patch("/api/importacoes/" + id + "/linhas/" + linhaId, "{\"dados\":{\"quantidade\":999}}");
        assertThat(recusada).hasStatus(HttpStatus.OK);
        assertThat(lerJson(recusada, "$.situacao")).isEqualTo("RECUSADA");
        assertThat(String.valueOf(lerJson(recusada, "$.motivo"))).contains("não aparece na fonte");
    }

    @Test
    void osCamposObrigatoriosDoEnvioDao422() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        var csrf = ApoioDeTeste.pedirCsrf(mvc);
        MvcTestResult semArquivo = mvc.post().uri("/api/importacoes").multipart().param("eventoId", String.valueOf(evento2027()))
                .param("alcance", "EDICAO").session(rbs.sessao()).cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token()).exchange();
        assertThat(semArquivo).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(semArquivo, "$.campos.arquivo")).isEqualTo("Envie um arquivo ou cole um texto.");
    }

    static String texto(MvcTestResult resposta) {
        try {
            return resposta.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception erro) {
            throw new IllegalStateException(erro);
        }
    }
}
