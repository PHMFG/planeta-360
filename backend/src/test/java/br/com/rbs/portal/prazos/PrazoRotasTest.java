package br.com.rbs.portal.prazos;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] As rotas 62 a 64 (M5): listar, cadastrar e desativar os prazos da edição, da cota e da parceria. Só a RBS. Cada teste é
 * desfeito no fim (@Transactional): a rota não confirma nada. Os documentos de onde o prazo sai são os do kit, que ficam confirmados
 * no banco de teste: D02 (Manual de Operações 2027 v2, GLOBAL), D03 (plano da Master, COTA), D06 (anexo do Amarelo, PARCERIA) e D10
 * (manual de 2026, outra edição).
 */
@TesteDeIntegracao
@Transactional
class PrazoRotasTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String SO_RBS = "Esta operação é restrita à equipe da RBS.";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    long evento2027() {
        return ApoioDePrazos.evento(jdbc, 2027);
    }

    long d02() {
        return ApoioDePrazos.documento(jdbc, "Manual de Operações 2027", 2);
    }

    long d03() {
        return ApoioDePrazos.documento(jdbc, "Plano comercial · Cota Master 2027", 1);
    }

    long d06() {
        return ApoioDePrazos.documento(jdbc, "Anexo da parceria · Cliente Amarelo", 1);
    }

    long d10() {
        return ApoioDePrazos.documento(jdbc, "Manual de Operações 2026", 1);
    }

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    long roxo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Roxo");
    }

    SessaoDeApi rbs() {
        return SessaoDeApi.entrar(mvc, RBS);
    }

    /** O corpo da rota 63 com só o que o teste preenche (o resto fica de fora, como o front faz). */
    static Map<String, Object> corpo(String escopo, String titulo, String data) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("escopo", escopo);
        corpo.put("titulo", titulo);
        corpo.put("data", data);
        return corpo;
    }

    MvcTestResult criar(SessaoDeApi pessoa, Map<String, Object> corpo) {
        return pessoa.post("/api/eventos/" + evento2027() + "/prazos", AtlasJson.escrever(corpo));
    }

    long criarOk(SessaoDeApi pessoa, Map<String, Object> corpo) {
        MvcTestResult resposta = criar(pessoa, corpo);
        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        return ((Number) lerJson(resposta, "$.id")).longValue();
    }

    /** O 422 com o campo errado: devolve a frase do campo. */
    String campo(MvcTestResult resposta, String campo) {
        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        @SuppressWarnings("unchecked")
        Map<String, String> campos = (Map<String, String>) lerJson(resposta, "$.campos");
        assertThat(campos).as("campos do 422").containsKey(campo);
        return campos.get(campo);
    }

    // ---------------------------------------------------------------------
    // Rota 63: cadastrar
    // ---------------------------------------------------------------------

    @Test
    void cadastraUmPrazoDaEdicaoInteiraSemDocumentoEDevolveOPrazo() {
        SessaoDeApi rbs = rbs();

        MvcTestResult resposta = criar(rbs, corpo("GLOBAL", "Reunião de alinhamento", "2026-12-10"));

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        long id = ((Number) lerJson(resposta, "$.id")).longValue();
        assertThat(resposta.getResponse().getHeader("Location")).isEqualTo("/api/prazos/" + id);
        assertThat(lerJson(resposta, "$.eventoId")).isEqualTo((int) evento2027());
        assertThat(lerJson(resposta, "$.escopo")).isEqualTo("GLOBAL");
        assertThat(lerJson(resposta, "$.cota")).isNull();
        assertThat(lerJson(resposta, "$.parceria")).isNull();
        assertThat(lerJson(resposta, "$.titulo")).isEqualTo("Reunião de alinhamento");
        assertThat(lerJson(resposta, "$.data")).isEqualTo("2026-12-10");
        assertThat(lerJson(resposta, "$.hora")).isNull();
        assertThat(lerJson(resposta, "$.descricao")).isNull();
        assertThat(lerJson(resposta, "$.documento")).isNull();
        assertThat(lerJson(resposta, "$.pagina")).isNull();
        assertThat(lerJson(resposta, "$.ativo")).isEqualTo(true);
        assertThat(lerJson(resposta, "$.criadoPor.perfil")).isEqualTo("ADMIN_RBS");
        assertThat(lerJson(resposta, "$.criadoPor.nome")).isNotNull();
        assertThat((String) lerJson(resposta, "$.criadoEm")).endsWith("-03:00");
    }

    @Test
    void cadastraUmPrazoDeCotaComHoraDescricaoDocumentoEPagina() {
        Map<String, Object> corpo = corpo("COTA", "Informar os geradores próprios", "2026-12-04");
        corpo.put("cota", "MASTER");
        corpo.put("hora", "18:00");
        corpo.put("descricao", "Informar à produção os geradores próprios.");
        corpo.put("documentoId", d02());
        corpo.put("pagina", 5);

        MvcTestResult resposta = criar(rbs(), corpo);

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(resposta, "$.cota.codigo")).isEqualTo("MASTER");
        assertThat(lerJson(resposta, "$.cota.nome")).isEqualTo("Master");
        assertThat(lerJson(resposta, "$.hora")).isEqualTo("18:00");
        assertThat(lerJson(resposta, "$.descricao")).isEqualTo("Informar à produção os geradores próprios.");
        assertThat(lerJson(resposta, "$.documento.id")).isEqualTo((int) d02());
        assertThat(lerJson(resposta, "$.documento.titulo")).isEqualTo("Manual de Operações 2027");
        assertThat(lerJson(resposta, "$.documento.versao")).isEqualTo(2);
        assertThat(lerJson(resposta, "$.documento.status")).isEqualTo("INDEXADO");
        assertThat(lerJson(resposta, "$.pagina")).isEqualTo(5);
    }

    @Test
    void cadastraUmPrazoDeParceriaComOPatrocinador() {
        Map<String, Object> corpo = corpo("PARCERIA", "Montagem do espaço", "2027-01-20");
        corpo.put("parceriaId", amarelo());

        MvcTestResult resposta = criar(rbs(), corpo);

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(resposta, "$.parceria.id")).isEqualTo((int) amarelo());
        assertThat(lerJson(resposta, "$.parceria.patrocinador")).isEqualTo("Cliente Amarelo");
        assertThat(lerJson(resposta, "$.cota")).isNull();
    }

    @Test
    void ocadastroGravaOHistoricoNaEntidadePrazo() {
        long id = criarOk(rbs(), corpo("GLOBAL", "Reunião de alinhamento", "2026-12-10"));

        var linha = jdbc.sql("SELECT acao, valor_novo, detalhe, parceria_id FROM historico WHERE entidade = 'prazo' AND entidade_id = :id")
                .param("id", id).query().singleRow();

        assertThat(linha.get("acao")).isEqualTo("CRIACAO");
        assertThat((String) linha.get("valor_novo")).contains("Reunião de alinhamento");
        assertThat(linha.get("parceria_id")).isNull();
    }

    // ---------------------------------------------------------------------
    // Rota 63: o que dá 422, campo a campo
    // ---------------------------------------------------------------------

    @Test
    void escopoAusenteOuForaDaListaDa422() {
        Map<String, Object> semEscopo = corpo(null, "Prazo", "2026-12-10");
        Map<String, Object> inventado = corpo("TODOS", "Prazo", "2026-12-10");

        assertThat(campo(criar(rbs(), semEscopo), "escopo")).isEqualTo("Escopo inválido. Use GLOBAL, COTA ou PARCERIA.");
        assertThat(campo(criar(rbs(), inventado), "escopo")).isEqualTo("Escopo inválido. Use GLOBAL, COTA ou PARCERIA.");
    }

    @Test
    void cotaEObrigatoriaSoNoEscopoCotaEProibidaForaDele() {
        SessaoDeApi rbs = rbs();
        Map<String, Object> semCota = corpo("COTA", "Prazo", "2026-12-10");
        Map<String, Object> cotaQueNaoExiste = corpo("COTA", "Prazo", "2026-12-10");
        cotaQueNaoExiste.put("cota", "OURO");
        Map<String, Object> globalComCota = corpo("GLOBAL", "Prazo", "2026-12-10");
        globalComCota.put("cota", "MASTER");
        Map<String, Object> parceriaComCota = corpo("PARCERIA", "Prazo", "2026-12-10");
        parceriaComCota.put("parceriaId", amarelo());
        parceriaComCota.put("cota", "MASTER");

        assertThat(campo(criar(rbs, semCota), "cota")).isEqualTo("Informe a cota: MASTER, SUBCOTA_G ou SUBCOTA.");
        assertThat(campo(criar(rbs, cotaQueNaoExiste), "cota")).isEqualTo("Informe a cota: MASTER, SUBCOTA_G ou SUBCOTA.");
        assertThat(campo(criar(rbs, globalComCota), "cota")).isEqualTo("Só o prazo de escopo COTA leva cota.");
        assertThat(campo(criar(rbs, parceriaComCota), "cota")).isEqualTo("Só o prazo de escopo COTA leva cota.");
    }

    @Test
    void parceriaEObrigatoriaSoNoEscopoParceriaEPrecisaSerDaEdicao() {
        SessaoDeApi rbs = rbs();
        Map<String, Object> semParceria = corpo("PARCERIA", "Prazo", "2026-12-10");
        Map<String, Object> inexistente = corpo("PARCERIA", "Prazo", "2026-12-10");
        inexistente.put("parceriaId", 999_999);
        Map<String, Object> deOutraEdicao = corpo("PARCERIA", "Prazo", "2026-12-10");
        deOutraEdicao.put("parceriaId", jdbc.sql("SELECT id FROM parceria WHERE evento_id = :e").param("e", ApoioDePrazos.evento(jdbc, 2026))
                .query(Long.class).list().get(0));
        Map<String, Object> globalComParceria = corpo("GLOBAL", "Prazo", "2026-12-10");
        globalComParceria.put("parceriaId", amarelo());
        Map<String, Object> cotaComParceria = corpo("COTA", "Prazo", "2026-12-10");
        cotaComParceria.put("cota", "MASTER");
        cotaComParceria.put("parceriaId", amarelo());

        assertThat(campo(criar(rbs, semParceria), "parceriaId")).isEqualTo("Informe a parceria (parceriaId) do prazo.");
        assertThat(campo(criar(rbs, inexistente), "parceriaId")).isEqualTo("Esta parceria não existe.");
        assertThat(campo(criar(rbs, deOutraEdicao), "parceriaId")).isEqualTo("Esta parceria não é da edição do prazo.");
        assertThat(campo(criar(rbs, globalComParceria), "parceriaId")).isEqualTo("Só o prazo de escopo PARCERIA leva parceria.");
        assertThat(campo(criar(rbs, cotaComParceria), "parceriaId")).isEqualTo("Só o prazo de escopo PARCERIA leva parceria.");
    }

    @Test
    void tituloVazioOuLongoDa422() {
        SessaoDeApi rbs = rbs();

        assertThat(campo(criar(rbs, corpo("GLOBAL", null, "2026-12-10")), "titulo")).isEqualTo("Informe o título do prazo.");
        assertThat(campo(criar(rbs, corpo("GLOBAL", "   ", "2026-12-10")), "titulo")).isEqualTo("Informe o título do prazo.");
        assertThat(campo(criar(rbs, corpo("GLOBAL", "x".repeat(201), "2026-12-10")), "titulo"))
                .isEqualTo("O título tem no máximo 200 caracteres.");
        assertThat(campo(criar(rbs, corpo("GLOBAL", "com\u0000nulo", "2026-12-10")), "titulo")).isEqualTo("O título tem caracteres inválidos.");
        assertThat(criar(rbs, corpo("GLOBAL", "x".repeat(200), "2026-12-10"))).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void dataAusenteOuInvalidaDa422() {
        SessaoDeApi rbs = rbs();

        assertThat(campo(criar(rbs, corpo("GLOBAL", "Prazo", null)), "data")).isEqualTo("Informe a data do prazo (AAAA-MM-DD).");
        for (String invalida : List.of("2026-02-30", "04/12/2026", "2026-12-4", "amanhã", "20261204", "2026-13-01")) {
            assertThat(campo(criar(rbs, corpo("GLOBAL", "Prazo", invalida)), "data")).as(invalida)
                    .isEqualTo("Data inválida. Use o formato AAAA-MM-DD, por exemplo 2026-12-04.");
        }
        for (String foraDoIntervalo : List.of("1999-12-31", "2101-01-01", "0001-01-01")) {
            assertThat(campo(criar(rbs, corpo("GLOBAL", "Prazo", foraDoIntervalo)), "data")).as(foraDoIntervalo)
                    .isEqualTo("O ano da data tem de estar entre 2000 e 2100.");
        }
        assertThat(criar(rbs, corpo("GLOBAL", "Prazo", "2000-01-01"))).hasStatus(HttpStatus.CREATED);
        assertThat(criar(rbs, corpo("GLOBAL", "Prazo", "2100-12-31"))).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void horaForaDoFormatoHHmmDa422() {
        SessaoDeApi rbs = rbs();

        for (String invalida : List.of("25:00", "18:60", "18:00:30", "6:00", "18h", "18", "às 18", "24:00")) {
            Map<String, Object> corpo = corpo("GLOBAL", "Prazo", "2026-12-10");
            corpo.put("hora", invalida);
            assertThat(campo(criar(rbs, corpo), "hora")).as(invalida).isEqualTo("A hora tem o formato HH:mm, como 18:00.");
        }
        for (String valida : List.of("00:00", "09:30", "23:59")) {
            Map<String, Object> corpo = corpo("GLOBAL", "Prazo", "2026-12-10");
            corpo.put("hora", valida);
            MvcTestResult resposta = criar(rbs, corpo);
            assertThat(resposta).as(valida).hasStatus(HttpStatus.CREATED);
            assertThat(lerJson(resposta, "$.hora")).isEqualTo(valida);
        }
    }

    @Test
    void descricaoLongaDa422() {
        SessaoDeApi rbs = rbs();
        Map<String, Object> longa = corpo("GLOBAL", "Prazo", "2026-12-10");
        longa.put("descricao", "d".repeat(1001));
        Map<String, Object> noLimite = corpo("GLOBAL", "Prazo", "2026-12-10");
        noLimite.put("descricao", "d".repeat(1000));
        Map<String, Object> emBranco = corpo("GLOBAL", "Prazo", "2026-12-10");
        emBranco.put("descricao", "   ");

        assertThat(campo(criar(rbs, longa), "descricao")).isEqualTo("A descrição tem no máximo 1000 caracteres.");
        assertThat(criar(rbs, noLimite)).hasStatus(HttpStatus.CREATED);
        MvcTestResult semTexto = criar(rbs, emBranco);
        assertThat(semTexto).as("descrição em branco vale como sem descrição").hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(semTexto, "$.descricao")).isNull();
    }

    @Test
    void documentoQueNaoExisteOuEDeOutraEdicaoDa422() {
        SessaoDeApi rbs = rbs();
        Map<String, Object> inexistente = corpo("GLOBAL", "Prazo", "2026-12-10");
        inexistente.put("documentoId", 999_999);
        Map<String, Object> de2026 = corpo("GLOBAL", "Prazo", "2026-12-10");
        de2026.put("documentoId", d10());

        assertThat(campo(criar(rbs, inexistente), "documentoId")).isEqualTo("Este documento não existe.");
        assertThat(campo(criar(rbs, de2026), "documentoId")).isEqualTo("Este documento não é da edição do prazo.");
    }

    @Test
    void paginaSemDocumentoMenorQueUmOuMaiorQueAsPaginasDa422() {
        SessaoDeApi rbs = rbs();
        int paginas = jdbc.sql("SELECT paginas FROM documento WHERE id = :id").param("id", d02()).query(Integer.class).single();
        Map<String, Object> semDocumento = corpo("GLOBAL", "Prazo", "2026-12-10");
        semDocumento.put("pagina", 3);
        Map<String, Object> zero = corpo("GLOBAL", "Prazo", "2026-12-10");
        zero.put("documentoId", d02());
        zero.put("pagina", 0);
        Map<String, Object> negativa = corpo("GLOBAL", "Prazo", "2026-12-10");
        negativa.put("documentoId", d02());
        negativa.put("pagina", -2);
        Map<String, Object> alemDoFim = corpo("GLOBAL", "Prazo", "2026-12-10");
        alemDoFim.put("documentoId", d02());
        alemDoFim.put("pagina", paginas + 1);
        Map<String, Object> naUltima = corpo("GLOBAL", "Prazo", "2026-12-10");
        naUltima.put("documentoId", d02());
        naUltima.put("pagina", paginas);

        assertThat(campo(criar(rbs, semDocumento), "pagina")).isEqualTo("A página só vale com um documento (documentoId).");
        assertThat(campo(criar(rbs, zero), "pagina")).isEqualTo("A página começa em 1.");
        assertThat(campo(criar(rbs, negativa), "pagina")).isEqualTo("A página começa em 1.");
        assertThat(campo(criar(rbs, alemDoFim), "pagina")).isEqualTo("O documento tem " + paginas + " páginas.");
        assertThat(criar(rbs, naUltima)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void oPrazoNaoPodeTerAlcanceMaiorQueODoDocumentoDeOrigem() {
        SessaoDeApi rbs = rbs();
        String frase = "Um prazo não vale para mais gente que o documento de onde saiu.";
        // o plano da Master é COTA: só a Master, ou uma parceria Master
        Map<String, Object> planoNaEdicaoInteira = corpo("GLOBAL", "Prazo", "2026-12-10");
        planoNaEdicaoInteira.put("documentoId", d03());
        Map<String, Object> planoNaSubcotaG = corpo("COTA", "Prazo", "2026-12-10");
        planoNaSubcotaG.put("cota", "SUBCOTA_G");
        planoNaSubcotaG.put("documentoId", d03());
        Map<String, Object> planoNoRoxo = corpo("PARCERIA", "Prazo", "2026-12-10");
        planoNoRoxo.put("parceriaId", roxo());
        planoNoRoxo.put("documentoId", d03());
        // o anexo do Amarelo é PARCERIA: só o Amarelo
        Map<String, Object> anexoNaEdicaoInteira = corpo("GLOBAL", "Prazo", "2026-12-10");
        anexoNaEdicaoInteira.put("documentoId", d06());
        Map<String, Object> anexoNaMaster = corpo("COTA", "Prazo", "2026-12-10");
        anexoNaMaster.put("cota", "MASTER");
        anexoNaMaster.put("documentoId", d06());
        Map<String, Object> anexoNoRoxo = corpo("PARCERIA", "Prazo", "2026-12-10");
        anexoNoRoxo.put("parceriaId", roxo());
        anexoNoRoxo.put("documentoId", d06());

        for (Map<String, Object> maior : List.of(planoNaEdicaoInteira, planoNaSubcotaG, planoNoRoxo, anexoNaEdicaoInteira, anexoNaMaster,
                anexoNoRoxo)) {
            assertThat(campo(criar(rbs, maior), "documentoId")).as(maior.toString()).startsWith(frase);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM prazo WHERE titulo = 'Prazo'").query(Long.class).single()).isZero();
    }

    @Test
    void oPrazoComAlcanceMenorOuIgualAoDoDocumentoPassa() {
        SessaoDeApi rbs = rbs();
        Map<String, Object> planoNaMaster = corpo("COTA", "Prazo do plano", "2026-12-10");
        planoNaMaster.put("cota", "MASTER");
        planoNaMaster.put("documentoId", d03());
        Map<String, Object> planoNoAmarelo = corpo("PARCERIA", "Prazo do plano", "2026-12-10");
        planoNoAmarelo.put("parceriaId", amarelo());
        planoNoAmarelo.put("documentoId", d03());
        Map<String, Object> anexoNoAmarelo = corpo("PARCERIA", "Prazo do anexo", "2026-12-10");
        anexoNoAmarelo.put("parceriaId", amarelo());
        anexoNoAmarelo.put("documentoId", d06());
        Map<String, Object> manualNoRoxo = corpo("PARCERIA", "Prazo do manual", "2026-12-10");
        manualNoRoxo.put("parceriaId", roxo());
        manualNoRoxo.put("documentoId", d02());

        for (Map<String, Object> menor : List.of(planoNaMaster, planoNoAmarelo, anexoNoAmarelo, manualNoRoxo)) {
            assertThat(criar(rbs, menor)).as(menor.toString()).hasStatus(HttpStatus.CREATED);
        }
    }

    @Test
    void osErrosVemTodosJuntosNaMesmaResposta() {
        Map<String, Object> corpo = corpo("COTA", " ", "amanhã");
        corpo.put("hora", "6h");
        corpo.put("pagina", 0);

        MvcTestResult resposta = criar(rbs(), corpo);

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        @SuppressWarnings("unchecked")
        Map<String, String> campos = (Map<String, String>) lerJson(resposta, "$.campos");
        assertThat(campos.keySet()).containsExactlyInAnyOrder("cota", "titulo", "data", "hora", "pagina");
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("Confira os campos destacados e tente de novo.");
    }

    @Test
    void edicaoQueNaoExisteDa404() {
        MvcTestResult resposta = rbs().post("/api/eventos/999999/prazos", AtlasJson.escrever(corpo("GLOBAL", "Prazo", "2026-12-10")));

        assertThat(resposta).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("Não encontramos esta edição do festival.");
    }

    // ---------------------------------------------------------------------
    // Rota 62: listar
    // ---------------------------------------------------------------------

    @Test
    void listaPorDataHoraETituloSoDaEdicaoEOsAtivosPorPadrao() {
        SessaoDeApi rbs = rbs();
        long segundo = ApoioDePrazos.inserir(jdbc, "GLOBAL", null, null, "B segundo", java.time.LocalDate.of(2031, 5, 2), null, null, null, null);
        long primeiroDeManha = ApoioDePrazos.inserir(jdbc, "GLOBAL", null, null, "Z de manhã", java.time.LocalDate.of(2031, 5, 1),
                java.time.LocalTime.of(9, 0), null, null, null);
        long primeiroDeTarde = ApoioDePrazos.inserir(jdbc, "GLOBAL", null, null, "A de tarde", java.time.LocalDate.of(2031, 5, 1),
                java.time.LocalTime.of(18, 0), null, null, null);
        long diaInteiro = ApoioDePrazos.inserir(jdbc, "GLOBAL", null, null, "Dia inteiro", java.time.LocalDate.of(2031, 5, 1), null, null, null, null);
        long terceiro = ApoioDePrazos.inserir(jdbc, "GLOBAL", null, null, "A terceiro", java.time.LocalDate.of(2031, 5, 2), null, null, null, null);
        long desativado = ApoioDePrazos.inserir(jdbc, "GLOBAL", null, null, "Desativado", java.time.LocalDate.of(2031, 5, 1),
                java.time.LocalTime.of(8, 0), null, null, null);
        ApoioDePrazos.desativar(jdbc, desativado);
        // de outra edição: não aparece na lista da edição de 2027
        jdbc.sql("""
                INSERT INTO prazo (evento_id, escopo, titulo, data, criado_por) VALUES (:e, 'GLOBAL', 'De 2026', DATE '2031-05-01', :r)
                """).param("e", ApoioDePrazos.evento(jdbc, 2026)).param("r", ApoioDePrazos.rbs(jdbc)).update();

        MvcTestResult ativos = rbs.get("/api/eventos/" + evento2027() + "/prazos");
        MvcTestResult todos = rbs.get("/api/eventos/" + evento2027() + "/prazos?incluirInativos=true");

        assertThat(ativos).hasStatus(HttpStatus.OK);
        List<Integer> idsDosAtivos = lerLista(ativos, "$[?(@.data >= '2031-01-01')].id");
        assertThat(idsDosAtivos).containsExactly((int) primeiroDeManha, (int) primeiroDeTarde, (int) diaInteiro, (int) terceiro, (int) segundo);
        List<Integer> idsDeTodos = lerLista(todos, "$[?(@.data >= '2031-01-01')].id");
        assertThat(idsDeTodos).containsExactly((int) desativado, (int) primeiroDeManha, (int) primeiroDeTarde, (int) diaInteiro, (int) terceiro,
                (int) segundo);
        assertThat(lerLista(todos, "$[?(@.id == " + desativado + ")].ativo")).containsExactly(false);
        assertThat(lerLista(ativos, "$[?(@.titulo == 'De 2026')].id")).isEmpty();
    }

    @Test
    void listaTrazOMesmoObjetoDaRota63() {
        Map<String, Object> corpo = corpo("PARCERIA", "Montagem do espaço", "2027-01-20");
        corpo.put("parceriaId", amarelo());
        corpo.put("documentoId", d06());
        corpo.put("pagina", 2);
        SessaoDeApi rbs = rbs();
        long id = criarOk(rbs, corpo);

        MvcTestResult lista = rbs.get("/api/eventos/" + evento2027() + "/prazos");

        assertThat(lerLista(lista, "$[?(@.id == " + id + ")].parceria.patrocinador")).containsExactly("Cliente Amarelo");
        assertThat(lerLista(lista, "$[?(@.id == " + id + ")].documento.titulo")).containsExactly("Anexo da parceria · Cliente Amarelo");
        assertThat(lerLista(lista, "$[?(@.id == " + id + ")].pagina")).containsExactly(2);
        assertThat(lerLista(lista, "$[?(@.id == " + id + ")].criadoPor.perfil")).containsExactly("ADMIN_RBS");
    }

    @Test
    void listaDeEdicaoQueNaoExisteDa404EFiltroInvalidoDa400() {
        SessaoDeApi rbs = rbs();

        assertThat(rbs.get("/api/eventos/999999/prazos")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(rbs.get("/api/eventos/abc/prazos")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(rbs.get("/api/eventos/" + evento2027() + "/prazos?incluirInativos=talvez")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    // ---------------------------------------------------------------------
    // Rota 64: desativar
    // ---------------------------------------------------------------------

    @Test
    void desativarDa204NaoApagaESomeDaListaPadrao() {
        SessaoDeApi rbs = rbs();
        long id = criarOk(rbs, corpo("GLOBAL", "Reunião de alinhamento", "2026-12-10"));

        MvcTestResult resposta = rbs.delete("/api/prazos/" + id);

        assertThat(resposta).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(jdbc.sql("SELECT ativo FROM prazo WHERE id = :id").param("id", id).query(Boolean.class).single()).isFalse();
        assertThat(lerLista(rbs.get("/api/eventos/" + evento2027() + "/prazos"), "$[?(@.id == " + id + ")]")).isEmpty();
        assertThat(lerLista(rbs.get("/api/eventos/" + evento2027() + "/prazos?incluirInativos=true"), "$[?(@.id == " + id + ")].ativo"))
                .containsExactly(false);
        var linha = jdbc.sql("SELECT valor_anterior, valor_novo, detalhe FROM historico WHERE entidade = 'prazo' AND entidade_id = :id AND acao = 'ALTERACAO_STATUS'")
                .param("id", id).query().singleRow();
        assertThat(linha.get("valor_anterior")).isEqualTo("ATIVO");
        assertThat(linha.get("valor_novo")).isEqualTo("DESATIVADO");
    }

    @Test
    void desativarDeNovoTambemDa204SemGravarOutraLinhaNoHistorico() {
        SessaoDeApi rbs = rbs();
        long id = criarOk(rbs, corpo("GLOBAL", "Reunião de alinhamento", "2026-12-10"));
        rbs.delete("/api/prazos/" + id);

        MvcTestResult denovo = rbs.delete("/api/prazos/" + id);

        assertThat(denovo).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(jdbc.sql("SELECT count(*) FROM historico WHERE entidade = 'prazo' AND entidade_id = :id AND acao = 'ALTERACAO_STATUS'")
                .param("id", id).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void desativarOQueNaoExisteDa404() {
        SessaoDeApi rbs = rbs();

        MvcTestResult resposta = rbs.delete("/api/prazos/999999");

        assertThat(resposta).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("Não encontramos este prazo.");
        assertThat(rbs.delete("/api/prazos/abc")).hasStatus(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------------
    // Quem pode: só a RBS, e a permissão vem antes do corpo
    // ---------------------------------------------------------------------

    @Test
    void semSessaoAsTresRotasDao401() {
        SessaoDeApi anonima = SessaoDeApi.anonima(mvc);

        assertThat(anonima.get("/api/eventos/" + evento2027() + "/prazos")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(anonima.post("/api/eventos/" + evento2027() + "/prazos", AtlasJson.escrever(corpo("GLOBAL", "Prazo", "2026-12-10"))))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(anonima.delete("/api/prazos/1")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aMarcaRecebe403NasTresRotasQualquerQueSejaOCorpoOuOId() {
        SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);
        long id = criarOk(rbs(), corpo("GLOBAL", "Reunião de alinhamento", "2026-12-10"));

        for (String corpo : List.of("", "{}", "isto nao e json", "[]", "{\"escopo\":\"TODOS\",\"titulo\":\"\"}",
                AtlasJson.escrever(corpo("GLOBAL", "Prazo", "2026-12-10")))) {
            for (String uri : List.of("/api/eventos/" + evento2027() + "/prazos", "/api/eventos/999999/prazos", "/api/eventos/abc/prazos")) {
                MvcTestResult resposta = amarelo.post(uri, corpo);
                assertThat(resposta).as("POST " + uri + " [" + corpo + "]").hasStatus(HttpStatus.FORBIDDEN);
                assertThat(lerJson(resposta, "$.detail")).isEqualTo(SO_RBS);
            }
        }
        for (String uri : List.of("/api/eventos/" + evento2027() + "/prazos", "/api/eventos/" + evento2027() + "/prazos?incluirInativos=true",
                "/api/eventos/" + evento2027() + "/prazos?incluirInativos=talvez", "/api/eventos/999999/prazos", "/api/eventos/abc/prazos")) {
            assertThat(amarelo.get(uri)).as("GET " + uri).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(amarelo.head(uri)).as("HEAD " + uri).hasStatus(HttpStatus.FORBIDDEN);
        }
        for (String uri : List.of("/api/prazos/" + id, "/api/prazos/999999", "/api/prazos/abc")) {
            MvcTestResult resposta = amarelo.delete(uri);
            assertThat(resposta).as("DELETE " + uri).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo(SO_RBS);
        }
        assertThat(jdbc.sql("SELECT ativo FROM prazo WHERE id = :id").param("id", id).query(Boolean.class).single())
                .as("a marca não desativou nada").isTrue();
    }

    @Test
    void semOTokenCsrfOsMetodosQueAlteramDao403() {
        SessaoDeApi rbs = rbs();
        long id = criarOk(rbs, corpo("GLOBAL", "Reunião de alinhamento", "2026-12-10"));

        assertThat(rbs.postSemCsrf("/api/eventos/" + evento2027() + "/prazos", AtlasJson.escrever(corpo("GLOBAL", "Prazo", "2026-12-10"))))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(rbs.semCsrf(HttpMethod.DELETE, "/api/prazos/" + id, null)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(jdbc.sql("SELECT ativo FROM prazo WHERE id = :id").param("id", id).query(Boolean.class).single()).isTrue();
    }

    @Test
    void corpoQueNaoEJsonDa400ParaARbs() {
        SessaoDeApi rbs = rbs();

        assertThat(rbs.post("/api/eventos/" + evento2027() + "/prazos", "")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(rbs.post("/api/eventos/" + evento2027() + "/prazos", "isto nao e json")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    // ---------------------------------------------------------------------
    // O histórico do prazo é da RBS: a marca não o vê na rota 20
    // ---------------------------------------------------------------------

    @Test
    void aMarcaNaoVeNoHistoricoDaParceriaAsLinhasDosPrazos() {
        SessaoDeApi rbs = rbs();
        Map<String, Object> corpo = corpo("PARCERIA", "Montagem do espaço", "2027-01-20");
        corpo.put("parceriaId", amarelo());
        long id = criarOk(rbs, corpo);
        rbs.delete("/api/prazos/" + id);
        SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);

        MvcTestResult daMarca = amarelo.get("/api/parcerias/" + amarelo() + "/historico?tamanho=200");
        MvcTestResult daRbs = rbs.get("/api/parcerias/" + amarelo() + "/historico?tamanho=200");

        assertThat(daMarca).hasStatus(HttpStatus.OK);
        assertThat(lerLista(daMarca, "$.itens[?(@.entidade == 'prazo')]")).isEmpty();
        MvcTestResult filtrada = amarelo.get("/api/parcerias/" + amarelo() + "/historico?entidade=prazo");
        assertThat(filtrada).hasStatus(HttpStatus.OK);
        assertThat(lerJson(filtrada, "$.total")).as("nem o total, nem um filtro por entidade=prazo").isEqualTo(0);
        assertThat(lerLista(daRbs, "$.itens[?(@.entidade == 'prazo')].acao")).containsExactlyInAnyOrder("CRIACAO", "ALTERACAO_STATUS");
    }
}
