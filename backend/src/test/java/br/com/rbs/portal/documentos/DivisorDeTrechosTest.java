package br.com.rbs.portal.documentos;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.comum.TipoDeArquivo;
import br.com.rbs.portal.documentos.DivisorDeTrechos.Trecho;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Linha;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Pagina;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

        assertThat(divisor.dividir(paginas("1. Só título\n1.1 Outro título"))).isEmpty();
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
