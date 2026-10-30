package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
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
 * [ATLAS] As rotas 42 a 48 como o front as usa: login pela rota, sessão de verdade e token CSRF. O plano B de ponta a ponta
 * (botão, cartão, accept, relatório pronto com valores formatados, fontes e contas), o formato do JSON que o front espera
 * (frontend/src/api/atlas.ts), os status de cada erro e o isolamento: marca do Roxo com ids do Amarelo, conversa de outro
 * usuário da mesma parceria, RBS nas rotas só da marca, aba fechada. [PERMISSAO] [ISOLAMENTO] O serviço em si (histórico em chat,
 * descarte, validações) está em AtlasServiceTest. Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class AtlasControllerTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String AGENCIA = "atendimento@agencia-x.example";
    private static final String ROXO = "marketing@roxo.example";
    private static final String BOTAO = "{\"atalho\":\"MONTAR_RELATORIO\",\"modelo\":\"ATIVACAO_EVENTO\"}";
    private static final String ACCEPT_COMPLETO = "{\"acao\":\"accept\",\"valores\":[{\"chave\":\"ativacao.visitantes_total\",\"valor\":1200},"
            + "{\"chave\":\"ativacao.cadastros_consentidos\",\"valor\":1072},{\"chave\":\"ativacao.brindes_disponiveis\",\"valor\":300},"
            + "{\"chave\":\"ativacao.brindes_distribuidos\",\"valor\":150}]}";
    private static final String ABA_NAO_LIBERADA = "Esta aba ainda não foi liberada para a sua parceria.";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    SessaoDeApi entrar(String email) {
        return SessaoDeApi.entrar(mvc, email);
    }

    long numero(MvcTestResult resposta, String caminho) {
        return ((Number) lerJson(resposta, caminho)).longValue();
    }

    String urlDeConversas(long parceria) {
        return "/api/parcerias/" + parceria + "/atlas/conversas";
    }

    /** Abre pelo botão e confere o 201. */
    MvcTestResult abrirPeloBotao(SessaoDeApi pessoa) {
        MvcTestResult resposta = pessoa.post(urlDeConversas(amarelo()), BOTAO);
        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        return resposta;
    }

    /** O mesmo e responde o cartão com tudo; devolve o id do relatório pronto. */
    long montarRelatorioCompleto(SessaoDeApi pessoa) {
        MvcTestResult aberta = abrirPeloBotao(pessoa);
        MvcTestResult pronta = pessoa.post("/api/atlas/solicitacoes/" + numero(aberta, "$.itens[2].solicitacaoId") + "/respostas", ACCEPT_COMPLETO);
        assertThat(pronta).hasStatus(HttpStatus.OK);
        return numero(pronta, "$.itens[1].relatorioId");
    }

    void fecharAba() {
        jdbc.sql("UPDATE parceria_aba SET liberada = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'APLICACOES')")
                .param("p", amarelo()).update();
    }

    // ---------------------------------------------------------------------
    // O plano B pela API
    // ---------------------------------------------------------------------

    @Test
    void conversaVaziaResponde201SemItens() {
        SessaoDeApi pessoa = entrar(AMARELO);

        for (String corpo : java.util.Arrays.asList("{}", null)) {
            MvcTestResult resposta = pessoa.post(urlDeConversas(amarelo()), corpo);
            assertThat(resposta).as("corpo [" + corpo + "]").hasStatus(HttpStatus.CREATED);
            assertThat(lerJson(resposta, "$.itens")).isEqualTo(List.of());
            assertThat(lerJson(resposta, "$.llmDisponivel")).isEqualTo(false);
            assertThat(numero(resposta, "$.conversaId")).isPositive();
        }
    }

    @Test
    void botaoAbreAConversaComOCartaoNoFormatoQueOFrontEspera() {
        MvcTestResult resposta = abrirPeloBotao(entrar(AMARELO));

        assertThat(resposta.getResponse().getContentType()).startsWith("application/json");
        assertThat(lerJson(resposta, "$.llmDisponivel")).isEqualTo(false);
        assertThat(lerLista(resposta, "$.itens")).hasSize(3);
        assertThat(lerJson(resposta, "$.itens[0].tipo")).isEqualTo("usuario");
        assertThat(lerJson(resposta, "$.itens[0].texto")).isEqualTo("Montar o relatório da ativação (botão)");
        assertThat(lerJson(resposta, "$.itens[1].tipo")).isEqualTo("texto");
        assertThat(lerJson(resposta, "$.itens[2].tipo")).isEqualTo("cartao");
        assertThat(chaves(resposta, "$.itens[2]")).containsExactly("tipo", "solicitacaoId", "relatorioId", "mensagem", "status", "campos");
        assertThat(lerJson(resposta, "$.itens[2].status")).isEqualTo("PENDENTE");
        assertThat(lerJson(resposta, "$.itens[2].mensagem")).isEqualTo("Para fechar a seção de público, preciso destes números.");
        assertThat(chaves(resposta, "$.itens[2].campos[0]")).containsExactly("chave", "aba", "rotulo", "tipo", "unidade", "minimo", "maximo",
                "ajuda", "limiteCampo", "obrigatorio", "vigente");
        assertThat(lerJson(resposta, "$.itens[2].campos[0].chave")).isEqualTo("ativacao.visitantes_total");
        assertThat(lerJson(resposta, "$.itens[2].campos[0].aba")).isEqualTo("APLICACOES");
        assertThat(lerJson(resposta, "$.itens[2].campos[0].obrigatorio")).isEqualTo(true);
        assertThat(lerJson(resposta, "$.itens[2].campos[0].vigente")).isNull();
        assertThat(lerJson(resposta, "$.itens[2].campos[0].maximo")).isNull();
        assertThat(lerJson(resposta, "$.itens[2].campos[0].minimo")).isEqualTo(0);
        assertThat(lerJson(resposta, "$.itens[2].campos[2].obrigatorio")).isEqualTo(false);
        assertThat(lerJson(resposta, "$.itens[2].campos[1].limiteCampo")).isEqualTo("ativacao.visitantes_total");
    }

    @Test
    void acceptEntregaORelatorioProntoEARota47TraOsValoresFormatados() {
        SessaoDeApi pessoa = entrar(AMARELO);
        MvcTestResult aberta = abrirPeloBotao(pessoa);
        long solicitacao = numero(aberta, "$.itens[2].solicitacaoId");

        MvcTestResult pronta = pessoa.post("/api/atlas/solicitacoes/" + solicitacao + "/respostas", ACCEPT_COMPLETO);

        assertThat(pronta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(pronta, "$.conversaId")).isEqualTo(lerJson(aberta, "$.conversaId"));
        assertThat(lerLista(pronta, "$.itens")).hasSize(2);
        assertThat(lerJson(pronta, "$.itens[0].tipo")).isEqualTo("texto");
        assertThat(lerJson(pronta, "$.itens[0].texto"))
                .isEqualTo("Pronto: o relatório de ativação está montado. A origem de cada número está em Fontes e cálculos.");
        assertThat(chaves(pronta, "$.itens[1]")).containsExactly("tipo", "relatorioId", "titulo");
        assertThat(lerJson(pronta, "$.itens[1].tipo")).isEqualTo("relatorio_pronto");
        assertThat(lerJson(pronta, "$.itens[1].titulo")).isEqualTo("Ativação no evento");

        long relatorio = numero(pronta, "$.itens[1].relatorioId");
        MvcTestResult doRelatorio = pessoa.get("/api/atlas/relatorios/" + relatorio);
        assertThat(doRelatorio).hasStatus(HttpStatus.OK);
        assertThat(chaves(doRelatorio, "$")).containsExactly("id", "parceriaId", "patrocinador", "modelo", "titulo", "status", "criadoPor",
                "criadoEm", "finalizadoEm", "selo", "secoes");
        assertThat(lerJson(doRelatorio, "$.status")).isEqualTo("PRONTO");
        assertThat(lerJson(doRelatorio, "$.patrocinador")).isEqualTo("Cliente Amarelo");
        assertThat(lerJson(doRelatorio, "$.modelo")).isEqualTo("ATIVACAO_EVENTO");
        assertThat(numero(doRelatorio, "$.parceriaId")).isEqualTo(amarelo());
        assertThat(lerJson(doRelatorio, "$.criadoPor.perfil")).isEqualTo("ADMIN_PATROCINADOR");
        assertThat((String) lerJson(doRelatorio, "$.criadoEm")).endsWith("-03:00");
        assertThat(lerJson(doRelatorio, "$.finalizadoEm")).isNotNull();
        assertThat(lerJson(doRelatorio, "$.selo")).isEqualTo("Documento de trabalho da marca, montado pelo Atlas. Não validado pela RBS.");
        assertThat(lerLista(doRelatorio, "$.secoes[*].id")).containsExactly("capa", "resumo", "aplicacoes", "publico", "comprovacoes", "fontes");

        // os valores saem formatados, com "%" nos percentuais
        assertThat(lerLista(doRelatorio, "$.secoes[2].blocos[0].itens[*].valor")).containsExactly("19", "17", "89,4%", "17");
        assertThat(lerLista(doRelatorio, "$.secoes[3].blocos[0].itens[*].valor")).containsExactly("1.200", "1.072", "89,3%", "50%");
        assertThat(lerJson(doRelatorio, "$.secoes[3].blocos[1].texto"))
                .isEqualTo("Visitantes no espaço da marca: 1.200. Taxa de cadastro com consentimento: 89,3% dos visitantes.");
        assertThat(lerJson(doRelatorio, "$.secoes[1].blocos[0].texto"))
                .isEqualTo("Aplicações de marca comprovadas no evento por Cliente Amarelo: 17 de 19 previstas.");

        // formato dos blocos
        assertThat(chaves(doRelatorio, "$.secoes[0].blocos[0]")).containsExactly("tipo", "id", "titulo", "subtitulo", "edicao");
        assertThat(lerJson(doRelatorio, "$.secoes[0].blocos[0].subtitulo")).isEqualTo("Cliente Amarelo");
        assertThat(lerJson(doRelatorio, "$.secoes[0].blocos[0].edicao")).isEqualTo("Planeta Atlântida 2027");
        assertThat(chaves(doRelatorio, "$.secoes[1].blocos[0]")).containsExactly("tipo", "id", "texto", "redigidoPor");
        assertThat(lerJson(doRelatorio, "$.secoes[1].blocos[0].redigidoPor")).isEqualTo("MODELO");
        assertThat(lerJson(doRelatorio, "$.secoes[2].blocos[0].tipo")).isEqualTo("kpi_grade");
        assertThat(chaves(doRelatorio, "$.secoes[2].blocos[0].itens[0]")).containsExactly("rotulo", "valor", "indisponivel", "ref");
        assertThat(lerJson(doRelatorio, "$.secoes[2].blocos[0].itens[0].ref")).isEqualTo("cons:aplicacoes.previstas");
        assertThat(lerJson(doRelatorio, "$.secoes[2].blocos[0].itens[0].indisponivel")).isNull();
        assertThat(lerJson(doRelatorio, "$.secoes[2].blocos[1].tipo")).isEqualTo("tabela");
        assertThat(chaves(doRelatorio, "$.secoes[2].blocos[1]")).containsExactly("tipo", "id", "colunas", "linhas", "ref");
        assertThat(lerJson(doRelatorio, "$.secoes[2].blocos[1].colunas")).isEqualTo(List.of("Aplicação", "Previsto", "Comprovadas", "Fotos"));
        assertThat(lerJson(doRelatorio, "$.secoes[2].blocos[1].ref")).isEqualTo("lista:aplicacoes");
        assertThat(lerJson(doRelatorio, "$.secoes[4].blocos[0].tipo")).isEqualTo("galeria_comprovacoes");
        assertThat(chaves(doRelatorio, "$.secoes[4].blocos[0].itens[0]")).containsExactly("arquivoId", "nomeOriginal", "aplicacao", "descricao",
                "registradoEm");
        assertThat(lerJson(doRelatorio, "$.secoes[5].blocos[0].tipo")).isEqualTo("fontes");
        assertThat(chaves(doRelatorio, "$.secoes[5].blocos[0].itens[0]")).containsExactly("ref", "rotulo", "valor", "origem");
        assertThat(lerLista(doRelatorio, "$.secoes[5].blocos[0].itens[?(@.ref=='calc:taxa_cadastro')].origem"))
                .containsExactly("pct(cadastros, visitantes) com cadastros = 1.072 e visitantes = 1.200");
        assertThat(lerLista(doRelatorio, "$.secoes[5].blocos[0].itens[?(@.ref=='dado:ativacao.visitantes_total')].origem").get(0).toString())
                .startsWith("Informado por Marketing Amarelo em ").endsWith(", pelo Atlas");
        assertThat(lerLista(doRelatorio, "$.secoes[5].blocos[0].itens[?(@.ref=='cons:aplicacoes.previstas')].origem"))
                .containsExactly("Consolidação do portal: plano comercial e fotos de comprovação");
    }

    @Test
    void aTaxaDoRelatorioEAMesmaDeFnPctTruncado() {
        long relatorio = montarRelatorioCompleto(entrar(AMARELO));

        MvcTestResult doRelatorio = entrar(AMARELO).get("/api/atlas/relatorios/" + relatorio);

        String taxa = ((String) lerJson(doRelatorio, "$.secoes[3].blocos[0].itens[2].valor")).replace(',', '.').replace("%", "");
        assertThat(new java.math.BigDecimal(taxa)).isEqualByComparingTo(
                jdbc.sql("SELECT fn_pct_truncado(100 * CAST(1072 AS numeric) / 1200)").query(java.math.BigDecimal.class).single());
    }

    @Test
    void declineDeixaOsNumerosIndisponiveisNuncaZero() {
        SessaoDeApi pessoa = entrar(AMARELO);
        MvcTestResult aberta = abrirPeloBotao(pessoa);

        MvcTestResult pronta = pessoa.post("/api/atlas/solicitacoes/" + numero(aberta, "$.itens[2].solicitacaoId") + "/respostas",
                "{\"acao\":\"decline\"}");

        assertThat(pronta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(pronta, "$.itens[1].tipo")).isEqualTo("relatorio_pronto");
        MvcTestResult doRelatorio = pessoa.get("/api/atlas/relatorios/" + numero(pronta, "$.itens[1].relatorioId"));
        assertThat(lerLista(doRelatorio, "$.secoes[3].blocos[0].itens[*].valor")).containsExactly(null, null, null, null);
        assertThat(lerJson(doRelatorio, "$.secoes[3].blocos[0].itens[0].indisponivel")).isEqualTo("Não informado pela marca.");
        assertThat(lerJson(doRelatorio, "$.secoes[3].blocos[1].tipo")).isEqualTo("aviso_indisponivel");
        assertThat(chaves(doRelatorio, "$.secoes[3].blocos[1]")).containsExactly("tipo", "id", "motivo");
        assertThat(corpo(doRelatorio)).doesNotContain("\"valor\":\"0\"").doesNotContain("\"valor\":0");
    }

    @Test
    void cancelDevolveUmAvisoEOBotaoContinuaOMesmoRelatorio() {
        SessaoDeApi pessoa = entrar(AMARELO);
        MvcTestResult aberta = abrirPeloBotao(pessoa);
        long conversa = numero(aberta, "$.conversaId");

        MvcTestResult fechada = pessoa.post("/api/atlas/solicitacoes/" + numero(aberta, "$.itens[2].solicitacaoId") + "/respostas",
                "{\"acao\":\"cancel\"}");

        assertThat(fechada).hasStatus(HttpStatus.OK);
        assertThat(lerLista(fechada, "$.itens")).hasSize(1);
        assertThat(lerJson(fechada, "$.itens[0].tipo")).isEqualTo("aviso");
        assertThat(lerJson(fechada, "$.itens[0].texto")).isEqualTo("Cartão fechado. Use Montar relatório para continuar.");

        MvcTestResult continuada = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", BOTAO);
        assertThat(continuada).hasStatus(HttpStatus.OK);
        assertThat(lerLista(continuada, "$.itens[*].tipo")).containsExactly("usuario", "texto", "cartao");
        assertThat(numero(continuada, "$.itens[2].relatorioId")).isEqualTo(numero(aberta, "$.itens[2].relatorioId"));
    }

    @Test
    void aConversaInteiraEAsListas() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long relatorio = montarRelatorioCompleto(pessoa);

        MvcTestResult lista = pessoa.get(urlDeConversas(amarelo()));
        assertThat(lista).hasStatus(HttpStatus.OK);
        assertThat(chaves(lista, "$")).containsExactly("llmDisponivel", "conversas");
        assertThat(lerJson(lista, "$.llmDisponivel")).isEqualTo(false);
        assertThat(lerLista(lista, "$.conversas")).hasSize(1);
        assertThat(chaves(lista, "$.conversas[0]")).containsExactly("id", "criadaEm", "atualizadaEm", "relatorio");
        assertThat(numero(lista, "$.conversas[0].relatorio.id")).isEqualTo(relatorio);
        assertThat(lerJson(lista, "$.conversas[0].relatorio.status")).isEqualTo("PRONTO");

        MvcTestResult inteira = pessoa.get("/api/atlas/conversas/" + numero(lista, "$.conversas[0].id"));
        assertThat(inteira).hasStatus(HttpStatus.OK);
        assertThat(lerLista(inteira, "$.itens[*].tipo")).containsExactly("usuario", "texto", "cartao", "texto", "relatorio_pronto");
        assertThat(lerJson(inteira, "$.itens[2].status")).isEqualTo("RESPONDIDA");
        assertThat(lerJson(inteira, "$.itens[2].campos[0].vigente.origem")).isEqualTo("ATLAS");
        assertThat(lerJson(inteira, "$.itens[2].campos[0].vigente.valor")).isEqualTo(1200);
        assertThat(lerJson(inteira, "$.itens[2].campos[0].vigente.informadoPor.nome")).isEqualTo("Marketing Amarelo");

        MvcTestResult relatorios = pessoa.get("/api/parcerias/" + amarelo() + "/atlas/relatorios");
        assertThat(relatorios).hasStatus(HttpStatus.OK);
        assertThat(chaves(relatorios, "$[0]")).containsExactly("id", "modelo", "titulo", "status", "criadoPor", "criadoEm", "finalizadoEm");
        assertThat(numero(relatorios, "$[0].id")).isEqualTo(relatorio);
        assertThat(lerJson(relatorios, "$[0].titulo")).isEqualTo("Ativação no evento");
    }

    @Test
    void aListaDeRelatoriosComecaVaziaEMostraOQueOutroUsuarioDaParceriaMontou() {
        assertThat(lerLista(entrar(AGENCIA).get("/api/parcerias/" + amarelo() + "/atlas/relatorios"), "$")).isEmpty();

        long relatorio = montarRelatorioCompleto(entrar(AMARELO));

        MvcTestResult daAgencia = entrar(AGENCIA).get("/api/parcerias/" + amarelo() + "/atlas/relatorios");
        assertThat(numero(daAgencia, "$[0].id")).isEqualTo(relatorio);
        assertThat(entrar(AGENCIA).get("/api/atlas/relatorios/" + relatorio)).hasStatus(HttpStatus.OK);
        assertThat(entrar(RBS).get("/api/atlas/relatorios/" + relatorio)).hasStatus(HttpStatus.OK);
    }

    // ---------------------------------------------------------------------
    // Erros
    // ---------------------------------------------------------------------

    @Test
    void semSessaoTodasAsRotasDao401() {
        SessaoDeApi anonima = SessaoDeApi.anonima(mvc);

        assertThat(anonima.post(urlDeConversas(1), BOTAO)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(anonima.get(urlDeConversas(1))).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(anonima.post("/api/atlas/conversas/1/mensagens", BOTAO)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(anonima.get("/api/atlas/conversas/1")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(anonima.post("/api/atlas/solicitacoes/1/respostas", "{\"acao\":\"cancel\"}")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(anonima.get("/api/atlas/relatorios/1")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(anonima.get("/api/parcerias/1/atlas/relatorios")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void postSemOTokenCsrfDa403() {
        SessaoDeApi pessoa = entrar(AMARELO);
        MvcTestResult aberta = abrirPeloBotao(pessoa);

        assertThat(pessoa.postSemCsrf(urlDeConversas(amarelo()), BOTAO)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(pessoa.postSemCsrf("/api/atlas/conversas/" + numero(aberta, "$.conversaId") + "/mensagens", BOTAO)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(pessoa.postSemCsrf("/api/atlas/solicitacoes/" + numero(aberta, "$.itens[2].solicitacaoId") + "/respostas",
                "{\"acao\":\"cancel\"}")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(pessoa.get("/api/atlas/conversas/" + numero(aberta, "$.conversaId")), "$.itens[2].status")).isEqualTo("PENDENTE");
    }

    @Test
    void erroDeCartaoJaRespondidoETurnoSaemComProblemDetail() {
        SessaoDeApi pessoa = entrar(AMARELO);
        MvcTestResult aberta = abrirPeloBotao(pessoa);
        long conversa = numero(aberta, "$.conversaId");
        String respostas = "/api/atlas/solicitacoes/" + numero(aberta, "$.itens[2].solicitacaoId") + "/respostas";

        MvcTestResult segundoCartao = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", BOTAO);
        assertThat(segundoCartao).hasStatus(HttpStatus.CONFLICT);
        assertThat(segundoCartao.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(segundoCartao, "$.title")).isEqualTo("Conflito");

        assertThat(pessoa.post(respostas, "{\"acao\":\"decline\"}")).hasStatus(HttpStatus.OK);
        MvcTestResult jaRespondido = pessoa.post(respostas, "{\"acao\":\"cancel\"}");
        assertThat(jaRespondido).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(jaRespondido, "$.detail")).isEqualTo("Este cartão já foi respondido.");

        jdbc.sql("UPDATE atlas_conversa SET turno_desde = now() WHERE id = :id").param("id", conversa).update();
        MvcTestResult turno = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", BOTAO);
        assertThat(turno).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(turno, "$.detail")).isEqualTo("O Atlas ainda está respondendo nesta conversa. Espere a resposta.");
    }

    @Test
    void textoSemModeloDeLinguagemDa503ComAFraseDoBotao() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = numero(pessoa.post(urlDeConversas(amarelo()), "{}"), "$.conversaId");

        MvcTestResult resposta = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Quanto deu por visitante?\"}");

        assertThat(resposta).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resposta.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(resposta, "$.status")).isEqualTo(503);
        assertThat(lerJson(resposta, "$.title")).isEqualTo("Serviço indisponível");
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("O Atlas está sem o modelo de linguagem agora. Use o botão Montar relatório.");
    }

    @Test
    void erros422TrazemCampos() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = numero(pessoa.post(urlDeConversas(amarelo()), "{}"), "$.conversaId");
        String mensagens = "/api/atlas/conversas/" + conversa + "/mensagens";

        for (String corpo : List.of("{\"texto\":\"\"}", "{\"texto\":\"" + "x".repeat(1001) + "\"}")) {
            MvcTestResult resposta = pessoa.post(mensagens, corpo);
            assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(lerJson(resposta, "$.campos.texto")).isNotNull();
        }
        assertThat(lerJson(pessoa.post(urlDeConversas(amarelo()), "{\"atalho\":\"OUTRO\",\"modelo\":\"ATIVACAO_EVENTO\"}"), "$.campos.atalho"))
                .isNotNull();
        assertThat(lerJson(pessoa.post(urlDeConversas(amarelo()), "{\"atalho\":\"MONTAR_RELATORIO\",\"modelo\":\"OUTRO\"}"), "$.campos.modelo"))
                .isNotNull();
        assertThat(pessoa.post(mensagens, "{}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(pessoa.post(mensagens, "")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(pessoa.post(mensagens, "isto nao e json")).hasStatus(HttpStatus.BAD_REQUEST);

        long cartao = numero(abrirPeloBotao(pessoa), "$.itens[2].solicitacaoId");
        MvcTestResult acaoRuim = pessoa.post("/api/atlas/solicitacoes/" + cartao + "/respostas", "{\"acao\":\"talvez\"}");
        assertThat(acaoRuim).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(acaoRuim, "$.campos.acao")).isEqualTo("Use accept, decline ou cancel.");
    }

    @Test
    void acceptComValorRuimDa422PelaChaveDoCampoENadaFicaGravado() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long cartao = numero(abrirPeloBotao(pessoa), "$.itens[2].solicitacaoId");
        String respostas = "/api/atlas/solicitacoes/" + cartao + "/respostas";

        MvcTestResult cadastrosAcima = pessoa.post(respostas, "{\"acao\":\"accept\",\"valores\":[{\"chave\":\"ativacao.visitantes_total\",\"valor\":100},"
                + "{\"chave\":\"ativacao.cadastros_consentidos\",\"valor\":101}]}");
        assertThat(cadastrosAcima).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(cadastrosAcima, "$.campos['ativacao.cadastros_consentidos']")).isEqualTo("Não pode passar de Visitantes no espaço (100).");

        MvcTestResult semObrigatoria = pessoa.post(respostas, "{\"acao\":\"accept\",\"valores\":[{\"chave\":\"ativacao.visitantes_total\",\"valor\":100}]}");
        assertThat(semObrigatoria).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(semObrigatoria, "$.campos['ativacao.cadastros_consentidos']")).isEqualTo("Informe este número.");

        MvcTestResult foraDoCartao = pessoa.post(respostas, "{\"acao\":\"accept\",\"valores\":[{\"chave\":\"ativacao.outro\",\"valor\":1}]}");
        assertThat(foraDoCartao).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(foraDoCartao, "$.campos['ativacao.outro']")).isEqualTo("Este campo não está no cartão.");

        assertThat(jdbc.sql("SELECT count(*) FROM dado_informado WHERE parceria_id = :p").param("p", amarelo()).query(Long.class).single()).isZero();
        assertThat(pessoa.post(respostas, "{\"acao\":\"cancel\"}")).as("o cartão continua aberto").hasStatus(HttpStatus.OK);
    }

    @Test
    void idQueNaoEhNumeroDa404ComAFraseDoRecurso() {
        SessaoDeApi pessoa = entrar(AMARELO);

        Map<String, String> esperado = new java.util.LinkedHashMap<>();
        esperado.put("/api/atlas/conversas/abc", "Não encontramos esta conversa.");
        esperado.put("/api/atlas/relatorios/abc", "Não encontramos este relatório.");
        esperado.put("/api/parcerias/abc/atlas/conversas", "Não encontramos esta parceria.");
        esperado.put("/api/parcerias/abc/atlas/relatorios", "Não encontramos esta parceria.");
        esperado.forEach((uri, frase) -> {
            MvcTestResult resposta = pessoa.get(uri);
            assertThat(resposta).as(uri).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(lerJson(resposta, "$.detail")).as(uri).isEqualTo(frase);
        });
        MvcTestResult cartao = pessoa.post("/api/atlas/solicitacoes/abc/respostas", "{\"acao\":\"cancel\"}");
        assertThat(cartao).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(cartao, "$.detail")).isEqualTo("Não encontramos esta solicitação.");
        assertThat(pessoa.post("/api/atlas/conversas/abc/mensagens", BOTAO)).hasStatus(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------------
    // Isolamento e permissão
    // ---------------------------------------------------------------------

    @Test
    void marcaDoRoxoComIdsDoAmareloTomaSempre404() {
        SessaoDeApi amarelo = entrar(AMARELO);
        MvcTestResult aberta = abrirPeloBotao(amarelo);
        long conversa = numero(aberta, "$.conversaId");
        long cartao = numero(aberta, "$.itens[2].solicitacaoId");
        long relatorio = montarRelatorioCompleto(amarelo);
        SessaoDeApi roxo = entrar(ROXO);

        Map<String, MvcTestResult> respostas = new java.util.LinkedHashMap<>();
        respostas.put("42 abrir", roxo.post(urlDeConversas(amarelo()), BOTAO));
        respostas.put("42 abrir vazia", roxo.post(urlDeConversas(amarelo()), "{}"));
        respostas.put("43 listar", roxo.get(urlDeConversas(amarelo())));
        respostas.put("44 mensagem", roxo.post("/api/atlas/conversas/" + conversa + "/mensagens", BOTAO));
        respostas.put("45 conversa", roxo.get("/api/atlas/conversas/" + conversa));
        respostas.put("46 resposta", roxo.post("/api/atlas/solicitacoes/" + cartao + "/respostas", "{\"acao\":\"cancel\"}"));
        respostas.put("47 relatório", roxo.get("/api/atlas/relatorios/" + relatorio));
        respostas.put("48 lista", roxo.get("/api/parcerias/" + amarelo() + "/atlas/relatorios"));
        respostas.forEach((rota, resposta) -> assertThat(resposta).as(rota).hasStatus(HttpStatus.NOT_FOUND));

        // a frase é a mesma de um id que não existe
        assertThat(lerJson(respostas.get("45 conversa"), "$.detail")).isEqualTo(lerJson(roxo.get("/api/atlas/conversas/999999"), "$.detail"));
        assertThat(lerJson(respostas.get("46 resposta"), "$.detail"))
                .isEqualTo(lerJson(roxo.post("/api/atlas/solicitacoes/999999/respostas", "{\"acao\":\"cancel\"}"), "$.detail"));
        assertThat(lerJson(respostas.get("47 relatório"), "$.detail")).isEqualTo(lerJson(roxo.get("/api/atlas/relatorios/999999"), "$.detail"));
        assertThat(lerJson(respostas.get("42 abrir"), "$.detail")).isEqualTo(lerJson(roxo.post(urlDeConversas(999999), BOTAO), "$.detail"));
    }

    @Test
    void conversaESolicitacaoDeOutroUsuarioDaMesmaParceriaDao404() {
        MvcTestResult aberta = abrirPeloBotao(entrar(AMARELO));
        long conversa = numero(aberta, "$.conversaId");
        long cartao = numero(aberta, "$.itens[2].solicitacaoId");
        SessaoDeApi agencia = entrar(AGENCIA);

        assertThat(agencia.get("/api/atlas/conversas/" + conversa)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(agencia.post("/api/atlas/conversas/" + conversa + "/mensagens", BOTAO)).hasStatus(HttpStatus.NOT_FOUND);
        MvcTestResult resposta = agencia.post("/api/atlas/solicitacoes/" + cartao + "/respostas", ACCEPT_COMPLETO);
        assertThat(resposta).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("Não encontramos esta solicitação.");
        assertThat(lerLista(agencia.get(urlDeConversas(amarelo())), "$.conversas")).isEmpty();
        assertThat(lerJson(entrar(AMARELO).get("/api/atlas/conversas/" + conversa), "$.itens[2].status")).isEqualTo("PENDENTE");
    }

    @Test
    void aAgenciaTambemConversaComOAtlasNaPropriaConversa() {
        SessaoDeApi agencia = entrar(AGENCIA);

        MvcTestResult aberta = abrirPeloBotao(agencia);
        MvcTestResult pronta = agencia.post("/api/atlas/solicitacoes/" + numero(aberta, "$.itens[2].solicitacaoId") + "/respostas", ACCEPT_COMPLETO);

        assertThat(pronta).hasStatus(HttpStatus.OK);
        MvcTestResult relatorio = agencia.get("/api/atlas/relatorios/" + numero(pronta, "$.itens[1].relatorioId"));
        assertThat(lerJson(relatorio, "$.criadoPor.nome")).isEqualTo("Agência de ativação");
        assertThat(lerJson(relatorio, "$.secoes[5].blocos[0].itens[?(@.ref=='dado:ativacao.visitantes_total')].origem").toString())
                .contains("Informado por Agência de ativação");
    }

    @Test
    void rbsTomaOs403DasRotasDaConversaMasLeORelatorio() {
        SessaoDeApi amarelo = entrar(AMARELO);
        MvcTestResult aberta = abrirPeloBotao(amarelo);
        long conversa = numero(aberta, "$.conversaId");
        long cartao = numero(aberta, "$.itens[2].solicitacaoId");
        SessaoDeApi rbs = entrar(RBS);

        assertThat(rbs.post(urlDeConversas(amarelo()), BOTAO)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(rbs.get(urlDeConversas(amarelo()))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(rbs.post("/api/atlas/conversas/" + conversa + "/mensagens", BOTAO)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(rbs.get("/api/atlas/conversas/" + conversa)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(rbs.post("/api/atlas/solicitacoes/" + cartao + "/respostas", ACCEPT_COMPLETO)).hasStatus(HttpStatus.FORBIDDEN);
        long relatorio = montarRelatorioCompleto(entrar(AGENCIA));
        assertThat(rbs.get("/api/atlas/relatorios/" + relatorio)).hasStatus(HttpStatus.OK);
        assertThat(rbs.get("/api/parcerias/" + amarelo() + "/atlas/relatorios")).hasStatus(HttpStatus.OK);
    }

    @Test
    void abaDeAplicacoesFechadaDa403NasSeteRotasATambemNoRelatorioProntoEARbsPassa() {
        SessaoDeApi amarelo = entrar(AMARELO);
        MvcTestResult aberta = abrirPeloBotao(amarelo);
        long conversa = numero(aberta, "$.conversaId");
        long cartao = numero(aberta, "$.itens[2].solicitacaoId");
        long relatorio = montarRelatorioCompleto(amarelo);
        fecharAba();

        Map<String, MvcTestResult> respostas = new java.util.LinkedHashMap<>();
        respostas.put("42", amarelo.post(urlDeConversas(amarelo()), BOTAO));
        respostas.put("43", amarelo.get(urlDeConversas(amarelo())));
        respostas.put("44", amarelo.post("/api/atlas/conversas/" + conversa + "/mensagens", BOTAO));
        respostas.put("45", amarelo.get("/api/atlas/conversas/" + conversa));
        respostas.put("46", amarelo.post("/api/atlas/solicitacoes/" + cartao + "/respostas", "{\"acao\":\"cancel\"}"));
        respostas.put("47 pronto", amarelo.get("/api/atlas/relatorios/" + relatorio));
        respostas.put("48", amarelo.get("/api/parcerias/" + amarelo() + "/atlas/relatorios"));
        respostas.put("48 pela agência", entrar(AGENCIA).get("/api/parcerias/" + amarelo() + "/atlas/relatorios"));
        respostas.forEach((rota, resposta) -> {
            assertThat(resposta).as(rota).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(resposta, "$.detail")).as(rota).isEqualTo(ABA_NAO_LIBERADA);
        });

        SessaoDeApi rbs = entrar(RBS);
        assertThat(rbs.get("/api/atlas/relatorios/" + relatorio)).hasStatus(HttpStatus.OK);
        assertThat(rbs.get("/api/parcerias/" + amarelo() + "/atlas/relatorios")).hasStatus(HttpStatus.OK);
    }

    @Test
    void vinculoRevogadoDerrubaASessaoNaChamadaSeguinte() {
        SessaoDeApi agencia = entrar(AGENCIA);
        long conversa = numero(agencia.post(urlDeConversas(amarelo()), "{}"), "$.conversaId");
        jdbc.sql("DELETE FROM parceria_usuario WHERE parceria_id = :p AND usuario_id = (SELECT id FROM usuario WHERE email = :e)")
                .param("p", amarelo()).param("e", AGENCIA).update();

        assertThat(agencia.get("/api/atlas/conversas/" + conversa)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(agencia.get(urlDeConversas(amarelo()))).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @SuppressWarnings("unchecked")
    private static List<String> chaves(MvcTestResult resposta, String caminho) {
        return new java.util.ArrayList<>(((Map<String, Object>) lerJson(resposta, caminho)).keySet());
    }
}
