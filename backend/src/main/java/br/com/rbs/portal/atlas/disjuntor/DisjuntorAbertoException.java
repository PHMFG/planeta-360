package br.com.rbs.portal.atlas.disjuntor;

import br.com.rbs.portal.atlas.provedor.ProvedorIndisponivelException;
import java.time.Duration;

/**
 * O disjuntor do provedor está aberto: a chamada nem saiu. [ATLAS] É uma {@link ProvedorIndisponivelException}, então quem já trata a
 * queda do provedor (o loop, a busca, o JEV) trata esta igual e na hora, sem esperar o tempo limite. Quem precisa saber que foi o
 * disjuntor (o traço do diagnóstico) pergunta pelo tipo. A mensagem é segura para o log: o nome do provedor e quanto falta.
 */
public class DisjuntorAbertoException extends ProvedorIndisponivelException {

    private final String provedor;
    private final String estado;
    private final Duration falta;

    public DisjuntorAbertoException(String provedor, String estado, Duration falta) {
        super("O disjuntor do provedor " + provedor + " está aberto; a próxima tentativa é em " + Math.max(0, falta.toSeconds()) + " s.");
        this.provedor = provedor;
        this.estado = estado;
        this.falta = falta;
    }

    /** {@code llm}, {@code jev} ou {@code gemini}. */
    public String provedor() {
        return provedor;
    }

    /** {@code ABERTO}, ou {@code MEIO_ABERTO} quando a tentativa de prova já está ocupada. */
    public String estado() {
        return estado;
    }

    /** Quanto falta para o disjuntor deixar uma chamada de prova passar (zero se já pode). */
    public Duration falta() {
        return falta;
    }
}
