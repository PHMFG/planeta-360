package br.com.rbs.portal.importacao;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * [ATLAS] O corpus fictício do Atlas de entrada é a fonte dos testes dos leitores e da rodada de validação: cada arquivo de
 * backend/src/test/resources/importacao/corpus/ tem o gabarito do que deve ser extraído e de onde cada valor saiu. Este teste
 * confere que os nove arquivos existem, que o JSON é válido, que o .xlsx e o .docx abrem com o Apache POI, que toda célula ou
 * parágrafo citado na origem existe e tem o valor do gabarito, e que o texto, os prints e o PDF têm o que o gabarito promete.
 * Não usa banco: é JUnit puro.
 */
class CorpusDeEntradaTest {

    private static final String PASTA = "importacao/corpus/";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> ARQUIVOS = Set.of(
            "tv-novembro.xlsx",
            "padrao-rbs-outubro.xlsx",
            "radio92-outubro.docx",
            "zh-pagina-dupla.png",
            "post-instagram.png",
            "pesquisa-ativacao.xlsx",
            "numeros-da-edicao.pdf",
            "agencia-amarelo.xlsx",
            "conversa-whatsapp.txt");
    private static final Set<String> DESTINOS = Set.of(
            "VEICULACAO", "COMPROVANTE", "PESQUISA", "NUMEROS_EDICAO", "DADOS_ATIVACAO", "SO_CONSULTA");
    private static final Pattern TELEFONE = Pattern.compile("\\(\\d{2}\\) \\d{4,5}-\\d{4}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PARAGRAFO = Pattern.compile("parágrafo (\\d+)");
    private static final Pattern LINHA = Pattern.compile("linha (\\d+)");

    private static JsonNode gabarito() throws IOException {
        try (InputStream entrada = new ClassPathResource(PASTA + "gabarito.json").getInputStream()) {
            return JSON.readTree(entrada);
        }
    }

    private static byte[] bytes(String arquivo) throws IOException {
        try (InputStream entrada = new ClassPathResource(PASTA + arquivo).getInputStream()) {
            return entrada.readAllBytes();
        }
    }

    private static String normalizar(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFC).replaceAll("\\s+", " ").strip();
    }

    @Test
    void oGabaritoListaOsNoveArquivosComDestinoEOrigem() throws IOException {
        JsonNode arquivos = gabarito().path("arquivos");
        assertThat(arquivos.isArray()).as("gabarito.arquivos").isTrue();
        assertThat(arquivos.size()).as("arquivos do gabarito").isEqualTo(ARQUIVOS.size());

        Set<String> vistos = new HashSet<>();
        for (JsonNode arquivo : arquivos) {
            String nome = arquivo.path("arquivo").asString();
            vistos.add(nome);
            assertThat(ARQUIVOS).as("arquivo previsto no corpus").contains(nome);
            assertThat(DESTINOS).as(nome + ": destino").contains(arquivo.path("destino").asString());
            assertThat(new ClassPathResource(PASTA + nome).exists()).as(nome + " existe").isTrue();
            assertThat(arquivo.path("temDadoPessoal").asBoolean())
                    .as(nome + ": só a conversa tem dado pessoal")
                    .isEqualTo("conversa-whatsapp.txt".equals(nome));
            assertThat(arquivo.path("linhas").isArray()).as(nome + ": linhas").isTrue();
            assertThat(arquivo.path("naoEntra").isArray()).as(nome + ": naoEntra").isTrue();
            for (JsonNode linha : arquivo.path("linhas")) {
                assertThat(linha.has("dados")).as(nome + ": dados da linha").isTrue();
                assertThat(linha.path("origem").properties()).as(nome + ": origem da linha").isNotEmpty();
                for (var campo : linha.path("origem").properties()) {
                    assertThat(campo.getValue().asString()).as(nome + ": origem de " + campo.getKey()).isNotBlank();
                }
            }
            for (JsonNode nao : arquivo.path("naoEntra")) {
                assertThat(nao.path("o").asString()).as(nome + ": o que não entra").isNotBlank();
                assertThat(nao.path("motivo").asString()).as(nome + ": motivo").isNotBlank();
            }
        }
        assertThat(vistos).containsExactlyInAnyOrderElementsOf(ARQUIVOS);
    }

