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
import java.util.Locale;
import java.util.Map;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Component;

/**
 * Lê .xlsx e .xls pelo Apache POI (E6). [IMPORTACAO] Antes de abrir, põe os limites contra zip bomb: a proporção mínima de
 * compressão (0,01) e 20 MB por entrada. Depois, no máximo 20 abas e 50.000 células por aba. Nenhuma fórmula é avaliada: o valor
 * vem do cache da célula; a célula só com fórmula vira o próprio texto da fórmula.
 */
@Component
public class LeitorDePlanilha implements LeitorDeConteudo {

    static final int MAXIMO_DE_ABAS = 20;
    static final int MAXIMO_DE_CELULAS_POR_ABA = 50_000;
    private static final long MAXIMO_DE_ENTRADA = 20L * 1024 * 1024;

    /** Uma aba lida: o nome e as linhas, cada uma com o texto de cada célula (vazio quando não há célula). */
    public record AbaLida(String nome, List<List<String>> linhas) {
    }

    @Override
    public boolean aceita(String extensao) {
        return "xlsx".equalsIgnoreCase(extensao) || "xls".equalsIgnoreCase(extensao);
    }

    @Override
    public ConteudoLido ler(Path arquivo) {
        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxEntrySize(MAXIMO_DE_ENTRADA);
        // Um formatador por leitura: o DataFormatter guarda estado e o bean é compartilhado por duas threads da fila.
        DataFormatter formato = new DataFormatter(Locale.forLanguageTag("pt-BR"));
        try (InputStream entrada = Files.newInputStream(arquivo); Workbook livro = WorkbookFactory.create(entrada)) {
            if (livro.getNumberOfSheets() > MAXIMO_DE_ABAS) {
                throw new LeituraFalhouException("A planilha tem " + livro.getNumberOfSheets() + " abas; o limite é "
                        + MAXIMO_DE_ABAS + ".");
            }
            List<AbaLida> abas = new ArrayList<>();
            List<Pedaco> pedacos = new ArrayList<>();
            int ordem = 0;
            for (int s = 0; s < livro.getNumberOfSheets(); s++) {
                Sheet aba = livro.getSheetAt(s);
                if (aba == null) {
                    continue;
                }
                int ultimaLinha = aba.getLastRowNum();
                int celulas = 0;
                int ultimaColuna = 0;
                for (int r = 0; r <= ultimaLinha; r++) {
                    Row linha = aba.getRow(r);
                    if (linha == null) {
                        continue;
                    }
                    celulas += Math.max(0, linha.getLastCellNum());
                    ultimaColuna = Math.max(ultimaColuna, Math.max(0, linha.getLastCellNum()));
                    if (celulas > MAXIMO_DE_CELULAS_POR_ABA) {
                        throw new LeituraFalhouException("A aba \"" + aba.getSheetName() + "\" passa de "
                                + MAXIMO_DE_CELULAS_POR_ABA + " células.");
                    }
                }
                List<List<String>> linhas = new ArrayList<>();
                for (int r = 0; r <= ultimaLinha; r++) {
                    Row linha = aba.getRow(r);
                    List<String> valores = new ArrayList<>(ultimaColuna);
                    for (int c = 0; c < ultimaColuna; c++) {
                        Cell celula = linha == null ? null : linha.getCell(c);
                        String valor = valorDaCelula(celula, formato);
                        if (valor.isBlank()) {
                            valores.add("");
                            continue;
                        }
                        valores.add(valor);
                        String localizador = aba.getSheetName() + "!" + new CellReference(r, c).formatAsString();
                        pedacos.add(new Pedaco(valor, localizador, ++ordem));
                    }
                    linhas.add(valores);
                }
                abas.add(new AbaLida(aba.getSheetName(), linhas));
            }
            return new ConteudoLido("XLSX", pedacos, Map.of("abas", abas));
        } catch (LeituraFalhouException falha) {
            throw falha;
        } catch (IOException | RuntimeException erro) {
            throw new LeituraFalhouException("Não consegui abrir a planilha. Confira se o arquivo não está corrompido.", erro);
        }
    }

    /** O texto da célula, sem avaliar fórmula: o valor em cache ou, sem cache, a própria fórmula. */
    private String valorDaCelula(Cell celula, DataFormatter formato) {
        if (celula == null) {
            return "";
        }
        if (celula.getCellType() == CellType.FORMULA) {
            return switch (celula.getCachedFormulaResultType()) {
                case STRING -> celula.getStringCellValue();
                case NUMERIC, BOOLEAN -> formato.formatCellValue(celula);
                default -> "=" + celula.getCellFormula();
            };
        }
        return formato.formatCellValue(celula).strip();
    }

    /** As abas de um conteúdo lido por este leitor. */
    @SuppressWarnings("unchecked")
    public static List<AbaLida> abas(ConteudoLido conteudo) {
        Object abas = conteudo.estrutura().get("abas");
        return abas instanceof List<?> lista ? (List<AbaLida>) lista : List.of();
    }
}
