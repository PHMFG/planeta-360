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
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.prazos.ApoioDePrazos;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * [ATLAS] [VALIDACAO] O {@code consultar_prazos} pelo loop inteiro, no Atlas só chat (M5): o prazo citado com {@code [R1]} passa na
 * checagem e vira fonte gravada como {@code REGISTRO}, com o documento e a página de origem; a {@code [R9]} que não existe, uma data
 * que a consulta não deu e os dias que não são os de {@code dias_restantes} não passam; e o resultado de um turno anterior vai ao
 * modelo sem o texto. Com o modelo roteirizado. Não é {@code @Transactional}: o turno confirma de verdade, e o que ficou (os prazos
 * que o teste gravou e o que o Atlas gravou) é apagado no fim.
 */
@TesteComLlmRoteirizado
@TestPropertySource(properties = {"atlas.relatorio.ligado=false"})
class ConsultarPrazosNoChatTest {

    private static final String PRAZOS = "consultar_prazos";
    private static final String MANUAL = "Manual de Operações 2027";
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    JdbcClient jdbc;

    private ApoioDoFluxo.Limpeza limpeza;
    private long ultimoPrazo;
    private LocalDate hoje;

    @BeforeEach
    void guardarOQueJaExiste() {
        modelo.limpar();
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
        ultimoPrazo = ApoioDePrazos.ultimoId(jdbc);
        hoje = Datas.hoje();
    }