    @Test
    void asCelulasCitadasNasPlanilhasExistemEConferemComOGabarito() throws IOException {
        List<String> falhas = new ArrayList<>();
        int conferidas = 0;
        for (JsonNode arquivo : gabarito().path("arquivos")) {
            String nome = arquivo.path("arquivo").asString();
            if (!nome.endsWith(".xlsx")) {
                continue;
            }
            try (XSSFWorkbook planilha = new XSSFWorkbook(new ByteArrayInputStream(bytes(nome)))) {
                for (JsonNode linha : arquivo.path("linhas")) {
                    JsonNode dados = linha.path("dados");
                    for (var campo : linha.path("origem").properties()) {
                        String local = campo.getValue().asString();
                        String[] partes = local.split("!", 2);
                        if (partes.length != 2) {
                            falhas.add(nome + ": origem sem aba e célula: " + local);
                            continue;
                        }
                        Sheet aba = planilha.getSheet(partes[0]);
                        if (aba == null) {
                            falhas.add(nome + ": a aba \"" + partes[0] + "\" não existe");
                            continue;
                        }
                        CellReference referencia;
                        try {
                            referencia = new CellReference(partes[1]);
                        } catch (RuntimeException erro) {
                            falhas.add(nome + ": célula inválida: " + local);
                            continue;
                        }
                        Row linhaDaPlanilha = aba.getRow(referencia.getRow());
                        Cell celula = linhaDaPlanilha == null ? null : linhaDaPlanilha.getCell(referencia.getCol());
                        if (celula == null) {
                            falhas.add(nome + ": a célula " + local + " não existe");
                            continue;
                        }
                        JsonNode esperado = dados.path(campo.getKey());
                        if (esperado.isMissingNode() || esperado.isNull()) {
                            continue;
                        }
                        conferidas++;
                        if (esperado.isNumber()) {
                            if (celula.getCellType() != CellType.NUMERIC) {
                                falhas.add(nome + ": " + local + " devia ser número, é " + celula.getCellType());
                            } else if (Math.abs(celula.getNumericCellValue() - esperado.asDouble()) > 1e-9) {
                                falhas.add(nome + ": " + local + " tem " + celula.getNumericCellValue()
                                        + ", o gabarito diz " + esperado.asDouble());
                            }
                        } else {
                            if (celula.getCellType() != CellType.STRING) {
                                falhas.add(nome + ": " + local + " devia ser texto, é " + celula.getCellType());
                            } else if (!celula.getStringCellValue().equals(esperado.asString())) {
                                falhas.add(nome + ": " + local + " tem \"" + celula.getStringCellValue()
                                        + "\", o gabarito diz \"" + esperado.asString() + "\"");
                            }
                        }
                    }
                }
            }
        }
        assertThat(falhas).as("células do gabarito").isEmpty();
        assertThat(conferidas).as("células conferidas").isGreaterThanOrEqualTo(100);
    }

    @Test
    void osParagrafosCitadosNoWordExistemETemAQuantidade() throws IOException {
        List<String> falhas = new ArrayList<>();
        int conferidos = 0;
        for (JsonNode arquivo : gabarito().path("arquivos")) {
            String nome = arquivo.path("arquivo").asString();
            if (!nome.endsWith(".docx")) {
                continue;
            }
            try (XWPFDocument documento = new XWPFDocument(new ByteArrayInputStream(bytes(nome)))) {
                List<XWPFParagraph> paragrafos = documento.getParagraphs();
                for (JsonNode linha : arquivo.path("linhas")) {
                    for (var campo : linha.path("origem").properties()) {
                        Matcher achado = PARAGRAFO.matcher(campo.getValue().asString());
                        if (!achado.matches()) {
                            falhas.add(nome + ": origem que não é parágrafo: " + campo.getValue().asString());
                            continue;
                        }
                        int numero = Integer.parseInt(achado.group(1));
                        if (numero < 1 || numero > paragrafos.size()) {
                            falhas.add(nome + ": o parágrafo " + numero + " não existe (há " + paragrafos.size() + ")");
                            continue;
                        }
                        conferidos++;
                        if (paragrafos.get(numero - 1).getText().isBlank()) {
                            falhas.add(nome + ": o parágrafo " + numero + " está vazio");
                        }
                    }
                    JsonNode quantidade = linha.path("dados").path("quantidade");
                    Matcher achado = PARAGRAFO.matcher(linha.path("origem").path("quantidade").asString());
                    if (quantidade.isNumber() && achado.matches()) {
                        int numero = Integer.parseInt(achado.group(1));
                        String texto = paragrafos.get(numero - 1).getText();
                        if (!texto.contains(String.valueOf(quantidade.asInt()))) {
                            falhas.add(nome + ": o parágrafo " + numero + " não traz a quantidade " + quantidade.asInt());
                        }
                    }
                }
            }
        }
        assertThat(falhas).as("parágrafos do Word").isEmpty();
        assertThat(conferidos).as("origens conferidas no Word").isGreaterThan(0);
    }

