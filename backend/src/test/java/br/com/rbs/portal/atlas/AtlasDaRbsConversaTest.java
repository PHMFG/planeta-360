package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.RBS;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.exigirHistoricoValido;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.nomesDasFerramentas;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.ApoioDoDiagnostico.Linha;
import br.com.rbs.portal.atlas.loop.PromptsDoAtlas;
import br.com.rbs.portal.atlas.loop.TetoDaRbs;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.MensagemDeChat;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.PedidoAoLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.LimiteDeUsoException;
import java.time.format.DateTimeFormatter;
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
 * [ATLAS] A conversa da RBS de ponta a ponta (M8), com o modelo roteirizado, pelas rotas 67, 44, 45 e 49 como o front as usa: o prompt
 * é o da RBS e as ferramentas são só as quatro dela; a busca só traz o manual da edição, com a fonte; o "não encontrei" não manda a
 * RBS falar com a RBS; ferramenta de marca pedida pelo modelo e marcador de parceria na resposta não passam; a checagem das fontes e
 * dos números vale como na conversa da marca; o traço fica sem parceria; e o teto é por pessoa. Não é {@code @Transactional}: o turno
 * roda com transações curtas próprias e o resumo, em outra thread; o que o teste confirmou é apagado no fim.
 */
@TesteDoAtlasDaRbs
class AtlasDaRbsConversaTest {

    private static final String NAO_ENCONTREI = "Não encontrei isso nos documentos do portal.";
    private static final String PRAZO = "04/12/2026";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    AtlasRepositorio repositorio;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void preparar() {
        modelo.limpar();
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagar() {
        limpeza.apagar();
    }

    SessaoDeApi entrar() {
        return SessaoDeApi.entrar(mvc, RBS);
    }

    long evento2027() {
        return jdbc.sql("SELECT id FROM evento WHERE edicao = 2027").query(Long.class).single();
    }

    long abrir(SessaoDeApi pessoa) {
        MvcTestResult aberta = pessoa.post("/api/eventos/" + evento2027() + "/atlas/conversas", "");
        assertThat(aberta).hasStatus(HttpStatus.CREATED);
        return ((Number) lerJson(aberta, "$.conversaId")).longValue();
    }

    MvcTestResult falar(SessaoDeApi pessoa, long conversa, String texto) {
        MvcTestResult resposta = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"" + texto + "\"}");
        assertThat(resposta).hasStatus(HttpStatus.OK);
        return resposta;
    }

    List<String> textos(MvcTestResult resposta) {
        return lerLista(resposta, "$.itens[?(@.tipo=='texto')].texto");
    }

    List<String> tipos(MvcTestResult resposta) {
        return lerLista(resposta, "$.itens[*].tipo");
    }

    // ---------------------------------------------------------------------
    // O turno: itens, prompt, ferramentas e traço
    // ---------------------------------------------------------------------

    @Test
    void aRbsConversaERecebeOsItensDoTurnoComODiagnosticoDisponivel() {
        SessaoDeApi rbs = entrar();
        long conversa = abrir(rbs);
        modelo.texto("Posso ajudar com os documentos da edição.");

        MvcTestResult resposta = falar(rbs, conversa, "Oi, tudo bem?");

        assertThat(lerJson(resposta, "$.conversaId")).isEqualTo((int) conversa);
        assertThat(lerJson(resposta, "$.relatorioDisponivel")).isEqualTo(false);
        assertThat(lerJson(resposta, "$.diagnosticoDisponivel")).isEqualTo(true);
        assertThat(tipos(resposta)).containsExactly("usuario", "texto");
        assertThat(textos(resposta)).containsExactly("Posso ajudar com os documentos da edição.");
        assertThat(lerLista(resposta, "$.itens[?(@.tipo=='texto')].porModelo")).containsExactly(true);
        assertThat(lerLista(resposta, "$.itens[?(@.tipo=='texto')].turno")).hasSize(1).doesNotContainNull();
        // a conversa inteira pela rota 45
        MvcTestResult inteira = rbs.get("/api/atlas/conversas/" + conversa);
        assertThat(inteira).hasStatus(HttpStatus.OK);
        assertThat(tipos(inteira)).containsExactly("usuario", "texto");
        assertThat(lerJson(inteira, "$.diagnosticoDisponivel")).isEqualTo(true);
    }

