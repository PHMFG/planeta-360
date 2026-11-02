package br.com.rbs.portal.documentos;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.comum.TipoDeArquivo;
import br.com.rbs.portal.documentos.DivisorDeTrechos.Trecho;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Linha;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Pagina;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * [ATLAS] Teste unitário (sem Spring e sem banco) da divisão em trechos (I4 da ingestão): títulos, tabela inteira, páginas, limite de
 * tokens, sobreposição, e a remoção do cabeçalho e do rodapé repetidos. Os casos reais são o D01 (PDF de 11 páginas, com duas tabelas e
 * uma frase na quebra de página) e o D08 (TXT); os limites usam texto montado na hora.
 */
class DivisorDeTrechosTest {

    private static final ExtratorDeTexto EXTRATOR = new ExtratorDeTexto();
    private final DivisorDeTrechos divisor = new DivisorDeTrechos();

    private static List<Trecho> d01;
    private static List<Trecho> d08;

    @BeforeAll
    static void dividirOKit() {
        DivisorDeTrechos divisor = new DivisorDeTrechos();
        d01 = divisor.dividir(extrair("D01"));
        d08 = divisor.dividir(extrair("D08"));
    }

    private static List<Pagina> extrair(String id) {
        var documento = KitDeDocumentos.por(id);
        return EXTRATOR.extrair(TipoDeArquivo.daExtensao(documento.arquivo()).orElseThrow(), documento.conteudo()).paginas();
    }

    private static Trecho comTexto(List<Trecho> trechos, String parte) {
        return trechos.stream().filter(t -> t.texto().contains(parte)).findFirst()
                .orElseThrow(() -> new AssertionError("nenhum trecho tem: " + parte));
    }

    /** Páginas montadas na hora: cada argumento é uma página, com as linhas separadas por \n. */
    private static List<Pagina> paginas(String... textos) {
        List<Pagina> paginas = new ArrayList<>();
        for (int i = 0; i < textos.length; i++) {
            paginas.add(new Pagina(i + 1, Arrays.stream(textos[i].split("\n")).map(l -> new Linha(l, 62, List.of(62.0), false)).toList()));
        }
        return paginas;
    }

    private static String frases(String prefixo, int quantas) {
        return IntStream.rangeClosed(1, quantas).mapToObj(i -> prefixo + " " + i + " descreve uma regra da operação do festival com detalhe suficiente para ocupar espaço.")
                .reduce((a, b) -> a + " " + b).orElse("");
    }

    // ---------------------------------------------------------------------
    // D01: títulos, tabela, páginas
    // ---------------------------------------------------------------------

    @Test
    void oD01VirouTrechosNumeradosEmOrdemComPaginasCoerentes() {
        assertThat(d01).as("um trecho nunca atravessa página: o kit passou de 12 para 20 trechos").hasSizeBetween(18, 22);
        assertThat(d01).extracting(Trecho::ordem).containsExactlyElementsOf(IntStream.rangeClosed(1, d01.size()).boxed().toList());
        assertThat(d01).allSatisfy(t -> {
            assertThat(t.paginaInicio()).isBetween(1, 11);
            assertThat(t.paginaFim()).isEqualTo(t.paginaInicio());
            assertThat(t.texto()).isNotBlank();
            assertThat(t.tokens()).isEqualTo(DivisorDeTrechos.estimarTokens(t.texto()));
        });
        assertThat(d01.stream().mapToInt(Trecho::paginaInicio)).isSorted();
        assertThat(d01.get(0).paginaInicio()).isEqualTo(1);
        assertThat(d01.get(d01.size() - 1).paginaFim()).isEqualTo(11);
    }

    @Test
    void cadaTrechoLevaOCaminhoDaSecaoEOsTitulosDeDentroFicamNoTexto() {
        Trecho energia = comTexto(d01, "A tarifa de energia da edição 2027 é de R$ 312,00 por kVA");

        assertThat(energia.secaoCaminho()).isEqualTo("4 Energia elétrica");
        assertThat(energia.texto()).contains("4.1 Pontos de energia", "4.2 Tarifa de energia", "4.3 Geradores próprios");
        assertThat(energia.paginaInicio()).isEqualTo(5);

        Trecho abastecimento = comTexto(d01, "O abastecimento dos geradores é feito exclusivamente pela equipe da produção");
        assertThat(abastecimento.secaoCaminho()).isEqualTo("5 Abastecimento de geradores");
        assertThat(abastecimento.texto()).startsWith("5.1 Janela de abastecimento");

        assertThat(d01.stream().map(Trecho::secaoCaminho)).doesNotContainNull();
        assertThat(d01.get(0).secaoCaminho()).as("a capa em caixa alta é o título dela").isEqualTo("PLANETA 360 · OPERAÇÕES DO FESTIVAL");
    }

    @Test
    void oSumarioNaoViraTrechoEOsTitulosSemTextoSoDaoOCaminho() {
        assertThat(d01).noneMatch(t -> t.texto().contains("12. Glossário\n"));
        assertThat(d01).noneMatch(t -> t.texto().startsWith("Sumário"));
        // "1. Apresentação" não tem texto próprio: o caminho dos filhos a traz.
        assertThat(comTexto(d01, "1.1 Objetivo deste manual").secaoCaminho()).isEqualTo("1 Apresentação");
    }

    @Test
    void aTabelaFicaInteiraNoMesmoTrechoComALegenda() {
        Trecho cronograma = comTexto(d01, "Tabela 1 · Marcos da operação 2027");
        assertThat(cronograma.texto()).contains("Marco Data Quem entrega", "Informar os geradores e as cargas elétricas à",
                "27/11/2026 Marca ou agência", "Abrir os portões ao público 29/01/2027 e 30/01/2027 Produção",
                "Desmontar as ativações 31/01/2027 a 03/02/2027 Montadora");
        assertThat(cronograma.secaoCaminho()).isEqualTo("2 Cronograma operacional");

        Trecho energia = comTexto(d01, "Tabela 2 · Valores de energia e geradores na edição 2027");
        assertThat(energia.texto()).contains("Item Condição Valor", "Energia da rede", "R$ 450,00 por equipamento",
                "Instalado depois da vistoria final R$ 1.100,00 por ponto adicional");
    }

    @Test
    void aFraseQueCruzaAQuebraDePaginaSeCortaNelaECadaPedacoFicaNaPaginaDele() {
        Trecho cabeca = comTexto(d01, "A montagem das ativações começa em 18/01/2027");
        Trecho cauda = comTexto(d01, "de festival na condição em que estiverem");

        // a frase começa no pé da página 3 e termina no topo da 4: cada pedaço fica no trecho da página onde está
        assertThat(cabeca.texto()).endsWith("Estruturas que não estiverem prontas na vistoria seguem para o dia");
        assertThat(cabeca.paginaInicio()).isEqualTo(3);
        assertThat(cabeca.paginaFim()).isEqualTo(3);
        assertThat(cauda.paginaInicio()).isEqualTo(4);
        assertThat(cauda.paginaFim()).isEqualTo(4);
        assertThat(cauda).isNotSameAs(cabeca);
        assertThat(d01.stream().filter(t -> t.texto().contains("seguem para o dia")).count()).isEqualTo(1);
    }

    @Test
    void ocabecalhoEORodapeRepetidosFicamUmaVezSoNaPrimeiraPagina() {
        // as duas linhas do pé têm algarismo (360, 2027): saem das repetições, mas ficam uma vez, na primeira página (a capa)
        assertThat(d01.get(0).texto()).contains("DOCUMENTO FICTÍCIO · Planeta 360 · testes do Atlas DOCUMENTO FICTÍCIO · Planeta 360 · testes do Atlas · Manual de Operações 2027 Página 1 de 11");
        assertThat(d01.stream().skip(1)).noneMatch(t -> t.texto().contains("DOCUMENTO FICTÍCIO"));
        assertThat(d01.stream().skip(1)).noneMatch(t -> t.texto().matches("(?s).*Página \\d+ de 11.*"));
        assertThat(d01.stream().map(Trecho::texto).collect(Collectors.joining("\n")).split("testes do Atlas", -1)).as("duas linhas, uma vez cada")
                .hasSize(3);
    }

    @Test
    void cabecalhoERodapeSoSaemQuandoSeRepetemEmPeloMenosMetadeDasPaginas() {
        // O título "2. Cronograma operacional" está no sumário (página 2) e no topo da página 3: repetido em 2 de 11 páginas, não é cabeçalho.
        assertThat(comTexto(d01, "A tabela 1 lista os marcos").texto()).startsWith("A tabela 1 lista os marcos");
        assertThat(comTexto(d01, "A tabela 1 lista os marcos").secaoCaminho()).isEqualTo("2 Cronograma operacional");

        List<Trecho> trechos = divisor.dividir(paginas(
                "TOPO FIXO\nCapítulo A\nO primeiro texto fica aqui.\nPé fixo página 1",
                "TOPO FIXO\nO segundo texto fica aqui.\nPé fixo página 2",
                "TOPO FIXO\nO terceiro texto fica aqui.\nPé fixo página 3"));

        assertThat(trechos).as("um trecho por página").hasSize(3);
        assertThat(trechos).extracting(Trecho::paginaInicio).containsExactly(1, 2, 3);
        assertThat(trechos).allSatisfy(t -> assertThat(t.texto()).doesNotContain("TOPO FIXO").doesNotContain("Pé fixo"));
        assertThat(trechos.get(0).texto()).contains("primeiro texto");
        assertThat(trechos.get(1).texto()).contains("segundo texto");
        assertThat(trechos.get(2).texto()).contains("terceiro texto");
    }

    // ---------------------------------------------------------------------
    // D08 (TXT)
    // ---------------------------------------------------------------------

    @Test
    void oD08TxtVirouTrechosPorTitulosEEstaTodoNaPagina1() {
        assertThat(d08).hasSizeBetween(3, 6);
        assertThat(d08).allSatisfy(t -> {
            assertThat(t.paginaInicio()).isEqualTo(1);
            assertThat(t.paginaFim()).isEqualTo(1);
        });
        Trecho perguntas = comTexto(d08, "1.3 Quando é a reunião de alinhamento com as marcas?");
        assertThat(perguntas.secaoCaminho()).isEqualTo("1 PERGUNTAS FREQUENTES");
        assertThat(perguntas.texto()).contains("10/12/2026, às 15h");
        Trecho numeros = comTexto(d08, "O festival terá 3 palcos e 6 portões");
        assertThat(numeros.secaoCaminho()).isEqualTo("2 O FESTIVAL EM NÚMEROS (DADOS FICTÍCIOS PARA 2027)");
    }

    @Test
    void oTxtNaoTemCabecalhoNemRodapeEAMarcaDoTopoEDoPeFicaNoTexto() {
        assertThat(juncao(d08).split("DOCUMENTO FICTÍCIO · Planeta 360 · testes do Atlas", -1)).as("a marca do topo e a do pé").hasSize(3);
        assertThat(d08.get(0).texto()).contains("DOCUMENTO FICTÍCIO · Planeta 360 · testes do Atlas");
        assertThat(d08.get(d08.size() - 1).texto()).endsWith("DOCUMENTO FICTÍCIO · Planeta 360 · testes do Atlas");
    }

    // ---------------------------------------------------------------------
    // Limites
    // ---------------------------------------------------------------------

