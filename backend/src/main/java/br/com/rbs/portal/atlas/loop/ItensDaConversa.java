package br.com.rbs.portal.atlas.loop;

import br.com.rbs.portal.atlas.AtlasDtos.CampoDoCartaoDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemAviso;
import br.com.rbs.portal.atlas.AtlasDtos.FeriadoConsiderado;
import br.com.rbs.portal.atlas.AtlasDtos.ItemCalculo;
import br.com.rbs.portal.atlas.AtlasDtos.ItemCartao;
import br.com.rbs.portal.atlas.AtlasDtos.VariavelDoCalculo;
import br.com.rbs.portal.atlas.AtlasDtos.DadosDaFerramenta;
import br.com.rbs.portal.atlas.AtlasDtos.ItemDaConversa;
import br.com.rbs.portal.atlas.AtlasDtos.ItemFerramenta;
import br.com.rbs.portal.atlas.AtlasDtos.ItemModelo;
import br.com.rbs.portal.atlas.AtlasDtos.ItemPedidoRelatorio;
import br.com.rbs.portal.atlas.AtlasDtos.ItemRaciocinio;
import br.com.rbs.portal.atlas.AtlasDtos.ItemRelatorioPronto;
import br.com.rbs.portal.atlas.AtlasDtos.FonteDoTexto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemTexto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemUsuario;
import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeChamada;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeFonte;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeConversa;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeMensagem;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeRelatorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeSolicitacao;
import br.com.rbs.portal.atlas.ModoDoAtlas;
import br.com.rbs.portal.atlas.checagem.Frases;
import br.com.rbs.portal.atlas.diagnostico.TracoRepositorio;
import br.com.rbs.portal.atlas.ferramentas.Calcular;
import br.com.rbs.portal.atlas.ferramentas.ContarDias;
import br.com.rbs.portal.atlas.ferramentas.GerarModelo;
import br.com.rbs.portal.atlas.ferramentas.ModelosEmBranco;
import br.com.rbs.portal.atlas.ferramentas.PedidoDeRelatorio;
import br.com.rbs.portal.atlas.provedor.DisponibilidadeDoLlm;
import br.com.rbs.portal.atlas.relatorio.ContaDoChat;
import br.com.rbs.portal.atlas.relatorio.Especificacao;
import br.com.rbs.portal.atlas.relatorio.ModeloBase;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Resolucao;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.dados.DadoInformadoService;
import br.com.rbs.portal.dados.DadosDtos.CampoDto;
import br.com.rbs.portal.dados.DadosDtos.VigenteDto;
import br.com.rbs.portal.relatorio.RelatorioService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
 *       frases do servidor: a mensagem com {@code sem_modelo} (o plano B, a recusa de fora de escopo, a resposta do JEV) e as frases
 *       fixas do loop ({@link LoopDoAgente#FRASES_DO_SERVIDOR}); verdadeiro no resto, que só o modelo escreve;</li>
 *   <li>cada pedido {@code relatorio_solicitar_campos} &rarr; item {@code cartao}, com os dados de {@code atlas_solicitacao}
 *       (achada pelo {@code tool_call_id}) e o estado de agora, os campos do catálogo e o valor vigente de cada um;</li>
 *   <li>a resposta de {@code relatorio_finalizar} &rarr; item {@code relatorio_pronto};</li>
 *   <li>a resposta de {@code relatorio_solicitar_campos} com {@code cancel} &rarr; item {@code aviso} ("Cartão fechado..."): sem
 *       modelo de linguagem, "Use Montar relatório para continuar"; com ele ligado agora (o mesmo {@code llmDisponivel} da
 *       resposta), "Quando tiver os números, peça para continuar". Se a resposta traz {@code relatorioDescartado} (o cartão se fechou
 *       porque o relatório foi descartado), o aviso é outro: "Relatório descartado. Você pode pedir um novo quando quiser.".</li>
 *   <li>a resposta de {@code pedido_de_relatorio} &rarr; item {@code pedido_relatorio}, e a de {@code gerar_modelo} &rarr; item
 *       {@code modelo}, com o relatório ligado ou desligado. O item do pedido traz {@code relatorioOficial}, {@code modelos} e o
 *       texto de agora, relidos (a RBS pode ter disponibilizado o relatório depois); o do modelo traz o que a ferramenta entregou.
 *       Resposta com erro não gera item.</li>
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
    private final ModelosEmBranco modelosEmBranco;
    private final RelatorioService relatorios;

    public ItensDaConversa(AtlasRepositorio repositorio, DadoInformadoService dados, ResolvedorDeReferencias resolvedor,
                           ModeloBase modelos, DisponibilidadeDoLlm llm, ModoDoAtlas modo, TracoRepositorio tracos,
                           ModelosEmBranco modelosEmBranco, RelatorioService relatorios) {
        this.tracos = tracos;
        this.modelosEmBranco = modelosEmBranco;
        this.relatorios = relatorios;
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

        // As fontes citadas em cada resposta (atlas_fonte), de uma vez só para a conversa inteira.
        Map<Long, List<LinhaDeFonte>> fontes = new HashMap<>();
        repositorio.fontesDaConversa(conversa.id()).forEach(f -> fontes.computeIfAbsent(f.mensagemId(), id -> new ArrayList<>()).add(f));

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
                        List<LinhaDeFonte> fontesDaMensagem = fontes.getOrDefault(mensagem.id(), List.of());
                        // As contas desta mensagem, e só as dela: duas mensagens podem ter contas de mesmo nome e valores diferentes.
                        Resolucao resolucao = contexto.resolucao(modelos, resolvedor);
                        resolucao.esquecerContas();
                        fontesDaMensagem.stream().filter(f -> "CALCULO".equals(f.tipo()) && f.dados() != null)
                                .map(f -> ContaDoChat.deDados(AtlasJson.ler(f.dados(), new TypeReference<Map<String, Object>>() {
                                }))).flatMap(Optional::stream).forEach(resolucao::registrarConta);
                        itens.add(new ItemTexto(resolucao.paraExibicao(mensagem.conteudo()),
                                escritoPeloModelo(mensagem), turno,
                                fontesDoTexto(mensagem.conteudo(), fontesDaMensagem)));
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
                        itemDaResposta(mensagem, ferramentas.get(mensagem.toolCallId()), doPedido == null ? null : turnos.get(doPedido),
                                contexto).ifPresent(itens::add);
                    }
                }
            }
        }
        return itens;
    }

    /**
     * As fontes que o texto cita, na ordem em que a primeira citação aparece. Só entra o que está em {@code atlas_fonte} para essa
     * mensagem (o servidor só grava a fonte de uma citação que existia no turno).
     */
    private static List<FonteDoTexto> fontesDoTexto(String texto, List<LinhaDeFonte> gravadas) {
        if (gravadas.isEmpty()) {
            return List.of();
        }
        Map<String, LinhaDeFonte> porMarcador = new HashMap<>();
        gravadas.forEach(f -> porMarcador.put(f.marcador(), f));
        List<FonteDoTexto> citadas = new ArrayList<>();
        for (String marcador : new LinkedHashSet<>(Frases.citacoes(texto))) {
            LinhaDeFonte f = porMarcador.get(marcador);
            if (f != null) {
                citadas.add(new FonteDoTexto(f.marcador(), f.tipo(), f.documentoId(), f.titulo(), f.versao(), f.pagina(), f.secao(),
                        f.trechoId(), f.substituido(), f.revogado()));
            }
        }
        return citadas;
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

    private Optional<ItemDaConversa> itemDaResposta(LinhaDeMensagem mensagem, String ferramenta, String turno, Contexto contexto) {
        if (ferramenta == null || mensagem.conteudo() == null) {
            return Optional.empty();
        }
        // A conta, a contagem de dias e as respostas diretas valem com o relatório ligado ou desligado.
        if (Calcular.NOME.equals(ferramenta) || ContarDias.NOME.equals(ferramenta)) {
            return itemDeCalculo(mensagem, ferramenta, turno);
        }
        if (PedidoDeRelatorio.NOME.equals(ferramenta)) {
            return itemDoPedidoDeRelatorio(mensagem, turno, contexto);
        }
        if (GerarModelo.NOME.equals(ferramenta)) {
            return itemDoModelo(mensagem, turno);
        }
        if (!modo.relatorioLigado()) {
            return Optional.empty();   // só chat: o relatório pronto e o aviso do cartão de uma conversa antiga não saem
        }
        Map<String, Object> resultado = AtlasJson.ler(mensagem.conteudo(), new TypeReference<Map<String, Object>>() {
        });
        if ("relatorio_finalizar".equals(ferramenta) && "PRONTO".equals(resultado.get("status"))) {
            return Optional.of(new ItemRelatorioPronto(((Number) resultado.get("relatorioId")).longValue(),
                    (String) resultado.get("titulo"), turno));
        }
        if ("relatorio_solicitar_campos".equals(ferramenta) && "cancel".equals(resultado.get("acao"))) {
            if (Boolean.TRUE.equals(resultado.get(RelatorioAtlasService.RELATORIO_DESCARTADO))) {
                return Optional.of(new ItemAviso(PlanoBSemLlm.AVISO_RELATORIO_DESCARTADO));   // o relatório se foi: não é um cartão fechado
            }
            return Optional.of(new ItemAviso(llm.disponivel() ? PlanoBSemLlm.AVISO_CARTAO_FECHADO_COM_MODELO
                    : PlanoBSemLlm.AVISO_CARTAO_FECHADO));
        }
        return Optional.empty();
    }

    /**
     * A resposta de uma ferramenta que deu certo como mapa, se for do {@code tipo} esperado; erro de ferramenta ({@code {"erro": ...}}),
     * texto que não é um objeto JSON e resposta de outro tipo não geram item.
     */
    private static Optional<Map<String, Object>> respostaDoTipo(LinhaDeMensagem mensagem, String tipo) {
        try {
            Map<String, Object> resposta = AtlasJson.ler(mensagem.conteudo(), new TypeReference<Map<String, Object>>() {
            });
            return resposta != null && !resposta.containsKey("erro") && tipo.equals(resposta.get("tipo")) ? Optional.of(resposta)
                    : Optional.empty();
        } catch (RuntimeException naoEObjeto) {
            return Optional.empty();
        }
    }

    /**
     * O item do {@code pedido_de_relatorio}. O que o histórico guardou só prova que o pedido foi respondido: {@code relatorioOficial},
     * os modelos e o texto que depende deles são relidos agora, pelo mesmo critério da ferramenta (a rota 35 e a aba Pesquisa), porque o
     * relatório pode ter sido disponibilizado, ou a Pesquisa aberta ou fechada, depois do turno.
     */
    private Optional<ItemDaConversa> itemDoPedidoDeRelatorio(LinhaDeMensagem mensagem, String turno, Contexto contexto) {
        if (respostaDoTipo(mensagem, PedidoDeRelatorio.TIPO_DO_RESULTADO).isEmpty()) {
            return Optional.empty();
        }
        long parceriaId = contexto.conversa.parceriaId();
        boolean relatorioOficial = relatorios.temVersaoParaVer(contexto.usuario, parceriaId);
        return Optional.of(new ItemPedidoRelatorio(PedidoDeRelatorio.texto(relatorioOficial, modo.relatorioLigado()), relatorioOficial,
                modelosEmBranco.oferecidos(contexto.usuario, parceriaId), turno));
    }

    /** O item do {@code gerar_modelo}: o que a ferramenta entregou, sem o tipo do modelo (o {@code tipo} do item é {@code modelo}). */
    private static Optional<ItemDaConversa> itemDoModelo(LinhaDeMensagem mensagem, String turno) {
        return respostaDoTipo(mensagem, GerarModelo.TIPO_DO_RESULTADO).map(resposta -> new ItemModelo((String) resposta.get("titulo"),
                (String) resposta.get("conteudo"), (String) resposta.get("nomeArquivo"), turno));
    }

    /**
     * O "Como calculei" de uma conta ou de uma contagem de dias: só da resposta que deu certo. Erro de ferramenta
     * ({@code {"erro": ...}}) e conta indisponível ({@code {"indisponivel": true, ...}}) não geram item: não há conta a mostrar.
     */
    @SuppressWarnings("unchecked")
    private Optional<ItemDaConversa> itemDeCalculo(LinhaDeMensagem mensagem, String ferramenta, String turno) {
        Map<String, Object> resposta;
        try {
            resposta = AtlasJson.ler(mensagem.conteudo(), new TypeReference<Map<String, Object>>() {
            });
        } catch (RuntimeException naoEObjeto) {
            return Optional.empty();
        }
        if (resposta.containsKey("erro") || Boolean.TRUE.equals(resposta.get("indisponivel")) || !(resposta.get("marcador") instanceof String marcador)) {
            return Optional.empty();
        }
        if (ContarDias.NOME.equals(ferramenta)) {
            List<FeriadoConsiderado> feriados = new ArrayList<>();
            for (Object feriado : (List<Object>) resposta.getOrDefault("feriadosConsiderados", List.of())) {
                Map<String, Object> item = (Map<String, Object>) feriado;
                feriados.add(new FeriadoConsiderado((String) item.get("data"), (String) item.get("nome")));
            }
            return Optional.of(new ItemCalculo(marcador, ferramenta, null, null, List.of(), (String) resposta.get("resultado"),
                    (String) resposta.get("regra"), feriados, turno));
        }
        List<VariavelDoCalculo> variaveis = new ArrayList<>();
        for (Object variavel : (List<Object>) resposta.getOrDefault("variaveis", List.of())) {
            Map<String, Object> item = (Map<String, Object>) variavel;
            variaveis.add(new VariavelDoCalculo((String) item.get("nome"), (String) item.get("origem"), (String) item.get("valor")));
        }
        String ref = (String) resposta.get("ref");
        return Optional.of(new ItemCalculo(marcador, ferramenta, ref == null ? null : ref.substring(ref.indexOf(':') + 1),
                (String) resposta.get("script"), variaveis, (String) resposta.get("valorFormatado"), null, List.of(), turno));
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
     * O texto foi escrito pelo modelo de linguagem? Não, se o servidor o escreveu sem o modelo (a coluna {@code sem_modelo}, V15: o
     * plano B, a recusa de fora de escopo, a resposta direta do JEV, a resposta fixa do cartão) ou se é uma das frases fixas que o
     * loop grava sozinho.
     */
    private static boolean escritoPeloModelo(LinhaDeMensagem mensagem) {
        return !mensagem.semModelo() && !LoopDoAgente.FRASES_DO_SERVIDOR.contains(mensagem.conteudo());
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
