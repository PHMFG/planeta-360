package br.com.rbs.portal.atlas.loop;

import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeMensagem;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.ChamadaDeFerramenta;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.MensagemDeChat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

/**
 * Grava a conversa em {@code atlas_mensagem} no formato de chat completions, o mesmo que o loop do modelo de linguagem usa.
 * [ATLAS] O plano B (o botão) escreve o histórico COMO SE o modelo tivesse chamado as ferramentas: mensagem {@code user},
 * mensagem {@code assistant} com {@code tool_calls} (ids gerados pelo servidor, {@code botao_<uuid>}), mensagens {@code tool}
 * com o resultado e textos fixos do assistente. Assim, quando o modelo entra, ele continua a mesma conversa sem quebrar a regra
 * "toda tool_call tem resposta" (a do cartão fica pendente até a rota 46). Cada gravação trava a linha da conversa primeiro:
 * as mensagens ganham a próxima {@code ordem} sem colidir. Chamar dentro da transação do passo que as escreve.
 * <p>
 * O loop do modelo de linguagem escreve no mesmo formato (ids {@code call_<uuid>}, gerados aqui para serem únicos na conversa,
 * e o {@code reasoning_content} quando vier) e usa {@link #paraOProvedor} para remontar o histórico na ordem em que aconteceu.
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

    /** O começo do id das chamadas que o plano B (o botão) escreve; as do modelo começam com {@code call_}. */
    public static final String PREFIXO_DO_BOTAO = "botao_";

    /** Um id de pedido de ferramenta gerado pelo servidor (o botão), no lugar do que o modelo daria. */
    public static String novoIdDeChamada() {
        return PREFIXO_DO_BOTAO + UUID.randomUUID();
    }

    /**
     * Um id para o pedido de ferramenta do modelo. O loop não usa o id que o modelo deu: não há garantia de que ele seja único na
     * conversa (e {@code atlas_solicitacao.tool_call_id} é amarrado ao pedido), e o modelo aceita o histórico com os nossos.
     */
    public static String novoIdDoModelo() {
        return "call_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** A mensagem da pessoa. Devolve o id da mensagem. */
    public long usuario(long conversaId, String texto) {
        repositorio.travarConversa(conversaId);
        return repositorio.inserirMensagem(conversaId, "user", texto, null, null, null);
    }

    /** A mensagem do assistente: um texto, pedidos de ferramenta, ou os dois juntos. Devolve o id da mensagem. */
    public long assistente(long conversaId, String texto, List<Chamada> chamadas) {
        return assistente(conversaId, texto, chamadas, null);
    }

    /** O mesmo, com o {@code reasoning_content} que o modelo mandou (nulo se não mandou). */
    public long assistente(long conversaId, String texto, List<Chamada> chamadas, String raciocinio) {
        repositorio.travarConversa(conversaId);
        return repositorio.inserirMensagem(conversaId, "assistant", texto, chamadas.isEmpty() ? null : paraJson(chamadas), null,
                raciocinio);
    }

    /** A resposta de uma ferramenta ao pedido {@code toolCallId}. */
    public long resultado(long conversaId, String toolCallId, String conteudoJson) {
        repositorio.travarConversa(conversaId);
        return repositorio.inserirMensagem(conversaId, "tool", conteudoJson, null, toolCallId, null);
    }

    public int ultimaOrdem(long conversaId) {
        return repositorio.ultimaOrdem(conversaId);
    }

    /** O histórico na ordem em que aconteceu, para o loop remontar a conversa. */
    public List<LinhaDeMensagem> mensagens(long conversaId) {
        return repositorio.mensagens(conversaId);
    }

    /** A frase que toma o lugar do texto de um resultado de busca de um turno anterior. */
    public static final String AVISO_DO_RESULTADO_ANTIGO = "resultado de um turno anterior; busque de novo para citar";

    /**
     * As ferramentas cujo resultado traz texto de documento: de turno anterior, vão ao modelo sem o texto. A busca traz os trechos; os
     * prazos (M5) trazem o título e a descrição que a RBS escreveu a partir de um documento, e a data dele.
     */
    private static final Set<String> FERRAMENTAS_COM_TEXTO_DE_DOCUMENTO = Set.of("buscar_documentos", "consultar_prazos");

    /**
     * O histórico para mandar ao modelo, na ordem em que aconteceu: a mensagem da pessoa, a do assistente (com os pedidos de
     * ferramenta e o raciocínio que ele deu) e a resposta de cada ferramenta (com o nome dela). [ATLAS] Toda {@code tool_call}
     * tem de ter resposta antes da próxima mensagem que não é de ferramenta, ou o provedor responde 400. Se alguma ficou sem
     * (um turno que caiu no meio), esta remontagem completa a conta com um erro, só na memória; o cartão que espera a resposta
     * não passa por aqui, porque o loop não chama o modelo enquanto há cartão aberto.
     * <p>
     * [ISOLAMENTO] <b>O resultado de uma busca nos documentos, ou de uma consulta de prazos, de um turno anterior vai sem o texto</b>
     * ({@code ordem} até {@code ordemDoTurno}): só o documento e a versão, e a frase "resultado de um turno anterior; busque de novo para citar". Sem
     * isso, um documento revogado ou uma versão substituída continuariam disponíveis ao modelo ("o que dizia aquela circular?"
     * teria resposta sem nova busca, e uma frase sem número e sem citação passaria pela checagem). O que foi gravado em
     * {@code atlas_tool_call} e {@code atlas_fonte} fica como estava: aqui só muda o que vai ao modelo. O que o assistente já escreveu
     * e o raciocínio antigo continuam no histórico (o provedor exige o raciocínio junto das ferramentas); a nota do prompt do turno
     * cobre esse caso, e o risco que sobra está em {@code pendencias.md}.
     */
    public List<MensagemDeChat> paraOProvedor(long conversaId, int ordemDoTurno) {
        List<MensagemDeChat> remontado = new ArrayList<>();
        Map<String, String> nomes = new LinkedHashMap<>();
        Set<String> semResposta = new LinkedHashSet<>();
        for (LinhaDeMensagem linha : repositorio.mensagens(conversaId)) {
            if (!"tool".equals(linha.papel())) {
                completarSemResposta(remontado, nomes, semResposta);
            }
            switch (linha.papel()) {
                case "user" -> remontado.add(MensagemDeChat.usuario(linha.conteudo()));
                case "assistant" -> {
                    List<ChamadaDeFerramenta> chamadas = chamadasDe(linha.toolCalls());
                    chamadas.forEach(c -> {
                        nomes.put(c.id(), c.nome());
                        semResposta.add(c.id());
                    });
                    remontado.add(MensagemDeChat.assistente(linha.conteudo(), chamadas, linha.reasoningContent()));
                }
                default -> {
                    semResposta.remove(linha.toolCallId());
                    String ferramenta = nomes.get(linha.toolCallId());
                    String conteudo = linha.ordem() <= ordemDoTurno && FERRAMENTAS_COM_TEXTO_DE_DOCUMENTO.contains(ferramenta)
                            ? semOTextoDosDocumentos(linha.conteudo()) : linha.conteudo();
                    remontado.add(MensagemDeChat.resultado(linha.toolCallId(), ferramenta, conteudo));
                }
            }
        }
        completarSemResposta(remontado, nomes, semResposta);
        return remontado;
    }

    /**
     * O resultado de uma busca ({@code trechos}) ou de uma consulta de prazos ({@code registros}) sem o texto: só os documentos (título
     * e versão, sem repetir) e o aviso. Num registro, o documento está em {@code origem}; o prazo de uma entrega ou sem origem não tem
     * documento a dizer. Um erro de ferramenta ({@code {"erro": ...}}) não tem texto de documento e segue como está.
     */
    static String semOTextoDosDocumentos(String conteudoJson) {
        Map<String, Object> resultado;
        try {
            resultado = AtlasJson.ler(conteudoJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (RuntimeException naoEObjeto) {
            resultado = null;
        }
        if (resultado != null && resultado.containsKey("erro")) {
            return conteudoJson;
        }
        Set<List<Object>> vistos = new LinkedHashSet<>();
        if (resultado != null && resultado.get("trechos") instanceof List<?> trechos) {
            for (Object trecho : trechos) {
                if (trecho instanceof Map<?, ?> mapa) {
                    vistos.add(java.util.Arrays.asList(mapa.get("documento"), mapa.get("versao")));
                }
            }
        }
        if (resultado != null && resultado.get("registros") instanceof List<?> registros) {
            for (Object registro : registros) {
                if (registro instanceof Map<?, ?> mapa && mapa.get("origem") instanceof Map<?, ?> origem && origem.get("documento") != null) {
                    vistos.add(java.util.Arrays.asList(origem.get("documento"), origem.get("versao")));
                }
            }
        }
        List<Map<String, Object>> documentos = new ArrayList<>();
        for (List<Object> documento : vistos) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("documento", documento.get(0));
            item.put("versao", documento.get(1));
            documentos.add(item);
        }
        Map<String, Object> novo = new LinkedHashMap<>();
        novo.put("tipo", "resultado_de_turno_anterior");
        novo.put("aviso", AVISO_DO_RESULTADO_ANTIGO);
        novo.put("documentos", documentos);
        return AtlasJson.escrever(novo);
    }

    private static void completarSemResposta(List<MensagemDeChat> remontado, Map<String, String> nomes, Set<String> semResposta) {
        for (String id : new ArrayList<>(semResposta)) {
            remontado.add(MensagemDeChat.resultado(id, nomes.get(id), AtlasJson.escrever(Map.of("erro", "A ferramenta não chegou a responder."))));
        }
        semResposta.clear();
    }

    @SuppressWarnings("unchecked")
    private static List<ChamadaDeFerramenta> chamadasDe(String toolCallsJson) {
        if (toolCallsJson == null) {
            return List.of();
        }
        List<ChamadaDeFerramenta> chamadas = new ArrayList<>();
        for (Map<String, Object> chamada : AtlasJson.ler(toolCallsJson, new TypeReference<List<Map<String, Object>>>() {
        })) {
            Map<String, Object> funcao = (Map<String, Object>) chamada.get("function");
            chamadas.add(new ChamadaDeFerramenta((String) chamada.get("id"), (String) funcao.get("name"), (String) funcao.get("arguments")));
        }
        return chamadas;
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