    @Test
    void nenhumTrechoDoKitPassaDoAlvoSalvoTabelaENenhumPassaDoLimite() {
        for (KitDeDocumentos.Documento documento : KitDeDocumentos.DOCUMENTOS) {
            if ("D12".equals(documento.id())) {
                continue;
            }
            for (Trecho trecho : divisor.dividir(extrair(documento.id()))) {
                assertThat(trecho.tokens()).as(documento.id() + " trecho " + trecho.ordem()).isLessThanOrEqualTo(DivisorDeTrechos.ALVO_DE_TOKENS);
            }
        }
    }

    @Test
    void secaoGrandeSeParteNasFrasesEComSobreposicaoDeUmaFraseDentroDaMesmaSecao() {
        String texto = "1. Regras\n" + frases("A regra", 100).replace(". A regra", ".\nA regra") + "\n2. Outra seção\nA outra seção é curta e fica no seu trecho.";

        List<Trecho> trechos = divisor.dividir(paginas(texto));

        List<Trecho> daSecao1 = trechos.stream().filter(t -> t.secaoCaminho().startsWith("1 Regras")).toList();
        assertThat(daSecao1.size()).isGreaterThanOrEqualTo(3);
        assertThat(daSecao1).allSatisfy(t -> assertThat(t.tokens()).isLessThanOrEqualTo(DivisorDeTrechos.ALVO_DE_TOKENS + 40));
        for (int i = 1; i < daSecao1.size(); i++) {
            String anterior = daSecao1.get(i - 1).texto();
            String ultimaFrase = anterior.substring(anterior.lastIndexOf("A regra"));
            assertThat(daSecao1.get(i).texto()).as("o trecho " + (i + 1) + " abre com a última frase do anterior").startsWith(ultimaFrase.strip());
        }
        // Nada da seção 1 foi perdido: a regra 1 e a regra 100 estão em algum trecho.
        assertThat(daSecao1.get(0).texto()).contains("A regra 1 descreve");
        assertThat(daSecao1.get(daSecao1.size() - 1).texto()).contains("A regra 100 descreve");
        // Entre seções não há sobreposição.
        Trecho outra = trechos.get(trechos.size() - 1);
        assertThat(outra.secaoCaminho()).isEqualTo("2 Outra seção");
        assertThat(outra.texto()).doesNotContain("A regra");
    }

    @Test
    void secoesPequenasDaMesmaRaizSeJuntamComOTituloDeDentroNoTexto() {
        String corpo = frases("O assunto", 14);   // 14 frases de ~24 tokens: a raiz passa de 300 tokens e fecha o trecho

        List<Trecho> trechos = divisor.dividir(paginas("1. Raiz\n1.1 Primeira\n" + corpo + "\n1.2 Segunda\nTexto da segunda parte.\n2. Outra raiz\nTexto de outra raiz."));

        assertThat(trechos).hasSize(2);
        assertThat(trechos.get(0).secaoCaminho()).isEqualTo("1 Raiz");
        assertThat(trechos.get(0).texto()).startsWith("1.1 Primeira\nO assunto 1 descreve").endsWith("1.2 Segunda\nTexto da segunda parte.");
        assertThat(trechos.get(0).tokens()).isGreaterThanOrEqualTo(DivisorDeTrechos.MINIMO_DE_TOKENS);
        assertThat(trechos.get(1).secaoCaminho()).isEqualTo("2 Outra raiz");
        assertThat(trechos.get(1).texto()).isEqualTo("Texto de outra raiz.");
    }

    @Test
    void tabelaMaiorQueOAlvoFicaInteiraEMaiorQueOLimiteSeParteRepetindoLegendaECabecalho() {
        List<Linha> linhas = new ArrayList<>();
        linhas.add(new Linha("1. Valores", 62, List.of(62.0), false));
        linhas.add(new Linha("Tabela 1 · Valores", 62, List.of(62.0), true));
        linhas.add(new Linha("Item Valor", 70, List.of(70.0, 300.0), true));
        for (int i = 1; i <= 40; i++) {
            linhas.add(new Linha("Item de número " + i + " com uma descrição bem comprida para ocupar espaço R$ " + i + ",00", 70, List.of(70.0, 300.0), true));
        }
        List<Trecho> inteira = divisor.dividir(List.of(new Pagina(1, linhas)));
        assertThat(inteira).hasSize(1);
        assertThat(inteira.get(0).tokens()).isGreaterThan(300);

        List<Linha> enorme = new ArrayList<>(linhas.subList(0, 3));
        for (int i = 1; i <= 500; i++) {
            enorme.add(new Linha("Item de número " + i + " com uma descrição bem comprida para ocupar espaço R$ " + i + ",00", 70, List.of(70.0, 300.0), true));
        }
        List<Trecho> partidas = divisor.dividir(List.of(new Pagina(1, enorme)));
        assertThat(partidas.size()).isGreaterThan(2);
        assertThat(partidas).allSatisfy(t -> {
            assertThat(t.texto()).startsWith("Tabela 1 · Valores\nItem Valor\n");
            assertThat(t.tokens()).isLessThanOrEqualTo(DivisorDeTrechos.LIMITE_DE_TOKENS);
        });
        assertThat(partidas.get(partidas.size() - 1).texto()).contains("Item de número 500");
    }

    @Test
    void fraseSemPontuacaoAcimaDoLimiteSeParteNasPalavras() {
        String gigante = "palavra ".repeat(DivisorDeTrechos.LIMITE_DE_TOKENS * 2).strip();

        List<Trecho> trechos = divisor.dividir(paginas("1. Texto\n" + gigante));

        assertThat(trechos.size()).isGreaterThanOrEqualTo(2);
        assertThat(trechos).allSatisfy(t -> assertThat(t.tokens()).isLessThanOrEqualTo(DivisorDeTrechos.LIMITE_DE_TOKENS));
    }

    @Test
    void abreviacaoNaoTerminaAFraseEDocumentoSemTextoNaoGeraTrechos() {
        List<Trecho> trechos = divisor.dividir(paginas("1. Contato\nFale com o Sr. Souza no ramal 10. Ele atende de manhã. A tarde, não."));
        assertThat(trechos).hasSize(1);
        assertThat(trechos.get(0).texto()).isEqualTo("Fale com o Sr. Souza no ramal 10. Ele atende de manhã. A tarde, não.");

        assertThat(divisor.dividir(paginas("\n  \n"))).as("só linhas em branco: nada a indexar").isEmpty();
        List<Trecho> soTitulos = divisor.dividir(paginas("1. Só título\n1.1 Outro título"));
        assertThat(soTitulos).as("títulos sem texto nenhum depois são o texto do documento: nenhuma linha some").hasSize(1);
        assertThat(soTitulos.get(0).secaoCaminho()).isNull();
        assertThat(soTitulos.get(0).texto()).isEqualTo("1. Só título\n1.1 Outro título");
    }

    // ---------------------------------------------------------------------
    // M3, rodada de correção: nada some do índice (achado 2)
    // ---------------------------------------------------------------------

    private static List<Pagina> txt(String... linhas) {
        return EXTRATOR.extrairTxt(String.join("\n", linhas).getBytes(StandardCharsets.UTF_8)).paginas();
    }

    /** O que o índice guarda de cada trecho: o caminho da seção e o texto. */
    private static String juncao(List<Trecho> trechos) {
        return trechos.stream().map(t -> (t.secaoCaminho() == null ? "" : t.secaoCaminho() + "\n") + t.texto()).collect(Collectors.joining("\n"));
    }

    private static final Pattern NUMERO_OU_PALAVRA = Pattern.compile("\\d+(?:[.,/:]\\d+)+|[\\p{L}\\p{N}]+");

    private static Set<String> palavrasENumeros(String texto) {
        Set<String> palavras = new LinkedHashSet<>();
        Matcher achado = NUMERO_OU_PALAVRA.matcher(texto);
        while (achado.find()) {
            palavras.add(achado.group());
        }
        return palavras;
    }

