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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * [ATLAS] [VALIDACAO] O {@code listar_entregas} pelo loop inteiro, no Atlas só chat (M5): o marcador {@code [R1]} passa na checagem e
 * vira fonte gravada como {@code REGISTRO}, o {@code [R9]} que não existe não passa, a data da entrega vale e outra não, e a
 * contagem das linhas vale como número. Com o modelo roteirizado. Não é {@code @Transactional}: o turno confirma de verdade, e o que
 * ficou (as entregas que o teste gravou e o que o Atlas gravou) é apagado no fim.
 */
@TesteComLlmRoteirizado
@TestPropertySource(properties = {"atlas.relatorio.ligado=false"})
class ListarEntregasNoChatTest {

    private static final String LISTAR = "listar_entregas";

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    JdbcClient jdbc;

    private ApoioDoFluxo.Limpeza limpeza;
    private long ultimaEntrega;

    @BeforeEach
    void guardarOQueJaExiste() {
        modelo.limpar();
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
        ultimaEntrega = jdbc.sql("SELECT coalesce(max(id), 0) FROM entrega").query(Long.class).single();
    }

    @AfterEach
    void apagarOQueOTesteConfirmou() {
        limpeza.apagar();
        jdbc.sql("DELETE FROM entrega WHERE id > :u").param("u", ultimaEntrega).update();
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

    String ultimaMensagemAoModelo() {
        MensagemDeChat ultima = modelo.ultimoPedido().mensagens().get(modelo.ultimoPedido().mensagens().size() - 1);
        return ultima.conteudo();
    }

    @Test
    void ofereceAFerramentaEmTodoEstadoMenosOCartaoAberto() {
        long conversa = abrir();
        modelo.texto("Certo.");

        falar(conversa, "Oi");

        List<String> nomes = ProvedorRoteirizado.nomesDasFerramentas(modelo.ultimoPedido());
        assertThat(nomes).contains(LISTAR);
        assertThat(nomes.indexOf(LISTAR)).as("depois de buscar_documentos e antes de contar_dias")
                .isEqualTo(nomes.indexOf("buscar_documentos") + 1).isLessThan(nomes.indexOf("contar_dias"));
    }

    @Test
    void oMarcadorR1PassaNaChecagemEViraFonteGravadaComoRegistroERelidaNaConversa() {
        long conversa = abrir();
        modelo.chamada(LISTAR, "{\"aba\":\"MIDIA\",\"status\":\"EM_ANALISE\"}").texto("Sua peça para mídia própria está em análise [R1].");

        ItemTexto texto = textoFinal(falar(conversa, "Como está a minha peça de mídia?"));

        assertThat(texto.texto()).isEqualTo("Sua peça para mídia própria está em análise [R1].");
        assertThat(texto.porModelo()).isTrue();
        assertThat(texto.fontes()).hasSize(1);
        FonteDoTexto fonte = texto.fontes().get(0);
        assertThat(fonte.marcador()).isEqualTo("R1");
        assertThat(fonte.tipo()).isEqualTo("REGISTRO");
        assertThat(fonte.titulo()).isEqualTo("Peça de 30\" para mídia própria");
        assertThat(fonte.secao()).isEqualTo("Mídia e conteúdo");
        assertThat(fonte.documentoId()).as("a entrega não veio de um documento").isNull();
        List<Map<String, Object>> gravadas = jdbc.sql("""
                SELECT f.marcador, f.tipo, f.registro_entidade, f.registro_id, f.dados ->> 'titulo' AS titulo
                FROM atlas_fonte f JOIN atlas_mensagem m ON m.id = f.mensagem_id WHERE m.conversa_id = :c
                """).param("c", conversa).query().listOfRows();
        long entrega = jdbc.sql("SELECT id FROM entrega WHERE titulo = 'Peça de 30\" para mídia própria'").query(Long.class).single();
        assertThat(gravadas).hasSize(1);
        assertThat(gravadas.get(0)).containsEntry("tipo", "REGISTRO").containsEntry("marcador", "R1")
                .containsEntry("registro_entidade", "entrega").containsEntry("registro_id", entrega)
                .containsEntry("titulo", "Peça de 30\" para mídia própria");
        assertThat(textoFinal(atlas.verConversa(usuario(jdbc, AMARELO), conversa)).fontes()).isEqualTo(texto.fontes());
    }

    @Test
    void citacaoQueNaoExisteNoTurnoERecusadaENovaRedacaoSegue() {
        long conversa = abrir();
        modelo.chamada(LISTAR, "{\"aba\":\"MIDIA\",\"status\":\"EM_ANALISE\"}").texto("Sua peça está em análise [R9].")
                .texto("Sua peça está em análise [R1].");

        ItemTexto texto = textoFinal(falar(conversa, "Como está a minha peça de mídia?"));

        assertThat(texto.texto()).isEqualTo("Sua peça está em análise [R1].");
        assertThat(ultimaMensagemAoModelo()).contains("[R9]", "não existe nos resultados deste turno", "R1");
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_fonte f JOIN atlas_mensagem m ON m.id = f.mensagem_id WHERE m.conversa_id = :c")
                .param("c", conversa).query(Long.class).single()).as("a R9 recusada não deixa fonte").isEqualTo(1L);
    }

