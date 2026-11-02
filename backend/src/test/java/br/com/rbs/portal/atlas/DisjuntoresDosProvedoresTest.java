package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.RBS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.etapas;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.exigirCoerencia;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.primeira;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.todas;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.tracoDoTurno;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.ApoioDoDiagnostico.Linha;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemTexto;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.disjuntor.DisjuntoresDosProvedores;
import br.com.rbs.portal.atlas.jev.ModoDoJev;
import br.com.rbs.portal.atlas.jev.PontoDoJev;
import br.com.rbs.portal.atlas.loop.LoopDoAgente;
import br.com.rbs.portal.atlas.provedor.ProvedorAtual;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * [ATLAS] Os disjuntores do modelo de linguagem, do JEV e do Gemini (M8, problema 17 do quadro) com os provedores falsos: o provedor
 * que falha 5 vezes seguidas abre o disjuntor; aberto, a resposta sai sem chamar o provedor (o modelo dá a frase fixa de falha na hora,
 * o JEV fica como se estivesse desligado, a busca segue só na lista textual); depois de 60 s uma chamada de prova passa e, dando certo,
 * o disjuntor fecha. O estado aparece na rota 52 e a recusa deixa a etapa {@code DISJUNTOR} no traço. Contexto próprio, com os
 * disjuntores ligados (o contexto normal da suíte os desliga) e um relógio que o teste adianta; sem {@code @Transactional}, porque o
 * traço vai ao banco no fim do turno e o teste apaga o que confirmou.
 */
@TesteComJevRoteirizado
@Import({RelogioDeTesteConfig.class, EmbeddingsControlavelConfig.class})
@TestPropertySource(properties = {"atlas.disjuntores.ligado=true", "atlas.diagnostico.ligado=true", "atlas.prazo-do-resumo=PT0.5S"})
class DisjuntoresDosProvedoresTest {

    private static final String BUSCAR = "buscar_documentos";

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    JevRoteirizado jev;

    @Autowired
    ConfiguracaoDoJevDeTeste config;

    @Autowired
    EmbeddingsControlavel embeddings;

    @Autowired
    RelogioDeTeste relogio;

    @Autowired
    DisjuntoresDosProvedores disjuntores;

