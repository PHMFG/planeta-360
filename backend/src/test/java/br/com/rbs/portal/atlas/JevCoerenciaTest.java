package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.LoopDoAgenteTest.ARGS_INICIAR;
import static br.com.rbs.portal.atlas.LoopDoAgenteTest.EDITAR;
import static br.com.rbs.portal.atlas.LoopDoAgenteTest.INICIAR;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.contexto.AtlasContextoFabrica;
import br.com.rbs.portal.atlas.diagnostico.RegistroDoTraco;
import br.com.rbs.portal.atlas.jev.ModoDoJev;
import br.com.rbs.portal.atlas.jev.PontoDoJev;
import br.com.rbs.portal.atlas.jev.ProvedorDeJev.RespostaDaPergunta;
import br.com.rbs.portal.atlas.jev.ProvedorDeJev.RespostaDoJev;
import br.com.rbs.portal.atlas.jev.ServicoDoJev;
import br.com.rbs.portal.atlas.jev.ServicoDoJev.ParagrafoParaJulgar;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.core.type.TypeReference;

/**
 * [ATLAS] O ponto {@code coerencia} do JEV (V2 do v3, passo 9, M7), com o JEV e o modelo roteirizados: o parágrafo que o modelo escreve
 * em {@code relatorio_editar}, com os números no lugar de {@code [VALOR]}, contra os selos do servidor ("Comprovadas: abaixo do
 * previsto" no Amarelo de 2027). Em ATIVO, o incoerente é recusado como qualquer texto recusado: o modelo reescreve e, na segunda,
 * o bloco fica com a frase-modelo; em SOMBRA nada muda; abaixo do limiar ou em erro o texto segue; sem selo no relatório o ponto não
 * roda. A consulta sai antes da transação do loop. Não é {@code @Transactional}: o traço e as decisões vão ao banco no fim do turno e
 * o teste apaga o que confirmou.
 */
@TesteComJevRoteirizado
@TestPropertySource(properties = {"atlas.diagnostico.ligado=true", "atlas.diagnostico.marca=true", "atlas.prazo-do-resumo=PT0.5S"})
class JevCoerenciaTest {

    static final String SUPEROU = "A marca superou o previsto nas aplicações: {{cons:aplicacoes.comprovadas}} de {{cons:aplicacoes.previstas}}.";
    static final String NAO_ATINGIU = "A marca ficou abaixo do previsto nas aplicações: {{cons:aplicacoes.comprovadas}} de {{cons:aplicacoes.previstas}}.";
    static final String PUBLICO_OK = "O espaço recebeu {{dado:ativacao.visitantes_total}} visitantes.";

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    JevRoteirizado jev;

    @Autowired
    ConfiguracaoDoJevDeTeste config;

    @Autowired
    ServicoDoJev servico;

    @Autowired
    AtlasContextoFabrica contextos;

    @Autowired
    RegistroDoTraco registro;

