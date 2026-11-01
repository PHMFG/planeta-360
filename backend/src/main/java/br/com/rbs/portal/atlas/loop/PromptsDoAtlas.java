package br.com.rbs.portal.atlas.loop;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;

/**
 * O prompt de sistema do Atlas em cada modo (M6). [ATLAS] {@code chat.txt} é a base dos dois: tem todas as regras de fontes,
 * registros, contas e números da parceria, e uma linha só com {@value #MARCADOR_DOS_PEDIDOS}, no lugar de dizer o que fazer com um
 * pedido de relatório. O modo escolhe o que entra ali:
 * <ul>
 *   <li><b>Relatório desligado</b> ({@code atlas.relatorio.ligado=false}): {@code relatorio-desligado.txt}, com as duas linhas que o
 *       chat sempre teve (todo pedido de relatório vai para {@code pedido_de_relatorio}, e a conversa pode ter mensagens antigas
 *       sobre um relatório). O prompt fica igual em conteúdo ao de antes do M6;</li>
 *   <li><b>Relatório ligado</b>: {@code relatorio-ligado.txt}, que separa os dois caminhos (o relatório da ativação monta aqui, com
 *       {@code relatorio_iniciar}; qualquer outro vai para {@code pedido_de_relatorio}), e, no fim do prompt, o capítulo de
 *       {@code relatorio.txt} (o fluxo do relatório e a regra do número do texto dele: nenhum, só marcador).</li>
 * </ul>
 * O resultado ainda leva {@code @MARCA@} e {@code @DATA@}, que o {@link LoopDoAgente} troca a cada turno. Qualquer outro marcador
 * {@code @...@} que sobrar é erro de montagem: a API não sobe. Sem estado: tudo estático.
 * <p>
 * O Atlas da RBS (M8) tem o prompt dele, {@code rbs.txt}, inteiro e à parte ({@link #comporDaRbs}): fala com a equipe da RBS, não
 * tem marca nem relatório, e só leva o {@code @DATA@}.
 */
public final class PromptsDoAtlas {

    /** A linha de {@code chat.txt} que cada modo preenche. */
    public static final String MARCADOR_DOS_PEDIDOS = "@PEDIDOS_DE_RELATORIO@";

    /** Os dois marcadores que o loop troca a cada turno; todos os outros são erro. */
    private static final Pattern MARCADOR_DO_TURNO = Pattern.compile("@(?:MARCA|DATA)@");
    private static final Pattern MARCADOR = Pattern.compile("@[A-Z_]+@");

    private PromptsDoAtlas() {
    }

    /**
     * O prompt do modo, ainda com {@code @MARCA@} e {@code @DATA@}.
     *
     * @throws IllegalStateException se a base não tem o marcador dos pedidos de relatório, ou se sobrou outro marcador
     */
    public static String compor(boolean relatorioLigado) {
        String base = ler("chat");
        if (!base.contains(MARCADOR_DOS_PEDIDOS)) {
            throw new IllegalStateException("O prompt chat.txt não tem a linha " + MARCADOR_DOS_PEDIDOS + ".");
        }
        String pedidos = ler(relatorioLigado ? "relatorio-ligado" : "relatorio-desligado").stripTrailing();
        String prompt = base.replace(MARCADOR_DOS_PEDIDOS, pedidos);
        if (relatorioLigado) {
            prompt = prompt.stripTrailing() + "\n\n" + ler("relatorio").stripTrailing() + "\n";
        }
        var sobra = MARCADOR.matcher(MARCADOR_DO_TURNO.matcher(prompt).replaceAll(""));
        if (sobra.find()) {
            throw new IllegalStateException("Sobrou o marcador " + sobra.group() + " no prompt do Atlas.");
        }
        return prompt;
    }

    /** O limite do prompt da RBS, em caracteres: ele é escrito à mão para ser curto, e um corte acidental não passa despercebido. */
    public static final int LIMITE_DO_PROMPT_DA_RBS = 6_000;

    /**
     * O prompt do Atlas da RBS, ainda com {@code @DATA@}. Não tem {@code @MARCA@}: a conversa não é de marca nenhuma.
     *
     * @throws IllegalStateException se sobrou outro marcador (inclusive {@code @MARCA@}), ou se passa de {@value #LIMITE_DO_PROMPT_DA_RBS}
     *                               caracteres
     */
    public static String comporDaRbs() {
        String prompt = ler("rbs");
        var sobra = MARCADOR.matcher(prompt.replace("@DATA@", ""));
        if (sobra.find()) {
            throw new IllegalStateException("Sobrou o marcador " + sobra.group() + " no prompt da RBS.");
        }
        if (prompt.length() > LIMITE_DO_PROMPT_DA_RBS) {
            throw new IllegalStateException("O prompt da RBS tem " + prompt.length() + " caracteres; o máximo é "
                    + LIMITE_DO_PROMPT_DA_RBS + ".");
        }
        return prompt;
    }

    private static String ler(String nome) {
        String caminho = "atlas/prompts/" + nome + ".txt";
        try {
            return new String(new ClassPathResource(caminho).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException erro) {
            throw new UncheckedIOException("Não consegui ler o prompt do Atlas (resources/" + caminho + ").", erro);
        }
    }
}
