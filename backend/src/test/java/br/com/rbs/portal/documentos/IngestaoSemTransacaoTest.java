package br.com.rbs.portal.documentos;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.embeddings.EmbeddingsDeTeste;
import br.com.rbs.portal.atlas.embeddings.EmbeddingsIndisponivelException;
import br.com.rbs.portal.atlas.embeddings.ProvedorDeEmbeddings;
import br.com.rbs.portal.documentos.DocumentoRepositorio.TrechoParaGravar;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
        volatile boolean estourarAPilha;
        volatile String fraseDoProvedor;

        void limpar() {
            transacaoAtiva.clear();
            threads.clear();
            falhar = false;
            vetorCurto = false;
            estourarAPilha = false;
            fraseDoProvedor = null;
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
            if (estourarAPilha) {
                throw new StackOverflowError();   // um Error, e não uma Exception: o catch (Exception) da ingestão não o pega
            }
            if (fraseDoProvedor != null) {
                throw new EmbeddingsIndisponivelException(fraseDoProvedor);
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

    /** O repositório de verdade, com um gancho: falhar ao ler o documento da ingestão. */
    static class RepositorioComGanchos extends DocumentoRepositorio {

        volatile boolean falharAoLer;

        RepositorioComGanchos(JdbcClient jdbc) {
            super(jdbc);
        }

        // O repositório é um @Repository: o Spring o entrega por um proxy, então o teste mexe nele por métodos, e não pelos campos.
        public void limpar() {
            falharAoLer = false;
        }

        public void falharAoLer(boolean falhar) {
            falharAoLer = falhar;
        }

        @Override
        public Optional<ParaIngestao> paraIngestao(long id) {
            if (falharAoLer) {
                throw new IllegalStateException("a conexão caiu na leitura do documento");
            }
            return super.paraIngestao(id);
        }
    }

    @TestConfiguration
    static class ProvedorQueGrava {

        @Bean
        @Primary
        GravadorDeEmbeddings gravadorDeEmbeddings() {
            return new GravadorDeEmbeddings();
        }

        @Bean
        @Primary
        RepositorioComGanchos repositorioComGanchos(JdbcClient jdbc) {
            return new RepositorioComGanchos(jdbc);
        }
    }

    @Autowired
    GravadorDeEmbeddings embeddings;

    @Autowired
    RepositorioComGanchos repositorio;

    @Autowired
    IngestaoService ingestao;

    @Autowired
    PlatformTransactionManager gerenteDeTransacao;

    private final List<Long> criados = new ArrayList<>();

    @BeforeEach
    void limparOGravador() {
        embeddings.limpar();
        repositorio.limpar();
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
    void erroDoProvedorQueNaoEExcecaoTambemDeixaODocumentoEmErroEPodeSerReindexado() {
        SessaoDeApi rbs = entrar(RBS);
        long id = enviarEAprovar(rbs, "teste-erro-fatal");
        embeddings.estourarAPilha = true;

        acao(rbs, id, "APROVAR");

        assertThat(statusDe(id)).as("um StackOverflowError não pode deixar o documento preso em PROCESSANDO").isEqualTo("ERRO");
        assertThat(jdbc.sql("SELECT erro FROM documento WHERE id = :id").param("id", id).query(String.class).single())
                .isEqualTo("Falha inesperada na indexação (StackOverflowError). Veja o log da API.");
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ?", id)).isZero();
        assertThat(contar("SELECT count(*) FROM historico WHERE entidade = 'documento' AND entidade_id = ? AND valor_novo = 'ERRO'", id)).isEqualTo(1);

        // e a RBS reindexa, em vez de receber 409 até a API reiniciar
        embeddings.estourarAPilha = false;
        assertThat(acao(rbs, id, "REINDEXAR")).hasStatus(HttpStatus.OK);
        assertThat(statusDe(id)).isEqualTo("INDEXADO");
        assertThat(acao(rbs, id, "REVOGAR")).hasStatus(HttpStatus.OK);
    }

    @Test
    void erroDoProvedorQueNaoEExcecaoNaoImpedeRecusarODocumento() {
        SessaoDeApi rbs = entrar(RBS);
        long id = enviarEAprovar(rbs, "teste-erro-fatal-recusa");
        embeddings.estourarAPilha = true;
        acao(rbs, id, "APROVAR");

        assertThat(acao(rbs, id, "RECUSAR")).hasStatus(HttpStatus.OK);
        assertThat(statusDe(id)).isEqualTo("RECUSADO");
    }

    /** Espera até a conexão de outra sessão estar parada numa trava de aviso (advisory lock) do PostgreSQL. */
    private void esperarAlguemNaTravaDoGrupo() throws InterruptedException {
        for (int tentativa = 0; tentativa < 200; tentativa++) {
            long esperando = contar("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock' AND wait_event = 'advisory'");
            if (esperando > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("ninguém ficou esperando a trava do grupo em 10 segundos");
    }

    @Test
    void aVersaoNovaQueEsperaAVelhaTerminarDeSePublicarAindaRegistraNoHistoricoQueAVelhaFoiSubstituida() throws Exception {
        SessaoDeApi rbs = entrar(RBS);
        long v1 = enviarEAprovar(rbs, "teste-historico-da-troca");
        long v2 = enviarEAprovar(rbs, "teste-historico-da-troca");
        long rbsId = contar("SELECT id FROM usuario WHERE email = ?", RBS);
        TransactionTemplate transacao = new TransactionTemplate(gerenteDeTransacao);
        // as duas aprovadas e PROCESSANDO, sem disparar a ingestão: quem publica é este teste (v1) e a ingestão de verdade (v2)
        transacao.executeWithoutResult(estado -> {
            repositorio.aprovar(v1, rbsId);
            repositorio.aprovar(v2, rbsId);
        });
        String vetor = ProvedorDeEmbeddings.comoTextoDoBanco(new EmbeddingsDeTeste().paraConsulta("texto do trecho"));
        CountDownLatch v1PublicouSemConfirmar = new CountDownLatch(1);
        CountDownLatch confirmarV1 = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            // v1 está no meio da própria publicação: tem a trava do grupo e o INDEXADO ainda não foi confirmado
            Future<?> publicandoV1 = threads.submit(() -> transacao.executeWithoutResult(estado -> {
                repositorio.inserirTrechos(v1, List.of(new TrechoParaGravar(1, 1, 1, null, "texto do trecho", 4, vetor)));
                repositorio.gravarResultadoDaIngestao(v1, 1, 1, EmbeddingsDeTeste.MODELO, ProvedorDeEmbeddings.DIMENSOES, 1);
                repositorio.publicar(v1);
                v1PublicouSemConfirmar.countDown();
                try {
                    confirmarV1.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrompida) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(v1PublicouSemConfirmar.await(30, TimeUnit.SECONDS)).isTrue();

            // v2 termina de indexar e tenta publicar: tem de esperar a trava de v1
            Future<?> indexandoV2 = threads.submit(() -> ingestao.processar(v2));
            esperarAlguemNaTravaDoGrupo();
            assertThat(indexandoV2.isDone()).as("v2 espera a trava de v1").isFalse();
            confirmarV1.countDown();
            publicandoV1.get(30, TimeUnit.SECONDS);
            indexandoV2.get(30, TimeUnit.SECONDS);
        } finally {
            confirmarV1.countDown();
            threads.shutdownNow();
        }

        assertThat(statusDe(v2)).isEqualTo("INDEXADO");
        assertThat(statusDe(v1)).isEqualTo("SUBSTITUIDO");
        assertThat(contar("SELECT count(*) FROM historico WHERE entidade = 'documento' AND entidade_id = ? AND valor_anterior = 'INDEXADO' AND valor_novo = 'SUBSTITUIDO'", v1))
                .as("a v1 ficou SUBSTITUIDO e o histórico tem de dizer isso, uma vez").isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ? AND ativo", v1)).isZero();
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ? AND ativo", v2)).isGreaterThan(0);
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

    // ---------------------------------------------------------------------
    // M3, segunda rodada de correção: documento preso e histórico falso (J e K)
    // ---------------------------------------------------------------------

    @Test
    void seALeituraDoDocumentoFalhaNaIngestaoOdocumentoVaiParaErroEPodeSerReindexadoOuRecusado() {
        SessaoDeApi rbs = entrar(RBS);
        long id = enviarEAprovar(rbs, "teste-leitura-falha");
        repositorio.falharAoLer(true);

        acao(rbs, id, "APROVAR");

        assertThat(statusDe(id)).as("a falha ao ler o documento não pode deixá-lo preso em PROCESSANDO").isEqualTo("ERRO");
        assertThat(jdbc.sql("SELECT erro FROM documento WHERE id = :id").param("id", id).query(String.class).single())
                .isEqualTo("Falha inesperada na indexação (IllegalStateException). Veja o log da API.");
        assertThat(contar("SELECT count(*) FROM historico WHERE entidade = 'documento' AND entidade_id = ? AND valor_novo = 'ERRO'", id)).isEqualTo(1);

        repositorio.falharAoLer(false);
        assertThat(acao(rbs, id, "REINDEXAR")).hasStatus(HttpStatus.OK);
        assertThat(statusDe(id)).isEqualTo("INDEXADO");
    }

    @Test
    void seALeituraDoDocumentoFalhaNaIngestaoAindaDaParaRecusar() {
        SessaoDeApi rbs = entrar(RBS);
        long id = enviarEAprovar(rbs, "teste-leitura-falha-recusa");
        repositorio.falharAoLer(true);
        acao(rbs, id, "APROVAR");

        assertThat(acao(rbs, id, "RECUSAR")).hasStatus(HttpStatus.OK);
        assertThat(statusDe(id)).isEqualTo("RECUSADO");
    }

    @Test
    void fraseDoProvedorComCaractereNuloEMuitoLongaVaiParaErroSemNulEComNoMaximo500Caracteres() {
        SessaoDeApi rbs = entrar(RBS);
        long id = enviarEAprovar(rbs, "teste-frase-com-nul");
        embeddings.fraseDoProvedor = "O Gemini respondeu 400: chave inválida\u0000 " + "e a frase continua ".repeat(60);

        acao(rbs, id, "APROVAR");

        assertThat(statusDe(id)).as("o NUL não pode impedir o ERRO: o PostgreSQL não guarda U+0000").isEqualTo("ERRO");
        String erro = jdbc.sql("SELECT erro FROM documento WHERE id = :id").param("id", id).query(String.class).single();
        assertThat(erro).startsWith("O Gemini respondeu 400: chave inválida e a frase continua").hasSize(500);
        assertThat(contar("SELECT count(*) FROM historico WHERE entidade = 'documento' AND entidade_id = ? AND valor_novo = 'ERRO'", id)).isEqualTo(1);
        assertThat(acao(rbs, id, "RECUSAR")).as("e a RBS ainda recusa").hasStatus(HttpStatus.OK);
    }
}
