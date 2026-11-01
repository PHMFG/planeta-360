package br.com.rbs.portal.documentos;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.embeddings.EmbeddingsDesligado;
import br.com.rbs.portal.atlas.embeddings.ProvedorDeEmbeddings;
import br.com.rbs.portal.documentos.CarregadorDoKit.Resultado;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] O que acontece na subida da API e com o kit: o PROCESSANDO que sobrou de uma queda vira ERRO ("processamento interrompido"),
 * a API avisa no log (e a aba mostra) quando há documento indexado com outro modelo de embeddings, o kit sobe uma vez só, e o
 * {@code DocumentosDaAmostra} não cria nada com os embeddings desligados. Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class IngestaoNaSubidaTest extends ApoioDeDocumentos {

    @Autowired
    IngestaoService ingestao;

    @Autowired
    CarregadorDoKit carregador;

    @Autowired
    ProvedorDeEmbeddings embeddings;

    @Autowired
    PrazosDaAmostra prazosDaAmostra;

    @Autowired(required = false)
    DocumentosDaAmostra amostraNoContextoDeTeste;

    private ListAppender<ILoggingEvent> ouvir(Class<?> classe) {
        Logger logger = (Logger) LoggerFactory.getLogger(classe);
        ListAppender<ILoggingEvent> lista = new ListAppender<>();
        lista.start();
        logger.addAppender(lista);
        return lista;
    }

    private void parar(Class<?> classe, ListAppender<ILoggingEvent> lista) {
        ((Logger) LoggerFactory.getLogger(classe)).detachAppender(lista);
    }

    @Test
    void processandoQueSobrouDeUmaQuedaViraErroInterrompidoComHistorico() {
        SessaoDeApi rbs = entrar(RBS);
        long id = enviarOk(rbs, "comunicado.pdf", KitDeDocumentos.por("D09").conteudo(), global("Comunicado de teste", "teste-orfao"));
        acao(rbs, id, "APROVAR");
        assertThat(statusDe(id)).isEqualTo("PROCESSANDO");
        ListAppender<ILoggingEvent> log = ouvir(IngestaoService.class);

        int quantos = ingestao.recuperarOrfaos();
        parar(IngestaoService.class, log);

        assertThat(quantos).isGreaterThanOrEqualTo(1);
        assertThat(statusDe(id)).isEqualTo("ERRO");
        assertThat(jdbc.sql("SELECT erro FROM documento WHERE id = :id").param("id", id).query(String.class).single())
                .isEqualTo("Processamento interrompido: a API foi reiniciada antes de terminar. Reindexe o documento.");
        assertThat(contar("SELECT count(*) FROM historico WHERE entidade = 'documento' AND entidade_id = ? AND valor_anterior = 'PROCESSANDO' AND valor_novo = 'ERRO'", id))
                .isEqualTo(1);
        assertThat(log.list).anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("estavam em processamento"));
        // e a RBS reindexa: já tinha sido aprovado
        assertThat(lerJson(rbs.get("/api/documentos/" + id), "$.acoes[0]")).isEqualTo("REINDEXAR");
        assertThat(ingestao.recuperarOrfaos()).as("nada mais a recuperar").isZero();
    }

    @Test
    void documentoIndexadoComOutroModeloGeraErroNoLogComAContagemEOComandoEAAbaMostraOAviso() {
        SessaoDeApi rbs = entrar(RBS);
        long d02 = doKit("D02");
        long d03 = doKit("D03");
        assertThat(ingestao.avisarModeloDiferente()).as("o kit foi indexado com o modelo de agora").isZero();
        jdbc.sql("UPDATE documento SET modelo_embedding = 'gemini-embedding-2' WHERE id IN (:ids)").param("ids", List.of(d02, d03)).update();
        ListAppender<ILoggingEvent> log = ouvir(IngestaoService.class);

        long diferentes = ingestao.avisarModeloDiferente();
        parar(IngestaoService.class, log);

        assertThat(diferentes).isEqualTo(2);
        assertThat(log.list).anyMatch(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains("2 documento(s) indexado(s) com um modelo")
                && e.getFormattedMessage().contains("DROP DATABASE") && e.getFormattedMessage().contains("scripts/preparar-banco-atlas.sh"));
        assertThat(lerJson(rbs.get("/api/documentos/" + d02), "$.modeloDiferente")).isEqualTo(true);
        assertThat(lerJson(rbs.get("/api/documentos/" + d02), "$.modelo")).isEqualTo("gemini-embedding-2");
        assertThat(lerJson(rbs.get("/api/documentos/" + doKit("D06")), "$.modeloDiferente")).isEqualTo(false);
    }

    @Test
    void ocarregadorDoKitNaoEnviaDeNovoOQueOGrupoJaTem() {
        var rbs = carregador.equipeDaRbs().orElseThrow();
        long antes = contar("SELECT count(*) FROM documento");

        Resultado resultado = carregador.carregar(rbs);

        assertThat(resultado.enviados()).isEmpty();
        assertThat(resultado.recusas()).isEmpty();
        assertThat(contar("SELECT count(*) FROM documento")).isEqualTo(antes);
        assertThat(resultado.estados()).containsEntry("D01", "SUBSTITUIDO").containsEntry("D02", "INDEXADO").containsEntry("D11", "AGUARDANDO_APROVACAO")
                .containsEntry("D12", "ERRO").containsEntry("D13", "REVOGADO").hasSize(13);
    }

    @Test
    void aAmostraDeDevNaoCriaNadaComOsEmbeddingsDesligadosEAvisaNoLog() {
        long antes = contar("SELECT count(*) FROM documento");
        ListAppender<ILoggingEvent> log = ouvir(DocumentosDaAmostra.class);

        long prazosAntes = contar("SELECT count(*) FROM prazo");

        new DocumentosDaAmostra(carregador, new EmbeddingsDesligado(), prazosDaAmostra).run(null);
        parar(DocumentosDaAmostra.class, log);

        assertThat(contar("SELECT count(*) FROM documento")).isEqualTo(antes);
        assertThat(contar("SELECT count(*) FROM prazo")).as("sem o kit também não há prazos de documento").isEqualTo(prazosAntes);
        assertThat(log.list).anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("embeddings estão desligados")
                && e.getFormattedMessage().contains("a próxima subida cria e indexa o kit"));
    }

    @Test
    void aAmostraDeDevComOsEmbeddingsLigadosNaoRepeteOKitEDizOEstado() {
        long antes = contar("SELECT count(*) FROM documento");
        ListAppender<ILoggingEvent> log = ouvir(DocumentosDaAmostra.class);

        new DocumentosDaAmostra(carregador, embeddings, prazosDaAmostra).run(null);
        parar(DocumentosDaAmostra.class, log);

        assertThat(contar("SELECT count(*) FROM documento")).isEqualTo(antes);
        assertThat(log.list).anyMatch(e -> e.getLevel() == Level.INFO && e.getFormattedMessage().contains("o kit já estava no banco"));
        assertThat(log.list).anyMatch(e -> e.getLevel() == Level.INFO && e.getFormattedMessage().contains("16 prazos do Manual de Operações 2027 v2 e dos planos comerciais criados"));
        assertThat(contar("SELECT count(*) FROM prazo")).as("a amostra de dev cria os prazos do D02 e dos planos D03 a D05, uma vez só").isEqualTo(16L);
    }

    @Test
    void aAmostraDeDocumentosSoExisteNosPerfisDevEDemoENuncaNoTesteNemEmProducao() {
        assertThat(DocumentosDaAmostra.class.getAnnotation(Profile.class).value()).containsExactlyInAnyOrder("dev", "demo");
        assertThat(amostraNoContextoDeTeste).as("o perfil test usa o KitDeDocumentosDeTeste").isNull();
    }

    @Test
    void aFraseDaRecusaSemOGatilhoEAMensagemDaExcecao() {
        assertThat(CarregadorDoKit.fraseDaRecusa(new IllegalStateException("outro erro"))).isEqualTo("outro erro");
    }
}
