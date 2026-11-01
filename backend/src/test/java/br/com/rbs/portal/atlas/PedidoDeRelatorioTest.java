package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.RBS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.ROXO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.VERDE;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.valores;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.exigirHistoricoValido;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.pedido;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemCartao;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.AtlasDtos.RespostaDoCartaoRequisicao;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.contexto.AtlasContextoFabrica;
import br.com.rbs.portal.atlas.ferramentas.Execucao;
import br.com.rbs.portal.atlas.ferramentas.PedidoDeRelatorio;
import br.com.rbs.portal.atlas.ferramentas.RegistroDeFerramentas;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.MensagemDeChat;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.relatorio.RelatorioService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

/**
 * [ATLAS] A ferramenta {@code pedido_de_relatorio} (M5): resposta direta com texto fixo sem algarismo, {@code relatorioOficial} só
 * quando a RBS disponibiliza uma versão (pelo mesmo critério da rota 35: nunca o rascunho nem o validado, e nunca o de outra
 * parceria), os modelos que a parceria pode pedir (a Pesquisa só para a Master), argumento desconhecido como erro, e o loop: uma
 * ida só ao modelo, a mesma rodada com outra ferramenta respondida nas duas, e o cartão da mesma rodada mandando. Cada teste é
 * desfeito no fim (@Transactional).
 */
@TesteComLlmRoteirizado
@Transactional
class PedidoDeRelatorioTest {

    static final Map<String, String> BRIEFING = Map.of("tipo", "BRIEFING_CONTEUDO", "titulo", "Briefing de conteúdo");
    static final Map<String, String> PESQUISA = Map.of("tipo", "PERGUNTAS_PESQUISA", "titulo", "Perguntas da pesquisa");
    static final Map<String, String> PLANO = Map.of("tipo", "PLANO_OPERACIONAL_ATIVACAO", "titulo", "Plano operacional da ativação");

    @Autowired
    RegistroDeFerramentas registro;

    @Autowired
    AtlasContextoFabrica contextos;

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    RelatorioService relatorios;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void zerarORoteiro() {
        modelo.limpar();
    }

