package br.com.rbs.portal.atlas.provedor;

import org.springframework.stereotype.Component;

/**
 * Diz se há modelo de linguagem para conversar. [ATLAS] É o ponto único de {@code llmDisponivel}: verdadeiro quando o
 * {@link ProvedorAtual} tem um provedor (a chave posta pelo debug, ou o bean {@link ProvedorDeLlm} das variáveis de ambiente),
 * falso quando não tem (o padrão: {@code ATLAS_LLM} desligado ou sem chave, e a suíte de testes). As rotas 42 a 46 perguntam aqui.
 */
@Component
public class DisponibilidadeDoLlm {

    /** O que a rota 44 responde (503) a uma mensagem de texto quando não há modelo de linguagem. */
    public static final String SEM_MODELO = "O Atlas está sem o modelo de linguagem agora. Use o botão Montar relatório.";

    private final ProvedorAtual provedor;

    public DisponibilidadeDoLlm(ProvedorAtual provedor) {
        this.provedor = provedor;
    }

    public boolean disponivel() {
        return provedor.provedor().isPresent();
    }
}
