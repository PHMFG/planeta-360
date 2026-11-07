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
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;

/**
 * Lê o PowerPoint (.pptx) pelo XSLF do Apache POI (E8). [IMPORTACAO] Cada forma com texto vira um pedaço com o localizador
 * "slide N"; cada linha de uma tabela vira um pedaço com "slide N, tabela T, linha L", com as células separadas por " | ". Antes de
 * abrir, valem os mesmos limites contra zip bomb da planilha. O .ppt antigo (OLE2) não é lido.
 */
@Component
public class LeitorDePowerPoint implements LeitorDeConteudo {

    private static final long MAXIMO_DE_ENTRADA = 20L * 1024 * 1024;

    @Override
    public boolean aceita(String extensao) {
        return "pptx".equalsIgnoreCase(extensao);
    }

    @Override
    public ConteudoLido ler(Path arquivo) {
        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxEntrySize(MAXIMO_DE_ENTRADA);
        try (InputStream entrada = Files.newInputStream(arquivo); XMLSlideShow apresentacao = new XMLSlideShow(entrada)) {
            List<Pedaco> pedacos = new ArrayList<>();
            int ordem = 0;
            int numeroDoSlide = 0;
            for (XSLFSlide slide : apresentacao.getSlides()) {
                numeroDoSlide++;
                int tabela = 0;
                for (XSLFShape forma : slide.getShapes()) {
                    if (forma instanceof XSLFTextShape texto) {
                        String limpo = texto.getText() == null ? "" : texto.getText().strip();
                        if (!limpo.isEmpty()) {
                            pedacos.add(new Pedaco(limpo, "slide " + numeroDoSlide, ++ordem));
                        }
                    } else if (forma instanceof XSLFTable tabelaDaForma) {
                        tabela++;
                        List<XSLFTableRow> linhas = tabelaDaForma.getRows();
                        for (int r = 0; r < linhas.size(); r++) {
                            String limpo = textoDaLinha(linhas.get(r));
                            if (!limpo.isEmpty()) {
                                pedacos.add(new Pedaco(limpo, "slide " + numeroDoSlide + ", tabela " + tabela + ", linha " + (r + 1),
                                        ++ordem));
                            }
                        }
                    }
                }
            }
            return new ConteudoLido("PPTX", pedacos, Map.of());
        } catch (IOException | RuntimeException erro) {
            throw new LeituraFalhouException("Não consegui abrir a apresentação do PowerPoint. Confira se o arquivo não está "
                    + "corrompido.", erro);
        }
    }

    private static String textoDaLinha(XSLFTableRow linha) {
        List<String> celulas = new ArrayList<>();
        for (XSLFTableCell celula : linha.getCells()) {
            String texto = celula.getText() == null ? "" : celula.getText().strip();
            if (!texto.isEmpty()) {
                celulas.add(texto);
            }
        }
        return String.join(" | ", celulas);
    }
}