    private static final Pattern NUMERACAO_EM_TEXTO = Pattern.compile("\\b(?:p[aá]gina|p[aá]g\\.?|page|p\\.)\\s*\\d+(?:\\s*(?:de|of|/)\\s*\\d+)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SO_O_NUMERO_DA_PAGINA = Pattern.compile("^[\\s\\-–—|•·]*(\\d{1,4})(?:\\s*(?:/|de|of)\\s*\\d{1,4})?[\\s\\-–—|•·]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** A linha como o cabeçalho a compara: espaços unidos e a numeração de página no texto trocada por "#". */
    private static String chaveDaLinha(String linha) {
        return NUMERACAO_EM_TEXTO.matcher(linha.strip().replaceAll("\\s+", " ")).replaceAll("#");
    }

    /** As linhas sem algarismo que aparecem na borda (3 primeiras ou 3 últimas) de 2 páginas ou mais: cabeçalho e rodapé, que saem inteiros. */
    private static Set<String> cabecalhosSemAlgarismo(List<Pagina> paginas) {
        java.util.Map<String, Set<Integer>> porLinha = new java.util.HashMap<>();
        for (Pagina pagina : paginas) {
            List<Linha> linhas = pagina.linhas();
            for (int i = 0; i < linhas.size(); i++) {
                if ((i < 3 || i >= linhas.size() - 3) && !linhas.get(i).tabela() && !linhas.get(i).texto().isBlank()) {
                    porLinha.computeIfAbsent(chaveDaLinha(linhas.get(i).texto()), chave -> new HashSet<>()).add(pagina.numero());
                }
            }
        }
        Set<String> cabecalhos = new HashSet<>();
        porLinha.forEach((chave, onde) -> {
            if (onde.size() >= 2 && chave.chars().noneMatch(Character::isDigit)) {
                cabecalhos.add(chave);
            }
        });
        return cabecalhos;
    }

    /**
     * O texto CRU das páginas (como o extrator o entregou, sem nenhum cabeçalho ou rodapé tirado), menos a numeração de página
     * ("Página 3 de 11" onde estiver, e a linha que é só o número da página, no topo ou no pé, com o número a até 5 da página) e menos
     * as linhas de cabeçalho e rodapé sem algarismo, que saem inteiras. As que têm algarismo ficam uma vez, e entram na conta.
     */
    private static String textoCruSemNumeracao(List<Pagina> paginas) {
        Set<String> cabecalhos = cabecalhosSemAlgarismo(paginas);
        StringBuilder texto = new StringBuilder();
        for (Pagina pagina : paginas) {
            List<Linha> linhas = pagina.linhas();
            for (int i = 0; i < linhas.size(); i++) {
                String linha = linhas.get(i).texto().strip();
                Matcher soNumero = SO_O_NUMERO_DA_PAGINA.matcher(linha);
                boolean naBorda = i < 3 || i >= linhas.size() - 3;
                if (naBorda && soNumero.matches() && Math.abs(Integer.parseInt(soNumero.group(1)) - pagina.numero()) <= 5) {
                    continue;
                }
                if (naBorda && cabecalhos.contains(chaveDaLinha(linha))) {
                    continue;
                }
                texto.append(NUMERACAO_EM_TEXTO.matcher(linha).replaceAll(" ")).append('\n');
            }
        }
        return texto.toString();
    }

    /** O que sumiu do índice: palavras e números do texto cru de cada página que não estão em nenhum trecho nem em nenhum caminho. */
    private Set<String> oQueSumiu(List<Pagina> paginas) {
        Set<String> faltando = palavrasENumeros(textoCruSemNumeracao(paginas));
        faltando.removeAll(palavrasENumeros(juncao(divisor.dividir(paginas))));
        return faltando;
    }

    /**
     * Toda palavra e todo número do texto extraído cru (não o que sobrou depois do cabeçalho e do rodapé) está no texto ou no caminho de
     * algum trecho, menos a numeração de página. As repetições de um cabeçalho que ficou uma vez têm as mesmas palavras da que ficou.
     */
    private void confereQueNadaSumiu(List<Pagina> paginas) {
        assertThat(oQueSumiu(paginas)).as("palavras e números do texto cru que não estão em nenhum trecho nem no caminho de seção").isEmpty();
    }

    @Test
    void listaDeTitulosSeguidosSoOUltimoEhTituloEORestoFicaNoTexto() {
        List<Pagina> paginas = txt("DOCUMENTOS EXIGIDOS", "1. Contrato assinado", "2. Apólice de seguro de R$ 2.000.000,00", "3. Comprovante de pagamento",
                "ENTREGA", "As peças são entregues pela marca no portal até o prazo do plano comercial.", "",
                "INGRESSOS", "10 Ingressos Pista", "4 Ingressos Camarote", "Os ingressos são distribuídos entre os setores do festival.", "",
                "PRAZO FINAL 15/01/2027", "Sem prorrogação.");

        List<Trecho> trechos = divisor.dividir(paginas);

        assertThat(juncao(trechos)).contains("Contrato assinado", "Apólice de seguro de R$ 2.000.000,00", "Comprovante de pagamento",
                "10 Ingressos Pista", "4 Ingressos Camarote", "PRAZO FINAL 15/01/2027", "Sem prorrogação.");
        assertThat(trechos.stream().map(Trecho::texto).collect(Collectors.joining("\n")))
                .as("o número fica no texto, e não só no caminho").contains("10 Ingressos Pista\n4 Ingressos Camarote", "R$ 2.000.000,00");
        assertThat(trechos.stream().map(Trecho::secaoCaminho)).as("só a última linha da sequência é o título")
                .doesNotContain("1 Contrato assinado", "3 Comprovante de pagamento").noneMatch(c -> c != null && c.contains("Ingressos Camarote"));
        confereQueNadaSumiu(paginas);
    }

    @Test
    void tituloSemCorpoAntesDoProximoTituloOuDoFimFicaNoTexto() {
        List<Pagina> paginas = txt("1. Regras", "O texto da regra.", "2. Anexos", "3. Glossário", "O glossário explica os termos usados.", "4. Fim");

        List<Trecho> trechos = divisor.dividir(paginas);

        assertThat(trechos).as("o 3 é de primeiro nível: abre outro trecho").hasSize(2);
        assertThat(trechos.get(0).secaoCaminho()).isEqualTo("1 Regras");
        assertThat(trechos.get(0).texto()).as("o título sem corpo antes do próximo fica no texto da seção que estava aberta")
                .isEqualTo("O texto da regra.\n2. Anexos");
        assertThat(trechos.get(1).secaoCaminho()).isEqualTo("3 Glossário");
        assertThat(trechos.get(1).texto()).as("o título no fim do documento fica no texto").isEqualTo("O glossário explica os termos usados.\n4. Fim");
        confereQueNadaSumiu(paginas);
    }

    @Test
    void linhaEmCaixaAltaComNumeroOuDataEhTextoENaoTitulo() {
        assertThat(DivisorDeTrechos.titulo("PRAZO FINAL 15/01/2027")).isNull();
        assertThat(DivisorDeTrechos.titulo("MASTER 40 20")).isNull();
        assertThat(DivisorDeTrechos.titulo("SUBCOTA G 20 10")).isNull();
        assertThat(DivisorDeTrechos.titulo("TOTAL R$ 312,00")).isNull();
        assertThat(DivisorDeTrechos.titulo("CORTESIAS 25%")).isNull();
        // um número solto no título não o desfaz: a capa do D01 e o FAQ do D08 são títulos
        assertThat(DivisorDeTrechos.titulo("PLANETA 360 · OPERAÇÕES DO FESTIVAL")).isNotNull();
        assertThat(DivisorDeTrechos.titulo("FAQ DO PATROCINADOR · PLANETA ATLÂNTIDA 2027")).isNotNull();
        assertThat(DivisorDeTrechos.titulo("CRONOGRAMA")).isNotNull();
    }

    @Test
    void tabelaEmTxtComLinhasEmCaixaAltaComNumerosNaoSome() {
        List<Pagina> paginas = txt("1. Cotas e ingressos", "A tabela abaixo mostra as cortesias e as credenciais por dia.", "MASTER 40 20", "SUBCOTA G 20 10",
                "SUBCOTA 10 5", "Os valores valem para a edição de 2027.");

        List<Trecho> trechos = divisor.dividir(paginas);

        assertThat(trechos).hasSize(1);
        assertThat(trechos.get(0).secaoCaminho()).isEqualTo("1 Cotas e ingressos");
        assertThat(trechos.get(0).texto()).contains("MASTER 40 20", "SUBCOTA G 20 10", "SUBCOTA 10 5");
        confereQueNadaSumiu(paginas);
    }

    @Test
    void tituloPaiSeguidoDoFilhoContinuaVirandoCaminho() {
        List<Trecho> trechos = divisor.dividir(txt("3. Energia", "3.2 Tarifa", "A tarifa é de R$ 312,00 por kVA."));

        assertThat(trechos).hasSize(1);
        assertThat(trechos.get(0).secaoCaminho()).isEqualTo("3 Energia › 3.2 Tarifa");
        assertThat(trechos.get(0).texto()).isEqualTo("A tarifa é de R$ 312,00 por kVA.");
    }

    @Test
    void subsecaoVaziaEntreIrmasFicaNoTextoEOPaiContinuaNoCaminho() {
        List<Pagina> paginas = txt("3. Energia", "3.1 Pontos", "3.2 Tarifa", "A tarifa é de R$ 312,00 por kVA.");

        List<Trecho> trechos = divisor.dividir(paginas);

        assertThat(trechos).hasSize(1);
        assertThat(trechos.get(0).secaoCaminho()).as("só a última é o título; o ancestral de numeração prefixo fica junto").isEqualTo("3 Energia › 3.2 Tarifa");
        assertThat(trechos.get(0).texto()).isEqualTo("3.1 Pontos\nA tarifa é de R$ 312,00 por kVA.");
        confereQueNadaSumiu(paginas);
    }

    @Test
    void oSumarioFicaNoTextoEOsTitulosDepoisDeleContinuamComoCaminho() {
        List<Pagina> paginas = txt("CAPA DO MANUAL", "Versão 1.0 do manual.", "", "Sumário", "1. Apresentação", "2. Cronograma operacional", "3. Seguros", "",
                "1. Apresentação", "1.1 Objetivo", "Este manual reúne as regras de operação.", "", "2. Cronograma operacional",
                "Os prazos terminam às 18h.");

        List<Trecho> trechos = divisor.dividir(paginas);

        assertThat(trechos.stream().map(Trecho::texto).collect(Collectors.joining("\n"))).contains("Sumário\n1. Apresentação\n2. Cronograma operacional\n3. Seguros");
        assertThat(trechos.stream().map(Trecho::secaoCaminho)).contains("CAPA DO MANUAL", "1 Apresentação › 1.1 Objetivo");
        assertThat(juncao(trechos)).contains("1.1 Objetivo", "2 Cronograma operacional");
        confereQueNadaSumiu(paginas);
    }

    @Test
    void nadaDoKitSomeDoIndice() {
        for (KitDeDocumentos.Documento documento : KitDeDocumentos.DOCUMENTOS) {
            if ("D12".equals(documento.id())) {
                continue;   // sem texto extraível
            }
            assertThat(oQueSumiu(extrair(documento.id()))).as(documento.id() + ": palavras e números do texto cru que sumiram").isEmpty();
        }
    }

    // ---------------------------------------------------------------------
    // M3, rodada de correção: o cabeçalho não apaga número (achado 3)
    // ---------------------------------------------------------------------

    @Test
    void linhaComNumeroDiferenteNoPeDaPrimeiraPaginaENoTopoDaSegundaNaoSaiNemUmaNemOutra() {
        List<Pagina> paginas = paginas(
                "Abertura do capítulo de energia elétrica.\nA instalação segue as regras da produção.\nCada ponto tem carga contratada.\nTarifa por kVA: R$ 312,00",
                "Tarifa por kVA: R$ 450,00\nO gerador próprio paga cadastro.\nO ruído máximo é de 72 dB.\nA vistoria confere a instalação.");

        List<Trecho> trechos = divisor.dividir(paginas);

        assertThat(juncao(trechos)).contains("Tarifa por kVA: R$ 312,00", "Tarifa por kVA: R$ 450,00");
        confereQueNadaSumiu(paginas);
    }

    @Test
    void linhaIgualNoPeDaPrimeiraPaginaENoTopoDaSegundaNaoEhCabecalhoNemRodape() {
        // topo com topo e pé com pé: o pé de uma página e o topo da outra não se repetem
        List<Trecho> trechos = divisor.dividir(paginas(
                "Primeiro parágrafo da primeira página.\nSegundo parágrafo da primeira página.\nTerceiro parágrafo da primeira página.\nAviso importante da produção",
                "Aviso importante da produção\nPrimeiro parágrafo da segunda página.\nSegundo parágrafo da segunda página.\nTerceiro parágrafo da segunda página."));

        assertThat(juncao(trechos).split("Aviso importante da produção", -1)).as("as duas ocorrências ficam").hasSize(3);
    }

    private static List<Pagina> quatroPaginas(String linhaDoTopo, String linhaDoPe, boolean comRepetida) {
        List<Pagina> paginas = new ArrayList<>();
        for (int n = 1; n <= 4; n++) {
            List<Linha> linhas = new ArrayList<>();
            if (linhaDoTopo != null) {
                linhas.add(new Linha(linhaDoTopo.formatted(n), 62, List.of(62.0), false));
            }
            linhas.add(new Linha("Texto do primeiro parágrafo da página " + n + " do manual.", 62, List.of(62.0), false));
            linhas.add(new Linha("Texto do segundo parágrafo, bem diferente, da página " + n + ".", 62, List.of(62.0), false));
            linhas.add(new Linha("Mais um parágrafo comum, sem relação com os outros, para a página " + n + ".", 62, List.of(62.0), false));
            linhas.add(new Linha("Fecha o raciocínio dessa página número " + n + " com um quarto parágrafo.", 62, List.of(62.0), false));
            if (comRepetida) {
                linhas.add(new Linha("Ingressos: 40/20/10/2", 62, List.of(62.0), false));
            }
            if (linhaDoPe != null) {
                linhas.add(new Linha(linhaDoPe.formatted(n), 62, List.of(62.0), false));
            }
            paginas.add(new Pagina(n, linhas));
        }
        return paginas;
    }

    @Test
    void numeracaoDePaginaNaoImpedeQueORodapeSaia() {
        for (String modelo : List.of("Página %d de 4", "Pág. %d", "%d/4", "%d", "- %d -", "Page %d of 4", "%d/20")) {
            List<String> mantidas = DivisorDeTrechos.linhasMantidas(quatroPaginas(null, modelo, false));

            assertThat(mantidas).as(modelo).hasSize(16)
                    .noneMatch(linha -> IntStream.rangeClosed(1, 4).anyMatch(n -> linha.equals(modelo.formatted(n))));
        }
    }

    @Test
    void rodapeComTextoENumeracaoDePaginaTemAlgarismoForaDaNumeracaoEFicaUmaVez() {
        String modelo = "Manual de Operações 2027 · Planeta 360 · Página %d de 4";

        List<String> mantidas = DivisorDeTrechos.linhasMantidas(quatroPaginas(null, modelo, false));

        assertThat(mantidas).hasSize(17).containsOnlyOnce(modelo.formatted(1)).doesNotContain(modelo.formatted(2), modelo.formatted(3), modelo.formatted(4));
    }

    @Test
    void numeroForaDaNumeracaoPrecisaSerIgualLetraPorLetra() {
        List<String> mantidas = DivisorDeTrechos.linhasMantidas(quatroPaginas("Capítulo %d", "Página %d de 4", false));

        assertThat(mantidas).as("o capítulo muda de página para página: não é cabeçalho").contains("Capítulo 1", "Capítulo 2", "Capítulo 3", "Capítulo 4");
        assertThat(mantidas).as("a numeração de página sai").noneMatch(linha -> linha.startsWith("Página "));
    }

    @Test
    void linhaIgualRepetidaComNumeroForaDaNumeracaoSaiDasRepeticoesMasFicaUmaVezNaPrimeiraPagina() {
        List<Pagina> paginas = quatroPaginas(null, "Página %d de 4", true);

        List<String> mantidas = DivisorDeTrechos.linhasMantidas(paginas);

        assertThat(mantidas.stream().filter("Ingressos: 40/20/10/2"::equals).count()).as("fica uma vez só").isEqualTo(1);
        assertThat(mantidas.indexOf("Ingressos: 40/20/10/2")).as("na primeira página, antes do texto da segunda")
                .isLessThan(mantidas.indexOf("Texto do primeiro parágrafo da página 2 do manual."));
        assertThat(juncao(divisor.dividir(paginas))).contains("Ingressos: 40/20/10/2");
    }

    @Test
    void paginasSoComLinhasDeTabelaComOsMesmosRotulosEmTodasAsPaginasNaoSomem() {
        // sem legenda nem recuo, o extrator não marca a tabela; a chave que trocava todo dígito por # via "cabeçalho" em cada linha
        // (o documento inteiro virava vazio)
        List<Pagina> paginas = paginas(
                "Arena 25 cortesias\nCamarote 40 cortesias\nLounge 10 cortesias",
                "Arena 30 cortesias\nCamarote 45 cortesias\nLounge 12 cortesias",
                "Arena 35 cortesias\nCamarote 50 cortesias\nLounge 14 cortesias");

        List<Trecho> trechos = divisor.dividir(paginas);

        assertThat(trechos).isNotEmpty();
        assertThat(juncao(trechos)).contains("Arena 25 cortesias", "Arena 30 cortesias", "Arena 35 cortesias", "Camarote 45 cortesias",
                "Lounge 14 cortesias");
        confereQueNadaSumiu(paginas);
    }

    @Test
    void oCabecalhoERodapeReaisDoKitContinuamSaindo() {
        List<Pagina> paginas = extrair("D01");

        List<String> mantidas = DivisorDeTrechos.linhasMantidas(paginas);

        assertThat(mantidas.stream().filter(linha -> linha.contains("DOCUMENTO FICTÍCIO")).count()).as("o pé tem 360 e 2027: fica uma vez").isEqualTo(2);
        assertThat(mantidas.stream().filter(linha -> linha.matches(".*Página \\d+ de 11")).toList()).containsExactly(
                "DOCUMENTO FICTÍCIO · Planeta 360 · testes do Atlas · Manual de Operações 2027 Página 1 de 11");
        assertThat(mantidas.size()).as("duas linhas por página, nas 11 páginas, e as da primeira ficam")
                .isEqualTo(paginas.stream().mapToInt(p -> p.linhas().size()).sum() - 2 * 10);
    }

    // ---------------------------------------------------------------------
    // M3, rodada de correção: o limite de 1.500 tokens vale com a sobreposição (achado 4)
    // ---------------------------------------------------------------------

    /** Uma frase de exatamente {@code tokens} tokens (4 caracteres cada), que começa em maiúscula e termina em ponto. */
    private static String fraseDeTokens(String inicio, int tokens) {
        StringBuilder frase = new StringBuilder(inicio);
        while (frase.length() < tokens * 4 - 1) {
            frase.append(" palavra");
        }
        frase.setLength(tokens * 4 - 1);
        if (frase.charAt(frase.length() - 1) == ' ') {
            frase.setCharAt(frase.length() - 1, 'x');
        }
        return frase.append('.').toString();
    }

    @Test
    void asFrasesGrandesNaoGanhamSobreposicaoQueEstoureOLimite() {
        String a = fraseDeTokens("Primeira frase", 984);
        String b = fraseDeTokens("Segunda frase", 978);
        String c = fraseDeTokens("Terceira frase", 979);
        assertThat(DivisorDeTrechos.estimarTokens(a)).isEqualTo(984);

        List<Trecho> trechos = divisor.dividir(paginas("1. Texto grande\n" + a + "\n" + b + "\n" + c));

        assertThat(trechos).extracting(Trecho::tokens).as("sem espaço para a sobreposição, o trecho vai sem ela").containsExactly(984, 978, 979);
        assertThat(trechos).allSatisfy(t -> assertThat(t.tokens()).isLessThanOrEqualTo(DivisorDeTrechos.LIMITE_DE_TOKENS));
        assertThat(juncao(trechos).split("Primeira frase", -1)).as("a frase não se repete").hasSize(2);
    }

    @Test
    void aSobreposicaoQueCabeNoLimiteContinuaEntrando() {
        String a = fraseDeTokens("Primeira frase", 600);
        String b = fraseDeTokens("Segunda frase", 400);

        List<Trecho> trechos = divisor.dividir(paginas("1. Texto grande\n" + a + "\n" + b));

        assertThat(trechos).hasSize(2);
        assertThat(trechos.get(1).texto()).startsWith(a).endsWith(b);
        assertThat(trechos.get(1).tokens()).isLessThanOrEqualTo(DivisorDeTrechos.LIMITE_DE_TOKENS);
    }

    @Test
    void nenhumTrechoPassaDoLimiteComFrasesDeTamanhosVariados() {
        for (int base = 300; base <= 1400; base += 137) {
            List<String> frases = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                frases.add(fraseDeTokens("Frase " + i, base + (i * 61) % 400));
            }

            List<Trecho> trechos = divisor.dividir(paginas("1. Texto\n" + String.join("\n", frases)));

            assertThat(trechos).as("base " + base).allSatisfy(t -> assertThat(t.tokens()).isLessThanOrEqualTo(DivisorDeTrechos.LIMITE_DE_TOKENS));
        }
    }

