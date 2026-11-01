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
import br.com.rbs.portal.atlas.loop.LoopDoAgente;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.PedidoAoLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * [ATLAS] O ponto {@code resposta} do JEV (R5, M7), com o JEV e o modelo roteirizados e o kit de documentos no banco de teste: o texto
 * já passou na checagem das fontes, e o JEV ainda diz, numa requisição só (até 8 frases), se o trecho citado apoia cada frase que cita
 * um trecho ({@code apoia}, {@code contradiz} ou {@code nao_diz}). Em ATIVO, {@code contradiz}, ou {@code nao_diz} numa frase com
 * número ou data, recusa o texto com o motivo (uma nova redação) e, na segunda recusa, vale o L2; em SOMBRA nada muda; abaixo do
 * limiar ou em erro a resposta segue. Não é {@code @Transactional}: o traço e as decisões vão ao banco no fim do turno e o teste apaga
 * o que confirmou.
 */
@TesteComJevRoteirizado
@TestPropertySource(properties = {"atlas.diagnostico.ligado=true", "atlas.diagnostico.marca=true", "atlas.prazo-do-resumo=PT0.5S"})
class JevRespostaTest {

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
    org.springframework.test.web.servlet.assertj.MockMvcTester mvc;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void preparar() {
        modelo.limpar();
        jev.limpar();
        config.limpar().ligar(PontoDoJev.RESPOSTA, ModoDoJev.ATIVO);
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

    private static ItemTexto textoFinal(ConversaDto conversa) {
        return (ItemTexto) conversa.itens().stream().filter(ItemTexto.class::isInstance).reduce((a, b) -> b).orElseThrow();
    }

    /** O modelo busca e escreve a resposta que o redator montar com o que a busca devolveu. */
    private void buscarEEscrever(Function<PedidoAoLlm, String> redator) {
        modelo.chamada(BUSCAR, busca(CONSULTA)).passo(pedido -> new RespostaDoLlm(redator.apply(pedido), List.of(), null));
    }

    private List<Map<String, Object>> decisoes(long conversa) {
        return jdbc.sql("SELECT d.pergunta, d.escolha, d.confianca, d.acao, d.alvo, d.modo FROM atlas_decisao d JOIN atlas_mensagem m "
                + "ON m.id = d.mensagem_id WHERE m.conversa_id = :c AND d.ponto = 'resposta' ORDER BY d.id").param("c", conversa)
                .query().listOfRows();
    }

    @Test
    void quandoOTrechoApoiaARespostaSegueEOJevRecebeARespostaEOTrechoCitado() {
        jev.resposta(frase -> "apoia", 0.97);
        buscarEEscrever(pedido -> frasePrazo(marcador(pedido, PRAZO, true)));
        long conversa = conversa();

        ConversaDto resposta = falar(conversa, "Qual o prazo para informar os geradores próprios?");

        assertThat(textoFinal(resposta).texto()).contains(PRAZO);
        assertThat(jev.recebidasDe(PontoDoJev.RESPOSTA)).hasSize(1);
        var pedido = jev.recebidasDe(PontoDoJev.RESPOSTA).get(0).pedido();
        assertThat(pedido.perguntas().keySet()).containsExactly("frase_1");
        assertThat(pedido.perguntas().get("frase_1").criterios().keySet()).containsExactly("apoia", "contradiz", "nao_diz");
        assertThat(pedido.perguntas().get("frase_1").instrucoes()).contains("Frase: Os geradores próprios devem ser informados");
        assertThat(pedido.estado()).startsWith("Resposta do assistente:\nOs geradores próprios").contains("\n\nTrechos citados:\n[T")
                .contains(PRAZO);
        assertThat(jev.algumaComTransacaoAberta()).isFalse();
        assertThat(decisoes(conversa)).hasSize(1);
        assertThat(decisoes(conversa).get(0)).containsEntry("pergunta", "frase_1").containsEntry("escolha", "apoia")
                .containsEntry("alvo", "frase:1").containsEntry("modo", "ATIVO");
        var turno = ApoioDoDiagnostico.tracoDoTurno(jdbc, conversa);
        ApoioDoDiagnostico.exigirCoerencia(turno);
        var r5 = ApoioDoDiagnostico.primeira(turno, "R5_RESPOSTA");
        assertThat(r5.resultado()).isEqualTo("OK");
        assertThat(r5.dados()).containsEntry("ponto", "resposta").containsEntry("frases", 1);
        assertThat((List<?>) r5.dados().get("itens")).hasSize(1);
        assertThat(ApoioDoDiagnostico.etapas(turno).indexOf("R5_RESPOSTA")).isGreaterThan(ApoioDoDiagnostico.etapas(turno).indexOf("CHECAGEM"));
    }

    @Test
    void contradizPedeNovaRedacaoComOMotivoENaSegundaSaiOL2() {
        jev.resposta(frase -> "contradiz", 0.96);
        List<String> pedidosDeRedacao = new ArrayList<>();
        modelo.chamada(BUSCAR, busca(CONSULTA))
                .passo(pedido -> new RespostaDoLlm(frasePrazo(marcador(pedido, PRAZO, true)), List.of(), null))
                .passo(pedido -> {
                    pedidosDeRedacao.add(pedido.mensagens().get(pedido.mensagens().size() - 1).conteudo());
                    return new RespostaDoLlm(frasePrazo(marcador(pedido, PRAZO, true)), List.of(), null);
                });
        long conversa = conversa();

        ConversaDto resposta = falar(conversa, "Qual o prazo para informar os geradores próprios?");

        assertThat(pedidosDeRedacao).hasSize(1);
        assertThat(pedidosDeRedacao.get(0)).startsWith("Sua resposta foi recusada pelo servidor: A frase \"Os geradores próprios devem ser informados")
                .contains("contradiz o trecho que ela cita").contains("Escreva a resposta de novo");
        assertThat(modelo.pedidos()).as("busca, a resposta recusada e a nova redação").hasSize(3);
        // contradisse de novo: o L2 (o turno buscou), e o texto fixo no lugar da resposta
        assertThat(textoFinal(resposta).texto()).isEqualTo(LoopDoAgente.TEXTO_NAO_ENCONTREI);
        assertThat(textoFinal(resposta).porModelo()).isFalse();
        assertThat(jev.recebidasDe(PontoDoJev.RESPOSTA)).hasSize(2);
        modelo.pedidos().forEach(ProvedorRoteirizado::exigirHistoricoValido);
    }

    @Test
    void aNovaRedacaoQueOJevApoiaPassa() {
        List<String> respostas = new ArrayList<>();
        jev.passo(PontoDoJev.RESPOSTA, pedido -> {
            boolean primeira = respostas.isEmpty();
            respostas.add("x");
            String escolha = primeira ? "contradiz" : "apoia";
            return new br.com.rbs.portal.atlas.jev.ProvedorDeJev.RespostaDoJev(Map.of("frase_1",
                    br.com.rbs.portal.atlas.jev.ProvedorDeJev.RespostaDaPergunta.deEscolha(escolha, 0.96, null)), 3);
        });
        modelo.chamada(BUSCAR, busca(CONSULTA))
                .passo(pedido -> new RespostaDoLlm(frasePrazo(marcador(pedido, PRAZO, true)), List.of(), null))
                .passo(pedido -> new RespostaDoLlm("O prazo para informar os geradores próprios é " + PRAZO + " [" + marcador(pedido, PRAZO, true) + "].",
                        List.of(), null));
        long conversa = conversa();

        ConversaDto resposta = falar(conversa, "Qual o prazo para informar os geradores próprios?");

        // a primeira passa na checagem e o JEV a contradiz; a segunda é a nova redação, que o JEV apoia: ela é a resposta
        assertThat(textoFinal(resposta).texto()).startsWith("O prazo para informar os geradores próprios é " + PRAZO);
        assertThat(modelo.pedidos()).hasSize(3);
        assertThat(decisoes(conversa)).extracting(d -> d.get("escolha")).containsExactly("contradiz", "apoia");
    }

    @Test
    void naoDizNumaFraseComDataRecusaENumaSemNumeroNao() {
        jev.resposta(frase -> "nao_diz", 0.95);
        buscarEEscrever(pedido -> "O manual trata dos geradores próprios [" + marcador(pedido, PRAZO, true) + "].");
        long semNumero = conversa();

        ConversaDto aceita = falar(semNumero, "O manual fala de geradores?");

        assertThat(textoFinal(aceita).texto()).startsWith("O manual trata dos geradores próprios [T");
        assertThat(modelo.pedidos()).hasSize(2);
        assertThat(decisoes(semNumero).get(0).get("acao")).isEqualTo("não agiu: a frase segue");

        modelo.limpar();
        jev.limpar().resposta(frase -> "nao_diz", 0.95);
        buscarEEscrever(pedido -> frasePrazo(marcador(pedido, PRAZO, true)));
        modelo.passo(pedido -> {
            assertThat(pedido.mensagens().get(pedido.mensagens().size() - 1).conteudo())
                    .contains("não diz o que ela afirma sobre o número ou a data");
            return new RespostaDoLlm(frasePrazo(marcador(pedido, PRAZO, true)), List.of(), null);
        });
        long comData = conversa();

        ConversaDto recusada = falar(comData, "Qual o prazo para informar os geradores próprios?");

        assertThat(textoFinal(recusada).texto()).isEqualTo(LoopDoAgente.TEXTO_NAO_ENCONTREI);
        assertThat(decisoes(comData).get(0).get("acao")).isEqualTo("fez: recusou a resposta e pediu nova redação");
    }

    @Test
    void abaixoDoLimiarEEmErroARespostaSegue() {
        jev.resposta(frase -> "contradiz", 0.55);
        buscarEEscrever(pedido -> frasePrazo(marcador(pedido, PRAZO, true)));
        long abaixo = conversa();
        assertThat(textoFinal(falar(abaixo, "Qual o prazo para informar os geradores próprios?")).texto()).contains(PRAZO);
        assertThat(decisoes(abaixo).get(0).get("acao")).isEqualTo("não agiu: confiança abaixo do limiar");

        modelo.limpar();
        jev.limpar().falhar(PontoDoJev.RESPOSTA, "TIMEOUT");
        buscarEEscrever(pedido -> frasePrazo(marcador(pedido, PRAZO, true)));
        long erro = conversa();
        assertThat(textoFinal(falar(erro, "Qual o prazo para informar os geradores próprios?")).texto()).contains(PRAZO);
        assertThat(jdbc.sql("SELECT d.erro FROM atlas_decisao d JOIN atlas_mensagem m ON m.id = d.mensagem_id WHERE m.conversa_id = :c")
                .param("c", erro).query(String.class).single()).isEqualTo("TIMEOUT");
        assertThat(jev.recebidasDe(PontoDoJev.RESPOSTA)).as("sem nova tentativa").hasSize(1);
    }

    @Test
    void emSombraARespostaNaoMudaEADecisaoDizOQueTeriaFeito() {
        config.limpar().ligar(PontoDoJev.RESPOSTA, ModoDoJev.SOMBRA);
        jev.resposta(frase -> "contradiz", 0.99);
        buscarEEscrever(pedido -> frasePrazo(marcador(pedido, PRAZO, true)));
        long conversa = conversa();

        ConversaDto resposta = falar(conversa, "Qual o prazo para informar os geradores próprios?");

        assertThat(textoFinal(resposta).texto()).contains(PRAZO);
        assertThat(modelo.pedidos()).hasSize(2);
        assertThat(decisoes(conversa).get(0)).containsEntry("modo", "SOMBRA").containsEntry("acao", "teria feito: recusar a resposta e pedir nova redação");
    }

    @Test
    void soAsFrasesQueCitamTrechoVaoAoJevEAteOitoOsNumerosEntramComoValor() {
        jev.resposta(frase -> "apoia", 0.97);
        buscarEEscrever(pedido -> {
            String m = marcador(pedido, PRAZO, true);
            StringBuilder texto = new StringBuilder();
            for (int i = 1; i <= 10; i++) {
                texto.append("Os geradores próprios devem ser informados até ").append(PRAZO).append(" [").append(m).append("]. ");
            }
            texto.append("Sobre a parceria: {{cons:aplicacoes.previstas}}.");
            return texto.toString();
        });
        long conversa = conversa();

        falar(conversa, "Qual o prazo para informar os geradores próprios?");

        var pedido = jev.recebidasDe(PontoDoJev.RESPOSTA).get(0).pedido();
        assertThat(pedido.perguntas().keySet()).as("até 8 frases").containsExactly("frase_1", "frase_2", "frase_3", "frase_4", "frase_5",
                "frase_6", "frase_7", "frase_8");
        assertThat(pedido.estado()).contains("[VALOR]").doesNotContain("{{");
        assertThat(pedido.perguntas().values()).allSatisfy(p -> assertThat(p.instrucoes()).doesNotContain("[VALOR]"));
    }

    @Test
    void respostaSemFraseQueCiteTrechoNaoConsultaOJevENoTracoFicaPulada() {
        jev.resposta(frase -> "apoia", 0.97);
        modelo.chamada("parceria_consultar", "{}").passo(pedido -> new RespostaDoLlm("Aplicações previstas: {{cons:aplicacoes.previstas}}.", List.of(), null));
        long conversa = conversa();

        falar(conversa, "Quantas aplicações estão previstas?");

        assertThat(jev.recebidasDe(PontoDoJev.RESPOSTA)).isEmpty();
        assertThat(jdbc.sql("SELECT resultado || ' ' || (dados ->> 'motivo') FROM atlas_traco WHERE conversa_id = :c AND etapa = 'R5_RESPOSTA'")
                .param("c", conversa).query(String.class).single()).isEqualTo("PULADO SEM_FRASE_COM_CITACAO");
    }

    @Test
    void naoEncontreiEPontoDesligadoNaoConsultamOJev() {
        modelo.chamada(BUSCAR, busca(CONSULTA)).texto(LoopDoAgente.FRASE_NAO_ENCONTREI);
        long conversa = conversa();
        falar(conversa, "Qual o prazo para informar os geradores próprios?");
        assertThat(jev.recebidasDe(PontoDoJev.RESPOSTA)).isEmpty();

        config.limpar();
        jev.limpar();
        buscarEEscrever(pedido -> frasePrazo(marcador(pedido, PRAZO, true)));
        falar(conversa(), "Qual o prazo para informar os geradores próprios?");
        assertThat(jev.recebidas()).isEmpty();
    }

    @Test
    void noFluxoORecusadoDoJevMandaTextoRecusadoEATelaDescartaOQueRecebeu() {
        jev.resposta(frase -> "contradiz", 0.96);
        long conversa = conversa();
        modelo.chamada(BUSCAR, busca(CONSULTA))
                .passo(pedido -> new RespostaDoLlm(frasePrazo(marcador(pedido, PRAZO, true)), List.of(), null))
                .passo(pedido -> new RespostaDoLlm(frasePrazo(marcador(pedido, PRAZO, true)), List.of(), null));
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);

        var eventos = ApoioDoFluxo.eventos(ApoioDoFluxo.falarEmFluxo(pessoa, conversa, "Qual o prazo para informar os geradores próprios?"));

        assertThat(ApoioDoFluxo.nomes(eventos)).contains("texto_recusado").endsWith("fim");
        assertThat(ApoioDoFluxo.turnoSolto(jdbc, conversa)).isTrue();
    }
}
