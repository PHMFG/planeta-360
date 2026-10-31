package br.com.rbs.portal.atlas.loop;

import br.com.rbs.portal.atlas.AtlasDtos.CampoDoCartaoDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemAviso;
import br.com.rbs.portal.atlas.AtlasDtos.ItemCartao;
import br.com.rbs.portal.atlas.AtlasDtos.DadosDaFerramenta;
import br.com.rbs.portal.atlas.AtlasDtos.ItemDaConversa;
import br.com.rbs.portal.atlas.AtlasDtos.ItemFerramenta;
import br.com.rbs.portal.atlas.AtlasDtos.ItemRaciocinio;
import br.com.rbs.portal.atlas.AtlasDtos.ItemRelatorioPronto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemTexto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemUsuario;
import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeChamada;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeConversa;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeMensagem;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeRelatorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeSolicitacao;
import br.com.rbs.portal.atlas.ModoDoAtlas;
import br.com.rbs.portal.atlas.diagnostico.TracoRepositorio;
import br.com.rbs.portal.atlas.provedor.DisponibilidadeDoLlm;
import br.com.rbs.portal.atlas.relatorio.Especificacao;
import br.com.rbs.portal.atlas.relatorio.ModeloBase;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Resolucao;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.dados.DadoInformadoService;
import br.com.rbs.portal.dados.DadosDtos.CampoDto;
import br.com.rbs.portal.dados.DadosDtos.VigenteDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

/**
 * Deriva os itens da conversa que a API devolve (rotas 42 a 46) do histórico gravado em {@code atlas_mensagem}. [ATLAS] Nada é
 * guardado só para a tela:
 * <ul>
 *   <li>mensagem {@code user} &rarr; item {@code usuario} (a do botão sai como "Montar o relatório da ativação");</li>
 *   <li>mensagem {@code assistant} com resumo do raciocínio gravado &rarr; item {@code raciocinio} com o {@code resumo}, antes de
 *       todos os itens da mesma mensagem. O {@code reasoning_content} cru nunca vira item: mensagem com raciocínio e sem resumo
 *       (recusado, atrasado, ou de antes da V9) fica sem ele;</li>
 *   <li>mensagem {@code assistant} com texto &rarr; item {@code texto}, com os marcadores {@code {{ref}}} trocados pelos valores
 *       (o que não tem valor vira "indisponível"); nunca sai marcador cru nem número inventado. {@code porModelo} é falso nas
 *       frases do servidor: a mensagem do plano B (as chamadas dela têm id {@code botao_}) e as frases fixas do loop
 *       ({@link LoopDoAgente#FRASES_DO_SERVIDOR}); verdadeiro no resto, que só o modelo escreve;</li>
 *   <li>cada pedido {@code relatorio_solicitar_campos} &rarr; item {@code cartao}, com os dados de {@code atlas_solicitacao}
 *       (achada pelo {@code tool_call_id}) e o estado de agora, os campos do catálogo e o valor vigente de cada um;</li>
 *   <li>a resposta de {@code relatorio_finalizar} &rarr; item {@code relatorio_pronto};</li>
 *   <li>a resposta de {@code relatorio_solicitar_campos} com {@code cancel} &rarr; item {@code aviso} ("Cartão fechado..."): sem
 *       modelo de linguagem, "Use Montar relatório para continuar"; com ele ligado agora (o mesmo {@code llmDisponivel} da
 *       resposta), "Quando tiver os números, peça para continuar".</li>
 * </ul>
 * Com o debug das ferramentas ({@code atlas.debug.ferramentas}), cada pedido de ferramenta vira também um item {@code ferramenta},
 * depois do {@code raciocinio} e do {@code texto} da mesma mensagem e na ordem em que rodou (antes do {@code cartao} dele).
 * Com o relatório desligado ({@code atlas.relatorio.ligado=false}) não sai {@code cartao}, {@code relatorio_pronto} nem
 * {@code aviso}, nem de conversa antiga que os tenha; o histórico em si não muda.
 * Só as mensagens depois de {@code desdeOrdem} viram item (o turno); com 0, a conversa inteira.
 */
@Component
public class ItensDaConversa {

