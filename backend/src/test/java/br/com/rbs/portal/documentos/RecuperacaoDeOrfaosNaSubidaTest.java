package br.com.rbs.portal.documentos;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.atlas.embeddings.EmbeddingsDeTeste;
import br.com.rbs.portal.atlas.embeddings.ProvedorDeEmbeddings;
import br.com.rbs.portal.comum.Armazenamento;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [ATLAS] A recuperação dos órfãos acontece <b>antes</b> de a API atender e antes dos {@code ApplicationRunner} (por isso este contexto
 * próprio, com a ingestão assíncrona como em produção). O cenário: um documento PROCESSANDO que sobrou de uma queda existe no banco
 * quando a API sobe (semeado logo depois do Flyway), e um {@code ApplicationRunner} (no papel do {@code DocumentosDaAmostra}) aprova
 * outro documento e deixa a ingestão dele em andamento, parada no provedor de embeddings, até o teste liberar. Antes da correção, a
 * recuperação rodava no {@code ApplicationReadyEvent}, depois dos runners, e marcava os dois como órfãos: o que estava em andamento
 * ia para ERRO "a API foi reiniciada" sem reinício nenhum. Bloqueante.
 */
@TesteDeIntegracao
@TestPropertySource(properties = "atlas.ingestao.sincrona=false")
@Import(RecuperacaoDeOrfaosNaSubidaTest.Cenario.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecuperacaoDeOrfaosNaSubidaTest extends ApoioDeDocumentos {

    static final String TITULO_EM_ANDAMENTO = "Em andamento durante a subida";
    static final String CHAVE_DO_ORFAO = "teste-orfao-de-verdade";
    static final String CHAVE_EM_ANDAMENTO = "teste-em-andamento-na-subida";
    static final CountDownLatch LIBERAR_A_INGESTAO = new CountDownLatch(1);
    static final AtomicLong ORFAO = new AtomicLong();
    static final AtomicLong EM_ANDAMENTO = new AtomicLong();

    /** Os embeddings de teste, parados no documento "em andamento" até o teste soltar; os outros documentos (o kit) passam direto. */
    static final class ProvedorQueEspera implements ProvedorDeEmbeddings {

        private final EmbeddingsDeTeste verdadeiro = new EmbeddingsDeTeste();

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
            if (TITULO_EM_ANDAMENTO.equals(tituloDoDocumento)) {
                try {
                    LIBERAR_A_INGESTAO.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException interrompida) {
                    Thread.currentThread().interrupt();
                }
            }
            return verdadeiro.paraTrechos(tituloDoDocumento, trechos);
        }

        @Override
        public float[] paraConsulta(String consulta) {
            return verdadeiro.paraConsulta(consulta);
        }
    }

    @TestConfiguration
    static class Cenario {

        /** Limpa e migra como a suíte e, logo depois do Flyway, deixa um documento PROCESSANDO no banco: o órfão de uma queda. */
        @Bean
        @Primary
        FlywayMigrationStrategy limparMigrarEDeixarUmOrfao() {
            return flyway -> {
                flyway.clean();
                flyway.migrate();
                JdbcClient jdbc = JdbcClient.create(flyway.getConfiguration().getDataSource());
                long rbs = jdbc.sql("SELECT id FROM usuario WHERE email = 'b2b@rbs.example'").query(Long.class).single();
                long arquivo = jdbc.sql("""
                                INSERT INTO arquivo (parceria_id, categoria, nome_original, mime_type, tamanho_bytes, caminho, enviado_por)
                                VALUES (NULL, 'DOCUMENTO', 'orfao.pdf', 'application/pdf', 10, 'geral/orfao-de-teste-da-subida.pdf', :rbs)
                                RETURNING id
                                """).param("rbs", rbs).query(Long.class).single();
                long documento = jdbc.sql("""
                                INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, arquivo_id, enviado_por)
                                VALUES ('Órfão de uma queda', :chave, 1, 'OUTRO', 'GLOBAL',
                                        (SELECT id FROM evento WHERE nome = 'Planeta Atlântida' AND edicao = 2027), :arquivo, :rbs)
                                RETURNING id
                                """).param("chave", CHAVE_DO_ORFAO).param("arquivo", arquivo).param("rbs", rbs).query(Long.class).single();
                jdbc.sql("""
                                UPDATE documento SET status = 'PROCESSANDO', aprovado_por = :rbs, aprovado_por_perfil = 'ADMIN_RBS', aprovado_em = now()
                                WHERE id = :id
                                """).param("rbs", rbs).param("id", documento).update();
                ORFAO.set(documento);
            };
        }

        @Bean
        @Primary
        ProvedorDeEmbeddings provedorQueEspera() {
            return new ProvedorQueEspera();
        }

        /** No papel do DocumentosDaAmostra: durante a subida, a RBS aprova um documento e a ingestão dele fica em andamento. */
        @Bean
        ApplicationRunner comecarUmaIngestaoDuranteASubida(DocumentoRepositorio repositorio, Armazenamento armazenamento, IngestaoService ingestao,
                                                           PlatformTransactionManager gerenteDeTransacao, JdbcClient jdbc) {
            return argumentos -> {
                long rbs = jdbc.sql("SELECT id FROM usuario WHERE email = 'b2b@rbs.example'").query(Long.class).single();
                long evento = repositorio.eventoDaEdicao(2027).orElseThrow();
                long id = new TransactionTemplate(gerenteDeTransacao).execute(estado -> {
                    var arquivo = armazenamento.gravarDocumento(KitDeDocumentos.por("D09").conteudo(), "andamento.pdf", rbs, null);
                    long documento = repositorio.inserir(TITULO_EM_ANDAMENTO, CHAVE_EM_ANDAMENTO, 1, "OUTRO", "GLOBAL", evento, null, null,
                            arquivo.id(), false, 2, 5, null, rbs);
                    repositorio.aprovar(documento, rbs);
                    return documento;
                });
                EM_ANDAMENTO.set(id);
                ingestao.depoisDoCommit(id);   // sem transação em curso: vai já para a fila, e a thread dela para no provedor
            };
        }
    }

    @Autowired
    IngestaoService ingestao;

    @Test
    void oDocumentoQueComecouAProcessarDuranteASubidaNaoViraOrfao() {
        long id = EM_ANDAMENTO.get();

        assertThat(statusDe(id)).as("a recuperação rodou antes do runner: este documento ainda não estava em PROCESSANDO").isEqualTo("PROCESSANDO");
        assertThat(jdbc.sql("SELECT erro FROM documento WHERE id = :id").param("id", id).query(String.class).optional()).isEmpty();

        LIBERAR_A_INGESTAO.countDown();
        assertThat(ingestao.aguardar(id, Duration.ofSeconds(60))).as("a ingestão que estava em andamento termina normalmente").isEqualTo("INDEXADO");
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ? AND ativo", id)).isGreaterThan(0);
        assertThat(contar("SELECT count(*) FROM historico WHERE entidade = 'documento' AND entidade_id = ? AND valor_novo = 'ERRO'", id)).isZero();
    }

    @Test
    void oOrfaoDeVerdadeQueJaEstavaNoBancoQuandoAApiSubiuViraErroInterrompidoComHistorico() {
        long id = ORFAO.get();

        assertThat(statusDe(id)).isEqualTo("ERRO");
        assertThat(jdbc.sql("SELECT erro FROM documento WHERE id = :id").param("id", id).query(String.class).single())
                .isEqualTo(IngestaoService.PROCESSAMENTO_INTERROMPIDO);
        assertThat(contar("SELECT count(*) FROM historico WHERE entidade = 'documento' AND entidade_id = ? AND valor_anterior = 'PROCESSANDO' AND valor_novo = 'ERRO'", id))
                .isEqualTo(1);
    }

    @AfterAll
    void apagarOQueEsteContextoCriou() {
        LIBERAR_A_INGESTAO.countDown();
        List<Long> criados = List.of(ORFAO.get(), EM_ANDAMENTO.get());
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
    }
}
