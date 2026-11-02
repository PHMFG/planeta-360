package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.contexto.AtlasContextoFabrica;
import br.com.rbs.portal.atlas.ferramentas.Execucao;
import br.com.rbs.portal.atlas.ferramentas.RegistroDeFerramentas;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.DefinicaoDeFerramenta;
import br.com.rbs.portal.comum.ConflitoException;
import java.time.LocalDate;
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
 * [ATLAS] A rota 65, {@code POST /api/atlas/relatorios/{id}/descartar} (M6), como o front a usa: login de verdade, sessão e token CSRF,
 * com o relatório que o botão monta (o plano B, sem modelo). O relatório {@code MONTANDO} ou {@code AGUARDANDO_RESPOSTA} vira
 * {@code DESCARTADO} e a resposta é o corpo da rota 47; o cartão pendente vira {@code CANCELADA}, a chamada que ele deixou aberta
 * recebe {@code cancel} e o histórico ganha {@code ALTERACAO_STATUS} em nome de quem descartou; descartar de novo não muda nada;
 * pronto dá 409; e cada erro de acesso (401, 403 sem CSRF, 403 da RBS, 404 de outra marca, de relatório que não existe e de outra
 * pessoa da mesma parceria, que não é a dona do relatório, 403 de aba fechada) e o turno em andamento (409). Com o modelo de linguagem, a conversa que continua depois do descarte está em
 * {@link DescarteDoRelatorioComLlmTest}; com o relatório desligado, em {@link AtlasSoChatTest}. Cada teste é desfeito no fim
 * (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class DescarteDoRelatorioTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String AGENCIA = "atendimento@agencia-x.example";
    private static final String ROXO = "marketing@roxo.example";
    private static final String BOTAO = "{\"atalho\":\"MONTAR_RELATORIO\",\"modelo\":\"ATIVACAO_EVENTO\"}";
    private static final String ACCEPT_COMPLETO = "{\"acao\":\"accept\",\"valores\":[{\"chave\":\"ativacao.visitantes_total\",\"valor\":1200},"
            + "{\"chave\":\"ativacao.cadastros_consentidos\",\"valor\":1072}]}";
    private static final String JA_PRONTO = "Este relatório já está pronto e não pode ser descartado.";
    private static final String NAO_ENCONTRADO = "Não encontramos este relatório.";
    private static final String SO_PATROCINADOR = "Só o Admin Patrocinador da parceria conversa com o Atlas.";
    private static final String TURNO_EM_ANDAMENTO = "O Atlas ainda está respondendo nesta conversa. Espere a resposta.";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AtlasContextoFabrica contextos;

    @Autowired
    RegistroDeFerramentas registro;

    @Autowired
    AtlasRepositorio repositorio;

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    SessaoDeApi entrar(String email) {
        return SessaoDeApi.entrar(mvc, email);
    }

    long numero(MvcTestResult resposta, String caminho) {
        return ((Number) lerJson(resposta, caminho)).longValue();
    }

    long contar(String sql, Object... parametros) {
        var consulta = jdbc.sql(sql);
        for (Object parametro : parametros) {
            consulta = consulta.param(parametro);
        }
        return consulta.query(Long.class).single();
    }

    /** O botão abre a conversa e o cartão; o relatório fica AGUARDANDO_RESPOSTA. Devolve a resposta (201). */
    MvcTestResult abrirPeloBotao(SessaoDeApi pessoa) {
        MvcTestResult resposta = pessoa.post("/api/parcerias/" + amarelo() + "/atlas/conversas", BOTAO);
        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        return resposta;
    }

    long relatorioDaConversa(long conversa) {
        return contar("SELECT id FROM atlas_relatorio WHERE conversa_id = ? ORDER BY id DESC LIMIT 1", conversa);
    }

    String statusDoRelatorio(long relatorio) {
        return jdbc.sql("SELECT status FROM atlas_relatorio WHERE id = :id").param("id", relatorio).query(String.class).single();
    }

    MvcTestResult descartar(SessaoDeApi pessoa, Object relatorio) {
        return pessoa.post("/api/atlas/relatorios/" + relatorio + "/descartar", null);
    }

    /** O botão, o cartão respondido com tudo: o relatório fica PRONTO. Devolve o id do relatório. */
    long montarRelatorioPronto(SessaoDeApi pessoa) {
        MvcTestResult aberta = abrirPeloBotao(pessoa);
        MvcTestResult pronta = pessoa.post("/api/atlas/solicitacoes/" + numero(aberta, "$.itens[2].solicitacaoId") + "/respostas",
                ACCEPT_COMPLETO);
        assertThat(pronta).hasStatus(HttpStatus.OK);
        return relatorioDaConversa(numero(aberta, "$.conversaId"));
    }

    /** As linhas ALTERACAO_STATUS do relatório no histórico. */
    List<Map<String, Object>> historicoDoDescarte(long relatorio) {
        return jdbc.sql("""
                        SELECT usuario_id, parceria_id, valor_anterior, valor_novo, detalhe
                        FROM historico WHERE acao = 'ALTERACAO_STATUS' AND entidade = 'atlas_relatorio' AND entidade_id = :id ORDER BY id
                        """).param("id", relatorio).query().listOfRows();
    }

    /** Os nomes das ferramentas que o modelo receberia agora nesta conversa. */
    List<String> ferramentasOferecidas(long conversa) {
        return registro.oferecidas(repositorio.relatorioAtivoDaConversa(conversa), true).stream().map(DefinicaoDeFerramenta::nome).toList();
    }

    long idDoUsuario(String email) {
        return contar("SELECT id FROM usuario WHERE email = ?", email);
    }

    // ---------------------------------------------------------------------
    // O que o descarte faz
    // ---------------------------------------------------------------------

    @Test
    void descartaORelatorioComOCartaoAbertoEDevolveOMesmoCorpoDaRota47() {
        SessaoDeApi pessoa = entrar(AMARELO);
        MvcTestResult aberta = abrirPeloBotao(pessoa);
        long conversa = numero(aberta, "$.conversaId");
        long cartao = numero(aberta, "$.itens[2].solicitacaoId");
        long relatorio = relatorioDaConversa(conversa);
        assertThat(statusDoRelatorio(relatorio)).isEqualTo("AGUARDANDO_RESPOSTA");
        String chamada = jdbc.sql("SELECT tool_call_id FROM atlas_solicitacao WHERE id = :id").param("id", cartao).query(String.class).single();

        MvcTestResult resposta = descartar(pessoa, relatorio);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(resposta.getResponse().getContentType()).startsWith("application/json");
        assertThat(lerJson(resposta, "$.status")).isEqualTo("DESCARTADO");
        assertThat(numero(resposta, "$.id")).isEqualTo(relatorio);
        assertThat(lerJson(resposta, "$.modelo")).isEqualTo("ATIVACAO_EVENTO");
        assertThat(lerJson(resposta, "$.selo")).isEqualTo("Documento de trabalho da marca, montado pelo Atlas. Não validado pela RBS.");
        assertThat(lerLista(resposta, "$.secoes")).hasSize(6);
        // o mesmo corpo da rota 47, byte a byte
        assertThat(corpo(resposta)).isEqualTo(corpo(pessoa.get("/api/atlas/relatorios/" + relatorio)));
        // o relatório, o cartão e a chamada que o cartão deixou aberta
        assertThat(statusDoRelatorio(relatorio)).isEqualTo("DESCARTADO");
        assertThat(jdbc.sql("SELECT status FROM atlas_solicitacao WHERE id = :id").param("id", cartao).query(String.class).single())
                .isEqualTo("CANCELADA");
        assertThat(contar("SELECT count(*) FROM atlas_solicitacao WHERE id = ? AND respondida_em IS NOT NULL", cartao)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id WHERE m.conversa_id = ? "
                + "AND t.ferramenta = 'relatorio_solicitar_campos' AND t.resultado IS NULL", conversa)).isZero();
        String resultado = jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'tool' AND tool_call_id = :t")
                .param("c", conversa).param("t", chamada).query(String.class).single();
        assertThat(AtlasJson.ler(resultado, Map.class)).containsEntry("acao", "cancel");
        // o turno foi solto
        assertThat(contar("SELECT count(*) FROM atlas_conversa WHERE id = ? AND turno_desde IS NULL", conversa)).isEqualTo(1);
        // o histórico: uma linha, em nome de quem descartou
        List<Map<String, Object>> historico = historicoDoDescarte(relatorio);
        assertThat(historico).hasSize(1);
        assertThat(historico.get(0)).containsEntry("valor_anterior", "AGUARDANDO_RESPOSTA").containsEntry("valor_novo", "DESCARTADO")
                .containsEntry("usuario_id", idDoUsuario(AMARELO)).containsEntry("parceria_id", amarelo());
        assertThat(String.valueOf(historico.get(0).get("detalhe"))).contains("Ativação no evento").contains("descartado");
        // a conversa mostra o cartão fechado, e a lista (rota 43) e a rota 48 o relatório descartado
        MvcTestResult lida = pessoa.get("/api/atlas/conversas/" + conversa);
        assertThat(lerLista(lida, "$.itens[?(@.tipo=='cartao')].status")).containsExactly("CANCELADA");
        assertThat(lerLista(pessoa.get("/api/parcerias/" + amarelo() + "/atlas/relatorios"), "$[*].status")).contains("DESCARTADO");
        assertThat(lerLista(pessoa.get("/api/parcerias/" + amarelo() + "/atlas/conversas"), "$.conversas[?(@.id==" + conversa + ")].relatorio.status"))
                .containsExactly("DESCARTADO");
    }

    @Test
    void descartaORelatorioEmMontagemDepoisDeFecharOCartao() {
        SessaoDeApi pessoa = entrar(AMARELO);
        MvcTestResult aberta = abrirPeloBotao(pessoa);
        long relatorio = relatorioDaConversa(numero(aberta, "$.conversaId"));
        // cancelar o cartão mantém o relatório em montagem
        MvcTestResult cancelado = pessoa.post("/api/atlas/solicitacoes/" + numero(aberta, "$.itens[2].solicitacaoId") + "/respostas",
                "{\"acao\":\"cancel\"}");
        assertThat(cancelado).hasStatus(HttpStatus.OK);
        assertThat(statusDoRelatorio(relatorio)).isEqualTo("MONTANDO");

        MvcTestResult resposta = descartar(pessoa, relatorio);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.status")).isEqualTo("DESCARTADO");
        assertThat(statusDoRelatorio(relatorio)).isEqualTo("DESCARTADO");
        assertThat(historicoDoDescarte(relatorio)).hasSize(1).first().satisfies(linha -> assertThat(linha)
                .containsEntry("valor_anterior", "MONTANDO").containsEntry("valor_novo", "DESCARTADO"));
        assertThat(contar("SELECT count(*) FROM atlas_solicitacao WHERE relatorio_id = ? AND status = 'PENDENTE'", relatorio)).isZero();
    }

    @Test
    void descartarDeNovoE200SemMudarNadaENemUmaLinhaNoHistorico() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long relatorio = relatorioDaConversa(numero(abrirPeloBotao(pessoa), "$.conversaId"));
        MvcTestResult primeira = descartar(pessoa, relatorio);
        long mensagens = contar("SELECT count(*) FROM atlas_mensagem m JOIN atlas_relatorio r ON r.conversa_id = m.conversa_id WHERE r.id = ?",
                relatorio);

        MvcTestResult segunda = descartar(pessoa, relatorio);

        assertThat(primeira).hasStatus(HttpStatus.OK);
        assertThat(segunda).hasStatus(HttpStatus.OK);
        assertThat(corpo(segunda)).isEqualTo(corpo(primeira));
        assertThat(historicoDoDescarte(relatorio)).hasSize(1);
        assertThat(contar("SELECT count(*) FROM atlas_mensagem m JOIN atlas_relatorio r ON r.conversa_id = m.conversa_id WHERE r.id = ?",
                relatorio)).isEqualTo(mensagens);
        // outra pessoa da parceria não é a dona: continua 404, também com o relatório já descartado
        assertThat(descartar(entrar(AGENCIA), relatorio)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(historicoDoDescarte(relatorio)).hasSize(1);
    }

    @Test
    void relatorioProntoDa409ENaoMudaNada() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long relatorio = montarRelatorioPronto(pessoa);
        assertThat(statusDoRelatorio(relatorio)).isEqualTo("PRONTO");
        String antes = corpo(pessoa.get("/api/atlas/relatorios/" + relatorio));

        MvcTestResult resposta = descartar(pessoa, relatorio);

        assertThat(resposta).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo(JA_PRONTO);
        assertThat(lerJson(resposta, "$.status")).isEqualTo(409);
        assertThat(statusDoRelatorio(relatorio)).isEqualTo("PRONTO");
        assertThat(historicoDoDescarte(relatorio)).isEmpty();
        assertThat(corpo(pessoa.get("/api/atlas/relatorios/" + relatorio))).isEqualTo(antes);
        // com o turno em andamento o pronto continua dando a frase do pronto
        jdbc.sql("UPDATE atlas_conversa SET turno_desde = now() WHERE id = (SELECT conversa_id FROM atlas_relatorio WHERE id = :r)")
                .param("r", relatorio).update();
        assertThat(lerJson(descartar(pessoa, relatorio), "$.detail")).isEqualTo(JA_PRONTO);
    }

    @Test
    void soODonoDoRelatorioDescartaEOutroDaMesmaParceriaRecebe404ComAFraseDasRotas45E46() {
        // o relatório é de quem o criou: a rota 65 seleciona por id e dono, como as rotas 45 e 46 (conversa e cartão)
        SessaoDeApi dono = entrar(AMARELO);
        MvcTestResult aberta = abrirPeloBotao(dono);
        long conversa = numero(aberta, "$.conversaId");
        long cartao = numero(aberta, "$.itens[2].solicitacaoId");
        long relatorio = relatorioDaConversa(conversa);
        SessaoDeApi agencia = entrar(AGENCIA);

        MvcTestResult resposta = descartar(agencia, relatorio);

        assertThat(resposta).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo(NAO_ENCONTRADO);
        // as rotas 45 e 46 dão 404 a esta mesma pessoa, e a rota 47 mostra o relatório (ela é da parceria)
        assertThat(agencia.get("/api/atlas/conversas/" + conversa)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(agencia.post("/api/atlas/solicitacoes/" + cartao + "/respostas", "{\"acao\":\"cancel\"}"))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(agencia.get("/api/atlas/relatorios/" + relatorio)).hasStatus(HttpStatus.OK);
        // nada mudou: o relatório, o cartão e o histórico, e a conversa não ficou presa
        assertThat(statusDoRelatorio(relatorio)).isEqualTo("AGUARDANDO_RESPOSTA");
        assertThat(contar("SELECT count(*) FROM atlas_solicitacao WHERE id = ? AND status = 'PENDENTE'", cartao)).isEqualTo(1);
        assertThat(historicoDoDescarte(relatorio)).isEmpty();
        assertThat(contar("SELECT count(*) FROM atlas_conversa WHERE id = ? AND turno_desde IS NULL", conversa)).isEqualTo(1);
        // o dono não fica com o cartão travado: responde sem o 409 de "já respondido"
        assertThat(dono.post("/api/atlas/solicitacoes/" + cartao + "/respostas", ACCEPT_COMPLETO)).hasStatus(HttpStatus.OK);
        assertThat(statusDoRelatorio(relatorio)).isEqualTo("PRONTO");
    }

    @Test
    void outroDaParceriaRecebe404MesmoComAAbaFechadaEODonoRecebe403() {
        SessaoDeApi dono = entrar(AMARELO);
        long relatorio = relatorioDaConversa(numero(abrirPeloBotao(dono), "$.conversaId"));
        jdbc.sql("UPDATE parceria_aba SET liberada = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'APLICACOES')")
                .param("p", amarelo()).update();

        // a ordem é a das rotas 45 e 46: a parceria, o dono e só então a aba
        assertThat(descartar(entrar(AGENCIA), relatorio)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(descartar(dono, relatorio)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(statusDoRelatorio(relatorio)).isEqualTo("AGUARDANDO_RESPOSTA");
    }

    @Test
    void depoisDeDescartarAConversaFicaLivreEORelatorioIniciarFuncionaDeNovo() {
        SessaoDeApi dono = entrar(AMARELO);
        long conversa = numero(abrirPeloBotao(dono), "$.conversaId");
        long primeiro = relatorioDaConversa(conversa);
        var pessoa = usuario(jdbc, AMARELO);
        AtlasContexto contexto = contextos.para(pessoa, amarelo(), conversa, requisicao());
        FontesDoTurno turno = new FontesDoTurno("Oi", 2027, LocalDate.of(2026, 11, 1));
        String iniciar = "{\"modelo\":\"ATIVACAO_EVENTO\",\"objetivo\":\"Relatório da ativação\"}";
        // com o relatório em andamento o modelo nem recebe relatorio_iniciar, e chamá-lo à força dá o 409
        assertThat(ferramentasOferecidas(conversa)).doesNotContain("relatorio_iniciar");
        assertThatThrownBy(() -> registro.executar(contexto, turno, registro.preparar(contexto, turno, "relatorio_iniciar", iniciar), "call_x"))
                .isInstanceOf(ConflitoException.class).hasMessageContaining("Já existe um relatório sendo montado");

        assertThat(descartar(dono, primeiro)).hasStatus(HttpStatus.OK);

        // a conversa não espera mais o cartão: relatorio_iniciar volta à oferta e abre outro relatório
        assertThat(ferramentasOferecidas(conversa)).contains("relatorio_iniciar").doesNotContain("relatorio_editar", "relatorio_finalizar");
        Execucao feita = registro.executar(contexto, turno, registro.preparar(contexto, turno, "relatorio_iniciar", iniciar), "call_y");
        long segundo = ((Number) AtlasJson.ler(feita.resultadoJson(), Map.class).get("relatorioId")).longValue();
        assertThat(segundo).isGreaterThan(primeiro);
        assertThat(statusDoRelatorio(primeiro)).isEqualTo("DESCARTADO");
        assertThat(statusDoRelatorio(segundo)).isEqualTo("MONTANDO");
        assertThat(contar("SELECT count(*) FROM atlas_relatorio WHERE conversa_id = ?", conversa)).isEqualTo(2);
        assertThat(ferramentasOferecidas(conversa)).doesNotContain("relatorio_iniciar").contains("relatorio_editar", "relatorio_finalizar");
    }

    @Test
    void depoisDeDescartarOBotaoComecaOutroRelatorioNaMesmaConversa() {
        SessaoDeApi pessoa = entrar(AMARELO);
        MvcTestResult aberta = abrirPeloBotao(pessoa);
        long conversa = numero(aberta, "$.conversaId");
        long primeiro = relatorioDaConversa(conversa);
        assertThat(descartar(pessoa, primeiro)).hasStatus(HttpStatus.OK);

        MvcTestResult outra = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", BOTAO);

        assertThat(outra).hasStatus(HttpStatus.OK);
        assertThat(lerLista(outra, "$.itens[?(@.tipo=='cartao')].status")).containsExactly("PENDENTE");
        long segundo = relatorioDaConversa(conversa);
        assertThat(segundo).isGreaterThan(primeiro);
        assertThat(statusDoRelatorio(primeiro)).isEqualTo("DESCARTADO");
        assertThat(statusDoRelatorio(segundo)).isEqualTo("AGUARDANDO_RESPOSTA");
        assertThat(contar("SELECT count(*) FROM atlas_relatorio WHERE conversa_id = ?", conversa)).isEqualTo(2);
    }

    // ---------------------------------------------------------------------
    // Acesso
    // ---------------------------------------------------------------------

    @Test
    void semSessaoDa401() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long relatorio = relatorioDaConversa(numero(abrirPeloBotao(pessoa), "$.conversaId"));

        MvcTestResult resposta = descartar(SessaoDeApi.anonima(mvc), relatorio);

        assertThat(resposta).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(statusDoRelatorio(relatorio)).isEqualTo("AGUARDANDO_RESPOSTA");
    }

    @Test
    void semTokenCsrfDa403ENadaMuda() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long relatorio = relatorioDaConversa(numero(abrirPeloBotao(pessoa), "$.conversaId"));

        MvcTestResult resposta = pessoa.postSemCsrf("/api/atlas/relatorios/" + relatorio + "/descartar", null);

        assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(statusDoRelatorio(relatorio)).isEqualTo("AGUARDANDO_RESPOSTA");
    }

    @Test
    void adminRbsRecebe403AntesDeQualquerOutraConferencia() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long relatorio = relatorioDaConversa(numero(abrirPeloBotao(pessoa), "$.conversaId"));
        SessaoDeApi rbs = entrar(RBS);

        for (Object id : List.of(relatorio, 999_999, "abc")) {
            MvcTestResult resposta = descartar(rbs, id);
            assertThat(resposta).as("id " + id).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(resposta, "$.detail")).as("id " + id).isEqualTo(SO_PATROCINADOR);
        }
        assertThat(statusDoRelatorio(relatorio)).isEqualTo("AGUARDANDO_RESPOSTA");
    }

    @Test
    void marcaDeOutraParceriaEInexistenteDao404ComAMesmaFrase() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long relatorio = relatorioDaConversa(numero(abrirPeloBotao(pessoa), "$.conversaId"));
        SessaoDeApi roxo = entrar(ROXO);

        for (Object id : List.of(relatorio, 999_999, "abc", 0, -1)) {
            MvcTestResult resposta = descartar(roxo, id);
            assertThat(resposta).as("id " + id).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(lerJson(resposta, "$.detail")).as("id " + id).isEqualTo(NAO_ENCONTRADO);
        }
        assertThat(statusDoRelatorio(relatorio)).isEqualTo("AGUARDANDO_RESPOSTA");
        assertThat(historicoDoDescarte(relatorio)).isEmpty();
    }

    @Test
    void abaDeAplicacoesFechadaDa403ParaAMarca() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long relatorio = relatorioDaConversa(numero(abrirPeloBotao(pessoa), "$.conversaId"));
        jdbc.sql("UPDATE parceria_aba SET liberada = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'APLICACOES')")
                .param("p", amarelo()).update();

        MvcTestResult resposta = descartar(pessoa, relatorio);

        assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("Esta aba ainda não foi liberada para a sua parceria.");
        assertThat(statusDoRelatorio(relatorio)).isEqualTo("AGUARDANDO_RESPOSTA");
    }

    @Test
    void turnoEmAndamentoNaConversaDa409ENadaMudaENemSoltaOTurnoDeOutro() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = numero(abrirPeloBotao(pessoa), "$.conversaId");
        long relatorio = relatorioDaConversa(conversa);
        jdbc.sql("UPDATE atlas_conversa SET turno_desde = now() WHERE id = :c").param("c", conversa).update();

        MvcTestResult resposta = descartar(pessoa, relatorio);

        assertThat(resposta).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo(TURNO_EM_ANDAMENTO);
        assertThat(statusDoRelatorio(relatorio)).isEqualTo("AGUARDANDO_RESPOSTA");
        assertThat(contar("SELECT count(*) FROM atlas_solicitacao WHERE relatorio_id = ? AND status = 'PENDENTE'", relatorio)).isEqualTo(1);
        assertThat(historicoDoDescarte(relatorio)).isEmpty();
        // o turno que estava em andamento continua: a rota não o soltou
        assertThat(contar("SELECT count(*) FROM atlas_conversa WHERE id = ? AND turno_desde IS NOT NULL", conversa)).isEqualTo(1);
    }
}
