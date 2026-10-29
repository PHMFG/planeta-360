package br.com.rbs.portal.atlas.loop;

import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeMensagem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Grava a conversa em {@code atlas_mensagem} no formato de chat completions, o mesmo que o loop do modelo de linguagem usa.
 * [ATLAS] O plano B (o botão) escreve o histórico COMO SE o modelo tivesse chamado as ferramentas: mensagem {@code user},
 * mensagem {@code assistant} com {@code tool_calls} (ids gerados pelo servidor, {@code botao_<uuid>}), mensagens {@code tool}
 * com o resultado e textos fixos do assistente. Assim, quando o modelo entra, ele continua a mesma conversa sem quebrar a regra
 * "toda tool_call tem resposta" (a do cartão fica pendente até a rota 46). Cada gravação trava a linha da conversa primeiro:
 * as mensagens ganham a próxima {@code ordem} sem colidir. Chamar dentro da transação do passo que as escreve.
 */
@Component
public class HistoricoDaConversa {

    /** Um pedido de ferramenta do assistente: o id, o nome da ferramenta e os argumentos como texto JSON. */
    public record Chamada(String id, String nome, String argumentosJson) {
    }

    private final AtlasRepositorio repositorio;

    public HistoricoDaConversa(AtlasRepositorio repositorio) {
        this.repositorio = repositorio;
    }

    /** Um id de pedido de ferramenta gerado pelo servidor (o botão), no lugar do que o modelo daria. */
    public static String novoIdDeChamada() {
        return "botao_" + UUID.randomUUID();
    }

    /** A mensagem da pessoa. Devolve o id da mensagem. */
    public long usuario(long conversaId, String texto) {
        repositorio.travarConversa(conversaId);
        return repositorio.inserirMensagem(conversaId, "user", texto, null, null);
    }

    /** A mensagem do assistente: um texto, pedidos de ferramenta, ou os dois juntos. Devolve o id da mensagem. */
    public long assistente(long conversaId, String texto, List<Chamada> chamadas) {
        repositorio.travarConversa(conversaId);
        return repositorio.inserirMensagem(conversaId, "assistant", texto, chamadas.isEmpty() ? null : paraJson(chamadas), null);
    }

    /** A resposta de uma ferramenta ao pedido {@code toolCallId}. */
    public long resultado(long conversaId, String toolCallId, String conteudoJson) {
        repositorio.travarConversa(conversaId);
        return repositorio.inserirMensagem(conversaId, "tool", conteudoJson, null, toolCallId);
    }

    public int ultimaOrdem(long conversaId) {
        return repositorio.ultimaOrdem(conversaId);
    }

    /** O histórico na ordem em que aconteceu, para o loop remontar a conversa. */
    public List<LinhaDeMensagem> mensagens(long conversaId) {
        return repositorio.mensagens(conversaId);
    }

    /** {@code [{"id":..., "type":"function", "function":{"name":..., "arguments":"{...}"}}]}: o formato de chat completions. */
    static String paraJson(List<Chamada> chamadas) {
        List<Map<String, Object>> lista = new ArrayList<>();
        for (Chamada chamada : chamadas) {
            Map<String, Object> funcao = new LinkedHashMap<>();
            funcao.put("name", chamada.nome());
            funcao.put("arguments", chamada.argumentosJson());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", chamada.id());
            item.put("type", "function");
            item.put("function", funcao);
            lista.add(item);
        }
        return AtlasJson.escrever(lista);
    }
}