    // ---------------------------------------------------------------------
    // M3, rodada de correção: linha partida por hífen (achado 5)
    // ---------------------------------------------------------------------

    @Test
    void linhaQueTerminaEmLetraEHifenJuntaComAProximaSemEspaco() {
        List<Trecho> trechos = divisor.dividir(paginas(
                "1. Prazos\nA reunião de alinhamento acontece toda sexta-\nfeira, às 15h, por vídeo. A montagem tem inser-\nção de cabos."));

        assertThat(trechos).hasSize(1);
        assertThat(trechos.get(0).texto()).contains("toda sexta-feira, às 15h").contains("inser-ção de cabos");
    }

    @Test
    void hifenSemLetraAntesOuComMaiusculaDepoisNaoJunta() {
        List<Trecho> trechos = divisor.dividir(paginas("1. Prazos\nO prazo vai de 2026 -\n2027, conforme o plano. Lista de itens-\nOutra linha começa aqui."));

        assertThat(trechos).hasSize(1);
        assertThat(trechos.get(0).texto()).contains("de 2026 - 2027", "Lista de itens- Outra linha");
    }

    @Test
    void titulosNumeradosEEmCaixaAltaSaoReconhecidosETextoComumNao() {
        assertThat(DivisorDeTrechos.titulo("3.2 Tarifa de energia").rotulo()).isEqualTo("3.2 Tarifa de energia");
        assertThat(DivisorDeTrechos.titulo("3.2 Tarifa de energia").nivel()).isEqualTo(2);
        assertThat(DivisorDeTrechos.titulo("4. Energia elétrica").rotulo()).isEqualTo("4 Energia elétrica");
        assertThat(DivisorDeTrechos.titulo("PLANETA 360 · OPERAÇÕES DO FESTIVAL").nivel()).isEqualTo(1);
        assertThat(DivisorDeTrechos.titulo("1.1 Quando e onde acontece o festival?")).isNotNull();
        assertThat(DivisorDeTrechos.titulo("Sumário")).isNotNull();
        assertThat(DivisorDeTrechos.titulo("29/01/2027 e 30/01/2027 Produção")).isNull();
        assertThat(DivisorDeTrechos.titulo("3. Garantir que a agência conheça o plano.")).as("item de lista termina em ponto").isNull();
        assertThat(DivisorDeTrechos.titulo("Este é um parágrafo comum de texto.")).isNull();
        assertThat(DivisorDeTrechos.titulo("kVA")).isNull();
        assertThat(DivisorDeTrechos.titulo("10 dB(A) medidos a 3 metros,")).isNull();
    }

    // ---------------------------------------------------------------------
    // M3, segunda rodada de correção: só a última linha de uma sequência de títulos é o título (A)
    // ---------------------------------------------------------------------

    /** Cerca de 500 tokens em frases, para cada seção ter o seu trecho e os pequenos não se juntarem. */
    private static String corpo(String assunto) {
        return frases("O assunto " + assunto, 20);
    }

    private static List<String> caminhos(List<Trecho> trechos) {
        return trechos.stream().map(Trecho::secaoCaminho).toList();
    }

    @Test
    void numaSequenciaDeTitulosNumeradosSoAUltimaEhTituloEAAnteriorFicaNoTexto() {
        List<Pagina> paginas = txt("1. Regras", corpo("Regras"), "2. Anexos", "3. Glossário", corpo("Glossário"), "3.1 Termos", corpo("Termos"),
                "4. Fim", corpo("Fim"));

        List<Trecho> trechos = divisor.dividir(paginas);

        assertThat(caminhos(trechos)).containsExactly("1 Regras", "3 Glossário", "3 Glossário › 3.1 Termos", "4 Fim");
        assertThat(trechos.get(0).texto()).as("2. Anexos fica no texto").endsWith("2. Anexos");
        confereQueNadaSumiu(paginas);
    }

    @Test
    void capituloEmDuasLinhasSoASegundaEhTitulo() {
        List<Pagina> paginas = txt("CAPÍTULO I", "DAS OBRIGAÇÕES", corpo("Obrigações"), "CAPÍTULO II", "DOS PRAZOS", corpo("Prazos"), "CAPÍTULO III",
                "DAS MULTAS", corpo("Multas"));

        List<Trecho> trechos = divisor.dividir(paginas);

        assertThat(caminhos(trechos)).as("a linha CAPÍTULO I, antes do primeiro título de primeiro nível, é o trecho sem caminho")
                .containsExactly(null, "DAS OBRIGAÇÕES", "DOS PRAZOS", "DAS MULTAS");
        assertThat(trechos.get(0).texto()).isEqualTo("CAPÍTULO I");
        assertThat(trechos.get(1).texto()).endsWith("CAPÍTULO II");
        assertThat(juncao(trechos)).contains("CAPÍTULO II", "CAPÍTULO III");
        confereQueNadaSumiu(paginas);
    }

