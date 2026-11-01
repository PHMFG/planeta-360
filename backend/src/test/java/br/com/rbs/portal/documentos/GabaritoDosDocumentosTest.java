package br.com.rbs.portal.documentos;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.comum.TipoDeArquivo;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Extracao;
import br.com.rbs.portal.documentos.KitDeDocumentos.Documento;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * [ATLAS] O gabarito do kit de documentos (backend/src/main/resources/dev/documentos/gabarito.json) é a verdade sobre o que cada
 * página contém: todo {@code trecho} dele tem de estar no texto que o PDFBox extrai da {@code pagina} indicada. Se o PDF mudar e o
 * gabarito não (ou o contrário), este teste falha. É bloqueante (B): sem ele, os casos do avaliador e a avaliação da busca (M4)
 * cobrariam do Atlas um fato que o documento não tem. O gabarito foi conferido com o pdf.js (ferramentas/documentos-ficticios/
 * conferir.mjs); aqui a conta é a mesma, pelo extrator da API. Espaços e quebras de linha são normalizados nos dois lados.
 */
class GabaritoDosDocumentosTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ExtratorDeTexto extrator = new ExtratorDeTexto();
    private final Map<String, Extracao> extraidos = new HashMap<>();

    /** Espaços e quebras de linha viram um espaço só; o texto é normalizado em NFC. O resto não muda. */
    static String normalizar(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFC).replace(' ', ' ').replaceAll("\\s+", " ").strip();
    }

    private Extracao extracao(String id) {
        return extraidos.computeIfAbsent(id, chave -> {
            Documento documento = KitDeDocumentos.por(chave);
            TipoDeArquivo tipo = TipoDeArquivo.daExtensao(documento.arquivo()).orElseThrow();
            return extrator.extrair(tipo, documento.conteudo());
        });
    }

    private static JsonNode gabarito() throws IOException {
        return JSON.readTree(new ClassPathResource(KitDeDocumentos.GABARITO).getInputStream());
    }

    @Test
    void todoTrechoDoGabaritoEstaNoTextoExtraidoDaPaginaIndicada() throws IOException {
        List<String> falhas = new ArrayList<>();
        int conferidos = 0;
        for (JsonNode fato : gabarito()) {
            if (fato.path("semResposta").asBoolean(false) || fato.path("documento").isNull() || fato.path("trecho").isNull()) {
                continue;   // pergunta que o kit não responde: não há trecho a achar
            }
            String id = fato.path("id").asString();
            int pagina = fato.path("pagina").asInt();
            Extracao extracao = extracao(id);
            if (pagina < 1 || pagina > extracao.totalDePaginas()) {
                falhas.add(fato.path("fato").asString() + ": " + id + " não tem a página " + pagina);
                continue;
            }
            String textoDaPagina = normalizar(extracao.paginas().get(pagina - 1).texto());
            String trecho = normalizar(fato.path("trecho").asString());
            conferidos++;
            if (!textoDaPagina.contains(trecho)) {
                falhas.add(fato.path("fato").asString() + " (" + id + ", página " + pagina + "): \"" + trecho + "\"");
            }
        }
        assertThat(falhas).as("trechos do gabarito que o PDFBox não achou na página indicada").isEmpty();
        assertThat(conferidos).as("fatos conferidos").isGreaterThanOrEqualTo(100);
    }

    @Test
    void aContinuacaoDeUmaFraseQueCruzaAQuebraDePaginaEstaNaPaginaSeguinte() throws IOException {
        List<String> falhas = new ArrayList<>();
        int comQuebra = 0;
        for (JsonNode fato : gabarito()) {
            if (!fato.path("quebraDePagina").asBoolean(false)) {
                continue;
            }
            comQuebra++;
            Extracao extracao = extracao(fato.path("id").asString());
            int pagina = fato.path("pagina").asInt();
            String seguinte = normalizar(extracao.paginas().get(pagina).texto());
            if (!seguinte.contains(normalizar(fato.path("trechoContinuacao").asString()))) {
                falhas.add(fato.path("fato").asString() + ": a continuação não está na página " + (pagina + 1));
            }
        }
        assertThat(comQuebra).as("fatos com quebra de página").isGreaterThanOrEqualTo(1);
        assertThat(falhas).isEmpty();
    }

    @Test
    void oD12NaoTemTextoExtraivelEODocumentoDeTextoTemUmaPaginaSo() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(ExtratorDeTexto.ExtracaoFalhouException.class,
                () -> extrator.extrairPdf(KitDeDocumentos.por("D12").conteudo())).getMessage()).isEqualTo(ExtratorDeTexto.SEM_TEXTO);
        assertThat(extracao("D08").totalDePaginas()).isEqualTo(1);
    }

    @Test
    void asPaginasDoKitSaoAsDoConferirMjs() {
        Map<String, Integer> esperadas = Map.ofEntries(Map.entry("D01", 11), Map.entry("D02", 11), Map.entry("D03", 5),
                Map.entry("D04", 5), Map.entry("D05", 4), Map.entry("D06", 3), Map.entry("D07", 3), Map.entry("D09", 2),
                Map.entry("D10", 7), Map.entry("D11", 2), Map.entry("D13", 1));
        esperadas.forEach((id, paginas) -> assertThat(extracao(id).totalDePaginas()).as(id).isEqualTo(paginas));
    }
}
