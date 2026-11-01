package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.LoopDoAgenteTest.ARGS_INICIAR;
import static br.com.rbs.portal.atlas.LoopDoAgenteTest.INICIAR;
import static br.com.rbs.portal.atlas.LoopDoAgenteTest.SOLICITAR;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.CampoDoCartaoDto;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemCartao;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.jev.ModoDoJev;
import br.com.rbs.portal.atlas.jev.PontoDoJev;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * [ATLAS] O ponto {@code lacunas} do JEV (passo 4, M7), com o JEV e o modelo roteirizados: das lacunas opcionais que o modelo pediu no
 * cartão (os brindes disponíveis e distribuídos), o JEV diz, com uma {@code noul} por lacuna, se vale perguntar. Em ATIVO, a que fica
 * abaixo do limiar não entra no cartão; as obrigatórias nunca saem; na dúvida, ou se o JEV falhou, pergunta; se só havia opcionais
 * dispensadas, não há cartão e o modelo segue. Em SOMBRA o cartão sai inteiro. O botão (plano B) não consulta o JEV. Não é
 * {@code @Transactional}: o traço e as decisões vão ao banco no fim do turno e o teste apaga o que confirmou.
 */
@TesteComJevRoteirizado
@TestPropertySource(properties = {"atlas.diagnostico.ligado=true", "atlas.diagnostico.marca=true", "atlas.prazo-do-resumo=PT0.5S"})
class JevLacunasTest {

