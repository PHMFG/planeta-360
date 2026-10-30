package br.com.rbs.portal.atlas.loop;

import br.com.rbs.portal.atlas.relatorio.ChecagemDeTexto;
import br.com.rbs.portal.atlas.relatorio.Referencias;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Resolucao;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * O filtro do texto do chat em fluxo, de UMA ida ao modelo. [ATLAS] [VALIDACAO] O texto chega em pedaços; antes de ir à tela, cada
 * trecho passa pela mesma {@link ChecagemDeTexto} do chat, e só sai o que ela aprovaria, já com os marcadores trocados pelos
 * valores (o mesmo que o item {@code texto} mostra no fim):
 * <ul>
 *   <li>um marcador {@code {{...}}} fica retido até fechar, e sai resolvido e formatado;</li>
 *   <li>a última palavra fica retida até vir um espaço (ou o fim): "tool" pode virar "toolbox", "parceria" pode virar
 *       "parceria_consultar"; o corte nunca cai dentro de um marcador;</li>
 *   <li>a cada trecho novo, a checagem roda sobre TUDO o que chegou até o corte (o texto cru, com os marcadores). Se ela recusar
 *       (algarismo fora de marcador, chave crua, marcador que não resolve ou sem valor, nome de ferramenta, "marcador"...), o
 *       filtro para de repassar e avisa uma vez ({@code texto_recusado}). O que já saiu tinha passado pela checagem.</li>
 * </ul>
 * No fim da ida ({@link #fechar}) o resto sai, se a checagem do texto inteiro aprovar. A checagem completa do loop continua
 * valendo depois disso. Não é seguro para várias threads: roda na thread do turno.
 */
final class FiltroDoTexto {

    private final Resolucao resolucao;
    private final ChecagemDeTexto checagem;
    private final OuvinteDoTurno ouvinte;
    private final StringBuilder recebido = new StringBuilder();
    private int aceito;               // quantos caracteres de "recebido" já passaram pelo filtro
    private String exibido = "";      // o que já foi à tela: paraExibicao(recebido[0, aceito])
    private boolean recusado;

    FiltroDoTexto(Resolucao resolucao, ChecagemDeTexto checagem, OuvinteDoTurno ouvinte) {
        this.resolucao = resolucao;
        this.checagem = checagem;
        this.ouvinte = ouvinte;
    }

    /** Um pedaço do texto cru, como o modelo o mandou. */
    void receber(String pedaco) {
        if (recusado || pedaco == null || pedaco.isEmpty()) {
            return;
        }
        recebido.append(pedaco);
        avancar(pontoSeguro());
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

    /** Recusa o texto desta ida, se ainda não recusou: para de repassar e avisa a tela, uma vez ({@code texto_recusado}). */
    void recusar() {
        if (!recusado) {
            recusado = true;
            ouvinte.textoRecusado();
        }
    }

    private void avancar(int corte) {
        if (corte <= aceito) {
            return;
        }
        String candidato = recebido.substring(0, corte);
        if (candidato.isBlank()) {
            return;   // nada para mostrar ainda (e a checagem recusaria o texto vazio)
        }
        if (!checagem.chat(resolucao, candidato).aprovado()) {
            recusar();
            return;
        }
        String agora = resolucao.paraExibicao(candidato);
        if (!agora.startsWith(exibido)) {
            recusar();   // não deveria acontecer (o corte nunca cai num marcador); na dúvida, nada de meio texto na tela
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
     * Até onde o texto recebido pode passar agora: antes de um marcador que ainda não fechou (ou de uma chave solta no fim, que
     * pode ser o começo de um), e até o último espaço que não está dentro de um marcador.
     */
    private int pontoSeguro() {
        String texto = recebido.toString();
        int limite = texto.length();
        int aberto = marcadorAberto(texto);
        if (aberto >= 0) {
            limite = aberto;
        } else if (texto.endsWith("{")) {
            limite = texto.length() - 1;
        }
        List<int[]> marcadores = new ArrayList<>();
        Matcher m = Referencias.MARCADOR.matcher(texto.substring(0, limite));
        while (m.find()) {
            marcadores.add(new int[]{m.start(), m.end()});
        }
        for (int i = limite - 1; i >= aceito; i--) {
            if (Character.isWhitespace(texto.charAt(i)) && !dentro(marcadores, i)) {
                return i + 1;
            }
        }
        return aceito;
    }

    /** A posição do primeiro "{{" que ainda não fechou (lendo da esquerda para a direita), ou -1. */
    private static int marcadorAberto(String texto) {
        int desde = 0;
        while (true) {
            int abre = texto.indexOf("{{", desde);
            if (abre < 0) {
                return -1;
            }
            int fecha = texto.indexOf("}}", abre + 2);
            if (fecha < 0) {
                return abre;
            }
            desde = fecha + 2;
        }
    }

    private static boolean dentro(List<int[]> marcadores, int posicao) {
        for (int[] marcador : marcadores) {
            if (posicao >= marcador[0] && posicao < marcador[1]) {
                return true;
            }
        }
        return false;
    }
}