    @Test
    void oModeloRecebeOPromptDaRbsEAsSeisFerramentasDela() {
        SessaoDeApi rbs = entrar();
        long conversa = abrir(rbs);
        modelo.texto("Posso ajudar com os documentos da edição.");

        falar(rbs, conversa, "Oi");

        PedidoAoLlm pedido = modelo.ultimoPedido();
        String esperado = PromptsDoAtlas.comporDaRbs().replace("@DATA@", DateTimeFormatter.ofPattern("dd/MM/yyyy").format(Datas.hoje()));
        assertThat(pedido.mensagens().get(0).conteudo()).isEqualTo(esperado);
        assertThat(esperado).contains("equipe da RBS").doesNotContain("@MARCA@").doesNotContain("Cliente Amarelo");
        assertThat(nomesDasFerramentas(pedido)).containsExactly("buscar_documentos", "listar_entregas", "consultar_midia", "contar_dias",
                "calcular", "gerar_modelo");
        assertThat(pedido.ferramentas().toString()).doesNotContain("parceria_consultar").doesNotContain("relatorio_");
        exigirHistoricoValido(pedido);
        assertThat(pedido.sessao()).isEqualTo(String.valueOf(conversa));
    }

    @Test
    void oPromptDaRbsTemMenosDeSeisMilCaracteresETemSoAData() {
        String prompt = PromptsDoAtlas.comporDaRbs();

        assertThat(prompt.length()).isLessThan(6_000);
        assertThat(prompt).contains("@DATA@").doesNotContain("@MARCA@").doesNotContain("@PEDIDOS_DE_RELATORIO@");
        assertThat(prompt.replace("@DATA@", "")).doesNotContainPattern("@[A-Z_]+@");
    }

