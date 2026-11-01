package br.com.rbs.portal.documentos;

import br.com.rbs.portal.documentos.ExtratorDeTexto.Linha;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Pagina;
import java.util.ArrayList;
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
 *       quebra de página. A linha repetida que tem um número de dado ({@code Ingressos: 40/20/10/2}) fica uma vez, na primeira;</li>
 *   <li>corta por seção e título: numeração ({@code 3.2 Tarifa de energia}) ou linha em caixa alta sem data nem número de dado; cada
 *       trecho leva o caminho da seção ({@code 3 Energia › 3.2 Tarifa}) e as páginas onde começa e termina. <b>Nenhuma linha some</b>:
 *       duas ou mais linhas com cara de título em sequência são lista (um sumário, os documentos exigidos) e ficam no texto, e o
 *       título sem texto depois dele também;</li>
 *   <li>tabela inteira num trecho: a sequência de linhas com colunas alinhadas que o extrator marcou nunca é cortada (acima de
 *       {@value #LIMITE_DE_TOKENS} tokens ela se parte nas linhas, repetindo a legenda e o cabeçalho);</li>
 *   <li>de {@value #MINIMO_DE_TOKENS} a {@value #ALVO_DE_TOKENS} tokens por trecho (estimativa de 4 caracteres por token) e no máximo
 *       {@value #LIMITE_DE_TOKENS}. O Gemini corta <b>em silêncio</b> o que passa de 8.192 tokens, então o limite fica longe disso.
 *       Seções pequenas se juntam (com o título delas dentro do texto); a seção grande se parte nas frases;</li>
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

    record Titulo(int nivel, String rotulo) {
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
        List<Secao> secoes = secoes(linhas);
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

    /** As linhas que sobram depois de tirar o cabeçalho e o rodapé repetidos: é o que os testes comparam com os trechos. */
    static List<String> linhasMantidas(List<Pagina> paginas) {
        return semCabecalhoERodape(paginas).stream().map(LinhaDoDocumento::texto).toList();
    }

    /** A numeração de página no fim da linha ("... Página 3 de 11", "Pág. 3", "Page 3 of 4", "3/11"). */
    private static final Pattern NUMERACAO_NO_FIM = Pattern.compile(
            "\\b(?:p[aá]gina|p[aá]g\\.?|page|p\\.)\\s*\\d+(?:\\s*(?:de|of|/)\\s*\\d+)?\\s*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** A numeração de página no começo da linha ("Página 3 de 11 · Manual"). */
    private static final Pattern NUMERACAO_NO_INICIO = Pattern.compile(
            "^\\s*(?:p[aá]gina|p[aá]g\\.?|page|p\\.)\\s*\\d+(?:\\s*(?:de|of|/)\\s*\\d+)?(?!\\d)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** A linha que é só o número da página ("3", "3/11", "3 de 11", "- 3 -"). */
    private static final Pattern SO_A_NUMERACAO = Pattern.compile(
            "^[\\s\\-–—|•·]*\\d{1,4}(?:\\s*(?:/|de|of)\\s*\\d{1,4})?[\\s\\-–—|•·]*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** O número que é dado e não numeração: com separador no meio (40/20/10/2, 312,00, 1.800, 15:30), em reais, em % ou depois de "rótulo:". */
    private static final Pattern NUMERO_DE_DADO = Pattern.compile("\\d[/,.:]\\d|R\\$\\s*\\d|\\d\\s*%|:\\s*\\d");

    /**
     * Tira o cabeçalho e o rodapé: a linha que se repete na borda (as 3 primeiras ou as 3 últimas) de pelo menos metade das páginas, e
     * de 2 no mínimo. O topo só se compara com o topo, e o pé com o pé (o pé de uma página e o topo da seguinte não se repetem). A
     * linha tem de ser igual, letra por letra, e só a numeração de página fica de fora da comparação ("Página 3 de 11", "Pág. 3",
     * "3/11" ou o número sozinho): "Tarifa por kVA: R$ 312,00" e "Tarifa por kVA: R$ 450,00" são duas linhas, e nenhuma sai. A linha
     * repetida que carrega um número de dado ("Ingressos: 40/20/10/2" no pé de 4 páginas) sai das repetições, mas fica uma vez, onde
     * apareceu primeiro. Linha de tabela nunca sai. Documento de uma página só compara o topo com o pé dela mesma (o TXT). Numa página
     * curta (menos de 6 linhas) as duas janelas se cruzam e a linha do meio conta nas duas.
     */
    private static List<LinhaDoDocumento> semCabecalhoERodape(List<Pagina> paginas) {
        Map<String, Integer> noTopo = new HashMap<>();
        Map<String, Integer> noPe = new HashMap<>();
        for (Pagina pagina : paginas) {
            Set<String> chavesDoTopo = new HashSet<>();
            Set<String> chavesDoPe = new HashSet<>();
            Set<String> soNoTopo = new HashSet<>();
            Set<String> soNoPe = new HashSet<>();
            List<Linha> linhas = pagina.linhas();
            for (int i = 0; i < linhas.size(); i++) {
                if (!podeSerCabecalho(linhas.get(i))) {
                    continue;
                }
                String chave = chaveDaBorda(linhas.get(i).texto());
                if (i < JANELA_DA_BORDA) {
                    chavesDoTopo.add(chave);
                    soNoTopo.add(chave);
                }
                if (i >= linhas.size() - JANELA_DA_BORDA) {
                    chavesDoPe.add(chave);
                    if (i >= JANELA_DA_BORDA) {
                        soNoPe.add(chave);
                    }
                }
            }
            chavesDoTopo.forEach(chave -> noTopo.merge(chave, 1, Integer::sum));
            chavesDoPe.forEach(chave -> noPe.merge(chave, 1, Integer::sum));
            if (paginas.size() == 1) {
                // A MESMA linha no topo e (mais abaixo) no pé da única página é o cabeçalho e o rodapé dela (o TXT): vale por mais uma.
                soNoTopo.stream().filter(soNoPe::contains).forEach(chave -> {
                    noTopo.merge(chave, 1, Integer::sum);
                    noPe.merge(chave, 1, Integer::sum);
                });
            }
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
                    String chave = chaveDaBorda(linha.texto());
                    repetida = (i < JANELA_DA_BORDA && noTopo.getOrDefault(chave, 0) >= minimo)
                            || (i >= linhas.size() - JANELA_DA_BORDA && noPe.getOrDefault(chave, 0) >= minimo);
                    if (repetida && NUMERO_DE_DADO.matcher(chave).find() && jaFicouUma.add(chave)) {
                        repetida = false;   // a primeira ocorrência de uma linha com dado fica: o dado não pode sumir do índice
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

    /** A linha sem a numeração de página (que vira "#"), com os espaços unidos. O resto fica como está, letra por letra. */
    static String chaveDaBorda(String texto) {
        String linha = texto.strip().replaceAll("\\s+", " ");
        if (SO_A_NUMERACAO.matcher(linha).matches()) {
            return "#";
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
    private static final int TAMANHO_MAXIMO_DE_TITULO = 110;

    /** O título que a linha é, ou nulo se for texto. Título numerado ou em caixa alta, curto e sem pontuação de frase no fim. */
    static Titulo titulo(String texto) {
        String linha = texto.strip();
        if (linha.isEmpty() || linha.length() > TAMANHO_MAXIMO_DE_TITULO) {
            return null;
        }
        char ultimo = linha.charAt(linha.length() - 1);
        if (ultimo == ',' || ultimo == ';' || ultimo == ':') {
            return null;
        }
        Matcher numerado = TITULO_NUMERADO.matcher(linha);
        if (numerado.matches() && linha.charAt(linha.length() - 1) != '.') {
            String numero = numerado.group(1);
            return new Titulo(numero.split("\\.").length, numero + " " + numerado.group(2).strip());
        }
        if (TITULO_SOLTO.matcher(linha).matches()) {
            return new Titulo(1, linha);
        }
        long letras = linha.chars().filter(Character::isLetter).count();
        if (letras >= 4 && TITULO_EM_CAIXA_ALTA.matcher(linha).matches() && ultimo != '.' && !temDadoNumerico(linha)) {
            return new Titulo(1, linha);
        }
        return null;
    }

    /**
     * A linha em caixa alta que carrega dado ({@code PRAZO FINAL 15/01/2027}, {@code MASTER 40 20}, {@code CORTESIAS 25%}) é linha de
     * texto, e não título: tem data, número com separador ou percentual, ou dois números soltos ou mais. Um número solto só
     * ({@code PLANETA 360 · OPERAÇÕES DO FESTIVAL}, {@code ... ATLÂNTIDA 2027}) não desfaz o título.
     */
    private static boolean temDadoNumerico(String linha) {
        int soltos = 0;
        for (String pedaco : linha.split("\\s+")) {
            if (pedaco.matches("\\d+")) {
                soltos++;
            } else if (pedaco.matches(".*\\d.*") && pedaco.matches(".*[/,.:%].*")) {
                return true;
            }
        }
        return soltos >= 2;
    }

    // ---------------------------------------------------------------------
    // Quais linhas com cara de título são título de verdade
    // ---------------------------------------------------------------------

    private static boolean emBranco(LinhaDoDocumento linha) {
        return !linha.tabela() && linha.texto().isBlank();
    }

    private static boolean temCaraDeTitulo(LinhaDoDocumento linha) {
        return !linha.tabela() && !linha.texto().isBlank() && titulo(linha.texto()) != null;
    }

    /**
     * Diz, linha a linha, quais das que têm cara de título abrem uma seção. Nenhuma linha do documento pode sumir do índice, e uma
     * seção só existe com texto (ou com um filho que tenha). Por isso, de cada sequência de linhas com cara de título (as linhas em
     * branco entre elas não a interrompem; o texto e a tabela, sim):
     * <ul>
     *   <li>uma linha só, seguida de texto ou de tabela, é título;</li>
     *   <li>duas ou mais são uma lista (os itens de um sumário, de documentos exigidos, de ingressos por setor), e nenhuma vira caminho:
     *       ficam no texto. A exceção é a hierarquia que termina em texto: o último título e os que o contêm, de nível mais raso
     *       cada vez ({@code 3 Energia}, {@code 3.2 Tarifa}), são títulos, e o resto da sequência fica no texto, como o sumário que
     *       vem antes do {@code 1. Apresentação} de verdade;</li>
     *   <li>sem texto depois (o fim do documento), nenhuma é título.</li>
     * </ul>
     */
    private static boolean[] titulosDeSecao(List<LinhaDoDocumento> linhas) {
        boolean[] titulo = new boolean[linhas.size()];
        int i = 0;
        while (i < linhas.size()) {
            if (!temCaraDeTitulo(linhas.get(i))) {
                i++;
                continue;
            }
            List<Integer> sequencia = new ArrayList<>();
            int j = i;
            while (j < linhas.size() && (emBranco(linhas.get(j)) || temCaraDeTitulo(linhas.get(j)))) {
                if (!emBranco(linhas.get(j))) {
                    sequencia.add(j);
                }
                j++;
            }
            boolean seguidaDeTexto = j < linhas.size();
            if (seguidaDeTexto && sequencia.size() == 1) {
                titulo[sequencia.get(0)] = true;
            } else if (seguidaDeTexto) {
                List<Integer> hierarquia = new ArrayList<>();
                int nivelDoUltimo = Integer.MAX_VALUE;
                for (int k = sequencia.size() - 1; k >= 0; k--) {
                    int nivel = titulo(linhas.get(sequencia.get(k)).texto()).nivel();
                    if (nivel < nivelDoUltimo) {
                        hierarquia.add(sequencia.get(k));
                        nivelDoUltimo = nivel;
                    }
                }
                if (hierarquia.size() >= 2) {
                    hierarquia.forEach(indice -> titulo[indice] = true);
                }
            }
            i = j;
        }
        return titulo;
    }

    /**
     * Junta as linhas em seções: cada título abre uma; o texto, as frases, as tabelas e as linhas de lista (que têm cara de título
     * e não são) vão para a seção aberta, cada item de lista numa linha própria.
     */
    private static List<Secao> secoes(List<LinhaDoDocumento> linhas) {
        boolean[] ehTitulo = titulosDeSecao(linhas);
        List<Secao> secoes = new ArrayList<>();
        List<String> pilha = new ArrayList<>();
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
            if (ehTitulo[i]) {
                Titulo titulo = titulo(linha.texto());
                encerrarParagrafo(atual, paragrafo);
                while (pilha.size() >= titulo.nivel()) {
                    pilha.remove(pilha.size() - 1);
                }
                pilha.add(titulo.rotulo());
                atual = new Secao(List.copyOf(pilha));
                secoes.add(atual);
                continue;
            }
            if (titulo(linha.texto()) != null) {
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
        for (String parte : partirFraseLonga(frase)) {
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

    /** Uma "frase" acima do limite de tokens (texto sem pontuação) se parte nas palavras, para nunca passar do limite do Gemini. */
    private static List<String> partirFraseLonga(String frase) {
        int maximo = ALVO_DE_TOKENS * CARACTERES_POR_TOKEN;
        if (estimarTokens(frase) <= LIMITE_DE_TOKENS) {
            return List.of(frase);
        }
        List<String> partes = new ArrayList<>();
        String resto = frase;
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
        List<Unidade> unidades = new ArrayList<>();
        List<Atomo> atual = new ArrayList<>();
        int tokens = 0;
        for (Atomo atomo : secao.atomos) {
            if (atomo.tabela() && atomo.tokens() > ALVO_DE_TOKENS) {
                fechar(unidades, secao, atual);
                tokens = 0;
                for (Atomo parte : partirTabela(atomo)) {
                    unidades.add(new Unidade(secao.caminho, List.of(parte)));
                }
                continue;
            }
            if (!atual.isEmpty() && tokens + atomo.tokens() > ALVO_DE_TOKENS) {
                Atomo ultimo = atual.get(atual.size() - 1);
                fechar(unidades, secao, atual);
                atual = new ArrayList<>();
                tokens = 0;
                // A frase de sobreposição: a última do trecho que fechou abre o seguinte, se o trecho ainda couber no limite com ela.
                if (!ultimo.tabela() && ultimo.tokens() + atomo.tokens() <= LIMITE_DE_TOKENS) {
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

    /** Tabela acima do limite: parte nas linhas, repetindo a legenda e o cabeçalho em cada parte. */
    private static List<Atomo> partirTabela(Atomo tabela) {
        if (tabela.tokens() <= LIMITE_DE_TOKENS) {
            return List.of(tabela);
        }
        String[] linhas = tabela.texto().split("\n");
        int quantasDeCabecalho = linhas.length > 1 && linhas[0].regionMatches(true, 0, "Tabela ", 0, 7) ? 2 : 1;
        String cabecalho = String.join("\n", java.util.Arrays.copyOfRange(linhas, 0, Math.min(quantasDeCabecalho, linhas.length)));
        List<Atomo> partes = new ArrayList<>();
        StringBuilder parte = new StringBuilder(cabecalho);
        for (int i = quantasDeCabecalho; i < linhas.length; i++) {
            if (estimarTokens(parte + "\n" + linhas[i]) > ALVO_DE_TOKENS && parte.length() > cabecalho.length()) {
                partes.add(new Atomo(parte.toString(), "\n", tabela.paginaInicio(), tabela.paginaFim(), true));
                parte = new StringBuilder(cabecalho);
            }
            parte.append('\n').append(linhas[i]);
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
     * O trecho de um grupo de unidades: o caminho é o que as unidades têm em comum (ou a raiz da primeira, se não têm nada em comum);
     * o título de cada unidade que está mais fundo entra no texto, numa linha, para o trecho não perder de que seção cada parte é.
     */
    private static Trecho montar(List<Unidade> grupo) {
        List<String> caminho = prefixoComum(grupo);
        if (caminho.isEmpty()) {   // seções de raízes diferentes: o trecho leva o nome da raiz da primeira
            List<String> primeira = grupo.get(0).caminho();
            caminho = primeira.isEmpty() ? primeira : primeira.subList(0, 1);
        }
        StringBuilder texto = new StringBuilder();
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
                boolean logoAposOTitulo = primeira && !titulos.isEmpty();
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
