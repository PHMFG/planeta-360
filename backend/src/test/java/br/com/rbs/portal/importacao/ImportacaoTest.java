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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
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
                + "('tv-novembro.xlsx', 'padrao-rbs-outubro.xlsx', 'conversa-whatsapp.txt', 'resumo.txt')").update();
        jdbc.sql("DELETE FROM aproveitamento_previsto WHERE formato IN ('Comprovante de teste', 'Linha escolhida de teste')").update();
        jdbc.sql("DELETE FROM arquivo WHERE categoria = 'COMPROVACAO' AND observacao LIKE 'importacao:%'").update();
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
    void soConsultaConfirmaSemNenhumaLinhaAceita() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        MvcTestResult criada = enviar(rbs, "resumo.txt", "Resumo da reunião de operações do festival.".getBytes(StandardCharsets.UTF_8),
                evento2027(), "EDICAO", null, null, "SO_CONSULTA");
        assertThat(criada).hasStatus(HttpStatus.ACCEPTED);
        long id = ((Number) lerJson(criada, "$.id")).longValue();
        MvcTestResult detalhe = rbs.get("/api/importacoes/" + id);
        assertThat(lerJson(detalhe, "$.situacao")).isEqualTo("EM_PREVIA");
        assertThat(lerLista(detalhe, "$.itens")).isEmpty();

        MvcTestResult confirmada = rbs.post("/api/importacoes/" + id + "/confirmar", "{}");
        assertThat(confirmada).hasStatus(HttpStatus.OK);
        assertThat(lerJson(confirmada, "$.situacao")).isEqualTo("CONFIRMADA");
        assertThat(lerJson(confirmada, "$.resultado.inseridas")).isEqualTo(0);
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
    void oTotalDoMesContraExibicoesDoMesmoMesVemRecusadoNaPrevia() {
        // M2: o total do mês e as exibições do mesmo mês não se somam. A prévia já recusa, com o motivo (achado da rodada H1).
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        Map<String, Object> plano = jdbc.sql("""
                        SELECT ap.veiculo, ap.formato, ap.espaco_programa FROM aproveitamento_previsto ap
                        JOIN parceria pa ON pa.id = ap.parceria_id JOIN patrocinador p ON p.id = pa.patrocinador_id
                        JOIN evento e ON e.id = pa.evento_id
                        WHERE p.nome_canonico = 'Cliente Amarelo' AND e.edicao = 2027 AND ap.categoria = 'MIDIA'
                          AND ap.espaco_programa IS NOT NULL AND ap.formato !~ '[",]' AND ap.espaco_programa !~ '[",]'
                          AND NOT EXISTS (SELECT 1 FROM veiculacao v WHERE v.aproveitamento_id = ap.id AND v.periodo = 'MES')
                        ORDER BY ap.id LIMIT 1""").query().singleRow();
        String linhaDoPlano = "Cliente Amarelo," + plano.get("veiculo") + "," + plano.get("formato") + "," + plano.get("espaco_programa");
        byte[] exibicao = ("patrocinador,veiculo,formato,espaco_programa,exibido_em,quantidade,link\n"
                + linhaDoPlano + ",2026-12-10T15:00:00-03:00,1,\n").getBytes(StandardCharsets.UTF_8);
        long primeira = ((Number) lerJson(enviar(rbs, "exibicao-dezembro.csv", exibicao, evento2027(), "EDICAO", null, null,
                "VEICULACAO"), "$.id")).longValue();
        assertThat(rbs.patch("/api/importacoes/" + primeira + "/linhas", "{\"situacao\":\"ACEITA\",\"todas\":true}")).hasStatus(HttpStatus.OK);
        assertThat(rbs.post("/api/importacoes/" + primeira + "/confirmar", "{}")).hasStatus(HttpStatus.OK);

        byte[] total = ("patrocinador,veiculo,formato,espaco_programa,mes,quantidade,link\n"
                + linhaDoPlano + ",2026-12,30,\n").getBytes(StandardCharsets.UTF_8);
        long segunda = ((Number) lerJson(enviar(rbs, "total-dezembro.csv", total, evento2027(), "EDICAO", null, null,
                "VEICULACAO"), "$.id")).longValue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itens = (List<Map<String, Object>>) (List<?>) lerLista(rbs.get("/api/importacoes/" + segunda), "$.itens");
        assertThat(itens).hasSize(1);
        assertThat(itens.getFirst().get("situacao")).isEqualTo("RECUSADA");
        assertThat(String.valueOf(itens.getFirst().get("motivo"))).contains("já tem exibições em 2026-12");
    }

    @Test
    void aLinhaRepetidaEALinhaSemDataVemRecusadasComOMotivo() {
        // Rodada do Atlas da RBS (06/11): a linha igual a outra do mesmo arquivo entrava para revisar, e a linha sem data sumia.
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        byte[] csv = ("patrocinador,veiculo,formato,espaco_programa,exibido_em,quantidade,link\n"
                + "Cliente Amarelo,Rede Atlântida RS,Chamadas assinadas 5,Indeterminado,2026-11-20T10:15:30-03:00,1,\n"
                + "Cliente Amarelo,Rede Atlântida RS,Chamadas assinadas 5,Indeterminado,2026-11-20T14:42:10-03:00,1,\n"
                + "Cliente Amarelo,Rede Atlântida RS,Chamadas assinadas 5,Indeterminado,2026-11-20T10:15:30-03:00,1,\n"
                + "Cliente Amarelo,Rede Atlântida RS,Chamadas assinadas 5,Indeterminado,,1,\n").getBytes(StandardCharsets.UTF_8);
        long id = ((Number) lerJson(enviar(rbs, "repetida.csv", csv, evento2027(), "EDICAO", null, null, "VEICULACAO"), "$.id"))
                .longValue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itens = (List<Map<String, Object>>) (List<?>) lerLista(rbs.get("/api/importacoes/" + id), "$.itens");
        assertThat(itens).hasSize(4);
        assertThat(itens.get(1).get("situacao")).isNotEqualTo("RECUSADA");
        assertThat(itens.get(2).get("situacao")).isEqualTo("RECUSADA");
        assertThat(String.valueOf(itens.get(2).get("motivo"))).contains("Repete").contains("linha 2");
        assertThat(itens.get(3).get("situacao")).isEqualTo("RECUSADA");
        assertThat(String.valueOf(itens.get(3).get("motivo"))).contains("Falta a data ou o mês");
    }

    @Test
    void asTransicoesProibidasDao409EAsPermissoesSeguemOContrato() throws IOException {
        mapearTv();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);
        MvcTestResult criada = enviar(rbs, "tv-novembro.xlsx", corpus("tv-novembro.xlsx"), evento2027(), "EDICAO", null, null,
                "VEICULACAO");
        long id = ((Number) lerJson(criada, "$.id")).longValue();
        MvcTestResult lista = rbs.get("/api/importacoes?eventoId=" + evento2027() + "&situacao=EM_PREVIA");
        assertThat(lista).hasStatus(HttpStatus.OK);
        assertThat(lerLista(lista, "$.itens[*].id").stream().map(v -> ((Number) v).longValue()).toList()).contains(id);
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
    void escolherALinhaDoPlanoTiraARecusaDoCasador() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        Map<String, Object> plano = planoDaMidiaSemTotalMensal();
        byte[] csv = ("patrocinador,veiculo,formato,espaco_programa,exibido_em,quantidade,link\n"
                + "Cliente Amarelo,Rádio Que Não Existe,Formato Que Não Existe,Espaço Que Não Existe,"
                + "2026-12-10T15:00:00-03:00,1,\n").getBytes(StandardCharsets.UTF_8);
        long id = ((Number) lerJson(enviar(rbs, "linha-sem-casamento.csv", csv, evento2027(), "EDICAO", null, null,
                "VEICULACAO"), "$.id")).longValue();
        Map<String, Object> item = primeiraLinha(rbs.get("/api/importacoes/" + id));
        assertThat(item.get("situacao")).isEqualTo("RECUSADA");
        assertThat(String.valueOf(item.get("motivo"))).contains("Escolha a linha do plano.");
        long linhaId = ((Number) item.get("id")).longValue();
        long planoId = ((Number) plano.get("id")).longValue();

        MvcTestResult editada = rbs.patch("/api/importacoes/" + id + "/linhas/" + linhaId,
                "{\"aproveitamentoId\":" + planoId + "}");
        assertThat(editada).hasStatus(HttpStatus.OK);
        assertThat(lerJson(editada, "$.situacao")).isNotEqualTo("RECUSADA");
        assertThat(((Number) lerJson(editada, "$.aproveitamento.id")).longValue()).isEqualTo(planoId);

        assertThat(rbs.patch("/api/importacoes/" + id + "/linhas", "{\"situacao\":\"ACEITA\",\"todas\":true}"))
                .hasStatus(HttpStatus.OK);
        assertThat(rbs.post("/api/importacoes/" + id + "/confirmar", "{}")).hasStatus(HttpStatus.OK);
    }

    @Test
    void oConflitoDoMesUsaALinhaDoPlanoEscolhida() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        long plano = jdbc.sql("""
                        INSERT INTO aproveitamento_previsto (parceria_id, categoria, veiculo, formato, espaco_programa,
                                                             quantidade_prevista, unidade, tipo_midia)
                        VALUES (:parceria, 'MIDIA', 'Rádio Escolhida', 'Linha escolhida de teste', 'Programa escolhido', 10,
                                'INSERCAO', 'ASSINADA')
                        RETURNING id
                        """).param("parceria", parceria).query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO veiculacao (aproveitamento_id, exibido_em, quantidade, periodo, mes_referencia)
                        VALUES (:plano, TIMESTAMPTZ '2026-12-01 00:00:00-03', 5, 'MES', DATE '2026-12-01')
                        """).param("plano", plano).update();
        byte[] csv = ("patrocinador,veiculo,formato,espaco_programa,exibido_em,quantidade,link\n"
                + "Cliente Amarelo,Rádio Que Não Existe,Formato Que Não Existe,Espaço Que Não Existe,"
                + "2026-12-10T15:00:00-03:00,1,\n").getBytes(StandardCharsets.UTF_8);
        long id = ((Number) lerJson(enviar(rbs, "linha-escolhida.csv", csv, evento2027(), "EDICAO", null, null,
                "VEICULACAO"), "$.id")).longValue();
        long linhaId = ((Number) primeiraLinha(rbs.get("/api/importacoes/" + id)).get("id")).longValue();

        MvcTestResult editada = rbs.patch("/api/importacoes/" + id + "/linhas/" + linhaId,
                "{\"aproveitamentoId\":" + plano + "}");
        assertThat(editada).hasStatus(HttpStatus.OK);
        assertThat(lerJson(editada, "$.situacao")).isEqualTo("RECUSADA");
        assertThat(String.valueOf(lerJson(editada, "$.motivo"))).contains("já tem o total de 2026-12");
    }

    private void imagemTranscritaEPostaEmLinha(String transcricao, String localizador, String patrocinador, String veiculo,
                                               String formato, String data, String trechoDaData) {
        roteiro.limpar()
                .passo(pedido -> {
                    assertThat(pedido.imagem()).as("a primeira chamada leva a imagem").isNotNull();
                    return new RespostaDoLlm(transcricao, List.of(), null);
                })
                .passo(pedido -> {
                    assertThat(pedido.imagem()).as("a extração trabalha só sobre a transcrição").isNull();
                    String argumentos = """
                            {"destino":"COMPROVANTE","linhas":[{"campos":{"patrocinador":"%s","veiculo":"%s","formato":"%s","data":"%s"},
                              "origem":{"patrocinador":{"localizador":"%s","trecho":"%s"},
                                        "veiculo":{"localizador":"%s","trecho":"%s"},
                                        "formato":{"localizador":"%s","trecho":"%s"},
                                        "data":{"localizador":"%s","trecho":"%s"}}}]}
                            """.formatted(patrocinador, veiculo, formato, data, localizador, patrocinador, localizador, veiculo,
                            localizador, formato, localizador, trechoDaData);
                    return new RespostaDoLlm(null, List.of(new ChamadaDeFerramenta("m1", "propor_linhas", argumentos)), null);
                });
    }

    private static byte[] pdfSemTexto() throws IOException {
        try (PDDocument documento = new PDDocument(); ByteArrayOutputStream saida = new ByteArrayOutputStream()) {
            documento.addPage(new PDPage());
            documento.save(saida);
            return saida.toByteArray();
        }
    }

    @Test
    void aImagemEhTranscritaEAsLinhasSaemDaTranscricao() throws IOException {
        imagemTranscritaEPostaEmLinha("Cliente Roxo\natl.la\nPost feed serviço e valorização do cotista (durante)\n"
                        + "21 de outubro de 2026", "imagem", "Cliente Roxo", "atl.la",
                "Post feed serviço e valorização do cotista (durante)", "2026-10-21", "21 de outubro de 2026");
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        MvcTestResult criada = enviar(rbs, "post-instagram.png", corpus("post-instagram.png"), evento2027(), "EDICAO", null, null,
                "COMPROVANTE");
        assertThat(criada).hasStatus(HttpStatus.ACCEPTED);
        assertThat(roteiro.pedidos().getFirst().imagem().mimeType()).isEqualTo("image/png");
        long id = ((Number) lerJson(criada, "$.id")).longValue();
        MvcTestResult detalhe = rbs.get("/api/importacoes/" + id);
        assertThat(lerJson(detalhe, "$.situacao")).isEqualTo("EM_PREVIA");
        assertThat(lerJson(detalhe, "$.itens[0].situacao")).isEqualTo("RECUSADA");
        assertThat(String.valueOf(lerJson(detalhe, "$.itens[0].motivo")))
                .contains("Nenhuma veiculação de");
        assertThat(lerJson(detalhe, "$.itens[0].dados.data")).isEqualTo("2026-10-21");
        assertThat(lerJson(detalhe, "$.itens[0].origem.data.localizador")).isEqualTo("imagem");

        long linhaId = ((Number) lerJson(detalhe, "$.itens[0].id")).longValue();
        MvcTestResult origem = rbs.get("/api/importacoes/" + id + "/origem/" + linhaId + "?campo=data");
        assertThat(origem).hasStatus(HttpStatus.OK);
        assertThat(lerJson(origem, "$.tipo")).isEqualTo("PNG");
        assertThat(lerJson(origem, "$.localizador")).isEqualTo("imagem");
        assertThat(lerJson(origem, "$.trecho")).isEqualTo("21 de outubro de 2026");
        assertThat(lerJson(origem, "$.texto")).isNull();
    }

    @Test
    void oPdfEscaneadoCaiNaTranscricaoPorPagina() throws IOException {
        imagemTranscritaEPostaEmLinha("Cliente Amarelo\nZero Hora\nPágina dupla assinada\n18 de outubro de 2026", "página 1",
                "Cliente Amarelo", "Zero Hora", "Página dupla assinada", "2026-10-18", "18 de outubro de 2026");
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        MvcTestResult criada = enviar(rbs, "escaneado.pdf", pdfSemTexto(), evento2027(), "EDICAO", null, null, "COMPROVANTE");
        assertThat(criada).hasStatus(HttpStatus.ACCEPTED);
        long id = ((Number) lerJson(criada, "$.id")).longValue();
        MvcTestResult detalhe = rbs.get("/api/importacoes/" + id);
        assertThat(lerJson(detalhe, "$.situacao")).isEqualTo("EM_PREVIA");
        assertThat(lerJson(detalhe, "$.itens[0].origem.data.localizador")).isEqualTo("página 1");
    }

    @Test
    void oProvedorSemVisaoDeixaAImportacaoEmErro() throws IOException {
        roteiro.limpar().semVisao();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        MvcTestResult criada = enviar(rbs, "post-instagram.png", corpus("post-instagram.png"), evento2027(), "EDICAO", null, null,
                "COMPROVANTE");
        assertThat(criada).hasStatus(HttpStatus.ACCEPTED);
        long id = ((Number) lerJson(criada, "$.id")).longValue();
        MvcTestResult detalhe = rbs.get("/api/importacoes/" + id);
        assertThat(lerJson(detalhe, "$.situacao")).isEqualTo("ERRO");
        assertThat(String.valueOf(lerJson(detalhe, "$.erro"))).contains("Este provedor não lê imagem");
        assertThat(roteiro.pedidos()).isEmpty();
    }

    private long planoDoComprovante() {
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        long plano = jdbc.sql("""
                        INSERT INTO aproveitamento_previsto (parceria_id, categoria, veiculo, formato, quantidade_prevista, unidade,
                                                             tipo_midia)
                        VALUES (:parceria, 'MIDIA', 'Rádio 92', 'Comprovante de teste', 10, 'INSERCAO', 'ASSINADA')
                        RETURNING id
                        """).param("parceria", parceria).query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO veiculacao (aproveitamento_id, exibido_em, quantidade)
                        VALUES (:plano, TIMESTAMPTZ '2026-10-18 12:00:00-03', 1)
                        """).param("plano", plano).update();
        return plano;
    }

    @Test
    void oComprovanteCasaAVeiculacaoERetirarDesligaOVinculo() throws IOException {
        long plano = planoDoComprovante();
        long veiculacao = jdbc.sql("SELECT id FROM veiculacao WHERE aproveitamento_id = :plano").param("plano", plano)
                .query(Long.class).single();
        imagemTranscritaEPostaEmLinha("Cliente Amarelo\nRádio 92\nComprovante de teste\n18 de outubro de 2026", "imagem",
                "Cliente Amarelo", "Rádio 92", "Comprovante de teste", "2026-10-18", "18 de outubro de 2026");
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        MvcTestResult criada = enviar(rbs, "comprovante-outubro.png", corpus("post-instagram.png"), evento2027(), "EDICAO", null,
                null, "COMPROVANTE");
        long id = ((Number) lerJson(criada, "$.id")).longValue();
        MvcTestResult detalhe = rbs.get("/api/importacoes/" + id);
        assertThat(lerJson(detalhe, "$.itens[0].situacao")).isEqualTo("PROPOSTA");

        assertThat(rbs.patch("/api/importacoes/" + id + "/linhas", "{\"situacao\":\"ACEITA\",\"todas\":true}"))
                .hasStatus(HttpStatus.OK);
        MvcTestResult confirmada = rbs.post("/api/importacoes/" + id + "/confirmar", "{}");
        assertThat(confirmada).hasStatus(HttpStatus.OK);
        assertThat(lerJson(confirmada, "$.situacao")).isEqualTo("CONFIRMADA");
        assertThat(lerJson(confirmada, "$.resultado.inseridas")).isEqualTo(1);
        Long arquivo = jdbc.sql("SELECT comprovante_arquivo_id FROM veiculacao WHERE id = :id").param("id", veiculacao)
                .query(Long.class).single();
        assertThat(arquivo).as("o comprovante ficou ligado à veiculação").isNotNull();
        assertThat(jdbc.sql("SELECT categoria FROM arquivo WHERE id = :id").param("id", arquivo).query(String.class).single())
                .isEqualTo("COMPROVACAO");

        MvcTestResult retirada = rbs.post("/api/importacoes/" + id + "/retirar", "{}");
        assertThat(retirada).hasStatus(HttpStatus.OK);
        assertThat(lerJson(retirada, "$.situacao")).isEqualTo("RETIRADA");
        assertThat(jdbc.sql("SELECT comprovante_arquivo_id FROM veiculacao WHERE id = :id").param("id", veiculacao)
                .query(Long.class).optional()).isEmpty();
    }

    @Test
    void semVeiculacaoNaDataAaLinhaFicaRecusada() throws IOException {
        planoDoComprovante();
        imagemTranscritaEPostaEmLinha("Cliente Amarelo\nRádio 92\nComprovante de teste\n19 de outubro de 2026", "imagem",
                "Cliente Amarelo", "Rádio 92", "Comprovante de teste", "2026-10-19", "19 de outubro de 2026");
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        MvcTestResult criada = enviar(rbs, "comprovante-outubro.png", corpus("post-instagram.png"), evento2027(), "EDICAO", null,
                null, "COMPROVANTE");
        long id = ((Number) lerJson(criada, "$.id")).longValue();
        MvcTestResult detalhe = rbs.get("/api/importacoes/" + id);
        assertThat(lerJson(detalhe, "$.itens[0].situacao")).isEqualTo("RECUSADA");
        assertThat(String.valueOf(lerJson(detalhe, "$.itens[0].motivo")))
                .contains("Nenhuma veiculação de");
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


    private Map<String, Object> planoDaMidiaSemTotalMensal() {
        return jdbc.sql("""
                        SELECT ap.id, ap.veiculo, ap.formato, ap.espaco_programa FROM aproveitamento_previsto ap
                        JOIN parceria pa ON pa.id = ap.parceria_id JOIN patrocinador p ON p.id = pa.patrocinador_id
                        JOIN evento e ON e.id = pa.evento_id
                        WHERE p.nome_canonico = 'Cliente Amarelo' AND e.edicao = 2027 AND ap.categoria = 'MIDIA'
                          AND ap.espaco_programa IS NOT NULL AND ap.formato !~ '[",]' AND ap.espaco_programa !~ '[",]'
                          AND NOT EXISTS (SELECT 1 FROM veiculacao v WHERE v.aproveitamento_id = ap.id AND v.periodo = 'MES')
                        ORDER BY ap.id LIMIT 1""").query().singleRow();
    }

    private static String linhaDoPlano(Map<String, Object> plano) {
        return "Cliente Amarelo," + plano.get("veiculo") + "," + plano.get("formato") + "," + plano.get("espaco_programa");
    }

    @Test
    void aDataDoMesmoMesNaoVaiJuntoComOMesParaACarga() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        Map<String, Object> plano = planoDaMidiaSemTotalMensal();
        byte[] linha = ("patrocinador,veiculo,formato,espaco_programa,exibido_em,mes,quantidade,link\n"
                + linhaDoPlano(plano) + ",2026-12-10T15:00:00-03:00,2026-12,1,\n").getBytes(StandardCharsets.UTF_8);
        long id = ((Number) lerJson(enviar(rbs, "exibicao-com-mes.csv", linha, evento2027(), "EDICAO", null, null,
                "VEICULACAO"), "$.id")).longValue();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itens = (List<Map<String, Object>>) (List<?>) lerLista(rbs.get("/api/importacoes/" + id), "$.itens");
        assertThat(itens).hasSize(1);
        assertThat(itens.getFirst().get("situacao")).isEqualTo("PROPOSTA");
        @SuppressWarnings("unchecked")
        Map<String, Object> dados = (Map<String, Object>) itens.getFirst().get("dados");
        assertThat(dados).as("a data já diz o mês").doesNotContainKey("mes");

        assertThat(rbs.patch("/api/importacoes/" + id + "/linhas", "{\"situacao\":\"ACEITA\",\"todas\":true}")).hasStatus(HttpStatus.OK);
        MvcTestResult confirmada = rbs.post("/api/importacoes/" + id + "/confirmar", "{}");
        assertThat(confirmada).hasStatus(HttpStatus.OK);
        assertThat(lerJson(confirmada, "$.resultado.rejeitadas")).as("nenhuma linha recusada na carga").isEqualTo(0);
    }

    @Test
    void aDataDeOutroMesQueOMesEhRecusadaJaNaPrevia() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        Map<String, Object> plano = planoDaMidiaSemTotalMensal();
        byte[] linha = ("patrocinador,veiculo,formato,espaco_programa,exibido_em,mes,quantidade,link\n"
                + linhaDoPlano(plano) + ",2026-12-10T15:00:00-03:00,2027-01,1,\n").getBytes(StandardCharsets.UTF_8);
        long id = ((Number) lerJson(enviar(rbs, "exibicao-mes-diferente.csv", linha, evento2027(), "EDICAO", null, null,
                "VEICULACAO"), "$.id")).longValue();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itens = (List<Map<String, Object>>) (List<?>) lerLista(rbs.get("/api/importacoes/" + id), "$.itens");
        assertThat(itens).hasSize(1);
        assertThat(itens.getFirst().get("situacao")).isEqualTo("RECUSADA");
        assertThat(String.valueOf(itens.getFirst().get("motivo"))).contains("A data da exibição não é do mês informado.");
    }

    /** Um PDF de verdade com uma linha de texto, para a extração livre dos números da edição. */
    private static byte[] pdfComTexto(String texto) throws IOException {
        try (PDDocument documento = new PDDocument(); ByteArrayOutputStream saida = new ByteArrayOutputStream()) {
            PDPage pagina = new PDPage();
            documento.addPage(pagina);
            var fonte = new org.apache.pdfbox.pdmodel.font.PDType1Font(
                    org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
            try (var conteudo = new org.apache.pdfbox.pdmodel.PDPageContentStream(documento, pagina)) {
                conteudo.beginText();
                conteudo.setFont(fonte, 10);
                conteudo.newLineAtOffset(40, 750);
                conteudo.showText(texto);
                conteudo.endText();
            }
            documento.save(saida);
            return saida.toByteArray();
        }
    }

    private static String origem(String trecho) {
        return "{\"localizador\":\"página 1, linha 1\",\"trecho\":\"" + trecho + "\"}";
    }

    @Test
    void osNumerosDaEdicaoComMilEMilhaoChegamNaEscala() throws IOException {
        roteiro.limpar().passo(pedido -> new RespostaDoLlm(null, List.of(new ChamadaDeFerramenta("m1", "propor_linhas",
                "{\"destino\":\"NUMEROS_EDICAO\",\"linhas\":["
                        + "{\"campos\":{\"indicador\":\"Público (planetários)\",\"valor\":80,\"unidade\":\"mil planetários\","
                        + "\"qualificador\":\"MAIS_DE\",\"fonte\":\"PV_PA_26_GERAL.pdf\"},"
                        + "\"origem\":{\"indicador\":" + origem("Público (planetários)") + ",\"valor\":" + origem("+80 mil planetários")
                        + ",\"unidade\":" + origem("+80 mil planetários") + ",\"qualificador\":" + origem("+80 mil planetários")
                        + ",\"fonte\":" + origem("PV_PA_26_GERAL.pdf") + "}},"
                        + "{\"campos\":{\"indicador\":\"Marcas parceiras\",\"valor\":39,\"unidade\":\"marcas\","
                        + "\"qualificador\":\"EXATO\",\"fonte\":\"PV_PA_26_GERAL.pdf\"},"
                        + "\"origem\":{\"indicador\":" + origem("Marcas parceiras") + ",\"valor\":" + origem("39 marcas parceiras")
                        + ",\"unidade\":" + origem("39 marcas parceiras") + ",\"qualificador\":" + origem("39 marcas parceiras")
                        + ",\"fonte\":" + origem("PV_PA_26_GERAL.pdf") + "}},"
                        + "{\"campos\":{\"indicador\":\"Impactos nas redes do evento\",\"valor\":19,\"unidade\":\"milhões de impactos\","
                        + "\"qualificador\":\"MAIS_DE\",\"fonte\":\"PV_PA_26_GERAL.pdf\"},"
                        + "\"origem\":{\"indicador\":" + origem("Impactos nas redes do evento") + ",\"valor\":"
                        + origem("+19 milhões de impactos nas redes") + ",\"unidade\":" + origem("+19 milhões de impactos nas redes")
                        + ",\"qualificador\":" + origem("+19 milhões de impactos nas redes") + ",\"fonte\":"
                        + origem("PV_PA_26_GERAL.pdf") + "}},"
                        + "{\"campos\":{\"indicador\":\"Pessoas nas transmissões\",\"valor\":1.6,\"unidade\":\"milhão\","
                        + "\"qualificador\":\"EXATO\",\"fonte\":\"Relatório de transmissões 2026\"},"
                        + "\"origem\":{\"indicador\":" + origem("Pessoas nas transmissões") + ",\"valor\":"
                        + origem("1,6 milhão nas transmissões") + ",\"unidade\":" + origem("1,6 milhão nas transmissões")
                        + ",\"qualificador\":" + origem("1,6 milhão nas transmissões") + ",\"fonte\":"
                        + origem("Relatório de transmissões 2026") + "}}"
                        + "]}")), null));
        String texto = "Público (planetários): +80 mil planetários. Marcas parceiras: 39 marcas parceiras. "
                + "Impactos nas redes do evento: +19 milhões de impactos nas redes. Pessoas nas transmissões: "
                + "1,6 milhão nas transmissões. Fonte: PV_PA_26_GERAL.pdf. Fonte: Relatório de transmissões 2026.";
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long id = ((Number) lerJson(enviar(rbs, "numeros-da-edicao.pdf", pdfComTexto(texto), evento2027(), "EDICAO", null, null,
                "NUMEROS_EDICAO"), "$.id")).longValue();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itens = (List<Map<String, Object>>) (List<?>) lerLista(rbs.get("/api/importacoes/" + id), "$.itens");
        assertThat(itens).hasSize(4).allSatisfy(item -> assertThat(item.get("situacao")).isEqualTo("PROPOSTA"));
        assertThat(dados(itens.get(0))).containsEntry("valor", 80000).containsEntry("unidade", "PESSOAS");
        assertThat(dados(itens.get(1))).containsEntry("valor", 39).containsEntry("unidade", "MARCAS");
        assertThat(dados(itens.get(2))).containsEntry("valor", 19000000).containsEntry("unidade", "IMPACTOS");
        assertThat(dados(itens.get(3))).containsEntry("valor", 1600000).containsEntry("unidade", "UNIDADE");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dados(Map<String, Object> item) {
        return (Map<String, Object>) item.get("dados");
    }

    @Test
    void aVeiculacaoSemMesESemDataEhRecusadaNaPrevia() {
        String conversa = "Oi, aqui é da Rádio 92: em outubro rodamos 40 chamadas assinadas de 5 segundos do Cliente Amarelo "
                + "no programa Pretinho Básico.";
        roteiro.limpar().passo(pedido -> resposta("VEICULACAO", """
                {"campos":{"patrocinador":"Cliente Amarelo","veiculo":"Rádio 92","formato":"chamadas assinadas de 5 segundos",
                  "espaco_programa":"Pretinho Básico","quantidade":40},
                 "origem":{"patrocinador":{"localizador":"linha 1","trecho":"Cliente Amarelo"},
                           "veiculo":{"localizador":"linha 1","trecho":"Rádio 92"},
                           "formato":{"localizador":"linha 1","trecho":"40 chamadas assinadas de 5 segundos"},
                           "espaco_programa":{"localizador":"linha 1","trecho":"programa Pretinho Básico"},
                           "quantidade":{"localizador":"linha 1","trecho":"40 chamadas"}}}
                """));
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long id = ((Number) lerJson(enviar(rbs, "conversa-colada.txt", conversa.getBytes(StandardCharsets.UTF_8), evento2027(),
                "EDICAO", null, null, "VEICULACAO"), "$.id")).longValue();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itens = (List<Map<String, Object>>) (List<?>) lerLista(rbs.get("/api/importacoes/" + id), "$.itens");
        assertThat(itens).hasSize(1);
        assertThat(itens.getFirst().get("situacao")).isEqualTo("RECUSADA");
        assertThat(String.valueOf(itens.getFirst().get("motivo"))).contains("Falta a data ou o mês da entrega.");
    }

    private static RespostaDoLlm resposta(String destino, String linha) {
        return new RespostaDoLlm(null, List.of(new ChamadaDeFerramenta("m1", "propor_linhas",
                "{\"destino\":\"" + destino + "\",\"linhas\":[" + linha + "]}")), null);
    }
}
