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
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Lê o texto colado ou um .txt (E4 e E8): uma linha por pedaço, com o localizador "linha N". Numa exportação de conversa do WhatsApp
 * (as linhas "dd/mm/aaaa hh:mm - Autor: texto"), o localizador vira "mensagem N" e as linhas de continuação — as que não começam
 * com data e hora — ficam na mesma mensagem. O detector roda sobre estes pedaços antes de qualquer modelo, e o visualizador mostra o
 * antes, o trecho e o depois da linha (ou da mensagem).
 */
@Component
public class LeitorDeTexto implements LeitorDeConteudo {

    /** O começo de uma mensagem exportada do WhatsApp: data, hora, autor e o texto. */
    private static final Pattern MENSAGEM_DO_WHATSAPP = Pattern.compile("^\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2} - [^:]+: .*$");

    @Override
    public boolean aceita(String extensao) {
        return "txt".equalsIgnoreCase(extensao);
    }

    @Override
    public ConteudoLido ler(Path arquivo) {
        try {
            String texto = LeitorDeCsv.decodificar(Files.readAllBytes(arquivo));
            return new ConteudoLido("TXT", ehConversaDoWhatsApp(texto) ? mensagens(texto) : linhas(texto), Map.of());
        } catch (IOException erro) {
            throw new LeituraFalhouException("Não consegui ler o arquivo de texto.", erro);
        }
    }

    private static boolean ehConversaDoWhatsApp(String texto) {
        for (String linha : texto.split("\\R")) {
            if (MENSAGEM_DO_WHATSAPP.matcher(linha.strip()).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Uma linha por pedaço, com o localizador "linha N"; as linhas em branco não viram pedaço. */
    private static List<Pedaco> linhas(String texto) {
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
        return pedacos;
    }

    /**
     * Uma mensagem por pedaço, com o localizador "mensagem N". Uma linha que começa com data e hora abre a mensagem; a que não começa
     * é continuação dela (o texto do WhatsApp quebra a mensagem em várias linhas) e entra no mesmo pedaço.
     */
    private static List<Pedaco> mensagens(String texto) {
        List<Pedaco> pedacos = new ArrayList<>();
        StringBuilder atual = null;
        for (String linha : texto.split("\\R", -1)) {
            String limpa = linha.strip();
            if (MENSAGEM_DO_WHATSAPP.matcher(limpa).matches()) {
                if (atual != null) {
                    int numero = pedacos.size() + 1;
                    pedacos.add(new Pedaco(atual.toString(), "mensagem " + numero, numero));
                }
                atual = new StringBuilder(limpa);
            } else if (!limpa.isEmpty() && atual != null) {
                atual.append('\n').append(limpa);
            }
        }
        if (atual != null) {
            int numero = pedacos.size() + 1;
            pedacos.add(new Pedaco(atual.toString(), "mensagem " + numero, numero));
        }
        return pedacos;
    }
}
