package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.AbrirConversaRequisicao;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemCartao;
import br.com.rbs.portal.atlas.AtlasDtos.ItemTexto;
import br.com.rbs.portal.atlas.loop.HistoricoDaConversa;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.MensagemDeChat;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.PedidoAoLlm;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] As duas colunas da V15 em uso. {@code fora_do_modelo}: a resposta fixa de um cartão aberto e a mensagem da pessoa que a
 * provocou ficam na conversa e na tela, mas o histórico que vai ao modelo as pula (a chamada do cartão continua esperando a resposta,
 * e o histórico segue válido para o provedor), e elas não contam no teto diário nem no relógio de 7 dias do relatório parado.
 * {@code sem_modelo}: o servidor escreveu a mensagem do assistente (o botão); ela continua no histórico do modelo, não conta no teto,
 * e a conversa a mostra com {@code porModelo} falso. Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class ForaDoModeloTest {

    private static final AbrirConversaRequisicao BOTAO = new AbrirConversaRequisicao("MONTAR_RELATORIO", "ATIVACAO_EVENTO");
    private static final String RESPOSTA_FIXA = "Use o cartão acima para informar os números: assim eles ficam registrados com autor e data.";

    @Autowired
    AtlasService atlas;

    @Autowired
    AtlasRepositorio repositorio;

    @Autowired
    HistoricoDaConversa historico;

    @Autowired
    JdbcClient jdbc;

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    UsuarioAutenticado marca() {
        return usuario(jdbc, AMARELO);
    }

    /** A conversa do botão: o cartão está aberto (a chamada de ferramenta dele ainda espera a resposta). */
    ConversaDto comCartaoAberto() {
        return atlas.abrirConversa(marca(), amarelo(), BOTAO, requisicao());
    }

    /** O que um cartão aberto recebe quando a pessoa escreve e o JEV diz "responde ao cartão": as duas mensagens, fora do modelo. */
    void conversarComOCartaoAberto(long conversa) {
        historico.usuarioForaDoModelo(conversa, "e os brindes, informo onde?");
        historico.assistenteForaDoModelo(conversa, RESPOSTA_FIXA);
    }

    private List<MensagemDeChat> doModelo(long conversa) {
        List<MensagemDeChat> mensagens = new ArrayList<>();
        mensagens.add(MensagemDeChat.sistema("prompt"));
        mensagens.addAll(historico.paraOProvedor(conversa, repositorio.ultimaOrdem(conversa)));
        return mensagens;
    }

    @Test
    void oHistoricoDoModeloPulaAsMensagensForaDoModeloEContinuaValidoComOCartaoPendente() {
        ConversaDto aberta = comCartaoAberto();
        long conversa = aberta.conversaId();
        ItemCartao cartao = aberta.itens().stream().filter(ItemCartao.class::isInstance).map(ItemCartao.class::cast).findFirst().orElseThrow();
        conversarComOCartaoAberto(conversa);

        // com o cartão ainda aberto: a chamada pendente recebe o erro de "não chegou a responder", e nenhuma das duas mensagens vai
        List<MensagemDeChat> pendente = doModelo(conversa);
        assertThat(pendente).extracting(MensagemDeChat::conteudo).doesNotContain(RESPOSTA_FIXA, "e os brindes, informo onde?");

        // a pessoa responde o cartão: a resposta de verdade fica depois das duas mensagens, e o histórico do modelo é o de sempre
        atlas.responderCartao(marca(), cartao.solicitacaoId(),
                new AtlasDtos.RespostaDoCartaoRequisicao("decline", List.of()), requisicao());
        List<MensagemDeChat> depois = doModelo(conversa);

        ProvedorRoteirizado.exigirHistoricoValido(new PedidoAoLlm(String.valueOf(conversa), depois, List.of()));
        assertThat(depois).filteredOn(m -> "user".equals(m.papel())).extracting(MensagemDeChat::conteudo)
                .containsExactly(br.com.rbs.portal.atlas.loop.PlanoBSemLlm.TEXTO_DO_BOTAO);   // a do botão fica; a de fora do modelo saiu
        assertThat(depois).filteredOn(m -> "tool".equals(m.papel())).hasSize(depois.stream().mapToInt(m -> m.chamadas().size()).sum());
        assertThat(depois).extracting(MensagemDeChat::conteudo).doesNotContain(RESPOSTA_FIXA);
    }

    @Test
    void aConversaContinuaMostrandoAsDuasMensagensComAFixaSemPorModelo() {
        ConversaDto aberta = comCartaoAberto();
        long conversa = aberta.conversaId();
        conversarComOCartaoAberto(conversa);

        ConversaDto inteira = atlas.verConversa(marca(), conversa);

        assertThat(inteira.itens()).filteredOn(AtlasDtos.ItemUsuario.class::isInstance).extracting(i -> ((AtlasDtos.ItemUsuario) i).texto())
                .contains("e os brindes, informo onde?");
        List<ItemTexto> textos = inteira.itens().stream().filter(ItemTexto.class::isInstance).map(ItemTexto.class::cast).toList();
        ItemTexto fixa = textos.stream().filter(t -> RESPOSTA_FIXA.equals(t.texto())).findFirst().orElseThrow();
        assertThat(fixa.porModelo()).isFalse();
    }

    @Test
    void aMensagemDoBotaoEDoServidorETemSemModeloVerdadeiro() {
        ConversaDto aberta = comCartaoAberto();

        // o plano B escreve o histórico "como se o modelo tivesse chamado": continua no histórico do modelo, mas sem_modelo marca que não foi ele
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'assistant' AND sem_modelo AND NOT fora_do_modelo")
                .param("c", aberta.conversaId()).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'assistant' AND NOT sem_modelo")
                .param("c", aberta.conversaId()).query(Long.class).single()).isZero();
        assertThat(doModelo(aberta.conversaId())).extracting(MensagemDeChat::papel).contains("assistant");
        assertThat(aberta.itens()).filteredOn(ItemTexto.class::isInstance).extracting(i -> ((ItemTexto) i).porModelo()).containsOnly(false);
        assertThat(repositorio.mensagensDoModeloHoje(amarelo())).isZero();
    }

    @Test
    void aMensagemDoModeloContaNoTetoESemModeloEForaDoModeloNao() {
        ConversaDto aberta = comCartaoAberto();
        long conversa = aberta.conversaId();
        conversarComOCartaoAberto(conversa);
        assertThat(repositorio.mensagensDoModeloHoje(amarelo())).isZero();

        historico.assistente(conversa, "Resposta do modelo.", List.of());

        assertThat(repositorio.mensagensDoModeloHoje(amarelo())).isEqualTo(1);
    }

    @Test
    void oRelogioDeSeteDiasNaoContaAMensagemForaDoModelo() {
        ConversaDto aberta = comCartaoAberto();
        long conversa = aberta.conversaId();
        envelhecer(conversa, 8);
        conversarComOCartaoAberto(conversa);   // gravadas agora, mas fora do modelo: conversar com o cartão aberto não mantém o relatório vivo
        long relatorio = aberta.itens().stream().filter(ItemCartao.class::isInstance).map(ItemCartao.class::cast).findFirst().orElseThrow()
                .relatorioId();

        atlas.verConversa(marca(), conversa);

        assertThat(jdbc.sql("SELECT status FROM atlas_relatorio WHERE id = :id").param("id", relatorio).query(String.class).single())
                .isEqualTo("DESCARTADO");
    }

    @Test
    void oRelogioDeSeteDiasContaAMensagemDeVerdade() {
        ConversaDto aberta = comCartaoAberto();
        long conversa = aberta.conversaId();
        envelhecer(conversa, 8);
        historico.usuario(conversa, "voltei");   // uma mensagem comum, de agora, mantém o relatório vivo
        long relatorio = aberta.itens().stream().filter(ItemCartao.class::isInstance).map(ItemCartao.class::cast).findFirst().orElseThrow()
                .relatorioId();

        atlas.verConversa(marca(), conversa);

        assertThat(jdbc.sql("SELECT status FROM atlas_relatorio WHERE id = :id").param("id", relatorio).query(String.class).single())
                .isEqualTo("AGUARDANDO_RESPOSTA");
    }

    private void envelhecer(long conversaId, int dias) {
        jdbc.sql("UPDATE atlas_mensagem SET criada_em = now() - make_interval(days => :d) WHERE conversa_id = :c")
                .param("d", dias).param("c", conversaId).update();
        jdbc.sql("UPDATE atlas_relatorio SET criado_em = now() - make_interval(days => :d) WHERE conversa_id = :c")
                .param("d", dias).param("c", conversaId).update();
    }
}
