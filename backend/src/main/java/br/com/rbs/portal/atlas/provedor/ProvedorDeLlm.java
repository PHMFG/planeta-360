package br.com.rbs.portal.atlas.provedor;

/**
 * O modelo de linguagem que o Atlas usa, visto pelo nosso código. [ATLAS] Esta interface é o ÚNICO ponto que liga o LLM: se
 * existe um bean {@code ProvedorDeLlm}, o Atlas conversa em texto livre ({@code llmDisponivel} verdadeiro nas rotas 42 a 46); se
 * não existe, o Atlas só oferece o botão "Montar relatório" (plano B, sem modelo de linguagem) e a mensagem de texto recebe 503.
 * Na parte 2 do MVP não há implementação e o bean não existe, então a API sobe e a suíte roda sem chave nem provedor.
 * <p>
 * A parte 3 (Spring AI, o loop do agente, o chat) escreve os métodos desta interface e a implementação, ligada ao
 * {@code ChatModel}; nos testes, uma implementação com roteiro. O loop não conhece o Spring AI: fala só com esta interface.
 * Ver {@link DisponibilidadeDoLlm} para quem decide o que a API diz.
 */
public interface ProvedorDeLlm {
}
