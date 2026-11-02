package br.com.rbs.portal.atlas.loop;

import br.com.rbs.portal.atlas.checagem.ChecagemDeFontes;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.checagem.Frases;
import br.com.rbs.portal.atlas.relatorio.ChecagemDeTexto.Veredito;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Resolucao;

/**
 * O filtro do texto do chat em fluxo, de UMA ida ao modelo. [ATLAS] [VALIDACAO] O texto chega em pedaços; antes de ir à tela, o que
 * chegou passa pela mesma {@link ChecagemDeFontes} do chat, e só sai o que ela aprovaria, já com os marcadores {@code {{ref}}} trocados
 * pelos valores (o mesmo que o item {@code texto} mostra no fim). As citações {@code [T1]} seguem no texto: é a tela quem as
 * transforma em link para a fonte.
 * <p>
 * <b>O filtro segura a frase inteira.</b> A regra "o número está na fonte citada na MESMA frase" não dá para conferir palavra a
 * palavra: o número costuma vir antes do {@code [T1]} da frase, e a citação pode vir até depois do ponto ({@code ...2026. [T1]}). Por
 * isso o texto só avança quando uma frase está completa, no sentido de {@link Frases}: ponto, {@code !}, {@code ?} ou reticências
 * seguidos de espaço, ou uma quebra de linha; {@code 1.250,00} e {@code 3.2} não cortam, as citações que vêm logo depois do ponto
 * ficam presas à frase anterior, e a frase fica em aberto enquanto o que vem depois do ponto ainda pode ser o começo de uma citação.
 * Um marcador {@code {{...}}} que não fechou também segura. Resposta sem pontuação sai no {@link #fechar}. A tela recebe a resposta
 * frase a frase; se a checagem recusar uma frase, o filtro para de repassar e avisa uma vez ({@code texto_recusado}), e o que já saiu
 * tinha passado pela checagem.
 * <p>
 * Quando a checagem recusa, o filtro guarda o motivo e até onde tinha conferido ({@link #motivoDaRecusa},
 * {@link #caracteresNaRecusa}), para o loop deixar a etapa {@code CHECAGEM} de origem {@code FLUXO} no traço. Não é seguro para várias
 * threads: roda na thread do turno.
 */
final class FiltroDoTexto {

    /** O motivo, no traço, de o texto já exibido não ser o começo do texto resolvido de novo. */
    static final String TEXTO_MUDOU_NA_TELA = "O texto que já estava na tela mudou quando o trecho seguinte chegou.";

    private final Resolucao resolucao;
    private final ChecagemDeFontes checagem;
    private final FontesDoTurno fontes;
    private final OuvinteDoTurno ouvinte;
    private final StringBuilder recebido = new StringBuilder();
    private int aceito;               // quantos caracteres de "recebido" já passaram pelo filtro
    private String exibido = "";      // o que já foi à tela: paraExibicao(recebido[0, aceito])
    private boolean recusado;
    private String motivoDaRecusa;    // a frase da checagem que recusou; nulo se não recusou, ou se foi o loop que mandou recusar
    private int caracteresNaRecusa;   // quanto do texto cru a checagem tinha conferido quando recusou

    FiltroDoTexto(Resolucao resolucao, ChecagemDeFontes checagem, FontesDoTurno fontes, OuvinteDoTurno ouvinte) {
        this.resolucao = resolucao;
        this.checagem = checagem;
        this.fontes = fontes;
        this.ouvinte = ouvinte;
    }

    /** Um pedaço do texto cru, como o modelo o mandou. */
    void receber(String bruto) {
        // Sem os caracteres invisíveis, como o texto que o loop confere e grava: um deles, sozinho, é um pedaço vazio.
        String pedaco = ChecagemDeFontes.semInvisiveis(bruto);
        if (recusado || pedaco == null || pedaco.isEmpty()) {
            return;
        }
        recebido.append(pedaco);
        avancar(fimDasFrasesCompletas());
    }

    /** A ida terminou: o que estava retido passa (ou é recusado) pela checagem do texto inteiro. */
    void fechar() {
        if (!recusado) {
            avancar(recebido.length());
        }
    }

    /** O filtro recusou o texto desta ida (e já avisou a tela)? */
    boolean recusado() {
        return recusado;
    }

    /** Algum trecho desta ida já foi à tela? */
    boolean saiuAlgo() {
        return !exibido.isEmpty();
    }

    /**
     * Por que a checagem do texto recusou o trecho (a frase dela), ou nulo se o filtro não recusou ou se só o loop mandou recusar
     * ({@link #recusar()}: a checagem completa recusou, ou o modelo caiu no meio do texto).
     */
    String motivoDaRecusa() {
        return motivoDaRecusa;
    }

    /** Quantos caracteres do texto cru a checagem tinha conferido quando recusou o trecho; 0 se ela não recusou. */
    int caracteresNaRecusa() {
        return caracteresNaRecusa;
    }

    /** Recusa o texto desta ida, se ainda não recusou: para de repassar e avisa a tela, uma vez ({@code texto_recusado}). */
    void recusar() {
        if (!recusado) {
            recusado = true;
            ouvinte.textoRecusado();
        }
    }

    /** A checagem recusou o trecho: guarda o motivo e o tamanho conferido (só da primeira vez) e recusa a ida. */
    private void recusarPelaChecagem(String motivo, int caracteres) {
        if (!recusado) {
            motivoDaRecusa = motivo;
            caracteresNaRecusa = caracteres;
        }
        recusar();
    }

    private void avancar(int corte) {
        if (corte <= aceito) {
            return;
        }
        String candidato = recebido.substring(0, corte);
        if (candidato.isBlank()) {
            return;   // nada para mostrar ainda (e a checagem recusaria o texto vazio)
        }
        Veredito veredito = checagem.chat(resolucao, fontes, candidato);
        if (!veredito.aprovado()) {
            recusarPelaChecagem(veredito.motivo(), candidato.length());
            return;
        }
        String agora = resolucao.paraExibicao(candidato);
        if (!agora.startsWith(exibido)) {
            // não deveria acontecer (o corte nunca cai num marcador); na dúvida, nada de meio texto na tela
            recusarPelaChecagem(TEXTO_MUDOU_NA_TELA, candidato.length());
            return;
        }
        String novo = agora.substring(exibido.length());
        aceito = corte;
        exibido = agora;
        if (!novo.isEmpty()) {
            ouvinte.texto(novo);
        }
    }

    /**
     * Até onde o texto recebido tem só frases completas: o fim da última frase que nada que chegue depois muda. Antes de uma frase
     * em aberto (sem ponto, ou com o ponto sem saber ainda o que vem depois, ou com um marcador que não fechou), nada avança.
     */
    private int fimDasFrasesCompletas() {
        String texto = recebido.toString();
        int fim = aceito;
        while (true) {
            int proximo = Frases.fimDaPrimeiraFraseCompleta(texto, fim);
            if (proximo < 0) {
                return fim;
            }
            fim = proximo;
        }
    }
}