    private final AtlasRepositorio repositorio;
    private final DadoInformadoService dados;
    private final ResolvedorDeReferencias resolvedor;
    private final ModeloBase modelos;
    private final DisponibilidadeDoLlm llm;
    private final ModoDoAtlas modo;
    private final TracoRepositorio tracos;

    public ItensDaConversa(AtlasRepositorio repositorio, DadoInformadoService dados, ResolvedorDeReferencias resolvedor,
                           ModeloBase modelos, DisponibilidadeDoLlm llm, ModoDoAtlas modo, TracoRepositorio tracos) {
        this.tracos = tracos;
        this.repositorio = repositorio;
        this.dados = dados;
        this.resolvedor = resolvedor;
        this.modelos = modelos;
        this.llm = llm;
        this.modo = modo;
    }

    /**
     * Os itens das mensagens de {@code ordem} maior que {@code desdeOrdem}, vistos por {@code usuario} (o perfil dele filtra o
     * que os marcadores resolvem). [PERMISSAO] Não confere acesso: quem chama já conferiu a conversa e a aba.
     */
    @Transactional(readOnly = true)
    public List<ItemDaConversa> itens(UsuarioAutenticado usuario, LinhaDeConversa conversa, int desdeOrdem) {
        List<LinhaDeMensagem> mensagens = repositorio.mensagens(conversa.id());
        Map<String, LinhaDeSolicitacao> cartoes = new HashMap<>();
        repositorio.solicitacoesDaConversa(conversa.id()).forEach(s -> cartoes.put(s.toolCallId(), s));
        Map<String, String> ferramentas = new HashMap<>();
        Map<String, Long> mensagemDoPedido = new HashMap<>();   // diagnóstico: id do pedido de ferramenta -> mensagem do assistente que o fez
        // Diagnóstico à vista da marca: a que turno do traço cada mensagem do assistente pertence (vazio com o diagnóstico fora).
        Map<Long, String> turnos = modo.diagnosticoNaMarca() ? tracos.turnosDasMensagens(conversa.id()) : new HashMap<>();
        Map<Long, Especificacao> especificacoes = new HashMap<>();
        Contexto contexto = new Contexto(usuario, conversa);
        Map<String, String> resultados = new HashMap<>();   // debug: toolCallId -> o que a ferramenta devolveu ao modelo
        Map<Long, List<LinhaDeChamada>> trilha = new HashMap<>();   // debug: mensagemId -> a trilha dos pedidos dela, em ordem
        if (modo.debugDeFerramentas()) {
            mensagens.stream().filter(m -> "tool".equals(m.papel()) && m.toolCallId() != null)
                    .forEach(m -> resultados.put(m.toolCallId(), m.conteudo()));
            repositorio.chamadasDaConversa(conversa.id()).forEach(c -> trilha.computeIfAbsent(c.mensagemId(), id -> new ArrayList<>()).add(c));
        }

        List<ItemDaConversa> itens = new ArrayList<>();
        for (LinhaDeMensagem mensagem : mensagens) {
            boolean doTurno = mensagem.ordem() > desdeOrdem;
            switch (mensagem.papel()) {
                case "user" -> {
                    if (doTurno) {
                        itens.add(new ItemUsuario(PlanoBSemLlm.TEXTO_DO_BOTAO.equals(mensagem.conteudo())
                                ? PlanoBSemLlm.TEXTO_DO_BOTAO_NA_TELA : mensagem.conteudo()));
                    }
                }
                case "assistant" -> {
                    List<Map<String, Object>> chamadas = chamadasDe(mensagem);
                    String turno = turnos.get(mensagem.id());
                    if (doTurno && mensagem.resumoDoRaciocinio() != null && !mensagem.resumoDoRaciocinio().isBlank()) {
                        itens.add(new ItemRaciocinio(mensagem.resumoDoRaciocinio(), turno));
                    }
                    if (doTurno && mensagem.conteudo() != null && !mensagem.conteudo().isBlank()) {
                        itens.add(new ItemTexto(contexto.resolucao(modelos, resolvedor).paraExibicao(mensagem.conteudo()),
                                escritoPeloModelo(mensagem.conteudo(), chamadas), turno));
                    }
                    List<LinhaDeChamada> trilhaDaMensagem = trilha.getOrDefault(mensagem.id(), List.of());
                    int posicao = 0;
                    for (Map<String, Object> chamada : chamadas) {
                        String id = (String) chamada.get("id");
                        String nome = nomeDaFerramenta(chamada);
                        ferramentas.put(id, nome);
                        mensagemDoPedido.put(id, mensagem.id());
                        if (doTurno && modo.debugDeFerramentas()) {
                            LinhaDeChamada registro = posicao < trilhaDaMensagem.size() ? trilhaDaMensagem.get(posicao) : null;
                            itens.add(ferramentaDoDebug(chamada, nome, resultados.get(id), registro, turno));
                        }
                        posicao++;
                        LinhaDeSolicitacao cartao = cartoes.get(id);
                        if (doTurno && modo.relatorioLigado() && "relatorio_solicitar_campos".equals(nome) && cartao != null) {
                            itens.add(cartao(contexto, cartao, especificacoes, turno));
                        }
                    }
                }
                default -> {
                    if (doTurno) {
                        Long doPedido = mensagemDoPedido.get(mensagem.toolCallId());   // nulo: resposta sem o pedido (histórico quebrado)
                        itemDaResposta(mensagem, ferramentas.get(mensagem.toolCallId()), doPedido == null ? null : turnos.get(doPedido))
                                .ifPresent(itens::add);
                    }
                }
            }
        }
        return itens;
    }