    AtlasContexto contexto(String email, String cliente) {
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, cliente);
        var pessoa = usuario(jdbc, email);
        long conversa = atlas.abrirConversa(pessoa, parceria, null, requisicao()).conversaId();
        return contextos.para(pessoa, parceria, conversa, requisicao());
    }

    FontesDoTurno turno() {
        return new FontesDoTurno("Oi", 2027, LocalDate.of(2026, 11, 1));
    }

    Execucao pedir(AtlasContexto contexto, String argumentos) {
        var preparo = registro.preparar(contexto, turno(), PedidoDeRelatorio.NOME, argumentos);
        return registro.executar(contexto, turno(), preparo, "call_x");
    }

    static Map<String, Object> json(String texto) {
        return AtlasJson.ler(texto, new TypeReference<Map<String, Object>>() {
        });
    }

    Map<String, Object> resposta(AtlasContexto contexto) {
        return json(pedir(contexto, "{}").resultadoJson());
    }

    long relatorioDaAmostra(long parceriaId) {
        return jdbc.sql("SELECT id FROM relatorio WHERE parceria_id = :p AND versao = 1").param("p", parceriaId).query(Long.class).single();
    }

    // ---------------------------------------------------------------------
    // A resposta
    // ---------------------------------------------------------------------

    @Test
    void respondeDiretoComTextoFixoSemAlgarismoEOsModelosDaMaster() {
        Execucao execucao = pedir(contexto(AMARELO, "Cliente Amarelo"), "{}");

        assertThat(execucao.direta()).isTrue();
        assertThat(execucao.pausa()).isFalse();
        assertThat(execucao.pronto()).isFalse();
        Map<String, Object> resultado = json(execucao.resultadoJson());
        assertThat(resultado.keySet()).containsExactly("tipo", "texto", "relatorioOficial", "modelos");
        assertThat(resultado).containsEntry("tipo", "pedido_relatorio").containsEntry("relatorioOficial", false)
                .containsEntry("modelos", List.of(BRIEFING, PESQUISA, PLANO));
        String texto = (String) resultado.get("texto");
        assertThat(texto).isEqualTo(PedidoDeRelatorio.texto(false)).doesNotContainPattern("[0-9]");
        assertThat(texto).contains("equipe da RBS", "não pelo Atlas", "assim que a RBS o disponibilizar", "modelos em branco");
    }

    @Test
    void osDoisTextosFixosNaoTemAlgarismoNemChaveNemNomeDeFerramenta() {
        for (boolean oficial : List.of(false, true)) {
            assertThat(PedidoDeRelatorio.texto(oficial)).as("oficial=" + oficial).doesNotContainPattern("[0-9]").doesNotContain("{{")
                    .doesNotContain("_").doesNotContain("pedido").doesNotContain("gerar");
        }
    }

    @Test
    void argumentosVaziosValemEQualquerArgumentoEErro() {
        AtlasContexto amarelo = contexto(AMARELO, "Cliente Amarelo");

        for (String vazio : List.of("", "   ", "{}")) {
            assertThat(pedir(amarelo, vazio).direta()).as(vazio).isTrue();
        }
        for (String lixo : List.of("{\"parceria_id\":3}", "{\"tipo\":\"BRIEFING_CONTEUDO\"}", "{\"incluir\":true}")) {
            assertThatThrownBy(() -> pedir(amarelo, lixo)).as(lixo).isInstanceOf(DadosInvalidosException.class);
        }
        assertThatThrownBy(() -> pedir(amarelo, "{\"parceria_id\":3}"))
                .satisfies(erro -> assertThat(((DadosInvalidosException) erro).campos()).containsKey("parceria_id"));
    }

    @Test
    void relatorioOficialSoQuandoARbsDisponibilizaEODeOutraParceriaNaoVale() {
        AtlasContexto amarelo = contexto(AMARELO, "Cliente Amarelo");
        AtlasContexto roxo = contexto(ROXO, "Cliente Roxo");
        long relatorio = relatorioDaAmostra(amarelo.parceriaId());

        // a amostra só tem o rascunho, e o validado ainda não é do patrocinador
        assertThat(resposta(amarelo)).containsEntry("relatorioOficial", false);
        relatorios.mudarStatus(usuario(jdbc, RBS), relatorio, "VALIDADO", requisicao());
        assertThat(resposta(amarelo)).containsEntry("relatorioOficial", false);

        relatorios.mudarStatus(usuario(jdbc, RBS), relatorio, "DISPONIBILIZADO", requisicao());
        Map<String, Object> disponibilizado = resposta(amarelo);
        assertThat(disponibilizado).containsEntry("relatorioOficial", true);
        assertThat((String) disponibilizado.get("texto")).isEqualTo(PedidoDeRelatorio.texto(true)).contains("já está disponível")
                .doesNotContain("assim que a RBS o disponibilizar").doesNotContainPattern("[0-9]");
        assertThat(resposta(roxo)).as("o relatório do Amarelo não vale para o Roxo").containsEntry("relatorioOficial", false);
    }

    @Test
    void osModelosOferecidosDependemDaAbaPesquisa() {
        assertThat(resposta(contexto(AMARELO, "Cliente Amarelo"))).containsEntry("modelos", List.of(BRIEFING, PESQUISA, PLANO));
        assertThat(resposta(contexto(ROXO, "Cliente Roxo"))).containsEntry("modelos", List.of(BRIEFING, PLANO));
        assertThat(resposta(contexto(VERDE, "Cliente Verde"))).containsEntry("modelos", List.of(BRIEFING, PLANO));
    }

    // ---------------------------------------------------------------------
    // No loop
    // ---------------------------------------------------------------------

    long abrir() {
        return atlas.abrirConversa(usuario(jdbc, AMARELO), SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"), null, requisicao())
                .conversaId();
    }

    ConversaDto falar(long conversa, String texto) {
        return atlas.enviarMensagem(usuario(jdbc, AMARELO), conversa, new MensagemRequisicao(texto, null, null), requisicao());
    }

    List<String> papeis(long conversa) {
        return jdbc.sql("SELECT papel FROM atlas_mensagem WHERE conversa_id = :c ORDER BY ordem").param("c", conversa).query(String.class).list();
    }

    List<String> respostas(long conversa) {
        return jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'tool' ORDER BY ordem")
                .param("c", conversa).query(String.class).list();
    }

    @Test
    void oTurnoFazUmaIdaSoAoModeloEOHistoricoSegueValidoNaMensagemSeguinte() {
        long conversa = abrir();
        modelo.chamada(PedidoDeRelatorio.NOME, "{}");   // o roteiro só tem este passo: uma segunda ida derruba o teste

        falar(conversa, "Quero o relatório da minha parceria.");

        assertThat(modelo.pedidos()).hasSize(1);
        assertThat(modelo.sobraDoRoteiro()).isZero();
        assertThat(papeis(conversa)).containsExactly("user", "assistant", "tool");
        assertThat(json(respostas(conversa).get(0))).containsEntry("tipo", "pedido_relatorio");

        modelo.texto("Posso ajudar com mais alguma coisa.");
        falar(conversa, "Obrigado.");
        exigirHistoricoValido(modelo.ultimoPedido());
        assertThat(modelo.ultimoPedido().mensagens().stream().map(MensagemDeChat::papel).toList())
                .containsExactly("system", "user", "assistant", "tool", "user");
        assertThat(modelo.ultimoPedido().mensagens().get(3).conteudo()).contains("\"relatorioOficial\":false").doesNotContain("não chegou a responder");
    }

    @Test
    void naMesmaRodadaComConsultaAsDuasChamadasTemResposta() {
        long conversa = abrir();
        modelo.chamadas(null, null, pedido(LoopDoAgenteTest.CONSULTAR, "{}"), pedido(PedidoDeRelatorio.NOME, "{}")).texto("Aqui estão os números.");

        falar(conversa, "Quero o relatório e os números.");

        assertThat(modelo.pedidos()).as("a rodada tinha a direta e uma consulta: o turno segue com uma nova ida").hasSize(2);
        assertThat(papeis(conversa)).containsExactly("user", "assistant", "tool", "tool", "assistant");
        assertThat(respostas(conversa).get(0)).contains("\"dados\"");
        assertThat(json(respostas(conversa).get(1))).containsEntry("tipo", "pedido_relatorio");
        // o modelo sabe que o servidor já mostrou o pedido, e as diretas saem da oferta nessa ida
        MensagemDeChat doPedido = modelo.pedidos().get(1).mensagens().stream()
                .filter(m -> "tool".equals(m.papel()) && PedidoDeRelatorio.NOME.equals(m.ferramenta())).findFirst().orElseThrow();
        assertThat(doPedido.conteudo()).contains("O servidor já mostrou esta resposta à pessoa", "\"relatorioOficial\":false");
        assertThat(ProvedorRoteirizado.nomesDasFerramentas(modelo.pedidos().get(1))).doesNotContain(PedidoDeRelatorio.NOME, "gerar_modelo");
    }

    @Test
    void comOCartaoNaMesmaRodadaOCartaoMandaEOHistoricoSegueValido() {
        long conversa = abrir();
        modelo.chamada(LoopDoAgenteTest.INICIAR, LoopDoAgenteTest.ARGS_INICIAR).texto("Vamos montar o relatório.");
        falar(conversa, "Quero montar o relatório da ativação.");
        modelo.limpar();
        modelo.chamadas(null, null, pedido(PedidoDeRelatorio.NOME, "{}"), pedido(LoopDoAgenteTest.SOLICITAR, LoopDoAgenteTest.ARGS_CARTAO));

        ConversaDto turno = falar(conversa, "Quero o relatório e preciso informar uns números.");

        assertThat(modelo.pedidos()).hasSize(1);
        ItemCartao cartao = turno.itens().stream().filter(ItemCartao.class::isInstance).map(ItemCartao.class::cast).findFirst().orElseThrow();
        assertThat(cartao.status()).isEqualTo("PENDENTE");
        assertThat(json(respostas(conversa).get(1))).containsEntry("tipo", "pedido_relatorio");   // a 0 é a do relatorio_iniciar

        modelo.texto("Tudo bem, sigo sem esses números.");
        atlas.responderCartao(usuario(jdbc, AMARELO), cartao.solicitacaoId(), new RespostaDoCartaoRequisicao("decline", valores()), requisicao());
        exigirHistoricoValido(modelo.ultimoPedido());
        assertThat(modelo.ultimoPedido().mensagens().stream().filter(m -> "tool".equals(m.papel())).map(MensagemDeChat::conteudo))
                .noneMatch(conteudo -> conteudo.contains("não chegou a responder")).anyMatch(conteudo -> conteudo.contains("pedido_relatorio"));
    }
}
