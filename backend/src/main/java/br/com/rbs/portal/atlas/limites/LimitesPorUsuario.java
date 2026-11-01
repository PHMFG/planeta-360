package br.com.rbs.portal.atlas.limites;

import br.com.rbs.portal.comum.LimiteDeUsoException;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.EstimationProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Os limites do Atlas por usuário (plano do Atlas completo, M8, problema 15 do quadro). [ATLAS] Dois baldes de fichas do Bucket4j,
 * na memória (some quando a API reinicia, o que basta: o objetivo é frear rajada e gasto do provedor, e o teto diário por parceria
 * já está no banco):
 * <ul>
 *   <li><b>Mensagens de texto por hora</b> ({@code atlas.limites.mensagens-por-hora}, padrão {@value #MENSAGENS_POR_HORA}): a rota
 *       44 com {@code texto}. A ficha volta aos poucos (uma a cada 3 minutos, com o padrão), então a espera nunca é a hora inteira.
 *       O botão "Montar relatório" (plano B) não conta, e a resposta do cartão (rota 46) não consome, mas acima do limite segue o
 *       plano B, sem 429, para a marca não ficar presa a um cartão aberto.</li>
 *   <li><b>Relatórios por dia pelo modelo</b> ({@code atlas.limites.relatorios-por-dia}, padrão {@value #RELATORIOS_POR_DIA}): o
 *       {@code relatorio_iniciar} que o modelo chama. Acima do limite a ferramenta devolve um erro ao modelo, que sugere o botão.</li>
 * </ul>
 * A conferência da mensagem só olha ({@link #esperaPelaMensagem}); a ficha é gasta depois, quando o turno já começou
 * ({@link #consumirMensagem}), para uma tentativa recusada antes (a conversa ocupada, 409) não gastar cota. O tempo vem do
 * {@link Clock} do contexto (o relógio do sistema, ou o do teste), então "quanto falta" se confere sem esperar.
 */
@Component
public class LimitesPorUsuario {

    public static final int MENSAGENS_POR_HORA = 20;
    public static final int RELATORIOS_POR_DIA = 3;

    /** O corpo do 429 da mensagem: o que aconteceu e quanto falta. */
    public static final String FRASE_DA_MENSAGEM = "Você atingiu o limite de mensagens por hora. Tente de novo em ";

    /**
     * O erro que o {@code relatorio_iniciar} devolve ao modelo no 4º relatório do dia. Sem número, nem por extenso: o modelo repete
     * o que lê, e a checagem do texto recusaria um número sem origem.
     */
    public static final String FRASE_DO_RELATORIO = "A pessoa já chegou ao limite de relatórios que o assistente monta por dia. "
            + "Não monte outro: explique isso sem citar números e sugira o botão Montar relatório.";

    private final int mensagensPorHora;
    private final int relatoriosPorDia;
    private final TimeMeter relogio;
    private final ConcurrentMap<Long, Bucket> mensagens = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Bucket> relatorios = new ConcurrentHashMap<>();

    public LimitesPorUsuario(@Value("${atlas.limites.mensagens-por-hora:" + MENSAGENS_POR_HORA + "}") int mensagensPorHora,
                             @Value("${atlas.limites.relatorios-por-dia:" + RELATORIOS_POR_DIA + "}") int relatoriosPorDia,
                             ObjectProvider<Clock> relogioDoContexto) {
        this.mensagensPorHora = mensagensPorHora;
        this.relatoriosPorDia = relatoriosPorDia;
        this.relogio = medidorDe(relogioDoContexto.getIfAvailable(Clock::systemUTC));
    }

    private static TimeMeter medidorDe(Clock relogio) {
        return new TimeMeter() {
            @Override
            public long currentTimeNanos() {
                Instant agora = relogio.instant();
                return Math.addExact(Math.multiplyExact(agora.getEpochSecond(), 1_000_000_000L), agora.getNano());
            }

            @Override
            public boolean isWallClockBased() {
                return true;
            }
        };
    }

    public int mensagensPorHora() {
        return mensagensPorHora;
    }

    public int relatoriosPorDia() {
        return relatoriosPorDia;
    }

    private Bucket novo(long fichas, Duration periodo) {
        return Bucket.builder().addLimit(limite -> limite.capacity(fichas).refillGreedy(fichas, periodo))
                .withCustomTimePrecision(relogio).build();
    }

    private Bucket balde(ConcurrentMap<Long, Bucket> baldes, long usuarioId, long fichas, Duration periodo) {
        return baldes.computeIfAbsent(usuarioId, chave -> novo(fichas, periodo));
    }

    // ---------------------------------------------------------------------
    // Mensagens de texto por hora
    // ---------------------------------------------------------------------

    /** Quanto falta para a próxima mensagem do usuário poder sair; vazio se ele pode mandar agora. Só olha, não gasta ficha. */
    public Optional<Duration> esperaPelaMensagem(long usuarioId) {
        EstimationProbe sonda = balde(mensagens, usuarioId, mensagensPorHora, Duration.ofHours(1)).estimateAbilityToConsume(1);
        return sonda.canBeConsumed() ? Optional.empty() : Optional.of(Duration.ofNanos(sonda.getNanosToWaitForRefill()));
    }

    /** O usuário pode mandar uma mensagem de texto agora? (A rota 46 usa para escolher entre o modelo e o plano B.) */
    public boolean mensagemDisponivel(long usuarioId) {
        return esperaPelaMensagem(usuarioId).isEmpty();
    }

    /** O 429 da mensagem de texto, com quanto falta para liberar. */
    public LimiteDeUsoException recusaDaMensagem(Duration espera) {
        return new LimiteDeUsoException(FRASE_DA_MENSAGEM + descrever(espera) + ".");
    }

    /** 429 se o usuário não pode mandar agora. Só olha: a ficha é gasta por {@link #consumirMensagem}. */
    public void exigirMensagem(long usuarioId) {
        Optional<Duration> espera = esperaPelaMensagem(usuarioId);
        if (espera.isPresent()) {
            throw recusaDaMensagem(espera.get());
        }
    }

    /**
     * Gasta a ficha da mensagem, depois de o turno começar. Dois pedidos do mesmo usuário em conversas diferentes podem ter passado
     * juntos por {@link #exigirMensagem} com uma ficha só: aqui o segundo recebe o 429.
     */
    public void consumirMensagem(long usuarioId) {
        ConsumptionProbe sonda = balde(mensagens, usuarioId, mensagensPorHora, Duration.ofHours(1)).tryConsumeAndReturnRemaining(1);
        if (!sonda.isConsumed()) {
            throw recusaDaMensagem(Duration.ofNanos(sonda.getNanosToWaitForRefill()));
        }
    }

    // ---------------------------------------------------------------------
    // Relatórios por dia pelo modelo
    // ---------------------------------------------------------------------

    /** Gasta a ficha de um relatório do modelo. Falso se o usuário já chegou ao limite do dia. */
    public boolean consumirRelatorio(long usuarioId) {
        return balde(relatorios, usuarioId, relatoriosPorDia, Duration.ofDays(1)).tryConsume(1);
    }

    /** Devolve a ficha de um relatório que não chegou a ser criado (a ferramenta falhou depois de gastá-la). */
    public void devolverRelatorio(long usuarioId) {
        balde(relatorios, usuarioId, relatoriosPorDia, Duration.ofDays(1)).addTokens(1);
    }

    // ---------------------------------------------------------------------
    // Apoio
    // ---------------------------------------------------------------------

    /** "1 minuto", "12 minutos", "2 horas": a espera arredondada para cima, em minutos até duas horas e depois em horas. */
    public static String descrever(Duration espera) {
        long minutos = Math.max(1, (espera.toNanos() + 59_999_999_999L) / 60_000_000_000L);
        if (minutos <= 120) {
            return minutos + (minutos == 1 ? " minuto" : " minutos");
        }
        long horas = (minutos + 59) / 60;
        return horas + " horas";
    }
}
