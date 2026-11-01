package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.RBS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.loop.TetoDaRbs;
import br.com.rbs.portal.atlas.provedor.ProvedorAtual;
import br.com.rbs.portal.atlas.provedor.ProvedorAtual.ChaveDoDebug;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.PedidoAoLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.LimiteDeUsoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * [ATLAS] O teto diário da pessoa da RBS com pedidos em paralelo (M8). O contador é o das mensagens do assistente já gravadas, e um
 * turno só grava a primeira depois de o modelo responder: conferir o teto e começar o turno eram dois passos, e N pedidos da mesma
 * pessoa ao mesmo tempo, com uma vaga só, passavam todos. Aqui as conversas da RBS são abertas e os turnos disparados juntos, e o
 * modelo só responde quando todo pedido já passou pela conferência (ou foi barrado), então nenhum turno grava antes de os outros
 * conferirem. O modelo é seguro para várias threads. Não é {@code @Transactional}: tudo é confirmado de verdade, e o teste apaga o
 * que confirmou.
 */
@TesteDoAtlasDaRbs
class AtlasDaRbsTetoEmParaleloTest {

    private static final int PEDIDOS = 8;
    private static final String RESPOSTA = "Posso ajudar com os documentos da edição.";

    @Autowired
    AtlasService atlas;

    @Autowired
    AtlasRepositorio repositorio;

    @Autowired
    TetoDaRbs teto;

    @Autowired
    ProvedorAtual provedorAtual;

    @Autowired
    JdbcClient jdbc;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void preparar() {
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagar() {
        provedorAtual.esquecerChaveDoDebug();
        limpeza.apagar();
    }

    /** Um modelo seguro para várias threads que só responde quando {@code chegaram} zerou: cada pedido conta ao passar ou ao ser barrado. */
    static final class ModeloQueEspera implements ProvedorDeLlm {

        final CountDownLatch chegaram;
        final List<Integer> idas = new CopyOnWriteArrayList<>();

        ModeloQueEspera(int pedidos) {
            this.chegaram = new CountDownLatch(pedidos);
        }

        @Override
        public RespostaDoLlm responder(PedidoAoLlm pedido) {
            idas.add(1);
            chegaram.countDown();
            try {
                if (!chegaram.await(20, TimeUnit.SECONDS)) {
                    throw new AssertionError("Nem todo pedido chegou à conferência do teto em 20 s.");
                }
            } catch (InterruptedException interrompido) {
                Thread.currentThread().interrupt();
            }
            return new RespostaDoLlm(RESPOSTA, List.of(), null);
        }
    }

    /** O resultado de cada pedido disparado junto: quantos passaram, as frases dos 429 e o que mais saiu. */
    record Resultado(AtomicInteger passaram, List<String> frasesDoTeto, List<Throwable> outros) {
    }

    private long evento2027() {
        return jdbc.sql("SELECT id FROM evento WHERE edicao = 2027").query(Long.class).single();
    }

    /** Põe a pessoa da RBS com {@code folga} vagas no teto de hoje (o resto já gasto em uma conversa só, para isso). */
    private void deixarFolga(UsuarioAutenticado pessoa, int folga) {
        long gasta = repositorio.criarConversaDaRbs(evento2027(), pessoa.id());
        jdbc.sql("""
                INSERT INTO atlas_mensagem (conversa_id, ordem, papel, conteudo)
                SELECT :conversa, g, 'assistant', 'Texto do assistente.' FROM generate_series(1, :quantas) g
                """).param("conversa", gasta).param("quantas", TetoDaRbs.PADRAO - folga).update();
        assertThat(teto.usadasHoje(pessoa.id())).isEqualTo(TetoDaRbs.PADRAO - folga);
    }

    /** Dispara {@link #PEDIDOS} mensagens juntas, cada uma na sua conversa da mesma pessoa, e espera todas terminarem. */
    private Resultado dispararJuntos(UsuarioAutenticado pessoa, ModeloQueEspera modelo) throws Exception {
        provedorAtual.usarChaveDoDebug(new ChaveDoDebug(modelo, "http://127.0.0.1", "modelo-do-teste", false, "xxxx", null, null));
        List<Long> conversas = new ArrayList<>();
        for (int i = 0; i < PEDIDOS; i++) {
            conversas.add(repositorio.criarConversaDaRbs(evento2027(), pessoa.id()));
        }
        Resultado resultado = new Resultado(new AtomicInteger(), new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>());
        ExecutorService threads = Executors.newFixedThreadPool(PEDIDOS);
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Future<?>> turnos = new ArrayList<>();
            for (long conversa : conversas) {
                turnos.add(threads.submit(() -> {
                    largada.await();
                    try {
                        atlas.enviarMensagem(pessoa, conversa, new MensagemRequisicao("Oi, Atlas.", null, null), requisicao());
                        resultado.passaram().incrementAndGet();
                    } catch (LimiteDeUsoException barrado) {
                        resultado.frasesDoTeto().add(barrado.getMessage());
                        modelo.chegaram.countDown();   // barrado: não vai ao modelo, e não pode deixar os outros esperando
                    } catch (RuntimeException erro) {
                        resultado.outros().add(erro);
                        modelo.chegaram.countDown();
                    }
                    return null;
                }));
            }
            largada.countDown();
            for (Future<?> turno : turnos) {
                turno.get(60, TimeUnit.SECONDS);
            }
        } finally {
            threads.shutdownNow();
        }
        return resultado;
    }

