package br.com.rbs.portal.consolidacao;

import br.com.rbs.portal.comum.DadosInvalidosException;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Lê a planilha CSV de uma carga (veiculação ou pesquisa) com o commons-csv: UTF-8 (aceita BOM no início), vírgula,
 * cabeçalho na primeira linha. Atende o item 5 da Entrega 2. [CARGA] [VALIDACAO] Confere o cabeçalho (422 com as colunas
 * que faltam), numera cada linha pela posição no arquivo (o cabeçalho é a linha 1; linha em branco conta no número, mas não
 * é lida) e entrega as linhas em lotes, para uma planilha grande não ficar toda na memória como objetos.
 *
 * <p>Célula vazia vira {@code null}. Coluna que não está no cabeçalho é ignorada se for a mais; se faltar, é erro.
 */
final class LeitorDeCsv {

    /**
     * Uma coluna esperada: o nome no cabeçalho, o tamanho máximo do texto (o da coluna da área de passagem no banco) e se
     * ela precisa estar no cabeçalho. Coluna opcional ausente vale nulo; a obrigatória que falta é 422.
     */
    record Coluna(String nome, int limite, boolean obrigatoria) {

        /** O caso de sempre: a coluna precisa estar no cabeçalho. */
        Coluna(String nome, int limite) {
            this(nome, limite, true);
        }
    }

    /** Uma linha de dados: o número no arquivo e os valores na ordem das colunas esperadas ({@code null} = vazio). */
    record Linha(int numero, List<String> valores) {
    }

    // DEFAULT: vírgula, aspas duplas (dobradas dentro do texto) e linhas em branco ignoradas.
    private static final CSVFormat FORMATO = CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(true).get();

    private LeitorDeCsv() {
    }

    /**
     * Lê o arquivo inteiro e devolve quantas linhas de dados havia. Cada lote (até {@code tamanhoDoLote} linhas) vai para
     * {@code destino}, que deve consumi-lo na hora (a lista é reaproveitada). Erros, todos 422 com {@code campos.arquivo}:
     * cabeçalho sem coluna esperada, texto maior que o limite da coluna, CSV mal formado e planilha sem nenhuma linha de dados.
     */
    static int ler(byte[] conteudo, List<Coluna> colunas, int tamanhoDoLote, Consumer<List<Linha>> destino) {
        String texto = new String(conteudo, StandardCharsets.UTF_8);
        if (!texto.isEmpty() && texto.charAt(0) == '﻿') {
            texto = texto.substring(1);   // o BOM que o Excel grava no começo do arquivo
        }
        ContadorDeLinhas contador = new ContadorDeLinhas(texto);
        int total = 0;
        try (CSVParser leitor = FORMATO.parse(new StringReader(texto))) {
            Iterator<CSVRecord> registros = leitor.iterator();
            if (!registros.hasNext()) {
                throw invalido("A planilha está vazia. A primeira linha deve ter o cabeçalho com os nomes das colunas.");
            }
            int[] posicoes = localizarColunas(registros.next(), colunas);

            List<Linha> lote = new ArrayList<>(tamanhoDoLote);
            while (registros.hasNext()) {
                CSVRecord registro = registros.next();
                lote.add(montarLinha(registro, contador.linhaDa(registro.getCharacterPosition()), posicoes, colunas));
                total++;
                if (lote.size() >= tamanhoDoLote) {
                    destino.accept(lote);
                    lote.clear();
                }
            }
            if (!lote.isEmpty()) {
                destino.accept(lote);
            }
        } catch (IOException | UncheckedIOException malFormado) {
            // Aspas abertas e nunca fechadas, ou texto solto depois de uma célula entre aspas.
            throw invalido("O arquivo não é um CSV válido (confira as aspas perto da linha "
                    + contador.ultimaLinha() + "). Salve a planilha como CSV em UTF-8, separado por vírgulas.");
        }
        if (total == 0) {
            throw invalido("A planilha tem o cabeçalho, mas nenhuma linha de dados.");
        }
        return total;
    }

    /** O índice de cada coluna esperada dentro do registro; erro 422 listando as que o cabeçalho não tem. */
    private static int[] localizarColunas(CSVRecord cabecalho, List<Coluna> colunas) {
        Map<String, Integer> indices = new HashMap<>();
        for (int i = 0; i < cabecalho.size(); i++) {
            indices.putIfAbsent(cabecalho.get(i).strip().toLowerCase(Locale.ROOT), i);
        }
        int[] posicoes = new int[colunas.size()];
        List<String> faltam = new ArrayList<>();
        for (int i = 0; i < colunas.size(); i++) {
            Integer indice = indices.get(colunas.get(i).nome());
            posicoes[i] = indice == null ? -1 : indice;
            if (indice == null && colunas.get(i).obrigatoria()) {
                faltam.add(colunas.get(i).nome());
            }
        }
        if (!faltam.isEmpty()) {
            String dica = cabecalho.size() == 1 && cabecalho.get(0).contains(";")
                    ? " O separador das colunas deve ser a vírgula, e não o ponto e vírgula." : "";
            throw invalido("O cabeçalho da planilha não tem as colunas: " + String.join(", ", faltam) + "." + dica);
        }
        return posicoes;
    }

    private static Linha montarLinha(CSVRecord registro, int numero, int[] posicoes, List<Coluna> colunas) {
        List<String> valores = new ArrayList<>(colunas.size());
        for (int i = 0; i < colunas.size(); i++) {
            // Coluna opcional que não veio no cabeçalho: a célula é nula, como a vazia.
            String valor = posicoes[i] >= 0 && posicoes[i] < registro.size() ? registro.get(posicoes[i]).strip() : "";
            if (valor.isEmpty()) {
                valor = null;
            } else if (valor.length() > colunas.get(i).limite()) {
                // Não dá para gravar na área de passagem (o banco recusaria): o envio inteiro é devolvido para a RBS corrigir.
                throw invalido("Linha " + numero + ": a coluna \"" + colunas.get(i).nome() + "\" passa de "
                        + colunas.get(i).limite() + " caracteres.");
            }
            valores.add(valor);
        }
        return new Linha(numero, valores);
    }

    private static DadosInvalidosException invalido(String mensagem) {
        return new DadosInvalidosException(Map.of("arquivo", mensagem));
    }

    /**
     * Descobre em que linha do arquivo começa cada registro, a partir da posição em caracteres que o commons-csv informa.
     * O parser ignora as linhas em branco antes do registro, mas a posição aponta para o começo delas; por isso o contador
     * pula as quebras de linha que estiverem ali. Registro com texto entre aspas em várias linhas conta pela linha em que começa.
     */
    private static final class ContadorDeLinhas {

        private final String texto;
        private int posicao = 0;
        private int linha = 1;

        ContadorDeLinhas(String texto) {
            this.texto = texto;
        }

        int linhaDa(long inicioDoRegistro) {
            int alvo = (int) Math.min(inicioDoRegistro, texto.length());
            while (alvo < texto.length() && (texto.charAt(alvo) == '\n' || texto.charAt(alvo) == '\r')) {
                alvo++;
            }
            for (; posicao < alvo; posicao++) {
                char c = texto.charAt(posicao);
                boolean fimDeLinha = c == '\n'
                        || (c == '\r' && !(posicao + 1 < texto.length() && texto.charAt(posicao + 1) == '\n'));
                if (fimDeLinha) {
                    linha++;
                }
            }
            return linha;
        }

        int ultimaLinha() {
            return linha;
        }
    }
}
