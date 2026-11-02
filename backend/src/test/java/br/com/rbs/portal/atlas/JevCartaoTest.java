package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.CADASTROS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.VISITANTES;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemCartao;
import br.com.rbs.portal.atlas.AtlasDtos.ItemTexto;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.AtlasDtos.RespostaDoCartaoRequisicao;
import br.com.rbs.portal.atlas.jev.ModoDoJev;
import br.com.rbs.portal.atlas.jev.PontoDoJev;
import br.com.rbs.portal.atlas.loop.LoopDoAgente;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * [ATLAS] O ponto {@code cartao} do JEV (passo 6, M7), com o JEV e o modelo roteirizados (critério B do plano): quem escreve com o
 * cartão aberto e só quer saber dos números dele recebe a resposta fixa, sem o modelo, e o cartão <b>continua aberto</b>; as duas
 * mensagens ficam {@code fora_do_modelo}, e o histórico que vai ao modelo continua válido depois. {@code outro_assunto} e
 * {@code cancelar} seguem o caminho de hoje (o cartão fecha com {@code cancel} e a mensagem vai ao modelo). A decisão vem antes de
 * fechar o cartão. Não é {@code @Transactional}: o que o teste confirmou é apagado no fim.
 */
@TesteComJevRoteirizado
class JevCartaoTest {

    private static final String PERGUNTA = "e os brindes, onde eu informo?";

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    JevRoteirizado jev;

    @Autowired
    ConfiguracaoDoJevDeTeste config;

    @Autowired
    AtlasRepositorio repositorio;