    @Autowired
    JdbcClient jdbc;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void preparar() {
        modelo.limpar();
        jev.limpar();
        config.limpar().ligar(PontoDoJev.COERENCIA, ModoDoJev.ATIVO);
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

    private void falar(long conversa, String texto) {
        atlas.enviarMensagem(usuario(jdbc, AMARELO), conversa, new MensagemRequisicao(texto, null, null), requisicao());
    }

    private static String edicao(String resumo, String publico) {
        List<Map<String, String>> operacoes = new java.util.ArrayList<>();
        if (resumo != null) {
            operacoes.add(Map.of("op", "definir_texto", "bloco", "b-resumo", "texto", resumo));
        }
        if (publico != null) {
            operacoes.add(Map.of("op", "definir_texto", "bloco", "b-publico", "texto", publico));
        }
        return AtlasJson.escrever(Map.of("operacoes", operacoes));
    }

    /** O JEV acha incoerente o parágrafo que diz que a marca superou o previsto, e coerente o resto. */
    private void incoerenteSoSeSuperou(double confianca) {
        jev.passo(PontoDoJev.COERENCIA, pedido -> new RespostaDoJev(Map.of("coerencia", RespostaDaPergunta.deEscolha(
                pedido.estado().contains("superou") ? "incoerente" : "coerente", confianca, null)), 4));
    }

    /** O resultado de cada chamada de {@code relatorio_editar} da conversa, na ordem: uma lista de {@code resultado} por operação. */
    private List<List<String>> resultadosDasEdicoes(long conversa) {
        return jdbc.sql("SELECT m.conteudo FROM atlas_mensagem m WHERE m.conversa_id = :c AND m.papel = 'tool' AND m.conteudo LIKE '%blocosSemTexto%' "
                        + "ORDER BY m.ordem").param("c", conversa).query(String.class).list().stream()
                .map(json -> AtlasJson.ler(json, new TypeReference<Map<String, Object>>() {
                }))
                .map(mapa -> ((List<?>) mapa.get("operacoes")).stream().map(o -> (String) ((Map<?, ?>) o).get("resultado")).toList()).toList();
    }

    private String motivoDaRecusa(long conversa) {
        return jdbc.sql("SELECT m.conteudo FROM atlas_mensagem m WHERE m.conversa_id = :c AND m.papel = 'tool' AND m.conteudo LIKE '%recusada%' "
                + "ORDER BY m.ordem LIMIT 1").param("c", conversa).query(String.class).single();
    }

    private List<Map<String, Object>> decisoes(long conversa) {
        return jdbc.sql("SELECT d.pergunta, d.escolha, d.acao, d.alvo, d.modo FROM atlas_decisao d JOIN atlas_mensagem m ON m.id = d.mensagem_id "
                + "WHERE m.conversa_id = :c AND d.ponto = 'coerencia' ORDER BY d.id").param("c", conversa).query().listOfRows();
    }

    private String textoDoBloco(long conversa, String bloco) {
        String especificacao = jdbc.sql("SELECT especificacao::text FROM atlas_relatorio WHERE conversa_id = :c").param("c", conversa)
                .query(String.class).single();
        return especificacao;
    }

    @Test
    void oParagrafoIncoerenteComOsSelosERecusadoENaSegundaFicaAFraseModelo() {
        incoerenteSoSeSuperou(0.97);
        long conversa = conversa();
        modelo.chamada(INICIAR, ARGS_INICIAR).chamada(EDITAR, edicao(SUPEROU, PUBLICO_OK)).chamada(EDITAR, edicao(SUPEROU, null))
                .texto("Pronto.");

        falar(conversa, "monta o relatório");

        assertThat(resultadosDasEdicoes(conversa)).containsExactly(List.of("recusada", "aplicada"), List.of("frase_modelo"));
        String motivo = motivoDaRecusa(conversa);
        assertThat(motivo).contains("O texto não combina com os selos do relatório (Comprovadas: abaixo do previsto)")
                .contains("Escreva este bloco de novo");
        // o bloco que o JEV recusou duas vezes ficou com a frase-modelo (MODELO), e o outro, com o texto do modelo de linguagem (ATLAS)
        String especificacao = textoDoBloco(conversa, "b-resumo");
        assertThat(especificacao).contains("\"redigido_por\": \"MODELO\"").contains("\"redigido_por\": \"ATLAS\"");
        assertThat(especificacao).doesNotContain("superou o previsto");
        assertThat(decisoes(conversa)).extracting(d -> d.get("escolha")).containsExactly("incoerente", "coerente", "incoerente");
        assertThat(decisoes(conversa).get(0)).containsEntry("alvo", "b-resumo").containsEntry("pergunta", "coerencia").containsEntry("modo", "ATIVO")
                .containsEntry("acao", "fez: recusou o parágrafo e pediu nova redação");
        assertThat(decisoes(conversa).get(1).get("acao")).isEqualTo("não agiu: o parágrafo segue");
        modelo.pedidos().forEach(ProvedorRoteirizado::exigirHistoricoValido);
    }

    @Test
    void aNovaRedacaoCoerenteEAplicada() {
        incoerenteSoSeSuperou(0.97);
        long conversa = conversa();
        modelo.chamada(INICIAR, ARGS_INICIAR).chamada(EDITAR, edicao(SUPEROU, null)).chamada(EDITAR, edicao(NAO_ATINGIU, null)).texto("Pronto.");

        falar(conversa, "monta o relatório");

        assertThat(resultadosDasEdicoes(conversa)).containsExactly(List.of("recusada"), List.of("aplicada"));
        assertThat(textoDoBloco(conversa, "b-resumo")).contains("ficou abaixo do previsto").contains("\"redigido_por\": \"ATLAS\"");
    }

    @Test
    void oStateTemOsSelosEOParagrafoComOsNumerosEmValorSemNomeDeParceria() {
        incoerenteSoSeSuperou(0.2);
        long conversa = conversa();
        modelo.chamada(INICIAR, ARGS_INICIAR).chamada(EDITAR, edicao(SUPEROU, PUBLICO_OK)).texto("Pronto.");

        falar(conversa, "monta o relatório");

        List<JevRoteirizado.Recebida> recebidas = jev.recebidasDe(PontoDoJev.COERENCIA);
        assertThat(recebidas).as("uma requisição por parágrafo").hasSize(2);
        assertThat(recebidas.stream().map(r -> r.pedido().estado()).toList()).anyMatch(e -> e.equals(
                "Selos do relatório, calculados pelo servidor:\n- Comprovadas: abaixo do previsto\n\nParágrafo escrito pelo assistente "
                        + "(os números estão no lugar de [VALOR]):\nA marca superou o previsto nas aplicações: [VALOR] de [VALOR]."));
        assertThat(recebidas).allSatisfy(r -> assertThat(r.pedido().estado()).doesNotContain("{{").doesNotContain("Cliente Amarelo"));
        assertThat(recebidas.get(0).pedido().perguntas().get("coerencia").criterios().keySet()).containsExactly("coerente", "incoerente");
        assertThat(jev.algumaComTransacaoAberta()).as("o JEV é consultado fora da transação do loop").isFalse();
    }

    @Test
    void abaixoDoLimiarEEmErroOTextoSegue() {
        incoerenteSoSeSuperou(0.5);
        long abaixo = conversa();
        modelo.chamada(INICIAR, ARGS_INICIAR).chamada(EDITAR, edicao(SUPEROU, null)).texto("Pronto.");
        falar(abaixo, "monta o relatório");
        assertThat(resultadosDasEdicoes(abaixo)).containsExactly(List.of("aplicada"));
        assertThat(decisoes(abaixo).get(0).get("acao")).isEqualTo("não agiu: confiança abaixo do limiar");

        modelo.limpar();
        jev.limpar().falhar(PontoDoJev.COERENCIA, "HTTP_422");
        long erro = conversa();
        modelo.chamada(INICIAR, ARGS_INICIAR).chamada(EDITAR, edicao(SUPEROU, null)).texto("Pronto.");
        falar(erro, "monta o relatório");
        assertThat(resultadosDasEdicoes(erro)).containsExactly(List.of("aplicada"));
        assertThat(jdbc.sql("SELECT d.erro FROM atlas_decisao d JOIN atlas_mensagem m ON m.id = d.mensagem_id WHERE m.conversa_id = :c "
                + "AND d.ponto = 'coerencia'").param("c", erro).query(String.class).single()).isEqualTo("HTTP_422");
    }

    @Test
    void emSombraOTextoIncoerenteEAplicadoEADecisaoDizOQueTeriaFeito() {
        config.limpar().ligar(PontoDoJev.COERENCIA, ModoDoJev.SOMBRA);
        incoerenteSoSeSuperou(0.99);
        long conversa = conversa();
        modelo.chamada(INICIAR, ARGS_INICIAR).chamada(EDITAR, edicao(SUPEROU, null)).texto("Pronto.");

        falar(conversa, "monta o relatório");

        assertThat(resultadosDasEdicoes(conversa)).containsExactly(List.of("aplicada"));
        assertThat(decisoes(conversa).get(0)).containsEntry("modo", "SOMBRA").containsEntry("acao", "teria feito: recusar o parágrafo e pedir nova redação");
    }

    @Test
    void semSeloNoRelatorioOPontoNaoRodaEOTracoDizPorque() {
        jev.coerencia("incoerente", 0.99);
        long conversa = conversa();
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        var traco = registro.abrir(parceria, conversa);
        AtlasContexto contexto = contextos.para(usuario(jdbc, AMARELO), parceria, conversa, requisicao()).comTraco(traco);

        Map<String, String> recusas = servico.coerencia(contexto, List.of(), List.of(new ParagrafoParaJulgar("b-resumo", SUPEROU)));
        traco.encerrar();

        assertThat(recusas).isEmpty();
        assertThat(jev.recebidas()).isEmpty();
        assertThat(jdbc.sql("SELECT resultado || ' ' || (dados ->> 'motivo') FROM atlas_traco WHERE conversa_id = :c AND etapa = 'V2_COERENCIA'")
                .param("c", conversa).query(String.class).single()).isEqualTo("PULADO SEM_SELO");
    }

    @Test
    void forDoRelatorioEmMontagemOuComOPontoDesligadoNaoConsultaOJev() {
        config.limpar();   // desligado
        incoerenteSoSeSuperou(0.99);
        long conversa = conversa();
        modelo.chamada(INICIAR, ARGS_INICIAR).chamada(EDITAR, edicao(SUPEROU, null)).texto("Pronto.");

        falar(conversa, "monta o relatório");

        assertThat(jev.recebidas()).isEmpty();
        assertThat(resultadosDasEdicoes(conversa)).containsExactly(List.of("aplicada"));
    }
}
