package br.com.rbs.portal.atlas.relatorio;

import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.ReferenciaDesconhecidaException;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Resolucao;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Valor;
import java.util.Optional;
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
 *   <li>sem HTML, sem Markdown de link ou imagem e sem URL;</li>
 *   <li>sem termo interno fora de marcador: chave crua de referência ({@code dado:}, {@code cons:}, {@code calc:},
 *       {@code lista:}, {@code parc:}, {@code evento:}), nome de ferramenta, as palavras "marcador" e "tool" e "cartão de
 *       solicitação". Quem lê o texto é a marca, e o funcionamento interno não é com ela;</li>
 *   <li>(só chat) todo marcador tem valor agora. No relatório, o bloco com dado que falta sai como indisponível, com o motivo;
 *       numa frase do chat, "indisponível" no lugar do número não faz sentido, então o modelo tem de dizer com palavras.</li>
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
    /** Chave de referência escrita fora de marcador ({@code dado:ativacao.visitantes_total}) ou nome de ferramenta. */
    private static final Pattern CHAVE_INTERNA = Pattern.compile(
            "(?iU)\\b(?:parc|evento|cons|lista|dado|calc):[a-z_]+(?:\\.[a-z0-9_]+)*"
                    + "|\\b(?:relatorio|parceria|buscar|listar|consultar|contar|pedido|gerar|executar)_[a-z_]+");
    /** Palavras do funcionamento interno que a marca não deve ler. */
    private static final Pattern TERMO_INTERNO = Pattern.compile(
            "(?iU)\\bmarcador(?:es)?\\b|\\btool(?:s|_calls?)?\\b|\\bcart[ãa]o de solicita[çc][ãa]o\\b");
    private static final Pattern FIM_DE_FRASE = Pattern.compile("(?<=[.!?…])\\s+(?=\\S)");

    /** O resultado: aprovado, com o texto limpo (espaços normalizados), ou recusado, com o motivo. */
    public record Veredito(boolean aprovado, String texto, String motivo) {

        public static Veredito aprovado(String texto) {
            return new Veredito(true, texto, null);
        }

        public static Veredito recusado(String motivo) {
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

        Optional<String> deForma = motivoDeForma(texto, semMarcadores);
        if (deForma.isPresent()) {
            return Veredito.recusado(deForma.get());
        }
        Optional<String> deMarcador = motivoDeMarcador(resolucao, texto, limitarTamanho);
        if (deMarcador.isPresent()) {
            return Veredito.recusado(deMarcador.get());
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

    /**
     * As regras de forma e de termo interno: sem HTML, sem link nem imagem em Markdown, sem URL, sem chave crua de referência nem
     * nome de ferramenta, sem as palavras do funcionamento interno. {@code texto} é o texto com os espaços normalizados e
     * {@code semMarcadores} o mesmo sem os marcadores {@code {{ref}}} (e, no chat de fontes, sem as citações). Devolve o motivo da
     * recusa, escrito para o modelo ler. É a regra do relatório e a do chat de fontes: um lugar só.
     */
    public static Optional<String> motivoDeForma(String texto, String semMarcadores) {
        if (texto.indexOf('<') >= 0 || texto.indexOf('>') >= 0) {
            return Optional.of("Sem HTML: não use os sinais < e >. Escreva só texto simples.");
        }
        if (LINK_OU_IMAGEM_DO_MARKDOWN.matcher(semMarcadores).find()) {
            return Optional.of("Sem link nem imagem em Markdown. Escreva só texto simples.");
        }
        if (URL.matcher(semMarcadores).find() || ENDERECO_SEM_ESQUEMA.matcher(semMarcadores).find()) {
            return Optional.of("Sem endereço de internet nem URL no texto.");
        }
        Matcher chave = CHAVE_INTERNA.matcher(semMarcadores);
        if (chave.find()) {
            return Optional.of("O texto tem o termo interno \"" + chave.group() + "\" fora de um marcador. A marca não lê "
                    + "chaves nem nomes de ferramenta: cite o dado com o marcador {{ref}}, entre chaves duplas, ou diga com palavras.");
        }
        Matcher termo = TERMO_INTERNO.matcher(semMarcadores);
        if (termo.find()) {
            return Optional.of("O texto usa o termo interno \"" + termo.group() + "\". Não fale com a marca de marcador, "
                    + "referência, ferramenta nem cartão de solicitação: diga o que ela precisa saber com palavras simples.");
        }
        return Optional.empty();
    }

    /**
     * Todo marcador {@code {{ref}}} do texto resolve nesta parceria (a resolução usa sempre a parceria da conversa, nunca a do
     * texto). No chat ({@code limitarTamanho} falso), o marcador também precisa ter valor agora; no parágrafo do relatório o bloco com
     * dado que falta sai como indisponível, com o motivo.
     */
    public static Optional<String> motivoDeMarcador(Resolucao resolucao, String texto, boolean limitarTamanho) {
        for (String ref : Referencias.marcadores(texto)) {
            Valor valor;
            try {
                valor = resolucao.valor(ref);
            } catch (ReferenciaDesconhecidaException desconhecida) {
                return Optional.of("O marcador {{" + ref + "}} não existe nesta parceria. Use só as referências (campo ref) "
                        + "que as ferramentas devolveram.");
            }
            if (!limitarTamanho && !valor.disponivel()) {
                return Optional.of("O dado {{" + ref + "}} (" + valor.rotulo() + ") não tem valor agora: " + valor.motivo()
                        + " Não use o marcador dele; diga com palavras que o dado ainda não existe.");
            }
        }
        return Optional.empty();
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
