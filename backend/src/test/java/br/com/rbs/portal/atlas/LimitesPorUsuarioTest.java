package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.limites.LimitesPorUsuario;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.MensagemDeChat;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] Os limites por usuário (M8, problema 15 do quadro): 20 mensagens de texto por hora (rota 44) e 3 relatórios por dia que o
 * modelo monta ({@code relatorio_iniciar}), com os valores padrão num contexto próprio (o contexto normal da suíte os deixa altos) e um
 * {@code Clock} que o teste adianta. Confere o que o contrato da {@code api.md} diz: o 429 com quanto falta, só na mensagem de texto;
 * o botão e a resposta do cartão (rota 46) acima do limite; o erro de ferramenta no 4º relatório; a ordem das conferências (403, 404,
 * 422 e o teto diário antes; o 409 depois, e a tentativa recusada não gasta ficha).
 */
@TesteComLlmRoteirizado
@Import(RelogioDeTesteConfig.class)
@TestPropertySource(properties = {"atlas.limites.mensagens-por-hora=20", "atlas.limites.relatorios-por-dia=3"})
@Transactional
class LimitesPorUsuarioTest {

    private static final String AMARELO = "marketing@amarelo.example";
    private static final String ROXO = "marketing@roxo.example";
    private static final String TEXTO = "{\"texto\":\"Oi, Atlas.\"}";
    private static final String RESPOSTA = "Posso ajudar.";
    private static final String INICIAR = "{\"modelo\":\"ATIVACAO_EVENTO\"}";
    private static final String ACEITAR = "{\"acao\":\"accept\",\"valores\":[{\"chave\":\"ativacao.visitantes_total\",\"valor\":1200},"
            + "{\"chave\":\"ativacao.cadastros_consentidos\",\"valor\":1072}]}";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    RelogioDeTeste relogio;

    @Autowired
    LimitesPorUsuario limites;

    @BeforeEach
    void comOsBaldesCheios() {
        modelo.limpar();
        // O balde fica na memória e o rollback não o zera: dois dias parados enchem todos de novo.
        relogio.avancar(Duration.ofDays(3));
    }

    SessaoDeApi entrar(String email) {
        return SessaoDeApi.entrar(mvc, email);
    }

    long parceria(String nome) {
        return SessaoDeApi.parceriaDe2027(jdbc, nome);
    }

    long conversaDe(SessaoDeApi pessoa, String parceria) {
        MvcTestResult aberta = pessoa.post("/api/parcerias/" + parceria(parceria) + "/atlas/conversas", "{}");
        assertThat(aberta).hasStatus(HttpStatus.CREATED);
        return ((Number) lerJson(aberta, "$.conversaId")).longValue();
    }

    String mensagens(long conversa) {
        return "/api/atlas/conversas/" + conversa + "/mensagens";
    }

    /** {@code quantas} mensagens de texto que o modelo responde, cada uma com 200. */
    void mandar(SessaoDeApi pessoa, long conversa, int quantas) {
        for (int i = 0; i < quantas; i++) {
            modelo.texto(RESPOSTA);
            assertThat(pessoa.post(mensagens(conversa), TEXTO)).as("mensagem %d de %d", i + 1, quantas).hasStatus(HttpStatus.OK);
        }
    }

    @Test
    void osPadroesSaoVinteMensagensPorHoraETresRelatoriosPorDia() {
        assertThat(LimitesPorUsuario.MENSAGENS_POR_HORA).isEqualTo(20);
        assertThat(LimitesPorUsuario.RELATORIOS_POR_DIA).isEqualTo(3);
        assertThat(limites.mensagensPorHora()).isEqualTo(20);
        assertThat(limites.relatoriosPorDia()).isEqualTo(3);
    }

    @Test
    void aMensagemVinteEUmaRecebe429ComQuantoFaltaESemChamarOModelo() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = conversaDe(pessoa, "Cliente Amarelo");
        mandar(pessoa, conversa, 20);
        modelo.texto("Isto não deve ser dito.");

        MvcTestResult resposta = pessoa.post(mensagens(conversa), TEXTO);

