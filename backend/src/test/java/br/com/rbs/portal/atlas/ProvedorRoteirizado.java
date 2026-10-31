package br.com.rbs.portal.atlas;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorIndisponivelException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private final Map<Integer, Pedacos> pedacosDoPasso = new HashMap<>();
    private final List<Integer> idasEmFluxo = new ArrayList<>();
    private final List<PedidoDeResumo> resumosPedidos = new CopyOnWriteArrayList<>();
    private volatile Function<PedidoDeResumo, String> resumidor = pedido -> RESUMO_PADRAO;
    private int registrados;

    /** O resumo do raciocínio que o roteiro devolve quando o teste não manda outro: passa na checagem do texto do chat. */
    public static final String RESUMO_PADRAO = "Conferi o que foi pedido e decidi como responder.";

    /** Os pedaços de um passo em fluxo. */
    private record Pedacos(List<String> raciocinio, List<String> texto) {
    }

    /** Zera o roteiro e o que foi recebido: cada teste começa daqui. */
    public ProvedorRoteirizado limpar() {
        roteiro.clear();
        pedidos.clear();
        transacaoAtiva.clear();
        pedacosDoPasso.clear();
        idasEmFluxo.clear();
        resumosPedidos.clear();
        resumidor = pedido -> RESUMO_PADRAO;
        registrados = 0;
        return this;
    }

    // ---------------------------------------------------------------------
    // O roteiro
    // ---------------------------------------------------------------------

    /** O modelo responde só com texto. */
    public ProvedorRoteirizado texto(String texto) {
        return passo(pedido -> new RespostaDoLlm(texto, List.of(), null));
    }

    /** O modelo responde só com texto e o provedor diz o que a ida gastou (para os testes do diagnóstico somarem tokens e custo). */
    public ProvedorRoteirizado textoComUso(String texto, int tokensEntrada, int tokensSaida, java.math.BigDecimal custoDeLista) {
        return passo(pedido -> new RespostaDoLlm(texto, List.of(), null,
                new UsoDoLlm("modelo-do-roteiro", tokensEntrada, tokensSaida, "stop", 7L, custoDeLista)));
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
        registrados++;
        return this;
    }

    /** O que o provedor devolve a cada pedido de resumo do raciocínio (por padrão, {@link #RESUMO_PADRAO}); pode lançar para falhar. */
    public ProvedorRoteirizado resumindoCom(Function<PedidoDeResumo, String> resumidor) {
        this.resumidor = resumidor;
        return this;
    }

    public static ChamadaDeFerramenta pedido(String nome, String argumentos) {
        return new ChamadaDeFerramenta("id-do-modelo", nome, argumentos);
    }

    // ---------------------------------------------------------------------
    // O provedor
    // ---------------------------------------------------------------------

    /**
     * O modelo responde só com texto, em fluxo, nestes pedaços (sem fluxo, a resposta é o texto inteiro). Com um raciocínio, ele
     * chega antes, também em pedaços (um por palavra).
     */
    public ProvedorRoteirizado textoEmPedacos(String raciocinio, String... pedacos) {
        pedacosDoPasso.put(registrados, new Pedacos(raciocinio == null ? List.of() : List.of(raciocinio.split("(?<= )")),
                List.of(pedacos)));
        return passo(pedido -> new RespostaDoLlm(String.join("", pedacos), List.of(), raciocinio));
    }

    @Override
    public RespostaDoLlm responderEmFluxo(PedidoAoLlm pedido, OuvinteDoFluxo ouvinte) {
        Pedacos pedacos = pedacosDoPasso.remove(pedidos.size());   // o passo que esta ida vai consumir
        idasEmFluxo.add(pedidos.size());
        RespostaDoLlm resposta = responder(pedido);
        if (pedacos == null) {
            if (resposta.raciocinio() != null) {
                ouvinte.raciocinio(resposta.raciocinio());
            }
            if (resposta.texto() != null) {
                ouvinte.texto(resposta.texto());
            }
        } else {
            pedacos.raciocinio().forEach(ouvinte::raciocinio);
            pedacos.texto().forEach(ouvinte::texto);
        }
        return resposta;
    }

    /** Quantas idas ao modelo foram em fluxo. */
    public int idasEmFluxo() {
        return idasEmFluxo.size();
    }

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

    /** O resumo do raciocínio não é uma ida do loop: não consome o roteiro e fica à parte de {@link #pedidos}. */
    @Override
    public String resumir(PedidoDeResumo pedido) {
        resumosPedidos.add(pedido);
        return resumidor.apply(pedido);
    }

    // ---------------------------------------------------------------------
    // O que o loop mandou
    // ---------------------------------------------------------------------

    /** Os pedidos de resumo do raciocínio que o loop fez, na ordem em que chegaram. */
    public List<PedidoDeResumo> resumosPedidos() {
        return List.copyOf(resumosPedidos);
    }

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
