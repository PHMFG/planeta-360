package br.com.rbs.portal.importacao;

import jakarta.annotation.PreDestroy;
import java.util.Map;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A fila da leitura da importação (E3). [IMPORTACAO] O POST da rota 109 grava a importação em LENDO e chama {@link #depoisDoCommit}:
 * a tarefa só começa depois do commit, num executor próprio ({@code atlas-importacao-}, 2 threads), como a
 * {@link br.com.rbs.portal.documentos.IngestaoService}. A fila fica na memória; na subida da API, antes de ela atender e antes dos
 * {@code ApplicationRunner}, o LENDO que sobrou de uma queda vira ERRO (o {@link ImportacaoService} faz isso). Com
 * {@code portal.importacao.sincrona=true} (os testes), o callback do commit manda a tarefa ao mesmo executor e espera o resultado,
 * sempre em outra thread: nunca na thread do callback, onde a transação ainda consta como ativa.
 */
@Service
public class FilaDeImportacao implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(FilaDeImportacao.class);

    private final ObjectProvider<ImportacaoService> servico;
    private final boolean sincrona;
    private final AtomicInteger numeroDaThread = new AtomicInteger();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, tarefa -> {
        Thread thread = new Thread(tarefa, "atlas-importacao-" + numeroDaThread.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Long, Future<?>> emAndamento = new ConcurrentHashMap<>();

    public FilaDeImportacao(ObjectProvider<ImportacaoService> servico, @Value("${portal.importacao.sincrona:false}") boolean sincrona) {
        this.servico = servico;
        this.sincrona = sincrona;
    }

    @PreDestroy
    void encerrar() {
        executor.shutdownNow();
    }

    /** Manda a importação para a fila depois do commit da transação em curso; sem transação, manda já. */
    public void depoisDoCommit(long importacaoId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    despachar(importacaoId);
                }
            });
        } else {
            despachar(importacaoId);
        }
    }

    private void despachar(long importacaoId) {
        FutureTask<Void> tarefa = new FutureTask<>(() -> {
            try {
                servico.getObject().processar(importacaoId);
            } finally {
                emAndamento.remove(importacaoId);
            }
        }, null);
        emAndamento.put(importacaoId, tarefa);
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
            log.error("Importação: a tarefa de leitura terminou com erro", falha.getCause());
        }
    }

    @Override
    public void afterSingletonsInstantiated() {
        servico.getObject().recuperarOrfaos();
    }
}