    @Autowired
    ProvedorAtual provedorAtual;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void preparar() {
        modelo.limpar();
        jev.limpar();
        embeddings.limpar();
        config.limpar();
        disjuntores.llm().reiniciar();
        disjuntores.jev().reiniciar();
        disjuntores.gemini().reiniciar();
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagar() {
        config.limpar();
        disjuntores.llm().reiniciar();
        disjuntores.jev().reiniciar();
        disjuntores.gemini().reiniciar();
        limpeza.apagar();
    }

    long abrir() {
        return atlas.abrirConversa(usuario(jdbc, AMARELO), SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"), null, requisicao())
                .conversaId();
    }

    ConversaDto falar(long conversa, String texto) {
        return atlas.enviarMensagem(usuario(jdbc, AMARELO), conversa, new MensagemRequisicao(texto, null, null), requisicao());
    }

    static List<String> textos(ConversaDto conversa) {
        return conversa.itens().stream().filter(ItemTexto.class::isInstance).map(item -> ((ItemTexto) item).texto()).toList();
    }

    Map<String, Object> provedorNaRota52(String nome) {
        MvcTestResult resposta = SessaoDeApi.entrar(mvc, RBS).get("/api/atlas/provedores");
        List<Map<String, Object>> provedores = lerLista(resposta, "$.provedores");
        return provedores.stream().filter(p -> nome.equals(p.get("nome"))).findFirst().orElseThrow();
    }

    long decisoes(long conversa) {
        return jdbc.sql("SELECT count(*) FROM atlas_decisao d JOIN atlas_mensagem m ON m.id = d.mensagem_id WHERE m.conversa_id = :c")
                .param("c", conversa).query(Long.class).single();
    }

    static String busca(String consulta) {
        return AtlasJson.escrever(Map.of("consulta", consulta));
    }

    // ---------------------------------------------------------------------
    // O modelo de linguagem
    // ---------------------------------------------------------------------

    @Test
    void cincoFalhasDoModeloAbremODisjuntorEARespostaSaiNaHoraSemChamarOProvedor() {
        long conversa = abrir();
        for (int i = 0; i < 5; i++) {
            modelo.falha();
            assertThat(textos(falar(conversa, "Oi"))).containsExactly(LoopDoAgente.FRASE_DA_FALHA);
        }
        assertThat(modelo.pedidos()).as("as cinco idas que falharam").hasSize(5);
        assertThat(provedorNaRota52("llm")).containsEntry("disjuntor", "ABERTO");

        modelo.texto("Voltei.");   // se o provedor fosse chamado, este passo seria consumido
        ConversaDto sexta = falar(conversa, "Oi de novo");

        assertThat(textos(sexta)).as("a frase fixa de falha, na hora").containsExactly(LoopDoAgente.FRASE_DA_FALHA);
        assertThat(modelo.pedidos()).as("o provedor não foi chamado com o disjuntor aberto").hasSize(5);
        assertThat(modelo.sobraDoRoteiro()).isEqualTo(1);
        List<Linha> traco = ultimoTurno(conversa);
        assertThat(etapas(traco)).containsSubsequence("G1_LIMITES", "LLM_IDA", "SAIDA").contains("DISJUNTOR");
        Linha disjuntor = primeira(traco, "DISJUNTOR");
        assertThat(disjuntor.resultado()).isEqualTo("RECUSADO");
        assertThat(disjuntor.dados()).containsEntry("provedor", "llm").containsEntry("estado", "ABERTO")
                .containsEntry("tentaDeNovoEmSegundos", 60);
        assertThat(primeira(traco, "LLM_IDA").resultado()).isEqualTo("ERRO");
        assertThat(primeira(traco, "SAIDA").dados()).containsEntry("tipo", "FRASE_FIXA").containsEntry("motivo", "FALHA_DO_MODELO");
        exigirCoerencia(traco);
    }

    @Test
    void depoisDeSessentaSegundosAChamadaDeProvaPassaEODisjuntorFecha() {
        long conversa = abrir();
        for (int i = 0; i < 5; i++) {
            modelo.falha();
            falar(conversa, "Oi");
        }
        assertThat(provedorNaRota52("llm")).containsEntry("disjuntor", "ABERTO");

        relogio.avancar(Duration.ofSeconds(30));
        modelo.texto("Voltei.");
        assertThat(textos(falar(conversa, "Oi"))).containsExactly(LoopDoAgente.FRASE_DA_FALHA);
        assertThat(modelo.pedidos()).hasSize(5);

        relogio.avancar(Duration.ofSeconds(31));
        assertThat(provedorNaRota52("llm")).containsEntry("disjuntor", "MEIO_ABERTO");
        assertThat(textos(falar(conversa, "Oi"))).containsExactly("Voltei.");   // a prova: o provedor foi chamado de novo
        assertThat(modelo.pedidos()).hasSize(6);
        assertThat(provedorNaRota52("llm")).containsEntry("disjuntor", "FECHADO");
    }

    @Test
    void aProvaQueFalhaReabreODisjuntorPorMaisSessentaSegundos() {
        long conversa = abrir();
        for (int i = 0; i < 5; i++) {
            modelo.falha();
            falar(conversa, "Oi");
        }
        relogio.avancar(Duration.ofSeconds(61));

        modelo.falha();
        assertThat(textos(falar(conversa, "Oi"))).containsExactly(LoopDoAgente.FRASE_DA_FALHA);   // a prova falhou
        assertThat(modelo.pedidos()).hasSize(6);
        assertThat(provedorNaRota52("llm")).containsEntry("disjuntor", "ABERTO");

        modelo.texto("Voltei.");
        falar(conversa, "Oi");
        assertThat(modelo.pedidos()).as("aberto de novo: o provedor não é chamado").hasSize(6);
    }

    @Test
    void trocarAChaveDoDebugDaOutraChanceAoModelo() {
        long conversa = abrir();
        for (int i = 0; i < 5; i++) {
            modelo.falha();
            falar(conversa, "Oi");
        }
        assertThat(provedorNaRota52("llm")).containsEntry("disjuntor", "ABERTO");

        provedorAtual.esquecerChaveDoDebug();   // o mesmo caminho de pôr ou tirar a chave pela tela de debug

        assertThat(provedorNaRota52("llm")).containsEntry("disjuntor", "FECHADO");
        modelo.texto("Voltei.");
        assertThat(textos(falar(conversa, "Oi"))).containsExactly("Voltei.");
    }

    @Test
    void ODisjuntorDoModeloNaoAtrapalhaOsOutrosProvedores() {
        long conversa = abrir();
        for (int i = 0; i < 5; i++) {
            modelo.falha();
            falar(conversa, "Oi");
        }

        Map<String, Object> jevNaRota = provedorNaRota52("jev");
        Map<String, Object> geminiNaRota = provedorNaRota52("gemini");

        assertThat(jevNaRota).containsEntry("disjuntor", "FECHADO");
        assertThat(geminiNaRota).containsEntry("disjuntor", "FECHADO");
    }

    // ---------------------------------------------------------------------
    // O JEV
    // ---------------------------------------------------------------------

    @Test
    void cincoFalhasDoJevAbremODisjuntorEOCaminhoFicaComoSeEleEstivesseDesligado() {
        config.ligar(PontoDoJev.INTENCAO, ModoDoJev.ATIVO);
        jev.falhar(PontoDoJev.INTENCAO, "HTTP_500");
        long conversa = abrir();
        for (int i = 0; i < 5; i++) {
            modelo.texto("Posso ajudar.");
            assertThat(textos(falar(conversa, "Oi"))).containsExactly("Posso ajudar.");   // o JEV caiu: o modelo responde
        }
        assertThat(jev.recebidas()).as("uma requisição por turno, todas falharam").hasSize(5);
        assertThat(provedorNaRota52("jev")).containsEntry("disjuntor", "ABERTO");

        modelo.texto("Posso ajudar.");
        long decisoesAntes = decisoes(conversa);
        ConversaDto sexta = falar(conversa, "Oi");

        assertThat(textos(sexta)).containsExactly("Posso ajudar.");   // o modelo respondeu, como sempre
        assertThat(jev.recebidas()).as("o JEV não foi chamado com o disjuntor aberto").hasSize(5);
        List<Linha> traco = ultimoTurno(conversa);
        assertThat(etapas(traco)).contains("DISJUNTOR").doesNotContain("J1_INTENCAO");   // como desligado: nem a etapa do ponto
        assertThat(primeira(traco, "DISJUNTOR").dados()).containsEntry("provedor", "jev").containsEntry("estado", "ABERTO");
        assertThat(decisoes(conversa)).as("o turno com o JEV bloqueado não gravou decisão nenhuma").isEqualTo(decisoesAntes);
        exigirCoerencia(traco);
    }

    @Test
    void depoisDeSessentaSegundosOJevRecebeUmaChamadaDeProvaEOsPontosVoltam() {
        config.ligar(PontoDoJev.INTENCAO, ModoDoJev.ATIVO);
        jev.falhar(PontoDoJev.INTENCAO, "TIMEOUT");
        long conversa = abrir();
        for (int i = 0; i < 5; i++) {
            modelo.texto("Posso ajudar.");
            falar(conversa, "Oi");
        }
        relogio.avancar(Duration.ofSeconds(61));
        assertThat(provedorNaRota52("jev")).containsEntry("disjuntor", "MEIO_ABERTO");
        jev.intencao("outro", 0.95);   // o JEV voltou
        modelo.texto("Posso ajudar.");

        falar(conversa, "Oi");

        assertThat(jev.recebidas()).as("a chamada de prova").hasSize(6);
        assertThat(provedorNaRota52("jev")).containsEntry("disjuntor", "FECHADO");
        modelo.texto("Posso ajudar.");
        falar(conversa, "Oi");
        assertThat(jev.recebidas()).hasSize(7);
    }

    @Test
    void jevSemChaveNaoAbreODisjuntor() {
        config.ligar(PontoDoJev.INTENCAO, ModoDoJev.ATIVO);
        jev.falhar(PontoDoJev.INTENCAO, "SEM_CHAVE");
        long conversa = abrir();
        for (int i = 0; i < 8; i++) {
            modelo.texto("Posso ajudar.");
            falar(conversa, "Oi");
        }
        assertThat(jev.recebidas()).hasSize(8);
        assertThat(provedorNaRota52("jev")).containsEntry("disjuntor", "FECHADO");
    }

    // ---------------------------------------------------------------------
    // O Gemini
    // ---------------------------------------------------------------------

    @Test
    void cincoFalhasDoGeminiAbremODisjuntorEABuscaSeguePelaListaTextual() {
        embeddings.falharNaConsulta(true);
        long conversa = abrir();
        modelo.chamada(BUSCAR, busca("prazo dos geradores")).chamada(BUSCAR, busca("energia por kVA")).chamada(BUSCAR, busca("seguro"))
                .texto("Veja o manual.");
        falar(conversa, "Tudo sobre a operação.");
        modelo.chamada(BUSCAR, busca("credenciais")).chamada(BUSCAR, busca("portões")).texto("Veja o manual.");
        falar(conversa, "E o resto?");
        assertThat(embeddings.consultas()).as("cinco chamadas ao Gemini, todas falharam").hasSize(5);
        assertThat(provedorNaRota52("gemini")).containsEntry("disjuntor", "ABERTO");

        modelo.chamada(BUSCAR, busca("prazo para informar os geradores")).texto("Veja o manual.");
        falar(conversa, "Até quando informo os geradores?");

        assertThat(embeddings.consultas()).as("o Gemini não foi chamado com o disjuntor aberto").hasSize(5);
        List<Linha> traco = ultimoTurno(conversa);
        Linha busca = primeira(traco, "BUSCA");
        assertThat(busca.resultado()).isEqualTo("OK");
        assertThat(busca.dados()).containsEntry("degradado", true).containsEntry("motivoDoDegradado", "EMBEDDING_FALHOU")
                .containsEntry("erroDoEmbedding", "DisjuntorAbertoException");
        assertThat(ApoioDoDiagnostico.tudoOQueOTracoGuarda(jdbc, conversa)).doesNotContain("503");
        assertThat((List<?>) busca.dados().get("vetorial")).isEmpty();
        assertThat((List<?>) busca.dados().get("escolhidos")).as("a lista textual ainda acha o manual").isNotEmpty();
        Linha disjuntor = primeira(traco, "DISJUNTOR");
        assertThat(disjuntor.dados()).containsEntry("provedor", "gemini").containsEntry("estado", "ABERTO");
        assertThat(todas(traco, "DISJUNTOR")).as("uma por provedor por turno").hasSize(1);
        exigirCoerencia(traco);
    }

    @Test
    void depoisDeSessentaSegundosOGeminiRecebeUmaChamadaDeProvaEABuscaVoltaAoVetor() {
        embeddings.falharNaConsulta(true);
        long conversa = abrir();
        modelo.chamada(BUSCAR, busca("prazo dos geradores")).chamada(BUSCAR, busca("energia por kVA")).chamada(BUSCAR, busca("seguro"))
                .texto("Veja o manual.");
        falar(conversa, "Tudo sobre a operação.");
        modelo.chamada(BUSCAR, busca("credenciais")).chamada(BUSCAR, busca("portões")).texto("Veja o manual.");
        falar(conversa, "E o resto?");
        assertThat(provedorNaRota52("gemini")).containsEntry("disjuntor", "ABERTO");

        relogio.avancar(Duration.ofSeconds(61));
        embeddings.falharNaConsulta(false);
        modelo.chamada(BUSCAR, busca("prazo para informar os geradores")).texto("Veja o manual.");
        falar(conversa, "Até quando informo os geradores?");

        assertThat(embeddings.consultas()).as("a chamada de prova").hasSize(6);
        Linha busca = primeira(ultimoTurno(conversa), "BUSCA");
        assertThat(busca.dados()).doesNotContainKeys("degradado", "motivoDoDegradado");
        assertThat(provedorNaRota52("gemini")).containsEntry("disjuntor", "FECHADO");
    }

    @Test
    void geminiDesligadoOuSemChaveNaoContaComoFalha() {
        embeddings.desligado(true);
        long conversa = abrir();
        for (int i = 0; i < 4; i++) {
            modelo.chamada(BUSCAR, busca("prazo dos geradores")).chamada(BUSCAR, busca("energia por kVA")).texto("Veja o manual.");
            falar(conversa, "Pergunta " + i);
        }
        assertThat(provedorNaRota52("gemini")).containsEntry("disjuntor", "FECHADO");
    }

    /** O último turno da conversa: as etapas dele, na ordem gravada. */
    private List<Linha> ultimoTurno(long conversa) {
        List<Linha> todas = ApoioDoDiagnostico.tracoDaConversa(jdbc, conversa);
        java.util.UUID ultimo = todas.get(todas.size() - 1).turno();
        // a etapa mais recente pode ser um resumo atrasado de outro turno: vale o turno do último SAIDA gravado
        for (int i = todas.size() - 1; i >= 0; i--) {
            if ("SAIDA".equals(todas.get(i).etapa())) {
                ultimo = todas.get(i).turno();
                break;
            }
        }
        java.util.UUID doTurno = ultimo;
        return todas.stream().filter(l -> l.turno().equals(doTurno)).sorted(java.util.Comparator.comparingInt(Linha::ordem)).toList();
    }
}