    @Autowired
    JdbcClient jdbc;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void preparar() {
        modelo.limpar();
        jev.limpar();
        config.limpar().ligar(PontoDoJev.CARTAO, ModoDoJev.ATIVO);
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagar() {
        config.limpar();
        limpeza.apagar();
    }

    private UsuarioAutenticado marca() {
        return usuario(jdbc, AMARELO);
    }

    private long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    /** A conversa do botão, com o cartão aberto. */
    private long conversaComCartaoAberto() {
        long conversa = atlas.abrirConversa(marca(), amarelo(), null, requisicao()).conversaId();
        atlas.enviarMensagem(marca(), conversa, new MensagemRequisicao(null, "MONTAR_RELATORIO", "ATIVACAO_EVENTO"), requisicao());
        return conversa;
    }

    private ConversaDto falar(long conversa, String texto) {
        return atlas.enviarMensagem(marca(), conversa, new MensagemRequisicao(texto, null, null), requisicao());
    }

    private String statusDoCartao(long conversa) {
        return jdbc.sql("SELECT s.status FROM atlas_solicitacao s JOIN atlas_relatorio r ON r.id = s.relatorio_id WHERE r.conversa_id = :c "
                + "ORDER BY s.id DESC LIMIT 1").param("c", conversa).query(String.class).single();
    }

    private long cartao(long conversa) {
        return jdbc.sql("SELECT s.id FROM atlas_solicitacao s JOIN atlas_relatorio r ON r.id = s.relatorio_id WHERE r.conversa_id = :c "
                + "ORDER BY s.id DESC LIMIT 1").param("c", conversa).query(Long.class).single();
    }

    private List<Map<String, Object>> decisoes(long conversa) {
        return jdbc.sql("SELECT d.ponto, d.modo, d.escolha, d.confianca, d.acao FROM atlas_decisao d JOIN atlas_mensagem m ON m.id = d.mensagem_id "
                + "WHERE m.conversa_id = :c ORDER BY d.id").param("c", conversa).query().listOfRows();
    }

    @Test
    void respondeAoCartaoDaARespostaFixaSemOModeloEOCartaoContinuaAberto() {
        long conversa = conversaComCartaoAberto();
        jev.cartao("responde_ao_cartao", 0.98);

        ConversaDto resposta = falar(conversa, PERGUNTA);

        assertThat(modelo.pedidos()).as("o modelo não foi chamado").isEmpty();
        assertThat(resposta.itens()).extracting(i -> i.getClass().getSimpleName()).containsExactly("ItemUsuario", "ItemTexto");
        ItemTexto fixa = (ItemTexto) resposta.itens().get(1);
        assertThat(fixa.texto()).isEqualTo(LoopDoAgente.RESPOSTA_DO_CARTAO_ABERTO)
                .isEqualTo("Use o cartão acima para informar os números: assim eles ficam registrados com autor e data.");
        assertThat(fixa.porModelo()).isFalse();
        assertThat(statusDoCartao(conversa)).as("o cartão continua aberto").isEqualTo("PENDENTE");
        assertThat(jdbc.sql("SELECT status FROM atlas_relatorio WHERE conversa_id = :c").param("c", conversa).query(String.class).single())
                .isEqualTo("AGUARDANDO_RESPOSTA");
        // as duas mensagens ficam fora do modelo (a segunda também sem modelo), e não contam no teto
        List<Map<String, Object>> ultimas = jdbc.sql("SELECT papel, fora_do_modelo, sem_modelo FROM atlas_mensagem WHERE conversa_id = :c "
                + "ORDER BY ordem DESC LIMIT 2").param("c", conversa).query().listOfRows();
        assertThat(ultimas).extracting(m -> m.get("papel")).containsExactly("assistant", "user");
        assertThat(ultimas).extracting(m -> m.get("fora_do_modelo")).containsExactly(true, true);
        assertThat(ultimas.get(0).get("sem_modelo")).isEqualTo(true);
        assertThat(repositorio.mensagensDoModeloHoje(amarelo())).isZero();
        assertThat(decisoes(conversa)).hasSize(1);
        assertThat(decisoes(conversa).get(0).get("acao")).isEqualTo("fez: respondeu com a frase fixa e manteve o cartão aberto");
    }

    @Test
    void oHistoricoDoModeloContinuaValidoQuandoOCartaoEPorFimRespondido() {
        long conversa = conversaComCartaoAberto();
        jev.cartao("responde_ao_cartao", 0.98);
        falar(conversa, PERGUNTA);
        falar(conversa, "e se eu não tiver o número dos brindes?");
        assertThat(statusDoCartao(conversa)).isEqualTo("PENDENTE");
        modelo.texto("Recebi os números.");

        atlas.responderCartao(marca(), cartao(conversa), new RespostaDoCartaoRequisicao("accept",
                ApoioDoAtlas.valores(VISITANTES, 1200L, CADASTROS, 1072L)), requisicao());

        assertThat(modelo.pedidos()).hasSize(1);
        ProvedorRoteirizado.exigirHistoricoValido(modelo.pedidos().get(0));
        // nenhuma das quatro mensagens fora do modelo foi ao provedor, e nenhum erro sintético tomou o lugar da resposta do cartão
        assertThat(modelo.pedidos().get(0).mensagens()).extracting(m -> m.conteudo())
                .doesNotContain(PERGUNTA, "e se eu não tiver o número dos brindes?", LoopDoAgente.RESPOSTA_DO_CARTAO_ABERTO);
        assertThat(modelo.pedidos().get(0).mensagens()).noneMatch(m -> "tool".equals(m.papel()) && m.conteudo() != null
                && m.conteudo().contains("não chegou a responder"));
        // a conversa continua mostrando as duas respostas fixas
        long fixas = atlas.verConversa(marca(), conversa).itens().stream()
                .filter(i -> i instanceof ItemTexto t && LoopDoAgente.RESPOSTA_DO_CARTAO_ABERTO.equals(t.texto())).count();
        assertThat(fixas).isEqualTo(2);
    }

    @Test
    void asMensagensDoCartaoAbertoNaoSeguraORelogioDeSeteDiasNemOTeto() {
        long conversa = conversaComCartaoAberto();
        jev.cartao("responde_ao_cartao", 0.98);
        jdbc.sql("UPDATE atlas_mensagem SET criada_em = now() - interval '8 days' WHERE conversa_id = :c").param("c", conversa).update();
        jdbc.sql("UPDATE atlas_relatorio SET criado_em = now() - interval '8 days' WHERE conversa_id = :c").param("c", conversa).update();

        falar(conversa, PERGUNTA);
        atlas.verConversa(marca(), conversa);

        assertThat(jdbc.sql("SELECT status FROM atlas_relatorio WHERE conversa_id = :c").param("c", conversa).query(String.class).single())
                .isEqualTo("DESCARTADO");
        assertThat(repositorio.mensagensDoModeloHoje(amarelo())).isZero();
    }

    @Test
    void outroAssuntoFechaOCartaoComCancelEMandaAMensagemAoModelo() {
        long conversa = conversaComCartaoAberto();
        jev.cartao("outro_assunto", 0.95);
        modelo.texto("O horário de montagem está no manual.");

        ConversaDto resposta = falar(conversa, "qual o horário de montagem?");

        assertThat(modelo.pedidos()).hasSize(1);
        assertThat(statusDoCartao(conversa)).isEqualTo("CANCELADA");
        assertThat(resposta.itens()).extracting(i -> i.getClass().getSimpleName()).contains("ItemAviso", "ItemTexto");
        ProvedorRoteirizado.exigirHistoricoValido(modelo.pedidos().get(0));
        assertThat(decisoes(conversa).get(0).get("acao")).asString().startsWith("não agiu: segue o caminho de hoje");
    }

    @Test
    void cancelarSegueOCaminhoDeHoje() {
        long conversa = conversaComCartaoAberto();
        jev.cartao("cancelar", 0.99);
        modelo.texto("Tudo bem, deixamos para depois.");

        falar(conversa, "deixa pra lá, não quero mais");

        assertThat(statusDoCartao(conversa)).isEqualTo("CANCELADA");
        assertThat(modelo.pedidos()).hasSize(1);
    }

    @Test
    void abaixoDoLimiarEEmErroOCartaoSegueOCaminhoDeHoje() {
        long conversa = conversaComCartaoAberto();
        jev.cartao("responde_ao_cartao", 0.55);
        modelo.texto("Resposta.");
        falar(conversa, PERGUNTA);
        assertThat(statusDoCartao(conversa)).isEqualTo("CANCELADA");
        assertThat(decisoes(conversa).get(0).get("acao")).isEqualTo("não agiu: confiança abaixo do limiar");

        modelo.limpar();
        jev.limpar().falhar(PontoDoJev.CARTAO, "HTTP_529");
        long outra = conversaComCartaoAberto();
        modelo.texto("Resposta.");
        falar(outra, PERGUNTA);
        assertThat(statusDoCartao(outra)).isEqualTo("CANCELADA");
        assertThat(modelo.pedidos()).hasSize(1);
        assertThat(jdbc.sql("SELECT d.erro FROM atlas_decisao d JOIN atlas_mensagem m ON m.id = d.mensagem_id WHERE m.conversa_id = :c")
                .param("c", outra).query(String.class).single()).isEqualTo("HTTP_529");
    }

    @Test
    void aDecisaoDoCartaoVemAntesDeFecharOCartao() {
        long conversa = conversaComCartaoAberto();
        // no momento em que o JEV é consultado, o cartão ainda está aberto e a mensagem da pessoa ainda não foi gravada
        jev.passo(PontoDoJev.CARTAO, pedido -> {
            assertThat(statusDoCartao(conversa)).isEqualTo("PENDENTE");
            assertThat(jdbc.sql("SELECT count(*) FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'user' AND conteudo = :t")
                    .param("c", conversa).param("t", PERGUNTA).query(Long.class).single()).isZero();
            return new br.com.rbs.portal.atlas.jev.ProvedorDeJev.RespostaDoJev(Map.of("cartao",
                    br.com.rbs.portal.atlas.jev.ProvedorDeJev.RespostaDaPergunta.deEscolha("responde_ao_cartao", 0.99, null)), 3);
        });

        falar(conversa, PERGUNTA);

        assertThat(jev.recebidasDe(PontoDoJev.CARTAO)).hasSize(1);
        assertThat(statusDoCartao(conversa)).isEqualTo("PENDENTE");
    }

    @Test
    void oStateDoCartaoLevaOsRotulosDosCamposEAMensagemSemNomeDeParceria() {
        long conversa = conversaComCartaoAberto();
        jev.cartao("outro_assunto", 0.95);
        modelo.texto("Ok.");

        falar(conversa, "O CPF do Cliente Amarelo é 123.456.789-09, mande para joao@amarelo.example");

        String estado = jev.recebidasDe(PontoDoJev.CARTAO).get(0).pedido().estado();
        assertThat(estado).contains("Visitantes").contains("[removido]").doesNotContain("123.456.789-09").doesNotContain("joao@amarelo")
                .doesNotContain("Cliente Amarelo").doesNotContain(String.valueOf(amarelo()));
    }

    @Test
    void semCartaoAbertoOPontoDoCartaoNaoEConsultado() {
        long conversa = atlas.abrirConversa(marca(), amarelo(), null, requisicao()).conversaId();
        modelo.texto("Olá!");

        falar(conversa, "oi");

        assertThat(jev.recebidas()).isEmpty();
    }

    @Test
    void oCartaoDoModeloDeLinguagemTambemRecebeARespostaFixa() {
        long conversa = atlas.abrirConversa(marca(), amarelo(), null, requisicao()).conversaId();
        modelo.chamada("relatorio_iniciar", "{\"modelo\":\"ATIVACAO_EVENTO\"}")
                .chamada("relatorio_solicitar_campos", LoopDoAgenteTest.ARGS_CARTAO);
        falar(conversa, "Monte o relatório.");
        assertThat(statusDoCartao(conversa)).isEqualTo("PENDENTE");
        jev.cartao("responde_ao_cartao", 0.97);

        falar(conversa, PERGUNTA);

        assertThat(modelo.pedidos()).as("só as duas idas que abriram o cartão").hasSize(2);
        assertThat(statusDoCartao(conversa)).isEqualTo("PENDENTE");
        // este cartão veio do modelo: a resposta fixa conta como mensagem fora do modelo, e o teto só tem as duas idas dele
        assertThat(repositorio.mensagensDoModeloHoje(amarelo())).isEqualTo(2);
    }

    @Test
    void aRespostaFixaTambemSaiNoFluxoEOCartaoNaoMuda() {
        long conversa = conversaComCartaoAberto();
        jev.cartao("responde_ao_cartao", 0.98);

        var resposta = ApoioDoFluxo.falarEmFluxo(sessaoDeApi(), conversa, PERGUNTA);

        var eventos = ApoioDoFluxo.eventos(resposta);
        assertThat(ApoioDoFluxo.juntar(eventos, "texto", "delta")).isEqualTo(LoopDoAgente.RESPOSTA_DO_CARTAO_ABERTO);
        assertThat(ApoioDoFluxo.nomes(eventos)).contains("fim");
        assertThat(ApoioDoFluxo.turnoSolto(jdbc, conversa)).isTrue();
        assertThat(statusDoCartao(conversa)).isEqualTo("PENDENTE");
        assertThat(modelo.pedidos()).isEmpty();
    }

    @Autowired
    org.springframework.test.web.servlet.assertj.MockMvcTester mvc;

    private SessaoDeApi sessaoDeApi() {
        return SessaoDeApi.entrar(mvc, AMARELO);
    }

    @Test
    void oItemDoCartaoAindaApareceNaConversa() {
        long conversa = conversaComCartaoAberto();
        jev.cartao("responde_ao_cartao", 0.98);
        falar(conversa, PERGUNTA);

        ConversaDto inteira = atlas.verConversa(marca(), conversa);

        assertThat(inteira.itens().stream().filter(ItemCartao.class::isInstance).map(ItemCartao.class::cast).findFirst().orElseThrow().status())
                .isEqualTo("PENDENTE");
    }
}