    /**
     * DEBUG: a ferramenta como o histórico a guarda. Os argumentos são o texto que o modelo enviou, o resultado é o que o modelo
     * recebeu (nulo se ainda não respondeu). O tempo e o erro vêm da trilha ({@code atlas_tool_call}), que tem uma linha por pedido
     * na ordem dele; se a linha não é desta ferramenta ou não existe, o tempo é nulo e o erro sai do próprio resultado.
     */
    private static ItemFerramenta ferramentaDoDebug(Map<String, Object> chamada, String nome, String resultado, LinhaDeChamada registro,
                                                    String turno) {
        String argumentos = argumentosDe(chamada);
        boolean daTrilha = registro != null && registro.ferramenta().equals(nome);
        boolean erro = daTrilha ? registro.erro() != null : resultadoEUmErro(resultado);
        return ItemFerramenta.de(DadosDaFerramenta.cortando(nome, argumentos, resultado, erro, daTrilha ? registro.duracaoMs() : null), turno);
    }

    @SuppressWarnings("unchecked")
    private static String argumentosDe(Map<String, Object> chamada) {
        return chamada.get("function") instanceof Map<?, ?> funcao ? (String) ((Map<String, Object>) funcao).get("arguments") : null;
    }

    /** O erro de ferramenta tem o formato {@code {"erro": "..."}} (RelatorioFerramentas.erroComoJson). */
    private static boolean resultadoEUmErro(String resultado) {
        if (resultado == null) {
            return false;
        }
        try {
            return AtlasJson.ler(resultado, new TypeReference<Map<String, Object>>() {
            }).get("erro") instanceof String;
        } catch (RuntimeException naoEObjeto) {
            return false;
        }
    }

    private Optional<ItemDaConversa> itemDaResposta(LinhaDeMensagem mensagem, String ferramenta, String turno) {
        if (ferramenta == null || mensagem.conteudo() == null || !modo.relatorioLigado()) {
            return Optional.empty();   // só chat: o relatório pronto e o aviso do cartão de uma conversa antiga não saem
        }
        Map<String, Object> resultado = AtlasJson.ler(mensagem.conteudo(), new TypeReference<Map<String, Object>>() {
        });
        if ("relatorio_finalizar".equals(ferramenta) && "PRONTO".equals(resultado.get("status"))) {
            return Optional.of(new ItemRelatorioPronto(((Number) resultado.get("relatorioId")).longValue(),
                    (String) resultado.get("titulo"), turno));
        }
        if ("relatorio_solicitar_campos".equals(ferramenta) && "cancel".equals(resultado.get("acao"))) {
            return Optional.of(new ItemAviso(llm.disponivel() ? PlanoBSemLlm.AVISO_CARTAO_FECHADO_COM_MODELO
                    : PlanoBSemLlm.AVISO_CARTAO_FECHADO));
        }
        return Optional.empty();
    }

