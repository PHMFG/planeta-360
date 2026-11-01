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
 * [ATLAS] [VALIDACAO] O {@code consultar_cota} pelo loop inteiro, no Atlas só chat (M5): a cota e o previsto do plano são citáveis
 * com {@code [R#]}, o número de uma linha só vale com a citação dela, a fonte do previsto leva o documento do plano e a página, e o
 * {@code calcular} lê a unidade da fonte (não mistura inserção com aplicação). Com o modelo roteirizado. Não é {@code @Transactional}:
 * o turno confirma de verdade e o que ficou é apagado no fim.
 */
@TesteComLlmRoteirizado
@TestPropertySource(properties = {"atlas.relatorio.ligado=false"})
class ConsultarCotaNoChatTest {

    private static final String COTA = "consultar_cota";
    private static final String CALCULAR = "calcular";

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

    String ultimaMensagemAoModelo() {
        return modelo.ultimoPedido().mensagens().get(modelo.ultimoPedido().mensagens().size() - 1).conteudo();
    }

    long linhasDeMidiaDoAmarelo() {
        return jdbc.sql("SELECT count(*) FROM aproveitamento_previsto WHERE parceria_id = :p AND categoria = 'MIDIA'")
                .param("p", SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo")).query(Long.class).single();
    }

    @Test
    void ofereceAFerramentaEntreAsDeConsultaEmTodoEstadoMenosOCartaoAberto() {
        long conversa = abrir();
        modelo.texto("Certo.");

        falar(conversa, "Oi");

        List<String> nomes = ProvedorRoteirizado.nomesDasFerramentas(modelo.ultimoPedido());
        assertThat(nomes).containsSubsequence("buscar_documentos", "listar_entregas", COTA, "contar_dias");
        assertThat(nomes.indexOf(COTA)).isEqualTo(nomes.indexOf("listar_entregas") + 1);
    }

    @Test
    void oResultadoDaCotaEDasEntregasDeUmTurnoAnteriorVaiSemOsRegistrosMesmoSeAAbaFechou() {
        long conversa = abrir();
        modelo.chamada("listar_entregas", "{\"aba\":\"MIDIA\"}").chamada(COTA, "{}").texto("Vi as entregas e a cota.");
        falar(conversa, "O que tenho em mídia?");
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        String fechar = "UPDATE parceria_aba SET liberada = :liberada WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'MIDIA')";
        jdbc.sql(fechar).param("liberada", false).param("p", parceria).update();
        try {
            modelo.limpar();
            modelo.texto("Certo.");

            falar(conversa, "Obrigado!");

            List<MensagemDeChat> historico = modelo.pedidos().get(0).mensagens();
            List<MensagemDeChat> antigos = historico.stream().filter(m -> m.papel().equals("tool")).toList();
            assertThat(antigos).extracting(MensagemDeChat::ferramenta).containsExactly("listar_entregas", COTA);
            for (MensagemDeChat antigo : antigos) {
                assertThat(antigo.conteudo()).contains("resultado_de_turno_anterior", "consulte de novo para citar")
                        .doesNotContain("MIDIA", "\"aba\"", "\"categoria\"", "\"tipo\":\"cortesia\"", "marcador", "registros");
            }
            ProvedorRoteirizado.exigirHistoricoValido(modelo.pedidos().get(0));
            // a atlas_tool_call guarda o original
            assertThat(jdbc.sql("SELECT count(*) FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id WHERE m.conversa_id = :c "
                    + "AND t.ferramenta IN ('listar_entregas', 'consultar_cota') AND t.resultado::text LIKE '%MIDIA%'").param("c", conversa)
                    .query(Long.class).single()).isEqualTo(2L);
        } finally {
            jdbc.sql(fechar).param("liberada", true).param("p", parceria).update();
        }
    }

    @Test
    void aCotaECitadaComR1EViraFonteGravadaComoRegistroDaParceria() {
        long conversa = abrir();
        modelo.chamada(COTA, "{}").texto("A sua cota é a Master, a Cota Patrocínio Master [R1].");

        ItemTexto texto = textoFinal(falar(conversa, "Qual é a minha cota?"));

        assertThat(texto.texto()).isEqualTo("A sua cota é a Master, a Cota Patrocínio Master [R1].");
        FonteDoTexto fonte = texto.fontes().get(0);
        assertThat(fonte.marcador()).isEqualTo("R1");
        assertThat(fonte.tipo()).isEqualTo("REGISTRO");
        assertThat(fonte.titulo()).isEqualTo("Cota da parceria");
        Map<String, Object> gravada = jdbc.sql("""
                SELECT f.registro_entidade, f.registro_id FROM atlas_fonte f JOIN atlas_mensagem m ON m.id = f.mensagem_id WHERE m.conversa_id = :c
                """).param("c", conversa).query().singleRow();
        assertThat(gravada).containsEntry("registro_entidade", "parceria")
                .containsEntry("registro_id", SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"));
    }

    @Test
    void citacaoQueNaoExisteNoTurnoERecusadaENovaRedacaoSegue() {
        long conversa = abrir();
        modelo.chamada(COTA, "{}").texto("A sua cota é a Master [R99].").texto("A sua cota é a Master [R1].");

        ItemTexto texto = textoFinal(falar(conversa, "Qual é a minha cota?"));

        assertThat(texto.texto()).isEqualTo("A sua cota é a Master [R1].");
        assertThat(ultimaMensagemAoModelo()).contains("[R99]", "não existe nos resultados deste turno", "R1");
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_fonte f JOIN atlas_mensagem m ON m.id = f.mensagem_id WHERE m.conversa_id = :c")
                .param("c", conversa).query(Long.class).single()).as("a R9 recusada não deixa fonte").isEqualTo(1L);
    }

    @Test
    void oPrevistoCitadoVaiComODocumentoDoPlanoEAPaginaNaFonteELidoDeNovoNaConversa() {
        long conversa = abrir();
        modelo.chamada(COTA, "{}").texto("O plano prevê 300 chamadas assinadas na RBS TV [R3].");

        ItemTexto texto = textoFinal(falar(conversa, "Quantas chamadas assinadas eu tenho na RBS TV?"));

        long plano = jdbc.sql("SELECT id FROM documento WHERE titulo = 'Plano comercial · Cota Master 2027' AND versao = 1").query(Long.class).single();
        FonteDoTexto fonte = texto.fontes().get(0);
        assertThat(fonte.marcador()).isEqualTo("R3");
        assertThat(fonte.titulo()).isEqualTo("RBS TV · Chamadas assinadas");
        assertThat(fonte.documentoId()).isEqualTo(plano);
        assertThat(fonte.versao()).isEqualTo(1);
        // a linha vem do cadastro com fonte_pagina 6, e o plano da Master tem 5 páginas: sem página, em vez de dizer 1
        assertThat(fonte.pagina()).isNull();
        assertThat(fonte.substituido()).isFalse();
        assertThat(fonte.revogado()).isFalse();
        Map<String, Object> dados = jdbc.sql("""
                SELECT f.dados ->> 'unidade' AS unidade, f.dados ->> 'documentoId' AS documento, f.dados ->> 'pagina' AS pagina,
                       f.documento_id, f.pagina AS coluna_pagina
                FROM atlas_fonte f JOIN atlas_mensagem m ON m.id = f.mensagem_id WHERE m.conversa_id = :c
                """).param("c", conversa).query().singleRow();
        assertThat(dados).containsEntry("unidade", "INSERCAO").containsEntry("documento", String.valueOf(plano)).containsEntry("pagina", null)
                .containsEntry("documento_id", null).containsEntry("coluna_pagina", null);
        assertThat(textoFinal(atlas.verConversa(usuario(jdbc, AMARELO), conversa)).fontes()).isEqualTo(texto.fontes());
    }

    @Test
    void numeroQueNaoEDaLinhaCitadaNaoTemOrigem() {
        long conversa = abrir();
        // o 10 é da linha seguinte (R4: vinhetas de bloco), mas a frase cita a R3
        modelo.chamada(COTA, "{}").texto("O plano prevê 10 chamadas assinadas na RBS TV [R3].")
                .texto("O plano prevê 300 chamadas assinadas na RBS TV [R3].");

        ItemTexto texto = textoFinal(falar(conversa, "Quantas chamadas assinadas eu tenho na RBS TV?"));

        assertThat(texto.texto()).isEqualTo("O plano prevê 300 chamadas assinadas na RBS TV [R3].");
        assertThat(ultimaMensagemAoModelo()).contains("não tem origem", "10");
    }

    @Test
    void numeroInventadoNaoTemOrigemEmNenhumaLinha() {
        long conversa = abrir();
        modelo.chamada(COTA, "{}").texto("O plano prevê 301 chamadas assinadas na RBS TV [R3].")
                .texto("O plano prevê 300 chamadas assinadas na RBS TV [R3].");

        ItemTexto texto = textoFinal(falar(conversa, "Quantas chamadas assinadas eu tenho na RBS TV?"));

        assertThat(texto.texto()).isEqualTo("O plano prevê 300 chamadas assinadas na RBS TV [R3].");
        assertThat(ultimaMensagemAoModelo()).contains("não tem origem", "301");
    }

    @Test
    void oCalcularLeAUnidadeDaFonteESomaLinhasDaMesmaUnidade() {
        long conversa = abrir();
        modelo.chamada(COTA, "{}").chamada(CALCULAR, "{\"nome\":\"total\",\"script\":\"a + b\",\"variaveis\":{"
                + "\"a\":{\"fonte\":\"R3\",\"valor\":\"300\"},\"b\":{\"fonte\":\"R4\",\"valor\":\"10\"}}}")
                .texto("Somando as duas linhas dá {{calc:total}} inserções [C1].");

        ItemTexto texto = textoFinal(falar(conversa, "Quantas inserções somam as chamadas e as vinhetas da RBS TV?"));

        assertThat(texto.texto()).isEqualTo("Somando as duas linhas dá 310 inserções [C1].");
        assertThat(texto.fontes()).extracting(FonteDoTexto::marcador).containsExactly("C1");
    }

    @Test
    void oCalcularRecusaMisturarInsercaoComAplicacaoDeLinhasDoPlano() {
        long primeiraAplicacao = 3 + linhasDeMidiaDoAmarelo();
        long conversa = abrir();
        modelo.chamada(COTA, "{}").chamada(CALCULAR, "{\"nome\":\"misto\",\"script\":\"a + b\",\"variaveis\":{"
                + "\"a\":{\"fonte\":\"R3\",\"valor\":\"300\"},\"b\":{\"fonte\":\"R" + primeiraAplicacao + "\",\"valor\":\"1\"}}}")
                .texto("Não consegui somar essas duas linhas.");

        falar(conversa, "Some as chamadas da RBS TV com as aplicações de marca.");

        String resultado = jdbc.sql("""
                SELECT m.conteudo FROM atlas_mensagem m WHERE m.conversa_id = :c AND m.papel = 'tool' ORDER BY m.ordem DESC LIMIT 1
                """).param("c", conversa).query(String.class).single();
        assertThat(resultado).contains("erro").containsIgnoringCase("unidades").contains("INSERCAO", "APLICACAO");
    }
}
