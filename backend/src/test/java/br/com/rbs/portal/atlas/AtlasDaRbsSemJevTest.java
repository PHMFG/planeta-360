package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.RBS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemTexto;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.jev.ModoDoJev;
import br.com.rbs.portal.atlas.loop.LoopDoAgente;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * [ATLAS] [ISOLAMENTO] O JEV está desligado na conversa da RBS (M8): com os seis pontos em {@code ATIVO} e o JEV roteirizado para
 * agir (recusar, tirar todo trecho, contradizer toda frase), um turno da RBS não faz uma requisição a ele, não grava decisão e segue
 * o caminho do modelo; o mesmo JEV, na conversa da marca, é consultado e age. O que a RBS escreve, portanto, nunca vai ao JEV. Não é
 * {@code @Transactional}: o que o teste confirmou é apagado no fim.
 */
@TesteComJevRoteirizado
@TestPropertySource(properties = {"atlas.rbs.ligado=true", "atlas.diagnostico.ligado=true", "atlas.prazo-do-resumo=PT0.5S"})
class AtlasDaRbsSemJevTest {

    @Autowired
    AtlasService atlas;

    @Autowired
    AtlasDaRbsService rbs;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    JevRoteirizado jev;

    @Autowired
    ConfiguracaoDoJevDeTeste config;

    @Autowired
    JdbcClient jdbc;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void preparar() {
        modelo.limpar();
        // o JEV age em tudo: se fosse consultado na conversa da RBS, a recusa, o corte dos trechos e a contradição apareceriam
        jev.limpar().intencao("fora_de_escopo", 0.99).fontes(estado -> 0.0).resposta(frase -> "contradiz", 0.99);
        config.limpar().ligarTodos(ModoDoJev.ATIVO);
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagar() {
        config.limpar();
        limpeza.apagar();
    }

    long evento2027() {
        return jdbc.sql("SELECT id FROM evento WHERE edicao = 2027").query(Long.class).single();
    }

    long conversaDaRbs() {
        return rbs.abrir(usuario(jdbc, RBS), evento2027()).conversaId();
    }

    ConversaDto falarComoRbs(long conversa, String texto) {
        return atlas.enviarMensagem(usuario(jdbc, RBS), conversa, new MensagemRequisicao(texto, null, null), requisicao());
    }

    static ItemTexto texto(ConversaDto conversa) {
        return conversa.itens().stream().filter(ItemTexto.class::isInstance).map(ItemTexto.class::cast).reduce((a, b) -> b).orElseThrow();
    }

    long decisoes() {
        return jdbc.sql("SELECT count(*) FROM atlas_decisao").query(Long.class).single();
    }

    @Test
    void aMensagemForaDeEscopoDaRbsNaoVaiAoJevEChegaAoModelo() {
        long conversa = conversaDaRbs();
        long antes = decisoes();
        modelo.texto("Posso ajudar com os documentos da edição.");

        ConversaDto resposta = falarComoRbs(conversa, "Qual é a receita de bolo de cenoura?");

        assertThat(jev.recebidas()).as("nenhuma requisição ao JEV").isEmpty();
        assertThat(modelo.pedidos()).as("o modelo respondeu, não a recusa fixa do JEV").hasSize(1);
        assertThat(texto(resposta).texto()).isEqualTo("Posso ajudar com os documentos da edição.");
        assertThat(texto(resposta).texto()).isNotEqualTo(LoopDoAgente.RECUSA_DE_FORA_DE_ESCOPO);
        assertThat(decisoes()).isEqualTo(antes);
        assertThat(ProvedorRoteirizado.nomesDasFerramentas(modelo.ultimoPedido())).containsExactly("buscar_documentos", "listar_entregas",
                "consultar_midia", "contar_dias", "calcular", "gerar_modelo");   // sem o subconjunto de nenhum ramo do JEV
    }

    @Test
    void aBuscaDaRbsNaoPassaPeloJevNemOTextoPorOutraConferencia() {
        long conversa = conversaDaRbs();
        long antes = decisoes();
        modelo.chamada("buscar_documentos", FontesNoChatTest.busca("prazo para informar os geradores próprios"));
        modelo.passo(pedido -> new RespostaDoLlm("Os geradores próprios devem ser informados à produção até 04/12/2026 ["
                + FontesNoChatTest.marcador(pedido, "04/12/2026", true) + "].", List.of(), null));

        ConversaDto resposta = falarComoRbs(conversa, "Até quando informo os geradores?");

        assertThat(jev.recebidas()).isEmpty();
        assertThat(texto(resposta).texto()).matches("Os geradores próprios devem ser informados à produção até 04/12/2026 \\[T[1-9]\\]\\.");
        assertThat(texto(resposta).fontes()).hasSize(1);
        assertThat(decisoes()).isEqualTo(antes);
        // nenhuma etapa do JEV no traço
        assertThat(jdbc.sql("""
                SELECT count(*) FROM atlas_traco WHERE conversa_id = :c AND etapa IN ('J1_INTENCAO', 'J1_CARTAO', 'R3_FONTES', 'R5_RESPOSTA',
                                                                                       'V2_COERENCIA', 'LACUNAS')
                """).param("c", conversa).query(Long.class).single()).isZero();
    }

    @Test
    void omesmoJevNaConversaDaMarcaEConsultadoEAge() {
        long conversaDaMarca = atlas.abrirConversa(usuario(jdbc, AMARELO), SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"), null,
                requisicao()).conversaId();

        ConversaDto resposta = atlas.enviarMensagem(usuario(jdbc, AMARELO), conversaDaMarca,
                new MensagemRequisicao("Qual é a receita de bolo de cenoura?", null, null), requisicao());

        assertThat(jev.recebidasDe(br.com.rbs.portal.atlas.jev.PontoDoJev.INTENCAO)).as("o JEV foi consultado na conversa da marca").hasSize(1);
        assertThat(texto(resposta).texto()).isEqualTo(LoopDoAgente.RECUSA_DE_FORA_DE_ESCOPO);
        assertThat(modelo.pedidos()).isEmpty();
    }
}
