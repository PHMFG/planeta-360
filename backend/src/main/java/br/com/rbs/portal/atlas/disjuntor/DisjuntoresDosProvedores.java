package br.com.rbs.portal.atlas.disjuntor;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Os três disjuntores do Atlas (M8): o do modelo de linguagem, o do JEV e o do Gemini (embeddings da pergunta). [ATLAS] Cada um
 * abre com 5 falhas seguidas ou com 50% das 10 últimas chamadas, e tenta de novo depois de 60 s
 * ({@code atlas.disjuntores.*}). {@code atlas.disjuntores.ligado} é o interruptor: falso (o padrão do perfil test) e as chamadas
 * passam direto, sem nunca abrir. O {@link Clock} é o do contexto (o do sistema, ou o do teste), para os 60 s se conferirem sem
 * esperar. O estado de cada um aparece na rota 52 ({@code disjuntor}) e a recusa deixa a etapa {@code DISJUNTOR} no traço.
 */
@Component
public class DisjuntoresDosProvedores {

    public static final String LLM = "llm";
    public static final String JEV = "jev";
    public static final String GEMINI = "gemini";

    private final DisjuntorDoProvedor llm;
    private final DisjuntorDoProvedor jev;
    private final DisjuntorDoProvedor gemini;

    @Autowired
    public DisjuntoresDosProvedores(@Value("${atlas.disjuntores.ligado:true}") boolean ligado,
                                    @Value("${atlas.disjuntores.falhas-seguidas:5}") int falhasSeguidas,
                                    @Value("${atlas.disjuntores.taxa-de-falha:50}") float taxaDeFalha,
                                    @Value("${atlas.disjuntores.janela:10}") int janela,
                                    @Value("${atlas.disjuntores.espera:PT60S}") Duration espera,
                                    ObjectProvider<Clock> relogioDoContexto) {
        this(ligado, falhasSeguidas, taxaDeFalha, janela, espera, relogioDoContexto.getIfAvailable(Clock::systemUTC));
    }

    /** Os três disjuntores com este relógio (os testes sem o contexto do Spring montam assim). */
    public DisjuntoresDosProvedores(boolean ligado, int falhasSeguidas, float taxaDeFalha, int janela, Duration espera, Clock relogio) {
        this.llm = new DisjuntorDoProvedor(LLM, ligado, falhasSeguidas, taxaDeFalha, janela, espera, relogio);
        this.jev = new DisjuntorDoProvedor(JEV, ligado, falhasSeguidas, taxaDeFalha, janela, espera, relogio);
        this.gemini = new DisjuntorDoProvedor(GEMINI, ligado, falhasSeguidas, taxaDeFalha, janela, espera, relogio);
    }

    public DisjuntorDoProvedor llm() {
        return llm;
    }

    public DisjuntorDoProvedor jev() {
        return jev;
    }

    public DisjuntorDoProvedor gemini() {
        return gemini;
    }

    /** O disjuntor do provedor com este nome ({@code llm}, {@code jev} ou {@code gemini}), ou nulo. */
    public DisjuntorDoProvedor de(String nome) {
        return switch (nome) {
            case LLM -> llm;
            case JEV -> jev;
            case GEMINI -> gemini;
            default -> null;
        };
    }
}
