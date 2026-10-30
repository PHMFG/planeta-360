package br.com.rbs.portal.atlas;

import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.loop.OuvinteDoTurno;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * A resposta em fluxo (SSE) de um turno das rotas 44 e 46 (docs/api.md, "Resposta em fluxo"). [ATLAS] Cada evento tem nome e um
 * JSON em {@code data}: {@code pensando} ({@code {}}, uma vez por ida ao modelo que raciocina; o raciocínio em si nunca sai do
 * servidor), {@code resumo} ({@code {"texto"}}, o resumo do raciocínio de uma ida, quando fica pronto), {@code texto}
 * ({@code {"delta"}}), {@code etapa} ({@code {"texto"}}), {@code texto_recusado} ({@code {}}), {@code fim} (o objeto Conversa, e
 * fecha) e {@code erro} ({@code {"status","detail"}}, e fecha). Quem chama é a thread do turno, e também a do resumo (que manda o
 * {@code resumo} quando fica pronto): por isso cada envio é um bloco só, sem intercalar dois eventos.
 * <p>
 * O cliente pode ir embora no meio: a primeira falha de envio (ou o aviso do servidor de que a conexão caiu ou o tempo acabou)
 * fecha o fluxo daqui, e o que vier depois é ignorado. Nunca sobe exceção para o loop: o turno termina e grava do mesmo jeito.
 */
final class FluxoDoTurno implements OuvinteDoTurno {

    /** O fluxo vale um pouco mais que a trava do turno (5 minutos), para nunca fechar com o turno ainda de pé. */
    static final Duration TEMPO_DO_FLUXO = Duration.ofMinutes(6);

    private static final Logger log = LoggerFactory.getLogger(FluxoDoTurno.class);

    private final SseEmitter emissor = new SseEmitter(TEMPO_DO_FLUXO.toMillis());
    private volatile boolean aberto = true;

    FluxoDoTurno() {
        emissor.onTimeout(() -> aberto = false);
        emissor.onError(erro -> aberto = false);
        emissor.onCompletion(() -> aberto = false);
    }

    SseEmitter emissor() {
        return emissor;
    }

    /** O cliente ainda está ouvindo? */
    boolean aberto() {
        return aberto;
    }

    @Override
    public void pensando() {
        enviar("pensando", Map.of());
    }

    @Override
    public void resumo(String texto) {
        enviar("resumo", Map.of("texto", texto));
    }

    @Override
    public void etapa(String texto) {
        enviar("etapa", Map.of("texto", texto));
    }

    @Override
    public void texto(String pedaco) {
        enviar("texto", Map.of("delta", pedaco));
    }

    @Override
    public void textoRecusado() {
        enviar("texto_recusado", Map.of());
    }

    /** O fim do turno: o mesmo objeto Conversa da resposta JSON. Fecha o fluxo. */
    synchronized void fim(ConversaDto conversa) {
        enviar("fim", conversa);
        fechar();
    }

    /** Uma falha inesperada depois de o fluxo abrir. Fecha o fluxo. */
    synchronized void erro(int status, String detalhe) {
        enviar("erro", Map.of("status", status, "detail", detalhe));
        fechar();
    }

    private synchronized void enviar(String evento, Object dados) {
        if (!aberto) {
            return;
        }
        try {
            emissor.send(SseEmitter.event().name(evento).data(dados, MediaType.APPLICATION_JSON));
        } catch (Exception clienteSaiu) {   // IOException da conexão, ou o emissor já fechado pelo servidor
            aberto = false;
            log.debug("Atlas: o cliente deixou o fluxo ({}); o turno segue no servidor.", clienteSaiu.getClass().getSimpleName());
        }
    }

    private synchronized void fechar() {
        if (aberto) {
            aberto = false;
            try {
                emissor.complete();
            } catch (Exception jaFechado) {
                log.debug("Atlas: o fluxo já estava fechado.");
            }
        }
    }
}
