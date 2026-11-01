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
import static br.com.rbs.portal.atlas.FontesNoChatTest.trechosDaUltimaBusca;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemTexto;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.contexto.AtlasContextoFabrica;
import br.com.rbs.portal.atlas.jev.ModoDoJev;
import br.com.rbs.portal.atlas.jev.PontoDoJev;
import br.com.rbs.portal.atlas.jev.ServicoDoJev;
import br.com.rbs.portal.atlas.jev.ServicoDoJev.JuizoDoTrecho;
import br.com.rbs.portal.atlas.jev.ServicoDoJev.TrechoParaJulgar;
import br.com.rbs.portal.atlas.loop.LoopDoAgente;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * [ATLAS] O ponto {@code fontes} do JEV (R3, M7), com o JEV e o modelo roteirizados e o kit de documentos no banco de teste: a
 * relevância de cada trecho que a busca devolveu. Em ATIVO, o trecho abaixo do limiar sai do resultado que o modelo recebe, e, sem
 * nenhum, a ferramenta diz "nenhum trecho relevante; reformule a consulta ou diga que não encontrou"; em SOMBRA nada sai; o trecho
 * que o JEV não julgou (erro, orçamento) segue. As requisições de uma busca saem em paralelo, sem transação aberta, e o orçamento do
 * turno é de 20. Não é {@code @Transactional}: o traço e as decisões vão ao banco no fim do turno e o teste apaga o que confirmou.
 */
@TesteComJevRoteirizado
@TestPropertySource(properties = {"atlas.diagnostico.ligado=true", "atlas.diagnostico.marca=true", "atlas.prazo-do-resumo=PT0.5S"})
class JevFontesTest {

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
    JdbcClient jdbc;

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

    private long conversa() {
        return atlas.abrirConversa(usuario(jdbc, AMARELO), SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"), null, requisicao())
                .conversaId();
    }

    private ConversaDto falar(long conversa, String texto) {
        return atlas.enviarMensagem(usuario(jdbc, AMARELO), conversa, new MensagemRequisicao(texto, null, null), requisicao());
    }

    private List<Map<String, Object>> decisoes(long conversa) {
        return jdbc.sql("SELECT d.ponto, d.modo, d.pergunta, d.confianca, d.acao, d.alvo, d.erro FROM atlas_decisao d JOIN atlas_mensagem m "
                + "ON m.id = d.mensagem_id WHERE m.conversa_id = :c AND d.ponto = 'fontes' ORDER BY d.id").param("c", conversa)
                .query().listOfRows();
    }

    private Map<String, Object> etapaDoJev(long conversa) {
        String json = jdbc.sql("SELECT dados::text FROM atlas_traco WHERE conversa_id = :c AND etapa = 'R3_FONTES'").param("c", conversa)
                .query(String.class).single();
        return AtlasJson.ler(json, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
        });
    }

    /** O JEV acha relevante o trecho que traz o prazo e irrelevante o resto. */
    private void soOPrazoEIrrelevante() {
        jev.fontes(estado -> estado.contains(PRAZO) ? 0.92 : 0.05);
    }

