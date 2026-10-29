package br.com.rbs.portal.parceria;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.ApoioDeTeste.lerTexto;
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
 * Testa eventos e parcerias (rotas 6 a 10): listar, ver, criar e editar, com o isolamento entre o Roxo e o Amarelo.
 * Atende os itens 3 e 5 da Entrega 2. [ISOLAMENTO] [PERMISSAO] [VALIDACAO] [HISTORICO]
 * Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class ParceriasControllerTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String ROXO = "marketing@roxo.example";
    private static final String AMARELO = "marketing@amarelo.example";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    long roxo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Roxo");
    }

    long evento(int edicao) {
        return jdbc.sql("SELECT id FROM evento WHERE edicao = :e").param("e", edicao).query(Long.class).single();
    }

    long contar(String sql, Object... parametros) {
        var consulta = jdbc.sql(sql);
        for (int i = 0; i < parametros.length; i++) {
            consulta = consulta.param(parametros[i]);
        }
        return consulta.query(Long.class).single();
    }

    String novaParceria(String marca, long eventoId, String cota, String emails) {
        return """
                {"patrocinador":{"nome":"%s","segmento":"Varejo"},"eventoId":%d,"cotaCodigo":"%s",
                 "nomeProduto":"Cota Ativação Arena","valorInvestimento":100000.00,"baseValor":"LIQUIDO","emails":%s}
                """.formatted(marca, eventoId, cota, emails);
    }

    // ---------------------------------------------------------------------
    // GET /api/eventos
    // ---------------------------------------------------------------------

    @Test
    void eventosVemDaEdicaoMaisNovaParaAMaisAntigaParaQualquerUsuarioLogado() {
        MvcTestResult resposta = SessaoDeApi.entrar(mvc, ROXO).get("/api/eventos");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerLista(resposta, "$[*].edicao")).containsExactly(2027, 2026);
        assertThat(lerJson(resposta, "$[0].nome")).isEqualTo("Planeta Atlântida");
        assertThat(lerJson(resposta, "$[0].dataInicio")).as("as datas de 2027 ainda estão em branco").isNull();
        assertThat(lerJson(resposta, "$[1].dataInicio")).isEqualTo("2026-01-30");
        assertThat(lerJson(resposta, "$[1].local")).isEqualTo("Saba, Atlântida (RS)");
    }

    @Test
    void eventosSemSessaoDevolve401() {
        assertThat(SessaoDeApi.anonima(mvc).get("/api/eventos")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    // ---------------------------------------------------------------------
    // GET /api/parcerias
    // ---------------------------------------------------------------------

    @Test
    void rbsListaTodasAsParceriasComResumo() {
        MvcTestResult resposta = SessaoDeApi.entrar(mvc, RBS).get("/api/parcerias");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerLista(resposta, "$[*].patrocinador.nome")).as("edição mais nova primeiro, depois o nome")
                .containsExactly("Cliente Amarelo", "Cliente Roxo", "Cliente Verde", "Cliente Amarelo");
        assertThat(lerLista(resposta, "$[*].evento.edicao")).containsExactly(2027, 2027, 2027, 2026);
        assertThat(lerJson(resposta, "$[0].cota.codigo")).isEqualTo("MASTER");
        assertThat(lerJson(resposta, "$[0].usuarios")).as("marketing e agência").isEqualTo(2);
        assertThat(lerJson(resposta, "$[1].usuarios")).isEqualTo(1);
        assertThat(lerJson(resposta, "$[0].relatorio.versao")).isEqualTo(1);
        assertThat(lerJson(resposta, "$[0].relatorio.status")).as("a RBS vê o rascunho").isEqualTo("RASCUNHO");

        Map<String, Object> resumo = jdbc.sql("SELECT total, aprovadas, pendentes, nao_aplicaveis FROM vw_entregas_resumo WHERE parceria_id = :id")
                .param("id", amarelo()).query().singleRow();
        assertThat(lerJson(resposta, "$[0].entregas.total")).isEqualTo(((Number) resumo.get("total")).intValue());
        assertThat(lerJson(resposta, "$[0].entregas.aprovadas")).isEqualTo(((Number) resumo.get("aprovadas")).intValue());
        assertThat(lerJson(resposta, "$[0].entregas.pendentes")).isEqualTo(((Number) resumo.get("pendentes")).intValue());
        assertThat(lerJson(resposta, "$[0].entregas.naoAplicaveis")).isEqualTo(((Number) resumo.get("nao_aplicaveis")).intValue());

        // A parceria encerrada de 2026 não tem entregas nem relatório: zeros, e o percentual e o relatório nulos.
        assertThat(lerJson(resposta, "$[3].entregas.total")).isEqualTo(0);
        assertThat(lerJson(resposta, "$[3].entregas.percentualConcluido")).isNull();
        assertThat(lerJson(resposta, "$[3].relatorio")).isNull();
        assertThat(lerJson(resposta, "$[3].usuarios")).isEqualTo(0);
    }

    @Test
    void filtrosDaListaPorEventoSituacaoENome() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        assertThat(lerLista(rbs.get("/api/parcerias?eventoId={e}", evento(2026)), "$[*].evento.edicao")).containsExactly(2026);
        assertThat(lerLista(rbs.get("/api/parcerias?situacao=ENCERRADA"), "$[*].situacao")).containsExactly("ENCERRADA");
        assertThat(lerLista(rbs.get("/api/parcerias?q=ROXO"), "$[*].patrocinador.nome")).as("sem diferenciar maiúsculas")
                .containsExactly("Cliente Roxo");
        assertThat(lerLista(rbs.get("/api/parcerias?q=%25"), "$")).as("% não é curinga").isEmpty();
        assertThat(lerLista(rbs.get("/api/parcerias?q=amarelo&eventoId={e}", evento(2027)), "$[*].id")).hasSize(1);
    }

    @Test
    void situacaoInvalidaNoFiltroDevolve422ComCampos() {
        MvcTestResult resposta = SessaoDeApi.entrar(mvc, RBS).get("/api/parcerias?situacao=QUALQUER");

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(resposta, "$.campos.situacao")).isNotNull();
    }

    @Test
    void roxoListaSoAParceriaDeleSemUsuariosESemRascunhoDeRelatorio() {
        MvcTestResult resposta = SessaoDeApi.entrar(mvc, ROXO).get("/api/parcerias");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerLista(resposta, "$[*].id")).containsExactly((int) roxo());
        assertThat(lerJson(resposta, "$[0].patrocinador.nome")).isEqualTo("Cliente Roxo");
        assertThat(lerJson(resposta, "$[0].cota.codigo")).isEqualTo("SUBCOTA_G");
        assertThat(lerJson(resposta, "$[0].usuarios")).as("dado interno da RBS").isNull();
        assertThat(lerJson(resposta, "$[0].relatorio")).as("o rascunho não aparece para o patrocinador").isNull();
        assertThat(corpo(resposta)).doesNotContain("Amarelo").doesNotContain("Verde");
    }

    @Test
    void relatorioDisponibilizadoApareceParaOPatrocinador() {
        long rbs = jdbc.sql("SELECT id FROM usuario WHERE email = :e").param("e", RBS).query(Long.class).single();
        jdbc.sql("UPDATE relatorio SET status = 'VALIDADO', validado_por = :u, validado_em = now() WHERE parceria_id = :p")
                .param("u", rbs).param("p", roxo()).update();
        jdbc.sql("UPDATE relatorio SET status = 'DISPONIBILIZADO', disponibilizado_por = :u, disponibilizado_em = now() WHERE parceria_id = :p")
                .param("u", rbs).param("p", roxo()).update();

        MvcTestResult doRoxo = SessaoDeApi.entrar(mvc, ROXO).get("/api/parcerias");

        assertThat(lerJson(doRoxo, "$[0].relatorio.status")).isEqualTo("DISPONIBILIZADO");
        assertThat(lerJson(doRoxo, "$[0].relatorio.versao")).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // GET /api/parcerias/{id}
    // ---------------------------------------------------------------------

    @Test
    void rbsVeOsDadosDaParceriaComAbasEEmails() {
        MvcTestResult resposta = SessaoDeApi.entrar(mvc, RBS).get("/api/parcerias/{id}", amarelo());

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.patrocinador.nome")).isEqualTo("Cliente Amarelo");
        assertThat(lerJson(resposta, "$.evento.edicao")).isEqualTo(2027);
        assertThat(lerJson(resposta, "$.cota.nome")).isEqualTo("Master");
        assertThat(lerJson(resposta, "$.nomeProduto")).isEqualTo("Cota Patrocínio Master");
        assertThat(lerJson(resposta, "$.valorInvestimento")).isEqualTo(1000000.0);
        assertThat(lerJson(resposta, "$.baseValor")).isEqualTo("BRUTO_TABELA");
        assertThat(lerJson(resposta, "$.situacao")).isEqualTo("ATIVA");
        assertThat(lerTexto(resposta, "$.criadoEm")).endsWith("-03:00");
        assertThat(lerLista(resposta, "$.abas[*].codigo")).containsExactly(
                "PERFIL", "APLICACOES", "MIDIA", "CORTESIAS", "CREDENCIAMENTO", "PESQUISA", "CONTRATO");
        assertThat(lerLista(resposta, "$.emailsAutorizados[*].email"))
                .containsExactlyInAnyOrder("marketing@amarelo.example", "atendimento@agencia-x.example");
        assertThat(lerLista(resposta, "$.emailsAutorizados[*].situacao")).containsOnly("ATIVO");
        assertThat(corpo(resposta)).doesNotContain("codigoAtivacao");
    }

    @Test
    void patrocinadorVeASuaParceria() {
        MvcTestResult resposta = SessaoDeApi.entrar(mvc, ROXO).get("/api/parcerias/{id}", roxo());

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.patrocinador.nome")).isEqualTo("Cliente Roxo");
        assertThat(lerJson(resposta, "$.cota.codigo")).isEqualTo("SUBCOTA_G");
        assertThat(lerLista(resposta, "$.abas[?(@.codigo=='PESQUISA')].liberada")).containsExactly(false);
    }

    @Test
    void roxoPedindoAParceriaDoAmareloRecebe404IgualAUmIdQueNaoExiste() {
        SessaoDeApi roxo = SessaoDeApi.entrar(mvc, ROXO);

        MvcTestResult doAmarelo = roxo.get("/api/parcerias/{id}", amarelo());
        MvcTestResult inexistente = roxo.get("/api/parcerias/{id}", 999_999);

        assertThat(doAmarelo).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(inexistente).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(doAmarelo, "$.title")).isEqualTo(lerJson(inexistente, "$.title"));
        assertThat(lerJson(doAmarelo, "$.detail")).as("a mesma frase: não revela que o id existe").isEqualTo(lerJson(inexistente, "$.detail"));
        assertThat(corpo(doAmarelo)).doesNotContain("Amarelo");
    }

    @Test
    void rbsPedindoParceriaInexistenteRecebe404() {
        assertThat(SessaoDeApi.entrar(mvc, RBS).get("/api/parcerias/{id}", 999_999)).hasStatus(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------------
    // POST /api/parcerias
    // ---------------------------------------------------------------------

    @Test
    void patrocinadorRecebe403AoCriarParceria() {
        long antes = contar("SELECT count(*) FROM parceria");

        MvcTestResult resposta = SessaoDeApi.entrar(mvc, ROXO).post("/api/parcerias",
                novaParceria("Cliente Novo", evento(2027), "SUBCOTA", "[]"));

        assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(contar("SELECT count(*) FROM parceria")).isEqualTo(antes);
    }

    @Test
    void rbsCriaParceriaComPatrocinadorNovoEDoisEmails() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult resposta = rbs.post("/api/parcerias", novaParceria("Cliente Novo", evento(2027), "SUBCOTA", """
                [{"email":"marketing@novo.example","vinculo":"MARCA"},{"email":"atendimento@agencia.example","vinculo":"AGENCIA"}]"""));

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        long id = ((Number) lerJson(resposta, "$.id")).longValue();
        assertThat(resposta.getResponse().getHeader("Location")).isEqualTo("/api/parcerias/" + id);
        assertThat(lerJson(resposta, "$.patrocinador.nome")).isEqualTo("Cliente Novo");
        assertThat(lerJson(resposta, "$.patrocinador.segmento")).isEqualTo("Varejo");
        assertThat(lerJson(resposta, "$.cota.codigo")).isEqualTo("SUBCOTA");
        assertThat(lerJson(resposta, "$.situacao")).isEqualTo("ATIVA");
        assertThat(lerJson(resposta, "$.valorInvestimento")).isEqualTo(100000.0);
        assertThat(lerJson(resposta, "$.baseValor")).isEqualTo("LIQUIDO");
        assertThat(lerLista(resposta, "$.emailsAutorizados[*].email")).containsExactly("marketing@novo.example", "atendimento@agencia.example");
        assertThat(lerLista(resposta, "$.emailsAutorizados[*].codigoAtivacao")).allSatisfy(c ->
                assertThat((String) c).matches("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}"));
        assertThat(lerTexto(resposta, "$.emailsAutorizados[0].codigoExpiraEm")).endsWith("-03:00");
        assertThat(lerLista(resposta, "$.emailsAutorizados[*].situacao")).containsOnly("PENDENTE");

        // As abas nascem do gatilho do banco, conforme a cota: a Subcota não abre a Pesquisa.
        assertThat(lerLista(resposta, "$.abas[*].codigo")).hasSize(7);
        assertThat(lerLista(resposta, "$.abas[?(@.codigo=='PESQUISA')].liberada")).containsExactly(false);
        assertThat(lerLista(resposta, "$.abas[?(@.codigo=='PERFIL')].liberada")).containsExactly(true);

        // [HISTORICO] CRIACAO do patrocinador novo e da parceria, e AUTORIZACAO de cada e-mail, todas nesta parceria.
        assertThat(contar("SELECT count(*) FROM historico WHERE acao = 'CRIACAO' AND parceria_id = ?", id)).isEqualTo(2);
        assertThat(contar("SELECT count(*) FROM historico WHERE acao = 'AUTORIZACAO' AND parceria_id = ?", id)).isEqualTo(2);

        // A GET seguinte nunca mais mostra o código.
        assertThat(corpo(rbs.get("/api/parcerias/{id}", id))).doesNotContain("codigoAtivacao");
    }

    @Test
    void criarMasterAbreAPesquisaEPermiteParceriaSemEmailsEPatrocinadorExistente() {
        long verde = jdbc.sql("SELECT id FROM patrocinador WHERE nome_canonico = 'Cliente Verde'").query(Long.class).single();

        MvcTestResult resposta = SessaoDeApi.entrar(mvc, RBS).post("/api/parcerias", """
                {"patrocinador":{"id":%d},"eventoId":%d,"cotaCodigo":"MASTER"}""".formatted(verde, evento(2026)));

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(resposta, "$.patrocinador.nome")).isEqualTo("Cliente Verde");
        assertThat(lerJson(resposta, "$.evento.edicao")).isEqualTo(2026);
        assertThat(lerJson(resposta, "$.valorInvestimento")).isNull();
        assertThat(lerLista(resposta, "$.emailsAutorizados")).isEmpty();
        assertThat(lerLista(resposta, "$.abas[?(@.codigo=='PESQUISA')].liberada")).containsExactly(true);
        assertThat(contar("SELECT count(*) FROM historico WHERE acao = 'CRIACAO' AND entidade = 'patrocinador'"))
                .as("patrocinador existente não ganha CRIACAO").isZero();
    }

    @Test
    void marcaQueJaTemParceriaNaEdicaoDevolve409() {
        long verde = jdbc.sql("SELECT id FROM patrocinador WHERE nome_canonico = 'Cliente Verde'").query(Long.class).single();

        MvcTestResult resposta = SessaoDeApi.entrar(mvc, RBS).post("/api/parcerias", """
                {"patrocinador":{"id":%d},"eventoId":%d,"cotaCodigo":"MASTER"}""".formatted(verde, evento(2027)));

        assertThat(resposta).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("Este patrocinador já tem parceria nesta edição do evento.");
    }

    @Test
    void nomeDePatrocinadorRepetidoDevolve409() {
        long antes = contar("SELECT count(*) FROM patrocinador");

        MvcTestResult resposta = SessaoDeApi.entrar(mvc, RBS).post("/api/parcerias",
                novaParceria("cliente AMARELO", evento(2027), "SUBCOTA", "[]"));

        assertThat(resposta).hasStatus(HttpStatus.CONFLICT);
        assertThat((String) lerJson(resposta, "$.detail")).startsWith("Já existe um patrocinador com este nome.");
        assertThat(contar("SELECT count(*) FROM patrocinador")).isEqualTo(antes);
    }

    @Test
    void camposObrigatoriosFaltandoDevolve422ComTodosOsErros() {
        MvcTestResult resposta = SessaoDeApi.entrar(mvc, RBS).post("/api/parcerias", "{}");

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(resposta, "$.campos.patrocinador")).isEqualTo("Informe o patrocinador.");
        assertThat(lerJson(resposta, "$.campos.eventoId")).isEqualTo("Informe o evento.");
        assertThat(lerJson(resposta, "$.campos.cotaCodigo")).isEqualTo("Informe a cota.");
    }

    @Test
    void regrasDePreenchimentoDevolve422ENadaFicaGravado() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long parcerias = contar("SELECT count(*) FROM parceria");
        long patrocinadores = contar("SELECT count(*) FROM patrocinador");

        // Primeiro o formato (Bean Validation): e-mail e vínculo mal escritos, valor negativo e base fora da lista.
        MvcTestResult formato = rbs.post("/api/parcerias", """
                {"patrocinador":{"nome":"Cliente Novo"},"eventoId":%d,"cotaCodigo":"MASTER","valorInvestimento":-5,"baseValor":"BRUTO",
                 "emails":[{"email":"errado","vinculo":"SOCIO"}]}""".formatted(evento(2027)));
        assertThat(formato).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(formato, "$.campos.valorInvestimento")).isEqualTo("O valor não pode ser negativo.");
        assertThat(lerJson(formato, "$.campos.baseValor")).isEqualTo("A base do valor deve ser BRUTO_TABELA ou LIQUIDO.");
        assertThat(lerJson(formato, "$.campos['emails[0].email']")).isEqualTo("E-mail inválido.");
        assertThat(lerJson(formato, "$.campos['emails[0].vinculo']")).isEqualTo("O vínculo deve ser MARCA ou AGENCIA.");

        // Depois as regras que só a API enxerga: id e nome juntos, evento e cota que não existem, valor sem base.
        MvcTestResult regras = rbs.post("/api/parcerias", """
                {"patrocinador":{"id":999999,"nome":"Cliente Novo"},"eventoId":999999,"cotaCodigo":"OURO","valorInvestimento":1000.00}""");
        assertThat(regras).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(regras, "$.campos.patrocinador")).as("id e nome juntos").isNotNull();
        assertThat(lerJson(regras, "$.campos.eventoId")).isEqualTo("Evento não encontrado.");
        assertThat(lerJson(regras, "$.campos.cotaCodigo")).isEqualTo("Cota não encontrada. Use MASTER, SUBCOTA_G ou SUBCOTA.");
        assertThat(lerJson(regras, "$.campos.baseValor")).as("valor sem base").isNotNull();

        // E, com o formato certo, os e-mails: repetido, da RBS, e o patrocinador que não existe.
        MvcTestResult emails = rbs.post("/api/parcerias", """
                {"patrocinador":{"id":999999},"eventoId":%d,"cotaCodigo":"MASTER",
                 "emails":[{"email":"a@novo.example","vinculo":"MARCA"},{"email":"A@novo.example","vinculo":"AGENCIA"},
                           {"email":"pesquisa@rbs.example","vinculo":"MARCA"}]}""".formatted(evento(2027)));
        assertThat(emails).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(emails, "$.campos['patrocinador.id']")).isEqualTo("Patrocinador não encontrado.");
        assertThat(lerJson(emails, "$.campos['emails[1].email']")).isEqualTo("E-mail repetido na lista.");
        assertThat((String) lerJson(emails, "$.campos['emails[2].email']")).contains("RBS");

        assertThat(contar("SELECT count(*) FROM parceria")).isEqualTo(parcerias);
        assertThat(contar("SELECT count(*) FROM patrocinador")).isEqualTo(patrocinadores);
    }

    @Test
    void corpoQueNaoEJsonDevolve400() {
        assertThat(SessaoDeApi.entrar(mvc, RBS).post("/api/parcerias", "isto nao e json")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void criarExigeOTokenCsrf() {
        assertThat(SessaoDeApi.entrar(mvc, RBS).postSemCsrf("/api/parcerias", novaParceria("Cliente Novo", evento(2027), "SUBCOTA", "[]")))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------------
    // PATCH /api/parcerias/{id}
    // ---------------------------------------------------------------------

    @Test
    void patrocinadorRecebe403NoPatchMesmoNaPropriaParceriaEEmQualquerId() {
        SessaoDeApi roxo = SessaoDeApi.entrar(mvc, ROXO);
        String corpo = "{\"nomeProduto\":\"Quero trocar\",\"abas\":[{\"codigo\":\"PESQUISA\",\"liberada\":true}]}";

        assertThat(roxo.patch("/api/parcerias/" + roxo(), corpo)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(roxo.patch("/api/parcerias/" + amarelo(), corpo)).as("de outra parceria: o mesmo 403").hasStatus(HttpStatus.FORBIDDEN);
        assertThat(roxo.patch("/api/parcerias/999999", corpo)).as("de um id que não existe: o mesmo 403").hasStatus(HttpStatus.FORBIDDEN);

        assertThat(contar("SELECT count(*) FROM parceria WHERE nome_produto = 'Quero trocar'")).isZero();
        assertThat(contar("SELECT count(*) FROM parceria_aba WHERE liberada AND parceria_id = ? AND aba_id = 6", roxo())).isZero();
    }

    @Test
    void patchDeParceriaInexistenteDevolve404ParaARbs() {
        assertThat(SessaoDeApi.entrar(mvc, RBS).patch("/api/parcerias/999999", "{}")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void patchFechaEReabreUmaAbaDaCotaDoRoxoEOEuPassaAMostrar() {
        // A Pesquisa não é da cota do Roxo (Subcota G) e não se libera (AbasEDaCotaTest); a Cortesias é, então serve de exemplo.
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        SessaoDeApi roxo = SessaoDeApi.entrar(mvc, ROXO);
        long id = roxo();
        String cortesias = "$.parcerias[0].abas[?(@.codigo=='CORTESIAS')].liberada";
        assertThat(lerLista(roxo.get("/api/auth/eu"), cortesias)).containsExactly(true);

        MvcTestResult fecha = rbs.patch("/api/parcerias/" + id, "{\"abas\":[{\"codigo\":\"CORTESIAS\",\"liberada\":false}]}");

        assertThat(fecha).hasStatus(HttpStatus.OK);
        assertThat(lerLista(fecha, "$.abas[?(@.codigo=='CORTESIAS')].liberada")).containsExactly(false);
        assertThat(lerLista(roxo.get("/api/auth/eu"), cortesias)).as("a sessão já aberta do Roxo vê a aba fechada").containsExactly(false);
        assertThat(lerLista(roxo.get("/api/parcerias/{id}", id), "$.abas[?(@.codigo=='CORTESIAS')].liberada")).containsExactly(false);

        // [ISOLAMENTO] Só a parceria do Roxo mudou; as outras abas e a Cortesias do Verde seguem como estavam.
        assertThat(lerLista(fecha, "$.abas[?(@.codigo=='APLICACOES')].liberada")).containsExactly(true);
        long verde = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Verde");
        assertThat(lerLista(rbs.get("/api/parcerias/{id}", verde), "$.abas[?(@.codigo=='CORTESIAS')].liberada")).containsExactly(true);

        // [HISTORICO] LIBERACAO_ABA com o estado de antes e o de depois, e nada de EDICAO (só a aba mudou).
        var linha = jdbc.sql("SELECT valor_anterior, valor_novo, detalhe, entidade FROM historico WHERE acao = 'LIBERACAO_ABA'")
                .query().singleRow();
        assertThat(linha.get("valor_anterior")).isEqualTo("liberada");
        assertThat(linha.get("valor_novo")).isEqualTo("fechada");
        assertThat(linha.get("detalhe")).isEqualTo("Cortesias");
        assertThat(contar("SELECT count(*) FROM historico WHERE acao = 'EDICAO'")).isZero();

        // Reabrir (a cota inclui a aba) e esconder do menu também funcionam; repetir o mesmo estado não grava nada.
        assertThat(lerLista(rbs.patch("/api/parcerias/" + id, "{\"abas\":[{\"codigo\":\"CORTESIAS\",\"liberada\":true}]}"),
                "$.abas[?(@.codigo=='CORTESIAS')].liberada")).containsExactly(true);
        rbs.patch("/api/parcerias/" + id, "{\"abas\":[{\"codigo\":\"CORTESIAS\",\"liberada\":true}]}");
        MvcTestResult escondida = rbs.patch("/api/parcerias/" + id, "{\"abas\":[{\"codigo\":\"PERFIL\",\"visivel\":false}]}");
        assertThat(lerLista(escondida, "$.abas[?(@.codigo=='PERFIL')].liberada")).as("aba oculta não conta como liberada").containsExactly(false);
        assertThat(contar("SELECT count(*) FROM historico WHERE acao = 'LIBERACAO_ABA'")).isEqualTo(3);
    }

    @Test
    void patchEditaOPerfilEGravaOValorAnteriorEONovoNoHistorico() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long id = amarelo();

        MvcTestResult resposta = rbs.patch("/api/parcerias/" + id, """
                {"patrocinador":{"nome":"Cliente Amarelo S.A.","segmento":"Bebidas"},"nomeProduto":"Cota Master 2027",
                 "valorInvestimento":1200000.00,"baseValor":"LIQUIDO","situacao":"EM_NEGOCIACAO"}""");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.patrocinador.nome")).isEqualTo("Cliente Amarelo S.A.");
        assertThat(lerJson(resposta, "$.patrocinador.segmento")).isEqualTo("Bebidas");
        assertThat(lerJson(resposta, "$.nomeProduto")).isEqualTo("Cota Master 2027");
        assertThat(lerJson(resposta, "$.valorInvestimento")).isEqualTo(1200000.0);
        assertThat(lerJson(resposta, "$.baseValor")).isEqualTo("LIQUIDO");
        assertThat(lerJson(resposta, "$.situacao")).isEqualTo("EM_NEGOCIACAO");
        assertThat(lerJson(resposta, "$.cota.codigo")).as("a cota não muda por aqui").isEqualTo("MASTER");

        List<Map<String, Object>> edicoes = jdbc.sql("SELECT entidade, detalhe, valor_anterior, valor_novo FROM historico WHERE acao = 'EDICAO' ORDER BY id")
                .query().listOfRows();
        assertThat(edicoes).extracting(e -> e.get("detalhe")).containsExactlyInAnyOrder(
                "nome", "segmento", "nomeProduto", "valorInvestimento", "baseValor", "situacao");
        assertThat(edicoes).anySatisfy(e -> assertThat(e).containsEntry("detalhe", "valorInvestimento")
                .containsEntry("valor_anterior", "1000000.00").containsEntry("valor_novo", "1200000.00"));
        assertThat(edicoes).anySatisfy(e -> assertThat(e).containsEntry("detalhe", "nome")
                .containsEntry("valor_anterior", "Cliente Amarelo").containsEntry("valor_novo", "Cliente Amarelo S.A."));
        assertThat(edicoes).anySatisfy(e -> assertThat(e).containsEntry("detalhe", "situacao")
                .containsEntry("valor_anterior", "ATIVA").containsEntry("valor_novo", "EM_NEGOCIACAO"));
    }

    @Test
    void patchVazioOuSemMudancaNaoGravaNada() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long id = amarelo();
        String antes = corpo(rbs.get("/api/parcerias/{id}", id));

        MvcTestResult vazio = rbs.patch("/api/parcerias/" + id, "{}");
        MvcTestResult igual = rbs.patch("/api/parcerias/" + id, "{\"nomeProduto\":\"Cota Patrocínio Master\",\"valorInvestimento\":1000000,\"situacao\":\"ATIVA\"}");

        assertThat(vazio).hasStatus(HttpStatus.OK);
        assertThat(corpo(vazio)).isEqualTo(antes);
        assertThat(corpo(igual)).isEqualTo(antes);
        assertThat(contar("SELECT count(*) FROM historico WHERE acao IN ('EDICAO', 'LIBERACAO_ABA')")).isZero();
    }

    @Test
    void patchComDadosInvalidosDevolve422() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long semValor = jdbc.sql("""
                        SELECT pa.id FROM parceria pa JOIN evento e ON e.id = pa.evento_id WHERE e.edicao = 2026
                        """).query(Long.class).single();   // a parceria de 2026 do Amarelo não tem valor nem base

        MvcTestResult valorSemBase = rbs.patch("/api/parcerias/" + semValor, "{\"valorInvestimento\":5000}");
        assertThat(valorSemBase).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(valorSemBase, "$.campos.baseValor")).isNotNull();

        MvcTestResult outros = rbs.patch("/api/parcerias/" + amarelo(), """
                {"situacao":"FECHADA","baseValor":"BRUTO","valorInvestimento":-1,"patrocinador":{"nome":"   "},
                 "abas":[{"codigo":"PESQUISA","liberada":true},{"codigo":"NAO_EXISTE","liberada":true},{"liberada":true}]}""");
        assertThat(outros).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(outros, "$.campos.situacao")).isNotNull();
        assertThat(lerJson(outros, "$.campos.baseValor")).isNotNull();
        assertThat(lerJson(outros, "$.campos.valorInvestimento")).isEqualTo("O valor não pode ser negativo.");
        assertThat(lerJson(outros, "$.campos['abas[2].codigo']")).isEqualTo("Informe o código da aba.");

        MvcTestResult abaErrada = rbs.patch("/api/parcerias/" + amarelo(),
                "{\"patrocinador\":{\"nome\":\"  \"},\"abas\":[{\"codigo\":\"NAO_EXISTE\",\"liberada\":true}]}");
        assertThat(lerJson(abaErrada, "$.campos['abas[0].codigo']")).isNotNull();
        assertThat(lerJson(abaErrada, "$.campos['patrocinador.nome']")).isEqualTo("Informe o nome do patrocinador.");
    }

    @Test
    void patchComNomeDePatrocinadorRepetidoDevolve409() {
        MvcTestResult resposta = SessaoDeApi.entrar(mvc, RBS).patch("/api/parcerias/" + amarelo(),
                "{\"patrocinador\":{\"nome\":\"cliente roxo\"}}");

        assertThat(resposta).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("Já existe um patrocinador com este nome.");
        assertThat(contar("SELECT count(*) FROM patrocinador WHERE nome_canonico = 'Cliente Amarelo'")).isEqualTo(1);
    }
}
