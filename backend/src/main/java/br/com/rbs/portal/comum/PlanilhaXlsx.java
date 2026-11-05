package br.com.rbs.portal.comum;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Gravador mínimo de planilha {@code .xlsx} (OOXML), sem dependência: o Apache POI só existe no escopo de teste, onde lê o que
 * este gravador escreve. Atende o R3.2 do plano dos requisitos da RBS. Escreve só o que o Excel, o LibreOffice e o Numbers
 * precisam para abrir: {@code [Content_Types].xml}, {@code _rels/.rels}, o livro, os estilos (normal e negrito) e uma planilha
 * por aba, com a primeira linha em negrito e congelada. [SEGURANCA] Nunca escreve fórmula (nenhum {@code <f>}): todo texto vai
 * como {@code inlineStr}, e o texto que começa com {@code = + - @}, tabulação ou retorno de carro ganha o apóstrofo de
 * {@link Csv#neutralizar}, para a planilha não virar vetor se alguém a salvar de novo em CSV. Caracteres que o XML 1.0 proíbe
 * são descartados. Todo o arquivo fica na memória: serve para exportações limitadas.
 */
public final class PlanilhaXlsx {

    public static final String TIPO_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** O Excel recusa nome de aba com mais de 31 caracteres. */
    static final int LIMITE_DO_NOME_DA_ABA = 31;

    private static final int LARGURA_MAXIMA = 60;

    /** Uma aba: o nome (será saneado) e as linhas; a primeira é o cabeçalho. Células: texto, número ou nulo. */
    public record Aba(String nome, List<List<Object>> linhas) {
    }

    private PlanilhaXlsx() {
    }

    /** Nome de aba válido: sem {@code [ ] : * ? / \}, sem apóstrofo nas pontas, de 1 a 31 caracteres. */
    public static String nomeDeAba(String nome) {
        String limpo = nome == null ? "" : sanearTexto(nome).replaceAll("[\\[\\]:*?/\\\\]", " ").replaceAll("\\s+", " ").trim();
        limpo = limpo.replaceAll("^'+|'+$", "").trim();
        if (limpo.length() > LIMITE_DO_NOME_DA_ABA) {
            limpo = cortar(limpo, LIMITE_DO_NOME_DA_ABA).replaceAll("^'+|'+$", "").trim();
        }
        return limpo.isEmpty() ? "Planilha" : limpo;
    }

    /** O arquivo {@code .xlsx} em bytes. Nomes de aba repetidos (sem diferenciar maiúsculas) ganham " (2)", " (3)"... */
    public static byte[] gerar(List<Aba> abas) {
        if (abas.isEmpty()) {
            throw new IllegalArgumentException("A planilha precisa de ao menos uma aba.");
        }
        List<String> nomes = nomesUnicos(abas);
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(saida, StandardCharsets.UTF_8)) {
            parte(zip, "[Content_Types].xml", tiposDeConteudo(abas.size()));
            parte(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>\
                    </Relationships>""");
            parte(zip, "xl/workbook.xml", livro(nomes));
            parte(zip, "xl/_rels/workbook.xml.rels", relacionamentosDoLivro(abas.size()));
            parte(zip, "xl/styles.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">\
                    <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>\
                    <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>\
                    <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>\
                    <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>\
                    <cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>\
                    <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>\
                    <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>\
                    </styleSheet>""");
            for (int i = 0; i < abas.size(); i++) {
                parte(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", planilha(abas.get(i).linhas()));
            }
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }
        return saida.toByteArray();
    }

    // ---------------------------------------------------------------------
    // Partes do pacote
    // ---------------------------------------------------------------------

    private static void parte(ZipOutputStream zip, String caminho, String conteudo) throws IOException {
        zip.putNextEntry(new ZipEntry(caminho));
        zip.write(conteudo.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String tiposDeConteudo(int abas) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
                <Default Extension="xml" ContentType="application/xml"/>\
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>\
                <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""");
        for (int i = 1; i <= abas; i++) {
            xml.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return xml.append("</Types>").toString();
    }

    private static String livro(List<String> nomes) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" \
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""");
        for (int i = 0; i < nomes.size(); i++) {
            xml.append("<sheet name=\"").append(escapar(nomes.get(i))).append("\" sheetId=\"").append(i + 1)
                    .append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        return xml.append("</sheets></workbook>").toString();
    }

    private static String relacionamentosDoLivro(int abas) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""");
        for (int i = 1; i <= abas; i++) {
            xml.append("<Relationship Id=\"rId").append(i)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                    .append(i).append(".xml\"/>");
        }
        xml.append("<Relationship Id=\"rId").append(abas + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        return xml.append("</Relationships>").toString();
    }

    private static String planilha(List<List<Object>> linhas) {
        int colunas = linhas.stream().mapToInt(List::size).max().orElse(0);
        int[] larguras = new int[colunas];
        StringBuilder dados = new StringBuilder();
        for (int l = 0; l < linhas.size(); l++) {
            dados.append("<row r=\"").append(l + 1).append("\">");
            List<Object> linha = linhas.get(l);
            for (int c = 0; c < linha.size(); c++) {
                Object valor = linha.get(c);
                if (valor == null) {
                    continue;
                }
                String ref = letrasDaColuna(c) + (l + 1);
                String estilo = l == 0 ? " s=\"1\"" : "";
                if (valor instanceof Number numero) {
                    String texto = numero instanceof BigDecimal decimal ? decimal.toPlainString() : numero.toString();
                    dados.append("<c r=\"").append(ref).append('"').append(estilo).append("><v>").append(texto).append("</v></c>");
                    larguras[c] = Math.max(larguras[c], texto.length());
                } else {
                    String texto = sanearTexto(Csv.neutralizar(valor.toString()));
                    dados.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(estilo)
                            .append("><is><t xml:space=\"preserve\">").append(escapar(texto)).append("</t></is></c>");
                    larguras[c] = Math.max(larguras[c], texto.length());
                }
            }
            dados.append("</row>");
        }
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""");
        if (!linhas.isEmpty()) {
            xml.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>"
                    + "</sheetView></sheetViews>");
        }
        if (colunas > 0) {
            xml.append("<cols>");
            for (int c = 0; c < colunas; c++) {
                xml.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"")
                        .append(Math.min(LARGURA_MAXIMA, Math.max(10, larguras[c] + 2))).append("\" customWidth=\"1\"/>");
            }
            xml.append("</cols>");
        }
        return xml.append("<sheetData>").append(dados).append("</sheetData></worksheet>").toString();
    }

    // ---------------------------------------------------------------------
    // Texto
    // ---------------------------------------------------------------------

    private static List<String> nomesUnicos(List<Aba> abas) {
        List<String> nomes = new ArrayList<>();
        Set<String> usados = new HashSet<>();
        for (Aba aba : abas) {
            String base = nomeDeAba(aba.nome());
            String nome = base;
            for (int n = 2; !usados.add(nome.toLowerCase(Locale.ROOT)); n++) {
                String sufixo = " (" + n + ")";
                nome = cortar(base, LIMITE_DO_NOME_DA_ABA - sufixo.length()).trim() + sufixo;
            }
            nomes.add(nome);
        }
        return nomes;
    }

    /** Corta em {@code maximo} caracteres sem partir um par substituto ao meio. */
    private static String cortar(String texto, int maximo) {
        if (texto.length() <= maximo) {
            return texto;
        }
        int fim = maximo;
        if (Character.isHighSurrogate(texto.charAt(fim - 1))) {
            fim--;
        }
        return texto.substring(0, fim);
    }

    /** Tira o que o XML 1.0 não admite: controles (menos tabulação, LF e CR), substitutos soltos e U+FFFE/U+FFFF. */
    static String sanearTexto(String texto) {
        StringBuilder limpo = new StringBuilder(texto.length());
        for (int i = 0; i < texto.length(); ) {
            int ponto = texto.codePointAt(i);
            i += Character.charCount(ponto);
            boolean legal = ponto == 0x9 || ponto == 0xA || ponto == 0xD || (ponto >= 0x20 && ponto <= 0xD7FF)
                    || (ponto >= 0xE000 && ponto <= 0xFFFD) || (ponto >= 0x10000 && ponto <= 0x10FFFF);
            if (legal) {
                limpo.appendCodePoint(ponto);
            }
        }
        return limpo.toString();
    }

    private static String escapar(String texto) {
        return texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    /** 0 = A, 25 = Z, 26 = AA. */
    static String letrasDaColuna(int indice) {
        StringBuilder letras = new StringBuilder();
        for (int n = indice; n >= 0; n = n / 26 - 1) {
            letras.insert(0, (char) ('A' + n % 26));
        }
        return letras.toString();
    }
}
