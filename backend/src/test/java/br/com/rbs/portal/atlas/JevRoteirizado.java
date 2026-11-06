package br.com.rbs.portal.atlas;

import br.com.rbs.portal.atlas.jev.JevIndisponivelException;
import br.com.rbs.portal.atlas.jev.PontoDoJev;
import br.com.rbs.portal.atlas.jev.ProvedorDeJev;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * O {@link ProvedorDeJev} dos testes: em vez de chamar a API do JEV, responde o que o teste mandou para cada ponto de decisão e guarda
 * cada requisição que recebeu (o {@code state}, as perguntas e se havia transação aberta na hora). Nenhum teste chama o JEV de verdade.
 * Ponto sem regra é um {@link AssertionError} (o teste esqueceu de rotear), nunca uma resposta de mentira. Seguro para várias threads
 * (o R3 e o V2 mandam requisições em paralelo). Não faz parte da API.
 */
public class JevRoteirizado implements ProvedorDeJev {

    /**
     * A requisição que o JEV recebeu e o ponto a que ela pertence (pelo nome das perguntas), se havia transação aberta na thread que
     * chamou e quantas conexões do pool estavam em uso na hora (zero quando o turno não segura conexão enquanto espera o JEV).
     */
    public record Recebida(PontoDoJev ponto, PedidoAoJev pedido, boolean transacaoAberta, int conexoesEmUso) {
    }

    private final Map<PontoDoJev, Function<PedidoAoJev, RespostaDoJev>> regras = new ConcurrentHashMap<>();
    private final List<Recebida> recebidas = new CopyOnWriteArrayList<>();
    private volatile Supplier<Integer> conexoesEmUso = () -> 0;
    private volatile long demoraEmMs;

    public JevRoteirizado limpar() {
        regras.clear();
        recebidas.clear();
        demoraEmMs = 0;
        return this;
    }

    /** Todo ponto demora este tanto para responder (um JEV lento); {@link #limpar} volta ao normal. */
    public JevRoteirizado atrasar(long ms) {
        this.demoraEmMs = ms;
        return this;
    }

    @Override
    public String modelo() {
        return "jev-de-teste";
    }

    /** De onde vem o número de conexões do pool em uso (o {@code JevRoteirizadoConfig} liga ao Hikari). */
    public JevRoteirizado medirConexoesCom(Supplier<Integer> conexoesEmUso) {
        this.conexoesEmUso = conexoesEmUso;
        return this;
    }

    // ---------------------------------------------------------------------
    // O roteiro
    // ---------------------------------------------------------------------

    /** O ponto responde como o teste mandar. */
    public JevRoteirizado passo(PontoDoJev ponto, Function<PedidoAoJev, RespostaDoJev> regra) {
        regras.put(ponto, regra);
        return this;
    }

    /** O destino que o JEV teria sugerido para o arquivo (o ponto ARQUIVO_DESTINO, em sombra). */
    public JevRoteirizado arquivoDestino(String escolha, double confianca) {
        return passo(PontoDoJev.ARQUIVO_DESTINO, pedido -> resposta("destino",
                RespostaDaPergunta.deEscolha(escolha, confianca, probabilidades(escolha, confianca))));
    }

    /** A intenção da mensagem, sem pedir modelo em branco e sem sinal de injeção. */
    public JevRoteirizado intencao(String escolha, double confianca) {
        return intencao(escolha, confianca, "nenhum", 0.99);
    }

    /** A intenção, o tipo do modelo em branco ({@code briefing_conteudo}...) e a confiança dele; o sinal de injeção é baixo. */
    public JevRoteirizado intencao(String escolha, double confianca, String tipoModelo, double confiancaDoTipo) {
        return passo(PontoDoJev.INTENCAO, pedido -> resposta(
                "intencao", RespostaDaPergunta.deEscolha(escolha, confianca, probabilidades(escolha, confianca)),
                "tipo_modelo", RespostaDaPergunta.deEscolha(tipoModelo, confiancaDoTipo, probabilidades(tipoModelo, confiancaDoTipo)),
                "sinal_injecao", RespostaDaPergunta.deNota(0.02)));
    }

    public JevRoteirizado cartao(String opcao, double confianca) {
        return passo(PontoDoJev.CARTAO, pedido -> resposta("cartao",
                RespostaDaPergunta.deEscolha(opcao, confianca, probabilidades(opcao, confianca))));
    }

    /** Cada trecho recebe a {@code noul} que a função der ao {@code state} dele. */
    public JevRoteirizado fontes(Function<String, Double> noulDoEstado) {
        return passo(PontoDoJev.FONTES, pedido -> resposta("relevante", RespostaDaPergunta.deNota(noulDoEstado.apply(pedido.estado()))));
    }

