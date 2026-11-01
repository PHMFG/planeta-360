package br.com.rbs.portal.atlas;

import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.ferramentas.EstadoDaConversa;
import br.com.rbs.portal.atlas.ferramentas.Execucao;
import br.com.rbs.portal.atlas.ferramentas.Ferramenta;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.DefinicaoDeFerramenta;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Uma ferramenta falsa para provar as duas fases (M4): a fase 1 ({@code preparar}) anota se havia transação aberta, a fase 2
 * ({@code executar}) também, e o corpo pode falhar em qualquer uma ou esperar um tempo para os testes de carga. Só existe em
 * {@code src/test}; o modelo roteirizado a chama pelo nome {@value #NOME}. Não faz parte da API.
 */
public class FerramentaDeTeste implements Ferramenta {

    public static final String NOME = "ferramenta_de_teste";

    /** O que acontece na fase 1: nada, falhar, ou esperar (como um provedor lento). */
    public enum Comportamento {
        NORMAL, FALHAR_NO_PREPARO, FALHAR_NA_EXECUCAO, DEMORAR
    }

    public record Registro(String fase, boolean transacaoAberta, String thread) {
    }

    private final List<Registro> registros = new CopyOnWriteArrayList<>();
    private volatile Comportamento comportamento = Comportamento.NORMAL;
    private volatile long demoraEmMs = 0;

    public void limpar() {
        registros.clear();
        comportamento = Comportamento.NORMAL;
        demoraEmMs = 0;
    }

    public void comportamento(Comportamento novo) {
        this.comportamento = novo;
    }

    public void demora(long milissegundos) {
        this.comportamento = Comportamento.DEMORAR;
        this.demoraEmMs = milissegundos;
    }

    public List<Registro> registros() {
        return List.copyOf(registros);
    }

    @Override
    public String nome() {
        return NOME;
    }

    @Override
    public DefinicaoDeFerramenta definicao() {
        return new DefinicaoDeFerramenta(NOME, "Ferramenta de teste.", "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");
    }

    @Override
    public int ordem() {
        return 900;
    }

    @Override
    public boolean oferecida(EstadoDaConversa estado) {
        return true;
    }

    @Override
    public Object preparar(AtlasContexto contexto, FontesDoTurno turno, Map<String, Object> argumentos) {
        registros.add(new Registro("preparar", TransactionSynchronizationManager.isActualTransactionActive(), Thread.currentThread().getName()));
        if (comportamento == Comportamento.FALHAR_NO_PREPARO) {
            throw new br.com.rbs.portal.comum.DadosInvalidosException("O preparo da ferramenta de teste falhou.", Map.of());
        }
        if (comportamento == Comportamento.DEMORAR) {
            try {
                new CountDownLatch(1).await(demoraEmMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrompido) {
                Thread.currentThread().interrupt();
            }
        }
        return "preparado";
    }

    @Override
    public Execucao executar(AtlasContexto contexto, FontesDoTurno turno, Map<String, Object> argumentos, String chamadaId,
                             Object preparado) {
        registros.add(new Registro("executar", TransactionSynchronizationManager.isActualTransactionActive(), Thread.currentThread().getName()));
        if (comportamento == Comportamento.FALHAR_NA_EXECUCAO) {
            throw new br.com.rbs.portal.comum.DadosInvalidosException("A execução da ferramenta de teste falhou.", Map.of());
        }
        return Execucao.resultado("{\"preparado\":\"" + preparado + "\"}");
    }
}
