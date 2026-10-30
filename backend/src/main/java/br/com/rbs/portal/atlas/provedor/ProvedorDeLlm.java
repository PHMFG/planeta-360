package br.com.rbs.portal.atlas.provedor;

import java.util.List;

/**
 * O modelo de linguagem que o Atlas usa, visto pelo nosso código. [ATLAS] Esta interface é o ÚNICO ponto que liga o LLM: se
 * existe um bean {@code ProvedorDeLlm}, o Atlas conversa em texto livre ({@code llmDisponivel} verdadeiro nas rotas 42 a 46); se
 * não existe, o Atlas só oferece o botão "Montar relatório" (plano B, sem modelo de linguagem) e a mensagem de texto recebe 503.
 * <p>
 * O loop do agente não conhece o Spring AI: fala só com esta interface, no formato de chat completions (mensagens com papel,
 * ferramentas descritas em JSON Schema, resposta com texto, {@code tool_calls} e {@code reasoning_content}). Uma chamada é UMA
 * ida ao modelo: executar as ferramentas, pausar no cartão e retomar é do loop. Implementações: {@code ProvedorSpringAi} (o
 * {@code ChatModel} do Spring AI, ligada só com {@code ATLAS_LLM=openai} e a chave) e, nos testes, um roteiro. O cliente HTTP
 * próprio (plano B do provedor, seção 16 do plano) seria só outra implementação.
 */
public interface ProvedorDeLlm {

    /**
     * Uma ida ao modelo. Não pode ser chamada dentro de uma transação (o loop garante). Falha de rede, do provedor ou resposta que
     * não se entende sobe como {@link ProvedorIndisponivelException}: nunca com mensagem que leve chave ou cabeçalho.
     */
    RespostaDoLlm responder(PedidoAoLlm pedido);

    /**
     * A mesma ida ao modelo, em fluxo: cada pedaço do {@code reasoning_content} e do texto vai ao {@code ouvinte} à medida que
     * chega, e no fim volta a resposta inteira, igual à de {@link #responder} (os pedidos de ferramenta chegam em pedaços e só
     * saem montados, na resposta). As mesmas regras de {@link #responder}: fora de transação, falha como
     * {@link ProvedorIndisponivelException}. O padrão, para quem não transmite, chama {@link #responder} e passa cada parte
     * inteira de uma vez.
     */
    default RespostaDoLlm responderEmFluxo(PedidoAoLlm pedido, OuvinteDoFluxo ouvinte) {
        RespostaDoLlm resposta = responder(pedido);
        if (resposta.raciocinio() != null) {
            ouvinte.raciocinio(resposta.raciocinio());
        }
        if (resposta.texto() != null) {
            ouvinte.texto(resposta.texto());
        }
        return resposta;
    }

    /** Quem recebe os pedaços de uma ida ao modelo em fluxo, na ordem em que chegam. Roda na thread de quem chamou. */
    interface OuvinteDoFluxo {

        /** Um pedaço do {@code reasoning_content}. */
        void raciocinio(String pedaco);

        /** Um pedaço do texto da resposta, cru (com os marcadores do jeito que o modelo os escreveu). */
        void texto(String pedaco);
    }

    /** Um pedido de ferramenta: o id (único na conversa), o nome e os argumentos como texto JSON, do jeito que o modelo mandou. */
    record ChamadaDeFerramenta(String id, String nome, String argumentos) {
    }

    /**
     * Uma mensagem no formato de chat completions. {@code papel} é {@code system}, {@code user}, {@code assistant} ou
     * {@code tool}. No assistente, {@code chamadas} traz os pedidos de ferramenta e {@code raciocinio} o
     * {@code reasoning_content} (se veio). Na ferramenta, {@code chamadaId} é o pedido que ela responde e {@code ferramenta} o
     * nome dela; {@code conteudo} é o JSON do resultado.
     */
    record MensagemDeChat(String papel, String conteudo, List<ChamadaDeFerramenta> chamadas, String chamadaId, String ferramenta,
                          String raciocinio) {

        public MensagemDeChat {
            chamadas = chamadas == null ? List.of() : List.copyOf(chamadas);
        }

        public static MensagemDeChat sistema(String texto) {
            return new MensagemDeChat("system", texto, List.of(), null, null, null);
        }

        public static MensagemDeChat usuario(String texto) {
            return new MensagemDeChat("user", texto, List.of(), null, null, null);
        }

        public static MensagemDeChat assistente(String texto, List<ChamadaDeFerramenta> chamadas, String raciocinio) {
            return new MensagemDeChat("assistant", texto, chamadas, null, null, raciocinio);
        }

        public static MensagemDeChat resultado(String chamadaId, String ferramenta, String conteudoJson) {
            return new MensagemDeChat("tool", conteudoJson, List.of(), chamadaId, ferramenta, null);
        }
    }

    /** Uma ferramenta que o modelo pode chamar: nome, descrição e o JSON Schema dos argumentos (texto JSON). */
    record DefinicaoDeFerramenta(String nome, String descricao, String esquema) {
    }

    /**
     * O que vai ao modelo: a {@code sessao} (o id da conversa, estável, vai no cabeçalho {@code x-opencode-session}), as mensagens
     * na ordem (a primeira é a de sistema) e as ferramentas do estado atual.
     */
    record PedidoAoLlm(String sessao, List<MensagemDeChat> mensagens, List<DefinicaoDeFerramenta> ferramentas) {

        public PedidoAoLlm {
            mensagens = List.copyOf(mensagens);
            ferramentas = ferramentas == null ? List.of() : List.copyOf(ferramentas);
        }
    }

    /** O que o modelo respondeu: texto (ou nulo), pedidos de ferramenta (ou vazio) e o {@code reasoning_content} (ou nulo). */
    record RespostaDoLlm(String texto, List<ChamadaDeFerramenta> chamadas, String raciocinio) {

        public RespostaDoLlm {
            chamadas = chamadas == null ? List.of() : List.copyOf(chamadas);
        }
    }
}
