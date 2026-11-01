package br.com.rbs.portal.atlas.jev;

import java.util.ArrayList;
import java.util.List;

/**
 * O que o JEV tem de guardar durante UM turno (plano do Atlas completo, M7). [ATLAS] Nasce com o contexto do turno (a fábrica de
 * contexto o cria) e morre com ele: o orçamento de requisições (no máximo {@value #MAXIMO_DE_REQUISICOES} por turno; depois disso o
 * que falta segue sem JEV, e o traço registra), as decisões que ainda não foram para {@code atlas_decisao} (elas só vão ao banco no
 * fim, numa transação curta: nenhum ponto grava durante uma chamada ao JEV) e se o turno já terminou antes do modelo (a resposta
 * fixa de um cartão aberto). Seguro para duas threads: o turno e as requisições em paralelo do R3.
 * <p>
 * {@link #NENHUM} é o estado de quem não tem turno (um contexto montado à mão): inerte, o JEV nunca é consultado.
 */
public final class JevDoTurno {

    public static final int MAXIMO_DE_REQUISICOES = 20;

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

    /** Quantas requisições ao JEV este turno ainda pode fazer. */
    public synchronized int restante() {
        return ativo ? MAXIMO_DE_REQUISICOES - requisicoes : 0;
    }

    /** Quantas requisições ao JEV este turno já fez (as que contam no orçamento). */
    public synchronized int requisicoes() {
        return requisicoes;
    }

    /** Reserva até {@code quantas} requisições e devolve quantas coube: 0 quando o orçamento do turno acabou. */
    synchronized int reservar(int quantas) {
        if (!ativo) {
            return 0;
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