    @AfterEach
    void apagarOQueOTesteConfirmou() {
        limpeza.apagar();
        ApoioDePrazos.apagarDepoisDe(jdbc, ultimoPrazo);
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

    long manual() {
        return ApoioDePrazos.documento(jdbc, MANUAL, 2);
    }

    /** Um prazo da edição inteira daqui a 10 dias, às 18h, que vem do manual de 2027 (v2), página 5. Devolve a data escrita. */
    String prazoDoManual() {
        ApoioDePrazos.inserir(jdbc, "GLOBAL", null, null, "Informar os geradores próprios", hoje.plusDays(10), LocalTime.of(18, 0),
                "Informar à produção os geradores próprios.", manual(), 5);
        return DIA.format(hoje.plusDays(10));
    }

    @Test
    void ofereceAFerramentaDepoisDaCotaEAntesDasContasEmTodoEstadoMenosOCartaoAberto() {
        long conversa = abrir();
        modelo.texto("Certo.");

        falar(conversa, "Oi");

        List<String> nomes = ProvedorRoteirizado.nomesDasFerramentas(modelo.ultimoPedido());
        assertThat(nomes).containsSubsequence("buscar_documentos", "listar_entregas", "consultar_cota", PRAZOS, "contar_dias", "calcular");
        assertThat(nomes.indexOf(PRAZOS)).isEqualTo(nomes.indexOf("consultar_cota") + 1);
    }

    @Test
    void oMarcadorR1PassaNaChecagemEViraFonteGravadaComODocumentoEAPaginaERelidaNaConversa() {
        String data = prazoDoManual();
        long conversa = abrir();
        modelo.chamada(PRAZOS, "{}").texto("Os geradores próprios devem ser informados até " + data + " [R1].");

        ItemTexto texto = textoFinal(falar(conversa, "Até quando informo os geradores?"));

        assertThat(texto.texto()).isEqualTo("Os geradores próprios devem ser informados até " + data + " [R1].");
        assertThat(texto.porModelo()).isTrue();
        assertThat(texto.fontes()).hasSize(1);
        FonteDoTexto fonte = texto.fontes().get(0);
        assertThat(fonte.marcador()).isEqualTo("R1");
        assertThat(fonte.tipo()).isEqualTo("REGISTRO");
        assertThat(fonte.titulo()).isEqualTo("Informar os geradores próprios");
        assertThat(fonte.documentoId()).isEqualTo(manual());
        assertThat(fonte.versao()).isEqualTo(2);
        assertThat(fonte.pagina()).isEqualTo(5);
        assertThat(fonte.substituido()).isFalse();
        assertThat(fonte.revogado()).isFalse();
        Map<String, Object> gravada = jdbc.sql("""
                SELECT f.marcador, f.tipo, f.registro_entidade, f.registro_id, f.documento_id, f.versao, f.pagina, f.trecho_id,
                       f.dados ->> 'titulo' AS titulo, f.dados ->> 'documentoId' AS documento, f.dados ->> 'pagina' AS pagina_em_dados
                FROM atlas_fonte f JOIN atlas_mensagem m ON m.id = f.mensagem_id WHERE m.conversa_id = :c
                """).param("c", conversa).query().singleRow();
        long prazo = jdbc.sql("SELECT id FROM prazo WHERE titulo = 'Informar os geradores próprios'").query(Long.class).single();
        assertThat(gravada).containsEntry("marcador", "R1").containsEntry("tipo", "REGISTRO").containsEntry("registro_entidade", "prazo")
                .containsEntry("registro_id", prazo).containsEntry("titulo", "Informar os geradores próprios")
                .containsEntry("documento", String.valueOf(manual())).containsEntry("pagina_em_dados", "5")
                .containsEntry("documento_id", null).containsEntry("versao", null).containsEntry("pagina", null).containsEntry("trecho_id", null);
        assertThat(textoFinal(atlas.verConversa(usuario(jdbc, AMARELO), conversa)).fontes()).isEqualTo(texto.fontes());
    }

    @Test
    void citacaoQueNaoExisteNoTurnoERecusadaENovaRedacaoSegue() {
        String data = prazoDoManual();
        long conversa = abrir();
        modelo.chamada(PRAZOS, "{}").texto("Os geradores devem ser informados até " + data + " [R9].")
                .texto("Os geradores devem ser informados até " + data + " [R1].");

        ItemTexto texto = textoFinal(falar(conversa, "Até quando informo os geradores?"));

        assertThat(texto.texto()).isEqualTo("Os geradores devem ser informados até " + data + " [R1].");
        assertThat(ultimaMensagemAoModelo()).contains("[R9]", "não existe nos resultados deste turno", "R1");
    }

    @Test
    void dataQueAConsultaNaoDeuNaoTemOrigem() {
        String data = prazoDoManual();
        String outra = DIA.format(hoje.plusDays(11));
        long conversa = abrir();
        modelo.chamada(PRAZOS, "{}").texto("Os geradores devem ser informados até " + outra + " [R1].")
                .texto("Os geradores devem ser informados até " + data + " [R1].");

        ItemTexto texto = textoFinal(falar(conversa, "Até quando informo os geradores?"));

        assertThat(texto.texto()).isEqualTo("Os geradores devem ser informados até " + data + " [R1].");
        assertThat(ultimaMensagemAoModelo()).contains(outra, "não tem origem");
    }

    @Test
    void aHoraEOsDiasQueFaltamValemQuandoSaemDoRegistroCitado() {
        String data = prazoDoManual();
        long conversa = abrir();
        modelo.chamada(PRAZOS, "{}").texto("Faltam 10 dias, e o prazo vai até as 18h do dia " + data + " [R1].");

        ItemTexto texto = textoFinal(falar(conversa, "Quanto falta para informar os geradores?"));

        assertThat(texto.texto()).isEqualTo("Faltam 10 dias, e o prazo vai até as 18h do dia " + data + " [R1].");
        assertThat(texto.fontes()).extracting(FonteDoTexto::marcador).containsExactly("R1");
    }

    @Test
    void diasQueNaoSaoOsDoRegistroNaoTemOrigem() {
        String data = prazoDoManual();
        long conversa = abrir();
        modelo.chamada(PRAZOS, "{}").texto("Faltam 12 dias para o prazo de " + data + " [R1].")
                .texto("Faltam 10 dias para o prazo de " + data + " [R1].");

        ItemTexto texto = textoFinal(falar(conversa, "Quanto falta para informar os geradores?"));

        assertThat(texto.texto()).isEqualTo("Faltam 10 dias para o prazo de " + data + " [R1].");
        assertThat(ultimaMensagemAoModelo()).contains("12", "não tem origem");
    }

    @Test
    void aContagemDosPrazosValeComoNumeroDaResposta() {
        prazoDoManual();
        ApoioDePrazos.global(jdbc, "Entregar a apólice", hoje.plusDays(3), null, null);
        long conversa = abrir();
        modelo.chamada(PRAZOS, "{}").texto("Há 2 prazos nos próximos dias [R1] [R2].");

        ItemTexto texto = textoFinal(falar(conversa, "Quais são os meus prazos?"));

        assertThat(texto.texto()).isEqualTo("Há 2 prazos nos próximos dias [R1] [R2].");
        assertThat(texto.fontes()).extracting(FonteDoTexto::titulo).containsExactly("Entregar a apólice", "Informar os geradores próprios");
    }

    @Test
    void oResultadoDeUmTurnoAnteriorVaiAoModeloSemOTextoESoComODocumentoEAVersao() {
        String data = prazoDoManual();
        long conversa = abrir();
        modelo.chamada(PRAZOS, "{}").texto("Os geradores devem ser informados até " + data + " [R1].");
        falar(conversa, "Até quando informo os geradores?");
        modelo.limpar();
        modelo.texto("Certo, é só isso.");

        falar(conversa, "Obrigado!");

        List<MensagemDeChat> historico = modelo.pedidos().get(0).mensagens();
        String resultadoVelho = historico.stream().filter(m -> m.papel().equals("tool") && "consultar_prazos".equals(m.ferramenta()))
                .map(MensagemDeChat::conteudo).findFirst().orElseThrow();
        assertThat(resultadoVelho).contains("resultado_de_turno_anterior", "busque de novo para citar", MANUAL)
                .doesNotContain(data, "Informar os geradores próprios", "dias_restantes", "marcador", "18:00", "registros");
        ProvedorRoteirizado.exigirHistoricoValido(modelo.pedidos().get(0));
        // a atlas_tool_call e a atlas_fonte guardam o original
        assertThat(jdbc.sql("""
                SELECT t.resultado::text FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id
                WHERE m.conversa_id = :c AND t.ferramenta = 'consultar_prazos'
                """).param("c", conversa).query(String.class).single()).contains(data, "Informar os geradores próprios");
    }

    @Test
    void citacaoDeUmTurnoAnteriorNaoValeNoSeguinte() {
        String data = prazoDoManual();
        long conversa = abrir();
        modelo.chamada(PRAZOS, "{}").texto("Os geradores devem ser informados até " + data + " [R1].");
        falar(conversa, "Até quando informo os geradores?");
        modelo.limpar();
        modelo.texto("Repito: até " + data + " [R1].").texto("Não consegui responder isso agora.");

        ItemTexto texto = textoFinal(falar(conversa, "E de novo?"));

        assertThat(texto.fontes()).isEmpty();
        assertThat(texto.texto()).doesNotContain(data);
    }

    @Test
    void proximosDiasForaDaFaixaEParceriaPorArgumentoVoltamAoModeloComoErro() {
        long conversa = abrir();
        modelo.chamada(PRAZOS, "{\"proximos_dias\":181}").chamada(PRAZOS, "{\"parceria_id\":1}").texto("Não consegui consultar os prazos agora.");

        falar(conversa, "Quais são os meus prazos?");

        List<String> resultados = jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'tool' ORDER BY ordem")
                .param("c", conversa).query(String.class).list();
        assertThat(resultados).hasSize(2);
        assertThat(resultados.get(0)).contains("erro", "proximos_dias", "1 a 180");
        assertThat(resultados.get(1)).contains("erro", "parceria_id");
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_fonte f JOIN atlas_mensagem m ON m.id = f.mensagem_id WHERE m.conversa_id = :c")
                .param("c", conversa).query(Long.class).single()).isZero();
    }

    @Test
    void prazoDeOutraMarcaNuncaChegaAoModelo() {
        ApoioDePrazos.daParceria(jdbc, "Cliente Roxo", "PRAZO-SECRETO-DO-ROXO", hoje.plusDays(2), null, null);
        ApoioDePrazos.daCota(jdbc, "SUBCOTA_G", "PRAZO-SECRETO-DA-SUBCOTA-G", hoje.plusDays(2), null, null);
        ApoioDePrazos.global(jdbc, "Prazo de todas as marcas", hoje.plusDays(2), null, null);
        long conversa = abrir();
        modelo.chamada(PRAZOS, "{\"proximos_dias\":180,\"incluir_vencidos\":true}").texto("Certo.");

        falar(conversa, "Quais são os meus prazos?");

        String resultado = jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'tool'").param("c", conversa)
                .query(String.class).single();
        assertThat(resultado).contains("Prazo de todas as marcas").doesNotContain("PRAZO-SECRETO");
        assertThat(jdbc.sql("SELECT resultado::text FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id WHERE m.conversa_id = :c")
                .param("c", conversa).query(String.class).single()).doesNotContain("PRAZO-SECRETO");
    }
}