    @Test
    void emAtivoOTrechoIrrelevanteSaiDoResultadoQueOModeloRecebe() {
        config.ligar(PontoDoJev.FONTES, ModoDoJev.ATIVO);
        soOPrazoEIrrelevante();
        long conversa = conversa();
        modelo.chamada(BUSCAR, busca(CONSULTA)).passo(pedido -> {
            List<Map<String, Object>> trechos = trechosDaUltimaBusca(pedido);
            assertThat(trechos).isNotEmpty().allSatisfy(t -> assertThat((String) t.get("texto")).contains(PRAZO));
            return new RespostaDoLlm(frasePrazo(marcador(pedido, PRAZO, true)), List.of(), null);
        });

        ConversaDto resposta = falar(conversa, "Qual o prazo para informar os geradores próprios?");

        int pedidos = jev.recebidasDe(PontoDoJev.FONTES).size();
        assertThat(pedidos).as("uma requisição por trecho que a busca devolveu").isBetween(2, 6);
        assertThat(jev.recebidasDe(PontoDoJev.FONTES)).allSatisfy(r -> assertThat(r.pedido().perguntas().keySet()).containsExactly("relevante"));
        assertThat(jev.algumaComTransacaoAberta()).isFalse();
        ItemTexto texto = (ItemTexto) resposta.itens().stream().filter(ItemTexto.class::isInstance).reduce((a, b) -> b).orElseThrow();
        assertThat(texto.texto()).contains(PRAZO);
        // as decisões: uma por trecho, com o alvo, e a ação de cada um
        List<Map<String, Object>> linhas = decisoes(conversa);
        assertThat(linhas).hasSize(pedidos);
        assertThat(linhas).allSatisfy(d -> {
            assertThat(d.get("modo")).isEqualTo("ATIVO");
            assertThat(d.get("pergunta")).isEqualTo("relevante");
            assertThat((String) d.get("alvo")).startsWith("trecho:");
        });
        assertThat(linhas).extracting(d -> d.get("acao")).contains("fez: manteve o trecho").contains("fez: tirou o trecho do resultado");
        // o traço: a etapa do R3 fica dentro da ferramenta (fora da soma do caminho crítico), com um item por trecho e sem o texto deles
        var turno = ApoioDoDiagnostico.tracoDoTurno(jdbc, conversa);
        ApoioDoDiagnostico.exigirCoerencia(turno);
        assertThat(ApoioDoDiagnostico.etapas(turno)).contains("BUSCA", "R3_FONTES");
        Map<String, Object> r3 = ApoioDoDiagnostico.primeira(turno, "R3_FONTES").dados();
        assertThat(r3).containsEntry("ponto", "fontes").containsEntry("modo", "ATIVO").containsEntry("requisicoes", pedidos);
        assertThat((List<?>) r3.get("itens")).hasSize(pedidos);
        assertThat(ApoioDoDiagnostico.primeira(turno, "R3_FONTES").resultado()).isEqualTo("RECUSADO");
        assertThat(ApoioDoDiagnostico.tudoOQueOTracoGuarda(jdbc, conversa)).doesNotContain("Podem pedir a janela extra");
    }

