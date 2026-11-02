package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.jev.ModoDoJev;
import br.com.rbs.portal.atlas.jev.PontoDoJev;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.core.type.TypeReference;

/**
 * [ATLAS] O JEV no diagnóstico (M7): as etapas {@code J1_INTENCAO} e {@code J1_CARTAO} do traço do turno trazem a escolha, a
 * confiança, as probabilidades, o modo, o limiar e a ação ("fez: X" em ativo, "teria feito: X" em sombra), e a {@code atlas_decisao}
 * leva o mesmo {@code turno}. Falha do JEV vira etapa {@code ERRO}, e o orçamento esgotado, {@code PULADO}. A marca vê as etapas pela
 * rota 49 (sem {@code detalhe} nem {@code mensagem}), e o {@code state} que foi ao JEV nunca está no traço nem na decisão. Não é
 * {@code @Transactional}: o que o teste confirmou é apagado no fim.
 */
@TesteComJevRoteirizado
@TestPropertySource(properties = {"atlas.diagnostico.ligado=true", "atlas.diagnostico.marca=true", "atlas.prazo-do-resumo=PT0.5S"})
class JevNoDiagnosticoTest {

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    JevRoteirizado jev;

    @Autowired
    ConfiguracaoDoJevDeTeste config;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvcTester mvc;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void preparar() {
        modelo.limpar();
        jev.limpar();
        config.limpar();
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

    private long conversa() {
        return atlas.abrirConversa(marca(), SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"), null, requisicao()).conversaId();
    }

    private void falar(long conversa, String texto) {
        atlas.enviarMensagem(marca(), conversa, new MensagemRequisicao(texto, null, null), requisicao());
    }

    private record Etapa(String etapa, String resultado, Map<String, Object> dados) {
    }

    private List<Etapa> etapas(long conversa) {
        return jdbc.sql("SELECT etapa, resultado, dados::text AS dados FROM atlas_traco WHERE conversa_id = :c ORDER BY turno, ordem")
                .param("c", conversa)
                .query((rs, n) -> new Etapa(rs.getString("etapa"), rs.getString("resultado"),
                        AtlasJson.ler(rs.getString("dados"), new TypeReference<Map<String, Object>>() {
                        }))).list();
    }

    private Etapa etapa(long conversa, String nome) {
        return etapas(conversa).stream().filter(e -> e.etapa().equals(nome)).findFirst().orElseThrow();
    }

    @Test
    void aIntencaoAtivaDeixaAEtapaComTudoEAAcaoQueFez() {
        config.ligar(PontoDoJev.INTENCAO, ModoDoJev.ATIVO);
        jev.intencao("fora_de_escopo", 0.97);
        long conversa = conversa();

        falar(conversa, "me conta uma piada");

        Etapa intencao = etapa(conversa, "J1_INTENCAO");
        assertThat(intencao.resultado()).isEqualTo("OK");
        assertThat(intencao.dados()).containsEntry("ponto", "intencao").containsEntry("modo", "ATIVO").containsEntry("limiar", 0.9)
                .containsEntry("modelo", "jev-de-teste").containsEntry("escolha", "fora_de_escopo").containsEntry("confianca", 0.97)
                .containsEntry("tipoModelo", "nenhum").containsEntry("sinalDeInjecao", 0.02).containsEntry("agiu", true)
                .containsEntry("acao", "fez: recusou o fora de escopo sem o modelo");
        assertThat(intencao.dados()).containsKey("probabilidades").containsKey("latenciaMs");
        // a recusa fixa fecha o turno sem o modelo: não há ida ao modelo no traço
        assertThat(etapas(conversa)).extracting(Etapa::etapa).containsExactly("G1_LIMITES", "J1_INTENCAO", "SAIDA");
        Etapa saida = etapa(conversa, "SAIDA");
        assertThat(saida.resultado()).isEqualTo("RECUSADO");
        assertThat(saida.dados()).containsEntry("tipo", "FRASE_FIXA").containsEntry("motivo", "FORA_DE_ESCOPO_JEV");
    }

    @Test
    void aRespostaFixaDoCartaoAbertoFicaLigadaAoTurnoEAOBotaoDeDiagnosticoAparece() {
        // a preparação encerra o turno sem o modelo: a SAIDA tem de levar a mensagem da resposta fixa, e o item da conversa, o turno
        config.ligar(PontoDoJev.CARTAO, ModoDoJev.ATIVO);
        jev.cartao("responde_ao_cartao", 0.98);
        long conversa = conversa();
        atlas.enviarMensagem(marca(), conversa, new MensagemRequisicao(null, "MONTAR_RELATORIO", "ATIVACAO_EVENTO"), requisicao());

        var resposta = atlas.enviarMensagem(marca(), conversa, new MensagemRequisicao("e os brindes, onde eu informo?", null, null), requisicao());

        assertThat(modelo.pedidos()).isEmpty();
        Map<String, Object> saida = jdbc.sql("SELECT mensagem_id, CAST(turno AS text) AS turno, dados::text AS dados FROM atlas_traco "
                + "WHERE conversa_id = :c AND etapa = 'SAIDA' ORDER BY id DESC LIMIT 1").param("c", conversa).query().singleRow();
        long fixa = jdbc.sql("SELECT id FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'assistant' AND fora_do_modelo ORDER BY ordem DESC LIMIT 1")
                .param("c", conversa).query(Long.class).single();
        assertThat(saida.get("mensagem_id")).as("a SAIDA leva a mensagem da resposta fixa").isEqualTo(fixa);
        assertThat((String) saida.get("dados")).contains("CARTAO_ABERTO_JEV");
        var texto = resposta.itens().stream().filter(AtlasDtos.ItemTexto.class::isInstance).map(AtlasDtos.ItemTexto.class::cast).findFirst().orElseThrow();
        assertThat(texto.turno()).as("o botão de diagnóstico precisa do turno").isEqualTo(saida.get("turno"));
        // reabrir a conversa traz o mesmo turno
        var reaberta = atlas.verConversa(marca(), conversa);
        assertThat(reaberta.itens().stream().filter(AtlasDtos.ItemTexto.class::isInstance).map(AtlasDtos.ItemTexto.class::cast)
                .map(AtlasDtos.ItemTexto::turno)).contains((String) saida.get("turno"));
    }

    @Test
    void emSombraAEtapaDizOQueTeriaFeitoEOTurnoSegueNormal() {
        config.ligar(PontoDoJev.INTENCAO, ModoDoJev.SOMBRA);
        jev.intencao("fora_de_escopo", 0.97);
        modelo.texto("Só posso ajudar com o portal.");
        long conversa = conversa();

        falar(conversa, "me conta uma piada");

        Etapa intencao = etapa(conversa, "J1_INTENCAO");
        assertThat(intencao.resultado()).isEqualTo("SOMBRA");
        assertThat(intencao.dados()).containsEntry("modo", "SOMBRA").containsEntry("agiu", false)
                .containsEntry("acao", "teria feito: recusar o fora de escopo sem o modelo");
        assertThat(etapas(conversa)).extracting(Etapa::etapa).contains("LLM_IDA").endsWith("SAIDA");
    }

    @Test
    void abaixoDoLimiarEmAtivoAEtapaDizQueNaoAgiu() {
        config.ligar(PontoDoJev.INTENCAO, ModoDoJev.ATIVO);
        jev.intencao("fora_de_escopo", 0.40);
        modelo.texto("Resposta.");
        long conversa = conversa();

        falar(conversa, "me conta uma piada");

        Etapa intencao = etapa(conversa, "J1_INTENCAO");
        assertThat(intencao.resultado()).isEqualTo("OK");
        assertThat(intencao.dados()).containsEntry("agiu", false).containsEntry("acao", "não agiu: confiança abaixo do limiar");
    }

    @Test
    void falhaDoJevViraEtapaErroComOMotivo() {
        config.ligar(PontoDoJev.INTENCAO, ModoDoJev.ATIVO);
        jev.falhar(PontoDoJev.INTENCAO, "HTTP_429");
        modelo.texto("Resposta.");
        long conversa = conversa();

        falar(conversa, "oi");

        Etapa intencao = etapa(conversa, "J1_INTENCAO");
        assertThat(intencao.resultado()).isEqualTo("ERRO");
        assertThat(intencao.dados()).containsEntry("erro", "HTTP_429").containsKey("acao");
    }

    @Test
    void oTurnoDaDecisaoEOMesmoDoTraco() {
        config.ligar(PontoDoJev.INTENCAO, ModoDoJev.ATIVO);
        jev.intencao("fora_de_escopo", 0.97);
        long conversa = conversa();

        falar(conversa, "me conta uma piada");

        String turnoDoTraco = jdbc.sql("SELECT DISTINCT turno::text FROM atlas_traco WHERE conversa_id = :c").param("c", conversa)
                .query(String.class).single();
        List<String> turnosDasDecisoes = jdbc.sql("SELECT DISTINCT d.turno::text FROM atlas_decisao d JOIN atlas_mensagem m ON m.id = d.mensagem_id "
                + "WHERE m.conversa_id = :c").param("c", conversa).query(String.class).list();
        assertThat(turnosDasDecisoes).containsExactly(turnoDoTraco);
    }

    @Test
    void oCartaoDeixaAEtapaJ1CartaoEOTurnoAcabaNaRespostaFixa() {
        config.ligar(PontoDoJev.CARTAO, ModoDoJev.ATIVO);
        jev.cartao("responde_ao_cartao", 0.98);
        long conversa = conversa();
        atlas.enviarMensagem(marca(), conversa, new MensagemRequisicao(null, "MONTAR_RELATORIO", "ATIVACAO_EVENTO"), requisicao());

        falar(conversa, "onde informo os brindes?");

        Etapa cartao = etapa(conversa, "J1_CARTAO");
        assertThat(cartao.resultado()).isEqualTo("OK");
        assertThat(cartao.dados()).containsEntry("ponto", "cartao").containsEntry("escolha", "responde_ao_cartao")
                .containsEntry("confianca", 0.98).containsEntry("agiu", true)
                .containsEntry("acao", "fez: respondeu com a frase fixa e manteve o cartão aberto");
        Etapa saida = etapa(conversa, "SAIDA");
        assertThat(saida.dados()).containsEntry("tipo", "FRASE_FIXA").containsEntry("motivo", "CARTAO_ABERTO_JEV");
        assertThat(etapas(conversa)).extracting(Etapa::etapa).doesNotContain("LLM_IDA");
    }

    @Test
    void aMarcaVeAsEtapasDoJevPelaRota49SemOStateNemODetalhe() {
        config.ligar(PontoDoJev.INTENCAO, ModoDoJev.ATIVO);
        jev.intencao("outro", 0.99);
        modelo.texto("Olá!");
        long conversa = conversa();
        falar(conversa, "meu CPF é 123.456.789-09, mande para joao@amarelo.example");

        MvcTestResult resposta = SessaoDeApi.entrar(mvc, AMARELO).get("/api/atlas/conversas/" + conversa + "/diagnostico");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerLista(resposta, "$.turnos[0].etapas[*].etapa")).contains("J1_INTENCAO");
        assertThat(lerLista(resposta, "$.turnos[0].etapas[?(@.etapa=='J1_INTENCAO')].dados.escolha")).containsExactly("outro");
        // o state (a mensagem mascarada) foi ao JEV, mas não vai a lugar nenhum do traço, da decisão nem da resposta da rota
        String corpo = br.com.rbs.portal.ApoioDeTeste.corpo(resposta);
        assertThat(corpo).doesNotContain("123.456.789-09").doesNotContain("joao@amarelo").doesNotContain("[removido]");
        assertThat(jev.recebidasDe(PontoDoJev.INTENCAO).get(0).pedido().estado()).isEqualTo("meu CPF é [removido], mande para [removido]");
        String tudoNoBanco = jdbc.sql("SELECT string_agg(dados::text, ' ') FROM atlas_traco WHERE conversa_id = :c").param("c", conversa)
                .query(String.class).single();
        assertThat(tudoNoBanco).doesNotContain("123.456.789-09").doesNotContain("joao@amarelo");
    }

    @Test
    void semJevLigadoOTracoNaoTemEtapaDoJev() {
        modelo.texto("Olá!");
        long conversa = conversa();

        falar(conversa, "oi");

        assertThat(etapas(conversa)).extracting(Etapa::etapa).doesNotContain("J1_INTENCAO", "J1_CARTAO", "R3_FONTES", "R5_RESPOSTA",
                "V2_COERENCIA", "LACUNAS");
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_decisao d JOIN atlas_mensagem m ON m.id = d.mensagem_id WHERE m.conversa_id = :c")
                .param("c", conversa).query(Long.class).single()).isZero();
    }
}
