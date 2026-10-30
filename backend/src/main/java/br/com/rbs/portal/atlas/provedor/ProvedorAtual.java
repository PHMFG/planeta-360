package br.com.rbs.portal.atlas.provedor;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * O modelo de linguagem que vale AGORA. [ATLAS] É o ponto único que o {@code LoopDoAgente} e o {@link DisponibilidadeDoLlm}
 * consultam, nesta ordem de precedência:
 * <ol>
 *   <li>a chave posta pela tela de debug ({@code PUT /api/debug/llm}, só com {@code atlas.debug.chaves=true}), se houver;</li>
 *   <li>o bean {@link ProvedorDeLlm} das variáveis de ambiente ({@link LlmConfig}: {@code ATLAS_LLM=openai} e
 *       {@code OPENCODE_API_KEY}; nos testes, o roteiro), se houver;</li>
 *   <li>nenhum: o Atlas fica no plano B (o botão "Montar relatório").</li>
 * </ol>
 * A chave do debug fica só na memória da API (some quando ela reinicia), dentro do provedor montado; aqui guarda-se só o final
 * dela, para mostrar. A troca é atômica (um {@link AtomicReference} com um registro imutável): quem já pegou o provedor (um turno
 * em andamento) termina com ele, e a próxima consulta já vê o novo.
 */
@Component
public class ProvedorAtual {

    /** De onde vem o modelo que vale agora. */
    public enum Origem { DEBUG, AMBIENTE, NENHUM }

    /** A chave posta pelo debug: o provedor montado com ela e o que se pode mostrar (nunca a chave inteira). */
    public record ChaveDoDebug(ProvedorDeLlm provedor, String baseUrl, String modelo, boolean raciocinio, String finalDaChave,
                               UsuarioAutenticado configuradoPor, OffsetDateTime configuradoEm) {
    }

    /** Uma leitura do estado, coerente: a origem, o provedor dela (nulo em NENHUM) e, se for DEBUG, os dados da chave. */
    public record Escolha(Origem origem, ProvedorDeLlm provedor, ChaveDoDebug debug) {
    }

    private final ObjectProvider<ProvedorDeLlm> doAmbiente;
    private final AtomicReference<ChaveDoDebug> debug = new AtomicReference<>();

    public ProvedorAtual(ObjectProvider<ProvedorDeLlm> doAmbiente) {
        this.doAmbiente = doAmbiente;
    }

    public Escolha escolha() {
        ChaveDoDebug chave = debug.get();
        if (chave != null) {
            return new Escolha(Origem.DEBUG, chave.provedor(), chave);
        }
        ProvedorDeLlm ambiente = doAmbiente.getIfAvailable();
        return ambiente != null ? new Escolha(Origem.AMBIENTE, ambiente, null) : new Escolha(Origem.NENHUM, null, null);
    }

    /** O provedor que vale agora, se houver. Quem conduz um turno pega uma vez e usa o mesmo até o fim. */
    public Optional<ProvedorDeLlm> provedor() {
        return Optional.ofNullable(escolha().provedor());
    }

    /** Passa a usar a chave do debug (troca a anterior, se houver). */
    public void usarChaveDoDebug(ChaveDoDebug chave) {
        debug.set(chave);
    }

    /** Esquece a chave do debug: volta ao provedor das variáveis de ambiente, ou a nenhum. */
    public void esquecerChaveDoDebug() {
        debug.set(null);
    }
}
