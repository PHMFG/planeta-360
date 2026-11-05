package br.com.rbs.portal.materiais;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.comum.Ole2DeTeste;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * R4.1: o Office antigo ({@code .doc}, {@code .xls} e {@code .ppt}, conteiner OLE2) entra nos envios de arquivo (rotas 17 e 72) só
 * com a assinatura OLE2, o diretório íntegro e sem projeto VBA. O servidor lê apenas o diretório do conteiner. Os arquivos vêm do
 * Apache POI (escopo test) ou são escritos à mão, malformados de propósito. [UPLOAD] Cada teste é desfeito no fim.
 */
@TesteDeIntegracao
@Transactional
class FormatosDoEscritorioTest extends ApoioDeEspacos {

    private static final String FRASE_DE_MACRO =
            "Este arquivo contém macros (VBA), que o portal não aceita. Salve uma cópia sem macros e envie de novo.";
    private static final Duration PRAZO = Duration.ofSeconds(5);

    private static String fraseDeFormato(String extensao) {
        return "O conteúdo do arquivo não é um " + extensao.toUpperCase() + " de verdade. Confira se o arquivo não foi renomeado.";
    }

    private static byte[] semMacro(String extensao) {
        return switch (extensao) {
            case "doc" -> Ole2DeTeste.doc();
            case "xls" -> Ole2DeTeste.xls();
            default -> Ole2DeTeste.ppt();
        };
    }

    private static String storageDeVba(String extensao) {
        return switch (extensao) {
            case "doc" -> "Macros";
            case "xls" -> "_VBA_PROJECT_CUR";
            default -> "VBA";
        };
    }

    private long entregaDaMarca() {
        return criarEntrega(entrar(RBS), amarelo(), "MIDIA", "VT");
    }

    private MvcTestResult naRota17(String nome, byte[] bytes) {
        long entrega = entregaDaMarca();
        return enviar(entrar(AMARELO), entrega, nome, bytes);
    }

    private MvcTestResult naRota72(String nome, byte[] bytes) {
        return enviarPara(entrar(AMARELO), "/api/parcerias/" + amarelo() + "/espacos/DOCUMENTO_INGRESSOS/arquivos", nome, bytes);
    }

