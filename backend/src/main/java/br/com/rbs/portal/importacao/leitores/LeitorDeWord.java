package br.com.rbs.portal.importacao.leitores;

import br.com.rbs.portal.importacao.ConteudoLido;
import br.com.rbs.portal.importacao.LeituraFalhouException;
import br.com.rbs.portal.importacao.LeitorDeConteudo;
import br.com.rbs.portal.importacao.Pedaco;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

/**
 * Lê o Word (.docx) pelo XWPF do Apache POI (E8). [IMPORTACAO] Cada parágrafo vira um pedaço com o localizador "parágrafo N", na
 * ordem em que o documento os apresenta (os parágrafos de dentro de uma tabela não contam nessa numeração); cada linha de tabela
 * vira um pedaço com "tabela T, linha L", e as células da linha vão juntas, separadas por " | ". Antes de abrir, valem os mesmos
 * limites contra zip bomb da planilha. O .doc antigo (OLE2) não é lido.
 */
@Component
public class LeitorDeWord implements LeitorDeConteudo {

    private static final long MAXIMO_DE_ENTRADA = 20L * 1024 * 1024;

    @Override
    public boolean aceita(String extensao) {
        return "docx".equalsIgnoreCase(extensao);
    }

    @Override
    public ConteudoLido ler(Path arquivo) {
        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxEntrySize(MAXIMO_DE_ENTRADA);
        try (InputStream entrada = Files.newInputStream(arquivo); XWPFDocument documento = new XWPFDocument(entrada)) {
            List<Pedaco> pedacos = new ArrayList<>();
            int ordem = 0;
            int paragrafo = 0;
            int tabela = 0;
            for (IBodyElement elemento : documento.getBodyElements()) {
                if (elemento instanceof XWPFParagraph texto) {
                    paragrafo++;
                    String limpo = texto.getText() == null ? "" : texto.getText().strip();
                    if (!limpo.isEmpty()) {
                        pedacos.add(new Pedaco(limpo, "parágrafo " + paragrafo, ++ordem));
                    }
                } else if (elemento instanceof XWPFTable tabelaDoDocumento) {
                    tabela++;
                    List<XWPFTableRow> linhas = tabelaDoDocumento.getRows();
                    for (int r = 0; r < linhas.size(); r++) {
                        String limpo = textoDaLinha(linhas.get(r));
                        if (!limpo.isEmpty()) {
                            pedacos.add(new Pedaco(limpo, "tabela " + tabela + ", linha " + (r + 1), ++ordem));
                        }
                    }
                }
            }
            return new ConteudoLido("DOCX", pedacos, Map.of());
        } catch (IOException | RuntimeException erro) {
            throw new LeituraFalhouException("Não consegui abrir o documento do Word. Confira se o arquivo não está corrompido.", erro);
        }
    }

    private static String textoDaLinha(XWPFTableRow linha) {
        List<String> celulas = new ArrayList<>();
        for (XWPFTableCell celula : linha.getTableCells()) {
            String texto = celula.getText() == null ? "" : celula.getText().strip();
            if (!texto.isEmpty()) {
                celulas.add(texto);
            }
        }
        return String.join(" | ", celulas);
    }
}
