package br.com.rbs.portal.atlas.jev;

import java.util.ArrayList;
import java.util.List;

/**
 * O que o JEV tem de guardar durante UM turno (plano do Atlas completo, M7). [ATLAS] Nasce com o contexto do turno (a fábrica de
 * contexto o cria) e morre com ele: o orçamento de requisições (os pontos {@code ATIVO} têm no máximo {@value #MAXIMO_DE_REQUISICOES}
 * por turno, e os pontos em {@code SOMBRA}, {@value #MAXIMO_DE_REQUISICOES_EM_SOMBRA} contados à parte, para a sombra, que só observa,
 * não gastar o que o ponto que age precisa; passado o limite, o que falta segue sem JEV, e o traço registra), as decisões que ainda
 * não foram para {@code atlas_decisao} (elas só vão ao banco no fim, numa transação curta: nenhum ponto grava durante uma chamada ao
 * JEV) e se o turno já terminou antes do modelo (a resposta fixa de um cartão aberto). Seguro para duas threads: o turno e as
 * requisições em paralelo do R3.
 * <p>
 * {@link #NENHUM} é o estado de quem não tem turno (um contexto montado à mão): inerte, o JEV nunca é consultado.
 */
public final class JevDoTurno {

    /** O limite de requisições dos pontos {@code ATIVO} em um turno. */
    public static final int MAXIMO_DE_REQUISICOES = 20;
    /** O limite, à parte, dos pontos em {@code SOMBRA}: a sombra só observa e não pode gastar o orçamento de quem age. */
    public static final int MAXIMO_DE_REQUISICOES_EM_SOMBRA = 12;

    /** O estado sem turno: não consulta o JEV, não guarda nada. */
    public static final JevDoTurno NENHUM = new JevDoTurno(false);

    /** Uma decisão do JEV a gravar em {@code atlas_decisao}, com o que o servidor fez ou teria feito. */
    static final class Decisao {

        final PontoDoJev ponto;
        final ModoDoJev modo;
        final double limiar;
        final String pergunta;
        final String escolha;
        final Double confianca;
        final Object probabilidades;
        final String modelo;
        final Long latenciaMs;
        final String erro;
        final String acao;
        final String alvo;
        String decisaoDoLlm;

        Decisao(PontoDoJev ponto, ModoDoJev modo, double limiar, String pergunta, String escolha, Double confianca,
                Object probabilidades, String modelo, Long latenciaMs, String erro, String acao, String alvo) {
            this.ponto = ponto;
            this.modo = modo;
            this.limiar = limiar;
            this.pergunta = pergunta;
            this.escolha = escolha;
            this.confianca = confianca;
            this.probabilidades = probabilidades;
            this.modelo = modelo;
            this.latenciaMs = latenciaMs;
            this.erro = erro;
            this.acao = acao;
            this.alvo = alvo;
        }
    }

    private final boolean ativo;
    private final List<Decisao> decisoes = new ArrayList<>();
    private int requisicoes;
    private int requisicoesEmSombra;
    private boolean encerradoNoPreparo;
    private Long mensagemDoPreparo;
    private String marca;

    public JevDoTurno() {
        this(true);
    }

    private JevDoTurno(boolean ativo) {
        this.ativo = ativo;
    }

    /** Este estado é de um turno de verdade? Falso no {@link #NENHUM}. */
    public boolean ativo() {
        return ativo;
    }

    /** Quantas requisições ao JEV os pontos {@code ATIVO} ainda podem fazer neste turno. */
    public synchronized int restante() {
        return ativo ? MAXIMO_DE_REQUISICOES - requisicoes : 0;
    }

    /** Quantas requisições ao JEV os pontos em {@code SOMBRA} ainda podem fazer neste turno (o limite deles é à parte). */
    public synchronized int restanteEmSombra() {
        return ativo ? MAXIMO_DE_REQUISICOES_EM_SOMBRA - requisicoesEmSombra : 0;
    }

    /** Quantas requisições ao JEV os pontos {@code ATIVO} já fizeram neste turno (as que contam no orçamento deles). */
    public synchronized int requisicoes() {
        return requisicoes;
    }

    /** Quantas requisições ao JEV os pontos em {@code SOMBRA} já fizeram neste turno (as que contam no limite deles). */
    public synchronized int requisicoesEmSombra() {
        return requisicoesEmSombra;
    }

    /**
     * Reserva até {@code quantas} requisições do ponto que está neste {@code modo} e devolve quantas coube: 0 quando o limite dele
     * acabou. {@code SOMBRA} gasta o limite da sombra; os outros modos ({@code ATIVO}), o do ponto ativo.
     */
    synchronized int reservar(int quantas, ModoDoJev modo) {
        if (!ativo) {
            return 0;
        }
        if (modo == ModoDoJev.SOMBRA) {
            int concedidas = Math.max(0, Math.min(quantas, MAXIMO_DE_REQUISICOES_EM_SOMBRA - requisicoesEmSombra));
            requisicoesEmSombra += concedidas;
            return concedidas;
        }
        int concedidas = Math.max(0, Math.min(quantas, MAXIMO_DE_REQUISICOES - requisicoes));
        requisicoes += concedidas;
        return concedidas;
    }

    synchronized void adicionar(Decisao decisao) {
        if (ativo) {
            decisoes.add(decisao);
        }
    }

    /** As decisões que ainda não foram gravadas, na ordem; a lista do turno fica vazia. */
    synchronized List<Decisao> esvaziar() {
        List<Decisao> pendentes = new ArrayList<>(decisoes);
        decisoes.clear();
        return pendentes;
    }

    /** As decisões ainda não gravadas (uma cópia), para quem precisa olhar sem gravar. */
    synchronized List<Decisao> pendentes() {
        return List.copyOf(decisoes);
    }

    /** O que o modelo de linguagem fez no turno, junto das decisões do ponto que ainda não tinham isso (para comparar em sombra). */
    public synchronized void decisaoDoLlm(PontoDoJev ponto, String decisaoDoLlm) {
        for (Decisao decisao : decisoes) {
            if (decisao.ponto == ponto && decisao.decisaoDoLlm == null) {
                decisao.decisaoDoLlm = decisaoDoLlm;
            }
        }
    }

    /**
     * O turno acabou na preparação, sem chamar o modelo (a resposta fixa de um cartão aberto). {@code mensagemId} é a mensagem do
     * assistente que a preparação gravou: a etapa {@code SAIDA} do traço a leva, e por ela a conversa liga a resposta ao turno
     * (o botão de diagnóstico).
     */
    public synchronized void encerrarNoPreparo(long mensagemId) {
        encerradoNoPreparo = true;
        mensagemDoPreparo = mensagemId;
    }

    public synchronized boolean encerradoNoPreparo() {
        return encerradoNoPreparo;
    }

    /** A mensagem do assistente que a preparação gravou ao encerrar o turno; nulo quando o turno não acabou nela. */
    public synchronized Long mensagemDoPreparo() {
        return mensagemDoPreparo;
    }

    /** O nome da marca da conversa, que a máscara tira do que vai ao JEV; nulo até alguém o pedir. */
    synchronized String marca() {
        return marca;
    }

    synchronized void marca(String nome) {
        this.marca = nome;
    }
}
