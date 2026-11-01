package br.com.rbs.portal.documentos;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.comum.TipoDeArquivo;
import br.com.rbs.portal.documentos.DivisorDeTrechos.Trecho;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Linha;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Pagina;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
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
        assertThat(d01).hasSizeBetween(10, 16);
        assertThat(d01).extracting(Trecho::ordem).containsExactlyElementsOf(IntStream.rangeClosed(1, d01.size()).boxed().toList());
        assertThat(d01).allSatisfy(t -> {
            assertThat(t.paginaInicio()).isBetween(1, 11);
            assertThat(t.paginaFim()).isBetween(t.paginaInicio(), 11);
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
    void aFraseQueCruzaAQuebraDePaginaFicaInteiraNoMesmoTrecho() {
        Trecho montagem = comTexto(d01, "A montagem das ativações começa em 18/01/2027");

        // a frase começa no pé da página 3 e termina no topo da 4
        assertThat(montagem.texto()).contains("Estruturas que não estiverem prontas na vistoria seguem para o dia de festival na condição em que estiverem");
        assertThat(montagem.paginaInicio()).isEqualTo(3);
        assertThat(montagem.paginaFim()).isEqualTo(4);
        assertThat(d01.stream().filter(t -> t.texto().contains("seguem para o dia")).count()).isEqualTo(1);
    }

    @Test
    void ocabecalhoEORodapeRepetidosNaoEntramNosTrechos() {
        assertThat(d01).noneMatch(t -> t.texto().contains("DOCUMENTO FICTÍCIO"));
        assertThat(d01).noneMatch(t -> t.texto().matches("(?s).*Página \\d+ de 11.*"));
        assertThat(d01).noneMatch(t -> t.texto().contains("testes do Atlas"));
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

        assertThat(trechos).hasSize(1);
        assertThat(trechos.get(0).texto()).doesNotContain("TOPO FIXO").doesNotContain("Pé fixo");
        assertThat(trechos.get(0).texto()).contains("primeiro texto", "segundo texto", "terceiro texto");
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
    void aMarcaDoTxtNoTopoENoPeSaiComoCabecalhoERodape() {
        assertThat(d08).noneMatch(t -> t.texto().contains("DOCUMENTO FICTÍCIO"));
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
    void seccoesDeRaizesDiferentesSoSeJuntamSeOTrechoAindaNaoTemOMinimo() {
        List<Trecho> trechos = divisor.dividir(paginas("1. Raiz\nTexto da primeira raiz.\n2. Outra raiz\nTexto de outra raiz."));

        assertThat(trechos).hasSize(1);
        assertThat(trechos.get(0).secaoCaminho()).isEqualTo("1 Raiz");
        assertThat(trechos.get(0).texto()).isEqualTo("Texto da primeira raiz.\n2 Outra raiz\nTexto de outra raiz.");
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

    /** Toda palavra e todo número das linhas que sobraram depois do cabeçalho e do rodapé está no texto ou no caminho de algum trecho. */
    private void confereQueNadaSumiu(List<Pagina> paginas) {
        Set<String> faltando = palavrasENumeros(String.join("\n", DivisorDeTrechos.linhasMantidas(paginas)));
        faltando.removeAll(palavrasENumeros(juncao(divisor.dividir(paginas))));
        assertThat(faltando).as("palavras e números do texto que não estão em nenhum trecho nem no caminho de seção").isEmpty();
    }

    @Test
    void listaDeTitulosSeguidosFicaNoTextoENenhumaViraCaminho() {
        List<Pagina> paginas = txt("DOCUMENTOS EXIGIDOS", "1. Contrato assinado", "2. Apólice de seguro de R$ 2.000.000,00", "3. Comprovante de pagamento",
                "ENTREGA", "As peças são entregues pela marca no portal até o prazo do plano comercial.", "",
                "INGRESSOS", "10 Ingressos Pista", "4 Ingressos Camarote", "Os ingressos são distribuídos entre os setores do festival.", "",
                "PRAZO FINAL 15/01/2027", "Sem prorrogação.");

        List<Trecho> trechos = divisor.dividir(paginas);

        assertThat(juncao(trechos)).contains("Contrato assinado", "Apólice de seguro de R$ 2.000.000,00", "Comprovante de pagamento",
                "10 Ingressos Pista", "4 Ingressos Camarote", "PRAZO FINAL 15/01/2027", "Sem prorrogação.");
        assertThat(trechos.stream().map(Trecho::texto).collect(Collectors.joining("\n")))
                .as("o número fica no texto, e não só no caminho").contains("10 Ingressos Pista\n4 Ingressos Camarote", "R$ 2.000.000,00");
        assertThat(trechos).as("lista não é seção: nenhuma linha dela vira caminho").allSatisfy(t -> assertThat(t.secaoCaminho()).isNull());
        confereQueNadaSumiu(paginas);
    }

    @Test
    void tituloSemCorpoAntesDoProximoTituloOuDoFimFicaNoTexto() {
        List<Pagina> paginas = txt("1. Regras", "O texto da regra.", "2. Anexos", "3. Glossário", "O glossário explica os termos usados.", "4. Fim");

        List<Trecho> trechos = divisor.dividir(paginas);

        assertThat(trechos).hasSize(1);
        assertThat(trechos.get(0).secaoCaminho()).isEqualTo("1 Regras");
        assertThat(trechos.get(0).texto()).contains("2. Anexos", "3. Glossário", "4. Fim", "O glossário explica os termos usados.");
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
        List<Pagina> paginas = txt("4. Cotas e ingressos", "A tabela abaixo mostra as cortesias e as credenciais por dia.", "MASTER 40 20", "SUBCOTA G 20 10",
                "SUBCOTA 10 5", "Os valores valem para a edição de 2027.");

        List<Trecho> trechos = divisor.dividir(paginas);

        assertThat(trechos).hasSize(1);
        assertThat(trechos.get(0).secaoCaminho()).isEqualTo("4 Cotas e ingressos");
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
        assertThat(trechos.get(0).secaoCaminho()).isEqualTo("3 Energia");
        assertThat(trechos.get(0).texto()).isEqualTo("3.1 Pontos\n3.2 Tarifa\nA tarifa é de R$ 312,00 por kVA.");
        confereQueNadaSumiu(paginas);
    }

    @Test
    void oSumarioFicaNoTextoEOsTitulosDepoisDeleContinuamComoCaminho() {
        List<Pagina> paginas = txt("CAPA DO MANUAL", "Versão 1.0 do manual.", "", "Sumário", "1. Apresentação", "2. Cronograma operacional", "3. Seguros", "",
                "1. Apresentação", "1.1 Objetivo", "Este manual reúne as regras de operação.", "", "2. Cronograma operacional",
                "Os prazos terminam às 18h.");

        List<Trecho> trechos = divisor.dividir(paginas);

        assertThat(trechos.stream().map(Trecho::texto).collect(Collectors.joining("\n"))).contains("Sumário\n1. Apresentação\n2. Cronograma operacional\n3. Seguros");
        assertThat(trechos.stream().map(Trecho::secaoCaminho)).contains("CAPA DO MANUAL", "1 Apresentação");
        assertThat(juncao(trechos)).contains("1.1 Objetivo", "2 Cronograma operacional");
        confereQueNadaSumiu(paginas);
    }

    @Test
    void nadaDoKitSomeDoIndice() {
        for (KitDeDocumentos.Documento documento : KitDeDocumentos.DOCUMENTOS) {
            if ("D12".equals(documento.id())) {
                continue;   // sem texto extraível
            }
            List<Pagina> paginas = extrair(documento.id());
            Set<String> faltando = palavrasENumeros(String.join("\n", DivisorDeTrechos.linhasMantidas(paginas)));
            faltando.removeAll(palavrasENumeros(juncao(divisor.dividir(paginas))));
            assertThat(faltando).as(documento.id() + ": palavras e números que sumiram").isEmpty();
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
        for (String modelo : List.of("Página %d de 4", "Pág. %d", "%d/4", "%d", "- %d -", "Page %d of 4",
                "Manual de Operações 2027 · Planeta 360 · Página %d de 4")) {
            List<String> mantidas = DivisorDeTrechos.linhasMantidas(quatroPaginas(null, modelo, false));

            assertThat(mantidas).as(modelo).hasSize(16)
                    .noneMatch(linha -> IntStream.rangeClosed(1, 4).anyMatch(n -> linha.equals(modelo.formatted(n))));
        }
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

        assertThat(mantidas).noneMatch(linha -> linha.contains("DOCUMENTO FICTÍCIO"));
        assertThat(mantidas.size()).as("duas linhas por página, nas 11 páginas").isEqualTo(paginas.stream().mapToInt(p -> p.linhas().size()).sum() - 2 * 11);
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
}
