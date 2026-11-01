package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.AbrirConversaRequisicao;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemCartao;
import br.com.rbs.portal.atlas.AtlasDtos.RespostaDoCartaoRequisicao;
import br.com.rbs.portal.atlas.limites.LimitesPorUsuario;
import br.com.rbs.portal.atlas.provedor.ProvedorAtual;
import br.com.rbs.portal.atlas.provedor.ProvedorAtual.ChaveDoDebug;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.PedidoAoLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * [ATLAS] A resposta do cartão (rota 46) com várias respostas ao mesmo tempo e uma ficha só (M8): a retomada que vai ao modelo gasta
 * uma ficha do limite de mensagens por hora, e o gasto é a própria decisão (atômico), então com uma ficha só uma das respostas vai
 * ao modelo e as outras seguem o plano B. Antes, a retomada só conferia que havia ficha e nunca a gastava: quem guardava uma ficha
 * mandava o modelo responder quantos cartões quisesse. O balde é de uma ficha por hora, o modelo é seguro para várias threads e
 * conta as idas. Não é {@code @Transactional}: tudo é confirmado de verdade, e o teste apaga o que confirmou.
 */
@TesteComLlmRoteirizado
@Import(RelogioDeTesteConfig.class)
@TestPropertySource(properties = "atlas.limites.mensagens-por-hora=1")
class RespostaDoCartaoEmParaleloTest {

    private static final int CARTOES = 6;

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorAtual provedorAtual;

    @Autowired
    RelogioDeTeste relogio;

    @Autowired
    LimitesPorUsuario limites;

    @Autowired
    JdbcClient jdbc;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void preparar() {
        relogio.avancar(Duration.ofDays(3));   // o balde fica na memória: três dias parados o enchem de novo
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagar() {
        provedorAtual.esquecerChaveDoDebug();
        limpeza.apagar();
    }

    /** Um modelo seguro para várias threads: responde sempre com o mesmo texto e conta as idas. */
    static final class ModeloQueConta implements ProvedorDeLlm {

        final AtomicInteger idas = new AtomicInteger();

        @Override
        public RespostaDoLlm responder(PedidoAoLlm pedido) {
            idas.incrementAndGet();
            return new RespostaDoLlm("Certo, sigo sem esses números.", List.of(), null);
        }
    }

    @Test
    void comUmaFichaSoUmaDasRespostasEmParaleloVaiAoModelo() throws Exception {
        UsuarioAutenticado pessoa = usuario(jdbc, AMARELO);
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        ModeloQueConta modelo = new ModeloQueConta();
        provedorAtual.usarChaveDoDebug(new ChaveDoDebug(modelo, "http://127.0.0.1", "modelo-do-teste", false, "xxxx", null, null));
        List<Long> cartoes = new ArrayList<>();
        for (int i = 0; i < CARTOES; i++) {   // o botão é o plano B: abre o cartão sem modelo e sem gastar ficha
            ConversaDto aberta = atlas.abrirConversa(pessoa, parceria, new AbrirConversaRequisicao("MONTAR_RELATORIO", "ATIVACAO_EVENTO"),
                    requisicao());
            cartoes.add(aberta.itens().stream().filter(ItemCartao.class::isInstance).map(ItemCartao.class::cast).findFirst()
                    .orElseThrow().solicitacaoId());
        }
        assertThat(limites.mensagemDisponivel(pessoa.id())).as("a ficha única está inteira").isTrue();

        ExecutorService threads = Executors.newFixedThreadPool(CARTOES);
        CountDownLatch largada = new CountDownLatch(1);
        List<Throwable> erros = new ArrayList<>();
        try {
            List<Future<?>> respostas = new ArrayList<>();
            for (long cartao : cartoes) {
                respostas.add(threads.submit(() -> {
                    largada.await();
                    atlas.responderCartao(pessoa, cartao, new RespostaDoCartaoRequisicao("decline", List.of()), requisicao());
                    return null;
                }));
            }
            largada.countDown();
            for (Future<?> resposta : respostas) {
                try {
                    resposta.get(60, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException erro) {
                    erros.add(erro.getCause());
                }
            }
        } finally {
            threads.shutdownNow();
        }

        assertThat(erros).as("nenhuma resposta deu erro: sem ficha o cartão segue o plano B, e não o 429").isEmpty();
        assertThat(modelo.idas.get()).as("idas ao modelo com uma ficha só").isEqualTo(1);
        assertThat(limites.mensagemDisponivel(pessoa.id())).as("a ficha foi gasta").isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_solicitacao WHERE id IN (:ids) AND status = 'RECUSADA'").param("ids", cartoes)
                .query(Long.class).single()).as("todos os cartões foram respondidos").isEqualTo(CARTOES);
    }
}
