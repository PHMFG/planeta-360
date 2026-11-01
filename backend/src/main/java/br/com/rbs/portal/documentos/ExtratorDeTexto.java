package br.com.rbs.portal.documentos;

import br.com.rbs.portal.comum.TipoDeArquivo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

/**
 * Tira o texto de um documento do Atlas, uma página por vez (M3 do plano do Atlas completo). [ATLAS] PDF pelo PDFBox, dentro da JVM:
 * o texto de cada página sai de {@code setStartPage}/{@code setEndPage} (base 1, inclusivo). TXT é UTF-8 e conta como uma página só
 * (a página 1), cortado depois por títulos. Não há OCR: PDF sem texto (uma página só de imagem), com senha ou corrompido não vira
 * trecho, e quem chama grava o motivo no documento ({@link ExtracaoFalhouException}, com frase pronta para a tela).
 * Não guarda nada nem abre transação: é uma conta sobre os bytes.
 *
 * <p>O PDFBox não marca tabela. Por isso cada linha leva a posição horizontal de cada pedaço dela, e as linhas de uma tabela (uma
 * sequência de linhas com as colunas alinhadas, depois de uma legenda "Tabela N" ou com recuo) saem com {@link Linha#tabela()}:
 * o {@link DivisorDeTrechos} mantém a tabela inteira num trecho.
 */
@Component
public class ExtratorDeTexto {

    /** O PDF maior que isto não é indexado (a extração e os embeddings de um livro inteiro não servem a um manual de operação). */
    public static final int LIMITE_DE_PAGINAS = 300;

    public static final String SEM_TEXTO = "Sem texto extraível; OCR fora do escopo.";

    /** Quanto o primeiro pedaço de uma linha precisa estar à direita da margem (em pontos) para contar como recuo de tabela. */
    private static final double RECUO_DE_TABELA = 3.0;
    /** A tolerância (em pontos) para duas colunas estarem alinhadas. */
    private static final double ALINHAMENTO = 2.0;

    /** Uma linha do texto. {@code colunas} traz a posição horizontal de cada pedaço dela (vazia no TXT). */
    public record Linha(String texto, double x, List<Double> colunas, boolean tabela) {

        Linha comoTabela() {
            return new Linha(texto, x, colunas, true);
        }
    }

    /** O texto de uma página (base 1), linha a linha. Página sem texto vem sem linhas. */
    public record Pagina(int numero, List<Linha> linhas) {

        /** O texto da página, uma linha por linha do PDF. */
        public String texto() {
            return linhas.stream().map(Linha::texto).collect(Collectors.joining("\n"));
        }
    }

    /**
     * O texto de todas as páginas. {@code milissegundos} é o tempo da extração. A lista nunca é vazia e tem ao menos uma página
     * com texto.
     */
    public record Extracao(List<Pagina> paginas, long milissegundos) {

        public int totalDePaginas() {
            return paginas.size();
        }
    }

    /** O motivo, em português, de o documento não poder ser lido: vai para {@code documento.erro} como está. */
    public static class ExtracaoFalhouException extends RuntimeException {

        private final Integer paginas;

        public ExtracaoFalhouException(String motivo, Integer paginas) {
            super(motivo);
            this.paginas = paginas;
        }

        /** Quantas páginas o PDF tem, quando deu para saber; nulo se o arquivo nem abriu. */
        public Integer paginas() {
            return paginas;
        }
    }

    /** Extrai pelo tipo do arquivo: PDF ou TXT (os dois únicos aceitos na rota 54). */
    public Extracao extrair(TipoDeArquivo tipo, byte[] conteudo) {
        return switch (tipo) {
            case PDF -> extrairPdf(conteudo);
            case TXT -> extrairTxt(conteudo);
            default -> throw new ExtracaoFalhouException("Este tipo de arquivo não é indexado.", null);
        };
    }

    public Extracao extrairPdf(byte[] conteudo) {
        long inicio = System.nanoTime();
        try (PDDocument documento = Loader.loadPDF(conteudo)) {
            int total = documento.getNumberOfPages();
            if (total > LIMITE_DE_PAGINAS) {
                throw new ExtracaoFalhouException("O PDF tem " + total + " páginas e o limite é " + LIMITE_DE_PAGINAS + ".", total);
            }
            List<Pagina> paginas = new ArrayList<>(total);
            boolean temTexto = false;
            for (int numero = 1; numero <= total; numero++) {
                LeitorDeLinhas leitor = new LeitorDeLinhas();
                leitor.setStartPage(numero);
                leitor.setEndPage(numero);
                leitor.getText(documento);
                List<Linha> linhas = marcarTabelas(leitor.linhas());
                temTexto |= !linhas.isEmpty();
                paginas.add(new Pagina(numero, List.copyOf(linhas)));
            }
            if (!temTexto) {
                throw new ExtracaoFalhouException(SEM_TEXTO, total);
            }
            return new Extracao(List.copyOf(paginas), milissegundosDesde(inicio));
        } catch (InvalidPasswordException comSenha) {
            throw new ExtracaoFalhouException("O PDF está protegido por senha.", null);
        } catch (ExtracaoFalhouException conhecida) {
            throw conhecida;
        } catch (IOException | RuntimeException ilegivel) {
            throw new ExtracaoFalhouException("O PDF está corrompido ou não pôde ser lido.", null);
        }
    }

