package br.com.rbs.portal.documentos;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.embeddings.EmbeddingsDeTeste;
import br.com.rbs.portal.atlas.embeddings.EmbeddingsIndisponivelException;
import br.com.rbs.portal.atlas.embeddings.ProvedorDeEmbeddings;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * [ATLAS] O que só aparece SEM uma transação envolvendo o teste, no molde do LoopSemTransacaoTest: a ingestão roda numa thread do
 * executor próprio ({@code atlas-ingestao-}) e <b>nunca</b> na thread do callback do commit; os embeddings são pedidos sem nenhuma
 * transação aberta; e o passo que falha não deixa nada gravado. Esta classe NÃO é {@code @Transactional}: aprovar confirma de verdade,
 * o callback {@code afterCommit} dispara e, com {@code atlas.ingestao.sincrona=true}, espera a tarefa terminar. O que o teste criou é
 * apagado no fim (os documentos, os trechos, os arquivos e as linhas do histórico, que são imutáveis por gatilho: o gatilho é
 * desligado só para esta limpeza, num {@code try/finally}). Bloqueante.
 */
@TesteDeIntegracao
@Import(IngestaoSemTransacaoTest.ProvedorQueGrava.class)
class IngestaoSemTransacaoTest extends ApoioDeDocumentos {

    /** Os embeddings de teste, anotando em que thread e com ou sem transação cada pedido chegou, e podendo falhar quando o teste mandar. */
    static final class GravadorDeEmbeddings implements ProvedorDeEmbeddings {

        final EmbeddingsDeTeste verdadeiro = new EmbeddingsDeTeste();
        final List<Boolean> transacaoAtiva = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();
        volatile boolean falhar;
        volatile boolean vetorCurto;

        void limpar() {
            transacaoAtiva.clear();
            threads.clear();
            falhar = false;
            vetorCurto = false;
        }

        @Override
        public String modelo() {
            return verdadeiro.modelo();
        }

        @Override
        public boolean ligado() {
            return true;
        }

        @Override
        public String motivoDeDesligado() {
            return "";
        }

        @Override
        public List<float[]> paraTrechos(String tituloDoDocumento, List<TextoDoTrecho> trechos) {
            transacaoAtiva.add(TransactionSynchronizationManager.isActualTransactionActive());
            threads.add(Thread.currentThread().getName());
            if (falhar) {
                throw new EmbeddingsIndisponivelException("O Gemini respondeu 503: indisponível.");
            }
            List<float[]> vetores = verdadeiro.paraTrechos(tituloDoDocumento, trechos);
            if (vetorCurto) {
                List<float[]> curtos = new ArrayList<>();
                vetores.forEach(v -> curtos.add(new float[] {1f, 0f, 0f}));
                return curtos;
            }
            return vetores;
        }

        @Override
        public float[] paraConsulta(String consulta) {
            return verdadeiro.paraConsulta(consulta);
        }
    }

    @TestConfiguration
    static class ProvedorQueGrava {

        @Bean
        @Primary
        GravadorDeEmbeddings gravadorDeEmbeddings() {
            return new GravadorDeEmbeddings();
        }
    }

    @Autowired
    GravadorDeEmbeddings embeddings;

    private final List<Long> criados = new ArrayList<>();

    @BeforeEach
    void limparOGravador() {
        embeddings.limpar();
    }

