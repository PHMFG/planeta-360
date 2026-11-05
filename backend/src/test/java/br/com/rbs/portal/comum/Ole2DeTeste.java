package br.com.rbs.portal.comum;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/**
 * Ajuda dos testes do Office antigo (R4.1): contêineres OLE2 de verdade, gerados pelo Apache POI (escopo test), e contêineres
 * escritos à mão, malformados de propósito. Não faz parte da API.
 */
public final class Ole2DeTeste {

    private Ole2DeTeste() {
    }

    public static byte[] doc() {
        return conteinerComFluxo("WordDocument");
    }

    public static byte[] ppt() {
        return conteinerComFluxo("PowerPoint Document");
    }

    public static byte[] xls() {
        try (HSSFWorkbook planilha = new HSSFWorkbook(); ByteArrayOutputStream saida = new ByteArrayOutputStream()) {
            planilha.createSheet("Plano").createRow(0).createCell(0).setCellValue("Cotas");
            planilha.write(saida);
            return saida.toByteArray();
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }
    }

    /** O mesmo arquivo, mas com um storage de VBA no diretório (o nome muda por formato: Macros, _VBA_PROJECT_CUR...). */
    public static byte[] comVba(byte[] original, String storage) {
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(original)); ByteArrayOutputStream saida = new ByteArrayOutputStream()) {
            DirectoryNode projeto = (DirectoryNode) fs.getRoot().createDirectory(storage);
            fs.getRoot().createDocument("PROJECT", new ByteArrayInputStream("ID=\"{00000000}\"".getBytes(StandardCharsets.US_ASCII)));
            projeto.createDocument("dir", new ByteArrayInputStream(new byte[64]));
            fs.writeFilesystem(saida);
            return saida.toByteArray();
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }
    }

    private static byte[] conteinerComFluxo(String fluxo) {
        try (POIFSFileSystem fs = new POIFSFileSystem(); ByteArrayOutputStream saida = new ByteArrayOutputStream()) {
            fs.createDocument(new ByteArrayInputStream(new byte[5000]), fluxo);
            fs.writeFilesystem(saida);
            return saida.toByteArray();
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }
    }

    // ---------------------------------------------------------------------
    // Contêineres escritos à mão (setores de 512 bytes, uma FAT que cobre tudo, o diretório em cadeia)
    // ---------------------------------------------------------------------

    /**
     * Um contêiner com {@code setoresDoDiretorio} setores de diretório encadeados (o primeiro tem a raiz e, se {@code nomeExtra} não
     * for nulo, um stream com esse nome). Com {@code circular}, o último setor aponta de volta para o primeiro. Cabe em até ~13 mil
     * setores (109 setores de FAT).
     */
    public static byte[] escritoAMao(int setoresDoDiretorio, boolean circular, String nomeExtra) {
        int fat = 1;
        while (fat * 128 < fat + setoresDoDiretorio) {
            fat++;
        }
        int total = fat + setoresDoDiretorio;
        byte[] b = new byte[512 * (1 + total)];
        byte[] assinatura = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        System.arraycopy(assinatura, 0, b, 0, 8);
        u16(b, 0x18, 0x3E);
        u16(b, 0x1A, 3);
        u16(b, 0x1C, 0xFFFE);
        u16(b, 0x1E, 9);
        u16(b, 0x20, 6);
        s32(b, 0x2C, fat);
        s32(b, 0x30, fat);            // o primeiro setor do diretório vem logo depois da FAT
        s32(b, 0x38, 4096);
        s32(b, 0x3C, -2);
        s32(b, 0x44, -2);
        Arrays.fill(b, 0x4C, 0x4C + 109 * 4, (byte) 0xFF);
        for (int i = 0; i < fat; i++) {
            s32(b, 0x4C + 4 * i, i);
        }
        int[] tabela = new int[fat * 128];
        Arrays.fill(tabela, -1);
        for (int i = 0; i < fat; i++) {
            tabela[i] = -3;
        }
        for (int i = 0; i < setoresDoDiretorio; i++) {
            tabela[fat + i] = i == setoresDoDiretorio - 1 ? (circular ? fat : -2) : fat + i + 1;
        }
        for (int i = 0; i < tabela.length; i++) {
            s32(b, 512 * (1 + i / 128) + 4 * (i % 128), tabela[i]);
        }
        int inicioDoDiretorio = 512 * (1 + fat);
        entrada(b, inicioDoDiretorio, "Root Entry", 5);
        if (nomeExtra != null) {
            entrada(b, inicioDoDiretorio + 128, nomeExtra, 2);
        }
        return b;
    }

    private static void entrada(byte[] b, int posicao, String nome, int tipo) {
        byte[] bytes = nome.getBytes(StandardCharsets.UTF_16LE);
        System.arraycopy(bytes, 0, b, posicao, bytes.length);
        u16(b, posicao + 64, bytes.length + 2);
        b[posicao + 66] = (byte) tipo;
        b[posicao + 67] = 1;
        s32(b, posicao + 68, -1);
        s32(b, posicao + 72, -1);
        s32(b, posicao + 76, -1);
    }

    /** Muda um inteiro de 32 bits do cabeçalho de um contêiner (para declarar números absurdos). */
    public static byte[] comCabecalho(byte[] original, int posicao, int valor) {
        byte[] copia = original.clone();
        s32(copia, posicao, valor);
        return copia;
    }

    private static void u16(byte[] b, int posicao, int valor) {
        b[posicao] = (byte) valor;
        b[posicao + 1] = (byte) (valor >> 8);
    }

    private static void s32(byte[] b, int posicao, int valor) {
        u16(b, posicao, valor);
        u16(b, posicao + 2, valor >> 16);
    }
}