    @Test
    void numeradoSeguidoDeSubtituloEmCaixaAltaSoOSubtituloEhTitulo() {
        List<Pagina> paginas = txt("1. Abertura", corpo("Abertura"), "2. Energia", "TARIFAS", corpo("Tarifas"), "3. Seguros", corpo("Seguros"));

        List<Trecho> trechos = divisor.dividir(paginas);

        assertThat(caminhos(trechos)).containsExactly("1 Abertura", "TARIFAS", "3 Seguros");
        assertThat(trechos.get(0).texto()).endsWith("2. Energia");
        confereQueNadaSumiu(paginas);
        assertThat(caminhos(divisor.dividir(txt("2. Energia", "TARIFAS", corpo("Tarifas"))))).as("sem seção antes, o 2 não segue a sequência: fica no trecho sem caminho, antes do TARIFAS")
                .containsExactly(null, "TARIFAS");
    }

    @Test
    void sumarioColadoNoTituloDeVerdadeSoOTituloDeVerdadeVaiParaOCaminho() {
        List<Pagina> paginas = txt("Sumário", "1. Apresentação", "2. Regras", "1. Apresentação", "1.1 Objetivo", corpo("Objetivo"), "2. Regras", corpo("Regras"));

        List<Trecho> trechos = divisor.dividir(paginas);

        assertThat(caminhos(trechos)).contains("1 Apresentação › 1.1 Objetivo", "2 Regras");
        assertThat(trechos.get(0).texto()).as("o sumário fica no texto").startsWith("Sumário\n1. Apresentação\n2. Regras");
        assertThat(caminhos(trechos)).noneMatch(c -> c != null && c.contains("Sumário"));
        confereQueNadaSumiu(paginas);
    }

    @Test
    void sumarioSeguidoDireitoDoTextoDoPrimeiroTitulo() {
        List<Pagina> paginas = txt("CAPA DO MANUAL", corpo("Capa"), "Sumário", "1. Apresentação", "2. Energia", "3. Seguros", "", "1. Apresentação",
                corpo("Apresentação"), "1.1 Objetivo", corpo("Objetivo"), "2. Energia", corpo("Energia"), "3. Seguros", corpo("Seguros"));

        List<Trecho> trechos = divisor.dividir(paginas);

        assertThat(caminhos(trechos)).containsExactly("CAPA DO MANUAL", "1 Apresentação", "1 Apresentação › 1.1 Objetivo", "2 Energia", "3 Seguros");
        assertThat(trechos.get(0).texto()).as("o sumário ficou com a capa").endsWith("Sumário\n1. Apresentação\n2. Energia\n3. Seguros");
        confereQueNadaSumiu(paginas);
    }

    @Test
    void listaDeDocumentosExigidosComValorEmReaisSoOUltimoTituloVaiParaOCaminho() {
        List<Pagina> paginas = txt("DOCUMENTOS EXIGIDOS", "1. Contrato assinado", "2. Apólice de R$ 2.000.000,00", "3. Comprovante", "ENTREGA", corpo("Entrega"));

        List<Trecho> trechos = divisor.dividir(paginas);

        assertThat(caminhos(trechos)).containsExactly(null, "ENTREGA");
        assertThat(trechos.get(0).texto()).isEqualTo("DOCUMENTOS EXIGIDOS\n1. Contrato assinado\n2. Apólice de R$ 2.000.000,00\n3. Comprovante");
        confereQueNadaSumiu(paginas);
    }

    @Test
    void numeroDeUmNivelForaDaSequenciaEhItemDeListaENaoTitulo() {
        List<Pagina> paginas = txt("INGRESSOS", "10 Ingressos Pista", "4 Ingressos Camarote", corpo("Ingressos"));

        List<Trecho> trechos = divisor.dividir(paginas);

        assertThat(caminhos(trechos)).containsExactly("INGRESSOS");
        assertThat(trechos.get(0).texto()).contains("10 Ingressos Pista\n4 Ingressos Camarote");
        confereQueNadaSumiu(paginas);

        List<Pagina> data = txt("15 Janeiro de 2027", corpo("Janeiro"));
        assertThat(caminhos(divisor.dividir(data))).as("sem sequência de títulos antes, 15 não é título").containsExactly((String) null);
        assertThat(divisor.dividir(data).get(0).texto()).startsWith("15 Janeiro de 2027");
        confereQueNadaSumiu(data);

        List<Pagina> lista = txt("1. Regras", corpo("Regras"), "2. Prazos", corpo("Prazos"), "7 Pontos de venda", "1. Verifique o gerador", "2. Ligue o cabo",
                "Depois confira a carga.", "3. Seguros", corpo("Seguros"));
        assertThat(caminhos(divisor.dividir(lista))).as("a lista dentro da seção e o 7 não quebram a sequência").containsExactly("1 Regras", "2 Prazos", "3 Seguros");
        confereQueNadaSumiu(lista);
    }

    @Test
    void ancestralDeNumeracaoPrefixoFicaNoCaminhoESubtituloIrmaoFicaNoTexto() {
        List<Pagina> paginas = txt("3. Energia", "3.1 Pontos", "3.2 Tarifa", corpo("Tarifa"), "3.3 Consumo", corpo("Consumo"));

        List<Trecho> trechos = divisor.dividir(paginas);

        assertThat(caminhos(trechos)).containsExactly("3 Energia › 3.2 Tarifa", "3 Energia › 3.3 Consumo");
        assertThat(trechos.get(0).texto()).startsWith("3.1 Pontos\n");
        confereQueNadaSumiu(paginas);
        assertThat(caminhos(divisor.dividir(txt("3.2 Tarifa", corpo("Tarifa"), "3.3 Consumo", corpo("Consumo"))))).as("irmãos sem pai não se aninham")
                .containsExactly("3.2 Tarifa", "3.3 Consumo");
    }

    @Test
    void tituloComAlgarismoAlemDaNumeracaoFicaNoCaminhoETambemNoTexto() {
        List<Pagina> paginas = txt("PLANO", corpo("Plano"), "TOTAL DE CREDENCIAIS 1800", corpo("Total"), "CAPACIDADE 40000", corpo("Capacidade"),
                "PLANO COMERCIAL 2027", corpo("Comercial"), "1. Energia", corpo("Energia"), "1.1 Pontos de 220 V", corpo("Pontos"));

        List<Trecho> trechos = divisor.dividir(paginas);

        assertThat(caminhos(trechos)).containsExactly("PLANO", "TOTAL DE CREDENCIAIS 1800", "CAPACIDADE 40000", "PLANO COMERCIAL 2027", "1 Energia",
                "1 Energia › 1.1 Pontos de 220 V");
        assertThat(trechos.get(0).texto()).as("sem algarismo, o título só fica no caminho").doesNotContain("PLANO\n").startsWith("O assunto Plano 1");
        assertThat(trechos.get(1).texto()).startsWith("TOTAL DE CREDENCIAIS 1800\n");
        assertThat(trechos.get(2).texto()).startsWith("CAPACIDADE 40000\n");
        assertThat(trechos.get(3).texto()).startsWith("PLANO COMERCIAL 2027\n");
        assertThat(trechos.get(4).texto()).as("o 1 da numeração não conta como algarismo").startsWith("O assunto Energia 1");
        assertThat(trechos.get(5).texto()).startsWith("1.1 Pontos de 220 V\n");
        confereQueNadaSumiu(paginas);
    }

    @Test
    void tituloComDataValorOuNumeroComSeparadorEhSoTexto() {
        List<Pagina> paginas = txt("1. Energia", corpo("Energia"), "1.1 Tarifa por kVA: R$ 312,00", corpo("Tarifa"), "PRAZO 15/01/2027", corpo("Prazo"));

        List<Trecho> trechos = divisor.dividir(paginas);

        assertThat(trechos).allSatisfy(t -> assertThat(t.secaoCaminho()).isEqualTo("1 Energia"));
        assertThat(juncao(trechos)).contains("1.1 Tarifa por kVA: R$ 312,00", "PRAZO 15/01/2027");
        assertThat(DivisorDeTrechos.titulo("1.1 Tarifa por kVA: R$ 312,00")).isNull();
        assertThat(DivisorDeTrechos.titulo("2. Apólice de R$ 2.000.000,00")).isNull();
        assertThat(DivisorDeTrechos.titulo("3. Desconto de 15%")).isNull();
        assertThat(DivisorDeTrechos.titulo("TOTAL DE CREDENCIAIS 1800")).isNotNull();
        assertThat(DivisorDeTrechos.titulo("CAPACIDADE 40000")).isNotNull();
        assertThat(DivisorDeTrechos.titulo("PLANO COMERCIAL 2027")).isNotNull();
        confereQueNadaSumiu(paginas);
    }

    // ---------------------------------------------------------------------
    // M3, segunda rodada de correção: cabeçalho e rodapé (B)
    // ---------------------------------------------------------------------

    /** Uma página por argumento: quatro linhas de texto diferentes e, no pé, o argumento. */
    private static List<Pagina> comPes(String... pes) {
        List<Pagina> paginas = new ArrayList<>();
        for (int n = 1; n <= pes.length; n++) {
            List<Linha> linhas = new ArrayList<>();
            for (int k = 1; k <= 4; k++) {
                linhas.add(new Linha("Parágrafo " + k + " único da página número " + n + " do relatório de testes.", 62, List.of(62.0), false));
            }
            linhas.add(new Linha(pes[n - 1], 62, List.of(62.0), false));
            paginas.add(new Pagina(n, linhas));
        }
        return paginas;
    }

    private static long vezes(List<String> linhas, String linha) {
        return linhas.stream().filter(linha::equals).count();
    }

    @Test
    void numeroSoltoOuNSobreNSoEhNumeracaoSeAcompanharAPagina() {
        for (String[] pes : new String[][] {{"1", "2", "3", "4"}, {"3", "4", "5", "6"}, {"1/4", "2/4", "3/4", "4/4"}, {"1/20", "2/20", "3/20", "4/20"},
                {"- 1 -", "- 2 -", "- 3 -", "- 4 -"}, {"0", "1", "2", "3"}}) {
            List<String> mantidas = DivisorDeTrechos.linhasMantidas(comPes(pes));

            assertThat(mantidas).as(String.join(" ", pes)).hasSize(16).doesNotContainAnyElementsOf(List.of(pes));
        }
        for (String[] pes : new String[][] {{"40/20", "40/20", "40/20", "40/20"}, {"1800", "1800", "1800", "1800"}, {"2027", "2027", "2027", "2027"}}) {
            List<String> mantidas = DivisorDeTrechos.linhasMantidas(comPes(pes));

            assertThat(vezes(mantidas, pes[0])).as(pes[0] + " não é numeração: fica uma vez, na primeira página").isEqualTo(1);
            assertThat(mantidas.indexOf(pes[0])).isEqualTo(4);
            assertThat(mantidas).hasSize(17);
        }
    }

    @Test
    void numeroQueNaoAcompanhaAPaginaOuMudaDeTotalNaoEhNumeracao() {
        for (String[] pes : new String[][] {{"11", "12", "13", "14"}, {"1/4", "2/5", "3/6", "4/7"}, {"1", "3", "5", "7"}, {"2027", "2028", "2029", "2030"}}) {
            List<String> mantidas = DivisorDeTrechos.linhasMantidas(comPes(pes));

            assertThat(mantidas).as(String.join(" ", pes)).hasSize(20).contains(pes);
        }
    }

    @Test
    void paginaEPagCotinuamNumeracaoSemConferirAContaDaPagina() {
        assertThat(DivisorDeTrechos.linhasMantidas(comPes("Página 7 de 40", "Página 9 de 40", "Página 11 de 40", "Página 13 de 40"))).hasSize(16);
        assertThat(DivisorDeTrechos.linhasMantidas(comPes("Pág. 7", "Pág. 9", "Pág. 11", "Pág. 13"))).hasSize(16);
    }

