package br.com.rbs.portal.atlas.provedor;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Diz se há modelo de linguagem para conversar. [ATLAS] É o ponto único de {@code llmDisponivel}: verdadeiro quando existe um bean
 * {@link ProvedorDeLlm}, falso quando não existe (o caso desta etapa e da suíte de testes). As rotas 42 a 46 perguntam aqui; a
 * etapa seguinte liga o provedor registrando o bean, sem mexer em mais nenhum lugar.
 */
@Component
public class DisponibilidadeDoLlm {

    /** O que a rota 44 responde (503) a uma mensagem de texto quando não há modelo de linguagem. */
    public static final String SEM_MODELO = "O Atlas está sem o modelo de linguagem agora. Use o botão Montar relatório.";

    private final ObjectProvider<ProvedorDeLlm> provedor;

    public DisponibilidadeDoLlm(ObjectProvider<ProvedorDeLlm> provedor) {
        this.provedor = provedor;
    }

    public boolean disponivel() {
        return provedor.getIfAvailable() != null;
    }
}
