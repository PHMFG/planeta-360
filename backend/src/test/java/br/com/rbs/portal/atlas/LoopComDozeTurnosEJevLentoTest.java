package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.FontesNoChatTest.BUSCAR;
import static br.com.rbs.portal.atlas.FontesNoChatTest.CONSULTA;
import static br.com.rbs.portal.atlas.FontesNoChatTest.PRAZO;
import static br.com.rbs.portal.atlas.FontesNoChatTest.busca;
import static br.com.rbs.portal.atlas.FontesNoChatTest.frasePrazo;
import static br.com.rbs.portal.atlas.FontesNoChatTest.marcador;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemTexto;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.jev.ModoDoJev;
import br.com.rbs.portal.atlas.jev.PontoDoJev;
import br.com.rbs.portal.atlas.provedor.ProvedorAtual;
import br.com.rbs.portal.atlas.provedor.ProvedorAtual.ChaveDoDebug;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.MensagemDeChat;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.PedidoAoLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * [ATLAS] Doze turnos simultâneos com o JEV lento (E56, critério B do M7), no molde dos de {@code LoopSemTransacaoTest}: o JEV (a
 * intenção, a relevância de cada trecho em paralelo e a conferência da resposta) e o modelo demoram, e nenhum turno pode segurar
 * uma conexão do pool nem uma transação durante a espera. O pool deste contexto é de <b>4 conexões</b> e o tempo de espera por
 * uma é de 1,5 s, com o JEV demorando 2,4 s por requisição: se um turno segurasse a conexão enquanto espera o JEV, os outros oito
 * (são doze, e o pool é de quatro) estourariam o tempo de conexão e o teste falharia. Não é {@code @Transactional}: tudo é
 * confirmado de verdade, e o teste apaga o que confirmou.
 */
@TesteComJevRoteirizado
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=4", "spring.datasource.hikari.connection-timeout=1500",
        "atlas.diagnostico.ligado=true", "atlas.diagnostico.marca=true", "atlas.prazo-do-resumo=PT0.5S"})
class LoopComDozeTurnosEJevLentoTest {

    private static final int TURNOS = 12;
    private static final long DEMORA_DO_JEV_EM_MS = 2_400;
    private static final long DEMORA_DO_MODELO_EM_MS = 300;

    @Autowired
    AtlasService atlas;

    @Autowired
    JevRoteirizado jev;

    @Autowired
    ConfiguracaoDoJevDeTeste config;

    @Autowired
    ProvedorAtual provedorAtual;

    @Autowired
    JdbcClient jdbc;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void preparar() {
        jev.limpar();
        config.limpar();
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagar() {
        provedorAtual.esquecerChaveDoDebug();
        jev.limpar();
        config.limpar();
        limpeza.apagar();
    }

    /** O modelo lento e seguro para várias threads: busca nos documentos e, com o resultado na mão, responde citando o trecho do prazo. */
    static final class ModeloQueBusca implements ProvedorDeLlm {

        final List<Boolean> transacaoAtiva = new CopyOnWriteArrayList<>();

        @Override
        public RespostaDoLlm responder(PedidoAoLlm pedido) {
            transacaoAtiva.add(TransactionSynchronizationManager.isActualTransactionActive());
            try {
                TimeUnit.MILLISECONDS.sleep(DEMORA_DO_MODELO_EM_MS);
            } catch (InterruptedException interrompido) {
                Thread.currentThread().interrupt();
            }
            MensagemDeChat ultima = pedido.mensagens().get(pedido.mensagens().size() - 1);
            if ("tool".equals(ultima.papel())) {
                return new RespostaDoLlm(frasePrazo(marcador(pedido, PRAZO, true)), List.of(), null);
            }
            return new RespostaDoLlm(null, List.of(ProvedorRoteirizado.pedido(BUSCAR, busca(CONSULTA))), null);
        }
    }

    @Test
    void dozeTurnosSimultaneosComJevEModeloLentosTerminamSemSegurarConexaoNemTransacao() throws Exception {
        UsuarioAutenticado pessoa = usuario(jdbc, AMARELO);
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        ModeloQueBusca lento = new ModeloQueBusca();
        provedorAtual.usarChaveDoDebug(new ChaveDoDebug(lento, "http://127.0.0.1", "modelo-lento", false, "xxxx", null, null));
        config.ligar(PontoDoJev.INTENCAO, ModoDoJev.ATIVO).ligar(PontoDoJev.FONTES, ModoDoJev.ATIVO).ligar(PontoDoJev.RESPOSTA, ModoDoJev.ATIVO);
        jev.intencao("pergunta_documentos", 0.95).fontes(estado -> estado.contains(PRAZO) ? 0.9 : 0.05).resposta(frase -> "apoia", 0.97)
                .atrasar(DEMORA_DO_JEV_EM_MS);
        List<Long> conversas = new ArrayList<>();
        for (int i = 0; i < TURNOS; i++) {
            conversas.add(atlas.abrirConversa(pessoa, parceria, null, requisicao()).conversaId());
        }

        List<ConversaDto> respostas = new ArrayList<>();
        ExecutorService threads = Executors.newFixedThreadPool(TURNOS);
        try {
            List<Future<ConversaDto>> turnos = new ArrayList<>();
            for (long conversa : conversas) {
                turnos.add(threads.submit(() -> atlas.enviarMensagem(pessoa, conversa,
                        new MensagemRequisicao("Qual o prazo para informar os geradores próprios?", null, null), requisicao())));
            }
            for (Future<ConversaDto> turno : turnos) {
                respostas.add(turno.get(60, TimeUnit.SECONDS));
            }
        } finally {
            threads.shutdownNow();
        }

        // todos os turnos responderam o que o modelo escreveu depois de buscar, conferido pelo JEV, sem cair numa frase de falha
        assertThat(respostas).hasSize(TURNOS).allSatisfy(resposta -> {
            ItemTexto texto = (ItemTexto) resposta.itens().stream().filter(ItemTexto.class::isInstance).reduce((a, b) -> b).orElseThrow();
            assertThat(texto.texto()).contains(PRAZO);
            assertThat(texto.porModelo()).isTrue();
        });
        // os três pontos foram consultados em todos os turnos (a intenção uma vez, a relevância por trecho, a resposta uma vez)
        assertThat(jev.recebidasDe(PontoDoJev.INTENCAO)).hasSize(TURNOS);
        assertThat(jev.recebidasDe(PontoDoJev.FONTES)).hasSizeGreaterThanOrEqualTo(TURNOS * 2);
        assertThat(jev.recebidasDe(PontoDoJev.RESPOSTA)).hasSize(TURNOS);
        // nenhuma requisição ao JEV nem ida ao modelo com transação aberta (a espera de 2,4 s com uma conexão presa derrubaria os outros turnos)
        assertThat(jev.algumaComTransacaoAberta()).as("transação aberta durante uma requisição ao JEV").isFalse();
        assertThat(lento.transacaoAtiva).as("idas ao modelo").hasSize(TURNOS * 2).containsOnly(false);
        for (long conversa : conversas) {
            assertThat(jdbc.sql("SELECT turno_desde IS NULL FROM atlas_conversa WHERE id = :c").param("c", conversa).query(Boolean.class)
                    .single()).as("o turno da conversa " + conversa + " terminou").isTrue();
            assertThat(jdbc.sql("SELECT count(*) FROM atlas_decisao d JOIN atlas_mensagem m ON m.id = d.mensagem_id WHERE m.conversa_id = :c")
                    .param("c", conversa).query(Long.class).single()).as("as decisões do JEV foram gravadas").isGreaterThanOrEqualTo(4);
        }
    }
}