    @Test
    void linhaRepetidaComAlgarismoForaDaNumeracaoFicaUmaVezNaPrimeiraPagina() {
        List<Pagina> paginas = new ArrayList<>();
        for (int n = 1; n <= 3; n++) {
            List<Linha> linhas = new ArrayList<>();
            linhas.add(new Linha("Texto um da página " + n + " do relatório.", 62, List.of(62.0), false));
            linhas.add(new Linha("Texto dois da página " + n + " do relatório.", 62, List.of(62.0), false));
            linhas.add(new Linha("Cortesias 40 por cota", 62, List.of(62.0), false));
            linhas.add(new Linha("Prazo 15-01-2027", 62, List.of(62.0), false));
            linhas.add(new Linha("Entrega ate 15 de janeiro", 62, List.of(62.0), false));
            paginas.add(new Pagina(n, linhas));
        }

        List<String> mantidas = DivisorDeTrechos.linhasMantidas(paginas);

        for (String linha : List.of("Cortesias 40 por cota", "Prazo 15-01-2027", "Entrega ate 15 de janeiro")) {
            assertThat(vezes(mantidas, linha)).as(linha).isEqualTo(1);
        }
        assertThat(mantidas.indexOf("Cortesias 40 por cota")).as("na primeira página").isLessThan(mantidas.indexOf("Texto um da página 2 do relatório."));
        assertThat(juncao(divisor.dividir(paginas))).contains("Cortesias 40 por cota", "Prazo 15-01-2027", "Entrega ate 15 de janeiro");
        confereQueNadaSumiu(paginas);
    }

    @Test
    void umTxtNaoTemCabecalhoNemRodapeEnadaSaiDele() {
        for (List<String> linhas : List.of(
                List.of("Pergunta um?", "Sim.", "Pergunta dois?", "A resposta longa da pergunta dois.", "Pergunta três?", "Sim."),
                List.of("Limite 40 credenciais", "Texto do meio um.", "Texto do meio dois.", "Texto do meio três.", "Texto do meio quatro.", "Limite 40 credenciais"),
                List.of("2. Energia", "A energia é contratada.", "Outro parágrafo da energia.", "Mais um parágrafo da energia.", "Fecha a energia.", "2. Energia"),
                List.of("TOPO FIXO", "Um.", "Dois.", "Três.", "Quatro.", "TOPO FIXO"))) {
            List<Pagina> paginas = txt(linhas.toArray(String[]::new));

            assertThat(DivisorDeTrechos.linhasMantidas(paginas)).as(String.join(" | ", linhas)).containsExactlyElementsOf(linhas);
            confereQueNadaSumiu(paginas);
        }
    }

    // ---------------------------------------------------------------------
    // M3, segunda rodada de correção: o limite de 1.500 tokens com o separador na conta (C)
    // ---------------------------------------------------------------------

    /** Uma frase de exatamente {@code caracteres} caracteres, que começa em maiúscula e termina em ponto. */
    private static String fraseDeCaracteres(String inicio, int caracteres) {
        StringBuilder frase = new StringBuilder(inicio);
        while (frase.length() < caracteres - 1) {
            frase.append(" palavra");
        }
        frase.setLength(caracteres - 1);
        if (frase.charAt(frase.length() - 1) == ' ') {
            frase.setCharAt(frase.length() - 1, 'x');
        }
        return frase.append('.').toString();
    }

    @Test
    void oSeparadorEntraNaContaDoLimiteDeTokens() {
        String a = fraseDeCaracteres("Primeira frase", 2000);
        String b = fraseDeCaracteres("Segunda frase", 4000);

        List<Trecho> trechos = divisor.dividir(paginas("1. Texto grande\n" + a + "\n" + b));

        assertThat(trechos).allSatisfy(t -> {
            assertThat(t.texto().length()).isLessThanOrEqualTo(DivisorDeTrechos.LIMITE_DE_TOKENS * DivisorDeTrechos.CARACTERES_POR_TOKEN);
            assertThat(t.tokens()).isLessThanOrEqualTo(DivisorDeTrechos.LIMITE_DE_TOKENS);
        });
        assertThat(trechos).extracting(Trecho::tokens).as("a sobreposição estouraria o limite por 1 caractere: o trecho vai sem ela").containsExactly(500, 1000);
    }

    @Test
    void somaExatamenteIgualAoLimiteCabeEASobreposicaoEntra() {
        String a = fraseDeCaracteres("Primeira frase", 2000);
        String b = fraseDeCaracteres("Segunda frase", 3999);

        List<Trecho> trechos = divisor.dividir(paginas("1. Texto grande\n" + a + "\n" + b));

        assertThat(trechos).extracting(Trecho::tokens).containsExactly(500, DivisorDeTrechos.LIMITE_DE_TOKENS);
        assertThat(trechos.get(1).texto()).hasSize(6000).startsWith(a).endsWith(b);
    }

    @Test
    void nenhumTrechoPassaDoLimiteComFrasesDeTamanhoAleatorioNemComTituloComAlgarismo() {
        Random sorteio = new Random(7);
        for (int vez = 0; vez < 1500; vez++) {
            StringBuilder documento = new StringBuilder(vez % 2 == 0 ? "1. Texto\n" : "RELATÓRIO DE 2027\n");
            int quantas = 2 + sorteio.nextInt(6);
            for (int i = 0; i < quantas; i++) {
                documento.append(fraseDeCaracteres("Frase " + i, 200 + sorteio.nextInt(30) * 200)).append(sorteio.nextBoolean() ? "\n" : " ");
            }
            for (Trecho trecho : divisor.dividir(paginas(documento.toString()))) {
                assertThat(trecho.tokens()).as(documento.toString().substring(0, 40) + " vez " + vez).isLessThanOrEqualTo(DivisorDeTrechos.LIMITE_DE_TOKENS);
            }
        }
    }

    @Test
    void tabelaComUmaLinhaMaiorQueOLimiteTambemNaoPassa() {
        List<Linha> linhas = new ArrayList<>();
        linhas.add(new Linha("1. Valores", 62, List.of(62.0), false));
        linhas.add(new Linha("Tabela 1 · Valores", 62, List.of(62.0), true));
        linhas.add(new Linha("Item Valor", 70, List.of(70.0, 300.0), true));
        linhas.add(new Linha("Item " + "comprido ".repeat(1500) + "R$ 1,00", 70, List.of(70.0, 300.0), true));

        List<Trecho> trechos = divisor.dividir(List.of(new Pagina(1, linhas)));

        assertThat(trechos).isNotEmpty().allSatisfy(t -> assertThat(t.tokens()).isLessThanOrEqualTo(DivisorDeTrechos.LIMITE_DE_TOKENS));
    }

    // ---------------------------------------------------------------------
    // M3, segunda rodada de correção: fuzz, o que sumiu e o limite, partindo do texto cru (D)
    // ---------------------------------------------------------------------

    @Test
    void fuzzNadaSomeDoTextoCruENenhumTrechoPassaDoLimite() {
        String[] tipos = {"1. Apresentação", "1.1 Objetivo", "2 Energia", "2.3.4 Detalhe", "CRONOGRAMA", "MASTER 40 20", "PRAZO 15/01/2027", "Sumário",
                "O texto da regra é este.", "Uma frase sem ponto", "outra minúscula", "Linha com hífen-", "feira e resto.", "• item da lista",
                "- item com traço;", "Tarifa por kVA: R$ 312,00", "", "", "40", "Página 3 de 9", "Sr. Fulano veio. Dr. Beltrano também!", "3 Ingressos Pista",
                "A: b:", "10 de Janeiro de 2027", "ÁREA 51", "ANEXO I", "Tabela 1 · Marcos", "TOTAL DE CREDENCIAIS 1800", "2. Apólice de R$ 100,00",
                "CAPÍTULO II", "DAS MULTAS", "1/9", "Sim."};
        Random sorteio = new Random(11);
        for (int vez = 0; vez < 4000; vez++) {
            int quantasPaginas = 1 + sorteio.nextInt(3);
            String[] textos = new String[quantasPaginas];
            for (int p = 0; p < quantasPaginas; p++) {
                StringBuilder pagina = new StringBuilder();
                int quantasLinhas = 1 + sorteio.nextInt(10);
                for (int k = 0; k < quantasLinhas; k++) {
                    pagina.append(tipos[sorteio.nextInt(tipos.length)]).append(k + 1 < quantasLinhas ? "\n" : "");
                }
                textos[p] = pagina.toString();
            }
            List<Pagina> paginas = paginas(textos);

            assertThat(oQueSumiu(paginas)).as("vez " + vez + ": " + Arrays.toString(textos)).isEmpty();
            assertThat(divisor.dividir(paginas)).as("vez " + vez + ": " + Arrays.toString(textos))
                    .allSatisfy(t -> assertThat(t.tokens()).isLessThanOrEqualTo(DivisorDeTrechos.LIMITE_DE_TOKENS));
            assertThat(divisor.dividir(paginas)).as("vez " + vez + ": uma página só por trecho: " + Arrays.toString(textos))
                    .allSatisfy(t -> assertThat(t.paginaInicio()).isEqualTo(t.paginaFim()));
        }
    }

    // ---------------------------------------------------------------------
    // M4, rodada de correção: trecho de uma página só, e título de primeiro nível abre trecho
    // ---------------------------------------------------------------------

    private static void umaPaginaSo(List<Trecho> trechos, String quem) {
        assertThat(trechos).as(quem).isNotEmpty().allSatisfy(t -> assertThat(t.paginaInicio()).as(quem + ", trecho " + t.ordem())
                .isEqualTo(t.paginaFim()));
    }

    private static Linha tabela(String texto) {
        return new Linha(texto, 70, List.of(70.0, 300.0), true);
    }

    @Test
    void nenhumTrechoDoKitAtravessaPagina() {
        for (KitDeDocumentos.Documento documento : KitDeDocumentos.DOCUMENTOS) {
            if ("D12".equals(documento.id())) {
                continue;
            }
            umaPaginaSo(divisor.dividir(extrair(documento.id())), documento.id());
        }
    }

    @Test
    void oSomDe85DbDoManualFicaNaPagina9ComASecao10ENaoNoTrechoQueComecouNa8() {
        Trecho som = comTexto(divisor.dividir(extrair("D02")), "O som de uma ativação não pode passar de 85 dB(A)");

        assertThat(som.paginaInicio()).isEqualTo(9);
        assertThat(som.paginaFim()).isEqualTo(9);
        assertThat(som.secaoCaminho()).startsWith("10 Ativação das marcas");
    }