    @ParameterizedTest
    @CsvSource({"doc, application/msword", "xls, application/vnd.ms-excel", "ppt, application/vnd.ms-powerpoint"})
    void osTresFormatosSemMacroSaoAceitosNaEntrega(String extensao, String mime) {
        MvcTestResult resposta = naRota17("Plano." + extensao.toUpperCase(), semMacro(extensao));

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(resposta, "$.mimeType")).as("o MIME vem da lista, não do navegador").isEqualTo(mime);
        assertThat(lerJson(resposta, "$.nomeOriginal")).isEqualTo("Plano." + extensao.toUpperCase());
    }

    @ParameterizedTest
    @CsvSource({"doc", "xls", "ppt"})
    void osTresFormatosSemMacroSaoAceitosNosEspacos(String extensao) {
        assertThat(naRota72("ingressos." + extensao, semMacro(extensao))).hasStatus(HttpStatus.CREATED);
    }

    @ParameterizedTest
    @CsvSource({"doc", "xls", "ppt"})
    void comProjetoVbaSaoRecusadosComAFraseDeMacroNasDuasRotas(String extensao) {
        byte[] comMacro = Ole2DeTeste.comVba(semMacro(extensao), storageDeVba(extensao));

        MvcTestResult entrega = naRota17("com-macro." + extensao, comMacro);
        MvcTestResult espaco = naRota72("com-macro." + extensao, comMacro);

        assertThat(entrega).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(lerJson(entrega, "$.detail")).isEqualTo(FRASE_DE_MACRO);
        assertThat(espaco).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(lerJson(espaco, "$.detail")).isEqualTo(FRASE_DE_MACRO);
    }

    @ParameterizedTest
    @CsvSource({"_VBA_PROJECT_CUR", "_VBA_PROJECT", "VBA", "Macros", "vba"})
    void cadaNomeDeVbaNoDiretorioReprova(String nome) {
        byte[] conteiner = Ole2DeTeste.escritoAMao(1, false, nome);

        MvcTestResult resposta = naRota17("macro.doc", conteiner);

        assertThat(resposta).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo(FRASE_DE_MACRO);
    }

    @Test
    void oContainerEscritoAMaoSemVbaPassa() {
        // Garante que os conteineres malformados dos outros testes só falham pelo defeito que têm, não por serem mal escritos.
        assertThat(naRota17("a-mao.doc", Ole2DeTeste.escritoAMao(1, false, "WordDocument"))).hasStatus(HttpStatus.CREATED);
        assertThat(naRota17("a-mao-grande.doc", Ole2DeTeste.escritoAMao(200, false, null)))
                .as("um diretório de 200 setores ainda está dentro do limite").hasStatus(HttpStatus.CREATED);
    }

    @ParameterizedTest
    @CsvSource({"doc", "xls", "ppt"})
    void pngRenomeadoRecebeAFraseDeFormato(String extensao) {
        byte[] png = png();

        MvcTestResult entrega = naRota17("foto." + extensao, png);
        MvcTestResult espaco = naRota72("foto." + extensao, png);

        assertThat(entrega).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(lerJson(entrega, "$.detail")).isEqualTo(fraseDeFormato(extensao));
        assertThat(espaco).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(lerJson(espaco, "$.detail")).isEqualTo(fraseDeFormato(extensao));
    }

    @Test
    void docxRenomeadoParaDocTambemRecebeAFraseDeFormato() {
        MvcTestResult resposta = naRota17("novo.doc", zip());

        assertThat(resposta).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo(fraseDeFormato("doc"));
    }

    @Test
    void diretorioCircularERecusadoSemTravar() {
        byte[] circular = Ole2DeTeste.escritoAMao(3, true, null);

        MvcTestResult resposta = assertTimeout(PRAZO, () -> naRota17("circular.doc", circular));

        assertThat(resposta).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo(fraseDeFormato("doc"));
    }

    @Test
    void setorQueApontaParaSiMesmoERecusado() {
        MvcTestResult resposta = assertTimeout(PRAZO, () -> naRota17("laco.xls", Ole2DeTeste.escritoAMao(1, true, null)));

        assertThat(resposta).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void setoresDeFatEDifatDeMaisSaoRecusadosSemAlocarNada() {
        byte[] base = Ole2DeTeste.doc();
        // 0x2C: quantidade de setores da FAT; 0x48: quantidade de setores da DIFAT; 0x30: primeiro setor do diretório.
        byte[][] malformados = {
                Ole2DeTeste.comCabecalho(base, 0x2C, Integer.MAX_VALUE),
                Ole2DeTeste.comCabecalho(base, 0x2C, 100_000),
                Ole2DeTeste.comCabecalho(base, 0x2C, -5),
                Ole2DeTeste.comCabecalho(base, 0x48, Integer.MAX_VALUE),
                Ole2DeTeste.comCabecalho(Ole2DeTeste.comCabecalho(base, 0x2C, 200), 0x44, 0),
                Ole2DeTeste.comCabecalho(base, 0x30, Integer.MAX_VALUE),
                Ole2DeTeste.comCabecalho(base, 0x30, 1_000_000),
                Ole2DeTeste.comCabecalho(base, 0x1E, 31),
        };
        for (int i = 0; i < malformados.length; i++) {
            byte[] arquivo = malformados[i];
            MvcTestResult resposta = assertTimeout(PRAZO, () -> naRota17("estranho.doc", arquivo));

            assertThat(resposta).as("malformado " + i).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            assertThat(lerJson(resposta, "$.detail")).as("malformado " + i).isEqualTo(fraseDeFormato("doc"));
        }
    }

    @Test
    void cabecalhoCortadoOuSoComAAssinaturaERecusado() {
        byte[] inteiro = Ole2DeTeste.xls();
        byte[] soAssinatura = java.util.Arrays.copyOf(inteiro, 8);
        byte[] cortado = java.util.Arrays.copyOf(inteiro, 600);

        assertThat(naRota17("a.xls", soAssinatura)).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(naRota17("b.xls", cortado)).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void diretorioGiganteERecusadoRapidoSemEstourarAMemoria() {
        // 3.000 setores de diretório encadeados (1,5 MB): bem acima do limite de 1.024 setores.
        byte[] gigante = Ole2DeTeste.escritoAMao(3_000, false, null);

        MvcTestResult resposta = assertTimeout(PRAZO, () -> naRota17("gigante.ppt", gigante));

        assertThat(resposta).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo(fraseDeFormato("ppt"));
    }

    @Test
    void arquivoRecusadoNaoViraEntregaNemVersao() {
        SessaoDeApi marca = entrar(AMARELO);
        long entrega = entregaDaMarca();
        long antes = contar("SELECT count(*) FROM arquivo WHERE entrega_id = ?", entrega);

        enviar(marca, entrega, "com-macro.doc", Ole2DeTeste.comVba(Ole2DeTeste.doc(), "Macros"));
        enviar(marca, entrega, "circular.doc", Ole2DeTeste.escritoAMao(3, true, null));

        assertThat(contar("SELECT count(*) FROM arquivo WHERE entrega_id = ?", entrega)).isEqualTo(antes);
    }
}