        assertThat(resposta).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(resposta.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(resposta, "$.title")).isEqualTo("Limite de uso atingido");
        assertThat(lerJson(resposta, "$.detail"))
                .isEqualTo("Você atingiu o limite de mensagens por hora. Tente de novo em 3 minutos.");
        assertThat(modelo.pedidos()).hasSize(20);   // o modelo não foi chamado na 21ª
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'user'").param("c", conversa)
                .query(Long.class).single()).as("a mensagem recusada não foi gravada").isEqualTo(20);
        assertThat(jdbc.sql("SELECT turno_desde IS NULL FROM atlas_conversa WHERE id = :c").param("c", conversa)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void oQuantoFaltaDiminuiComOTempoEAFichaVoltaAosPoucos() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = conversaDe(pessoa, "Cliente Amarelo");
        mandar(pessoa, conversa, 20);

        assertThat(lerJson(pessoa.post(mensagens(conversa), TEXTO), "$.detail")).isEqualTo(
                "Você atingiu o limite de mensagens por hora. Tente de novo em 3 minutos.");
        relogio.avancar(Duration.ofMinutes(2));
        assertThat(lerJson(pessoa.post(mensagens(conversa), TEXTO), "$.detail")).isEqualTo(
                "Você atingiu o limite de mensagens por hora. Tente de novo em 1 minuto.");
        relogio.avancar(Duration.ofSeconds(59));
        assertThat(pessoa.post(mensagens(conversa), TEXTO)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);   // falta 1 s

        relogio.avancar(Duration.ofSeconds(1));
        mandar(pessoa, conversa, 1);   // voltou uma ficha: uma mensagem passa
        assertThat(pessoa.post(mensagens(conversa), TEXTO)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void oLimiteEDoUsuarioNaoDaParceriaNemDeOutroUsuario() {
        SessaoDeApi doAmarelo = entrar(AMARELO);
        long conversaDoAmarelo = conversaDe(doAmarelo, "Cliente Amarelo");
        SessaoDeApi doRoxo = entrar(ROXO);
        long conversaDoRoxo = conversaDe(doRoxo, "Cliente Roxo");
        mandar(doAmarelo, conversaDoAmarelo, 20);

        assertThat(doAmarelo.post(mensagens(conversaDoAmarelo), TEXTO)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        mandar(doRoxo, conversaDoRoxo, 1);   // o Roxo tem a cota dele

        // e o limite vale para o usuário em qualquer conversa: uma conversa nova do Amarelo não zera nada
        long outraConversa = conversaDe(doAmarelo, "Cliente Amarelo");
        assertThat(doAmarelo.post(mensagens(outraConversa), TEXTO)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void oBotaoNaoContaNemEBarradoAcimaDoLimite() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = conversaDe(pessoa, "Cliente Amarelo");
        long doBotao = conversaDe(pessoa, "Cliente Amarelo");
        mandar(pessoa, conversa, 19);

        // o botão com o balde quase vazio não gasta a ficha que sobra: a 20ª mensagem de texto ainda passa
        MvcTestResult botao = pessoa.post(mensagens(doBotao), "{\"atalho\":\"MONTAR_RELATORIO\",\"modelo\":\"ATIVACAO_EVENTO\"}");
        assertThat(botao).hasStatus(HttpStatus.OK);
        mandar(pessoa, conversa, 1);

        // com o balde vazio o texto recebe 429 e o botão continua funcionando
        assertThat(pessoa.post(mensagens(conversa), TEXTO)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        long outraDoBotao = conversaDe(pessoa, "Cliente Amarelo");
        MvcTestResult denovo = pessoa.post(mensagens(outraDoBotao), "{\"atalho\":\"MONTAR_RELATORIO\",\"modelo\":\"ATIVACAO_EVENTO\"}");
        assertThat(denovo).hasStatus(HttpStatus.OK);
        assertThat(lerLista(denovo, "$.itens[*].tipo")).containsExactly("usuario", "texto", "cartao");
    }

    @Test
    void aRespostaDoCartaoAcimaDoLimiteSegueOPlanoBEmVezDeDar429() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = conversaDe(pessoa, "Cliente Amarelo");
        long outra = conversaDe(pessoa, "Cliente Amarelo");
        modelo.chamada("relatorio_iniciar", INICIAR).chamada("relatorio_solicitar_campos", LoopDoAgenteTest.ARGS_CARTAO);
        MvcTestResult aberta = pessoa.post(mensagens(conversa), "{\"texto\":\"Monte o relatório.\"}");   // gasta a ficha 1
        long cartao = ((Number) lerJson(aberta, "$.itens[1].solicitacaoId")).longValue();
        mandar(pessoa, outra, 19);   // o balde esvazia
        assertThat(pessoa.post(mensagens(outra), TEXTO)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        int idasAntes = modelo.pedidos().size();

        MvcTestResult resposta = pessoa.post("/api/atlas/solicitacoes/" + cartao + "/respostas", ACEITAR);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerLista(resposta, "$.itens[*].tipo")).containsExactly("texto", "relatorio_pronto");   // o plano B
        assertThat(modelo.pedidos()).as("o modelo não foi chamado de novo").hasSize(idasAntes);
    }

    @Test
    void aRespostaDoCartaoAbaixoDoLimiteUsaOModeloENaoGastaFicha() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = conversaDe(pessoa, "Cliente Amarelo");
        long outra = conversaDe(pessoa, "Cliente Amarelo");
        modelo.chamada("relatorio_iniciar", INICIAR).chamada("relatorio_solicitar_campos", LoopDoAgenteTest.ARGS_CARTAO);
        MvcTestResult aberta = pessoa.post(mensagens(conversa), "{\"texto\":\"Monte o relatório.\"}");   // gasta a ficha 1
        long cartao = ((Number) lerJson(aberta, "$.itens[1].solicitacaoId")).longValue();
        modelo.texto("Recebi os números.");

        MvcTestResult resposta = pessoa.post("/api/atlas/solicitacoes/" + cartao + "/respostas", ACEITAR);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerLista(resposta, "$.itens[*].texto")).contains("Recebi os números.");   // quem respondeu foi o modelo
        // a retomada não gastou ficha: ainda cabem as 19 mensagens que sobravam, e só a 21ª no total é barrada
        mandar(pessoa, outra, 19);
        assertThat(pessoa.post(mensagens(outra), TEXTO)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void oQuartoRelatorioPeloModeloVoltaComoErroDeFerramentaESugereOBotao() {
        SessaoDeApi pessoa = entrar(AMARELO);
        for (int i = 1; i <= 3; i++) {
            long conversa = conversaDe(pessoa, "Cliente Amarelo");
            modelo.chamada("relatorio_iniciar", INICIAR).texto("Comecei o relatório.");
            assertThat(pessoa.post(mensagens(conversa), "{\"texto\":\"Monte o relatório.\"}")).as("relatório %d", i)
                    .hasStatus(HttpStatus.OK);
            assertThat(jdbc.sql("SELECT count(*) FROM atlas_relatorio WHERE conversa_id = :c").param("c", conversa)
                    .query(Long.class).single()).as("relatório %d criado", i).isEqualTo(1);
        }
        long quarta = conversaDe(pessoa, "Cliente Amarelo");
        modelo.chamada("relatorio_iniciar", INICIAR).texto("Hoje não dá para montar outro por aqui. Use o botão Montar relatório.");

        MvcTestResult resposta = pessoa.post(mensagens(quarta), "{\"texto\":\"Monte o relatório.\"}");

        assertThat(resposta).hasStatus(HttpStatus.OK);   // erro de ferramenta, não de HTTP
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_relatorio WHERE conversa_id = :c").param("c", quarta).query(Long.class).single())
                .as("o 4º relatório não nasceu").isZero();
        MensagemDeChat resultado = modelo.ultimoPedido().mensagens().getLast();
        assertThat(resultado.papel()).isEqualTo("tool");
        assertThat(resultado.conteudo()).contains("\"erro\"").contains("botão Montar relatório").doesNotContainPattern("\\d");
        assertThat(lerLista(resposta, "$.itens[*].texto")).anyMatch(texto -> texto.toString().contains("botão Montar relatório"));
    }

    @Test
    void oRelatorioQueFalhaDevolveAFichaDoDia() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = conversaDe(pessoa, "Cliente Amarelo");
        // objetivo grande demais: a ferramenta falha depois de gastar a ficha e antes de criar o relatório, e a ficha volta
        String grande = "{\"modelo\":\"ATIVACAO_EVENTO\",\"objetivo\":\"" + "a".repeat(301) + "\"}";
        for (int i = 0; i < 5; i++) {
            modelo.chamada("relatorio_iniciar", grande).texto("Não consegui.");
            assertThat(pessoa.post(mensagens(conversa), TEXTO)).hasStatus(HttpStatus.OK);
        }
        for (int i = 1; i <= 3; i++) {
            long outra = conversaDe(pessoa, "Cliente Amarelo");
            modelo.chamada("relatorio_iniciar", INICIAR).texto("Comecei o relatório.");
            assertThat(pessoa.post(mensagens(outra), TEXTO)).hasStatus(HttpStatus.OK);
            assertThat(jdbc.sql("SELECT count(*) FROM atlas_relatorio WHERE conversa_id = :c").param("c", outra).query(Long.class)
                    .single()).as("relatório %d criado depois das tentativas que falharam", i).isEqualTo(1);
        }
    }

    @Test
    void aOrdemDasConferenciasAsDeFormatoEPermissaoVemAntesEOTetoDiarioAntesDoLimite() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long amarelo = parceria("Cliente Amarelo");
        long conversa = conversaDe(pessoa, "Cliente Amarelo");
        mandar(pessoa, conversa, 20);
        assertThat(pessoa.post(mensagens(conversa), TEXTO)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);

        // 403, 404 e 422 vêm antes do 429 do usuário
        assertThat(pessoa.post(mensagens(conversa), "{\"texto\":\"   \"}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(pessoa.post(mensagens(conversa), "{\"texto\":\"Oi\",\"atalho\":\"MONTAR_RELATORIO\"}"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(entrar("b2b@rbs.example").post(mensagens(conversa), TEXTO)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(entrar("atendimento@agencia-x.example").post(mensagens(conversa), TEXTO)).hasStatus(HttpStatus.NOT_FOUND);
        jdbc.sql("UPDATE parceria_aba SET liberada = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'APLICACOES')")
                .param("p", amarelo).update();
        assertThat(pessoa.post(mensagens(conversa), TEXTO)).hasStatus(HttpStatus.FORBIDDEN);
        jdbc.sql("UPDATE parceria_aba SET liberada = TRUE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'APLICACOES')")
                .param("p", amarelo).update();

        // o teto diário da parceria (que não volta em minutos) vem antes do limite por hora: a frase é a dele
        jdbc.sql("""
                INSERT INTO atlas_mensagem (conversa_id, ordem, papel, conteudo)
                SELECT :c, (SELECT max(ordem) FROM atlas_mensagem WHERE conversa_id = :c) + g, 'assistant', 'x' FROM generate_series(1, 200) g
                """).param("c", conversa).update();
        assertThat(lerJson(pessoa.post(mensagens(conversa), TEXTO), "$.detail"))
                .isEqualTo("O Atlas atingiu o limite de uso de hoje desta parceria. Use o botão Montar relatório ou tente amanhã.");
    }

    @Test
    void o429VemAntesDoConflitoEATentativaRecusadaNaoGastaFicha() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = conversaDe(pessoa, "Cliente Amarelo");
        mandar(pessoa, conversa, 18);

        // conversa ocupada: 409, e nenhuma ficha é gasta, por mais vezes que se tente
        jdbc.sql("UPDATE atlas_conversa SET turno_desde = now() WHERE id = :c").param("c", conversa).update();
        for (int i = 0; i < 6; i++) {
            assertThat(pessoa.post(mensagens(conversa), TEXTO)).as("tentativa %d", i + 1).hasStatus(HttpStatus.CONFLICT);
        }
        jdbc.sql("UPDATE atlas_conversa SET turno_desde = NULL WHERE id = :c").param("c", conversa).update();
        mandar(pessoa, conversa, 2);   // as duas fichas que sobravam ainda estão lá

        // esgotado, o 429 vem antes do 409
        jdbc.sql("UPDATE atlas_conversa SET turno_desde = now() WHERE id = :c").param("c", conversa).update();
        assertThat(pessoa.post(mensagens(conversa), TEXTO)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void oLimiteTambemBarraAMensagemEmFluxoAntesDeAbrirOFluxo() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = conversaDe(pessoa, "Cliente Amarelo");
        mandar(pessoa, conversa, 20);

        MvcTestResult emFluxo = pessoa.postAceitando(mensagens(conversa), TEXTO, "text/event-stream");

        assertThat(emFluxo).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(emFluxo.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(emFluxo, "$.detail")).isEqualTo("Você atingiu o limite de mensagens por hora. Tente de novo em 3 minutos.");
    }

    @Test
    void aFraseDaEsperaArredondaParaCimaEPassaParaHorasQuandoGrande() {
        assertThat(LimitesPorUsuario.descrever(Duration.ofSeconds(1))).isEqualTo("1 minuto");
        assertThat(LimitesPorUsuario.descrever(Duration.ofSeconds(61))).isEqualTo("2 minutos");
        assertThat(LimitesPorUsuario.descrever(Duration.ofMinutes(12))).isEqualTo("12 minutos");
        assertThat(LimitesPorUsuario.descrever(Duration.ofMinutes(120))).isEqualTo("120 minutos");
        assertThat(LimitesPorUsuario.descrever(Duration.ofMinutes(121))).isEqualTo("3 horas");
    }
}