    private ItemCartao cartao(Contexto contexto, LinhaDeSolicitacao cartao, Map<Long, Especificacao> especificacoes, String turno) {
        Especificacao especificacao = especificacoes.computeIfAbsent(cartao.relatorioId(), id -> {
            LinhaDeRelatorio relatorio = repositorio.relatorio(id).orElseThrow();
            return AtlasJson.ler(relatorio.especificacao(), Especificacao.class);
        });
        List<String> chaves = AtlasJson.ler(cartao.campos(), new TypeReference<List<String>>() {
        });
        List<CampoDoCartaoDto> campos = new ArrayList<>();
        for (String chave : chaves) {
            CampoDto campo = contexto.catalogo(dados).get(chave);
            if (campo != null) {
                campos.add(new CampoDoCartaoDto(campo.chave(), campo.aba(), campo.rotulo(), campo.tipo(), campo.unidade(),
                        campo.minimo(), campo.maximo(), campo.ajuda(), campo.limiteCampo(),
                        especificacao.lacunas().obrigatorias().contains(chave), contexto.vigentes(dados).get(chave)));
            }
        }
        return new ItemCartao(cartao.id(), cartao.relatorioId(), cartao.mensagem(), cartao.status(), campos, turno);
    }

    /**
     * O texto foi escrito pelo modelo de linguagem? Não, se a mensagem é do plano B (o servidor dá às chamadas dele o id
     * {@code botao_}) ou se é uma das frases fixas que o loop grava sozinho.
     */
    private static boolean escritoPeloModelo(String conteudo, List<Map<String, Object>> chamadas) {
        boolean doBotao = chamadas.stream().anyMatch(c -> c.get("id") instanceof String id
                && id.startsWith(HistoricoDaConversa.PREFIXO_DO_BOTAO));
        return !doBotao && !LoopDoAgente.FRASES_DO_SERVIDOR.contains(conteudo);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> chamadasDe(LinhaDeMensagem mensagem) {
        if (mensagem.toolCalls() == null) {
            return List.of();
        }
        return AtlasJson.ler(mensagem.toolCalls(), new TypeReference<List<Map<String, Object>>>() {
        });
    }

    @SuppressWarnings("unchecked")
    private static String nomeDaFerramenta(Map<String, Object> chamada) {
        Object funcao = chamada.get("function");
        return funcao instanceof Map<?, ?> mapa ? (String) ((Map<String, Object>) mapa).get("name") : null;
    }

    /** O que uma leitura precisa e só busca se um item pedir: o catálogo, os vigentes e a resolução dos textos. */
    private static final class Contexto {

        private final UsuarioAutenticado usuario;
        private final LinhaDeConversa conversa;
        private Map<String, CampoDto> catalogo;
        private Map<String, VigenteDto> vigentes;
        private Resolucao resolucao;

        Contexto(UsuarioAutenticado usuario, LinhaDeConversa conversa) {
            this.usuario = usuario;
            this.conversa = conversa;
        }

        Map<String, CampoDto> catalogo(DadoInformadoService dados) {
            if (catalogo == null) {
                catalogo = new HashMap<>();
                dados.listarCampos(null).forEach(c -> catalogo.put(c.chave(), c));
            }
            return catalogo;
        }

        Map<String, VigenteDto> vigentes(DadoInformadoService dados) {
            if (vigentes == null) {
                vigentes = dados.vigentes(conversa.parceriaId());
            }
            return vigentes;
        }

        /** A resolução dos textos do chat. Um marcador {@code calc:} usa as contas do modelo-base, as mesmas de todo relatório dele. */
        Resolucao resolucao(ModeloBase modelos, ResolvedorDeReferencias resolvedor) {
            if (resolucao == null) {
                Especificacao especificacao = modelos.obter(ModeloBase.ATIVACAO_EVENTO).orElseThrow();
                resolucao = resolvedor.abrir(usuario, conversa.parceriaId(), especificacao);
            }
            return resolucao;
        }
    }
}
