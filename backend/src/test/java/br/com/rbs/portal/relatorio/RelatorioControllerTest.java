package br.com.rbs.portal.relatorio;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.ApoioDeTeste;
import br.com.rbs.portal.ApoioDeTeste.Csrf;
import br.com.rbs.portal.TesteDeIntegracao;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Testa as rotas 31 a 36 (relatórios) como o front as usa: login pela rota, sessão de verdade e token CSRF em cada alteração.
 * Atende o item 6 da Entrega 2. [RELATORIO] [PERMISSAO] [ISOLAMENTO] [HISTORICO] Cada teste é desfeito no fim
 * (@Transactional). Uma recusa do banco aborta a transação do teste, então o teste que provoca um 409 termina nele.
 */
@TesteDeIntegracao
@Transactional
class RelatorioControllerTest {

    private static final String SENHA = "senha123";
    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String ROXO = "marketing@roxo.example";
    /** As 6 seções do modelo do relatório, na ordem do modelo. */
    private static final List<String> SECOES_DO_MODELO = List.of("Cumprimento das entregas", "Mídia: previsto x exibido",
            "Ativação no evento", "Pesquisa da ativação", "Cortesias e credenciamento", "O festival em números");
    /** Os 17 indicadores do catálogo, na ordem do modelo. */
    private static final List<String> INDICADORES_DO_MODELO = List.of("ENTREGAS_CONCLUIDAS_PCT", "ENTREGAS_PENDENTES",
            "MIDIA_PREVISTO", "MIDIA_EXIBIDO", "MIDIA_ENTREGA_PCT", "MIDIA_VEICULO_PCT",
            "APLICACOES_PREVISTAS", "APLICACOES_COMPROVADAS", "APLICACOES_COMPROVADAS_PCT", "COMPROVACOES_ENVIADAS",
            "PESQUISA_BASE", "PESQUISA_RESULTADO",
            "CORTESIAS_DISPONIVEIS", "CORTESIAS_DESTINADAS_PCT", "CREDENCIAIS_DIA",
            "FESTIVAL_NUMERO", "FESTIVAL_PESQUISA");
    private static final String FRASE_DE_PULO =
            "O relatório não pode ir de RASCUNHO para DISPONIBILIZADO. A ordem é RASCUNHO, VALIDADO, DISPONIBILIZADO.";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    // ---------------------------------------------------------------------
    // Ajudas: login pela rota, chamadas com sessão e CSRF, dados da amostra
    // ---------------------------------------------------------------------

    MockHttpSession entrar(String email) {
        MockHttpSession sessao = new MockHttpSession();
        Csrf csrf = ApoioDeTeste.pedirCsrf(mvc);
        MvcTestResult login = mvc.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("X-XSRF-TOKEN", csrf.token()).header("User-Agent", "TesteDeRelatorio/1.0").cookie(csrf.cookie())
                .session(sessao).content("{\"email\":\"" + email + "\",\"senha\":\"" + SENHA + "\"}").exchange();
        assertThat(login).hasStatus(HttpStatus.OK);
        return sessao;
    }

    MvcTestResult ler(MockHttpSession sessao, String uri) {
        return mvc.get().uri(uri).header("User-Agent", "TesteDeRelatorio/1.0").session(sessao).exchange();
    }

