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
        NORMAL, FALHAR_NO_PREPARO, FALHAR_NA_EXECUCAO, DEMORAR,
        /**
         * Registra um trecho no turno (como a busca faz na fase 2) e devolve um resultado que o loop não consegue gravar: o JSON com o
         * escape de nulo, que o PostgreSQL não guarda no jsonb. A transação do loop desfaz, e o modelo recebe um erro.
         */
        FONTE_E_RESULTADO_QUE_NAO_GRAVA,
        /**
         * Registra no turno um registro do portal ([R1], com o documento de origem em {@code dados}) e uma conta ([C1], de nome
         * {@code tarifa}), como o M5 faz, e devolve o resultado normalmente.
         */
        REGISTRO_E_CONTA
    }

    /** O documento de onde o registro do comportamento {@link Comportamento#REGISTRO_E_CONTA} diz ter vindo. */
    public record DocumentoDeTeste(long documentoId, int versao, int pagina) {
    }

    /** O trecho (real, do banco) que o comportamento {@link Comportamento#FONTE_E_RESULTADO_QUE_NAO_GRAVA} registra no turno. */
    public record TrechoDeTeste(long trechoId, long documentoId, String titulo, int versao, int pagina, String secao, String texto) {
    }

    public record Registro(String fase, boolean transacaoAberta, String thread) {
    }

    private final List<Registro> registros = new CopyOnWriteArrayList<>();
    private volatile Comportamento comportamento = Comportamento.NORMAL;
    private volatile long demoraEmMs = 0;
    private volatile TrechoDeTeste trechoDeTeste;
    private volatile DocumentoDeTeste documentoDoRegistro;

    public void limpar() {
        trechoDeTeste = null;
        documentoDoRegistro = null;
        registros.clear();
        comportamento = Comportamento.NORMAL;
        demoraEmMs = 0;
    }

    public void comportamento(Comportamento novo) {
        this.comportamento = novo;
    }

    public void fonteQueNaoGrava(TrechoDeTeste trecho) {
        this.trechoDeTeste = trecho;
        this.comportamento = Comportamento.FONTE_E_RESULTADO_QUE_NAO_GRAVA;
    }

    /** O registro e a conta do comportamento {@link Comportamento#REGISTRO_E_CONTA}; {@code documento} é a origem do registro (ou nulo). */
    public void registroEConta(DocumentoDeTeste documento) {
        this.documentoDoRegistro = documento;
        this.comportamento = Comportamento.REGISTRO_E_CONTA;
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
        if (comportamento == Comportamento.FONTE_E_RESULTADO_QUE_NAO_GRAVA) {
            TrechoDeTeste t = trechoDeTeste;
            turno.registrarTrecho(t.trechoId(), t.documentoId(), t.titulo(), t.versao(), t.pagina(), t.secao(), t.texto());
            turno.concluirBusca(false);
            return Execucao.resultado("{\"texto\":\"\\u0000\"}");
        }
        if (comportamento == Comportamento.REGISTRO_E_CONTA) {
            Map<String, Object> dadosDoRegistro = new java.util.LinkedHashMap<>();
            dadosDoRegistro.put("unidade", "INSERCAO");
            DocumentoDeTeste documento = documentoDoRegistro;
            if (documento != null) {
                dadosDoRegistro.put("documentoId", documento.documentoId());
                dadosDoRegistro.put("versao", documento.versao());
                dadosDoRegistro.put("pagina", documento.pagina());
            }
            FontesDoTurno.Fonte registro = turno.registrarRegistro("entrega", 77, "Cortesias do evento", "Cortesias › Lista",
                    "Há 12 cortesias previstas para o evento.", dadosDoRegistro);
            Map<String, Object> conta = new java.util.LinkedHashMap<>();
            conta.put("nome", "tarifa");
            conta.put("valorFormatado", "10.608,00");
            FontesDoTurno.Fonte fonteDaConta = turno.registrarCalculo("tarifa", "tarifa", "tarifa: preco = 312; area = 34; resultado = 10.608,00",
                    conta);
            return Execucao.resultado("{\"registro\":\"" + registro.marcador() + "\",\"conta\":\"" + fonteDaConta.marcador() + "\"}");
        }
        return Execucao.resultado("{\"preparado\":\"" + preparado + "\"}");
    }
}
