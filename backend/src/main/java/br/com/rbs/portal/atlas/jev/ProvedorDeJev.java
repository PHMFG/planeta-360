package br.com.rbs.portal.atlas.jev;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * O JEV (TypeSafe) visto pelo nosso código (plano do Atlas completo, M7). [ATLAS] Uma implementação de verdade ({@link ClienteJev}) e,
 * nos testes, um roteiro: nenhum teste chama a API real. Uma chamada é UMA requisição ao {@code /v1/systemone}, com um {@code state}
 * e várias perguntas. Nunca é chamada dentro de uma transação (quem chama garante). Qualquer falha (sem chave, rede, tempo limite,
 * status que não é 200, resposta que não se entende) sobe como {@link JevIndisponivelException}, sem nova tentativa: o turno segue o
 * caminho do modelo de linguagem.
 */
public interface ProvedorDeJev {

    /** Faz as perguntas sobre o estado. */
    RespostaDoJev perguntar(PedidoAoJev pedido);

    /** O modelo do JEV que as requisições usam (para o traço e a tabela {@code atlas_decisao}); nunca leva chave. */
    String modelo();

    /** Uma pergunta: {@code choice} (com as opções e a descrição de cada uma, na ordem) ou {@code noul} (sem opções). */
    record PerguntaAoJev(String tipo, String instrucoes, Map<String, String> criterios) {

        public static final String CHOICE = "choice";
        public static final String NOUL = "noul";

        public PerguntaAoJev {
            criterios = criterios == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(criterios));
        }

        public static PerguntaAoJev escolha(String instrucoes, Map<String, String> criterios) {
            return new PerguntaAoJev(CHOICE, instrucoes, criterios);
        }

        public static PerguntaAoJev nota(String instrucoes) {
            return new PerguntaAoJev(NOUL, instrucoes, Map.of());
        }

        public boolean ehEscolha() {
            return CHOICE.equals(tipo);
        }
    }

    /** O que se manda: o estado (já sem dado pessoal) e as perguntas por nome, na ordem. */
    record PedidoAoJev(String estado, Map<String, PerguntaAoJev> perguntas) {

        public PedidoAoJev {
            perguntas = Collections.unmodifiableMap(new LinkedHashMap<>(perguntas));
        }
    }

    /**
     * A resposta a uma pergunta. {@code choice}: {@code escolha}, {@code confianca} e {@code probabilidades} (o objeto ou a lista que
     * o JEV mandou, do jeito que veio). {@code noul}: só {@code noul}, de 0 a 1. O que não se aplica ao tipo é nulo.
     */
    record RespostaDaPergunta(String escolha, Double confianca, Object probabilidades, Double noul) {

        public static RespostaDaPergunta deEscolha(String escolha, double confianca, Object probabilidades) {
            return new RespostaDaPergunta(escolha, confianca, probabilidades, null);
        }

        public static RespostaDaPergunta deNota(double noul) {
            return new RespostaDaPergunta(null, null, null, noul);
        }
    }

    /** As respostas por nome de pergunta e o tempo que a requisição levou. Pergunta sem resposta no JEV não aparece. */
    record RespostaDoJev(Map<String, RespostaDaPergunta> respostas, long latenciaMs) {

        public RespostaDoJev {
            respostas = Collections.unmodifiableMap(new LinkedHashMap<>(respostas));
        }
    }
}
