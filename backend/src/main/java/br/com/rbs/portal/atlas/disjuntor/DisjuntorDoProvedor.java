package br.com.rbs.portal.atlas.disjuntor;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O disjuntor de UM provedor externo (plano do Atlas completo, M8, problema 17 do quadro; Resilience4j 2.4, só o módulo do
 * disjuntor, sem o starter do Spring). [ATLAS] Abre com {@code falhasSeguidas} falhas seguidas (5) ou com {@code taxaDeFalha} por cento
 * das últimas {@code janela} chamadas (50% das 10), e depois de {@code espera} (60 s) deixa passar UMA chamada de prova: se ela dá
 * certo, fecha; se falha, abre outra vez por mais {@code espera}. Aberto, a chamada nem sai: {@link #executar} lança
 * {@link DisjuntorAbertoException} na hora, e quem chama faz o que faria com o provedor fora do ar (o modelo de linguagem dá a frase
 * fixa de falha, o JEV fica como desligado, o Gemini cai para a busca textual).
 * <p>
 * O Resilience4j conta a taxa de falha na janela; as "5 falhas seguidas" ele não tem, então o contador é daqui e, ao chegar a 5, abre
 * o disjuntor à mão. O tempo vem do {@link Clock} (o do sistema, ou o do teste). Uma falha que não é do provedor (o chamador diz
 * qual: um erro de programação, uma chamada interrompida) devolve a permissão sem contar. Com {@code ligado} falso o disjuntor não
 * existe: a chamada passa direto e o estado é {@code DESLIGADO}.
 */
public final class DisjuntorDoProvedor {

    public static final String FECHADO = "FECHADO";
    public static final String ABERTO = "ABERTO";
    public static final String MEIO_ABERTO = "MEIO_ABERTO";
    public static final String DESLIGADO = "DESLIGADO";

    private static final Logger log = LoggerFactory.getLogger(DisjuntorDoProvedor.class);

    private final String nome;
    private final boolean ligado;
    private final int falhasSeguidas;
    private final Duration espera;
    private final Clock relogio;
    private final CircuitBreaker disjuntor;
    private final AtomicInteger seguidas = new AtomicInteger();
    private volatile Instant abertoAte;

    public DisjuntorDoProvedor(String nome, boolean ligado, int falhasSeguidas, float taxaDeFalha, int janela, Duration espera,
                               Clock relogio) {
        this.nome = nome;
        this.ligado = ligado;
        this.falhasSeguidas = falhasSeguidas;
        this.espera = espera;
        this.relogio = relogio;
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .clock(relogio)
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(janela)
                .minimumNumberOfCalls(janela)
                .failureRateThreshold(taxaDeFalha)
                .waitDurationInOpenState(espera)
                .permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .slowCallDurationThreshold(Duration.ofHours(1))   // lentidão não abre: o prazo de cada provedor já corta a chamada
                .writableStackTraceEnabled(false)
                .build();
        this.disjuntor = CircuitBreaker.of(nome, config);
        disjuntor.getEventPublisher().onStateTransition(evento -> {
            State para = evento.getStateTransition().getToState();
            if (para == State.OPEN) {
                abertoAte = relogio.instant().plus(espera);
                log.warn("Atlas: o disjuntor do provedor {} abriu; tenta de novo em {} s.", nome, espera.toSeconds());
            } else if (para == State.CLOSED) {
                seguidas.set(0);
                abertoAte = null;
                log.info("Atlas: o disjuntor do provedor {} fechou.", nome);
            }
        });
    }

    public String nome() {
        return nome;
    }

    /** Roda a chamada; qualquer exceção do provedor conta como falha. Aberto, lança {@link DisjuntorAbertoException} sem chamar. */
    public <T> T executar(Supplier<T> chamada) {
        return executar(chamada, falha -> true);
    }

    /**
     * Roda a chamada. {@code contaComoFalha} diz quais exceções são do provedor: as outras sobem sem contar (a permissão volta).
     * {@link Error} também sobe sem contar. Aberto, lança {@link DisjuntorAbertoException} sem chamar.
     */
    public <T> T executar(Supplier<T> chamada, Predicate<RuntimeException> contaComoFalha) {
        if (!ligado) {
            return chamada.get();
        }
        if (!disjuntor.tryAcquirePermission()) {
            throw new DisjuntorAbertoException(nome, estado(), falta());
        }
        long inicio = disjuntor.getCurrentTimestamp();
        T resultado;
        try {
            resultado = chamada.get();
        } catch (RuntimeException falha) {
            if (!contaComoFalha.test(falha)) {
                disjuntor.releasePermission();
                throw falha;
            }
            disjuntor.onError(disjuntor.getCurrentTimestamp() - inicio, disjuntor.getTimestampUnit(), falha);
            if (seguidas.incrementAndGet() >= falhasSeguidas && disjuntor.getState() == State.CLOSED) {
                disjuntor.transitionToOpenState();
            }
            throw falha;
        } catch (Error erro) {
            disjuntor.releasePermission();
            throw erro;
        }
        disjuntor.onSuccess(disjuntor.getCurrentTimestamp() - inicio, disjuntor.getTimestampUnit());
        seguidas.set(0);
        return resultado;
    }

    /** {@code FECHADO}, {@code ABERTO}, {@code MEIO_ABERTO} (a próxima chamada é a de prova) ou {@code DESLIGADO}. */
    public String estado() {
        if (!ligado) {
            return DESLIGADO;
        }
        return switch (disjuntor.getState()) {
            case OPEN -> aguardando() ? ABERTO : MEIO_ABERTO;
            case HALF_OPEN -> MEIO_ABERTO;
            default -> FECHADO;
        };
    }

    /** Aberto e ainda dentro da espera: nenhuma chamada passa. Com a espera vencida a próxima é a de prova, e isto é falso. */
    public boolean bloqueado() {
        return ligado && disjuntor.getState() == State.OPEN && aguardando();
    }

    /** Quanto falta para uma chamada de prova poder passar; zero fechado ou com a espera vencida. */
    public Duration falta() {
        Instant ate = abertoAte;
        if (!ligado || ate == null) {
            return Duration.ZERO;
        }
        Duration falta = Duration.between(relogio.instant(), ate);
        return falta.isNegative() ? Duration.ZERO : falta;
    }

    /** Fecha o disjuntor e zera a conta (a chave do modelo trocada na tela de debug, por exemplo, dá outra chance). */
    public void reiniciar() {
        seguidas.set(0);
        abertoAte = null;
        disjuntor.reset();
    }

    private boolean aguardando() {
        Instant ate = abertoAte;
        return ate != null && !relogio.instant().isAfter(ate);   // o Resilience4j só deixa a prova passar DEPOIS do prazo
    }
}
