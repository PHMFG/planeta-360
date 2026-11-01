package br.com.rbs.portal.documentos;

import br.com.rbs.portal.documentos.ExtratorDeTexto.Linha;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Pagina;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Corta o texto de um documento em trechos para a busca do Atlas (I4 da ingestão, M3 do plano do Atlas completo). [ATLAS] Sem rede,
 * sem banco e sem estado: é uma conta sobre as páginas que o {@link ExtratorDeTexto} entregou. As regras:
 * <ul>
 *   <li>antes de cortar, tira as linhas iguais no topo e no pé de 2 ou mais páginas (cabeçalho e rodapé; topo com topo e pé com pé,
 *       letra por letra, e só a numeração de página fica de fora da comparação), que sujariam os trechos e partiriam a frase na
 *       quebra de página. A linha repetida que tem <b>qualquer algarismo</b> fora da numeração ({@code Cortesias 40 por cota},
 *       {@code Prazo 15-01-2027}) fica uma vez, na primeira página. Número solto ou {@code n/N} só é numeração se acompanhar a página.
 *       Um documento de uma página só (o TXT) não tem cabeçalho nem rodapé: nada sai dele;</li>
 *   <li>corta por seção e título: numeração ({@code 3.2 Tarifa de energia}) ou linha em caixa alta; cada trecho leva o caminho da
 *       seção ({@code 3 Energia › 3.2 Tarifa}) e as páginas onde começa e termina. <b>Nenhuma linha some</b>: numa sequência de
 *       linhas com cara de título só a última é o título, com os ancestrais de numeração prefixo da dela; as outras ficam no texto.
 *       A numeração de um nível só ({@code 4.}) tem de seguir a sequência dos títulos aceitos. Título com data, {@code R$}, {@code %}
 *       ou número com separador é só texto, e o que tem algarismo além da numeração fica no caminho <b>e</b> no texto;</li>
 *   <li>tabela inteira num trecho: a sequência de linhas com colunas alinhadas que o extrator marcou nunca é cortada (acima de
 *       {@value #LIMITE_DE_TOKENS} tokens ela se parte nas linhas, repetindo a legenda e o cabeçalho);</li>
 *   <li>de {@value #MINIMO_DE_TOKENS} a {@value #ALVO_DE_TOKENS} tokens por trecho (estimativa de 4 caracteres por token) e no máximo
 *       {@value #LIMITE_DE_TOKENS}, <b>contando os separadores</b> e as linhas de título que entram no texto. O Gemini corta <b>em
 *       silêncio</b> o que passa de 8.192 tokens, então o limite fica longe disso. Seções pequenas se juntam (com o título delas dentro
 *       do texto); a seção grande se parte nas frases;</li>
 *   <li>uma frase de sobreposição entre dois trechos da mesma seção partida; nunca entre seções, e só se o trecho ainda couber nos
 *       {@value #LIMITE_DE_TOKENS} tokens com ela.</li>
 * </ul>
 * A frase que cruza a quebra de página fica inteira no mesmo trecho: o corte só acontece entre frases. A palavra partida por hífen na
 * quebra de linha ({@code sexta-} e {@code feira}) volta inteira, com o hífen.
 */
@Component
public class DivisorDeTrechos {

    public static final int MINIMO_DE_TOKENS = 300;
    public static final int ALVO_DE_TOKENS = 700;
    public static final int LIMITE_DE_TOKENS = 1500;
    static final int CARACTERES_POR_TOKEN = 4;
    private static final String SEPARADOR_DO_CAMINHO = " › ";

    /** Um trecho pronto para os embeddings. {@code secaoCaminho} é nulo quando o documento não tem título nenhum antes dele. */
    public record Trecho(int ordem, int paginaInicio, int paginaFim, String secaoCaminho, String texto, int tokens) {
    }

    /** A estimativa de tokens: 4 caracteres por token, arredondada para cima. */
    public static int estimarTokens(String texto) {
        return (texto.length() + CARACTERES_POR_TOKEN - 1) / CARACTERES_POR_TOKEN;
    }

    // ---------------------------------------------------------------------
    // Estruturas internas
    // ---------------------------------------------------------------------

    private record LinhaDoDocumento(String texto, int pagina, boolean tabela) {
    }

    /**
     * O título que uma linha é. {@code numeracao} é a lista de números ({@code 3.2} dá {@code [3, 2]}), ou nula no título sem
     * numeração (caixa alta, "Sumário"); {@code comAlgarismo}, se tem algarismo além da numeração.
     */
    record Titulo(int nivel, String rotulo, List<Integer> numeracao, boolean comAlgarismo) {

        boolean umNivelSo() {
            return numeracao != null && numeracao.size() == 1;
        }
    }

    /** A linha com cara de título, e se carrega dado (data, valor em reais, percentual, número com separador): aí é só texto. */
    private record Forma(Titulo titulo, boolean comDado) {
    }

    /** Um pedaço que não se parte: uma frase ou uma tabela. {@code antes} é o espaço que o separava do pedaço anterior. */
    private record Atomo(String texto, String antes, int paginaInicio, int paginaFim, boolean tabela) {

        int tokens() {
            return estimarTokens(texto);
        }
    }

    private static final class Secao {
        final List<String> caminho;
        final List<Atomo> atomos = new ArrayList<>();

        Secao(List<String> caminho) {
            this.caminho = caminho;
        }

        /** Quantos tokens cabem num pedaço desta seção: o limite menos as linhas de título que o trecho leva no começo do texto. */
        int limiteUtil() {
            return LIMITE_DE_TOKENS - estimarTokens(cabecalhoDe(caminho));
        }
    }

    /** Parte de uma seção que vira (sozinha ou com vizinhas) um trecho. */
    private record Unidade(List<String> caminho, List<Atomo> atomos) {

        int tokens() {
            return atomos.stream().mapToInt(Atomo::tokens).sum();
        }
    }

    // ---------------------------------------------------------------------
    // Entrada
    // ---------------------------------------------------------------------

    public List<Trecho> dividir(List<Pagina> paginas) {
        List<LinhaDoDocumento> linhas = semCabecalhoERodape(paginas);
        List<Secao> secoes = secoes(planejar(linhas));
        List<Unidade> unidades = new ArrayList<>();
        for (Secao secao : secoes) {
            unidades.addAll(unidadesDa(secao));
        }
        return trechos(unidades);
    }

    // ---------------------------------------------------------------------
    // Cabeçalho e rodapé repetidos
    // ---------------------------------------------------------------------

    private static final int JANELA_DA_BORDA = 3;
    /** A numeração solta ({@code 3}, {@code 3/11}) só vale se o número menos a página for constante e ficar entre -5 e 5. */
    private static final int DESVIO_MAXIMO_DA_NUMERACAO = 5;

    /** As linhas que sobram depois de tirar o cabeçalho e o rodapé repetidos: é o que os testes comparam com os trechos. */
    static List<String> linhasMantidas(List<Pagina> paginas) {
        return semCabecalhoERodape(paginas).stream().map(LinhaDoDocumento::texto).toList();
    }

    /** A numeração de página no fim da linha ("... Página 3 de 11", "Pág. 3", "Page 3 of 4"). */
    private static final Pattern NUMERACAO_NO_FIM = Pattern.compile(
            "\\b(?:p[aá]gina|p[aá]g\\.?|page|p\\.)\\s*\\d+(?:\\s*(?:de|of|/)\\s*\\d+)?\\s*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** A numeração de página no começo da linha ("Página 3 de 11 · Manual"). */
    private static final Pattern NUMERACAO_NO_INICIO = Pattern.compile(
            "^\\s*(?:p[aá]gina|p[aá]g\\.?|page|p\\.)\\s*\\d+(?:\\s*(?:de|of|/)\\s*\\d+)?(?!\\d)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** A linha que é só um número ("3", "3/11", "3 de 11", "- 3 -"): o grupo 1 é o número e o 3, o total, se houver. */
    private static final Pattern SO_UM_NUMERO = Pattern.compile(
            "^[\\s\\-–—|•·]*(\\d{1,4})(?:\\s*(/|de|of)\\s*(\\d{1,4}))?[\\s\\-–—|•·]*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Como a numeração solta de um documento se comporta: quanto o número passa da página, e o total, se vier ({@code 3/11}). */
    private record Assinatura(int desvio, Integer total) {
    }

    /**
     * Tira o cabeçalho e o rodapé: a linha que se repete na borda (as 3 primeiras ou as 3 últimas) de pelo menos metade das páginas, e
     * de 2 no mínimo. O topo só se compara com o topo, e o pé com o pé (o pé de uma página e o topo da seguinte não se repetem). A
     * linha tem de ser igual, letra por letra, e só a numeração de página fica de fora da comparação: "Página 3 de 11" e "Pág. 3" sempre,
     * e o número solto ou {@code n/N} só se acompanhar a página (o número menos a página constante, entre -5 e 5, e o total constante).
     * "Tarifa por kVA: R$ 312,00" e "Tarifa por kVA: R$ 450,00" são duas linhas, e nenhuma sai. A linha repetida que tem <b>qualquer
     * algarismo</b> fora da numeração ({@code Cortesias 40 por cota}, {@code 1800}, {@code 40/20}) sai das repetições, mas fica uma vez,
     * onde apareceu primeiro. Linha de tabela nunca sai. Um documento de uma página só (o TXT) não tem repetição possível: nada sai.
     */
    private static List<LinhaDoDocumento> semCabecalhoERodape(List<Pagina> paginas) {
        Assinatura numeracao = assinaturaDaNumeracao(paginas);
        Map<String, Integer> noTopo = new HashMap<>();
        Map<String, Integer> noPe = new HashMap<>();
        for (Pagina pagina : paginas) {
            Set<String> chavesDoTopo = new HashSet<>();
            Set<String> chavesDoPe = new HashSet<>();
            List<Linha> linhas = pagina.linhas();
            for (int i = 0; i < linhas.size(); i++) {
                if (!podeSerCabecalho(linhas.get(i))) {
                    continue;
                }
                String chave = chaveDaBorda(linhas.get(i).texto(), pagina.numero(), numeracao);
                if (i < JANELA_DA_BORDA) {
                    chavesDoTopo.add(chave);
                }
                if (i >= linhas.size() - JANELA_DA_BORDA) {
                    chavesDoPe.add(chave);
                }
            }
            chavesDoTopo.forEach(chave -> noTopo.merge(chave, 1, Integer::sum));
            chavesDoPe.forEach(chave -> noPe.merge(chave, 1, Integer::sum));
        }
        int minimo = Math.max(2, (paginas.size() + 1) / 2);
        Set<String> jaFicouUma = new HashSet<>();
        List<LinhaDoDocumento> resultado = new ArrayList<>();
        for (Pagina pagina : paginas) {
            List<Linha> linhas = pagina.linhas();
            for (int i = 0; i < linhas.size(); i++) {
                Linha linha = linhas.get(i);
                boolean repetida = false;
                if (podeSerCabecalho(linha)) {
                    String chave = chaveDaBorda(linha.texto(), pagina.numero(), numeracao);
                    repetida = (i < JANELA_DA_BORDA && noTopo.getOrDefault(chave, 0) >= minimo)
                            || (i >= linhas.size() - JANELA_DA_BORDA && noPe.getOrDefault(chave, 0) >= minimo);
                    if (repetida && temAlgarismo(chave) && jaFicouUma.add(chave)) {
                        repetida = false;   // a primeira ocorrência de uma linha com algarismo fica: o dado não pode sumir do índice
                    }
                }
                if (!repetida) {
                    resultado.add(new LinhaDoDocumento(linha.texto(), pagina.numero(), linha.tabela()));
                }
            }
        }
        return resultado;
    }

    private static boolean podeSerCabecalho(Linha linha) {
        return !linha.tabela() && !linha.texto().isBlank();
    }

    private static boolean temAlgarismo(String texto) {
        return texto.chars().anyMatch(Character::isDigit);
    }

    /**
     * A assinatura da numeração solta do documento: entre as linhas de borda que são só um número, a combinação (número menos página,
     * total) que mais páginas tem, desde que o desvio fique entre -5 e 5. Sem nenhuma, nulo: nenhum número solto é numeração.
     */
    private static Assinatura assinaturaDaNumeracao(List<Pagina> paginas) {
        Map<Assinatura, Set<Integer>> porAssinatura = new HashMap<>();
        for (Pagina pagina : paginas) {
            List<Linha> linhas = pagina.linhas();
            for (int i = 0; i < linhas.size(); i++) {
                boolean naBorda = i < JANELA_DA_BORDA || i >= linhas.size() - JANELA_DA_BORDA;
                if (!naBorda || !podeSerCabecalho(linhas.get(i))) {
                    continue;
                }
                Matcher numero = SO_UM_NUMERO.matcher(linhas.get(i).texto().strip().replaceAll("\\s+", " "));
                if (numero.matches()) {
                    int desvio = Integer.parseInt(numero.group(1)) - pagina.numero();
                    if (Math.abs(desvio) <= DESVIO_MAXIMO_DA_NUMERACAO) {
                        Integer total = numero.group(3) == null ? null : Integer.valueOf(numero.group(3));
                        porAssinatura.computeIfAbsent(new Assinatura(desvio, total), chave -> new HashSet<>()).add(pagina.numero());
                    }
                }
            }
        }
        return porAssinatura.entrySet().stream()
                .max(Comparator.<Map.Entry<Assinatura, Set<Integer>>>comparingInt(e -> e.getValue().size())
                        .thenComparingInt(e -> -Math.abs(e.getKey().desvio())).thenComparing(e -> e.getKey().total() == null ? -1 : -e.getKey().total()))
                .map(Map.Entry::getKey).orElse(null);
    }

    /**
     * A linha como o cabeçalho a compara: com os espaços unidos e a numeração de página trocada por "#". O resto fica como está, letra por
     * letra. O número solto só vira "#" se bater com a assinatura da numeração do documento; senão é um dado como outro qualquer.
     */
    private static String chaveDaBorda(String texto, int pagina, Assinatura numeracao) {
        String linha = texto.strip().replaceAll("\\s+", " ");
        Matcher numero = SO_UM_NUMERO.matcher(linha);
        if (numero.matches()) {
            int desvio = Integer.parseInt(numero.group(1)) - pagina;
            Integer total = numero.group(3) == null ? null : Integer.valueOf(numero.group(3));
            return numeracao != null && numeracao.equals(new Assinatura(desvio, total)) ? "#" : linha;
        }
        linha = NUMERACAO_NO_FIM.matcher(linha).replaceFirst("#");
        return NUMERACAO_NO_INICIO.matcher(linha).replaceFirst("#");
    }

    // ---------------------------------------------------------------------
    // Seções e títulos
    // ---------------------------------------------------------------------

    private static final Pattern TITULO_NUMERADO = Pattern.compile("^(\\d{1,2}(?:\\.\\d{1,2})*)\\.?\\s+(\\p{Lu}.*)$");
    private static final Pattern TITULO_EM_CAIXA_ALTA = Pattern.compile("^[\\p{Lu}\\d \\t·•\\-–—:&/(),.'’\"“”%]+$");
    private static final Pattern TITULO_SOLTO = Pattern.compile("(?i)^(sumário|índice)$");
    private static final Pattern NUMERACAO_DO_ROTULO = Pattern.compile("^\\d{1,2}(?:\\.\\d{1,2})*\\s+(.*)$");
    private static final int TAMANHO_MAXIMO_DE_TITULO = 110;

    /**
     * O título que a linha é, ou nulo se for texto: numerado ou em caixa alta, curto, sem pontuação de frase no fim e <b>sem dado</b>
     * ({@code 1.1 Tarifa por kVA: R$ 312,00}, {@code PRAZO FINAL 15/01/2027} são texto). Se a linha tem algarismo além da numeração, o
     * título fica no caminho e também no texto do trecho.
     */
    static Titulo titulo(String texto) {
        Forma forma = forma(texto);
        return forma == null || forma.comDado() ? null : forma.titulo();
    }

    /** A linha com cara de título (com ou sem dado), ou nulo. */
    private static Forma forma(String texto) {
        String linha = texto.strip();
        if (linha.isEmpty() || linha.length() > TAMANHO_MAXIMO_DE_TITULO) {
            return null;
        }
        char ultimo = linha.charAt(linha.length() - 1);
        if (ultimo == ',' || ultimo == ';' || ultimo == ':') {
            return null;
        }
        Matcher numerado = TITULO_NUMERADO.matcher(linha);
        if (numerado.matches() && ultimo != '.') {
            String numero = numerado.group(1);
            String resto = numerado.group(2).strip();
            List<Integer> numeracao = Arrays.stream(numero.split("\\.")).map(Integer::valueOf).toList();
            return new Forma(new Titulo(numeracao.size(), numero + " " + resto, numeracao, temAlgarismo(resto)), temDado(resto));
        }
        if (TITULO_SOLTO.matcher(linha).matches()) {
            return new Forma(new Titulo(1, linha, null, false), false);
        }
        long letras = linha.chars().filter(Character::isLetter).count();
        if (letras >= 4 && TITULO_EM_CAIXA_ALTA.matcher(linha).matches() && ultimo != '.') {
            return new Forma(new Titulo(1, linha, null, temAlgarismo(linha)), temDado(linha));
        }
        return null;
    }

    /**
     * A linha que carrega dado ({@code PRAZO FINAL 15/01/2027}, {@code MASTER 40 20}, {@code CORTESIAS 25%}, {@code Apólice de R$ 2.000.000,00})
     * é linha de texto, e não título: tem valor em reais, data, número com separador ou percentual, ou dois números soltos ou mais. Um
     * número solto só ({@code PLANETA 360 · OPERAÇÕES DO FESTIVAL}, {@code ... ATLÂNTIDA 2027}) não desfaz o título.
     */
    private static boolean temDado(String linha) {
        if (linha.contains("R$")) {
            return true;
        }
        int soltos = 0;
        for (String pedaco : linha.split("\\s+")) {
            if (pedaco.matches("\\d+")) {
                soltos++;
            } else if (pedaco.matches(".*\\d.*") && (pedaco.matches(".*[/,.:%].*") || pedaco.matches(".*\\d[-–]\\d.*"))) {
                return true;
            }
        }
        return soltos >= 2;
    }

    /** Se o rótulo de um título tem algarismo além da numeração do começo ({@code 3.2 Tarifa} não tem; {@code 4 Energia 220 V} tem). */
    private static boolean rotuloTemAlgarismo(String rotulo) {
        Matcher numeracao = NUMERACAO_DO_ROTULO.matcher(rotulo);
        return temAlgarismo(numeracao.matches() ? numeracao.group(1) : rotulo);
    }

    /** As linhas de título do caminho que têm algarismo, uma por linha e com a quebra no fim: entram no começo do texto do trecho. */
    private static String cabecalhoDe(List<String> caminho) {
        StringBuilder cabecalho = new StringBuilder();
        for (String titulo : caminho) {
            if (rotuloTemAlgarismo(titulo)) {
                cabecalho.append(titulo).append('\n');
            }
        }
        return cabecalho.toString();
    }

    // ---------------------------------------------------------------------
    // Quais linhas com cara de título são título de verdade
    // ---------------------------------------------------------------------

    /** O que cada linha é: texto, título de uma seção ou item de uma sequência de linhas com cara de título que não foi o título. */
    private enum Papel {
        TEXTO, TITULO, ITEM
    }

    /** As linhas, na ordem em que as seções as leem, e o papel de cada uma. */
    private record Plano(List<LinhaDoDocumento> linhas, Papel[] papeis) {
    }

    private static boolean emBranco(LinhaDoDocumento linha) {
        return !linha.tabela() && linha.texto().isBlank();
    }

    private static boolean temCaraDeTitulo(LinhaDoDocumento linha) {
        return !linha.tabela() && !linha.texto().isBlank() && forma(linha.texto()) != null;
    }

    /**
     * Diz, linha a linha, quais das que têm cara de título abrem uma seção. Nenhuma linha do documento pode sumir do índice, e uma
     * seção só existe com texto. Cada <b>sequência</b> de linhas com cara de título (as linhas em branco entre elas não a interrompem; o
     * texto e a tabela, sim) segue estas regras:
     * <ul>
     *   <li>só a <b>última</b> linha da sequência que pode ser título é o título da seção, e só se vier texto (ou tabela) depois da
     *       sequência. As outras ficam no texto, uma por linha;</li>
     *   <li>junto dela ficam no caminho os <b>ancestrais</b>: as linhas anteriores da sequência cuja numeração é prefixo da dela
     *       ({@code 3 Energia / 3.1 Pontos / 3.2 Tarifa} dá {@code 3 Energia › 3.2 Tarifa}, e {@code 3.1 Pontos} fica no texto);</li>
     *   <li>linha com dado (data, {@code R$}, {@code %}, número com separador) não pode ser título;</li>
     *   <li>a numeração de um nível só ({@code 4} ou {@code 4.}) só vale como título se seguir a sequência: o último aceito mais 1, ou
     *       1 quando ainda não houve nenhum. As linhas de um nível só da mesma sequência que vieram antes contam
     *       ({@code 2. Anexos / 3. Glossário} depois do {@code 1}). Se quebrar a sequência, a linha é texto.</li>
     * </ul>
     * As linhas antes do primeiro título escolhido ficam no texto da seção que estava aberta; os ancestrais e o título abrem as seções
     * juntos, e o que sobrou da sequência vai para o texto da seção do título.
     */
    private static Plano planejar(List<LinhaDoDocumento> linhas) {
        int total = linhas.size();
        Papel[] papeis = new Papel[total];
        Arrays.fill(papeis, Papel.TEXTO);
        List<LinhaDoDocumento> ordem = new ArrayList<>(linhas);
        int ultimoNumero = 0;
        int i = 0;
        while (i < total) {
            if (!temCaraDeTitulo(linhas.get(i))) {
                i++;
                continue;
            }
            List<Integer> posicoes = new ArrayList<>();   // onde cada linha com cara de título está
            int j = i;
            while (j < total && (emBranco(linhas.get(j)) || temCaraDeTitulo(linhas.get(j)))) {
                if (!emBranco(linhas.get(j))) {
                    posicoes.add(j);
                }
                j++;
            }
            boolean seguidaDeTexto = j < total;
            ultimoNumero = escolher(linhas, posicoes, seguidaDeTexto, ultimoNumero, ordem, papeis);
            i = j;
        }
        return new Plano(ordem, papeis);
    }

    /** Escolhe o título de uma sequência e os ancestrais, grava os papéis e a nova ordem das linhas. Devolve o último número de um nível aceito. */
    private static int escolher(List<LinhaDoDocumento> linhas, List<Integer> posicoes, boolean seguidaDeTexto, int ultimoNumero,
                                List<LinhaDoDocumento> ordem, Papel[] papeis) {
        int k = posicoes.size();
        Forma[] formas = new Forma[k];
        boolean[] valida = new boolean[k];
        Set<Integer> numerosValidos = new HashSet<>();   // os números de um nível só que seguem a sequência, nesta sequência
        for (int m = 0; m < k; m++) {
            formas[m] = forma(linhas.get(posicoes.get(m)).texto());
            Titulo titulo = formas[m].titulo();
            if (formas[m].comDado()) {
                continue;
            }
            if (titulo.umNivelSo()) {
                int numero = titulo.numeracao().get(0);
                valida[m] = numero == ultimoNumero + 1 || numerosValidos.contains(numero - 1);
                if (valida[m]) {
                    numerosValidos.add(numero);
                }
            } else {
                valida[m] = true;
            }
        }
        int escolhida = -1;
        for (int m = k - 1; m >= 0 && seguidaDeTexto; m--) {
            if (valida[m]) {
                escolhida = m;
                break;
            }
        }
        // Sem título escolhido, as linhas da sequência continuam no texto: em lista, uma por linha, se a sequência tem mais de uma ou a
        // linha é título possível; a linha com dado sozinha é parte do parágrafo.
        List<Integer> escolhidas = new ArrayList<>();
        if (escolhida >= 0) {
            List<Integer> numeracaoDoTitulo = formas[escolhida].titulo().numeracao();
            if (numeracaoDoTitulo != null) {
                for (int tamanho = 1; tamanho < numeracaoDoTitulo.size(); tamanho++) {
                    for (int m = escolhida - 1; m >= 0; m--) {
                        List<Integer> numeracao = formas[m].titulo().numeracao();
                        if (!formas[m].comDado() && numeracao != null && numeracao.equals(numeracaoDoTitulo.subList(0, tamanho))) {
                            escolhidas.add(m);
                            break;
                        }
                    }
                }
            }
            escolhidas.add(escolhida);
        }
        // a nova ordem: o que vem antes do primeiro escolhido, os escolhidos (ancestrais e título) e o resto da sequência
        int primeiro = escolhidas.isEmpty() ? -1 : escolhidas.stream().mapToInt(Integer::intValue).min().getAsInt();
        List<Integer> sequencia = new ArrayList<>();
        for (int m = 0; m < k; m++) {
            if (!escolhidas.contains(m) && m < primeiro) {
                sequencia.add(m);
            }
        }
        sequencia.addAll(escolhidas);
        for (int m = 0; m < k; m++) {
            if (!escolhidas.contains(m) && (primeiro < 0 || m > primeiro)) {
                sequencia.add(m);
            }
        }
        for (int lugar = 0; lugar < k; lugar++) {
            int m = sequencia.get(lugar);
            int posicao = posicoes.get(lugar);
            ordem.set(posicao, linhas.get(posicoes.get(m)));
            if (escolhidas.contains(m)) {
                papeis[posicao] = Papel.TITULO;
            } else {
                papeis[posicao] = k >= 2 || (valida[m] && !formas[m].comDado()) ? Papel.ITEM : Papel.TEXTO;
            }
        }
        if (escolhidas.isEmpty()) {
            return ultimoNumero;
        }
        for (int m : escolhidas) {
            if (formas[m].titulo().umNivelSo()) {
                return formas[m].titulo().numeracao().get(0);
            }
        }
        for (int m = escolhida - 1; m >= 0; m--) {
            if (valida[m] && formas[m].titulo().umNivelSo()) {
                return formas[m].titulo().numeracao().get(0);
            }
        }
        return ultimoNumero;
    }

    /**
     * Junta as linhas em seções: cada título abre uma; o texto, as frases, as tabelas e as linhas de lista (que têm cara de título
     * e não são) vão para a seção aberta, cada item de lista numa linha própria. O caminho de um título é o do pai de nível mais fundo
     * que ainda está aberto: um irmão sem pai ({@code 3.2} e {@code 3.3} sem {@code 3}) não se aninha no outro.
     */
    private static List<Secao> secoes(Plano plano) {
        List<LinhaDoDocumento> linhas = plano.linhas();
        List<Secao> secoes = new ArrayList<>();
        List<String> pilha = new ArrayList<>();
        List<Integer> niveis = new ArrayList<>();
        Secao atual = new Secao(List.of());
        secoes.add(atual);
        List<LinhaDoDocumento> paragrafo = new ArrayList<>();
        List<LinhaDoDocumento> tabela = new ArrayList<>();
        for (int i = 0; i < linhas.size(); i++) {
            LinhaDoDocumento linha = linhas.get(i);
            if (linha.tabela()) {
                encerrarParagrafo(atual, paragrafo);
                tabela.add(linha);
                continue;
            }
            encerrarTabela(atual, tabela);
            if (linha.texto().isBlank()) {
                encerrarParagrafo(atual, paragrafo);
                continue;
            }
            if (plano.papeis()[i] == Papel.TITULO) {
                Titulo titulo = forma(linha.texto()).titulo();
                encerrarParagrafo(atual, paragrafo);
                while (!niveis.isEmpty() && niveis.get(niveis.size() - 1) >= titulo.nivel()) {
                    niveis.remove(niveis.size() - 1);
                    pilha.remove(pilha.size() - 1);
                }
                niveis.add(titulo.nivel());
                pilha.add(titulo.rotulo());
                atual = new Secao(List.copyOf(pilha));
                secoes.add(atual);
                continue;
            }
            if (plano.papeis()[i] == Papel.ITEM) {
                encerrarParagrafo(atual, paragrafo);
                atual.atomos.add(new Atomo(linha.texto().strip(), "\n", linha.pagina(), linha.pagina(), false));
                continue;
            }
            paragrafo.add(linha);
        }
        encerrarParagrafo(atual, paragrafo);
        encerrarTabela(atual, tabela);
        return secoes;
    }

    private static void encerrarTabela(Secao secao, List<LinhaDoDocumento> tabela) {
        if (tabela.isEmpty()) {
            return;
        }
        StringBuilder texto = new StringBuilder();
        for (LinhaDoDocumento linha : tabela) {
            texto.append(texto.isEmpty() ? "" : "\n").append(linha.texto());
        }
        secao.atomos.add(new Atomo(texto.toString(), "\n", tabela.get(0).pagina(), tabela.get(tabela.size() - 1).pagina(), true));
        tabela.clear();
    }

    // ---------------------------------------------------------------------
    // Frases
    // ---------------------------------------------------------------------

    private static final Pattern FIM_DE_FRASE = Pattern.compile("[.!?…][\"”')\\]]*(\\s+)(?=[\\p{Lu}\\d\"“(•])");
    private static final Set<String> ABREVIACOES = Set.of("sr", "sra", "srs", "dr", "dra", "etc", "ex", "exs", "nº", "art", "inc", "pág", "págs",
            "cap", "tel", "av", "r", "ltda", "s.a", "obs", "aprox", "máx", "mín");

    /** As frases do parágrafo, cada uma com o espaço que a separava da anterior e a página onde começa e onde termina. */
    private static void encerrarParagrafo(Secao secao, List<LinhaDoDocumento> paragrafo) {
        if (paragrafo.isEmpty()) {
            return;
        }
        StringBuilder texto = new StringBuilder();
        List<int[]> inicios = new ArrayList<>();   // {deslocamento da linha no texto, página}
        for (int i = 0; i < paragrafo.size(); i++) {
            LinhaDoDocumento linha = paragrafo.get(i);
            if (i > 0) {
                String anterior = paragrafo.get(i - 1).texto();
                if (partidaPorHifen(anterior, linha.texto())) {
                    // "sexta-" + "feira": a palavra volta inteira, com o hífen; a hifenização tipográfica ("inser-" + "ção") fica como está
                } else {
                    texto.append(terminaFrase(anterior) && comecaFrase(linha.texto()) ? '\n' : ' ');
                }
            }
            inicios.add(new int[] {texto.length(), linha.pagina()});
            texto.append(linha.texto().strip());
        }
        String inteiro = texto.toString();
        int inicioDaFrase = 0;
        String antes = "\n";
        Matcher fim = FIM_DE_FRASE.matcher(inteiro);
        while (fim.find()) {
            int pontuacao = fim.start();
            if (abreviacao(inteiro, pontuacao)) {
                continue;
            }
            acrescentarFrase(secao, inteiro, inicioDaFrase, fim.start(1), antes, inicios);
            antes = fim.group(1).contains("\n") ? "\n" : " ";
            inicioDaFrase = fim.end(1);
        }
        acrescentarFrase(secao, inteiro, inicioDaFrase, inteiro.length(), antes, inicios);
        paragrafo.clear();
    }

    private static void acrescentarFrase(Secao secao, String inteiro, int de, int ate, String antes, List<int[]> inicios) {
        String frase = inteiro.substring(de, ate).strip();
        if (frase.isEmpty()) {
            return;
        }
        int paginaInicio = paginaEm(inicios, de);
        int paginaFim = paginaEm(inicios, Math.max(de, ate - 1));
        for (String parte : partirTextoLongo(frase, secao.limiteUtil())) {
            secao.atomos.add(new Atomo(parte, secao.atomos.isEmpty() ? "\n" : antes, paginaInicio, paginaFim, false));
            antes = " ";
        }
    }

    private static int paginaEm(List<int[]> inicios, int deslocamento) {
        int pagina = inicios.get(0)[1];
        for (int[] inicio : inicios) {
            if (inicio[0] <= deslocamento) {
                pagina = inicio[1];
            }
        }
        return pagina;
    }

    /** A linha termina em letra e hífen e a seguinte começa em minúscula: é a mesma palavra partida na quebra de linha. */
    private static boolean partidaPorHifen(String anterior, String seguinte) {
        String fim = anterior.strip();
        String inicio = seguinte.strip();
        return fim.length() >= 2 && fim.charAt(fim.length() - 1) == '-' && Character.isLetter(fim.charAt(fim.length() - 2))
                && !inicio.isEmpty() && Character.isLowerCase(inicio.codePointAt(0));
    }

    private static boolean terminaFrase(String linha) {
        char ultimo = linha.strip().charAt(linha.strip().length() - 1);
        return ultimo == '.' || ultimo == '!' || ultimo == '?' || ultimo == ':' || ultimo == ';';
    }

    private static boolean comecaFrase(String linha) {
        int primeiro = linha.strip().codePointAt(0);
        return Character.isUpperCase(primeiro) || Character.isDigit(primeiro) || primeiro == '•' || primeiro == '-';
    }

    /** Ponto depois de abreviação ("Sr.", "etc.") ou de inicial maiúscula não termina a frase. */
    private static boolean abreviacao(String texto, int pontuacao) {
        if (texto.charAt(pontuacao) != '.') {
            return false;
        }
        int inicio = pontuacao;
        while (inicio > 0 && (Character.isLetter(texto.charAt(inicio - 1)) || texto.charAt(inicio - 1) == 'º')) {
            inicio--;
        }
        String palavra = texto.substring(inicio, pontuacao).toLowerCase(Locale.ROOT);
        return ABREVIACOES.contains(palavra) || (palavra.length() == 1 && Character.isUpperCase(texto.charAt(inicio)));
    }

    /** Um texto acima do limite de tokens (uma "frase" sem pontuação, uma linha de tabela) se parte nas palavras, para nunca passar do limite. */
    private static List<String> partirTextoLongo(String texto, int limite) {
        int maximo = Math.min(ALVO_DE_TOKENS, limite) * CARACTERES_POR_TOKEN;
        if (estimarTokens(texto) <= limite) {
            return List.of(texto);
        }
        List<String> partes = new ArrayList<>();
        String resto = texto;
        while (resto.length() > maximo) {
            int corte = resto.lastIndexOf(' ', maximo);
            if (corte <= 0) {
                corte = maximo;
            }
            partes.add(resto.substring(0, corte).strip());
            resto = resto.substring(corte).strip();
        }
        if (!resto.isEmpty()) {
            partes.add(resto);
        }
        return partes;
    }

    // ---------------------------------------------------------------------
    // Unidades: a seção inteira, ou as partes dela quando passa do alvo
    // ---------------------------------------------------------------------

    private static List<Unidade> unidadesDa(Secao secao) {
        if (secao.atomos.isEmpty()) {
            return List.of();   // título sem texto (o sumário, ou o pai de outros títulos): só dá o caminho aos filhos
        }
        int total = secao.atomos.stream().mapToInt(Atomo::tokens).sum();
        if (total <= ALVO_DE_TOKENS) {
            return List.of(new Unidade(secao.caminho, List.copyOf(secao.atomos)));
        }
        String cabecalho = cabecalhoDe(secao.caminho);
        List<Unidade> unidades = new ArrayList<>();
        List<Atomo> atual = new ArrayList<>();
        int tokens = 0;
        for (Atomo atomo : secao.atomos) {
            if (atomo.tabela() && atomo.tokens() > ALVO_DE_TOKENS) {
                fechar(unidades, secao, atual);
                tokens = 0;
                for (Atomo parte : partirTabela(atomo, secao.limiteUtil())) {
                    unidades.add(new Unidade(secao.caminho, List.of(parte)));
                }
                continue;
            }
            if (!atual.isEmpty() && tokens + atomo.tokens() > ALVO_DE_TOKENS) {
                Atomo ultimo = atual.get(atual.size() - 1);
                fechar(unidades, secao, atual);
                atual = new ArrayList<>();
                tokens = 0;
                // A frase de sobreposição: a última do trecho que fechou abre o seguinte, se o trecho ainda couber no limite com ela. A conta
                // é a do texto de verdade: as linhas de título, a frase, o espaço que as separa e a frase seguinte.
                if (!ultimo.tabela() && estimarTokens(cabecalho + ultimo.texto() + atomo.antes() + atomo.texto()) <= LIMITE_DE_TOKENS) {
                    atual.add(new Atomo(ultimo.texto(), "\n", ultimo.paginaInicio(), ultimo.paginaFim(), false));
                    tokens = atual.get(0).tokens();
                }
            }
            atual.add(atomo);
            tokens += atomo.tokens();
        }
        fechar(unidades, secao, atual);
        return unidades;
    }

    private static void fechar(List<Unidade> unidades, Secao secao, List<Atomo> atual) {
        if (!atual.isEmpty()) {
            unidades.add(new Unidade(secao.caminho, List.copyOf(atual)));
            atual.clear();
        }
    }

    /** Tabela acima do limite: parte nas linhas, repetindo a legenda e o cabeçalho em cada parte (e a linha maior que o limite, nas palavras). */
    private static List<Atomo> partirTabela(Atomo tabela, int limite) {
        if (tabela.tokens() <= limite) {
            return List.of(tabela);
        }
        String[] linhas = tabela.texto().split("\n");
        int quantasDeCabecalho = linhas.length > 1 && linhas[0].regionMatches(true, 0, "Tabela ", 0, 7) ? 2 : 1;
        String cabecalho = String.join("\n", java.util.Arrays.copyOfRange(linhas, 0, Math.min(quantasDeCabecalho, linhas.length)));
        int espacoDaLinha = Math.max(1, limite - estimarTokens(cabecalho + "\n"));   // o que sobra no pedaço para uma linha só
        List<Atomo> partes = new ArrayList<>();
        StringBuilder parte = new StringBuilder(cabecalho);
        for (int i = quantasDeCabecalho; i < linhas.length; i++) {
            for (String linha : partirTextoLongo(linhas[i], espacoDaLinha)) {
                if (estimarTokens(parte + "\n" + linha) > ALVO_DE_TOKENS && parte.length() > cabecalho.length()) {
                    partes.add(new Atomo(parte.toString(), "\n", tabela.paginaInicio(), tabela.paginaFim(), true));
                    parte = new StringBuilder(cabecalho);
                }
                parte.append('\n').append(linha);
            }
        }
        partes.add(new Atomo(parte.toString(), "\n", tabela.paginaInicio(), tabela.paginaFim(), true));
        return partes;
    }

    // ---------------------------------------------------------------------
    // Trechos: junta as unidades pequenas
    // ---------------------------------------------------------------------

    private static List<Trecho> trechos(List<Unidade> unidades) {
        List<Trecho> trechos = new ArrayList<>();
        List<Unidade> grupo = new ArrayList<>();
        for (Unidade unidade : unidades) {
            if (grupo.isEmpty()) {
                grupo.add(unidade);
                continue;
            }
            List<Unidade> candidato = new ArrayList<>(grupo);
            candidato.add(unidade);
            int tokensJuntos = estimarTokens(montar(candidato).texto());
            boolean mesmaRaiz = topo(grupo.get(0).caminho()).equals(topo(unidade.caminho()));
            boolean pequenoDemais = estimarTokens(montar(grupo).texto()) < MINIMO_DE_TOKENS
                    && numerada(grupo.get(0).caminho()) == numerada(unidade.caminho());
            if (tokensJuntos <= ALVO_DE_TOKENS && (mesmaRaiz || pequenoDemais)) {
                grupo.add(unidade);
            } else {
                trechos.add(comOrdem(montar(grupo), trechos.size() + 1));
                grupo = new ArrayList<>();
                grupo.add(unidade);
            }
        }
        if (!grupo.isEmpty()) {
            trechos.add(comOrdem(montar(grupo), trechos.size() + 1));
        }
        return trechos;
    }

    /** A capa e os títulos em caixa alta não têm número; as seções do corpo têm. A capa não se junta à primeira seção. */
    private static boolean numerada(List<String> caminho) {
        return !caminho.isEmpty() && Character.isDigit(caminho.get(0).charAt(0));
    }

    private static String topo(List<String> caminho) {
        return caminho.isEmpty() ? "" : caminho.get(0);
    }

    private static Trecho comOrdem(Trecho trecho, int ordem) {
        return new Trecho(ordem, trecho.paginaInicio(), trecho.paginaFim(), trecho.secaoCaminho(), trecho.texto(), trecho.tokens());
    }

    /**
     * O trecho de um grupo de unidades: o caminho é o que as unidades têm em comum (ou a raiz da primeira unidade que tenha título, se
     * não têm nada em comum); o título de cada unidade que está mais fundo entra no texto, numa linha, para o trecho não perder de que
     * seção cada parte é. O título do caminho que tem algarismo além da numeração também entra no texto, no começo: o número não pode
     * ficar só no caminho.
     */
    private static Trecho montar(List<Unidade> grupo) {
        List<String> caminho = prefixoComum(grupo);
        if (caminho.isEmpty()) {   // seções de raízes diferentes: o trecho leva o nome da raiz da primeira que tem título
            for (Unidade unidade : grupo) {
                if (!unidade.caminho().isEmpty()) {
                    caminho = unidade.caminho().subList(0, 1);
                    break;
                }
            }
        }
        StringBuilder texto = new StringBuilder();
        List<String> primeiraDoGrupo = grupo.get(0).caminho();
        int quantosJaNoComeco = 0;   // os títulos do caminho que a primeira unidade também tem: os outros chegam como "título a mais"
        while (quantosJaNoComeco < caminho.size() && quantosJaNoComeco < primeiraDoGrupo.size()
                && caminho.get(quantosJaNoComeco).equals(primeiraDoGrupo.get(quantosJaNoComeco))) {
            quantosJaNoComeco++;
        }
        texto.append(cabecalhoDe(caminho.subList(0, quantosJaNoComeco)));
        int paginaInicio = Integer.MAX_VALUE;
        int paginaFim = 0;
        List<String> anterior = caminho;   // o caminho do que já está no texto: só os títulos novos entram
        for (Unidade unidade : grupo) {
            List<String> titulos = titulosAMais(anterior, unidade.caminho());
            anterior = unidade.caminho();
            for (String titulo : titulos) {
                if (!texto.isEmpty() && texto.charAt(texto.length() - 1) != '\n') {
                    texto.append('\n');
                }
                texto.append(titulo).append('\n');
            }
            boolean primeira = true;
            for (Atomo atomo : unidade.atomos()) {
                boolean logoAposOTitulo = primeira && (!titulos.isEmpty() || (texto.length() > 0 && texto.charAt(texto.length() - 1) == '\n'));
                if (!texto.isEmpty() && !logoAposOTitulo) {
                    texto.append(primeira ? "\n" : atomo.antes());
                }
                texto.append(atomo.texto());
                primeira = false;
                paginaInicio = Math.min(paginaInicio, atomo.paginaInicio());
                paginaFim = Math.max(paginaFim, atomo.paginaFim());
            }
        }
        String corpo = texto.toString().strip();
        return new Trecho(0, paginaInicio, paginaFim, caminho.isEmpty() ? null : String.join(SEPARADOR_DO_CAMINHO, caminho), corpo,
                Math.max(1, estimarTokens(corpo)));
    }

    /** Os títulos da unidade que o caminho do trecho não tem: o que vai numa linha própria dentro do texto. */
    private static List<String> titulosAMais(List<String> caminhoDoTrecho, List<String> caminhoDaUnidade) {
        int n = 0;
        while (n < caminhoDoTrecho.size() && n < caminhoDaUnidade.size() && caminhoDoTrecho.get(n).equals(caminhoDaUnidade.get(n))) {
            n++;
        }
        return caminhoDaUnidade.subList(n, caminhoDaUnidade.size());
    }

    private static List<String> prefixoComum(List<Unidade> grupo) {
        List<String> prefixo = new ArrayList<>(grupo.get(0).caminho());
        for (Unidade unidade : grupo) {
            int n = 0;
            while (n < prefixo.size() && n < unidade.caminho().size() && prefixo.get(n).equals(unidade.caminho().get(n))) {
                n++;
            }
            prefixo = new ArrayList<>(prefixo.subList(0, n));
        }
        return prefixo;
    }
}