    @Test
    void aDataDaEntregaValeComACitacaoEOutraDataNaoTemOrigem() {
        long amarelo = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        jdbc.sql("""
                INSERT INTO entrega (parceria_id, aba_id, titulo, status, prazo)
                VALUES (:p, (SELECT id FROM aba WHERE codigo = 'CONTRATO'), 'Contrato final de teste', 'AGUARDANDO_ENVIO', :d)
                """).param("p", amarelo).param("d", LocalDate.of(2026, 12, 4)).update();
        long conversa = abrir();
        modelo.chamada(LISTAR, "{\"aba\":\"CONTRATO\",\"status\":\"AGUARDANDO_ENVIO\"}").texto("O contrato vence em 05/12/2026 [R1].")
                .texto("O contrato vence em 04/12/2026 [R1].");

        ItemTexto texto = textoFinal(falar(conversa, "Quando vence o contrato?"));

        assertThat(texto.texto()).isEqualTo("O contrato vence em 04/12/2026 [R1].");
        assertThat(ultimaMensagemAoModelo()).contains("05/12/2026");
    }

    @Test
    void aContagemDasLinhasValeComoNumeroDaResposta() {
        long amarelo = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        for (int i = 1; i <= 2; i++) {
            jdbc.sql("""
                    INSERT INTO entrega (parceria_id, aba_id, titulo, status)
                    VALUES (:p, (SELECT id FROM aba WHERE codigo = 'MIDIA'), :t, 'EM_ANALISE')
                    """).param("p", amarelo).param("t", "Peça extra de teste " + i).update();
        }
        long conversa = abrir();
        modelo.chamada(LISTAR, "{\"status\":\"EM_ANALISE\"}").texto("Você tem 3 entregas em análise [R1] [R2] [R3].");

        ItemTexto texto = textoFinal(falar(conversa, "Quantas entregas estão em análise?"));

        assertThat(texto.texto()).isEqualTo("Você tem 3 entregas em análise [R1] [R2] [R3].");
        assertThat(texto.fontes()).extracting(FonteDoTexto::marcador).containsExactly("R1", "R2", "R3");
    }

    @Test
    void numeroQueNaoEDaContagemNemDeFonteNaoPassa() {
        long conversa = abrir();
        modelo.chamada(LISTAR, "{\"status\":\"EM_ANALISE\"}").texto("Você tem 7 entregas em análise [R1].")
                .texto("Você tem 1 entrega em análise [R1].");

        ItemTexto texto = textoFinal(falar(conversa, "Quantas entregas estão em análise?"));

        assertThat(texto.texto()).isEqualTo("Você tem 1 entrega em análise [R1].");
        assertThat(ultimaMensagemAoModelo()).contains("não tem origem");
    }

    @Test
    void parceriaNaoSeEscolhePorArgumentoEOErroVoltaAoModelo() {
        long conversa = abrir();
        modelo.chamada(LISTAR, "{\"parceria_id\":999}").texto("Não consegui consultar as entregas agora.");

        falar(conversa, "Quais são as minhas entregas?");

        String resultado = jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'tool'").param("c", conversa)
                .query(String.class).single();
        assertThat(resultado).contains("erro", "parceria_id");
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_fonte f JOIN atlas_mensagem m ON m.id = f.mensagem_id WHERE m.conversa_id = :c")
                .param("c", conversa).query(Long.class).single()).isZero();
    }
}