    private static final String VISITANTES = "ativacao.visitantes_total";
    private static final String CADASTROS = "ativacao.cadastros_consentidos";
    private static final String DISPONIVEIS = "ativacao.brindes_disponiveis";
    private static final String DISTRIBUIDOS = "ativacao.brindes_distribuidos";
    private static final String TODOS_OS_CAMPOS = AtlasJson.escrever(Map.of("mensagem", "Preciso destes números.",
            "campos", List.of(VISITANTES, CADASTROS, DISPONIVEIS, DISTRIBUIDOS)));
    private static final String SO_OS_BRINDES = AtlasJson.escrever(Map.of("mensagem", "Preciso destes números.",
            "campos", List.of(DISPONIVEIS, DISTRIBUIDOS)));

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

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void preparar() {
        modelo.limpar();
        jev.limpar();
        config.limpar().ligar(PontoDoJev.LACUNAS, ModoDoJev.ATIVO);
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagar() {
        config.limpar();
        limpeza.apagar();
    }

    private long conversa() {
        return atlas.abrirConversa(usuario(jdbc, AMARELO), SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"), null, requisicao())
                .conversaId();
    }

    private ConversaDto falar(long conversa, String texto) {
        return atlas.enviarMensagem(usuario(jdbc, AMARELO), conversa, new MensagemRequisicao(texto, null, null), requisicao());
    }

    private static List<String> chavesDoCartao(ConversaDto conversa) {
        return conversa.itens().stream().filter(ItemCartao.class::isInstance).map(ItemCartao.class::cast).findFirst().orElseThrow()
                .campos().stream().map(CampoDoCartaoDto::chave).toList();
    }

    private List<Map<String, Object>> decisoes(long conversa) {
        return jdbc.sql("SELECT d.pergunta, d.confianca, d.acao, d.alvo, d.modo, d.erro FROM atlas_decisao d JOIN atlas_mensagem m "
                + "ON m.id = d.mensagem_id WHERE m.conversa_id = :c AND d.ponto = 'lacunas' ORDER BY d.id").param("c", conversa)
                .query().listOfRows();
    }

    /** O JEV acha que não vale perguntar os brindes disponíveis (noul baixa) e que vale perguntar os distribuídos. */
    private void soOsDistribuidosValem() {
        jev.lacunas(estado -> estado.contains("Brindes disponíveis") ? 0.08 : 0.85);
    }

    @Test
    void aLacunaOpcionalQueNaoValeSaiDoCartaoEAsObrigatoriasFicam() {
        soOsDistribuidosValem();
        long conversa = conversa();
        modelo.chamada(INICIAR, ARGS_INICIAR).chamada(SOLICITAR, TODOS_OS_CAMPOS);

        ConversaDto resposta = falar(conversa, "quero o relatório só do público, sem foco em brindes");

        assertThat(chavesDoCartao(resposta)).containsExactly(VISITANTES, CADASTROS, DISTRIBUIDOS);
        // só as opcionais foram ao JEV, uma requisição por lacuna, com o pedido da pessoa e o rótulo do campo
        List<JevRoteirizado.Recebida> recebidas = jev.recebidasDe(PontoDoJev.LACUNAS);
        assertThat(recebidas).hasSize(2);
        assertThat(recebidas.stream().map(r -> r.pedido().estado()).toList()).containsExactlyInAnyOrder(
                "Pedido do patrocinador:\nquero o relatório só do público, sem foco em brindes\n\nCampo opcional do relatório: Brindes disponíveis",
                "Pedido do patrocinador:\nquero o relatório só do público, sem foco em brindes\n\nCampo opcional do relatório: Brindes distribuídos");
        assertThat(recebidas).allSatisfy(r -> assertThat(r.pedido().perguntas().keySet()).containsExactly("vale_perguntar"));
        assertThat(jev.algumaComTransacaoAberta()).isFalse();
        List<Map<String, Object>> linhas = decisoes(conversa);
        assertThat(linhas).extracting(d -> d.get("alvo")).containsExactlyInAnyOrder(DISPONIVEIS, DISTRIBUIDOS);
        assertThat(linhas.stream().filter(d -> DISPONIVEIS.equals(d.get("alvo"))).findFirst().orElseThrow())
                .containsEntry("acao", "fez: tirou a lacuna do cartão").containsEntry("modo", "ATIVO").containsEntry("pergunta", "vale_perguntar");
        assertThat(linhas.stream().filter(d -> DISTRIBUIDOS.equals(d.get("alvo"))).findFirst().orElseThrow())
                .containsEntry("acao", "fez: manteve a lacuna no cartão");
    }

    @Test
    void naDuvidaPergunta() {
        jev.lacunas(estado -> 0.31);   // acima do limiar de 0.30: vale perguntar
        long conversa = conversa();
        modelo.chamada(INICIAR, ARGS_INICIAR).chamada(SOLICITAR, TODOS_OS_CAMPOS);

        ConversaDto resposta = falar(conversa, "monta o relatório");

        assertThat(chavesDoCartao(resposta)).containsExactly(VISITANTES, CADASTROS, DISPONIVEIS, DISTRIBUIDOS);
    }

    @Test
    void seOJevFalhouOCartaoSaiInteiro() {
        jev.falhar(PontoDoJev.LACUNAS, "HTTP_429");
        long conversa = conversa();
        modelo.chamada(INICIAR, ARGS_INICIAR).chamada(SOLICITAR, TODOS_OS_CAMPOS);

        ConversaDto resposta = falar(conversa, "monta o relatório");

        assertThat(chavesDoCartao(resposta)).containsExactly(VISITANTES, CADASTROS, DISPONIVEIS, DISTRIBUIDOS);
        assertThat(decisoes(conversa)).hasSize(2).allSatisfy(d -> assertThat(d.get("erro")).isEqualTo("HTTP_429"));
        assertThat(jdbc.sql("SELECT resultado FROM atlas_traco WHERE conversa_id = :c AND etapa = 'LACUNAS'").param("c", conversa)
                .query(String.class).single()).isEqualTo("ERRO");
    }

    @Test
    void emSombraOCartaoSaiInteiroEADecisaoDizOQueTeriaFeito() {
        config.limpar().ligar(PontoDoJev.LACUNAS, ModoDoJev.SOMBRA);
        soOsDistribuidosValem();
        long conversa = conversa();
        modelo.chamada(INICIAR, ARGS_INICIAR).chamada(SOLICITAR, TODOS_OS_CAMPOS);

        ConversaDto resposta = falar(conversa, "monta o relatório");

        assertThat(chavesDoCartao(resposta)).containsExactly(VISITANTES, CADASTROS, DISPONIVEIS, DISTRIBUIDOS);
        assertThat(decisoes(conversa)).extracting(d -> d.get("acao")).containsExactlyInAnyOrder("teria feito: tirar a lacuna do cartão",
                "teria feito: manter a lacuna no cartão");
        assertThat(decisoes(conversa)).extracting(d -> d.get("modo")).containsOnly("SOMBRA");
    }

    @Test
    void seSoHaviaOpcionaisDispensadasNaoHaCartaoEOModeloSegue() {
        jev.lacunas(estado -> 0.01);
        long conversa = conversa();
        modelo.chamada(INICIAR, ARGS_INICIAR).chamada(SOLICITAR, SO_OS_BRINDES).passo(pedido -> {
            String resultado = pedido.mensagens().get(pedido.mensagens().size() - 1).conteudo();
            assertThat(resultado).contains("\"cartao\":\"dispensado\"").contains("não valem uma pergunta agora");
            return new br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm("Sigo sem os brindes.", List.of(), null);
        });

        ConversaDto resposta = falar(conversa, "monta o relatório sem brindes");

        assertThat(resposta.itens()).noneMatch(ItemCartao.class::isInstance);
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_solicitacao s JOIN atlas_relatorio r ON r.id = s.relatorio_id WHERE r.conversa_id = :c")
                .param("c", conversa).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT status FROM atlas_relatorio WHERE conversa_id = :c").param("c", conversa).query(String.class).single())
                .isEqualTo("MONTANDO");
        modelo.pedidos().forEach(ProvedorRoteirizado::exigirHistoricoValido);
    }

    @Test
    void oBotaoNaoConsultaOJevEPerguntaTudo() {
        soOsDistribuidosValem();
        long conversa = conversa();

        ConversaDto resposta = atlas.enviarMensagem(usuario(jdbc, AMARELO), conversa, new MensagemRequisicao(null, "MONTAR_RELATORIO",
                "ATIVACAO_EVENTO"), requisicao());

        assertThat(jev.recebidas()).isEmpty();
        assertThat(chavesDoCartao(resposta)).containsExactly(VISITANTES, CADASTROS, DISPONIVEIS, DISTRIBUIDOS);
    }

    @Test
    void pontoDesligadoNaoConsultaOJev() {
        config.limpar();
        soOsDistribuidosValem();
        long conversa = conversa();
        modelo.chamada(INICIAR, ARGS_INICIAR).chamada(SOLICITAR, TODOS_OS_CAMPOS);

        ConversaDto resposta = falar(conversa, "monta o relatório");

        assertThat(jev.recebidas()).isEmpty();
        assertThat(chavesDoCartao(resposta)).containsExactly(VISITANTES, CADASTROS, DISPONIVEIS, DISTRIBUIDOS);
    }
}