    /** POST ou PATCH com o token CSRF, como o front faz (cookie XSRF-TOKEN e cabeçalho X-XSRF-TOKEN). */
    MvcTestResult enviar(MockHttpSession sessao, HttpMethod metodo, String uri, String json) {
        Csrf csrf = ApoioDeTeste.pedirCsrf(mvc);
        var pedido = mvc.method(metodo).uri(uri).header("X-XSRF-TOKEN", csrf.token())
                .header("User-Agent", "TesteDeRelatorio/1.0").cookie(csrf.cookie()).session(sessao);
        if (json != null) {
            pedido.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return pedido.exchange();
    }

    MvcTestResult gerar(MockHttpSession sessao, long parceriaId) {
        return enviar(sessao, HttpMethod.POST, "/api/parcerias/" + parceriaId + "/relatorios", null);
    }

    MvcTestResult mudarStatus(MockHttpSession sessao, long relatorioId, String status) {
        return enviar(sessao, HttpMethod.PATCH, "/api/relatorios/" + relatorioId + "/status",
                "{\"status\":\"" + status + "\"}");
    }

    long parceria(String cliente) {
        return jdbc.sql("""
                        SELECT pa.id FROM parceria pa JOIN patrocinador p ON p.id = pa.patrocinador_id
                        JOIN evento e ON e.id = pa.evento_id WHERE p.nome_canonico = :cliente AND e.edicao = 2027
                        """).param("cliente", cliente).query(Long.class).single();
    }

    long usuarioId(String email) {
        return jdbc.sql("SELECT id FROM usuario WHERE email = :email").param("email", email).query(Long.class).single();
    }

    /** O relatório 1 de cada parceria de 2027 é o rascunho que a amostra gerou; a versão mais nova é a que o teste gerou. */
    long relatorioDaAmostra(long parceriaId) {
        return jdbc.sql("SELECT id FROM relatorio WHERE parceria_id = :p AND versao = 1").param("p", parceriaId)
                .query(Long.class).single();
    }

    long contarHistorico(String acao, long relatorioId) {
        return jdbc.sql("SELECT count(*) FROM historico WHERE acao = :acao AND entidade = 'relatorio' AND entidade_id = :id")
                .param("acao", acao).param("id", relatorioId).query(Long.class).single();
    }

    /** Um indicador do relatório (pelo código e pelo recorte) como mapa do JSON. */
    Map<String, Object> indicador(MvcTestResult resposta, String codigo, String recorte) {
        List<Map<String, Object>> todos = lerLista(resposta, "$.secoes[*].indicadores[*]");
        return todos.stream().filter(i -> codigo.equals(i.get("codigo")) && recorte.equals(i.get("recorte")))
                .findFirst().orElseThrow(() -> new AssertionError("Sem o indicador " + codigo + " / " + recorte));
    }

    static BigDecimal numero(Object valorDoJson) {
        return new BigDecimal(String.valueOf(valorDoJson));
    }

    static String texto(MvcTestResult resposta) {
        return new String(resposta.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------------
    // Rota 33: gerar. O relatório sai da base, sem passo manual
    // ---------------------------------------------------------------------

    @Test
    void rbsGeraORelatorioDoAmareloEOsNumerosBatemComOBanco() {
        long amarelo = parceria("Cliente Amarelo");
        MockHttpSession rbs = entrar(RBS);

        MvcTestResult resposta = gerar(rbs, amarelo);

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        long id = ((Number) lerJson(resposta, "$.id")).longValue();
        assertThat(resposta.getResponse().getHeader("Location")).isEqualTo("/api/relatorios/" + id);
        assertThat(lerJson(resposta, "$.parceriaId")).isEqualTo((int) amarelo);
        assertThat(lerJson(resposta, "$.versao")).as("a amostra já tem a versão 1").isEqualTo(2);
        assertThat(lerJson(resposta, "$.status")).isEqualTo("RASCUNHO");
        assertThat(lerJson(resposta, "$.geradoPor.nome")).isEqualTo("Equipe B2B RBS");
        assertThat(lerJson(resposta, "$.geradoPor.perfil")).isEqualTo("ADMIN_RBS");
        assertThat((String) lerJson(resposta, "$.geradoEm")).as("data com o fuso de Brasília").endsWith("-03:00");
        assertThat(lerJson(resposta, "$.validadoEm")).isNull();
        assertThat(lerJson(resposta, "$.disponibilizadoEm")).isNull();

        // As seções seguem o catálogo; o Amarelo é Master, então tem a pesquisa da ativação.
        assertThat(lerLista(resposta, "$.secoes[*].secao")).containsExactly(
                "Cumprimento das entregas", "Mídia: previsto x exibido", "Ativação no evento", "Pesquisa da ativação",
                "Cortesias e credenciamento", "O festival em números");
        assertThat(lerLista(resposta, "$.secoes[0].indicadores[*].codigo"))
                .containsExactly("ENTREGAS_CONCLUIDAS_PCT", "ENTREGAS_PENDENTES");

        // Indicador 1, conferido por SQL direto na tabela de entregas (não pela visão que a função usa).
        BigDecimal pctEntregas = jdbc.sql("""
                        SELECT round(100.0 * count(*) FILTER (WHERE status = 'APROVADO')
                                     / count(*) FILTER (WHERE status <> 'NAO_APLICAVEL'), 1)
                        FROM entrega WHERE parceria_id = :p
                        """).param("p", amarelo).query(BigDecimal.class).single();
        Map<String, Object> entregas = indicador(resposta, "ENTREGAS_CONCLUIDAS_PCT", "");
        assertThat(numero(entregas.get("valor"))).isEqualByComparingTo(pctEntregas).isEqualByComparingTo("90.9");
        assertThat(entregas).containsEntry("unidade", "%").containsEntry("disponivel", true)
                .containsEntry("nome", "Entregas concluídas").containsEntry("baseCalculo", "10 aprovadas de 11 aplicáveis");

        // Indicador 2, conferido na tabela do plano comercial e na das veiculações.
        BigDecimal previsto = jdbc.sql("""
                        SELECT sum(quantidade_prevista) FROM aproveitamento_previsto
                        WHERE parceria_id = :p AND categoria = 'MIDIA' AND unidade = 'INSERCAO'
                        """).param("p", amarelo).query(BigDecimal.class).single();
        BigDecimal exibido = jdbc.sql("""
                        SELECT sum(v.quantidade) FROM veiculacao v JOIN aproveitamento_previsto ap ON ap.id = v.aproveitamento_id
                        WHERE ap.parceria_id = :p AND ap.categoria = 'MIDIA' AND ap.unidade = 'INSERCAO'
                        """).param("p", amarelo).query(BigDecimal.class).single();
        assertThat(numero(indicador(resposta, "MIDIA_PREVISTO", "INSERCAO").get("valor"))).isEqualByComparingTo(previsto);
        assertThat(numero(indicador(resposta, "MIDIA_EXIBIDO", "INSERCAO").get("valor"))).isEqualByComparingTo(exibido);
        assertThat(previsto).isEqualByComparingTo("1113");
        assertThat(exibido).isEqualByComparingTo("1105");

        // Um terceiro, das cortesias.
        BigDecimal cortesias = jdbc.sql("SELECT sum(quantidade) FROM cortesia_cota WHERE parceria_id = :p")
                .param("p", amarelo).query(BigDecimal.class).single();
        assertThat(numero(indicador(resposta, "CORTESIAS_DISPONIVEIS", "").get("valor"))).isEqualByComparingTo(cortesias);

        // [RELATORIO] Sem dado na base: disponivel false e valor null, nunca zero. Com dado: valor sempre preenchido.
        List<Map<String, Object>> todos = lerLista(resposta, "$.secoes[*].indicadores[*]");
        assertThat(todos).filteredOn(i -> Boolean.FALSE.equals(i.get("disponivel")))
                .extracting(i -> i.get("codigo")).containsExactlyInAnyOrder("FESTIVAL_NUMERO", "FESTIVAL_PESQUISA");
        assertThat(todos).filteredOn(i -> Boolean.FALSE.equals(i.get("disponivel")))
                .allSatisfy(i -> assertThat(i.get("valor")).isNull());
        assertThat(todos).filteredOn(i -> Boolean.TRUE.equals(i.get("disponivel")))
                .allSatisfy(i -> assertThat(i.get("valor")).isNotNull());

        // A resposta é a fotografia gravada: mesma contagem de números que a tabela relatorio_indicador.
        long gravados = jdbc.sql("SELECT count(*) FROM relatorio_indicador WHERE relatorio_id = :id").param("id", id)
                .query(Long.class).single();
        assertThat(todos).hasSize((int) gravados);

        // [HISTORICO] A função do banco grava a geração, uma vez, com o autor e a versão.
        var linha = jdbc.sql("""
                        SELECT usuario_id, parceria_id, valor_novo FROM historico
                        WHERE acao = 'GERACAO_RELATORIO' AND entidade = 'relatorio' AND entidade_id = :id
                        """).param("id", id).query().singleRow();
        assertThat(((Number) linha.get("usuario_id")).longValue()).isEqualTo(usuarioId(RBS));
        assertThat(((Number) linha.get("parceria_id")).longValue()).isEqualTo(amarelo);
        assertThat(linha.get("valor_novo")).isEqualTo("versão 2 (RASCUNHO)");
    }

    @Test
    void cadaGeracaoCriaUmaVersaoNovaEAVersaoAnteriorNaoMuda() {
        long amarelo = parceria("Cliente Amarelo");
        MockHttpSession rbs = entrar(RBS);

        MvcTestResult v2 = gerar(rbs, amarelo);
        MvcTestResult v3 = gerar(rbs, amarelo);

        assertThat(lerJson(v2, "$.versao")).isEqualTo(2);
        assertThat(lerJson(v3, "$.versao")).isEqualTo(3);
        assertThat(lerJson(v3, "$.id")).isNotEqualTo(lerJson(v2, "$.id"));
        MvcTestResult v1 = ler(rbs, "/api/parcerias/" + amarelo + "/relatorio?versao=1");
        assertThat(v1).hasStatus(HttpStatus.OK);
        assertThat(lerJson(v1, "$.versao")).isEqualTo(1);
    }

    @Test
    void gerarParaParceriaQueNaoExisteDevolve404() {
        MockHttpSession rbs = entrar(RBS);

        MvcTestResult resposta = gerar(rbs, 999_999L);

        assertThat(resposta).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(resposta.getResponse().getContentType()).startsWith("application/problem+json");
    }

    // ---------------------------------------------------------------------
    // Rotas 31 e 32: situação de cada parceria, e o CSV da lista
    // ---------------------------------------------------------------------

    @Test
    void listaMostraSituacaoNumerosEFaltantesDeCadaParceriaDaEdicaoMaisNova() {
        MockHttpSession rbs = entrar(RBS);
        long amarelo = parceria("Cliente Amarelo");

        MvcTestResult resposta = ler(rbs, "/api/relatorios");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerLista(resposta, "$[*].patrocinador")).containsExactly("Cliente Amarelo", "Cliente Roxo", "Cliente Verde");
        assertThat(lerJson(resposta, "$[0].parceriaId")).isEqualTo((int) amarelo);
        assertThat(lerJson(resposta, "$[0].cota.codigo")).isEqualTo("MASTER");
        assertThat(lerJson(resposta, "$[0].cota.nome")).isEqualTo("Master");
        assertThat(lerJson(resposta, "$[0].evento.edicao")).isEqualTo(2027);
        assertThat(lerJson(resposta, "$[0].situacao")).isEqualTo("RASCUNHO");
        assertThat(lerJson(resposta, "$[0].relatorio.versao")).isEqualTo(1);
        assertThat(lerJson(resposta, "$[0].relatorio.status")).isEqualTo("RASCUNHO");
        assertThat(lerJson(resposta, "$[0].relatorio.validadoEm")).isNull();

        // Os números da lista batem com a tabela relatorio_indicador e com a visão de faltantes.
        long relatorio = relatorioDaAmostra(amarelo);
        long disponiveis = jdbc.sql("SELECT count(DISTINCT indicador_codigo) FROM relatorio_indicador WHERE relatorio_id = :id AND disponivel")
                .param("id", relatorio).query(Long.class).single();
        long indisponiveis = jdbc.sql("""
                        SELECT count(*) FROM indicador i WHERE NOT EXISTS (SELECT 1 FROM relatorio_indicador x
                        WHERE x.relatorio_id = :id AND x.indicador_codigo = i.codigo AND x.disponivel)
                        """).param("id", relatorio).query(Long.class).single();
        long faltantes = jdbc.sql("SELECT count(*) FROM vw_faltantes WHERE parceria_id = :p").param("p", amarelo)
                .query(Long.class).single();
        assertThat(lerJson(resposta, "$[0].relatorio.indicadoresDisponiveis")).isEqualTo((int) disponiveis);
        assertThat(lerJson(resposta, "$[0].relatorio.indicadoresIndisponiveis")).isEqualTo((int) indisponiveis);
        assertThat(indisponiveis).isEqualTo(2);
        assertThat(lerJson(resposta, "$[0].faltantes")).isEqualTo((int) faltantes);
        assertThat(faltantes).as("o Amarelo tem faltantes na amostra").isGreaterThan(0);
    }

    @Test
    void listaMostraSempreAVersaoMaisNovaEFiltraPorStatusEEdicao() {
        MockHttpSession rbs = entrar(RBS);
        long amarelo = parceria("Cliente Amarelo");
        long v2 = ((Number) lerJson(gerar(rbs, amarelo), "$.id")).longValue();
        assertThat(mudarStatus(rbs, v2, "VALIDADO")).hasStatus(HttpStatus.OK);

        MvcTestResult todas = ler(rbs, "/api/relatorios");
        assertThat(lerJson(todas, "$[0].relatorio.versao")).isEqualTo(2);
        assertThat(lerJson(todas, "$[0].situacao")).isEqualTo("VALIDADO");
        assertThat(lerLista(todas, "$[*].situacao")).containsExactly("VALIDADO", "RASCUNHO", "RASCUNHO");

        MvcTestResult validados = ler(rbs, "/api/relatorios?status=VALIDADO");
        assertThat(lerLista(validados, "$[*].patrocinador")).containsExactly("Cliente Amarelo");
        MvcTestResult rascunhos = ler(rbs, "/api/relatorios?status=RASCUNHO");
        assertThat(lerLista(rascunhos, "$[*].patrocinador")).containsExactly("Cliente Roxo", "Cliente Verde");
        assertThat(lerLista(ler(rbs, "/api/relatorios?status=DISPONIBILIZADO"), "$")).isEmpty();

        // A edição de 2026 tem uma parceria (sem usuário) e nenhum relatório.
        long evento2026 = jdbc.sql("SELECT id FROM evento WHERE edicao = 2026").query(Long.class).single();
        MvcTestResult de2026 = ler(rbs, "/api/relatorios?eventoId=" + evento2026);
        assertThat(lerLista(de2026, "$")).hasSize(1);
        assertThat(lerJson(de2026, "$[0].situacao")).isEqualTo("SEM_RELATORIO");
        assertThat(lerJson(de2026, "$[0].relatorio")).isNull();
        assertThat(lerJson(de2026, "$[0].evento.edicao")).isEqualTo(2026);
        assertThat(lerLista(ler(rbs, "/api/relatorios?eventoId=" + evento2026 + "&status=SEM_RELATORIO"), "$")).hasSize(1);
        assertThat(lerLista(ler(rbs, "/api/relatorios?status=SEM_RELATORIO"), "$")).isEmpty();
    }

    @Test
    void statusDeFiltroInexistenteDevolve422() {
        MockHttpSession rbs = entrar(RBS);

        MvcTestResult resposta = ler(rbs, "/api/relatorios?status=PUBLICADO");

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(resposta, "$.campos.status")).isNotNull();
    }

    @Test
    void csvDaListaTemBomPontoEVirgulaECabecalho() {
        MockHttpSession rbs = entrar(RBS);

        MvcTestResult resposta = ler(rbs, "/api/relatorios.csv");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(resposta.getResponse().getContentType()).isEqualTo("text/csv; charset=UTF-8");
        assertThat(resposta.getResponse().getHeader("Content-Disposition")).matches("attachment; filename=\"relatorios-\\d{4}-\\d{2}-\\d{2}\\.csv\"");
        byte[] bytes = resposta.getResponse().getContentAsByteArray();
        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String[] linhas = texto(resposta).substring(1).split("\r\n");
        assertThat(linhas[0]).isEqualTo("parceria_id;patrocinador;cota;edicao;situacao;versao;gerado_em;validado_em;"
                + "disponibilizado_em;indicadores_disponiveis;indicadores_indisponiveis;faltantes;marca_ve_versao;"
                + "marca_ve_disponibilizado_em");
        assertThat(linhas).hasSize(4);
        assertThat(linhas[1]).startsWith(parceria("Cliente Amarelo") + ";Cliente Amarelo;Master;2027;RASCUNHO;1;");
        String[] amarelo = linhas[1].split(";", -1);
        assertThat(amarelo).as("14 colunas, com validado_em, disponibilizado_em e as duas da marca em branco").hasSize(14);
        assertThat(amarelo[6]).as("gerado_em: um horário de Brasília no passado").endsWith("-03:00");
        assertThat(java.time.OffsetDateTime.parse(amarelo[6])).isBefore(java.time.OffsetDateTime.now());
        assertThat(amarelo[7]).isEmpty();
        assertThat(amarelo[8]).isEmpty();
        assertThat(amarelo[9]).isEqualTo("15");
        assertThat(amarelo[10]).isEqualTo("2");
        assertThat(amarelo[12]).as("nenhuma versão disponibilizada: a marca não vê nada").isEmpty();
        assertThat(amarelo[13]).isEmpty();
    }

    @Test
    void csvDaListaFiltraComOsMesmosParametrosDaLista() {
        MockHttpSession rbs = entrar(RBS);

        MvcTestResult resposta = ler(rbs, "/api/relatorios.csv?status=DISPONIBILIZADO");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(texto(resposta).substring(1).split("\r\n")).as("só o cabeçalho").hasSize(1);
    }

    // ---------------------------------------------------------------------
    // Rota 34: mudar o status. O gatilho do banco impõe a ordem
    // ---------------------------------------------------------------------

    @Test
    void ordemCertaFuncionaEGravaQuemFezEQuando() {
        long amarelo = parceria("Cliente Amarelo");
        long relatorio = relatorioDaAmostra(amarelo);
        MockHttpSession rbs = entrar(RBS);

        MvcTestResult validado = mudarStatus(rbs, relatorio, "VALIDADO");

        assertThat(validado).hasStatus(HttpStatus.OK);
        assertThat(lerJson(validado, "$.status")).isEqualTo("VALIDADO");
        assertThat(lerJson(validado, "$.validadoEm")).isNotNull();
        assertThat(lerJson(validado, "$.disponibilizadoEm")).isNull();
        assertThat(lerJson(validado, "$.geradoPor.nome")).isEqualTo("Equipe B2B RBS");
        var etapa = jdbc.sql("SELECT status, validado_por, validado_em, disponibilizado_por FROM relatorio WHERE id = :id")
                .param("id", relatorio).query().singleRow();
        assertThat(((Number) etapa.get("validado_por")).longValue()).isEqualTo(usuarioId(RBS));
        assertThat(etapa.get("validado_em")).isNotNull();
        assertThat(etapa.get("disponibilizado_por")).isNull();

        MvcTestResult disponibilizado = mudarStatus(rbs, relatorio, "DISPONIBILIZADO");

        assertThat(disponibilizado).hasStatus(HttpStatus.OK);
        assertThat(lerJson(disponibilizado, "$.status")).isEqualTo("DISPONIBILIZADO");
        assertThat(lerJson(disponibilizado, "$.validadoEm")).isNotNull();
        assertThat(lerJson(disponibilizado, "$.disponibilizadoEm")).isNotNull();
        var fim = jdbc.sql("SELECT status, disponibilizado_por, disponibilizado_em FROM relatorio WHERE id = :id")
                .param("id", relatorio).query().singleRow();
        assertThat(fim.get("status")).isEqualTo("DISPONIBILIZADO");
        assertThat(((Number) fim.get("disponibilizado_por")).longValue()).isEqualTo(usuarioId(RBS));
        assertThat(fim.get("disponibilizado_em")).isNotNull();

        // [HISTORICO] Uma linha por etapa, com autor, parceria, status anterior e novo, IP e navegador.
        var validacao = jdbc.sql("""
                        SELECT usuario_id, parceria_id, valor_anterior, valor_novo, detalhe, host(ip) AS ip, user_agent
                        FROM historico WHERE acao = 'VALIDACAO' AND entidade = 'relatorio' AND entidade_id = :id
                        """).param("id", relatorio).query().singleRow();
        assertThat(((Number) validacao.get("usuario_id")).longValue()).isEqualTo(usuarioId(RBS));
        assertThat(((Number) validacao.get("parceria_id")).longValue()).isEqualTo(amarelo);
        assertThat(validacao.get("valor_anterior")).isEqualTo("RASCUNHO");
        assertThat(validacao.get("valor_novo")).isEqualTo("VALIDADO");
        assertThat(validacao.get("detalhe")).isEqualTo("versão 1");
        assertThat(validacao.get("ip")).isEqualTo("127.0.0.1");
        assertThat(validacao.get("user_agent")).isEqualTo("TesteDeRelatorio/1.0");
        var liberacao = jdbc.sql("""
                        SELECT valor_anterior, valor_novo FROM historico
                        WHERE acao = 'DISPONIBILIZACAO' AND entidade = 'relatorio' AND entidade_id = :id
                        """).param("id", relatorio).query().singleRow();
        assertThat(liberacao.get("valor_anterior")).isEqualTo("VALIDADO");
        assertThat(liberacao.get("valor_novo")).isEqualTo("DISPONIBILIZADO");
    }

    @Test
    void pedirOStatusQueJaTemNaoMudaNadaENaoGravaHistorico() {
        long relatorio = relatorioDaAmostra(parceria("Cliente Amarelo"));
        MockHttpSession rbs = entrar(RBS);
        assertThat(mudarStatus(rbs, relatorio, "VALIDADO")).hasStatus(HttpStatus.OK);

        MvcTestResult repetido = mudarStatus(rbs, relatorio, "VALIDADO");

        assertThat(repetido).hasStatus(HttpStatus.OK);
        assertThat(lerJson(repetido, "$.status")).isEqualTo("VALIDADO");
        assertThat(contarHistorico("VALIDACAO", relatorio)).isEqualTo(1);
    }

    @Test
    void statusQueNaoExisteDevolve422EStatusVazioTambem() {
        long relatorio = relatorioDaAmostra(parceria("Cliente Amarelo"));
        MockHttpSession rbs = entrar(RBS);

        MvcTestResult inexistente = mudarStatus(rbs, relatorio, "PUBLICADO");
        MvcTestResult vazio = enviar(rbs, HttpMethod.PATCH, "/api/relatorios/" + relatorio + "/status", "{}");

        assertThat(inexistente).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(inexistente, "$.campos.status")).isEqualTo("Status inválido. Use VALIDADO ou DISPONIBILIZADO.");
        assertThat(vazio).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(vazio, "$.campos.status")).isEqualTo("Informe o status.");
    }

    @Test
    void mudarStatusDeRelatorioQueNaoExisteDevolve404() {
        MockHttpSession rbs = entrar(RBS);

        assertThat(mudarStatus(rbs, 999_999L, "VALIDADO")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void voltarDeStatusDevolve409ComAFraseDoBanco() {
        long relatorio = relatorioDaAmostra(parceria("Cliente Amarelo"));
        MockHttpSession rbs = entrar(RBS);
        assertThat(mudarStatus(rbs, relatorio, "VALIDADO")).hasStatus(HttpStatus.OK);
        assertThat(mudarStatus(rbs, relatorio, "DISPONIBILIZADO")).hasStatus(HttpStatus.OK);

        MvcTestResult resposta = mudarStatus(rbs, relatorio, "VALIDADO");

        assertThat(resposta).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(resposta, "$.detail"))
                .isEqualTo("O relatório não pode ir de DISPONIBILIZADO para VALIDADO. A ordem é RASCUNHO, VALIDADO, DISPONIBILIZADO.");
        // A recusa do banco aborta a transação do teste: nada mais pode ser consultado depois dela.
    }

    @Test
    void pularDeRascunhoDireitoParaDisponibilizadoDevolve409ComAFraseDoBanco() {
        long relatorio = relatorioDaAmostra(parceria("Cliente Amarelo"));
        MockHttpSession rbs = entrar(RBS);
        assertThat(contarHistorico("DISPONIBILIZACAO", relatorio)).isZero();

        MvcTestResult resposta = mudarStatus(rbs, relatorio, "DISPONIBILIZADO");

        assertThat(resposta).hasStatus(HttpStatus.CONFLICT);
        assertThat(resposta.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(resposta, "$.title")).isEqualTo("Conflito");
        assertThat(lerJson(resposta, "$.detail")).isEqualTo(FRASE_DE_PULO);
        assertThat(corpo(resposta)).as("sem rastro do PostgreSQL").doesNotContain("rn_relatorio").doesNotContain("ERROR");
        // A recusa do banco aborta a transação do teste: nada mais pode ser consultado depois dela.
    }

    // ---------------------------------------------------------------------
    // Rota 35: ver o relatório. O patrocinador só vê o disponibilizado mais novo
    // ---------------------------------------------------------------------

    @Test
    void patrocinadorRecebe404EnquantoSoHaRascunhoEDepoisVeORelatorioSemQuemGerou() {
        long amarelo = parceria("Cliente Amarelo");
        MockHttpSession rbs = entrar(RBS);
        MockHttpSession marketing = entrar(AMARELO);
        String rota = "/api/parcerias/" + amarelo + "/relatorio";

        // Só existe o rascunho da amostra (versão 1).
        MvcTestResult semNada = ler(marketing, rota);
        assertThat(semNada).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(semNada, "$.detail")).isEqualTo("O relatório desta parceria ainda não está disponível.");

        // Gerado e validado ainda não é disponibilizado.
        long v2 = ((Number) lerJson(gerar(rbs, amarelo), "$.id")).longValue();
        assertThat(mudarStatus(rbs, v2, "VALIDADO")).hasStatus(HttpStatus.OK);
        assertThat(ler(marketing, rota)).hasStatus(HttpStatus.NOT_FOUND);

        assertThat(mudarStatus(rbs, v2, "DISPONIBILIZADO")).hasStatus(HttpStatus.OK);
        MvcTestResult visto = ler(marketing, rota);
        assertThat(visto).hasStatus(HttpStatus.OK);
        assertThat(lerJson(visto, "$.id")).isEqualTo((int) v2);
        assertThat(lerJson(visto, "$.versao")).isEqualTo(2);
        assertThat(lerJson(visto, "$.status")).isEqualTo("DISPONIBILIZADO");
        assertThat(lerJson(visto, "$.geradoPor")).as("quem gerou é dado interno").isNull();
        assertThat(corpo(visto)).contains("\"geradoPor\":null").doesNotContain("Equipe B2B");
        assertThat(lerJson(visto, "$.validadoEm")).isNotNull();
        assertThat(lerJson(visto, "$.disponibilizadoEm")).isNotNull();
        assertThat(lerLista(visto, "$.secoes")).hasSize(6);
        assertThat(numero(indicador(visto, "ENTREGAS_CONCLUIDAS_PCT", "").get("valor"))).isEqualByComparingTo("90.9");

        // Uma versão nova em rascunho não esconde a disponibilizada, e ?versao= não vale para o patrocinador.
        MvcTestResult v3 = gerar(rbs, amarelo);
        assertThat(lerJson(v3, "$.versao")).isEqualTo(3);
        assertThat(lerJson(ler(marketing, rota), "$.versao")).isEqualTo(2);
        assertThat(lerJson(ler(marketing, rota + "?versao=1"), "$.versao")).isEqualTo(2);
        // A RBS vê a mais nova de qualquer status, ou a versão pedida.
        assertThat(lerJson(ler(rbs, rota), "$.versao")).isEqualTo(3);
        assertThat(lerJson(ler(rbs, rota), "$.status")).isEqualTo("RASCUNHO");
        assertThat(lerJson(ler(rbs, rota + "?versao=2"), "$.status")).isEqualTo("DISPONIBILIZADO");
        assertThat(lerJson(ler(rbs, rota + "?versao=2"), "$.geradoPor.nome")).isEqualTo("Equipe B2B RBS");
    }

    @Test
    void agenciaVinculadaAoAmareloTambemVeORelatorioDisponibilizado() {
        long amarelo = parceria("Cliente Amarelo");
        MockHttpSession rbs = entrar(RBS);
        long relatorio = relatorioDaAmostra(amarelo);
        mudarStatus(rbs, relatorio, "VALIDADO");
        mudarStatus(rbs, relatorio, "DISPONIBILIZADO");

        MvcTestResult resposta = ler(entrar("atendimento@agencia-x.example"), "/api/parcerias/" + amarelo + "/relatorio");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.geradoPor")).isNull();
    }

    @Test
    void rbsPedindoVersaoQueNaoExisteOuParceriaSemRelatorioRecebe404() {
        MockHttpSession rbs = entrar(RBS);
        long amarelo = parceria("Cliente Amarelo");
        long parceria2026 = jdbc.sql("SELECT pa.id FROM parceria pa JOIN evento e ON e.id = pa.evento_id WHERE e.edicao = 2026")
                .query(Long.class).single();

        MvcTestResult semVersao = ler(rbs, "/api/parcerias/" + amarelo + "/relatorio?versao=99");
        MvcTestResult semRelatorio = ler(rbs, "/api/parcerias/" + parceria2026 + "/relatorio");
        MvcTestResult versaoZero = ler(rbs, "/api/parcerias/" + amarelo + "/relatorio?versao=0");

        assertThat(semVersao).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(semVersao, "$.detail")).isEqualTo("Não encontramos esta versão do relatório.");
        assertThat(semRelatorio).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(semRelatorio, "$.detail")).isEqualTo("Esta parceria ainda não tem relatório.");
        assertThat(versaoZero).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(versaoZero, "$.campos.versao")).isEqualTo("A versão começa em 1.");
    }

    @Test
    void indicadorQueNaoValeParaACotaOuNaoTemPlanoApareceIndisponivelComOMotivo() {
        MockHttpSession rbs = entrar(RBS);

        MvcTestResult roxo = ler(rbs, "/api/parcerias/" + parceria("Cliente Roxo") + "/relatorio");

        assertThat(roxo).hasStatus(HttpStatus.OK);
        assertThat(lerLista(roxo, "$.secoes[*].secao")).as("o Roxo (Subcota G) também traz a pesquisa da ativação, indisponível")
                .containsExactlyElementsOf(SECOES_DO_MODELO);
        Map<String, Object> base = indicador(roxo, "PESQUISA_BASE", "");
        assertThat(base).containsEntry("disponivel", false).containsEntry("baseCalculo", "não se aplica a esta cota");
        assertThat(base.get("valor")).isNull();
        assertThat(indicador(roxo, "PESQUISA_RESULTADO", "")).containsEntry("disponivel", false);
        // O Roxo não tem aplicações de marca no plano comercial: a seção "Ativação no evento" vem indisponível, com o motivo.
        Map<String, Object> aplicacoes = indicador(roxo, "APLICACOES_PREVISTAS", "");
        assertThat(aplicacoes).containsEntry("disponivel", false).containsEntry("baseCalculo", "sem aplicações de marca no plano comercial");
        // O que tem dado continua com dado.
        assertThat(indicador(roxo, "MIDIA_PREVISTO", "INSERCAO")).containsEntry("disponivel", true);
    }

    // ---------------------------------------------------------------------
    // Isolamento: o Roxo pede o que é do Amarelo e recebe 404, igual a um id que não existe
    // ---------------------------------------------------------------------

    @Test
    void roxoPedindoORelatorioEOCsvDoAmareloRecebe404() {
        long amarelo = parceria("Cliente Amarelo");
        MockHttpSession rbs = entrar(RBS);
        long disponibilizado = ((Number) lerJson(gerar(rbs, amarelo), "$.id")).longValue();
        mudarStatus(rbs, disponibilizado, "VALIDADO");
        mudarStatus(rbs, disponibilizado, "DISPONIBILIZADO");
        long rascunho = relatorioDaAmostra(amarelo);
        MockHttpSession roxo = entrar(ROXO);

        MvcTestResult relatorio = ler(roxo, "/api/parcerias/" + amarelo + "/relatorio");
        MvcTestResult relatorioInexistente = ler(roxo, "/api/parcerias/999999/relatorio");
        MvcTestResult csvDisponibilizado = ler(roxo, "/api/relatorios/" + disponibilizado + "/csv");
        MvcTestResult csvRascunho = ler(roxo, "/api/relatorios/" + rascunho + "/csv");
        MvcTestResult csvInexistente = ler(roxo, "/api/relatorios/999999/csv");

        for (MvcTestResult resposta : List.of(relatorio, csvDisponibilizado, csvRascunho)) {
            assertThat(resposta).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(resposta.getResponse().getContentType()).startsWith("application/problem+json");
        }
        assertThat(lerJson(relatorio, "$.detail")).as("mesma frase de uma parceria que não existe")
                .isEqualTo(lerJson(relatorioInexistente, "$.detail"));
        assertThat(csvInexistente).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(csvDisponibilizado, "$.detail")).as("mesma frase de um relatório que não existe")
                .isEqualTo(lerJson(csvInexistente, "$.detail"));
        assertThat(lerJson(csvRascunho, "$.detail")).isEqualTo(lerJson(csvInexistente, "$.detail"));
        assertThat(corpo(csvDisponibilizado)).doesNotContain("Amarelo");
    }

    // ---------------------------------------------------------------------
    // Permissão: as rotas da RBS respondem 403 ao patrocinador
    // ---------------------------------------------------------------------

    @Test
    void patrocinadorRecebe403NasRotasDaRbs() {
        long amarelo = parceria("Cliente Amarelo");
        long roxo = parceria("Cliente Roxo");
        long relatorioDoAmarelo = relatorioDaAmostra(amarelo);
        long antes = jdbc.sql("SELECT count(*) FROM relatorio").query(Long.class).single();

        for (String email : List.of(AMARELO, ROXO)) {
            MockHttpSession sessao = entrar(email);
            assertThat(ler(sessao, "/api/relatorios")).as(email + " lista").hasStatus(HttpStatus.FORBIDDEN);
            assertThat(ler(sessao, "/api/relatorios.csv")).as(email + " lista em CSV").hasStatus(HttpStatus.FORBIDDEN);
            assertThat(gerar(sessao, amarelo)).as(email + " gera para o Amarelo").hasStatus(HttpStatus.FORBIDDEN);
            assertThat(gerar(sessao, roxo)).as(email + " gera para o Roxo").hasStatus(HttpStatus.FORBIDDEN);
            assertThat(mudarStatus(sessao, relatorioDoAmarelo, "VALIDADO")).as(email + " valida").hasStatus(HttpStatus.FORBIDDEN);
            assertThat(mudarStatus(sessao, relatorioDoAmarelo, "DISPONIBILIZADO")).as(email + " libera").hasStatus(HttpStatus.FORBIDDEN);
        }

        MvcTestResult negado = ler(entrar(AMARELO), "/api/relatorios");
        assertThat(negado.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(negado, "$.title")).isEqualTo("Acesso negado");
        assertThat(jdbc.sql("SELECT count(*) FROM relatorio").query(Long.class).single()).as("nada foi gerado").isEqualTo(antes);
        assertThat(jdbc.sql("SELECT status FROM relatorio WHERE id = :id").param("id", relatorioDoAmarelo)
                .query(String.class).single()).isEqualTo("RASCUNHO");
    }

    @Test
    void semSessaoDevolve401ESemTokenCsrfDevolve403() {
        long amarelo = parceria("Cliente Amarelo");
        MockHttpSession rbs = entrar(RBS);

        assertThat(mvc.get().uri("/api/relatorios").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/parcerias/" + amarelo + "/relatorio").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/relatorios/1/csv").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        MvcTestResult semToken = mvc.post().uri("/api/parcerias/" + amarelo + "/relatorios").session(rbs).exchange();
        assertThat(semToken).hasStatus(HttpStatus.FORBIDDEN);
        assertThat((String) lerJson(semToken, "$.detail")).contains("CSRF");
    }

    // ---------------------------------------------------------------------
    // Rota 36: CSV de um relatório
    // ---------------------------------------------------------------------

    @Test
    void csvDoRelatorioTemBomPontoEVirgulaCabecalhoENumerosEGravaDownload() {
        long amarelo = parceria("Cliente Amarelo");
        long relatorio = relatorioDaAmostra(amarelo);
        MockHttpSession rbs = entrar(RBS);

        MvcTestResult resposta = ler(rbs, "/api/relatorios/" + relatorio + "/csv");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(resposta.getResponse().getContentType()).isEqualTo("text/csv; charset=UTF-8");
        assertThat(resposta.getResponse().getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"relatorio-cliente-amarelo-v1.csv\"");
        assertThat(resposta.getResponse().getContentAsByteArray()).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        List<String> linhas = List.of(texto(resposta).substring(1).split("\r\n"));
        assertThat(linhas.get(0)).isEqualTo("secao;indicador_codigo;indicador;recorte;valor;unidade;base_calculo;disponivel");
        long numeros = jdbc.sql("SELECT count(*) FROM relatorio_indicador WHERE relatorio_id = :id").param("id", relatorio)
                .query(Long.class).single();
        assertThat(linhas).as("o cabeçalho e um número por linha").hasSize((int) numeros + 1);
        assertThat(linhas).contains(
                "Cumprimento das entregas;ENTREGAS_CONCLUIDAS_PCT;Entregas concluídas;;90,9;%;10 aprovadas de 11 aplicáveis;sim",
                "Mídia: previsto x exibido;MIDIA_PREVISTO;Mídia prevista;INSERCAO;1113;por unidade;soma do plano comercial;sim");
        assertThat(linhas).as("número sem dado: valor em branco e disponivel = nao").contains(
                "O festival em números;FESTIVAL_NUMERO;O festival em números;;;variável;sem registro na base;nao");

        // [HISTORICO] DOWNLOAD na entidade relatorio, com autor, parceria, IP e navegador.
        var download = jdbc.sql("""
                        SELECT usuario_id, parceria_id, detalhe, host(ip) AS ip, user_agent FROM historico
                        WHERE acao = 'DOWNLOAD' AND entidade = 'relatorio' AND entidade_id = :id
                        """).param("id", relatorio).query().singleRow();
        assertThat(((Number) download.get("usuario_id")).longValue()).isEqualTo(usuarioId(RBS));
        assertThat(((Number) download.get("parceria_id")).longValue()).isEqualTo(amarelo);
        assertThat(download.get("detalhe")).isEqualTo("CSV da versão 1");
        assertThat(download.get("ip")).isEqualTo("127.0.0.1");
        assertThat(download.get("user_agent")).isEqualTo("TesteDeRelatorio/1.0");
    }

    @Test
    void celulaQueComecaComIgualSaiNeutralizadaNoCsv() {
        long amarelo = parceria("Cliente Amarelo");
        // O nome da pesquisa vai para a base de cálculo de um número do relatório; um nome malicioso virou "=1+1".
        jdbc.sql("UPDATE pesquisa SET nome = '=1+1' WHERE escopo = 'ATIVACAO'").update();
        MockHttpSession rbs = entrar(RBS);
        long novo = ((Number) lerJson(gerar(rbs, amarelo), "$.id")).longValue();

        MvcTestResult resposta = ler(rbs, "/api/relatorios/" + novo + "/csv");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        String csv = texto(resposta);
        assertThat(csv).contains(";'=1+1;sim").doesNotContain(";=1+1");
        assertThat(resposta.getResponse().getHeader("Content-Disposition")).contains("relatorio-cliente-amarelo-v2.csv");
    }

    @Test
    void patrocinadorBaixaSoOCsvDoRelatorioDisponibilizado() {
        long amarelo = parceria("Cliente Amarelo");
        long relatorio = relatorioDaAmostra(amarelo);
        MockHttpSession rbs = entrar(RBS);
        MockHttpSession marketing = entrar(AMARELO);
        String rota = "/api/relatorios/" + relatorio + "/csv";

        MvcTestResult rascunho = ler(marketing, rota);
        assertThat(rascunho).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(rascunho, "$.detail")).isEqualTo("O relatório desta parceria ainda não está disponível.");
        mudarStatus(rbs, relatorio, "VALIDADO");
        assertThat(ler(marketing, rota)).as("validado ainda não é disponibilizado").hasStatus(HttpStatus.NOT_FOUND);
        assertThat(contarHistorico("DOWNLOAD", relatorio)).as("o 404 não grava download").isZero();

        mudarStatus(rbs, relatorio, "DISPONIBILIZADO");
        MvcTestResult csv = ler(marketing, rota);

        assertThat(csv).hasStatus(HttpStatus.OK);
        assertThat(csv.getResponse().getContentAsByteArray()).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(texto(csv)).contains("ENTREGAS_CONCLUIDAS_PCT");
        var download = jdbc.sql("""
                        SELECT usuario_id, parceria_id FROM historico
                        WHERE acao = 'DOWNLOAD' AND entidade = 'relatorio' AND entidade_id = :id
                        """).param("id", relatorio).query().singleRow();
        assertThat(((Number) download.get("usuario_id")).longValue()).isEqualTo(usuarioId(AMARELO));
        assertThat(((Number) download.get("parceria_id")).longValue()).isEqualTo(amarelo);
    }

    // ---------------------------------------------------------------------
    // Rota 31: a RBS vê o que a marca vê (campo disponibilizada)
    // ---------------------------------------------------------------------

    @Test
    void listaTrazAVersaoQueAMarcaVeMesmoQuandoAMaisNovaEstaEmRascunhoOuValidada() {
        MockHttpSession rbs = entrar(RBS);
        long amarelo = parceria("Cliente Amarelo");
        MvcTestResult semNada = ler(rbs, "/api/relatorios");
        assertThat(lerLista(semNada, "$[*].disponibilizada")).as("nenhuma versão disponibilizada: null nas três").hasSize(3).containsOnlyNulls();

        // A v1 vai até DISPONIBILIZADO; depois a RBS gera a v2 (rascunho) e a valida. A marca continua vendo a v1.
        long v1 = relatorioDaAmostra(amarelo);
        assertThat(mudarStatus(rbs, v1, "VALIDADO")).hasStatus(HttpStatus.OK);
        assertThat(mudarStatus(rbs, v1, "DISPONIBILIZADO")).hasStatus(HttpStatus.OK);
        long v2 = ((Number) lerJson(gerar(rbs, amarelo), "$.id")).longValue();

        MvcTestResult comRascunho = ler(rbs, "/api/relatorios");
        assertThat(lerJson(comRascunho, "$[0].situacao")).as("a versão mais nova é um rascunho").isEqualTo("RASCUNHO");
        assertThat(lerJson(comRascunho, "$[0].relatorio.versao")).isEqualTo(2);
        assertThat(lerJson(comRascunho, "$[0].disponibilizada.versao")).as("mas a marca vê a v1").isEqualTo(1);
        assertThat(((Number) lerJson(comRascunho, "$[0].disponibilizada.id")).longValue()).isEqualTo(v1);
        String quando = (String) lerJson(comRascunho, "$[0].disponibilizada.disponibilizadoEm");
        assertThat(quando).endsWith("-03:00");
        assertThat(java.time.OffsetDateTime.parse(quando)).isBetween(java.time.OffsetDateTime.now().minusMinutes(5),
                java.time.OffsetDateTime.now().plusMinutes(5));
        assertThat(lerJson(comRascunho, "$[0].disponibilizada")).isInstanceOf(Map.class);
        assertThat(new java.util.TreeSet<Object>(((Map<?, ?>) lerJson(comRascunho, "$[0].disponibilizada")).keySet()))
                .containsExactly("disponibilizadoEm", "id", "versao");
        assertThat(lerJson(comRascunho, "$[1].disponibilizada")).as("o Roxo não tem nada disponibilizado").isNull();
        // O que a RBS lê aqui é o que a marca recebe na rota 35.
        MvcTestResult daMarca = ler(entrar(AMARELO), "/api/parcerias/" + amarelo + "/relatorio");
        assertThat(lerJson(daMarca, "$.versao")).isEqualTo(lerJson(comRascunho, "$[0].disponibilizada.versao"));

        assertThat(mudarStatus(rbs, v2, "VALIDADO")).hasStatus(HttpStatus.OK);
        MvcTestResult validada = ler(rbs, "/api/relatorios");
        assertThat(lerJson(validada, "$[0].situacao")).isEqualTo("VALIDADO");
        assertThat(lerJson(validada, "$[0].disponibilizada.versao")).isEqualTo(1);

        // Quando a mais nova passa a ser a disponibilizada, a linha repete a mesma versão nos dois campos.
        assertThat(mudarStatus(rbs, v2, "DISPONIBILIZADO")).hasStatus(HttpStatus.OK);
        MvcTestResult nova = ler(rbs, "/api/relatorios");
        assertThat(lerJson(nova, "$[0].situacao")).isEqualTo("DISPONIBILIZADO");
        assertThat(lerJson(nova, "$[0].disponibilizada.versao")).isEqualTo(2);
        assertThat(((Number) lerJson(nova, "$[0].disponibilizada.id")).longValue()).isEqualTo(v2);
    }

    @Test
    void csvDaListaTrazAVersaoQueAMarcaVe() {
        MockHttpSession rbs = entrar(RBS);
        long amarelo = parceria("Cliente Amarelo");
        long v1 = relatorioDaAmostra(amarelo);
        mudarStatus(rbs, v1, "VALIDADO");
        mudarStatus(rbs, v1, "DISPONIBILIZADO");
        gerar(rbs, amarelo);

        String[] linhas = texto(ler(rbs, "/api/relatorios.csv")).substring(1).split("\r\n");

        String[] colunas = linhas[1].split(";", -1);
        assertThat(colunas[5]).as("versão mais nova").isEqualTo("2");
        assertThat(colunas[4]).isEqualTo("RASCUNHO");
        assertThat(colunas[12]).as("marca_ve_versao").isEqualTo("1");
        assertThat(colunas[13]).as("marca_ve_disponibilizado_em").endsWith("-03:00");
    }

    // ---------------------------------------------------------------------
    // Números do relatório em português: milhar com ponto e percentual de entrega que nunca vira 100 sem estar completo
    // ---------------------------------------------------------------------

    /** Uma linha de CPM nova no plano da Verde, com {@code exibido} já veiculado. A Verde só tinha inserções. */
    void cpmDaVerde(int previsto, int exibido) {
        long linha = jdbc.sql("""
                        INSERT INTO aproveitamento_previsto (parceria_id, categoria, bloco, veiculo, formato, tipo_midia, quantidade_prevista, unidade)
                        VALUES (:p, 'MIDIA', 'Mídia', 'atl.la', 'Display CPM de teste', 'PROPRIA', :previsto, 'CPM') RETURNING id
                        """).param("p", parceria("Cliente Verde")).param("previsto", previsto).query(Long.class).single();
        jdbc.sql("INSERT INTO veiculacao (aproveitamento_id, exibido_em, quantidade) VALUES (:a, now() - interval '1 day', :q)")
                .param("a", linha).param("q", exibido).update();
    }

    @Test
    void textoDeComoFoiCalculadoUsaMilharBrasileiroEPercentualDeEntregaNaoArredondaPara100() {
        cpmDaVerde(460_000, 459_900);   // 99,978%: arredondado viraria 100,0
        MockHttpSession rbs = entrar(RBS);

        MvcTestResult resposta = gerar(rbs, parceria("Cliente Verde"));

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        Map<String, Object> entrega = indicador(resposta, "MIDIA_ENTREGA_PCT", "CPM");
        assertThat(numero(entrega.get("valor"))).as("cortado na 1ª casa").isEqualByComparingTo("99.9");
        assertThat(entrega.get("baseCalculo")).isEqualTo("459.900 de 460.000");
        Map<String, Object> porVeiculo = indicador(resposta, "MIDIA_VEICULO_PCT", "atl.la · CPM");
        assertThat(numero(porVeiculo.get("valor"))).isEqualByComparingTo("99.9");
        assertThat(porVeiculo.get("baseCalculo")).isEqualTo("459.900 de 460.000");
        // O valor continua sendo número (o front formata); o texto é o único que mudou de aparência.
        assertThat(numero(indicador(resposta, "MIDIA_EXIBIDO", "CPM").get("valor"))).isEqualByComparingTo("459900");

        // O CSV do relatório leva o mesmo texto, e o percentual com vírgula.
        long relatorio = ((Number) lerJson(resposta, "$.id")).longValue();
        String csv = texto(ler(rbs, "/api/relatorios/" + relatorio + "/csv"));
        assertThat(csv).contains(";CPM;99,9;%;459.900 de 460.000;sim");
    }

    @Test
    void percentualDeEntregaDeMidiaSoChegaA100QuandoEstaCompleto() {
        cpmDaVerde(460_000, 460_000);
        MockHttpSession rbs = entrar(RBS);

        MvcTestResult resposta = gerar(rbs, parceria("Cliente Verde"));

        Map<String, Object> entrega = indicador(resposta, "MIDIA_ENTREGA_PCT", "CPM");
        assertThat(numero(entrega.get("valor"))).isEqualByComparingTo("100");
        assertThat(entrega.get("baseCalculo")).isEqualTo("460.000 de 460.000");
    }

    @Test
    void milharSoApareceNoTextoQuandoOsNumerosPassamDeMil() {
        MockHttpSession rbs = entrar(RBS);

        MvcTestResult resposta = gerar(rbs, parceria("Cliente Amarelo"));

        // 1.105 de 1.113: o exemplo do QA. Antes saía "1105 de 1113".
        assertThat(indicador(resposta, "MIDIA_ENTREGA_PCT", "INSERCAO").get("baseCalculo")).isEqualTo("1.105 de 1.113");
        assertThat(numero(indicador(resposta, "MIDIA_ENTREGA_PCT", "INSERCAO").get("valor"))).isEqualByComparingTo("99.2");
        // Abaixo de mil não muda nada.
        assertThat(indicador(resposta, "ENTREGAS_CONCLUIDAS_PCT", "").get("baseCalculo")).isEqualTo("10 aprovadas de 11 aplicáveis");
        assertThat(String.valueOf(indicador(resposta, "PESQUISA_RESULTADO", "P1 · Sim").get("baseCalculo")))
                .isEqualTo("base de 412 respondentes (percentual)");
    }

    @Test
    void asFuncoesDeNumeroDoBancoFormatamNoPadraoBrasileiro() {
        assertThat(jdbc.sql("SELECT fn_numero_br(459900)").query(String.class).single()).isEqualTo("459.900");
        assertThat(jdbc.sql("SELECT fn_numero_br(1234567.5)").query(String.class).single()).isEqualTo("1.234.567,5");
        assertThat(jdbc.sql("SELECT fn_numero_br(12.50)").query(String.class).single()).isEqualTo("12,5");
        assertThat(jdbc.sql("SELECT fn_numero_br(100.00)").query(String.class).single()).isEqualTo("100");
        assertThat(jdbc.sql("SELECT fn_numero_br(0)").query(String.class).single()).isEqualTo("0");
        assertThat(jdbc.sql("SELECT fn_numero_br(-1500.25)").query(String.class).single()).isEqualTo("-1.500,25");
        assertThat(jdbc.sql("SELECT fn_numero_br(999)").query(String.class).single()).isEqualTo("999");
        assertThat(jdbc.sql("SELECT fn_numero_br(1000)").query(String.class).single()).isEqualTo("1.000");
        // Um critério só: todo percentual corta na 1ª casa. Passar de 100 vale (exibido acima do previsto).
        assertThat(jdbc.sql("SELECT fn_pct_truncado(99.978)").query(BigDecimal.class).single()).isEqualByComparingTo("99.9");
        assertThat(jdbc.sql("SELECT fn_pct_truncado(104.06)").query(BigDecimal.class).single()).isEqualByComparingTo("104.0");
        assertThat(jdbc.sql("SELECT fn_pct_truncado(66.666)").query(BigDecimal.class).single()).as("corta, não arredonda para 66,7").isEqualByComparingTo("66.6");
        assertThat(jdbc.sql("SELECT fn_pct_truncado(197.26)").query(BigDecimal.class).single()).as("acima de 100").isEqualByComparingTo("197.2");
        assertThat(jdbc.sql("SELECT fn_pct_truncado(100)").query(BigDecimal.class).single()).isEqualByComparingTo("100");
        assertThat(jdbc.sql("SELECT count(*) FROM pg_proc WHERE proname = 'fn_pct_arredondado'").query(Long.class).single())
                .as("o arredondamento saiu de cena (V5)").isZero();
    }

    // ---------------------------------------------------------------------
    // Relatório completo: todas as seções do modelo, mesmo sem nenhum dado
    // ---------------------------------------------------------------------

    /** Uma parceria cadastrada ao vivo (edição 2027): sem plano comercial, sem entrega, sem cortesia e sem usuário. */
    long parceriaAoVivo(int cotaTipoId) {
        long patrocinador = jdbc.sql("INSERT INTO patrocinador (nome_canonico) VALUES ('Cliente Ao Vivo') RETURNING id")
                .query(Long.class).single();
        long evento = jdbc.sql("SELECT id FROM evento WHERE edicao = 2027").query(Long.class).single();
        return jdbc.sql("INSERT INTO parceria (evento_id, patrocinador_id, cota_tipo_id) VALUES (:e, :p, :c) RETURNING id")
                .param("e", evento).param("p", patrocinador).param("c", cotaTipoId).query(Long.class).single();
    }

    @Test
    void parceriaSemNenhumDadoGeraRelatorioComAsSeisSecoesEOs17IndicadoresIndisponiveis() {
        long aoVivo = parceriaAoVivo(1);   // Master
        MockHttpSession rbs = entrar(RBS);

        MvcTestResult resposta = gerar(rbs, aoVivo);

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(lerLista(resposta, "$.secoes[*].secao")).as("as 6 seções, na ordem do modelo")
                .containsExactlyElementsOf(SECOES_DO_MODELO);
        assertThat(lerLista(resposta, "$.secoes[*].indicadores[*].codigo")).as("os 17 indicadores, na ordem do modelo")
                .containsExactlyElementsOf(INDICADORES_DO_MODELO);
        List<Map<String, Object>> todos = lerLista(resposta, "$.secoes[*].indicadores[*]");
        assertThat(todos).as("sem dado nenhum, tudo indisponível, sem valor e sem recorte").allSatisfy(i -> {
            assertThat(i.get("disponivel")).isEqualTo(false);
            assertThat(i.get("valor")).isNull();
            assertThat(i.get("recorte")).isEqualTo("");
        });
        // O motivo vem em baseCalculo: sem mídia no plano, sem aplicações no plano, ou simplesmente sem registro.
        assertThat(indicador(resposta, "MIDIA_PREVISTO", "").get("baseCalculo")).isEqualTo("sem mídia no plano comercial");
        assertThat(indicador(resposta, "MIDIA_VEICULO_PCT", "").get("baseCalculo")).isEqualTo("sem mídia no plano comercial");
        assertThat(indicador(resposta, "COMPROVACOES_ENVIADAS", "").get("baseCalculo")).isEqualTo("sem aplicações de marca no plano comercial");
        assertThat(indicador(resposta, "PESQUISA_BASE", "").get("baseCalculo")).as("Master: a pesquisa vale, só falta registro")
                .isEqualTo("sem registro na base");
        assertThat(indicador(resposta, "ENTREGAS_PENDENTES", "").get("baseCalculo")).isEqualTo("sem registro na base");
        assertThat(indicador(resposta, "FESTIVAL_NUMERO", "").get("baseCalculo")).isEqualTo("sem registro na base");

        // A fotografia gravada tem as mesmas 17 linhas (a geração registra todas, não só a leitura).
        long id = ((Number) lerJson(resposta, "$.id")).longValue();
        assertThat(jdbc.sql("SELECT count(*) FROM relatorio_indicador WHERE relatorio_id = :id AND NOT disponivel")
                .param("id", id).query(Long.class).single()).isEqualTo(17);
        // A rota da RBS ler a versão devolve o mesmo.
        assertThat(lerLista(ler(rbs, "/api/parcerias/" + aoVivo + "/relatorio"), "$.secoes[*].indicadores[*].codigo"))
                .containsExactlyElementsOf(INDICADORES_DO_MODELO);
        // A lista (rota 31) conta os 17 como indisponíveis.
        MvcTestResult lista = ler(rbs, "/api/relatorios");
        assertThat(lerLista(lista, "$[?(@.parceriaId==" + aoVivo + ")].relatorio.indicadoresDisponiveis")).containsExactly(0);
        assertThat(lerLista(lista, "$[?(@.parceriaId==" + aoVivo + ")].relatorio.indicadoresIndisponiveis")).containsExactly(17);

        // O CSV também traz as 17 linhas, todas "nao", valor em branco.
        List<String> linhas = List.of(texto(ler(rbs, "/api/relatorios/" + id + "/csv")).substring(1).split("\r\n"));
        assertThat(linhas).hasSize(18);
        assertThat(linhas.subList(1, 18)).allSatisfy(linha -> assertThat(linha).endsWith(";nao"));
        assertThat(linhas).contains(
                "Mídia: previsto x exibido;MIDIA_PREVISTO;Mídia prevista;;;por unidade;sem mídia no plano comercial;nao",
                "Pesquisa da ativação;PESQUISA_RESULTADO;Resultado por pergunta;;;% ou nota;sem registro na base;nao");
    }

    @Test
    void cotaSemPesquisaTrazASecaoDePesquisaIndisponivelComOMotivo() {
        long subcota = parceriaAoVivo(3);   // Subcota: a pesquisa da ativação não faz parte da cota
        MockHttpSession rbs = entrar(RBS);

        MvcTestResult resposta = gerar(rbs, subcota);

        assertThat(lerLista(resposta, "$.secoes[*].secao")).containsExactlyElementsOf(SECOES_DO_MODELO);
        assertThat(indicador(resposta, "PESQUISA_BASE", "")).containsEntry("disponivel", false)
                .containsEntry("baseCalculo", "não se aplica a esta cota");
        assertThat(lerLista(resposta, "$.secoes[3].indicadores[*].codigo")).containsExactly("PESQUISA_BASE", "PESQUISA_RESULTADO");
    }

    @Test
    void indicadorPorRecorteComDadoMantemUmaLinhaPorRecorteESemDadoTemUmaSemRecorte() {
        long aoVivo = parceriaAoVivo(1);
        // Só mídia em CPM, com uma veiculação: o plano tem recorte por unidade (CPM) e por veículo, mas não tem inserções.
        long linha = jdbc.sql("""
                        INSERT INTO aproveitamento_previsto (parceria_id, categoria, bloco, veiculo, formato, tipo_midia, quantidade_prevista, unidade)
                        VALUES (:p, 'MIDIA', 'Mídia', 'atl.la', 'Display CPM de teste', 'PROPRIA', 1000, 'CPM') RETURNING id
                        """).param("p", aoVivo).query(Long.class).single();
        jdbc.sql("INSERT INTO veiculacao (aproveitamento_id, exibido_em, quantidade) VALUES (:a, now() - interval '1 day', 400)")
                .param("a", linha).update();
        MockHttpSession rbs = entrar(RBS);

        MvcTestResult resposta = gerar(rbs, aoVivo);

        assertThat(lerLista(resposta, "$.secoes[1].indicadores[*].codigo")).containsExactly("MIDIA_PREVISTO", "MIDIA_EXIBIDO",
                "MIDIA_ENTREGA_PCT", "MIDIA_VEICULO_PCT");
        assertThat(indicador(resposta, "MIDIA_ENTREGA_PCT", "CPM")).containsEntry("disponivel", true);
        assertThat(numero(indicador(resposta, "MIDIA_ENTREGA_PCT", "CPM").get("valor"))).isEqualByComparingTo("40");
        assertThat(indicador(resposta, "MIDIA_VEICULO_PCT", "atl.la · CPM")).containsEntry("disponivel", true);
        // Não sobra linha "indisponível sem recorte" ao lado de quem já tem recorte.
        assertThat(lerLista(resposta, "$.secoes[1].indicadores[*]")).hasSize(4);
        // As outras seções continuam inteiras e indisponíveis.
        assertThat(lerLista(resposta, "$.secoes[*].secao")).containsExactlyElementsOf(SECOES_DO_MODELO);
        assertThat(indicador(resposta, "APLICACOES_PREVISTAS", "")).containsEntry("disponivel", false);
    }

    @Test
    void relatorioGeradoAntesDaV5ChegaCompletoNaLeituraENoCsv() {
        long roxo = parceria("Cliente Roxo");
        long relatorio = relatorioDaAmostra(roxo);
        // Simula uma versão antiga: sem as linhas de pesquisa e de aplicações (o rascunho aceita a alteração).
        jdbc.sql("""
                        DELETE FROM relatorio_indicador WHERE relatorio_id = :id
                        AND indicador_codigo IN ('PESQUISA_BASE', 'PESQUISA_RESULTADO', 'APLICACOES_PREVISTAS', 'APLICACOES_COMPROVADAS',
                                                 'APLICACOES_COMPROVADAS_PCT', 'COMPROVACOES_ENVIADAS')
                        """).param("id", relatorio).update();
        MockHttpSession rbs = entrar(RBS);

        MvcTestResult lido = ler(rbs, "/api/parcerias/" + roxo + "/relatorio?versao=1");

        assertThat(lerLista(lido, "$.secoes[*].secao")).containsExactlyElementsOf(SECOES_DO_MODELO);
        List<Object> codigos = lerLista(lido, "$.secoes[*].indicadores[*].codigo");
        assertThat(codigos.stream().distinct().toList()).as("os 17 do modelo, na ordem (o Roxo tem mais de um recorte na mídia)")
                .containsExactlyElementsOf(INDICADORES_DO_MODELO);
        assertThat(indicador(lido, "PESQUISA_BASE", "")).containsEntry("disponivel", false).containsEntry("baseCalculo", "sem registro na base");
        assertThat(indicador(lido, "PESQUISA_BASE", "").get("valor")).isNull();
        List<String> csv = List.of(texto(ler(rbs, "/api/relatorios/" + relatorio + "/csv")).substring(1).split("\r\n"));
        assertThat(csv).contains("Pesquisa da ativação;PESQUISA_BASE;Base da pesquisa da ativação;;;respondentes;sem registro na base;nao");
        // A lista da RBS conta o que falta como indisponível, igual ao que a leitura mostra.
        long indisponiveisNaLeitura = lerLista(lido, "$.secoes[*].indicadores[?(@.disponivel==false)]").size();
        MvcTestResult lista = ler(rbs, "/api/relatorios");
        assertThat(lerLista(lista, "$[?(@.parceriaId==" + roxo + ")].relatorio.indicadoresIndisponiveis"))
                .containsExactly((int) indisponiveisNaLeitura);
    }

    // ---------------------------------------------------------------------
    // Percentuais: um critério só (cortar na 1ª casa) e acima de 100 quando o exibido passa do previsto
    // ---------------------------------------------------------------------

    @Test
    void percentualDeEntregaPassaDe100QuandoOExibidoSuperaOPrevistoEOCsvAcompanha() {
        cpmDaVerde(460_000, 907_300);   // 197,23%
        MockHttpSession rbs = entrar(RBS);

        MvcTestResult resposta = gerar(rbs, parceria("Cliente Verde"));

        Map<String, Object> entrega = indicador(resposta, "MIDIA_ENTREGA_PCT", "CPM");
        assertThat(numero(entrega.get("valor"))).as("não corta em 100").isEqualByComparingTo("197.2");
        assertThat(entrega.get("baseCalculo")).isEqualTo("907.300 de 460.000");
        assertThat(entrega).containsEntry("disponivel", true);
        assertThat(numero(indicador(resposta, "MIDIA_VEICULO_PCT", "atl.la · CPM").get("valor"))).isEqualByComparingTo("197.2");
        long relatorio = ((Number) lerJson(resposta, "$.id")).longValue();
        assertThat(texto(ler(rbs, "/api/relatorios/" + relatorio + "/csv"))).contains(";CPM;197,2;%;907.300 de 460.000;sim");
        // A consolidação (mesma conta) também passa de 100, na unidade, no veículo e na linha do plano.
        MvcTestResult consolidacao = ler(rbs, "/api/parcerias/" + parceria("Cliente Verde") + "/consolidacao");
        assertThat(numero(lerLista(consolidacao, "$.midia.porUnidade[?(@.unidade=='CPM')].percentual").getFirst()))
                .isEqualByComparingTo("197.2");
        assertThat(numero(lerLista(consolidacao, "$.midia.porVeiculo[?(@.unidade=='CPM')].percentual").getFirst()))
                .isEqualByComparingTo("197.2");
        assertThat(numero(lerLista(consolidacao, "$.midia.linhas[?(@.unidade=='CPM')].percentual").getFirst()))
                .isEqualByComparingTo("197.2");
    }

    @Test
    void todoPercentualCortaNaPrimeiraCasaNoRelatorioENaConsolidacao() {
        // Aplicações do Amarelo: 17 de 19 = 89,47%. Arredondado saía 89,5; cortado sai 89,4, como a mídia (99,28% = 99,2).
        long amarelo = parceria("Cliente Amarelo");
        MockHttpSession rbs = entrar(RBS);
        MvcTestResult relatorio = gerar(rbs, amarelo);
        assertThat(numero(indicador(relatorio, "APLICACOES_COMPROVADAS_PCT", "").get("valor"))).isEqualByComparingTo("89.4");
        assertThat(indicador(relatorio, "APLICACOES_COMPROVADAS_PCT", "").get("baseCalculo")).isEqualTo("17 de 19");
        assertThat(numero(indicador(relatorio, "MIDIA_ENTREGA_PCT", "INSERCAO").get("valor"))).isEqualByComparingTo("99.2");
        long id = ((Number) lerJson(relatorio, "$.id")).longValue();
        assertThat(texto(ler(rbs, "/api/relatorios/" + id + "/csv"))).contains(";89,4;%;17 de 19;sim");

        // Entregas e cortesias: 2 de 3 = 66,67%. Arredondado saía 66,7; cortado sai 66,6.
        long aoVivo = parceriaAoVivo(1);
        for (String status : List.of("APROVADO", "APROVADO", "ENVIADO")) {
            jdbc.sql("""
                            INSERT INTO entrega (parceria_id, aba_id, titulo, status)
                            SELECT :p, a.id, 'Entrega de teste', :s FROM aba a WHERE a.codigo = 'PERFIL'
                            """).param("p", aoVivo).param("s", status).update();
        }
        long cota = jdbc.sql("INSERT INTO cortesia_cota (parceria_id, setor, dia, quantidade) VALUES (:p, 'ARENA', 'SEXTA', 3) RETURNING id")
                .param("p", aoVivo).query(Long.class).single();
        jdbc.sql("INSERT INTO cortesia_destinacao (cortesia_cota_id, destinatario_nome, quantidade, registrado_por) VALUES (:c, 'Fulana', 2, :u)")
                .param("c", cota).param("u", usuarioId(RBS)).update();

        MvcTestResult doAoVivo = gerar(rbs, aoVivo);
        assertThat(numero(indicador(doAoVivo, "ENTREGAS_CONCLUIDAS_PCT", "").get("valor"))).isEqualByComparingTo("66.6");
        assertThat(numero(indicador(doAoVivo, "CORTESIAS_DESTINADAS_PCT", "").get("valor"))).isEqualByComparingTo("66.6");
        MvcTestResult consolidacao = ler(rbs, "/api/parcerias/" + aoVivo + "/consolidacao");
        assertThat(numero(lerJson(consolidacao, "$.entregas.percentualConcluido"))).isEqualByComparingTo("66.6");
        assertThat(numero(lerJson(consolidacao, "$.cortesias.percentual"))).isEqualByComparingTo("66.6");
        MvcTestResult lista = ler(rbs, "/api/parcerias");
        assertThat(numero(lerLista(lista, "$[?(@.id==" + aoVivo + ")].entregas.percentualConcluido").getFirst()))
                .as("o resumo da lista de parcerias usa a mesma visão").isEqualByComparingTo("66.6");
    }
}
