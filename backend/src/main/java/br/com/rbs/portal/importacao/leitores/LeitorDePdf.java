package br.com.rbs.portal.importacao.leitores;

import br.com.rbs.portal.documentos.ExtratorDeTexto;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Extracao;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Pagina;
import br.com.rbs.portal.importacao.ConteudoLido;
import br.com.rbs.portal.importacao.LeituraFalhouException;
import br.com.rbs.portal.importacao.LeitorDeConteudo;
import br.com.rbs.portal.importacao.Pedaco;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Lê o PDF com texto pelo {@link ExtratorDeTexto} que os documentos do Atlas já usam (E8). [IMPORTACAO] Cada linha de cada página vira
 * um pedaço com o localizador "página N, linha L"; a numeração da linha é a da leitura do PDFBox naquela página. O PDF sem texto
 * (escaneado), com senha ou corrompido falha com a frase do {@link ExtratorDeTexto}, e quem chama decide se tenta a leitura por
 * imagem (E9).
 */
@Component
public class LeitorDePdf implements LeitorDeConteudo {

    private final ExtratorDeTexto extrator;

    public LeitorDePdf(ExtratorDeTexto extrator) {
        this.extrator = extrator;
    }

    @Override
    public boolean aceita(String extensao) {
        return "pdf".equalsIgnoreCase(extensao);
    }

    @Override
    public ConteudoLido ler(Path arquivo) {
        byte[] conteudo;
        try {
            conteudo = Files.readAllBytes(arquivo);
        } catch (IOException erro) {
            throw new LeituraFalhouException("Não consegui ler o arquivo PDF.", erro);
        }
        Extracao extracao;
        try {
            extracao = extrator.extrairPdf(conteudo);
        } catch (ExtratorDeTexto.ExtracaoFalhouException falha) {
            throw new LeituraFalhouException(falha.getMessage(), falha);
        }
        List<Pedaco> pedacos = new ArrayList<>();
        int ordem = 0;
        for (Pagina pagina : extracao.paginas()) {
            int linha = 0;
            for (ExtratorDeTexto.Linha texto : pagina.linhas()) {
                linha++;
                String limpo = texto.texto() == null ? "" : texto.texto().strip();
                if (!limpo.isEmpty()) {
                    pedacos.add(new Pedaco(limpo, "página " + pagina.numero() + ", linha " + linha, ++ordem));
                }
            }
        }
        return new ConteudoLido("PDF", pedacos, Map.of());
    }
}
