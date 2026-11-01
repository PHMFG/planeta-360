package br.com.rbs.portal.atlas.relatorio;

import br.com.rbs.portal.atlas.checagem.NormalizadorDeNumeros;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.ReferenciaDesconhecidaException;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Resolucao;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Valor;
import java.text.Normalizer;
import java.util.List;
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
 *       fração e expoente) e, só no parágrafo do relatório (M6), o número e a data por extenso (o chat de fontes tem a regra
 *       própria da {@code ChecagemDeFontes}, que aceita o que vem da fonte citada);</li>
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
    /** O endereço sem esquema ({@code //coleta.click/x}) e o que esconde o ponto ({@code coleta-atlas[.]com}, {@code coleta-atlas(.)com}). */
    private static final Pattern ENDERECO_ESCONDIDO = Pattern.compile("//[\\p{L}\\p{N}-]|\\[\\.\\]|\\(\\.\\)");
    /**
     * O endereço sem esquema, pela <b>união de duas regras</b>: (1) um domínio de topo conhecido ({@code com}, {@code org}, {@code net},
     * {@code edu}, {@code gov}, {@code io}, {@code dev}, {@code app}, {@code br}, {@code co}, {@code me}, {@code info}, {@code biz},
     * {@code xyz}, {@code ly}, {@code ai}) depois de um rótulo de <i>qualquer</i> tamanho ({@code x.com/coleta}, {@code t.co/abc},
     * {@code coleta@x.io}, {@code a.b.com}); (2) qualquer {@code rótulo.rótulo} com dois rótulos de 2 ou mais caracteres e o último só
     * de letras ({@code coleta-atlas.invalid}, {@code coleta_atlas.invalid}, {@code site.link}, {@code exemplo.com.br}), porque
     * nenhuma lista cobre os domínios que um documento pode mandar escrever. O {@code _} conta como caractere de rótulo. Não pega
     * {@code 3.2}, {@code 1.250,00}, {@code 04/12/2026}, {@code v2.0}, {@code p.ex.}, {@code R.G.}, {@code S.A.}, {@code a.m.} nem
     * {@code e.g.} (rótulo de uma letra fora da lista de domínios, ou último rótulo com algarismo). Pega também {@code prazo.Depois},
     * a frase sem o espaço depois do ponto: a regra não distingue de um domínio, e o modelo reescreve com o espaço.
     */
    private static final Pattern ENDERECO_SEM_ESQUEMA = Pattern.compile(
            "(?<![\\p{L}\\p{N}_-])[\\p{L}\\p{N}_-]{2,}\\.\\p{L}{2,}(?![\\p{L}\\p{N}_-])");
    private static final Pattern ENDERECO_DE_DOMINIO_DE_TOPO = Pattern.compile(
            "(?i)\\b[a-z0-9_][a-z0-9_-]*(?:\\.[a-z0-9_-]+)*\\.(?:com|org|net|edu|gov|io|dev|app|br|co|me|info|biz|xyz|ly|ai)\\b");
    /** O ponto de outras escritas (largura total, ideográfico, ideográfico de meia largura) vale como ponto na conferência do endereço. */
    private static final Pattern PONTO_DE_OUTRA_ESCRITA = Pattern.compile("[\\uFF0E\\u3002\\uFF61]");
    /** Chave de referência escrita fora de marcador ({@code dado:ativacao.visitantes_total}) ou nome de ferramenta. */
    private static final Pattern CHAVE_INTERNA = Pattern.compile(
            "(?iU)\\b(?:parc|evento|cons|lista|dado|calc):[a-z_]+(?:\\.[a-z0-9_]+)*"
                    + "|\\b(?:relatorio|parceria|buscar|listar|consultar|contar|pedido|gerar|executar)_[a-z_]+");
    /** Palavras do funcionamento interno que a marca não deve ler. */
    private static final Pattern TERMO_INTERNO = Pattern.compile(
            "(?iU)\\bmarcador(?:es)?\\b|\\btool(?:s|_calls?)?\\b|\\bcart[ãa]o de solicita[çc][ãa]o\\b");
    private static final String SEM_ENDERECO = "Sem endereço de internet nem URL no texto.";
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

    /**
     * A mensagem do cartão de perguntas ({@code relatorio_solicitar_campos}), M6: a regra do parágrafo do relatório, sem o limite de
     * frases (o cartão tem o dele, de 400 caracteres, conferido por quem chama) e <b>sem marcador</b>. Nenhum algarismo, nenhum número
     * nem data por extenso, nenhum HTML, link ou endereço de internet, nenhuma chave crua ({@code dado:...}) nem nome de ferramenta:
     * antes só o tamanho era conferido, e um texto com número, link e chave chegava inteiro à tela. O cartão mostra a mensagem como
     * está, então um {@code {{ref}}} apareceria cru e também é recusado. A mensagem fixa do botão e as frases comuns passam, e
     * "Faltam dois números" também: "dois" sem unidade não é número. O motivo da recusa é escrito para o modelo ler e reescrever.
     */
    public Veredito mensagemDoCartao(String original) {
        if (original == null || original.isBlank()) {
            return Veredito.recusado("A mensagem do cartão está vazia.");
        }
        String texto = original.strip().replaceAll("\\s+", " ");
        if (Referencias.MARCADOR.matcher(texto).find()) {
            return Veredito.recusado("A mensagem do cartão não usa marcador {{ref}}: o cartão mostra o texto como está. Diga com "
                    + "palavras o que você precisa, sem citar valor.");
        }
        Optional<String> deForma = motivoDeForma(texto, texto);
        if (deForma.isPresent()) {
            return Veredito.recusado(deForma.get());
        }
        Matcher algarismo = ALGARISMO.matcher(texto);
        if (algarismo.find()) {
            return Veredito.recusado("A mensagem do cartão tem o algarismo \"" + algarismo.group() + "\". Não escreva número, nem por "
                    + "extenso: diga com palavras o que você precisa, sem citar valor.");
        }
        if (texto.indexOf('{') >= 0 || texto.indexOf('}') >= 0) {
            return Veredito.recusado("Há uma chave { ou } solta na mensagem do cartão. Escreva só texto simples.");
        }
        List<NormalizadorDeNumeros.Achado> porExtenso = NormalizadorDeNumeros.extrair(texto, false);
        if (!porExtenso.isEmpty()) {
            return Veredito.recusado("A mensagem do cartão tem número ou data por extenso (\"" + porExtenso.get(0).trecho().strip()
                    + "\"). Não escreva número nem data, nem por extenso: diga com palavras o que você precisa, sem citar valor.");
        }
        return Veredito.aprovado(texto);
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
            // M6: no texto do relatório o número e a data por extenso também são recusados ("dezessete", "vinte e três brindes",
            // "primeiro de dezembro"); o chat de fontes tem a própria regra (ChecagemDeFontes) e aceita o que vem da fonte
            List<NormalizadorDeNumeros.Achado> porExtenso = NormalizadorDeNumeros.extrair(semMarcadores, false);
            if (!porExtenso.isEmpty()) {
                return Veredito.recusado("O texto tem número ou data por extenso (\"" + porExtenso.get(0).trecho().strip() + "\"). Não "
                        + "escreva número nem data, nem por extenso: cite o dado com o marcador {{ref}}, que o servidor preenche.");
            }
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
     * <p>
     * Olha o texto como está e, se o NFKC o muda, também o normalizado: o ponto de preenchimento U+2024, o ponto pequeno U+FE52, os
     * dois-pontos largos U+FF1A e as letras e os sinais de largura total ({@code ｊａｖａｓｃｒｉｐｔ：}, {@code ＜b＞}) viram o caractere comum
     * e não escondem mais o endereço, o esquema ou o HTML. A normalização serve só para conferir: o texto que sai para a pessoa não
     * muda. O texto comum que o NFKC muda ({@code m²}, {@code ½}, {@code º}, {@code …}) não tem forma de endereço e passa. A regra dos
     * números não olha o NFKC (é outra conferência, em outro lugar).
     */
    public static Optional<String> motivoDeForma(String texto, String semMarcadores) {
        Optional<String> doTexto = motivoDeFormaDoTexto(texto, semMarcadores);
        if (doTexto.isPresent()) {
            return doTexto;
        }
        String textoNfkc = Normalizer.normalize(texto, Normalizer.Form.NFKC);
        String semMarcadoresNfkc = Normalizer.normalize(semMarcadores, Normalizer.Form.NFKC);
        if (textoNfkc.equals(texto) && semMarcadoresNfkc.equals(semMarcadores)) {
            return Optional.empty();   // o NFKC não mudou nada: não há o que conferir de novo
        }
        return motivoDeFormaDoTexto(textoNfkc, semMarcadoresNfkc);
    }

    private static Optional<String> motivoDeFormaDoTexto(String texto, String semMarcadores) {
        if (texto.indexOf('<') >= 0 || texto.indexOf('>') >= 0) {
            return Optional.of("Sem HTML: não use os sinais < e >. Escreva só texto simples.");
        }
        if (LINK_OU_IMAGEM_DO_MARKDOWN.matcher(semMarcadores).find()) {
            return Optional.of("Sem link nem imagem em Markdown. Escreva só texto simples.");
        }
        String paraEndereco = PONTO_DE_OUTRA_ESCRITA.matcher(semMarcadores).replaceAll(".");
        if (URL.matcher(paraEndereco).find() || ENDERECO_ESCONDIDO.matcher(paraEndereco).find()) {
            return Optional.of(SEM_ENDERECO);
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
        // por último: "cons:aplicacoes.comprovadas" também tem a forma rótulo.rótulo, e a recusa certa para ele é a de chave crua
        if (ENDERECO_SEM_ESQUEMA.matcher(paraEndereco).find() || ENDERECO_DE_DOMINIO_DE_TOPO.matcher(paraEndereco).find()) {
            return Optional.of(SEM_ENDERECO);
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
