package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.FonteDoTexto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemTexto;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.MensagemDeChat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * [ATLAS] [VALIDACAO] O {@code contar_dias} pelo loop inteiro, no Atlas só chat (M5): o resultado da conta vale na resposta, a
 * citação {@code [C1]} passa na checagem e vira fonte do item, a {@code [C9]} que não existe não passa, e uma data que a conta não
 * deu não tem origem. Com o modelo roteirizado. Não é {@code @Transactional}: o turno confirma de verdade e o que ficou é apagado no fim.
 */
@TesteComLlmRoteirizado
@TestPropertySource(properties = {"atlas.relatorio.ligado=false"})
class ContarDiasNoChatTest {

    private static final String DIAS = "contar_dias";
    private static final String DEZOITO_UTEIS = "{\"inicio\":\"2026-11-03\",\"dias\":18,\"uteis\":true}";

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    JdbcClient jdbc;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void guardarOQueJaExiste() {
        modelo.limpar();
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagarOQueOTesteConfirmou() {
        limpeza.apagar();
    }

    long abrir() {
        return atlas.abrirConversa(usuario(jdbc, AMARELO), SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"), null, requisicao())
                .conversaId();
    }

    ConversaDto falar(long conversa, String texto) {
        return atlas.enviarMensagem(usuario(jdbc, AMARELO), conversa, new MensagemRequisicao(texto, null, null), requisicao());
    }

    static ItemTexto textoFinal(ConversaDto conversa) {
        List<ItemTexto> textos = conversa.itens().stream().filter(ItemTexto.class::isInstance).map(ItemTexto.class::cast).toList();
        assertThat(textos).as("o turno produziu um texto").isNotEmpty();
        return textos.get(textos.size() - 1);
    }

    @Test
    void ofereceAFerramentaEmTodoEstadoMenosOCartaoAberto() {
        long conversa = abrir();
        modelo.texto("Certo.");

        falar(conversa, "Oi");

        assertThat(ProvedorRoteirizado.nomesDasFerramentas(modelo.ultimoPedido())).contains(DIAS);
    }

    @Test
    void oResultadoDaContagemVaiNaRespostaComACitacaoEViraFonteDoItem() {
        long conversa = abrir();
        modelo.chamada(DIAS, DEZOITO_UTEIS).texto("O prazo termina em 30/11/2026 [C1], já fora o feriado de 20/11/2026.");

        ItemTexto texto = textoFinal(falar(conversa, "Quando vencem 18 dias úteis depois de 03/11/2026?"));

        assertThat(texto.texto()).isEqualTo("O prazo termina em 30/11/2026 [C1], já fora o feriado de 20/11/2026.");
        assertThat(texto.porModelo()).isTrue();
        assertThat(texto.fontes()).hasSize(1);
        FonteDoTexto fonte = texto.fontes().get(0);
        assertThat(fonte.marcador()).isEqualTo("C1");
        assertThat(fonte.tipo()).isEqualTo("CALCULO");
        assertThat(fonte.titulo()).isEqualTo("Contagem de dias");
        List<Map<String, Object>> gravadas = jdbc.sql("""
                SELECT f.marcador, f.tipo, f.dados ->> 'resultado' AS resultado, f.dados ->> 'ferramenta' AS ferramenta
                FROM atlas_fonte f JOIN atlas_mensagem m ON m.id = f.mensagem_id WHERE m.conversa_id = :c
                """).param("c", conversa).query().listOfRows();
        assertThat(gravadas).hasSize(1);
        assertThat(gravadas.get(0)).containsEntry("tipo", "CALCULO").containsEntry("marcador", "C1")
                .containsEntry("resultado", "30/11/2026").containsEntry("ferramenta", "contar_dias");
    }

    @Test
    void oResultadoDaContaValeMesmoSemACitacaoNaFrase() {
        long conversa = abrir();
        modelo.chamada(DIAS, DEZOITO_UTEIS).texto("Dá 30/11/2026.");

        ItemTexto texto = textoFinal(falar(conversa, "Quando vencem 18 dias úteis depois de 03/11/2026?"));

        assertThat(texto.texto()).isEqualTo("Dá 30/11/2026.");
        assertThat(texto.fontes()).isEmpty();
    }

    @Test
    void citacaoQueNaoExisteNoTurnoERecusadaENovaRedacaoSegue() {
        long conversa = abrir();
        modelo.chamada(DIAS, DEZOITO_UTEIS).texto("O prazo termina em 30/11/2026 [C9].").texto("O prazo termina em 30/11/2026 [C1].");

        ItemTexto texto = textoFinal(falar(conversa, "Quando vencem 18 dias úteis depois de 03/11/2026?"));

        assertThat(texto.texto()).isEqualTo("O prazo termina em 30/11/2026 [C1].");
        MensagemDeChat pedidoDeNovaRedacao = modelo.ultimoPedido().mensagens().get(modelo.ultimoPedido().mensagens().size() - 1);
        assertThat(pedidoDeNovaRedacao.conteudo()).contains("[C9]", "não existe nos resultados deste turno", "C1");
    }

    @Test
    void dataQueAContagemNaoDeuNaoTemOrigem() {
        long conversa = abrir();
        modelo.chamada(DIAS, DEZOITO_UTEIS).texto("O prazo termina em 01/12/2026 [C1].").texto("O prazo termina em 30/11/2026 [C1].");

        ItemTexto texto = textoFinal(falar(conversa, "Quando vencem 18 dias úteis depois de 03/11/2026?"));

        assertThat(texto.texto()).isEqualTo("O prazo termina em 30/11/2026 [C1].");
        assertThat(modelo.ultimoPedido().mensagens().get(modelo.ultimoPedido().mensagens().size() - 1).conteudo()).contains("01/12/2026");
    }

    @Test
    void citacaoDeContaDeUmTurnoAnteriorNaoVale() {
        long conversa = abrir();
        modelo.chamada(DIAS, DEZOITO_UTEIS).texto("O prazo termina em 30/11/2026 [C1].");
        falar(conversa, "Quando vencem 18 dias úteis depois de 03/11/2026?");

        // no turno seguinte o C1 recomeça: sem chamar a ferramenta de novo, [C1] não existe
        modelo.limpar();
        modelo.texto("Repito: 30/11/2026 [C1].").texto("Não consegui responder isso agora.");
        ItemTexto texto = textoFinal(falar(conversa, "E de novo?"));

        assertThat(texto.fontes()).isEmpty();
        assertThat(texto.texto()).isNotEqualTo("Repito: 30/11/2026 [C1].");
    }

    @Test
    void aContagemNaoFabricaUmNumeroQueNingemDisse() {
        long conversa = abrir();
        // dias 312 a partir de 01/01: nem o 312 nem o 01/01 estão em lugar nenhum; "R$ 312,00 [C1]" nunca teria origem
        modelo.chamada(DIAS, "{\"inicio\":\"2026-01-01\",\"dias\":312}").texto("A multa é de R$ 312,00 [C1].")
                .texto("Não consegui responder isso agora.");

        ItemTexto texto = textoFinal(falar(conversa, "Qual é a multa?"));

        assertThat(texto.texto()).isEqualTo("Não consegui responder isso agora.");
        String resultado = jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'tool'").param("c", conversa)
                .query(String.class).single();
        assertThat(resultado).contains("erro", "dias", "inicio");
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_fonte f JOIN atlas_mensagem m ON m.id = f.mensagem_id WHERE m.conversa_id = :c")
                .param("c", conversa).query(Long.class).single()).isZero();
        // e o resultado de 01/01 até 09/11 (312 dias) também não vira número da resposta
        modelo.limpar();
        modelo.chamada(DIAS, "{\"inicio\":\"2026-01-01\",\"fim\":\"2026-11-09\"}").texto("São 312 dias [C1].").texto("Não sei dizer agora.");
        assertThat(textoFinal(falar(conversa, "Quantos dias passaram?")).texto()).isEqualTo("Não sei dizer agora.");
    }

    @Test
    void erroDeArgumentoVoltaAoModeloEOMarcadorNaoExiste() {
        long conversa = abrir();
        modelo.chamada(DIAS, "{\"inicio\":\"2026-02-30\",\"dias\":18}").texto("O prazo termina em 30/11/2026 [C1].")
                .texto("Não consegui responder isso agora.");

        falar(conversa, "Quando vencem 18 dias depois de 30/02/2026?");

        String resultado = jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'tool'").param("c", conversa)
                .query(String.class).single();
        assertThat(resultado).contains("erro", "inicio");
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_fonte f JOIN atlas_mensagem m ON m.id = f.mensagem_id WHERE m.conversa_id = :c")
                .param("c", conversa).query(Long.class).single()).isZero();
    }
}
