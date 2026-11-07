package br.com.rbs.portal.atlas.provedor;

import java.math.BigDecimal;
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

    /**
     * Uma chamada à parte, que não é uma ida do loop: resume o raciocínio cru de uma mensagem do assistente para a tela. [ATLAS]
     * O raciocínio nunca sai do servidor; a conversa recebe só este resumo, que o servidor ainda confere antes de guardar. É um
     * pedido de chat simples, sem ferramentas, com o raciocínio do modelo desligado ({@code "thinking": {"type": "disabled"}}),
     * poucos tokens de saída e temperatura baixa. Devolve o texto do modelo (ou nulo, se veio em branco). As mesmas regras de
     * {@link #responder}: fora de transação, falha como {@link ProvedorIndisponivelException}, e, como o que se pede aqui é
     * justamente o raciocínio, a mensagem da exceção nunca leva o corpo do erro do provedor. O padrão, para quem não resume, é
     * falhar: o resumo é opcional e a mensagem fica sem ele.
     */
    default String resumir(PedidoDeResumo pedido) {
        throw new ProvedorIndisponivelException("Este provedor não resume o raciocínio.");
    }

    /**
     * O mesmo que {@link #resumir}, devolvendo também o que a chamada gastou ({@link UsoDoLlm}), para o diagnóstico somar o custo
     * dos resumos. O padrão chama {@link #resumir} e devolve o uso nulo; quem lê o uso do provedor (o {@code ProvedorSpringAi})
     * sobrescreve este método, e {@link #resumir} passa a ser só o texto dele.
     */
    default ResumoDoLlm resumirMedindo(PedidoDeResumo pedido) {
        return new ResumoDoLlm(resumir(pedido), null);
    }

    /**
     * O nome do modelo que este provedor chama, para o diagnóstico quando a resposta não traz o dela; nulo se não sabe. Nunca leva
     * chave.
     */
    default String modelo() {
        return null;
    }

    /** O texto do resumo (ou nulo, se veio em branco) e o que a chamada gastou (ou nulo, se o provedor não diz). */
    record ResumoDoLlm(String texto, UsoDoLlm uso) {
    }

    /**
     * O pedido de {@link #resumir}: a {@code sessao} (o id da conversa, no cabeçalho {@code x-opencode-session}), as
     * {@code instrucoes} (a mensagem de sistema, o prompt do resumo) e a {@code entrada} (a mensagem do usuário, com o raciocínio
     * a resumir, que é dado e nunca instrução).
     */
    record PedidoDeResumo(String sessao, String instrucoes, String entrada) {
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
     * Uma imagem que vai junto da mensagem do usuário, em base64 (o pedido de visão do E9). O provedor compatível com a OpenAI a
     * manda como {@code image_url} com o prefixo {@code data:}; o que não lê imagem recusa o pedido.
     */
    record Imagem(String mimeType, String base64) {
    }

    /**
     * O que vai ao modelo: a {@code sessao} (o id da conversa, estável, vai no cabeçalho {@code x-opencode-session}), as mensagens
     * na ordem (a primeira é a de sistema), as ferramentas do estado atual e, quando há, a imagem que acompanha a mensagem da pessoa.
     */
    record PedidoAoLlm(String sessao, List<MensagemDeChat> mensagens, List<DefinicaoDeFerramenta> ferramentas, Imagem imagem) {

        public PedidoAoLlm {
            mensagens = List.copyOf(mensagens);
            ferramentas = ferramentas == null ? List.of() : List.copyOf(ferramentas);
        }

        /** O pedido sem imagem (o de todo o chat e o da extração de texto). */
        public PedidoAoLlm(String sessao, List<MensagemDeChat> mensagens, List<DefinicaoDeFerramenta> ferramentas) {
            this(sessao, mensagens, ferramentas, null);
        }
    }

    /** Este provedor entende imagem? Falso por padrão; quem manda {@code image_url} sobrescreve. */
    default boolean leImagem() {
        return false;
    }

    /**
     * O que se mediu de uma ida ao modelo, para o diagnóstico (rotas 49 a 53 do docs/api.md): o modelo que respondeu, os tokens de
     * entrada e de saída, o motivo do fim ({@code stop}, {@code tool_calls}, {@code length}...), a latência da chamada em
     * milissegundos e o custo de lista em dólares quando o provedor o informa ({@code usage.opencode.list_cost} no OpenCode Go).
     * Qualquer campo é nulo quando o provedor não o disse; nenhum leva texto da conversa, raciocínio nem chave.
     */
    record UsoDoLlm(String modelo, Integer tokensEntrada, Integer tokensSaida, String motivoDoFim, Long latenciaMs,
                    BigDecimal custoDeLista) {
    }

    /**
     * O que o modelo respondeu: texto (ou nulo), pedidos de ferramenta (ou vazio), o {@code reasoning_content} (ou nulo) e o que a
     * ida gastou ({@link UsoDoLlm}, ou nulo se quem respondeu não mede, como o roteiro dos testes).
     */
    record RespostaDoLlm(String texto, List<ChamadaDeFerramenta> chamadas, String raciocinio, UsoDoLlm uso) {

        public RespostaDoLlm {
            chamadas = chamadas == null ? List.of() : List.copyOf(chamadas);
        }

        public RespostaDoLlm(String texto, List<ChamadaDeFerramenta> chamadas, String raciocinio) {
            this(texto, chamadas, raciocinio, null);
        }

        /** A mesma resposta com o que se mediu dela. */
        public RespostaDoLlm comUso(UsoDoLlm medido) {
            return new RespostaDoLlm(texto, chamadas, raciocinio, medido);
        }

        /** Sem o raciocínio cru: um {@code toString} (de um log, de uma mensagem de erro) não pode levá-lo. */
        @Override
        public String toString() {
            return "RespostaDoLlm[comTexto=" + (texto != null) + ", chamadas=" + chamadas.size() + ", uso=" + uso + "]";
        }
    }
}
