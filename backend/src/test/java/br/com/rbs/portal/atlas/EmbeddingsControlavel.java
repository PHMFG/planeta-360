package br.com.rbs.portal.atlas;

import br.com.rbs.portal.atlas.embeddings.EmbeddingsDeTeste;
import br.com.rbs.portal.atlas.embeddings.EmbeddingsIndisponivelException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Os embeddings de teste que o teste controla: anotam se havia transação aberta na hora de embutir a consulta, podem demorar (um
 * provedor lento), falhar (o Gemini fora do ar) ou parecer desligados (sem chave). Indexar o kit funciona como sempre, porque só a
 * consulta é controlada. Só existe em {@code src/test}. Não faz parte da API.
 */
public class EmbeddingsControlavel extends EmbeddingsDeTeste {

    private final List<Boolean> transacaoNaConsulta = new CopyOnWriteArrayList<>();
    private final List<String> consultas = new CopyOnWriteArrayList<>();
    private volatile boolean falharNaConsulta;
    private volatile boolean desligado;
    private volatile long demoraEmMs;

    public void limpar() {
        transacaoNaConsulta.clear();
        consultas.clear();
        falharNaConsulta = false;
        desligado = false;
        demoraEmMs = 0;
    }

    public void falharNaConsulta(boolean falhar) {
        this.falharNaConsulta = falhar;
    }

    public void desligado(boolean desligado) {
        this.desligado = desligado;
    }

    public void demora(long milissegundos) {
        this.demoraEmMs = milissegundos;
    }

    /** Em cada consulta embutida, havia transação aberta? (Nunca pode haver.) */
    public List<Boolean> transacaoNaConsulta() {
        return List.copyOf(transacaoNaConsulta);
    }

    public List<String> consultas() {
        return List.copyOf(consultas);
    }

    @Override
    public boolean ligado() {
        return !desligado;
    }

    @Override
    public String motivoDeDesligado() {
        return desligado ? "desligado pelo teste" : "";
    }

    @Override
    public float[] paraConsulta(String consulta) {
        transacaoNaConsulta.add(TransactionSynchronizationManager.isActualTransactionActive());
        consultas.add(consulta);
        if (demoraEmMs > 0) {
            try {
                TimeUnit.MILLISECONDS.sleep(demoraEmMs);
            } catch (InterruptedException interrompido) {
                Thread.currentThread().interrupt();
            }
        }
        if (falharNaConsulta) {
            throw new EmbeddingsIndisponivelException("O Gemini respondeu 503: indisponível.");
        }
        return super.paraConsulta(consulta);
    }
}
