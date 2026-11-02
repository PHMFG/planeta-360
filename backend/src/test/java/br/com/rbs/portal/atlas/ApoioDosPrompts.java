package br.com.rbs.portal.atlas;

import br.com.rbs.portal.comum.Datas;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;

/**
 * [ATLAS] O que os testes sabem do prompt de sistema, escrito de forma independente do código (M6): lê os arquivos de
 * {@code atlas/prompts} e monta o prompt esperado de cada modo por conta própria, para o teste não repetir o erro da classe
 * {@code PromptsDoAtlas} se ela errar. Não faz parte da API.
 */
final class ApoioDosPrompts {

    static final String MARCADOR = "@PEDIDOS_DE_RELATORIO@";
    /** Qualquer marcador {@code @...@} que sobre no prompt final é erro. */
    static final Pattern MARCADOR_QUE_SOBROU = Pattern.compile("@[A-Z_]+@");

    private ApoioDosPrompts() {
    }

    static String arquivo(String nome) {
        try {
            return new String(new ClassPathResource("atlas/prompts/" + nome + ".txt").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }
    }

    /** O prompt do modo, ainda com {@code @MARCA@} e {@code @DATA@}: a base com o marcador trocado e, ligado, o fim do relatório. */
    static String esperadoSemMarcaNemData(boolean relatorioLigado) {
        String base = arquivo("chat").replace(MARCADOR, arquivo(relatorioLigado ? "relatorio-ligado" : "relatorio-desligado").stripTrailing());
        return relatorioLigado ? base.stripTrailing() + "\n\n" + arquivo("relatorio").stripTrailing() + "\n" : base;
    }

    /** O prompt que o modelo recebe, com o nome da marca e a data de hoje. */
    static String esperado(boolean relatorioLigado, String marca) {
        return esperadoSemMarcaNemData(relatorioLigado).replace("@MARCA@", marca)
                .replace("@DATA@", DateTimeFormatter.ofPattern("dd/MM/yyyy").format(Datas.hoje()));
    }
}
