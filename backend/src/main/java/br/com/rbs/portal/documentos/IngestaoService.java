package br.com.rbs.portal.documentos;

import br.com.rbs.portal.atlas.embeddings.EmbeddingsIndisponivelException;
import br.com.rbs.portal.atlas.embeddings.ProvedorDeEmbeddings;
import br.com.rbs.portal.atlas.embeddings.ProvedorDeEmbeddings.TextoDoTrecho;
import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.Armazenamento;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.Perfil;
import br.com.rbs.portal.comum.TipoDeArquivo;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.documentos.DivisorDeTrechos.Trecho;
import br.com.rbs.portal.documentos.DocumentoRepositorio.Orfao;
import br.com.rbs.portal.documentos.DocumentoRepositorio.ParaIngestao;
import br.com.rbs.portal.documentos.DocumentoRepositorio.TrechoParaGravar;
import br.com.rbs.portal.documentos.ExtratorDeTexto.ExtracaoFalhouException;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Extracao;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A ingestão dos documentos aprovados (I1 a I6 do quadro, M3 do plano do Atlas completo). [ATLAS] Aprovar um documento (rota 57)
 * o põe em PROCESSANDO e chama {@link #depoisDoCommit(long)}, que enfileira a tarefa <b>depois do commit</b> num executor próprio
 * ({@code atlas-ingestao-}, 2 threads). A fila fica na memória: na subida da API, <b>antes de ela atender e antes dos
 * {@code ApplicationRunner}</b> (como {@link SmartInitializingSingleton}), o PROCESSANDO que sobrou de uma queda vira ERRO
 * ("processamento interrompido") e o "Reindexar" recomeça. Os passos, cada um numa transação curta:
 * <ol>
 *   <li>extrair o texto do arquivo (sem transação);</li>
 *   <li>dividir em trechos (sem transação);</li>
 *   <li>embeddings (<b>sem transação aberta</b>: a rede do provedor é lenta e o pool tem 10 conexões);</li>
 *   <li>inserir os trechos, inativos, e o resultado da ingestão (modelo, dimensões, tempos);</li>
 *   <li>{@code fn_publicar_documento}: a versão anterior vira SUBSTITUIDO e a nova INDEXADO, na mesma transação.</li>
 * </ol>
 * Erro em qualquer passo deixa o documento em ERRO com o motivo, sem trecho nenhum gravado (os inativos saem junto). Isso vale para
 * qualquer {@link Throwable}, também um {@code StackOverflowError} ou um {@code OutOfMemoryError} do provedor: sem isso o documento
 * ficava preso em PROCESSANDO até a API reiniciar, e recusar, reindexar e revogar davam 409.
 *
 * <p>Com {@code atlas.ingestao.sincrona=true} (os testes e o kit), o callback do commit manda a tarefa ao <b>mesmo executor</b> e espera o
 * resultado. A ingestão nunca roda na thread do callback: ali a transação ainda consta como ativa, cada passo {@code REQUIRED} entraria
 * nela, e o passo que falha ficaria gravado (conferido em 30/10/2026, Spring 7.0.10); numa thread do executor a transação é nova e
 * o passo que falha é desfeito.
 */
@Service
public class IngestaoService implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(IngestaoService.class);
    static final String PROCESSAMENTO_INTERROMPIDO = "Processamento interrompido: a API foi reiniciada antes de terminar. Reindexe o documento.";
    private static final int LIMITE_DO_MOTIVO = 500;

    /** Uma falha que a ingestão já sabe explicar, com a frase pronta para a tela. */
    private static final class FalhaDaIngestao extends RuntimeException {
        FalhaDaIngestao(String motivo) {
            super(motivo);
        }
    }

    private final DocumentoRepositorio repositorio;
    private final Armazenamento armazenamento;
    private final ExtratorDeTexto extrator;
    private final DivisorDeTrechos divisor;
    private final ProvedorDeEmbeddings embeddings;
    private final HistoricoService historico;
    private final TransactionTemplate transacao;
    private final boolean sincrona;
    private final AtomicInteger numeroDaThread = new AtomicInteger();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, tarefa -> {
        Thread thread = new Thread(tarefa, "atlas-ingestao-" + numeroDaThread.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Long, Future<?>> emAndamento = new ConcurrentHashMap<>();

    public IngestaoService(DocumentoRepositorio repositorio, Armazenamento armazenamento, ExtratorDeTexto extrator,
                           DivisorDeTrechos divisor, ProvedorDeEmbeddings embeddings, HistoricoService historico,
                           PlatformTransactionManager gerenteDeTransacao, @Value("${atlas.ingestao.sincrona:false}") boolean sincrona) {
        this.repositorio = repositorio;
        this.armazenamento = armazenamento;
        this.extrator = extrator;
        this.divisor = divisor;
        this.embeddings = embeddings;
        this.historico = historico;
        this.transacao = new TransactionTemplate(gerenteDeTransacao);
        this.sincrona = sincrona;
    }

    @PreDestroy
    void encerrar() {
        executor.shutdownNow();
    }

    // ---------------------------------------------------------------------
    // Enfileirar
    // ---------------------------------------------------------------------

    /**
     * Manda o documento (já em PROCESSANDO) para a fila <b>depois do commit</b> da transação em curso; sem transação, manda já. Com
     * a transação desfeita, nada acontece. No modo síncrono, o callback espera a tarefa terminar.
     */
    public void depoisDoCommit(long documentoId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    despachar(documentoId);
                }
            });
        } else {
            despachar(documentoId);
        }
    }

    private void despachar(long documentoId) {
        // A tarefa entra no mapa ANTES de começar e sai dele quando termina: quem espera (aguardar) a encontra enquanto roda.
        FutureTask<Void> tarefa = new FutureTask<>(() -> {
            try {
                processar(documentoId);
            } finally {
                emAndamento.remove(documentoId);
            }
        }, null);
        emAndamento.put(documentoId, tarefa);
        executor.execute(tarefa);
        if (sincrona) {
            esperar(tarefa);
        }
    }

    private static void esperar(Future<?> tarefa) {
        try {
            tarefa.get();
        } catch (InterruptedException interrompida) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException falha) {
            log.error("Atlas: a tarefa de ingestão terminou com erro", falha.getCause());
        }
    }

    /**
     * Espera o documento sair de PROCESSANDO (a amostra e o kit sobem um documento por vez, e o D02 só pode ser indexado depois do D01).
     * Devolve o status em que ficou, ou PROCESSANDO se o prazo acabou.
     */
    public String aguardar(long documentoId, Duration prazo) {
        Future<?> tarefa = emAndamento.get(documentoId);
        if (tarefa != null) {
            try {
                tarefa.get(prazo.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrompida) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | java.util.concurrent.TimeoutException falha) {
                log.warn("Atlas: a espera pela ingestão do documento {} terminou sem resultado ({})", documentoId, falha.getClass().getSimpleName());
            }
        }
        return repositorio.statusDe(documentoId).orElse("INEXISTENTE");
    }

    // ---------------------------------------------------------------------
    // Os passos
    // ---------------------------------------------------------------------

    /**
     * Indexa o documento em PROCESSANDO. Nunca lança: qualquer falha vira ERRO com o motivo, também a da leitura do documento (se ela
     * ficasse de fora, o documento ficava preso em PROCESSANDO e REINDEXAR, RECUSAR e REVOGAR davam 409). Documento que não está em
     * PROCESSANDO (já indexado, revogado, ou outra tarefa chegou antes) é deixado como está. Pode ser chamado direto (os testes que
     * rodam dentro de uma transação de teste fazem isso: os passos entram nela).
     */
    public void processar(long documentoId) {
        try {
            ParaIngestao documento = repositorio.paraIngestao(documentoId).orElse(null);
            if (documento == null || !"PROCESSANDO".equals(documento.status())) {
                return;
            }
            // 1. Extrair (sem transação).
            Path caminho = armazenamento.localizar(documento.arquivoCaminho())
                    .orElseThrow(() -> new FalhaDaIngestao("O arquivo do documento não está mais no armazenamento. Envie o documento de novo."));
            byte[] conteudo = lerArquivo(caminho);
            TipoDeArquivo tipo = TipoDeArquivo.daExtensao(documento.arquivoNome())
                    .orElseThrow(() -> new FalhaDaIngestao("Este tipo de arquivo não é indexado."));
            // [ATLAS] As páginas fora do Atlas (R2.1) viram páginas sem texto ANTES do divisor: nenhum trecho nasce delas, e o texto delas
            // nunca chega aos embeddings nem ao modelo. A numeração e a contagem de páginas seguem as do PDF.
            Extracao extracao = extrator.extrair(tipo, conteudo).semPaginas(PaginasForaDoAtlas.daColuna(documento.paginasIgnoradas()));

            // 2. Dividir (sem transação).
            List<Trecho> trechos = divisor.dividir(extracao.paginas());
            if (trechos.isEmpty()) {
                throw new FalhaDaIngestao("O documento não tem texto que possa ser indexado.");
            }

            // 3. Embeddings, SEM transação aberta.
            if (!embeddings.ligado()) {
                throw new FalhaDaIngestao(embeddings.motivoDeDesligado());
            }
            long inicioDosEmbeddings = System.nanoTime();
            List<float[]> vetores = embeddings.paraTrechos(documento.titulo(),
                    trechos.stream().map(t -> new TextoDoTrecho(t.secaoCaminho(), t.texto())).toList());
            int embeddingsMs = (int) ((System.nanoTime() - inicioDosEmbeddings) / 1_000_000);
            if (vetores.size() != trechos.size()) {
                throw new FalhaDaIngestao("Os embeddings voltaram em número diferente dos trechos (" + vetores.size() + " para " + trechos.size() + ").");
            }
            List<TrechoParaGravar> paraGravar = new ArrayList<>(trechos.size());
            for (int i = 0; i < trechos.size(); i++) {
                Trecho t = trechos.get(i);
                paraGravar.add(new TrechoParaGravar(t.ordem(), t.paginaInicio(), t.paginaFim(), t.secaoCaminho(), t.texto(), t.tokens(),
                        ProvedorDeEmbeddings.comoTextoDoBanco(vetores.get(i))));
            }

            // 4. Trechos (inativos) e o resultado da ingestão, numa transação curta.
            transacao.executeWithoutResult(estado -> {
                repositorio.removerTrechosInativos(documentoId);
                repositorio.inserirTrechos(documentoId, paraGravar);
                repositorio.gravarResultadoDaIngestao(documentoId, extracao.totalDePaginas(), (int) extracao.milissegundos(),
                        embeddings.modelo(), ProvedorDeEmbeddings.DIMENSOES, embeddingsMs);
            });

            // 5. Publicar: a versão anterior sai e esta entra, na mesma transação.
            transacao.executeWithoutResult(estado -> publicar(documento, trechos.size(), embeddingsMs));
        } catch (Throwable erro) {
            marcarErro(documentoId, motivoDe(erro), erro);
        }
    }

    private void publicar(ParaIngestao documento, int trechos, int embeddingsMs) {
        // A trava do grupo (a mesma de fn_publicar_documento, que pega de novo sem problema na mesma transação) vem ANTES de ler quais
        // versões esta vai substituir: se outra versão do grupo está publicando sem ter confirmado, esta espera aqui e só então lê a
        // lista. Lida antes da trava, a lista vinha vazia, a versão antiga virava SUBSTITUIDO e o histórico não dizia.
        repositorio.travarGrupoDoDocumento(documento.id());
        List<Long> substituidos = repositorio.indexadosMaisAntigosDoGrupo(documento.id());
        repositorio.publicar(documento.id());
        UsuarioAutenticado quem = quem(documento.aprovadoPor());
        String situacao = repositorio.statusDe(documento.id()).orElse("INDEXADO");
        historico.registrar(AcaoHistorico.ALTERACAO_STATUS, quem, documento.parceriaId(), "documento", documento.id(), "PROCESSANDO", situacao,
                trechos + " trechos, embeddings " + embeddings.modelo() + " em " + embeddingsMs + " ms", null);
        for (Long antigo : substituidos) {
            // Só quem ficou SUBSTITUIDO de fato: uma versão revogada no meio da publicação continua REVOGADO, e o histórico não pode dizer
            // que foi substituída. O status é lido depois da função, na mesma transação.
            if (!"SUBSTITUIDO".equals(repositorio.statusDe(antigo).orElse(""))) {
                continue;
            }
            historico.registrar(AcaoHistorico.ALTERACAO_STATUS, quem, documento.parceriaId(), "documento", antigo, "INDEXADO", "SUBSTITUIDO",
                    "substituído pela versão " + documento.versao(), null);
        }
    }

    private void marcarErro(long documentoId, String motivo, Throwable erro) {
        log.warn("Atlas: a indexação do documento {} falhou: {}", documentoId, motivo);
        if (!(erro instanceof FalhaDaIngestao) && !(erro instanceof ExtracaoFalhouException) && !(erro instanceof EmbeddingsIndisponivelException)) {
            log.error("Atlas: falha inesperada na indexação do documento {}", documentoId, erro);
        }
        try {
            transacao.executeWithoutResult(estado -> {
                Optional<Orfao> mudou = repositorio.marcarErro(documentoId, motivo);
                repositorio.removerTrechosInativos(documentoId);
                mudou.ifPresent(documento -> historico.registrar(AcaoHistorico.ALTERACAO_STATUS, quem(documento.aprovadoPor()),
                        documento.parceriaId(), "documento", documentoId, "PROCESSANDO", "ERRO", motivo, null));
            });
        } catch (Throwable naoGravou) {
            log.error("Atlas: não consegui gravar o erro do documento {}", documentoId, naoGravou);
        }
    }

    /**
     * A frase do motivo: as que o Atlas já escreve valem como estão; o resto vira uma frase genérica (o detalhe fica no log). Sem o
     * caractere nulo (o PostgreSQL não guarda U+0000, e a frase do provedor pode trazê-lo: o ERRO não seria gravado e o documento ficava
     * preso em PROCESSANDO) e com no máximo {@value #LIMITE_DO_MOTIVO} caracteres.
     */
    private static String motivoDe(Throwable erro) {
        String motivo = null;
        if (erro instanceof FalhaDaIngestao || erro instanceof ExtracaoFalhouException || erro instanceof EmbeddingsIndisponivelException) {
            motivo = erro.getMessage() == null ? null : erro.getMessage().replace("\u0000", "");
        }
        if (motivo == null || motivo.isBlank()) {
            motivo = "Falha inesperada na indexação (" + erro.getClass().getSimpleName() + "). Veja o log da API.";
        }
        return motivo.length() > LIMITE_DO_MOTIVO ? motivo.substring(0, LIMITE_DO_MOTIVO) : motivo;
    }

    private static byte[] lerArquivo(Path caminho) {
        try {
            return Files.readAllBytes(caminho);
        } catch (IOException erro) {
            throw new FalhaDaIngestao("Não consegui ler o arquivo do documento no armazenamento.");
        }
    }

    /** Quem aparece no histórico das mudanças automáticas: a pessoa da RBS que aprovou o documento. */
    private static UsuarioAutenticado quem(Long aprovadoPor) {
        return new UsuarioAutenticado(aprovadoPor, null, null, Perfil.ADMIN_RBS);
    }

    // ---------------------------------------------------------------------
    // Na subida da API
    // ---------------------------------------------------------------------

    /**
     * Roda quando todos os beans já existem e <b>antes</b> de a API atender e de qualquer {@code ApplicationRunner} (o
     * {@code DocumentosDaAmostra} sobe o kit, e um documento aprovado nesse meio tempo está de verdade em processamento). O Flyway já
     * rodou: o {@code JdbcClient} do repositório depende da migração (DatabaseInitializationDependencyConfigurer do Spring Boot).
     * No {@code ApplicationReadyEvent}, que vem depois dos runners, uma tarefa em andamento era marcada como órfã.
     */
    @Override
    public void afterSingletonsInstantiated() {
        recuperarOrfaos();
        avisarModeloDiferente();
    }

    /** PROCESSANDO que sobrou de uma queda vira ERRO "processamento interrompido": a fila ficava na memória. Devolve quantos eram. */
    public int recuperarOrfaos() {
        List<Orfao> orfaos = transacao.execute(estado -> {
            List<Orfao> lista = repositorio.orfaosViramErro(PROCESSAMENTO_INTERROMPIDO);
            for (Orfao orfao : lista) {
                historico.registrar(AcaoHistorico.ALTERACAO_STATUS, quem(orfao.aprovadoPor()), orfao.parceriaId(), "documento", orfao.id(),
                        "PROCESSANDO", "ERRO", PROCESSAMENTO_INTERROMPIDO, null);
            }
            return lista;
        });
        int quantos = orfaos == null ? 0 : orfaos.size();
        if (quantos > 0) {
            log.warn("Atlas: {} documento(s) estavam em processamento quando a API caiu e foram para ERRO. Reindexe pela aba Documentos.", quantos);
        }
        return quantos;
    }

    /**
     * Esta entrega não reindexa documento INDEXADO sozinha. Se houver algum indexado com outro modelo de embeddings (a variável
     * ATLAS_EMBEDDINGS mudou), a busca o ignora: a API registra um erro no log, com a contagem e o comando para recriar o banco, e a aba
     * Documentos mostra o aviso. Devolve a contagem.
     */
    public long avisarModeloDiferente() {
        if (!embeddings.ligado()) {
            return 0;
        }
        long diferentes = repositorio.contarIndexadosComOutroModelo(embeddings.modelo());
        if (diferentes > 0) {
            log.error("Atlas: {} documento(s) indexado(s) com um modelo de embeddings diferente do atual ({}). A busca os ignora. "
                            + "Recrie o banco (DROP DATABASE e CREATE DATABASE, depois scripts/preparar-banco-atlas.sh) ou suba os documentos de novo.",
                    diferentes, embeddings.modelo());
        }
        return diferentes;
    }
}