    @AfterEach
    void apagarOQueOTesteConfirmou() {
        if (criados.isEmpty()) {
            return;
        }
        List<Long> arquivos = jdbc.sql("SELECT arquivo_id FROM documento WHERE id IN (:ids)").param("ids", criados).query(Long.class).list();
        jdbc.sql("DELETE FROM documento_trecho WHERE documento_id IN (:ids)").param("ids", criados).update();
        jdbc.sql("DELETE FROM documento WHERE id IN (:ids)").param("ids", criados).update();
        if (!arquivos.isEmpty()) {
            jdbc.sql("DELETE FROM arquivo WHERE id IN (:ids)").param("ids", arquivos).update();
        }
        jdbc.sql("ALTER TABLE historico DISABLE TRIGGER rn_historico_imutavel").update();
        try {
            jdbc.sql("DELETE FROM historico WHERE entidade = 'documento' AND entidade_id IN (:ids)").param("ids", criados).update();
        } finally {
            jdbc.sql("ALTER TABLE historico ENABLE TRIGGER rn_historico_imutavel").update();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM documento WHERE id IN (:ids)").param("ids", criados).query(Long.class).single())
                .as("a limpeza deixou sobra").isZero();
        criados.clear();
    }

    private long enviarEAprovar(SessaoDeApi rbs, String chave) {
        long id = enviarOk(rbs, "comunicado.pdf", KitDeDocumentos.por("D09").conteudo(), global("Comunicado de teste", chave));
        criados.add(id);
        return id;
    }

    @Test
    void aIngestaoRodaNumaThreadDoExecutorSemTransacaoAbertaNosEmbeddingsEOCommitJaConfirmouTudo() {
        SessaoDeApi rbs = entrar(RBS);
        long id = enviarEAprovar(rbs, "teste-sem-transacao");
        String threadDoTeste = Thread.currentThread().getName();

        MvcTestResult aprovado = acao(rbs, id, "APROVAR");

        // a resposta da rota 57 é a do momento da aprovação (PROCESSANDO); o modo síncrono esperou a ingestão terminar antes de responder
        assertThat(aprovado).hasStatus(HttpStatus.OK);
        assertThat(lerJson(aprovado, "$.status")).isEqualTo("PROCESSANDO");
        assertThat(statusDe(id)).as("confirmado de verdade, lido por outra conexão").isEqualTo("INDEXADO");
        assertThat(embeddings.transacaoAtiva).as("transação aberta em cada pedido de embeddings").containsExactly(false);
        assertThat(embeddings.threads).hasSize(1);
        assertThat(embeddings.threads.get(0)).as("nunca na thread do callback do commit").startsWith("atlas-ingestao-").isNotEqualTo(threadDoTeste);
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ? AND ativo", id)).isGreaterThan(0);
        assertThat(jdbc.sql("SELECT modelo_embedding FROM documento WHERE id = :id").param("id", id).query(String.class).single())
                .isEqualTo("embeddings-de-teste");
    }

    @Test
    void seOProvedorDeEmbeddingsFalhaODocumentoFicaEmErroComOMotivoESemNenhumTrecho() {
        SessaoDeApi rbs = entrar(RBS);
        long id = enviarEAprovar(rbs, "teste-falha-embeddings");
        embeddings.falhar = true;

        acao(rbs, id, "APROVAR");

        assertThat(statusDe(id)).isEqualTo("ERRO");
        assertThat(jdbc.sql("SELECT erro FROM documento WHERE id = :id").param("id", id).query(String.class).single())
                .isEqualTo("O Gemini respondeu 503: indisponível.");
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ?", id)).isZero();
        assertThat(contar("SELECT count(*) FROM historico WHERE entidade = 'documento' AND entidade_id = ? AND valor_novo = 'ERRO'", id)).isEqualTo(1);
        assertThat(embeddings.transacaoAtiva).containsExactly(false);

        // a RBS corrige a causa e reindexa: o documento já tinha sido aprovado, então sai de ERRO
        embeddings.falhar = false;
        MvcTestResult reindexado = acao(rbs, id, "REINDEXAR");
        assertThat(reindexado).hasStatus(HttpStatus.OK);
        assertThat(statusDe(id)).isEqualTo("INDEXADO");
        assertThat(jdbc.sql("SELECT erro FROM documento WHERE id = :id").param("id", id).query(String.class).optional()).isEmpty();
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ? AND ativo", id)).isGreaterThan(0);
    }

    @Test
    void seOPassoDeGravarOsTrechosFalhaNadaFicaGravadoEODocumentoVaiParaErro() {
        SessaoDeApi rbs = entrar(RBS);
        long id = enviarEAprovar(rbs, "teste-falha-gravacao");
        embeddings.vetorCurto = true;   // 3 dimensões onde o banco exige 768: o INSERT do passo 4 falha

        acao(rbs, id, "APROVAR");

        assertThat(statusDe(id)).isEqualTo("ERRO");
        assertThat(jdbc.sql("SELECT erro FROM documento WHERE id = :id").param("id", id).query(String.class).single())
                .startsWith("Falha inesperada na indexação (");
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ?", id))
                .as("o passo que falhou foi desfeito: nenhum trecho, nem inativo").isZero();
        assertThat(contar("SELECT count(*) FROM documento WHERE id = ? AND modelo_embedding IS NOT NULL", id)).as("nem o resultado da ingestão").isZero();
    }

    @Test
    void aVersaoNovaSoTiraAAntigaDoArQuandoTerminaDeIndexar() {
        SessaoDeApi rbs = entrar(RBS);
        long v1 = enviarEAprovar(rbs, "teste-troca-de-versao");
        acao(rbs, v1, "APROVAR");
        long v2 = enviarEAprovar(rbs, "teste-troca-de-versao");
        embeddings.falhar = true;

        acao(rbs, v2, "APROVAR");

        assertThat(statusDe(v2)).isEqualTo("ERRO");
        assertThat(statusDe(v1)).as("a v2 falhou: a v1 continua no ar").isEqualTo("INDEXADO");
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ? AND ativo", v1)).isGreaterThan(0);

        embeddings.falhar = false;
        acao(rbs, v2, "REINDEXAR");

        assertThat(statusDe(v2)).isEqualTo("INDEXADO");
        assertThat(statusDe(v1)).isEqualTo("SUBSTITUIDO");
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ? AND ativo", v1)).isZero();
    }

    @Test
    void aprovarDuasVezesNaoIndexaDuasVezes() {
        SessaoDeApi rbs = entrar(RBS);
        long id = enviarEAprovar(rbs, "teste-dois-cliques");

        MvcTestResult primeiro = acao(rbs, id, "APROVAR");
        MvcTestResult segundo = acao(rbs, id, "APROVAR");

        assertThat(primeiro).hasStatus(HttpStatus.OK);
        assertThat(segundo).as("já está indexado").hasStatus(HttpStatus.CONFLICT);
        assertThat(embeddings.threads).hasSize(1);
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ? AND ordem = 1", id)).isEqualTo(1);
    }
}