    /** Cada frase recebe a escolha que a função der ao texto da pergunta dela ({@code apoia}, {@code contradiz}, {@code nao_diz}). */
    public JevRoteirizado resposta(Function<String, String> escolhaDaFrase, double confianca) {
        return passo(PontoDoJev.RESPOSTA, pedido -> {
            Map<String, RespostaDaPergunta> respostas = new LinkedHashMap<>();
            pedido.perguntas().forEach((nome, pergunta) -> {
                String escolha = escolhaDaFrase.apply(pergunta.instrucoes());
                respostas.put(nome, RespostaDaPergunta.deEscolha(escolha, confianca, probabilidades(escolha, confianca)));
            });
            return new RespostaDoJev(respostas, 5);
        });
    }

    public JevRoteirizado coerencia(String escolha, double confianca) {
        return passo(PontoDoJev.COERENCIA, pedido -> resposta("coerencia",
                RespostaDaPergunta.deEscolha(escolha, confianca, probabilidades(escolha, confianca))));
    }

    /** Cada lacuna opcional recebe a {@code noul} que a função der ao {@code state} dela. */
    public JevRoteirizado lacunas(Function<String, Double> noulDoEstado) {
        return passo(PontoDoJev.LACUNAS, pedido -> resposta("vale_perguntar",
                RespostaDaPergunta.deNota(noulDoEstado.apply(pedido.estado()))));
    }

    /** O ponto falha com este motivo ({@code HTTP_429}, {@code TIMEOUT}...): o turno cai para o caminho do modelo. */
    public JevRoteirizado falhar(PontoDoJev ponto, String motivo) {
        return passo(ponto, pedido -> {
            throw new JevIndisponivelException(motivo);
        });
    }

    private static Map<String, Double> probabilidades(String escolha, double confianca) {
        Map<String, Double> mapa = new LinkedHashMap<>();
        mapa.put(escolha, confianca);
        mapa.put("resto", Math.round((1 - confianca) * 10_000) / 10_000.0);
        return mapa;
    }

    private static RespostaDoJev resposta(Object... paresNomeResposta) {
        Map<String, RespostaDaPergunta> respostas = new LinkedHashMap<>();
        for (int i = 0; i < paresNomeResposta.length; i += 2) {
            respostas.put((String) paresNomeResposta[i], (RespostaDaPergunta) paresNomeResposta[i + 1]);
        }
        return new RespostaDoJev(respostas, 5);
    }

    // ---------------------------------------------------------------------
    // O provedor
    // ---------------------------------------------------------------------

    @Override
    public RespostaDoJev perguntar(PedidoAoJev pedido) {
        PontoDoJev ponto = pontoDe(pedido);
        recebidas.add(new Recebida(ponto, pedido, TransactionSynchronizationManager.isActualTransactionActive(), conexoesEmUso.get()));
        Function<PedidoAoJev, RespostaDoJev> regra = regras.get(ponto);
        if (regra == null) {
            throw new AssertionError("O JEV roteirizado não tem regra para o ponto " + ponto.nome() + ".");
        }
        if (demoraEmMs > 0) {
            try {
                Thread.sleep(demoraEmMs);
            } catch (InterruptedException interrompida) {
                Thread.currentThread().interrupt();
                throw new JevIndisponivelException("INTERROMPIDO");
            }
        }
        return regra.apply(pedido);
    }

    /** O ponto a que a requisição pertence, pelo nome das perguntas (cada ponto tem os seus). */
    static PontoDoJev pontoDe(PedidoAoJev pedido) {
        List<String> nomes = List.copyOf(pedido.perguntas().keySet());
        if (nomes.contains("destino")) {
            return PontoDoJev.ARQUIVO_DESTINO;
        }
        if (nomes.contains("intencao")) {
            return PontoDoJev.INTENCAO;
        }
        if (nomes.contains("cartao")) {
            return PontoDoJev.CARTAO;
        }
        if (nomes.contains("relevante")) {
            return PontoDoJev.FONTES;
        }
        if (nomes.contains("coerencia")) {
            return PontoDoJev.COERENCIA;
        }
        if (nomes.contains("vale_perguntar")) {
            return PontoDoJev.LACUNAS;
        }
        if (nomes.stream().anyMatch(nome -> nome.startsWith("frase_"))) {
            return PontoDoJev.RESPOSTA;
        }
        throw new AssertionError("Pergunta ao JEV que nenhum ponto conhece: " + nomes);
    }

    // ---------------------------------------------------------------------
    // O que o JEV recebeu
    // ---------------------------------------------------------------------

    public List<Recebida> recebidas() {
        return List.copyOf(recebidas);
    }

    public List<Recebida> recebidasDe(PontoDoJev ponto) {
        return recebidas.stream().filter(r -> r.ponto() == ponto).toList();
    }

    /** Em alguma requisição ao JEV o pool tinha uma conexão em uso? (Num teste de um turno só, nunca pode ter.) */
    public boolean algumaComConexaoEmUso() {
        return recebidas.stream().anyMatch(r -> r.conexoesEmUso() > 0);
    }

    /** Em alguma requisição ao JEV havia uma transação aberta? (Nunca pode haver.) */
    public boolean algumaComTransacaoAberta() {
        return recebidas.stream().anyMatch(Recebida::transacaoAberta);
    }
}
