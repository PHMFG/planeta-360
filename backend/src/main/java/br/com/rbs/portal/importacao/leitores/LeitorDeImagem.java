package br.com.rbs.portal.importacao.leitores;

import br.com.rbs.portal.atlas.provedor.ProvedorAtual;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.Imagem;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.MensagemDeChat;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.PedidoAoLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm;
import br.com.rbs.portal.comum.TipoDeArquivo;
import br.com.rbs.portal.importacao.ConteudoLido;
import br.com.rbs.portal.importacao.LeituraFalhouException;
import br.com.rbs.portal.importacao.LeitorDeConteudo;
import br.com.rbs.portal.importacao.Pedaco;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

/**
 * Lê a imagem (.png e .jpg) e o PDF escaneado (E9). [IMPORTACAO] São duas chamadas: primeiro o modelo com visão transcreve o texto da
 * imagem; depois o {@code ExtratorLivre} trabalha só sobre a transcrição, como no texto corrido. A origem de cada valor aponta para a
 * imagem ("imagem") ou para a página do PDF escaneado ("página N"). O provedor sem visão recusa com a frase própria, e a importação
 * vai a ERRO.
 */
@Component
public class LeitorDeImagem implements LeitorDeConteudo {

    /** O teto de páginas de um PDF escaneado: cada página é uma chamada de visão. */
    static final int MAXIMO_DE_PAGINAS = 50;
    static final String SEM_VISAO = "Este provedor não lê imagem.";
    static final String SEM_MODELO = "A leitura de imagem precisa do modelo de linguagem, e ele está desligado agora.";

    private final ProvedorAtual provedorAtual;

    public LeitorDeImagem(ProvedorAtual provedorAtual) {
        this.provedorAtual = provedorAtual;
    }

    @Override
    public boolean aceita(String extensao) {
        return "png".equalsIgnoreCase(extensao) || "jpg".equalsIgnoreCase(extensao) || "jpeg".equalsIgnoreCase(extensao);
    }

    @Override
    public ConteudoLido ler(Path arquivo) {
        byte[] conteudo;
        try {
            conteudo = Files.readAllBytes(arquivo);
        } catch (IOException erro) {
            throw new LeituraFalhouException("Não consegui ler a imagem.", erro);
        }
        String extensao = TipoDeArquivo.extensaoDe(arquivo.getFileName().toString());
        String tipo = extensao.toUpperCase(Locale.ROOT);
        String mime = "png".equalsIgnoreCase(extensao) ? "image/png" : "image/jpeg";
        return new ConteudoLido(tipo, emPedacos(transcrever(conteudo, mime), "imagem"), Map.of("imagem", true));
    }

    /**
     * Lê um PDF sem texto (escaneado): renderiza cada página e a manda ao modelo de visão. O localizador é "página N", o mesmo que a
     * rota 116 devolve.
     */
    public ConteudoLido lerPdfEscaneado(Path arquivo) {
        byte[] conteudo;
        try {
            conteudo = Files.readAllBytes(arquivo);
        } catch (IOException erro) {
            throw new LeituraFalhouException("Não consegui ler o arquivo PDF.", erro);
        }
        try (PDDocument documento = Loader.loadPDF(conteudo)) {
            int paginas = documento.getNumberOfPages();
            if (paginas > MAXIMO_DE_PAGINAS) {
                throw new LeituraFalhouException("O PDF escaneado tem " + paginas + " páginas; o limite é " + MAXIMO_DE_PAGINAS + ".");
            }
            PDFRenderer renderizador = new PDFRenderer(documento);
            List<Pedaco> pedacos = new ArrayList<>();
            int ordem = 0;
            for (int pagina = 0; pagina < paginas; pagina++) {
                BufferedImage imagem = renderizador.renderImageWithDPI(pagina, 150);
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                ImageIO.write(imagem, "png", png);
                for (String linha : linhas(transcrever(png.toByteArray(), "image/png"))) {
                    pedacos.add(new Pedaco(linha, "página " + (pagina + 1), ++ordem));
                }
            }
            return new ConteudoLido("PDF", pedacos, Map.of("imagem", true));
        } catch (LeituraFalhouException falha) {
            throw falha;
        } catch (IOException | RuntimeException erro) {
            throw new LeituraFalhouException("Não consegui ler o PDF escaneado. Confira se o arquivo não está corrompido.", erro);
        }
    }

    /** A transcrição do modelo com visão; falha com a frase própria quando não há modelo ou ele não lê imagem. */
    String transcrever(byte[] imagem, String mime) {
        Optional<ProvedorDeLlm> provedor = provedorAtual.provedor();
        if (provedor.isEmpty()) {
            throw new LeituraFalhouException(SEM_MODELO);
        }
        if (!provedor.get().leImagem()) {
            throw new LeituraFalhouException(SEM_VISAO);
        }
        String sistema = """
                Você transcreve imagens de comprovantes e relatórios de mídia do portal Planeta 360. Devolva só o texto que está na
                imagem, linha a linha, preservando marcas, veículos, formatos, datas e números como aparecem. Não comente, não
                resuma e não invente nada que não esteja escrito.
                """;
        PedidoAoLlm pedido = new PedidoAoLlm("importacao-imagem", List.of(MensagemDeChat.sistema(sistema),
                MensagemDeChat.usuario("Transcreva esta imagem.")), List.of(),
                new Imagem(mime, Base64.getEncoder().encodeToString(imagem)));
        RespostaDoLlm resposta;
        try {
            resposta = provedor.get().responder(pedido);
        } catch (RuntimeException falha) {
            throw new LeituraFalhouException("O modelo de linguagem não respondeu à leitura da imagem.", falha);
        }
        String texto = resposta.texto() == null ? "" : resposta.texto().strip();
        if (texto.isEmpty()) {
            throw new LeituraFalhouException("Não consegui transcrever a imagem: o modelo não devolveu texto.");
        }
        return texto;
    }

    private static List<Pedaco> emPedacos(String transcricao, String localizador) {
        List<Pedaco> pedacos = new ArrayList<>();
        int ordem = 0;
        for (String linha : linhas(transcricao)) {
            pedacos.add(new Pedaco(linha, localizador, ++ordem));
        }
        return pedacos;
    }

    private static List<String> linhas(String texto) {
        List<String> linhas = new ArrayList<>();
        for (String linha : texto.split("\\R")) {
            String limpa = linha.strip();
            if (!limpa.isEmpty()) {
                linhas.add(limpa);
            }
        }
        return linhas;
    }
}