    @Test
    void comUmaVagaSoUmDosPedidosDaMesmaPessoaEmParaleloPassa() throws Exception {
        UsuarioAutenticado pessoa = usuario(jdbc, RBS);
        deixarFolga(pessoa, 1);
        ModeloQueEspera modelo = new ModeloQueEspera(PEDIDOS);

        Resultado resultado = dispararJuntos(pessoa, modelo);

        assertThat(resultado.outros()).as("nenhum erro além do 429").isEmpty();
        assertThat(resultado.passaram().get()).as("pedidos que passaram com uma vaga só").isEqualTo(1);
        assertThat(resultado.frasesDoTeto()).hasSize(PEDIDOS - 1).containsOnly(TetoDaRbs.MENSAGEM);
        assertThat(modelo.idas).as("o modelo foi chamado uma vez só").hasSize(1);
        assertThat(teto.usadasHoje(pessoa.id())).as("a pessoa fecha o dia no teto, e não acima dele").isEqualTo(TetoDaRbs.PADRAO);
    }

    @Test
    void comTresVagasPassamTresExatamente() throws Exception {
        UsuarioAutenticado pessoa = usuario(jdbc, RBS);
        deixarFolga(pessoa, 3);
        ModeloQueEspera modelo = new ModeloQueEspera(PEDIDOS);

        Resultado resultado = dispararJuntos(pessoa, modelo);

        assertThat(resultado.outros()).isEmpty();
        assertThat(resultado.passaram().get()).isEqualTo(3);
        assertThat(resultado.frasesDoTeto()).hasSize(PEDIDOS - 3).containsOnly(TetoDaRbs.MENSAGEM);
        assertThat(teto.usadasHoje(pessoa.id())).isEqualTo(TetoDaRbs.PADRAO);
    }

    @Test
    void oTurnoQueNaoComecaDevolveAVagaEOQueAcabaTambem() throws Exception {
        UsuarioAutenticado pessoa = usuario(jdbc, RBS);
        deixarFolga(pessoa, 2);
        provedorAtual.usarChaveDoDebug(new ChaveDoDebug(new ModeloQueEspera(0), "http://127.0.0.1", "modelo-do-teste", false, "xxxx", null,
                null));
        long ocupada = repositorio.criarConversaDaRbs(evento2027(), pessoa.id());
        long livre = repositorio.criarConversaDaRbs(evento2027(), pessoa.id());
        jdbc.sql("UPDATE atlas_conversa SET turno_desde = now() WHERE id = :c").param("c", ocupada).update();

        // 409: o turno não começou, e a vaga que o pedido marcou tem de voltar (por mais vezes que se tente)
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> atlas.enviarMensagem(pessoa, ocupada, new MensagemRequisicao("Oi", null, null), requisicao()))
                    .isInstanceOf(ConflitoException.class);
        }
        // as duas vagas seguem livres: dois turnos em fila passam, e o terceiro encontra o teto
        atlas.enviarMensagem(pessoa, livre, new MensagemRequisicao("Oi", null, null), requisicao());
        atlas.enviarMensagem(pessoa, livre, new MensagemRequisicao("Oi de novo", null, null), requisicao());
        assertThatThrownBy(() -> atlas.enviarMensagem(pessoa, livre, new MensagemRequisicao("Mais uma", null, null), requisicao()))
                .isInstanceOf(LimiteDeUsoException.class).hasMessage(TetoDaRbs.MENSAGEM);
        assertThat(teto.usadasHoje(pessoa.id())).isEqualTo(TetoDaRbs.PADRAO);
    }
}
