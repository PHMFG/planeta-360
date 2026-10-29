package br.com.rbs.portal.atlas;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorIndisponivelException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * O {@link ProvedorDeLlm} dos testes: em vez de chamar um modelo, devolve o que o roteiro mandou, na ordem, e guarda cada pedido
 * que recebeu (as mensagens, as ferramentas e se havia transação aberta na hora). Os testes do loop do agente nunca chamam o
 * provedor de verdade. Se o loop pedir mais do que o roteiro tem, o teste falha com um {@link AssertionError}. Atende o item 5 do
 * pedido da terceira parte do Atlas. Não faz parte da API.
 */
public class ProvedorRoteirizado implements ProvedorDeLlm {

    private final Deque<Function<PedidoAoLlm, RespostaDoLlm>> roteiro = new ArrayDeque<>();
    private final List<PedidoAoLlm> pedidos = new ArrayList<>();
    private final List<Boolean> transacaoAtiva = new ArrayList<>();

    /** Zera o roteiro e o que foi recebido: cada teste começa daqui. */
    public ProvedorRoteirizado limpar() {
        roteiro.clear();
        pedidos.clear();
        transacaoAtiva.clear();
        return this;
    }

    // ---------------------------------------------------------------------
    // O roteiro
    // ---------------------------------------------------------------------

    /** O modelo responde só com texto. */
    public ProvedorRoteirizado texto(String texto) {
        return passo(pedido -> new RespostaDoLlm(texto, List.of(), null));
    }

    /** O modelo pede uma ferramenta, com os argumentos em JSON. */
    public ProvedorRoteirizado chamada(String nome, String argumentos) {
        return chamadas(null, null, pedido(nome, argumentos));
    }

    /** O modelo pede uma ferramenta e junto escreve um texto. */
    public ProvedorRoteirizado chamadaComTexto(String texto, String nome, String argumentos) {
        return chamadas(texto, null, pedido(nome, argumentos));
    }

    /** O modelo pede uma ferramenta e manda o reasoning_content. */
    public ProvedorRoteirizado chamadaComRaciocinio(String raciocinio, String nome, String argumentos) {
        return chamadas(null, raciocinio, pedido(nome, argumentos));
    }

    /** O modelo pede várias ferramentas na mesma rodada. */
    public ProvedorRoteirizado chamadas(String texto, String raciocinio, ChamadaDeFerramenta... chamadas) {
        return passo(pedido -> new RespostaDoLlm(texto, List.of(chamadas), raciocinio));
    }

    /** O provedor não responde (rede, limite, 400...). */
    public ProvedorRoteirizado falha() {
        return passo(pedido -> {
            throw new ProvedorIndisponivelException("o provedor do teste não respondeu");
        });
    }

    /** O modelo não diz nada. */
    public ProvedorRoteirizado vazio() {
        return passo(pedido -> new RespostaDoLlm(null, List.of(), null));
    }

    /** Um passo à mão: recebe o pedido e devolve a resposta (para conferir o que o modelo recebeu na hora). */
    public ProvedorRoteirizado passo(Function<PedidoAoLlm, RespostaDoLlm> passo) {
        roteiro.add(passo);
        return this;
    }

    public static ChamadaDeFerramenta pedido(String nome, String argumentos) {
        return new ChamadaDeFerramenta("id-do-modelo", nome, argumentos);
    }

    // ---------------------------------------------------------------------
    // O provedor
    // ---------------------------------------------------------------------

    @Override
    public RespostaDoLlm responder(PedidoAoLlm pedido) {
        pedidos.add(pedido);
        transacaoAtiva.add(TransactionSynchronizationManager.isActualTransactionActive());
        Function<PedidoAoLlm, RespostaDoLlm> passo = roteiro.poll();
        if (passo == null) {
            throw new AssertionError("O loop pediu mais uma resposta ao modelo (a de número " + pedidos.size() + "), e o roteiro acabou.");
        }
        return passo.apply(pedido);
    }

    // ---------------------------------------------------------------------
    // O que o loop mandou
    // ---------------------------------------------------------------------

    public List<PedidoAoLlm> pedidos() {
        return List.copyOf(pedidos);
    }

    public PedidoAoLlm ultimoPedido() {
        return pedidos.get(pedidos.size() - 1);
    }

    /** Em cada ida ao modelo, havia uma transação aberta? (No loop, nunca.) */
    public List<Boolean> transacaoAtivaEmCadaPedido() {
        return List.copyOf(transacaoAtiva);
    }

    /** Quantos passos do roteiro o loop não usou. */
    public int sobraDoRoteiro() {
        return roteiro.size();
    }

    /** Os nomes das ferramentas que o pedido ofereceu. */
    public static List<String> nomesDasFerramentas(PedidoAoLlm pedido) {
        return pedido.ferramentas().stream().map(DefinicaoDeFerramenta::nome).toList();
    }

    /**
     * O histórico que o modelo recebe é válido para um provedor de chat completions: o primeiro é o de sistema, e toda
     * {@code tool_call} tem uma resposta de ferramenta com o mesmo id antes da próxima mensagem que não é de ferramenta; id
     * repetido não existe; resposta de ferramenta sem pedido também não.
     */
    public static void exigirHistoricoValido(PedidoAoLlm pedido) {
        List<MensagemDeChat> mensagens = pedido.mensagens();
        assertThat(mensagens.get(0).papel()).isEqualTo("system");
        Set<String> todosOsPedidos = new HashSet<>();
        Set<String> abertos = new HashSet<>();
        for (MensagemDeChat mensagem : mensagens.subList(1, mensagens.size())) {
            if (!"tool".equals(mensagem.papel())) {
                assertThat(abertos).as("pedidos de ferramenta sem resposta antes de uma mensagem " + mensagem.papel()).isEmpty();
            }
            switch (mensagem.papel()) {
                case "assistant" -> mensagem.chamadas().forEach(c -> {
                    assertThat(todosOsPedidos.add(c.id())).as("id repetido: " + c.id()).isTrue();
                    abertos.add(c.id());
                });
                case "tool" -> assertThat(abertos.remove(mensagem.chamadaId())).as("resposta de ferramenta sem pedido: " + mensagem.chamadaId()).isTrue();
                default -> {
                }
            }
        }
        assertThat(abertos).as("pedidos de ferramenta sem resposta no fim").isEmpty();
    }
}
