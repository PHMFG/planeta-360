package br.com.rbs.portal.atlas.loop;

import br.com.rbs.portal.atlas.AtlasDtos.CampoDoCartaoDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemAviso;
import br.com.rbs.portal.atlas.AtlasDtos.ItemCartao;
import br.com.rbs.portal.atlas.AtlasDtos.ItemDaConversa;
import br.com.rbs.portal.atlas.AtlasDtos.ItemRelatorioPronto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemTexto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemUsuario;
import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeConversa;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeMensagem;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeRelatorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeSolicitacao;
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
 *   <li>mensagem {@code user} &rarr; item {@code usuario};</li>
 *   <li>mensagem {@code assistant} com texto &rarr; item {@code texto}, com os marcadores {@code {{ref}}} trocados pelos valores
 *       (o que não tem valor vira "indisponível"); nunca sai marcador cru nem número inventado;</li>
 *   <li>cada pedido {@code relatorio_solicitar_campos} &rarr; item {@code cartao}, com os dados de {@code atlas_solicitacao}
 *       (achada pelo {@code tool_call_id}) e o estado de agora, os campos do catálogo e o valor vigente de cada um;</li>
 *   <li>a resposta de {@code relatorio_finalizar} &rarr; item {@code relatorio_pronto};</li>
 *   <li>a resposta de {@code relatorio_solicitar_campos} com {@code cancel} &rarr; item {@code aviso} ("Cartão fechado...").</li>
 * </ul>
 * Só as mensagens depois de {@code desdeOrdem} viram item (o turno); com 0, a conversa inteira.
 */
@Component
public class ItensDaConversa {

    private final AtlasRepositorio repositorio;
    private final DadoInformadoService dados;
    private final ResolvedorDeReferencias resolvedor;
    private final ModeloBase modelos;

    public ItensDaConversa(AtlasRepositorio repositorio, DadoInformadoService dados, ResolvedorDeReferencias resolvedor,
                           ModeloBase modelos) {
        this.repositorio = repositorio;
        this.dados = dados;
        this.resolvedor = resolvedor;
        this.modelos = modelos;
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
        Map<Long, Especificacao> especificacoes = new HashMap<>();
        Contexto contexto = new Contexto(usuario, conversa);

        List<ItemDaConversa> itens = new ArrayList<>();
        for (LinhaDeMensagem mensagem : mensagens) {
            boolean doTurno = mensagem.ordem() > desdeOrdem;
            switch (mensagem.papel()) {
                case "user" -> {
                    if (doTurno) {
                        itens.add(new ItemUsuario(mensagem.conteudo()));
                    }
                }
                case "assistant" -> {
                    if (doTurno && mensagem.conteudo() != null && !mensagem.conteudo().isBlank()) {
                        itens.add(new ItemTexto(contexto.resolucao(modelos, resolvedor).paraExibicao(mensagem.conteudo())));
                    }
                    for (Map<String, Object> chamada : chamadasDe(mensagem)) {
                        String id = (String) chamada.get("id");
                        String nome = nomeDaFerramenta(chamada);
                        ferramentas.put(id, nome);
                        LinhaDeSolicitacao cartao = cartoes.get(id);
                        if (doTurno && "relatorio_solicitar_campos".equals(nome) && cartao != null) {
                            itens.add(cartao(contexto, cartao, especificacoes));
                        }
                    }
                }
                default -> {
                    if (doTurno) {
                        itemDaResposta(mensagem, ferramentas.get(mensagem.toolCallId())).ifPresent(itens::add);
                    }
                }
            }
        }
        return itens;
    }

    private Optional<ItemDaConversa> itemDaResposta(LinhaDeMensagem mensagem, String ferramenta) {
        if (ferramenta == null || mensagem.conteudo() == null) {
            return Optional.empty();
        }
        Map<String, Object> resultado = AtlasJson.ler(mensagem.conteudo(), new TypeReference<Map<String, Object>>() {
        });
        if ("relatorio_finalizar".equals(ferramenta) && "PRONTO".equals(resultado.get("status"))) {
            return Optional.of(new ItemRelatorioPronto(((Number) resultado.get("relatorioId")).longValue(),
                    (String) resultado.get("titulo")));
        }
        if ("relatorio_solicitar_campos".equals(ferramenta) && "cancel".equals(resultado.get("acao"))) {
            return Optional.of(new ItemAviso(PlanoBSemLlm.AVISO_CARTAO_FECHADO));
        }
        return Optional.empty();
    }

    private ItemCartao cartao(Contexto contexto, LinhaDeSolicitacao cartao, Map<Long, Especificacao> especificacoes) {
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
        return new ItemCartao(cartao.id(), cartao.relatorioId(), cartao.mensagem(), cartao.status(), campos);
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
