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
 *   <li>antes de cortar, tira as linhas iguais no topo e no pé de 2 ou mais páginas (cabeçalho e rodapé, com o número da página
 *       ignorado), que sujariam os trechos e partiriam a frase na quebra de página;</li>
 *   <li>corta por seção e título: numeração ({@code 3.2 Tarifa de energia}) ou linha em caixa alta; cada trecho leva o caminho da
 *       seção ({@code 3 Energia › 3.2 Tarifa}) e as páginas onde começa e termina;</li>
 *   <li>tabela inteira num trecho: a sequência de linhas com colunas alinhadas que o extrator marcou nunca é cortada (acima de
 *       {@value #LIMITE_DE_TOKENS} tokens ela se parte nas linhas, repetindo a legenda e o cabeçalho);</li>
 *   <li>de {@value #MINIMO_DE_TOKENS} a {@value #ALVO_DE_TOKENS} tokens por trecho (estimativa de 4 caracteres por token) e no máximo
 *       {@value #LIMITE_DE_TOKENS}. O Gemini corta <b>em silêncio</b> o que passa de 8.192 tokens, então o limite fica longe disso.
 *       Seções pequenas se juntam (com o título delas dentro do texto); a seção grande se parte nas frases;</li>
 *   <li>uma frase de sobreposição entre dois trechos da mesma seção partida; nunca entre seções.</li>
 * </ul>
 * A frase que cruza a quebra de página fica inteira no mesmo trecho: o corte só acontece entre frases.
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

    /**
     * Tira a linha que se repete na borda (as 3 primeiras ou as 3 últimas) de 2 ou mais páginas, e de pelo menos metade delas: um
     * cabeçalho de verdade aparece em quase todas, e o título de uma seção que também está no sumário, não. O que conta é o texto
     * sem os números ("Página 3 de 11" e "Página 4 de 11" são a mesma linha). Linha de tabela nunca sai. Documento de uma página
     * só não tem o que comparar.
     */
    private static List<LinhaDoDocumento> semCabecalhoERodape(List<Pagina> paginas) {
        Map<String, Integer> ocorrencias = new HashMap<>();
        for (Pagina pagina : paginas) {
            Set<String> chaves = new HashSet<>();
            Set<String> noTopo = new HashSet<>();
            Set<String> noPe = new HashSet<>();
            List<Linha> linhas = pagina.linhas();
            for (int indice : indicesDaBorda(linhas)) {
                String texto = linhas.get(indice).texto();
                chaves.add(chaveDaBorda(texto));
                (indice < JANELA_DA_BORDA ? noTopo : noPe).add(texto.strip().toLowerCase(Locale.ROOT));
            }
            chaves.forEach(chave -> ocorrencias.merge(chave, 1, Integer::sum));
            // A MESMA linha, igual até nos números, no topo e no pé da página vale por mais uma: é o cabeçalho e o rodapé de um
            // documento de uma página só (o TXT). "A regra 1" no topo e "A regra 99" no pé não são a mesma linha.
            noTopo.stream().filter(noPe::contains).forEach(exata -> ocorrencias.merge(chaveDaBorda(exata), 1, Integer::sum));
        }
        int minimo = Math.max(2, (paginas.size() + 1) / 2);
        List<LinhaDoDocumento> resultado = new ArrayList<>();
        for (Pagina pagina : paginas) {
            Set<Integer> borda = new HashSet<>(indicesDaBorda(pagina.linhas()));
            for (int i = 0; i < pagina.linhas().size(); i++) {
                Linha linha = pagina.linhas().get(i);
                boolean repetida = borda.contains(i) && !linha.tabela() && ocorrencias.getOrDefault(chaveDaBorda(linha.texto()), 0) >= minimo;
                if (!repetida) {
                    resultado.add(new LinhaDoDocumento(linha.texto(), pagina.numero(), linha.tabela()));
                }
            }
        }
        return resultado;
    }

    private static List<Integer> indicesDaBorda(List<Linha> linhas) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < linhas.size(); i++) {
            boolean noTopo = i < JANELA_DA_BORDA;
            boolean noPe = i >= linhas.size() - JANELA_DA_BORDA;
            if ((noTopo || noPe) && !linhas.get(i).texto().isBlank()) {
                indices.add(i);
            }
        }
        return indices;
    }

    private static String chaveDaBorda(String texto) {
        return texto.toLowerCase(Locale.ROOT).replaceAll("\\d+", "#").replaceAll("\\s+", " ").strip();
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
        if (letras >= 4 && TITULO_EM_CAIXA_ALTA.matcher(linha).matches() && ultimo != '.') {
            return new Titulo(1, linha);
        }
        return null;
    }

    /** Junta as linhas em seções: cada título abre uma; o texto, as frases e as tabelas vão para a seção aberta. */
    private static List<Secao> secoes(List<LinhaDoDocumento> linhas) {
        List<Secao> secoes = new ArrayList<>();
        List<String> pilha = new ArrayList<>();
        Secao atual = new Secao(List.of());
        secoes.add(atual);
        List<LinhaDoDocumento> paragrafo = new ArrayList<>();
        List<LinhaDoDocumento> tabela = new ArrayList<>();
        for (LinhaDoDocumento linha : linhas) {
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
            Titulo titulo = titulo(linha.texto());
            if (titulo != null) {
                encerrarParagrafo(atual, paragrafo);
                while (pilha.size() >= titulo.nivel()) {
                    pilha.remove(pilha.size() - 1);
                }
                pilha.add(titulo.rotulo());
                atual = new Secao(List.copyOf(pilha));
                secoes.add(atual);
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
                texto.append(terminaFrase(paragrafo.get(i - 1).texto()) && comecaFrase(linha.texto()) ? '\n' : ' ');
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
                if (!ultimo.tabela()) {   // a frase de sobreposição: a última do trecho que fechou abre o seguinte
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