    @Test
    void semNenhumTrechoRelevanteAFerramentaDizParaReformularEOModeloResponde() {
        config.ligar(PontoDoJev.FONTES, ModoDoJev.ATIVO);
        jev.fontes(estado -> 0.02);
        long conversa = conversa();
        modelo.chamada(BUSCAR, busca(CONSULTA)).passo(pedido -> {
            Map<String, Object> resultado = AtlasJson.ler(pedido.mensagens().get(pedido.mensagens().size() - 1).conteudo(),
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            assertThat((List<?>) resultado.get("trechos")).isEmpty();
            assertThat(resultado.get("observacao")).isEqualTo("nenhum trecho relevante; reformule a consulta ou diga que não encontrou");
            return new RespostaDoLlm(LoopDoAgente.FRASE_NAO_ENCONTREI, List.of(), null);
        });

        ConversaDto resposta = falar(conversa, "Qual o prazo para informar os geradores próprios?");

        ItemTexto texto = (ItemTexto) resposta.itens().stream().filter(ItemTexto.class::isInstance).reduce((a, b) -> b).orElseThrow();
        assertThat(texto.texto()).isEqualTo(LoopDoAgente.TEXTO_NAO_ENCONTREI);
    }

    @Test
    void emSombraNadaSaiEAsDecisoesDizemOQueTeriaFeito() {
        config.ligar(PontoDoJev.FONTES, ModoDoJev.SOMBRA);
        soOPrazoEIrrelevante();
        long conversa = conversa();
        List<Integer> quantosTrechos = new ArrayList<>();
        modelo.chamada(BUSCAR, busca(CONSULTA)).passo(pedido -> {
            List<Map<String, Object>> trechos = trechosDaUltimaBusca(pedido);
            quantosTrechos.add(trechos.size());
            return new RespostaDoLlm(frasePrazo(marcador(pedido, PRAZO, true)), List.of(), null);
        });

        falar(conversa, "Qual o prazo para informar os geradores próprios?");

        // com o JEV desligado a busca devolve os mesmos trechos
        modelo.limpar();
        config.limpar();
        List<Integer> semJev = new ArrayList<>();
        modelo.chamada(BUSCAR, busca(CONSULTA)).passo(pedido -> {
            semJev.add(trechosDaUltimaBusca(pedido).size());
            return new RespostaDoLlm(frasePrazo(marcador(pedido, PRAZO, true)), List.of(), null);
        });
        falar(conversa(), "Qual o prazo para informar os geradores próprios?");
        assertThat(quantosTrechos).isEqualTo(semJev);
        assertThat(quantosTrechos.get(0)).isGreaterThan(1);
        assertThat(decisoes(conversa)).extracting(d -> d.get("acao")).contains("teria feito: tirar o trecho do resultado", "teria feito: manter o trecho")
                .allSatisfy(acao -> assertThat((String) acao).startsWith("teria feito: "));
        assertThat(decisoes(conversa)).extracting(d -> d.get("modo")).containsOnly("SOMBRA");
    }

    @Test
    void trechoQueOJevNaoJulgouSegue() {
        config.ligar(PontoDoJev.FONTES, ModoDoJev.ATIVO);
        jev.falhar(PontoDoJev.FONTES, "HTTP_429");
        long conversa = conversa();
        List<Integer> trechos = new ArrayList<>();
        modelo.chamada(BUSCAR, busca(CONSULTA)).passo(pedido -> {
            trechos.add(trechosDaUltimaBusca(pedido).size());
            return new RespostaDoLlm(frasePrazo(marcador(pedido, PRAZO, true)), List.of(), null);
        });

        falar(conversa, "Qual o prazo para informar os geradores próprios?");

        assertThat(trechos.get(0)).isEqualTo(jev.recebidasDe(PontoDoJev.FONTES).size());   // todos seguiram
        assertThat(decisoes(conversa)).allSatisfy(d -> assertThat(d.get("erro")).isEqualTo("HTTP_429"));
        assertThat(etapaDoJev(conversa)).containsKey("itens");
        assertThat(jdbc.sql("SELECT resultado FROM atlas_traco WHERE conversa_id = :c AND etapa = 'R3_FONTES'").param("c", conversa)
                .query(String.class).single()).isEqualTo("ERRO");
    }

    @Test
    void oStateDeCadaTrechoLevaSoAPerguntaEAqueleTrecho() {
        config.ligar(PontoDoJev.FONTES, ModoDoJev.SOMBRA);
        jev.fontes(estado -> 0.5);
        long conversa = conversa();
        modelo.chamada(BUSCAR, busca(CONSULTA)).texto(LoopDoAgente.FRASE_NAO_ENCONTREI);

        falar(conversa, "Qual o prazo para informar os geradores próprios, da Cliente Amarelo?");

        List<JevRoteirizado.Recebida> recebidas = jev.recebidasDe(PontoDoJev.FONTES);
        assertThat(recebidas).isNotEmpty();
        assertThat(recebidas).allSatisfy(r -> {
            assertThat(r.pedido().estado()).startsWith("Pergunta: " + CONSULTA + "\n\nTrecho de documento:\n");
            assertThat(r.pedido().estado()).doesNotContain("Cliente Amarelo");
        });
        assertThat(recebidas.stream().map(r -> r.pedido().estado()).distinct().count()).as("cada requisição, um trecho").isEqualTo(recebidas.size());
    }

    @Test
    void asRequisicoesDeUmaBuscaSaemEmParalelo() {
        config.ligar(PontoDoJev.FONTES, ModoDoJev.SOMBRA);
        jev.passo(PontoDoJev.FONTES, pedido -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrompida) {
                Thread.currentThread().interrupt();
            }
            return new br.com.rbs.portal.atlas.jev.ProvedorDeJev.RespostaDoJev(Map.of("relevante",
                    br.com.rbs.portal.atlas.jev.ProvedorDeJev.RespostaDaPergunta.deNota(0.5)), 250);
        });
        long conversa = conversa();
        modelo.chamada(BUSCAR, busca(CONSULTA)).texto(LoopDoAgente.FRASE_NAO_ENCONTREI);