    @Test
    void aConversaTemMensagensDadoPessoalEAsLinhasCitadas() throws IOException {
        String conversa = new String(bytes("conversa-whatsapp.txt"), StandardCharsets.UTF_8);
        List<String> mensagens = conversa.lines().filter((linha) -> !linha.isBlank()).toList();
        assertThat(mensagens).as("mensagens da conversa").hasSizeBetween(10, 15);
        assertThat(conversa).as("telefone fictício").containsPattern(TELEFONE);
        assertThat(conversa).as("e-mail fictício").containsPattern(EMAIL);
        assertThat(conversa).as("formato de exportação do WhatsApp")
                .containsPattern("\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2} - .+: .+");

        for (JsonNode arquivo : gabarito().path("arquivos")) {
            if (!"conversa-whatsapp.txt".equals(arquivo.path("arquivo").asString())) {
                continue;
            }
            for (JsonNode nao : arquivo.path("naoEntra")) {
                Matcher achado = LINHA.matcher(nao.path("o").asString());
                if (achado.matches()) {
                    assertThat(Integer.parseInt(achado.group(1))).as("linha citada em naoEntra")
                            .isBetween(1, mensagens.size());
                }
            }
        }
    }

    @Test
    void osPrintsSaoPngValidosEOsPdfTemOsNumerosEAsFontes() throws IOException {
        for (String nome : List.of("zh-pagina-dupla.png", "post-instagram.png")) {
            byte[] imagem = bytes(nome);
            assertThat(imagem.length).as(nome + ": tamanho").isGreaterThan(10_000);
            assertThat(imagem[0] & 0xff).as(nome + ": assinatura PNG").isEqualTo(0x89);
            assertThat(new String(imagem, 1, 3, StandardCharsets.ISO_8859_1)).isEqualTo("PNG");
            BufferedImage lida = ImageIO.read(new ByteArrayInputStream(imagem));
            assertThat(lida).as(nome + ": imagem legível").isNotNull();
            assertThat(lida.getWidth()).as(nome + ": largura").isPositive();
            assertThat(lida.getHeight()).as(nome + ": altura").isPositive();
        }

        byte[] pdf = bytes("numeros-da-edicao.pdf");
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).as("assinatura do PDF").isEqualTo("%PDF-");
        try (PDDocument documento = Loader.loadPDF(pdf)) {
            String texto = normalizar(new PDFTextStripper().getText(documento));
            assertThat(texto).as("números da edição").contains(
                    "+80 mil planetários",
                    "39 marcas parceiras",
                    "+19 milhões de impactos nas redes",
                    "1,6 milhão nas transmissões");
            for (JsonNode arquivo : gabarito().path("arquivos")) {
                if (!"numeros-da-edicao.pdf".equals(arquivo.path("arquivo").asString())) {
                    continue;
                }
                for (JsonNode linha : arquivo.path("linhas")) {
                    String fonte = linha.path("dados").path("fonte").asString();
                    assertThat(texto).as("fonte " + fonte + " no PDF").contains(fonte);
                }
            }
        }
    }

    @Test
    void oPerfilDaPesquisaSomaCemEmCadaDimensao() throws IOException {
        Map<String, Integer> somas = new LinkedHashMap<>();
        for (JsonNode arquivo : gabarito().path("arquivos")) {
            if (!"pesquisa-ativacao.xlsx".equals(arquivo.path("arquivo").asString())) {
                continue;
            }
            for (JsonNode linha : arquivo.path("linhas")) {
                JsonNode dados = linha.path("dados");
                if (!"PERFIL".equals(dados.path("tipo").asString())) {
                    continue;
                }
                String marca = dados.path("patrocinador").isNull() ? "GERAL" : dados.path("patrocinador").asString();
                String chave = marca + " · " + dados.path("dimensao").asString();
                somas.merge(chave, dados.path("valor").asInt(), Integer::sum);
                assertThat(dados.path("base_respondentes").asInt()).as("base de " + chave).isPositive();
            }
        }
        assertThat(somas).as("dimensões do perfil").hasSize(8);
        somas.forEach((chave, soma) -> assertThat(soma).as(chave + " soma 100").isEqualTo(100));
    }

    @Test
    void aPesquisaTemAsTresPerguntasEOCincoTextosLivres() throws IOException {
        Set<Integer> perguntas = new HashSet<>();
        List<String> textos = new ArrayList<>();
        for (JsonNode arquivo : gabarito().path("arquivos")) {
            if (!"pesquisa-ativacao.xlsx".equals(arquivo.path("arquivo").asString())) {
                continue;
            }
            for (JsonNode linha : arquivo.path("linhas")) {
                JsonNode dados = linha.path("dados");
                if (!"RESPOSTA".equals(dados.path("tipo").asString())) {
                    continue;
                }
                perguntas.add(dados.path("pergunta").asInt());
                if (dados.has("texto")) {
                    textos.add(dados.path("texto").asString());
                }
            }
        }
        assertThat(perguntas).as("perguntas da pesquisa da Master").containsExactlyInAnyOrder(1, 2, 3, 4);
        assertThat(textos).as("respostas de texto livre").hasSize(5).allSatisfy((texto) -> assertThat(texto).isNotBlank());
    }
}