    /** O TXT é UTF-8, uma página só. As linhas em branco ficam: separam os parágrafos. */
    public Extracao extrairTxt(byte[] conteudo) {
        long inicio = System.nanoTime();
        String texto = limpar(new String(conteudo, StandardCharsets.UTF_8));
        if (texto.isBlank()) {
            throw new ExtracaoFalhouException("O arquivo de texto está vazio.", 1);
        }
        List<Linha> linhas = new ArrayList<>();
        for (String linha : texto.split("\n", -1)) {
            linhas.add(new Linha(linha.stripTrailing(), 0, List.of(), false));
        }
        return new Extracao(List.of(new Pagina(1, List.copyOf(linhas))), milissegundosDesde(inicio));
    }

    /**
     * O texto como o resto do Atlas o lê: normalizado em NFC (o PDFBox pode entregar a letra e o acento separados), sem o BOM, com
     * o espaço sem quebra virando espaço comum e a quebra de linha {@code \n}.
     */
    static String limpar(String bruto) {
        return Normalizer.normalize(bruto, Normalizer.Form.NFC)
                .replace("﻿", "").replace(' ', ' ').replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    // ---------------------------------------------------------------------
    // PDF: as linhas e as tabelas
    // ---------------------------------------------------------------------

    /**
     * Junta os pedaços que o PDFBox entrega ({@code writeString}) em linhas, guardando onde cada pedaço começa. Um pedaço é
     * um trecho seguido de texto sem espaço grande: numa tabela, cada célula é um pedaço.
     */
    private static final class LeitorDeLinhas extends PDFTextStripper {

        private final List<Linha> linhas = new ArrayList<>();
        private final List<String> pedacos = new ArrayList<>();
        private final List<Double> colunas = new ArrayList<>();

        LeitorDeLinhas() throws IOException {
            super();
        }

        @Override
        protected void writeString(String texto, List<TextPosition> posicoes) throws IOException {
            String limpo = limpar(texto);
            if (!limpo.isEmpty() && !posicoes.isEmpty()) {
                pedacos.add(limpo);
                colunas.add((double) posicoes.get(0).getXDirAdj());
            }
            super.writeString(texto, posicoes);
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            fecharLinha();
            super.writeLineSeparator();
        }

        @Override
        protected void endPage(PDPage pagina) throws IOException {
            fecharLinha();
            super.endPage(pagina);
        }

        private void fecharLinha() {
            if (!pedacos.isEmpty()) {
                String texto = String.join(" ", pedacos).replaceAll("\\s+", " ").strip();
                if (!texto.isEmpty()) {
                    linhas.add(new Linha(texto, colunas.get(0), List.copyOf(colunas), false));
                }
            }
            pedacos.clear();
            colunas.clear();
        }

        List<Linha> linhas() {
            return linhas;
        }
    }

    /**
     * Marca as linhas de tabela. A tabela começa numa linha com 2 ou mais pedaços e é reconhecida pela legenda "Tabela N" logo
     * antes ou pelo recuo da primeira coluna em relação à margem do texto; as linhas seguintes fazem parte dela enquanto cada pedaço
     * começar numa das colunas da primeira linha (a quebra de uma célula comprida cai na mesma coluna). A legenda entra na tabela.
     */
    static List<Linha> marcarTabelas(List<Linha> linhas) {
        if (linhas.isEmpty()) {
            return linhas;
        }
        double margem = linhas.stream().mapToDouble(Linha::x).min().orElse(0);
        List<Linha> resultado = new ArrayList<>(linhas);
        int i = 0;
        while (i < resultado.size()) {
            Linha cabecalho = resultado.get(i);
            boolean comLegenda = i > 0 && resultado.get(i - 1).texto().regionMatches(true, 0, "Tabela ", 0, 7)
                    && !resultado.get(i - 1).tabela();
            boolean recuada = cabecalho.x() - margem > RECUO_DE_TABELA;
            if (distintas(cabecalho.colunas()) >= 2 && (comLegenda || recuada)) {
                int fim = i + 1;
                while (fim < resultado.size() && nasColunas(resultado.get(fim), cabecalho.colunas())) {
                    fim++;
                }
                if (fim - i >= 2) {
                    if (comLegenda) {
                        resultado.set(i - 1, resultado.get(i - 1).comoTabela());
                    }
                    for (int k = i; k < fim; k++) {
                        resultado.set(k, resultado.get(k).comoTabela());
                    }
                    i = fim;
                    continue;
                }
            }
            i++;
        }
        return resultado;
    }

    private static int distintas(List<Double> colunas) {
        List<Double> unicas = new ArrayList<>();
        for (double coluna : colunas) {
            if (unicas.stream().noneMatch(u -> Math.abs(u - coluna) <= ALINHAMENTO)) {
                unicas.add(coluna);
            }
        }
        return unicas.size();
    }

    private static boolean nasColunas(Linha linha, List<Double> colunasDaTabela) {
        return linha.colunas().stream().allMatch(c -> colunasDaTabela.stream().anyMatch(t -> Math.abs(t - c) <= ALINHAMENTO));
    }

    private static long milissegundosDesde(long inicioEmNanos) {
        return (System.nanoTime() - inicioEmNanos) / 1_000_000;
    }
}