    @Test
    void oTracoDoTurnoFicaSemParceriaEComOTetoPorUsuario() {
        SessaoDeApi rbs = entrar();
        long conversa = abrir(rbs);
        modelo.texto("Posso ajudar com os documentos da edição.");

        falar(rbs, conversa, "Oi");

        List<Linha> turno = ApoioDoDiagnostico.tracoDoTurno(jdbc, conversa);
        assertThat(ApoioDoDiagnostico.etapas(turno)).startsWith("G1_LIMITES", "LLM_IDA").contains("CHECAGEM").endsWith("SAIDA");
        assertThat(ApoioDoDiagnostico.primeira(turno, "G1_LIMITES").dados()).containsEntry("tetoPorUsuario", 200).containsKey("usadasHoje")
                .doesNotContainKey("tetoPorParceria");
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_traco WHERE conversa_id = :c AND parceria_id IS NOT NULL").param("c", conversa)
                .query(Long.class).single()).isZero();
        ApoioDoDiagnostico.exigirCoerencia(turno);
        // a RBS lê o diagnóstico da própria conversa (rota 49) e, na lista, o turno sem parceria (rotas 50 e 51)
        MvcTestResult diagnostico = rbs.get("/api/atlas/conversas/" + conversa + "/diagnostico");
        assertThat(diagnostico).hasStatus(HttpStatus.OK);
        assertThat(lerLista(diagnostico, "$.turnos")).hasSize(1);
        String idDoTurno = (String) lerJson(diagnostico, "$.turnos[0].turno");
        MvcTestResult lista = rbs.get("/api/atlas/diagnostico/turnos?tamanho=200");
        assertThat(lerLista(lista, "$.itens[?(@.turno=='" + idDoTurno + "')].parceriaId")).containsExactly((Object) null);
        MvcTestResult detalhe = rbs.get("/api/atlas/diagnostico/turnos/" + idDoTurno);
        assertThat(detalhe).hasStatus(HttpStatus.OK);
        assertThat(lerJson(detalhe, "$.parceria")).isNull();
        assertThat(lerJson(detalhe, "$.usuario.perfil")).isEqualTo("ADMIN_RBS");
    }

    // ---------------------------------------------------------------------
    // A busca: só o manual da edição, com a fonte
    // ---------------------------------------------------------------------

    @Test
    void aBuscaTrazOManualDaEdicaoEARespostaCitaAFonte() {
        SessaoDeApi rbs = entrar();
        long conversa = abrir(rbs);
        modelo.chamada("buscar_documentos", FontesNoChatTest.busca("prazo para informar os geradores próprios"));
        modelo.passo(pedido -> new RespostaDoLlm("Os geradores próprios devem ser informados à produção até " + PRAZO + " ["
                + FontesNoChatTest.marcador(pedido, PRAZO, true) + "].", List.of(), null));

        MvcTestResult resposta = falar(rbs, conversa, "Até quando informo os geradores?");

        assertThat(textos(resposta)).hasSize(1);
        assertThat(textos(resposta).get(0)).matches("Os geradores próprios devem ser informados à produção até 04/12/2026 \\[T[1-9]\\]\\.");
        assertThat(lerLista(resposta, "$.itens[?(@.tipo=='texto')].fontes[0].tipo")).containsExactly("TRECHO");
        assertThat(lerLista(resposta, "$.itens[?(@.tipo=='texto')].fontes[0].titulo")).containsExactly("Manual de Operações 2027");
        assertThat(lerLista(resposta, "$.itens[?(@.tipo=='texto')].fontes[0].versao")).containsExactly(2);
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_fonte f JOIN atlas_mensagem m ON m.id = f.mensagem_id WHERE m.conversa_id = :c")
                .param("c", conversa).query(Long.class).single()).isEqualTo(1);
        // o traço tem a etapa da busca, sem parceria
        assertThat(ApoioDoDiagnostico.etapas(ApoioDoDiagnostico.tracoDoTurno(jdbc, conversa))).contains("BUSCA", "FERRAMENTA");
    }

    static String buscaDePlano() {
        return "{\"consulta\":\"cortesias do plano comercial da cota Master\",\"tipo\":\"PLANO_COMERCIAL\"}";
    }

    static String buscaSemResultado() {
        return "{\"consulta\":\"zzzqqq xyzzy\"}";
    }

    @Test
    void oPlanoDeCotaExisteParaARbsEApareceNaBusca() {
        SessaoDeApi rbs = entrar();
        long conversa = abrir(rbs);
        modelo.chamada("buscar_documentos", buscaDePlano()).texto("O plano da cota Master está nos documentos da edição.");

        MvcTestResult resposta = falar(rbs, conversa, "Quantas cortesias a Master tem?");

        assertThat(textos(resposta)).containsExactly("O plano da cota Master está nos documentos da edição.");
        String resultado = modelo.ultimoPedido().mensagens().stream().filter(m -> "tool".equals(m.papel())).map(m -> m.conteudo())
                .reduce((primeiro, segundo) -> segundo).orElseThrow();
        assertThat(resultado).contains("Plano comercial · Cota Master 2027").doesNotContain("\"trechos\":[]");
    }

    @Test
    void aFraseDeNaoEncontreiNaoMandaFalarComARbs() {
        SessaoDeApi rbs = entrar();
        long conversa = abrir(rbs);
        modelo.chamada("buscar_documentos", buscaSemResultado()).texto(NAO_ENCONTREI);

        MvcTestResult resposta = falar(rbs, conversa, "Quantas cortesias a Master tem?");

        assertThat(textos(resposta)).containsExactly(NAO_ENCONTREI);
        assertThat(corpo(resposta)).doesNotContain("atendimento da RBS");
        assertThat(lerLista(resposta, "$.itens[?(@.tipo=='texto')].porModelo")).as("a frase fixa é do servidor").containsExactly(false);
        // o que a busca devolveu ao modelo: nenhum trecho (a consulta não casa com nenhum documento da edição)
        String resultado = modelo.ultimoPedido().mensagens().stream().filter(m -> "tool".equals(m.papel())).map(m -> m.conteudo())
                .reduce((primeiro, segundo) -> segundo).orElseThrow();
        assertThat(resultado).contains("\"trechos\":[]").contains("Nenhum trecho dos documentos do portal responde");
    }

    @Test
    void respostaSemFonteDepoisDeBuscaVaziaViraAFraseDeNaoEncontreiSemIndicacao() {
        SessaoDeApi rbs = entrar();
        long conversa = abrir(rbs);
        modelo.chamada("buscar_documentos", buscaSemResultado()).texto("O plano da Master dá uma boa quantidade de cortesias para a marca.");

        MvcTestResult resposta = falar(rbs, conversa, "Quantas cortesias a Master tem?");

        assertThat(textos(resposta)).containsExactly(NAO_ENCONTREI);
        assertThat(ApoioDoDiagnostico.primeira(ApoioDoDiagnostico.tracoDoTurno(jdbc, conversa), "SAIDA").dados()).containsEntry("motivo", "SEM_FONTE");
    }

    @Test
    void segundaRecusaDepoisDeUmaBuscaViraAFraseDeNaoEncontreiSemIndicacao() {
        SessaoDeApi rbs = entrar();
        long conversa = abrir(rbs);
        modelo.chamada("buscar_documentos", FontesNoChatTest.busca("prazo para informar os geradores próprios"))
                .texto("O prazo é 15/12/2026.").texto("O prazo é 15/12/2026.");

        MvcTestResult resposta = falar(rbs, conversa, "Qual é o prazo?");

        assertThat(textos(resposta)).containsExactly(NAO_ENCONTREI);
        assertThat(corpo(resposta)).doesNotContain("atendimento da RBS").doesNotContain("15/12/2026");
    }

    // ---------------------------------------------------------------------
    // Ferramenta de marca e marcador de parceria: não passam
    // ---------------------------------------------------------------------

    @Test
    void ferramentaDeMarcaPedidaPeloModeloVoltaComoErroENadaVaza() {
        SessaoDeApi rbs = entrar();
        long conversa = abrir(rbs);
        long relatorios = jdbc.sql("SELECT count(*) FROM atlas_relatorio").query(Long.class).single();
        modelo.chamada("parceria_consultar", "{}").chamada("relatorio_iniciar", "{\"modelo\":\"ATIVACAO_EVENTO\"}")
                .texto("Não consegui buscar isso agora.");

        MvcTestResult resposta = falar(rbs, conversa, "Como está o Amarelo?");

        assertThat(textos(resposta)).containsExactly("Não consegui buscar isso agora.");
        List<String> resultados = modelo.ultimoPedido().mensagens().stream().filter(m -> "tool".equals(m.papel())).map(m -> m.conteudo()).toList();
        assertThat(resultados).hasSize(2);
        assertThat(resultados.get(0)).contains("Não existe a ferramenta parceria_consultar");
        assertThat(resultados.get(1)).contains("Não existe a ferramenta relatorio_iniciar");
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_relatorio").query(Long.class).single()).isEqualTo(relatorios);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id
                WHERE m.conversa_id = :c AND t.erro IS NOT NULL
                """).param("c", conversa).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void marcadorDeParceriaNaRespostaDaRbsEhRecusadoENuncaChegaATela() {
        SessaoDeApi rbs = entrar();
        long conversa = abrir(rbs);
        modelo.texto("A marca é {{parc:patrocinador}} e tem {{cons:aplicacoes.previstas}} aplicações.").texto(NAO_ENCONTREI);

        MvcTestResult resposta = falar(rbs, conversa, "Quantas aplicações o Amarelo tem?");

        assertThat(textos(resposta)).containsExactly(NAO_ENCONTREI);
        assertThat(modelo.pedidos()).hasSize(2);
        List<MensagemDeChat> doUsuario = modelo.ultimoPedido().mensagens().stream().filter(m -> "user".equals(m.papel())).toList();
        assertThat(doUsuario.get(doUsuario.size() - 1).conteudo()).contains("recusada pelo servidor").contains("{{parc:patrocinador}}");
        assertThat(corpo(resposta)).doesNotContain("Cliente Amarelo").doesNotContain("{{");
    }

    @Test
    void numeroSemOrigemContinuaRecusadoESegundaRecusaViraAFraseFixaDoChat() {
        SessaoDeApi rbs = entrar();
        long conversa = abrir(rbs);
        modelo.texto("O prazo é 15/12/2026.").texto("O prazo é 15/12/2026.");

        MvcTestResult resposta = falar(rbs, conversa, "Qual é o prazo?");

        assertThat(textos(resposta)).containsExactly("Não consegui responder isso agora. Pergunte de outro jeito.");
        assertThat(corpo(resposta)).doesNotContain("relatório").doesNotContain("15/12/2026");
        assertThat(lerLista(resposta, "$.itens[?(@.tipo=='texto')].porModelo")).containsExactly(false);
    }

    @Test
    void aFalhaDoModeloNaConversaDaRbsNaoMencionaOBotaoDoRelatorio() {
        SessaoDeApi rbs = entrar();
        long conversa = abrir(rbs);
        modelo.falha();

        MvcTestResult resposta = falar(rbs, conversa, "Oi");

        assertThat(textos(resposta)).containsExactly("Não consegui falar com o modelo de linguagem agora. Tente de novo daqui a pouco.");
        assertThat(corpo(resposta)).doesNotContain("Montar relatório");
    }

    // ---------------------------------------------------------------------
    // Modelos em branco e contas
    // ---------------------------------------------------------------------

    @Test
    void oEsqueletoDoRelatorioDaCotaEUmaRespostaDiretaSemNovaIdaAoModelo() {
        SessaoDeApi rbs = entrar();
        long conversa = abrir(rbs);
        modelo.chamada("gerar_modelo", "{\"tipo\":\"ESQUELETO_RELATORIO_COTA\"}");

        MvcTestResult resposta = falar(rbs, conversa, "Me dá o esqueleto do relatório da cota");

        assertThat(tipos(resposta)).containsExactly("usuario", "modelo");
        assertThat(lerLista(resposta, "$.itens[?(@.tipo=='modelo')].titulo")).containsExactly("Esqueleto do relatório da cota");
        assertThat(lerLista(resposta, "$.itens[?(@.tipo=='modelo')].nomeArquivo")).containsExactly("esqueleto-do-relatorio-da-cota.md");
        assertThat(modelo.pedidos()).as("a resposta direta encerra o turno").hasSize(1);
    }

    @Test
    void aContaComOQueARbsEscreveuSaiComOComoCalculei() {
        SessaoDeApi rbs = entrar();
        long conversa = abrir(rbs);
        modelo.chamada("calcular", "{\"nome\":\"total\",\"script\":\"a * b\",\"formato\":\"moeda\",\"variaveis\":{"
                + "\"a\":{\"fonte\":\"usuario\",\"valor\":\"3\"},\"b\":{\"fonte\":\"usuario\",\"valor\":\"450\"}}}")
                .texto("Para 3 unidades a 450, o total é R$ {{calc:total}} [C1].");

        MvcTestResult resposta = falar(rbs, conversa, "Quanto são 3 geradores a 450 cada?");

        assertThat(tipos(resposta)).contains("usuario", "calculo", "texto");
        assertThat(textos(resposta)).containsExactly("Para 3 unidades a 450, o total é R$ 1.350,00 [C1].");
        assertThat(lerLista(resposta, "$.itens[?(@.tipo=='calculo')].resultado")).containsExactly("1.350,00");
    }

    // ---------------------------------------------------------------------
    // Erros do corpo, teto e turno
    // ---------------------------------------------------------------------

    @Test
    void corpoQueNaoServeDa422ComOCampoEOAtalhoNaoExisteParaARbs() {
        SessaoDeApi rbs = entrar();
        long conversa = abrir(rbs);
        String uri = "/api/atlas/conversas/" + conversa + "/mensagens";

        MvcTestResult vazio = rbs.post(uri, "{}");
        assertThat(vazio).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(vazio, "$.campos.texto")).isEqualTo("Escreva a mensagem.");
        assertThat(lerJson(rbs.post(uri, "{\"texto\":\"   \"}"), "$.campos.texto")).isEqualTo("Escreva a mensagem.");
        MvcTestResult longo = rbs.post(uri, "{\"texto\":\"" + "a".repeat(1001) + "\"}");
        assertThat(longo).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(longo, "$.campos.texto")).isEqualTo("A mensagem tem no máximo 1.000 caracteres.");
        assertThat(lerJson(rbs.post(uri, "{\"texto\":\"oi\\u0000oi\"}"), "$.campos.texto")).isEqualTo("A mensagem tem caracteres inválidos.");
        MvcTestResult atalho = rbs.post(uri, "{\"atalho\":\"MONTAR_RELATORIO\",\"modelo\":\"ATIVACAO_EVENTO\"}");
        assertThat(atalho).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(atalho, "$.campos.atalho")).isEqualTo("O Atlas da RBS só recebe mensagem de texto.");
        assertThat(lerJson(rbs.post(uri, "{\"texto\":\"oi\",\"atalho\":\"MONTAR_RELATORIO\"}"), "$.campos.atalho"))
                .isEqualTo("O Atlas da RBS só recebe mensagem de texto.");
        assertThat(rbs.post(uri, "isto nao e json")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(modelo.pedidos()).as("nenhum erro chegou ao modelo").isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_mensagem WHERE conversa_id = :c").param("c", conversa).query(Long.class).single())
                .isZero();
    }

    @Test
    void oTetoEPorPessoaDaRbsComOMesmo429EOBotaoDaMarcaNaoEntraNaConta() {
        SessaoDeApi rbs = entrar();
        long conversa = abrir(rbs);
        jdbc.sql("""
                INSERT INTO atlas_mensagem (conversa_id, ordem, papel, conteudo)
                SELECT :conversa, g, 'assistant', 'Texto do assistente.' FROM generate_series(1, :quantas) g
                """).param("conversa", conversa).param("quantas", TetoDaRbs.PADRAO).update();

        MvcTestResult atingido = rbs.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Oi\"}");

        assertThat(atingido).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(lerJson(atingido, "$.detail")).isEqualTo(TetoDaRbs.MENSAGEM);
        assertThat(modelo.pedidos()).isEmpty();
        // o traço do turno recusado fica, sem parceria
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_traco WHERE conversa_id = :c AND parceria_id IS NULL AND resultado = 'RECUSADO'")
                .param("c", conversa).query(Long.class).single()).isGreaterThanOrEqualTo(1);
        // outra pessoa da RBS tem o teto dela
        SessaoDeApi outra = SessaoDeApi.entrar(mvc, "pesquisa@rbs.example");
        long daOutra = abrir(outra);
        modelo.texto("Posso ajudar com os documentos da edição.");
        assertThat(falar(outra, daOutra, "Oi")).hasStatus(HttpStatus.OK);
    }

    @Test
    void asMensagensDoServidorNaoContamNoTetoDaRbsEOTetoVemDaPropriedade() {
        long conversa = repositorio.criarConversaDaRbs(evento2027(), usuarioDaRbs());
        jdbc.sql("""
                INSERT INTO atlas_mensagem (conversa_id, ordem, papel, conteudo, sem_modelo, fora_do_modelo)
                VALUES (:c, 1, 'assistant', 'a', TRUE, FALSE), (:c, 2, 'assistant', 'b', FALSE, FALSE), (:c, 3, 'user', 'c', FALSE, FALSE)
                """).param("c", conversa).update();

        TetoDaRbs deUm = new TetoDaRbs(repositorio, 1);
        TetoDaRbs deDois = new TetoDaRbs(repositorio, 2);

        assertThat(deDois.usadasHoje(usuarioDaRbs())).as("só a mensagem do modelo conta").isEqualTo(1);
        assertThat(deDois.exigirFolga(usuarioDaRbs())).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> deUm.exigirFolga(usuarioDaRbs())).isInstanceOf(LimiteDeUsoException.class)
                .hasMessage(TetoDaRbs.MENSAGEM);
        assertThat(deUm.teto()).isEqualTo(1);
        assertThat(new TetoDaRbs(repositorio, TetoDaRbs.PADRAO).teto()).isEqualTo(200);
    }

    long usuarioDaRbs() {
        return jdbc.sql("SELECT id FROM usuario WHERE email = :email").param("email", RBS).query(Long.class).single();
    }

    @Test
    void umTurnoEmAndamentoDa409() {
        SessaoDeApi rbs = entrar();
        long conversa = abrir(rbs);
        jdbc.sql("UPDATE atlas_conversa SET turno_desde = now() WHERE id = :id").param("id", conversa).update();

        MvcTestResult ocupada = rbs.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Oi\"}");

        assertThat(ocupada).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(ocupada, "$.detail")).isEqualTo(AtlasService.TURNO_EM_ANDAMENTO);
    }

    @Test
    void aRespostaEmFluxoFechaComOObjetoConversaDaRbs() {
        SessaoDeApi rbs = entrar();
        long conversa = abrir(rbs);
        modelo.texto("Posso ajudar com os documentos da edição.");

        MvcTestResult resposta = ApoioDoFluxo.falarEmFluxo(rbs, conversa, "Oi");

        List<ApoioDoFluxo.Evento> eventos = ApoioDoFluxo.eventos(resposta);
        ApoioDoFluxo.Evento fim = ApoioDoFluxo.fim(eventos);
        assertThat(fim.<List<String>>ler("$.itens[?(@.tipo=='texto')].texto")).containsExactly("Posso ajudar com os documentos da edição.");
        assertThat((Boolean) fim.ler("$.relatorioDisponivel")).isFalse();
        assertThat((Boolean) fim.ler("$.diagnosticoDisponivel")).isTrue();
        assertThat(ApoioDoFluxo.turnoSolto(jdbc, conversa)).isTrue();
        assertThat(ApoioDoFluxo.juntar(eventos, "texto", "delta")).isEqualTo("Posso ajudar com os documentos da edição.");
    }

    @Test
    void oRaciocinioCruNuncaSaiNaConversaDaRbs() {
        SessaoDeApi rbs = entrar();
        long conversa = abrir(rbs);
        modelo.passo(pedido -> new RespostaDoLlm("Posso ajudar com os documentos da edição.", List.of(), "RACIOCINIO-SECRETO-DA-RBS"));

        MvcTestResult resposta = falar(rbs, conversa, "Oi");

        assertThat(corpo(resposta)).doesNotContain("RACIOCINIO-SECRETO-DA-RBS");
        assertThat(corpo(rbs.get("/api/atlas/conversas/" + conversa))).doesNotContain("RACIOCINIO-SECRETO-DA-RBS");
        assertThat(ApoioDoDiagnostico.tudoOQueOTracoGuarda(jdbc, conversa)).doesNotContain("RACIOCINIO-SECRETO-DA-RBS");
    }
}