    @Test
    void todoFatoDoGabaritoQueCabeEmUmaPaginaEstaEmTrechoDaPaginaDele() throws java.io.IOException {
        var gabarito = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(new org.springframework.core.io.ClassPathResource(KitDeDocumentos.GABARITO).getInputStream());
        java.util.Map<String, List<Trecho>> porDocumento = new java.util.HashMap<>();
        List<String> falhas = new ArrayList<>();
        int conferidos = 0;
        for (var fato : gabarito) {
            if (fato.path("semResposta").asBoolean(false) || fato.path("documento").isNull() || fato.path("trecho").isNull()
                    || fato.path("quebraDePagina").asBoolean(false)) {
                continue;
            }
            String id = fato.path("id").asString();
            List<Trecho> trechos = porDocumento.computeIfAbsent(id, chave -> divisor.dividir(extrair(chave)));
            String procurado = GabaritoDosDocumentosTest.normalizar(fato.path("trecho").asString());
            List<Trecho> com = trechos.stream().filter(t -> GabaritoDosDocumentosTest.normalizar(t.texto()).contains(procurado)).toList();
            conferidos++;
            Set<Integer> paginasCertas = new HashSet<>(List.of(fato.path("pagina").asInt()));
            fato.path("paginasTambem").forEach(p -> paginasCertas.add(p.asInt()));
            int pagina = fato.path("pagina").asInt();
            if (com.isEmpty()) {
                falhas.add(fato.path("fato").asString() + " (" + id + "): nenhum trecho tem o texto do gabarito");
            } else if (com.stream().anyMatch(t -> !paginasCertas.contains(t.paginaInicio()))) {
                falhas.add(fato.path("fato").asString() + " (" + id + ", página " + pagina + "): trecho na página "
                        + com.stream().map(t -> t.paginaInicio() + "-" + t.paginaFim()).toList());
            }
        }
        assertThat(falhas).as("fatos do gabarito sem trecho na página certa").isEmpty();
        assertThat(conferidos).isGreaterThanOrEqualTo(100);
    }

    @Test
    void secaoQueAtravessaAPaginaViraUmTrechoPorPaginaComOMesmoCaminho() {
        List<Trecho> trechos = divisor.dividir(paginas(
                "1. Regras\n" + frases("A regra", 3).replace(". A regra", ".\nA regra"),
                frases("A norma", 3).replace(". A norma", ".\nA norma")));

        assertThat(trechos).hasSize(2);
        assertThat(trechos).extracting(Trecho::paginaInicio).containsExactly(1, 2);
        assertThat(trechos).extracting(Trecho::paginaFim).containsExactly(1, 2);
        assertThat(trechos).extracting(Trecho::secaoCaminho).containsExactly("1 Regras", "1 Regras");
        assertThat(trechos.get(0).texto()).contains("A regra 3 descreve").doesNotContain("A norma");
        assertThat(trechos.get(1).texto()).startsWith("A norma 1 descreve").doesNotContain("A regra");
    }

    @Test
    void aSobreposicaoNuncaVemDaPaginaAnterior() {
        String paginaUm = "1. Regras\n" + frases("A regra", 100).replace(". A regra", ".\nA regra");
        String paginaDois = frases("A norma", 100).replace(". A norma", ".\nA norma");

        List<Trecho> trechos = divisor.dividir(paginas(paginaUm, paginaDois));

        umaPaginaSo(trechos, "duas páginas grandes");
        List<Trecho> daPagina2 = trechos.stream().filter(t -> t.paginaInicio() == 2).toList();
        assertThat(daPagina2.size()).isGreaterThanOrEqualTo(2);
        assertThat(daPagina2.get(0).texto()).as("o primeiro da página 2 não abre com frase da 1").startsWith("A norma 1 descreve").doesNotContain("A regra");
        // dentro da mesma página a sobreposição de uma frase continua: o trecho 2 da página 2 abre com a última frase do 1
        String anterior = daPagina2.get(0).texto();
        assertThat(daPagina2.get(1).texto()).startsWith(anterior.substring(anterior.lastIndexOf("A norma")).strip());
        confereQueNadaSumiu(paginas(paginaUm, paginaDois));
    }

    @Test
    void fraseQueCruzaAQuebraDePaginaSeCortaNelaECadaPedacoFicaNaSuaPaginaSemPerderTexto() {
        List<Pagina> paginas = paginas("1. Prazos\nO prazo para informar os geradores vai até",
                "o dia 27/11/2026 às 18h, sem prorrogação.\nOutra regra.");

        List<Trecho> trechos = divisor.dividir(paginas);

        assertThat(trechos).hasSize(2);
        assertThat(trechos.get(0).paginaInicio()).isEqualTo(1);
        assertThat(trechos.get(0).paginaFim()).isEqualTo(1);
        assertThat(trechos.get(0).texto()).isEqualTo("O prazo para informar os geradores vai até");
        assertThat(trechos.get(1).paginaInicio()).isEqualTo(2);
        assertThat(trechos.get(1).paginaFim()).isEqualTo(2);
        assertThat(trechos.get(1).texto()).startsWith("o dia 27/11/2026 às 18h, sem prorrogação.").contains("Outra regra.");
        confereQueNadaSumiu(paginas);
    }

    @Test
    void palavraPartidaPorHifenNaQuebraDePaginaTambemSeCortaENadaSome() {
        List<Pagina> paginas = paginas("1. Datas\nO festival abre na sexta-", "feira, dia 29, e fecha no domingo.");

        List<Trecho> trechos = divisor.dividir(paginas);

        assertThat(trechos).hasSize(2);
        assertThat(trechos.get(0).texto()).endsWith("sexta-");
        assertThat(trechos.get(1).texto()).startsWith("feira, dia 29");
        umaPaginaSo(trechos, "hífen na quebra de página");
        confereQueNadaSumiu(paginas);
    }

    @Test
    void naMesmaPaginaAFraseEAPalavraPartidaPorHifenContinuamInteiras() {
        List<Trecho> trechos = divisor.dividir(paginas("1. Datas\nO festival abre na sexta-\nfeira, dia 29, e fecha no domingo."));

        assertThat(trechos).hasSize(1);
        assertThat(trechos.get(0).texto()).isEqualTo("O festival abre na sexta-feira, dia 29, e fecha no domingo.");
    }

    @Test
    void tituloDePrimeiroNivelSempreAbreOutroTrechoMesmoComAsDuasSecoesPequenasNaMesmaPagina() {
        List<Trecho> trechos = divisor.dividir(paginas("1. Raiz\nTexto da primeira raiz.\n2. Outra raiz\nTexto de outra raiz."));

        assertThat(trechos).hasSize(2);
        assertThat(trechos.get(0).secaoCaminho()).isEqualTo("1 Raiz");
        assertThat(trechos.get(0).texto()).isEqualTo("Texto da primeira raiz.");
        assertThat(trechos.get(1).secaoCaminho()).isEqualTo("2 Outra raiz");
        assertThat(trechos.get(1).texto()).isEqualTo("Texto de outra raiz.");
        umaPaginaSo(trechos, "duas raízes");
    }

    @Test
    void tituloDePrimeiroNivelEmCaixaAltaTambemAbreOutroTrecho() {
        List<Trecho> trechos = divisor.dividir(paginas("REGRAS GERAIS\nTexto curto das regras.\nPRAZOS\nTexto curto dos prazos."));

        assertThat(trechos).extracting(Trecho::secaoCaminho).containsExactly("REGRAS GERAIS", "PRAZOS");
        assertThat(trechos).extracting(Trecho::texto).containsExactly("Texto curto das regras.", "Texto curto dos prazos.");
    }

    @Test
    void tituloDeSegundoNivelNaoAbreTrechoESecoesPequenasDaMesmaRaizContinuamJuntas() {
        List<Trecho> trechos = divisor.dividir(paginas("1. Raiz\n1.1 Primeira\nTexto um da raiz.\n1.2 Segunda\nTexto dois da raiz."));

        assertThat(trechos).hasSize(1);
        assertThat(trechos.get(0).secaoCaminho()).isEqualTo("1 Raiz");
        assertThat(trechos.get(0).texto()).contains("1.1 Primeira", "Texto um da raiz.", "1.2 Segunda", "Texto dois da raiz.");
    }

    @Test
    void oSomDaSecao10NaoApareceComoSecao9() {
        String[] nomes = {"Um", "Dois", "Três", "Quatro", "Cinco", "Seis", "Sete", "Oito"};
        StringBuilder antes = new StringBuilder();
        for (int s = 1; s <= 8; s++) {
            antes.append(s).append(". Seção ").append(nomes[s - 1]).append("\nTexto curto da seção ").append(s).append(".\n");
        }
        String pagina8 = antes + "9. Credenciamento\nO credenciamento fecha antes da montagem.";
        String pagina9 = "10. Ativação das marcas\n10.1 Energia\nA energia segue o manual.\n10.2 Som\nO som não pode passar de 85 dB(A).";

        List<Trecho> trechos = divisor.dividir(paginas(pagina8, pagina9));

        Trecho som = comTexto(trechos, "85 dB(A)");
        assertThat(som.secaoCaminho()).startsWith("10 Ativação das marcas");
        assertThat(som.paginaInicio()).isEqualTo(2);
        assertThat(som.paginaFim()).isEqualTo(2);
        assertThat(comTexto(trechos, "O credenciamento fecha").secaoCaminho()).isEqualTo("9 Credenciamento");
        assertThat(som.texto()).doesNotContain("credenciamento");
    }

    @Test
    void tabelaQueAtravessaAPaginaSeParteNelaERepeteALegendaEOCabecalhoNaSeguinte() {
        Pagina um = new Pagina(1, List.of(new Linha("1. Valores", 62, List.of(62.0), false), tabela("Tabela 1 · Valores"), tabela("Item Valor"),
                tabela("Energia R$ 10,00"), tabela("Água R$ 20,00")));
        Pagina dois = new Pagina(2, List.of(tabela("Gás R$ 30,00"), tabela("Luz R$ 40,00")));

        List<Trecho> trechos = divisor.dividir(List.of(um, dois));

        assertThat(trechos).hasSize(2);
        assertThat(trechos.get(0).texto()).isEqualTo("Tabela 1 · Valores\nItem Valor\nEnergia R$ 10,00\nÁgua R$ 20,00");
        assertThat(trechos.get(1).texto()).isEqualTo("Tabela 1 · Valores\nItem Valor\nGás R$ 30,00\nLuz R$ 40,00");
        assertThat(trechos).extracting(Trecho::paginaInicio).containsExactly(1, 2);
        assertThat(trechos).extracting(Trecho::paginaFim).containsExactly(1, 2);
        assertThat(trechos).extracting(Trecho::secaoCaminho).containsExactly("1 Valores", "1 Valores");
    }

    @Test
    void tabelaSemLegendaQueAtravessaAPaginaNaoRepeteNada() {
        // sem legenda ("Tabela N"), a primeira linha pode ser dado, e não cabeçalho: repeti-la na página seguinte inventaria uma linha
        Pagina um = new Pagina(1, List.of(new Linha("1. Valores", 62, List.of(62.0), false), tabela("Item Valor"), tabela("Energia R$ 10,00")));
        Pagina dois = new Pagina(2, List.of(tabela("Gás R$ 30,00")));

        List<Trecho> trechos = divisor.dividir(List.of(um, dois));

        assertThat(trechos).extracting(Trecho::texto).containsExactly("Item Valor\nEnergia R$ 10,00", "Gás R$ 30,00");
    }

    @Test
    void tabelaSemLegendaNaoRepeteAPrimeiraLinhaComoSeFosseCabecalho() {
        Pagina um = new Pagina(1, List.of(new Linha("1. Cotas", 62, List.of(62.0), false), tabela("Master 40"), tabela("Prata 20")));
        Pagina dois = new Pagina(2, List.of(tabela("Bronze 10"), tabela("Apoio 5")));

        List<Trecho> trechos = divisor.dividir(List.of(um, dois));

        assertThat(trechos).extracting(Trecho::texto).containsExactly("Master 40\nPrata 20", "Bronze 10\nApoio 5");
        confereQueNadaSumiu(List.of(um, dois));
    }