        long inicio = System.nanoTime();
        falar(conversa, "Qual o prazo para informar os geradores próprios?");
        long ms = (System.nanoTime() - inicio) / 1_000_000;

        int requisicoes = jev.recebidasDe(PontoDoJev.FONTES).size();
        assertThat(requisicoes).isGreaterThanOrEqualTo(3);
        assertThat(ms).as("uma rodada de espera por busca, e não uma por trecho (" + requisicoes + " x 250 ms)")
                .isLessThan(250L * requisicoes - 100);
    }

    // ---------------------------------------------------------------------
    // O orçamento de 20 requisições por turno
    // ---------------------------------------------------------------------

    private static List<TrechoParaJulgar> trechos(int quantos, int desde) {
        List<TrechoParaJulgar> lista = new ArrayList<>();
        for (int i = 0; i < quantos; i++) {
            lista.add(new TrechoParaJulgar("trecho:" + (desde + i), "Texto do trecho " + (desde + i)));
        }
        return lista;
    }

    @Test
    void depoisDeVinteRequisicoesNoTurnoOQueFaltaSegueSemJevERegistraNoTraco() {
        config.ligar(PontoDoJev.FONTES, ModoDoJev.ATIVO);
        jev.fontes(estado -> 0.0);   // tudo irrelevante: o que o JEV julgar sai
        long conversa = conversa();
        AtlasContexto contexto = contextos.para(usuario(jdbc, AMARELO), SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"), conversa, requisicao());

        List<JuizoDoTrecho> primeira = servico.fontes(contexto, "pergunta", trechos(15, 0));
        List<JuizoDoTrecho> segunda = servico.fontes(contexto, "pergunta", trechos(10, 100));
        List<JuizoDoTrecho> terceira = servico.fontes(contexto, "pergunta", trechos(3, 200));

        assertThat(primeira).hasSize(15);
        assertThat(segunda).as("só 5 requisições couberam").hasSize(5).extracting(JuizoDoTrecho::alvo)
                .containsExactly("trecho:100", "trecho:101", "trecho:102", "trecho:103", "trecho:104");
        assertThat(terceira).as("o orçamento acabou: tudo segue sem JEV").isEmpty();
        assertThat(jev.recebidasDe(PontoDoJev.FONTES)).hasSize(20);
        assertThat(contexto.jev().restante()).isZero();
        assertThat(contexto.jev().requisicoes()).isEqualTo(20);
    }

    @Test
    void oOrcamentoEstouradoApareceNoTracoComOMotivo() {
        config.ligar(PontoDoJev.FONTES, ModoDoJev.ATIVO);
        jev.fontes(estado -> 0.5);
        long conversa = conversa();
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        var traco = registro.abrir(parceria, conversa);
        AtlasContexto contexto = contextos.para(usuario(jdbc, AMARELO), parceria, conversa, requisicao()).comTraco(traco);

        servico.fontes(contexto, "pergunta", trechos(15, 0));
        servico.fontes(contexto, "pergunta", trechos(10, 100));
        servico.fontes(contexto, "pergunta", trechos(3, 200));
        traco.encerrar();

        List<Map<String, Object>> etapas = jdbc.sql("SELECT resultado, dados::text AS dados FROM atlas_traco WHERE conversa_id = :c "
                + "AND etapa = 'R3_FONTES' ORDER BY ordem").param("c", conversa).query().listOfRows();
        assertThat(etapas).hasSize(3);
        Map<String, Object> cortada = AtlasJson.ler((String) etapas.get(1).get("dados"), new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
        });
        assertThat(cortada).containsEntry("requisicoes", 5).containsEntry("semJev", 5).containsEntry("motivoDoCorte", "ORCAMENTO_ESGOTADO");
        assertThat(etapas.get(2).get("resultado")).isEqualTo("PULADO");
        assertThat((String) etapas.get(2).get("dados")).contains("\"motivo\": \"ORCAMENTO_ESGOTADO\"");
    }

    @Autowired
    br.com.rbs.portal.atlas.diagnostico.RegistroDoTraco registro;
}
