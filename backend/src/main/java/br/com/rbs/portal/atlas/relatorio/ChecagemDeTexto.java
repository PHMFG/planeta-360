package br.com.rbs.portal.atlas.relatorio;

import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.ReferenciaDesconhecidaException;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Resolucao;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * A checagem do texto que o modelo de linguagem escreve (plano, seção 10.6). [ATLAS] [VALIDACAO] O Atlas redige, mas nunca escreve
 * um número: onde o texto precisa de um, ele usa um marcador {@code {{ref}}} e o servidor preenche. Esta é a barreira que
 * garante isso, no texto de um parágrafo do relatório e no texto do chat:
 * <ol>
 *   <li>tira os marcadores e recusa <b>qualquer algarismo</b> que sobrar (todo dígito Unicode, também os de outros alfabetos,
 *       fração e expoente; número por extenso escapa da regra, e o prompt o proíbe);</li>
 *   <li>todo marcador tem de resolver nesta parceria ({@link Resolucao#valor}): referência inventada, de campo fora do
 *       catálogo, de cálculo que o modelo não define ou de lista é recusada;</li>
 *   <li>(só parágrafo do relatório) até {@value #MAXIMO_DE_FRASES} frases e {@value #MAXIMO_DE_CARACTERES} caracteres;</li>
 *   <li>sem HTML, sem Markdown de link ou imagem e sem URL.</li>
 * </ol>
 * A quinta regra do plano (o bloco existe e é redigível) é do {@link RelatorioAtlasService}, que conhece o relatório. A checagem
 * não grava nada: devolve o texto limpo (espaços normalizados) ou o motivo da recusa, escrito para o modelo ler e corrigir.
 */
@Component
public class ChecagemDeTexto {

    public static final int MAXIMO_DE_FRASES = 3;
    public static final int MAXIMO_DE_CARACTERES = 600;

    private static final Pattern ALGARISMO = Pattern.compile("\\p{N}");
    private static final Pattern LINK_OU_IMAGEM_DO_MARKDOWN = Pattern.compile("!\\[|\\]\\s*\\(|^\\s*\\[[^\\]]*]:\\s*\\S", Pattern.MULTILINE);
    private static final Pattern URL = Pattern.compile("(?i)(?:\\b(?:https?|ftp|file|javascript|mailto)\\s*:|://|\\bdata:[a-z]+/|\\bwww\\.)");
    private static final Pattern ENDERECO_SEM_ESQUEMA = Pattern.compile(
            "(?i)\\b[a-z0-9][a-z0-9-]*(?:\\.[a-z0-9-]+)*\\.(?:com|org|net|edu|gov|io|dev|app|br|co|me|info|biz|xyz|ly|ai)\\b");
    private static final Pattern FIM_DE_FRASE = Pattern.compile("(?<=[.!?…])\\s+(?=\\S)");

    /** O resultado: aprovado, com o texto limpo (espaços normalizados), ou recusado, com o motivo. */
    public record Veredito(boolean aprovado, String texto, String motivo) {

        static Veredito aprovado(String texto) {
            return new Veredito(true, texto, null);
        }

        static Veredito recusado(String motivo) {
            return new Veredito(false, null, motivo);
        }
    }

    /** O texto de um parágrafo do relatório: as quatro regras. */
    public Veredito paragrafo(Resolucao resolucao, String texto) {
        return checar(resolucao, texto, true);
    }

    /** O texto do chat: as mesmas regras, menos o limite de frases e de caracteres (a resposta do chat não é um parágrafo). */
    public Veredito chat(Resolucao resolucao, String texto) {
        return checar(resolucao, texto, false);
    }

    private Veredito checar(Resolucao resolucao, String original, boolean limitarTamanho) {
        if (original == null || original.isBlank()) {
            return Veredito.recusado("O texto está vazio.");
        }
        String texto = original.strip().replaceAll("\\s+", " ");
        String semMarcadores = Referencias.semMarcadores(texto);   // o que o modelo escreveu por conta própria

        if (texto.indexOf('<') >= 0 || texto.indexOf('>') >= 0) {
            return Veredito.recusado("Sem HTML: não use os sinais < e >. Escreva só texto simples.");
        }
        if (LINK_OU_IMAGEM_DO_MARKDOWN.matcher(semMarcadores).find()) {
            return Veredito.recusado("Sem link nem imagem em Markdown. Escreva só texto simples.");
        }
        if (URL.matcher(semMarcadores).find() || ENDERECO_SEM_ESQUEMA.matcher(semMarcadores).find()) {
            return Veredito.recusado("Sem endereço de internet nem URL no texto.");
        }

        // Regra 2: todo marcador resolve nesta parceria. A resolução usa sempre a parceria da conversa, nunca a do texto.
        for (String ref : Referencias.marcadores(texto)) {
            try {
                resolucao.valor(ref);
            } catch (ReferenciaDesconhecidaException desconhecida) {
                return Veredito.recusado("O marcador {{" + ref + "}} não existe nesta parceria. Use só as referências (campo ref) "
                        + "que as ferramentas devolveram.");
            }
        }

        // Regra 1: sem os marcadores, não pode sobrar algarismo (nem chave solta de marcador mal fechado).
        Matcher algarismo = ALGARISMO.matcher(semMarcadores);
        if (algarismo.find()) {
            return Veredito.recusado("O texto tem o algarismo \"" + algarismo.group() + "\" fora de um marcador. Não escreva número, "
                    + "nem por extenso: cite o dado com o marcador {{ref}}, que o servidor preenche.");
        }
        if (semMarcadores.indexOf('{') >= 0 || semMarcadores.indexOf('}') >= 0) {
            return Veredito.recusado("Há uma chave { ou } solta no texto. Marcador se escreve {{ref}}, com duas chaves de cada lado.");
        }

        if (limitarTamanho) {
            if (texto.length() > MAXIMO_DE_CARACTERES) {
                return Veredito.recusado("O texto tem " + texto.length() + " caracteres; o máximo é " + MAXIMO_DE_CARACTERES + ".");
            }
            int frases = contarFrases(texto);
            if (frases > MAXIMO_DE_FRASES) {
                return Veredito.recusado("O texto tem " + frases + " frases; o máximo é " + MAXIMO_DE_FRASES + ".");
            }
        }
        return Veredito.aprovado(texto);
    }

    /** As frases do texto, contadas pelo fim de frase seguido de espaço. O marcador vale uma palavra, para o ponto dele não contar. */
    static int contarFrases(String texto) {
        String comPalavras = Referencias.MARCADOR.matcher(texto).replaceAll("X").strip();
        if (comPalavras.isEmpty()) {
            return 0;
        }
        return FIM_DE_FRASE.split(comPalavras).length;
    }
}