    @Test
    void naPaginaSeguinteACabecalhoJaRepetidoPeloExtratorNaoSeRepeteDeNovo() {
        Pagina um = new Pagina(1, List.of(new Linha("1. Valores", 62, List.of(62.0), false), tabela("Tabela 1 · Valores"), tabela("Item Valor"),
                tabela("Energia R$ 10,00")));
        Pagina dois = new Pagina(2, List.of(tabela("Item Valor"), tabela("Gás R$ 30,00")));

        List<Trecho> trechos = divisor.dividir(List.of(um, dois));

        assertThat(trechos).extracting(Trecho::texto).containsExactly("Tabela 1 · Valores\nItem Valor\nEnergia R$ 10,00", "Item Valor\nGás R$ 30,00");
        confereQueNadaSumiu(List.of(um, dois));
    }

    @Test
    void naPaginaSeguinteACabecalhoComOutraQuantidadeDeEspacosTambemContaComoIgual() {
        Pagina um = new Pagina(1, List.of(new Linha("1. Valores", 62, List.of(62.0), false), tabela("Tabela 1 · Valores"), tabela("Item   Valor"),
                tabela("Energia R$ 10,00")));
        Pagina dois = new Pagina(2, List.of(tabela("Item Valor"), tabela("Gás R$ 30,00")));

        assertThat(divisor.dividir(List.of(um, dois))).extracting(Trecho::texto).last().isEqualTo("Item Valor\nGás R$ 30,00");
    }

    @Test
    void tabelaComLegendaQueAtravessaAPaginaSemOCabecalhoRepeteALegendaEOCabecalho() {
        Pagina um = new Pagina(1, List.of(new Linha("1. Valores", 62, List.of(62.0), false), tabela("Tabela 1 · Valores"), tabela("Item Valor"),
                tabela("Energia R$ 10,00")));
        Pagina dois = new Pagina(2, List.of(tabela("Gás R$ 30,00")));

        assertThat(divisor.dividir(List.of(um, dois))).extracting(Trecho::texto).containsExactly("Tabela 1 · Valores\nItem Valor\nEnergia R$ 10,00",
                "Tabela 1 · Valores\nItem Valor\nGás R$ 30,00");
    }

    @Test
    void tabelaSemLegendaMaiorQueOLimiteSeParteSemRepetirLinhaNenhumaENadaSome() {
        List<Linha> linhas = new ArrayList<>();
        linhas.add(new Linha("1. Valores", 62, List.of(62.0), false));
        for (int i = 1; i <= 500; i++) {
            linhas.add(tabela("Item de número " + i + " com uma descrição bem comprida para ocupar espaço R$ " + i + ",00"));
        }
        List<Pagina> paginas = List.of(new Pagina(1, linhas));

        List<Trecho> partes = divisor.dividir(paginas);

        assertThat(partes.size()).isGreaterThan(2);
        assertThat(partes).allSatisfy(t -> assertThat(t.tokens()).isLessThanOrEqualTo(DivisorDeTrechos.LIMITE_DE_TOKENS));
        assertThat(partes.get(0).texto()).startsWith("Item de número 1 com");
        for (Trecho parte : partes.subList(1, partes.size())) {
            assertThat(parte.texto()).as("a parte " + parte.ordem() + " não começa pela primeira linha da tabela").doesNotStartWith("Item de número 1 ");
        }
        for (int i = 1; i <= 500; i++) {
            String linha = "Item de número " + i + " com uma descrição bem comprida para ocupar espaço R$ " + i + ",00";
            long vezes = partes.stream().filter(t -> java.util.Arrays.asList(t.texto().split("\n")).contains(linha)).count();
            assertThat(vezes).as(linha + " aparece em exatamente um trecho").isEqualTo(1);
        }
        confereQueNadaSumiu(paginas);
    }

    @Test
    void naPaginaSeguinteOutraTabelaComLegendaPropriaNaoGanhaOCabecalhoDaPrimeira() {
        Pagina um = new Pagina(1, List.of(new Linha("1. Valores", 62, List.of(62.0), false), tabela("Tabela 1 · Energia"), tabela("Item Valor"),
                tabela("Energia R$ 10,00")));
        Pagina dois = new Pagina(2, List.of(tabela("Tabela 2 · Água"), tabela("Item Preço"), tabela("Água R$ 20,00")));

        List<Trecho> trechos = divisor.dividir(List.of(um, dois));

        assertThat(trechos).extracting(Trecho::texto).containsExactly("Tabela 1 · Energia\nItem Valor\nEnergia R$ 10,00",
                "Tabela 2 · Água\nItem Preço\nÁgua R$ 20,00");
    }

    @Test
    void tabelaDeUmaPaginaSoContinuaInteira() {
        Pagina um = new Pagina(1, List.of(new Linha("1. Valores", 62, List.of(62.0), false), tabela("Tabela 1 · Valores"), tabela("Item Valor"),
                tabela("Energia R$ 10,00"), tabela("Água R$ 20,00")));

        List<Trecho> trechos = divisor.dividir(List.of(um));

        assertThat(trechos).extracting(Trecho::texto).containsExactly("Tabela 1 · Valores\nItem Valor\nEnergia R$ 10,00\nÁgua R$ 20,00");
    }

    @Test
    void umTxtComDoisTitulosDePrimeiroNivelViraDoisTrechosNaPagina1SemPerderNada() {
        List<Pagina> paginas = txt("1. Perguntas", "O festival abre na sexta.", "", "2. Números", "O público é de 40 mil pessoas por dia.");

        List<Trecho> trechos = divisor.dividir(paginas);

        assertThat(trechos).extracting(Trecho::secaoCaminho).containsExactly("1 Perguntas", "2 Números");
        assertThat(trechos).extracting(Trecho::paginaInicio).containsExactly(1, 1);
        assertThat(trechos).extracting(Trecho::paginaFim).containsExactly(1, 1);
        confereQueNadaSumiu(paginas);
    }

    @Test
    void oD08TxtContinuaNaPagina1ENaoPerdeNada() {
        List<Trecho> trechos = divisor.dividir(extrair("D08"));

        assertThat(trechos).isNotEmpty().allSatisfy(t -> {
            assertThat(t.paginaInicio()).isEqualTo(1);
            assertThat(t.paginaFim()).isEqualTo(1);
        });
        assertThat(oQueSumiu(extrair("D08"))).isEmpty();
    }

    /**
     * O documento montado ao acaso, com os marcadores conhecidos: cada linha do corpo leva um marcador (ZaSbLc) e o teste sabe a página
     * e o título de primeiro nível dele. Cada marcador tem de estar num trecho da página dele e do primeiro nível dele; nenhum trecho
     * atravessa página nem junta dois primeiros níveis; nenhum passa do limite; e nada some.
     */
    @Test
    void documentoEstruturadoAoAcasoCadaMarcadorFicaNaSuaPaginaNaSuaRaizENadaSome() {
        String[] nomes = {"Energia elétrica", "Credenciamento", "Ativação das marcas", "Montagem", "Abastecimento", "Segurança", "Seguros",
                "Contatos"};
        String[] subnomes = {"Pontos", "Tarifa", "Prazos", "Som", "Alimentos"};
        String[] palavras = {"regra", "prazo", "marca", "festival", "gerador", "vistoria", "credencial", "montadora", "palco", "ativação"};
        Pattern marcador = Pattern.compile("Z\\d+S\\d+L\\d+");
        Random sorteio = new Random(360);
        for (int vez = 0; vez < 250; vez++) {
            List<List<String>> paginasDoDocumento = new ArrayList<>();
            paginasDoDocumento.add(new ArrayList<>());
            java.util.Map<String, Integer> paginaDoMarcador = new java.util.LinkedHashMap<>();
            java.util.Map<String, String> raizDoMarcador = new java.util.HashMap<>();
            // um quarto dos documentos cabe numa página só (como o TXT), e um terço tem os títulos de primeiro nível em caixa alta
            boolean umaPaginaSo = sorteio.nextInt(4) == 0;
            boolean caixaAlta = sorteio.nextInt(3) == 0;
            int[] limiteDaPagina = {umaPaginaSo ? Integer.MAX_VALUE : 4 + sorteio.nextInt(30)};
            int secoes = 1 + sorteio.nextInt(6);
            for (int s = 1; s <= secoes; s++) {
                String raiz = caixaAlta ? nomes[s - 1].toUpperCase(java.util.Locale.ROOT) : s + " " + nomes[s - 1];
                java.util.function.Consumer<String> linha = texto -> {
                    List<String> atual = paginasDoDocumento.get(paginasDoDocumento.size() - 1);
                    atual.add(texto);
                    if (atual.size() >= limiteDaPagina[0]) {
                        paginasDoDocumento.add(new ArrayList<>());
                        limiteDaPagina[0] = umaPaginaSo ? Integer.MAX_VALUE : 4 + sorteio.nextInt(30);
                    }
                };
                linha.accept(caixaAlta ? raiz : s + ". " + nomes[s - 1]);
                int subsecoes = sorteio.nextInt(4);
                for (int j = 0; j <= subsecoes; j++) {
                    if (j > 0) {
                        linha.accept(s + "." + j + " " + subnomes[(j + s) % subnomes.length]);
                    }
                    int corpo = 1 + sorteio.nextInt(j == 0 && subsecoes > 0 ? 3 : 45);
                    for (int n = 1; n <= corpo; n++) {
                        String id = "Z" + s + "S" + j + "L" + n;
                        StringBuilder texto = new StringBuilder();
                        int quantas = 5 + sorteio.nextInt(10);
                        int ondeFicaOMarcador = sorteio.nextInt(quantas);
                        for (int k = 0; k < quantas; k++) {
                            texto.append(k == 0 ? "" : " ").append(k == ondeFicaOMarcador ? id + " " : "").append(palavras[sorteio.nextInt(palavras.length)]);
                        }
                        texto.append(sorteio.nextInt(4) == 0 ? "" : ".");
                        paginaDoMarcador.put(id, paginasDoDocumento.size());
                        raizDoMarcador.put(id, raiz);
                        linha.accept(texto.toString());
                    }
                }
            }
            if (paginasDoDocumento.get(paginasDoDocumento.size() - 1).isEmpty()) {
                paginasDoDocumento.remove(paginasDoDocumento.size() - 1);
            }
            List<Pagina> paginas = paginas(paginasDoDocumento.stream().map(p -> String.join("\n", p)).toArray(String[]::new));
            String quem = "vez " + vez + " (" + paginas.size() + " páginas)";

            List<Trecho> trechos = divisor.dividir(paginas);

            umaPaginaSo(trechos, quem);
            Set<String> vistos = new HashSet<>();
            for (Trecho trecho : trechos) {
                assertThat(trecho.tokens()).as(quem).isLessThanOrEqualTo(DivisorDeTrechos.LIMITE_DE_TOKENS);
                String raizDoTrecho = trecho.secaoCaminho().split(" › ")[0];
                Matcher achado = marcador.matcher(trecho.texto());
                boolean semMarcador = true;
                while (achado.find()) {
                    String id = achado.group();
                    vistos.add(id);
                    assertThat(trecho.paginaInicio()).as(quem + ": a página de " + id).isEqualTo(paginaDoMarcador.get(id));
                    assertThat(raizDoMarcador.get(id)).as(quem + ": o primeiro nível de " + id + " no trecho " + trecho.ordem() + " [" + trecho.secaoCaminho() + "]")
                            .isEqualTo(raizDoTrecho);
                    semMarcador = false;
                }
                assertThat(semMarcador).as(quem + ": trecho " + trecho.ordem() + " sem nenhum marcador").isFalse();
            }
            assertThat(vistos).as(quem + ": marcadores que sumiram").containsExactlyInAnyOrderElementsOf(paginaDoMarcador.keySet());
            confereQueNadaSumiu(paginas);
        }
    }
}
