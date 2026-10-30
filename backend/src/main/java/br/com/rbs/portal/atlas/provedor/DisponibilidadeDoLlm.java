package br.com.rbs.portal.atlas.provedor;

import br.com.rbs.portal.atlas.ModoDoAtlas;
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
    /** O mesmo 503 com o relatório desligado ({@code atlas.relatorio.ligado=false}): não há botão a oferecer. */
    public static final String SEM_MODELO_NO_CHAT = "O Atlas está sem o modelo de linguagem agora. Tente de novo mais tarde.";

    private final ProvedorAtual provedor;
    private final ModoDoAtlas modo;

    public DisponibilidadeDoLlm(ProvedorAtual provedor, ModoDoAtlas modo) {
        this.provedor = provedor;
        this.modo = modo;
    }

    /** A frase do 503, conforme o modo: com o relatório ligado, manda para o botão; desligado, só pede para tentar depois. */
    public String semModelo() {
        return modo.relatorioLigado() ? SEM_MODELO : SEM_MODELO_NO_CHAT;
    }

    public boolean disponivel() {
        return provedor.provedor().isPresent();
    }
}
