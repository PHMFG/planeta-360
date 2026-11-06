package br.com.rbs.portal.importacao.leitores;

import br.com.rbs.portal.importacao.ConteudoLido;
import br.com.rbs.portal.importacao.LeituraFalhouException;
import br.com.rbs.portal.importacao.LeitorDeConteudo;
import br.com.rbs.portal.importacao.Pedaco;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Component;

/**
 * Lê .csv na importação (E7). [IMPORTACAO] Aceita qualquer separador entre vírgula, ponto e vírgula e tabulação, e UTF-8 ou
 * Latin-1 (o Excel em português grava o CSV em ANSI). O conteúdo vira uma aba de uma coluna por célula, como a planilha, para o
 * extrator padrão e o mapeador trabalharem do mesmo jeito.
 */
@Component
public class LeitorDeCsv implements LeitorDeConteudo {

    static final String NOME_DA_ABA = "Planilha 1";

    @Override
    public boolean aceita(String extensao) {
        return "csv".equalsIgnoreCase(extensao);
    }

    @Override
    public ConteudoLido ler(Path arquivo) {
        try {
            byte[] bytes = Files.readAllBytes(arquivo);
            String texto = decodificar(bytes);
            if (!texto.isEmpty() && texto.charAt(0) == '\uFEFF') {
                texto = texto.substring(1);
            }
            char separador = separador(texto);
            CSVFormat formato = CSVFormat.DEFAULT.builder().setDelimiter(separador).setIgnoreEmptyLines(false).get();
            List<List<String>> linhas = new ArrayList<>();
            List<Pedaco> pedacos = new ArrayList<>();
            int ordem = 0;
            try (CSVParser parser = formato.parse(new StringReader(texto))) {
                Iterator<CSVRecord> registros = parser.iterator();
                int numero = 0;
                while (registros.hasNext()) {
                    CSVRecord registro = registros.next();
                    numero++;
                    List<String> valores = new ArrayList<>();
                    for (int c = 0; c < registro.size(); c++) {
                        String valor = registro.get(c) == null ? "" : registro.get(c).strip();
                        valores.add(valor);
                        if (!valor.isEmpty()) {
                            pedacos.add(new Pedaco(valor, NOME_DA_ABA + "!" + new CellReference(numero - 1, c).formatAsString(),
                                    ++ordem));
                        }
                    }
                    linhas.add(valores);
                }
            }
            LeitorDePlanilha.AbaLida aba = new LeitorDePlanilha.AbaLida(NOME_DA_ABA, linhas);
            return new ConteudoLido("CSV", pedacos, Map.of("abas", List.of(aba)));
        } catch (IOException | RuntimeException erro) {
            throw new LeituraFalhouException("Não consegui ler o CSV. Confira o separador e as aspas perto da linha quebrada.", erro);
        }
    }

    /** UTF-8 quando é UTF-8 válido; senão, Latin-1. */
    static String decodificar(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException naoEhUtf8) {
            return new String(bytes, Charset.forName("ISO-8859-1"));
        }
    }

    /** O separador da primeira linha: o que aparece mais vezes fora de aspas (vírgula quando não há nenhum). */
    static char separador(String texto) {
        int virgula = 0;
        int pontoEVirgula = 0;
        int tabulacao = 0;
        boolean aspas = false;
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (c == '"') {
                aspas = !aspas;
            } else if (!aspas) {
                if (c == ',') {
                    virgula++;
                } else if (c == ';') {
                    pontoEVirgula++;
                } else if (c == '\t') {
                    tabulacao++;
                } else if (c == '\n' || c == '\r') {
                    break;
                }
            }
        }
        if (pontoEVirgula >= virgula && pontoEVirgula >= tabulacao && pontoEVirgula > 0) {
            return ';';
        }
        if (tabulacao > virgula) {
            return '\t';
        }
        return ',';
    }

}
