package br.com.rbs.portal.importacao.leitores;

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
 * Lê o texto colado ou um .txt (E4): uma linha por pedaço, com o localizador "linha N". O detector roda sobre estes pedaços
 * antes de qualquer modelo, e o visualizador mostra o antes, o trecho e o depois da linha.
 */
@Component
public class LeitorDeTexto implements LeitorDeConteudo {

    @Override
    public boolean aceita(String extensao) {
        return "txt".equalsIgnoreCase(extensao);
    }

    @Override
    public ConteudoLido ler(Path arquivo) {
        try {
            String texto = LeitorDeCsv.decodificar(Files.readAllBytes(arquivo));
            List<Pedaco> pedacos = new ArrayList<>();
            int ordem = 0;
            int numero = 0;
            for (String linha : texto.split("\\R", -1)) {
                numero++;
                String limpa = linha.strip();
                if (!limpa.isEmpty()) {
                    pedacos.add(new Pedaco(limpa, "linha " + numero, ++ordem));
                }
            }
            return new ConteudoLido("TXT", pedacos, Map.of());
        } catch (IOException erro) {
            throw new LeituraFalhouException("Não consegui ler o arquivo de texto.", erro);
        }
    }
}
