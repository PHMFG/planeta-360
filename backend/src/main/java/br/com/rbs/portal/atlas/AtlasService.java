package br.com.rbs.portal.atlas;

import br.com.rbs.portal.atlas.AtlasDtos.AbrirConversaRequisicao;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaResumoDto;
import br.com.rbs.portal.atlas.AtlasDtos.ConversasDaParceriaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemDaConversa;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.AtlasDtos.RelatorioDaConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.RespostaDoCartaoRequisicao;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeConversa;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeRelatorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeSolicitacao;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.contexto.AtlasContextoFabrica;
import br.com.rbs.portal.atlas.diagnostico.EtapaDoTraco;
import br.com.rbs.portal.atlas.diagnostico.RegistroDoTraco;
import br.com.rbs.portal.atlas.diagnostico.ResultadoDaEtapa;
import br.com.rbs.portal.atlas.diagnostico.Traco;
import br.com.rbs.portal.atlas.diagnostico.Traco.Etapa;
import br.com.rbs.portal.atlas.loop.DescarteDeRelatorios;
import br.com.rbs.portal.atlas.loop.ItensDaConversa;
import br.com.rbs.portal.atlas.loop.LoopDoAgente;
import br.com.rbs.portal.atlas.loop.OuvinteDoTurno;
import br.com.rbs.portal.atlas.loop.PlanoBSemLlm;
import br.com.rbs.portal.atlas.loop.TetoDaRbs;
import br.com.rbs.portal.atlas.loop.TetoDiario;
import br.com.rbs.portal.atlas.provedor.DisponibilidadeDoLlm;
import br.com.rbs.portal.atlas.relatorio.ModeloBase;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.RelatorioAtlasDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.RelatorioAtlasResumoDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.AcaoDoCartao;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.LimiteDeUsoException;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.SemPermissaoException;
import br.com.rbs.portal.comum.ServicoIndisponivelException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.dados.DadosDtos.ValorRequisicao;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * A conversa da marca com o Atlas e o cartão de perguntas (rotas 42 a 46), e a porta das rotas do relatório (47 e 48). [ATLAS]
 * Aqui mora o que é de cada pedido, não do relatório: quem pode (o Admin Patrocinador conversa com o Atlas da parceria dele; a RBS só
 * lê relatório e, com {@code atlas.rbs.ligado}, conversa na conversa dela, a do Atlas da RBS, sem parceria: veja
 * {@link AtlasDaRbsService} e {@link #abrirTurnoDaRbs}), a conversa é de quem a abriu, o turno de uma mensagem por vez em cada
 * conversa e a escolha entre o plano B (o botão) e o modelo de linguagem. [ISOLAMENTO] Parceria, conversa e cartão de outra pessoa dão 404 com a mesma frase de "não existe", também quando
 * é de outro usuário da mesma parceria. [PERMISSAO] Aba do modelo (APLICACOES) fechada dá 403 com as duas frases do projeto, em
 * todas as rotas, o relatório pronto incluído. A parceria é reconferida a cada pedido (vínculo revogado vale na chamada seguinte).
 * <p>
 * Este service NÃO abre transação: nenhuma fica aberta durante trabalho longo (a chamada ao modelo de linguagem, em especial).
 * O turno começa e termina com uma instrução cada (confirmadas na hora), e o trabalho do plano B e cada passo do loop do agente
 * são uma transação curta própria. O turno termina também em erro ({@code finally}). Com um {@code ProvedorDeLlm} (o modelo de
 * linguagem ligado), a mensagem de texto da rota 44 e a resposta do cartão da rota 46 vão para o {@link LoopDoAgente}; o botão
 * continua sendo o plano B.
 * <p>
 * As rotas 44 e 46 têm também a resposta em fluxo (SSE, {@link FluxoDoTurno}): as conferências, o turno e o que vem antes do
 * modelo rodam no pedido, com os mesmos erros HTTP; o turno com o modelo roda numa thread própria (sem transação), transmitindo,
 * e termina mesmo que o cliente vá embora.
 */
@Service
public class AtlasService {

    /** O 409 de quem manda outra mensagem enquanto a conversa ainda responde a anterior. */
    public static final String TURNO_EM_ANDAMENTO = "O Atlas ainda está respondendo nesta conversa. Espere a resposta.";
    public static final String CONVERSA_NAO_ENCONTRADA = "Não encontramos esta conversa.";
    /** O 422 de quem manda {@code atalho} ou {@code modelo} na conversa da RBS: ela não tem relatório nem botão. */
    public static final String ATALHO_NA_RBS = "O Atlas da RBS só recebe mensagem de texto.";
    public static final int LIMITE_DO_TEXTO = 1000;
    public static final int LIMITE_DE_CONVERSAS = 20;
    static final int LIMITE_DE_VALORES = 20;
    /** O {@code detail} do evento {@code erro} da resposta em fluxo (a mesma frase do 500 de sempre). */
    public static final String ERRO_NO_FLUXO = "Algo deu errado do nosso lado. Tente de novo em instantes; se continuar, avise a equipe.";

    private static final Logger log = LoggerFactory.getLogger(AtlasService.class);

    /** As threads dos turnos em fluxo: uma por turno, enquanto ele roda (cada conversa tem um turno por vez). */
    private final ExecutorService turnosEmFluxo =
            Executors.newCachedThreadPool(Thread.ofPlatform().name("atlas-fluxo-", 1).daemon().factory());

    private final AtlasRepositorio repositorio;
    private final AtlasContextoFabrica contextos;
    private final VerificadorDeAcesso verificador;
    private final PlanoBSemLlm planoB;
    private final LoopDoAgente loop;
    private final TetoDiario teto;
    private final ItensDaConversa itens;
    private final DescarteDeRelatorios descarte;
    private final RelatorioAtlasService relatorios;
    private final DisponibilidadeDoLlm llm;
    private final ModeloBase modelos;
    private final ModoDoAtlas modo;
    private final RegistroDoTraco registro;
    private final TetoDaRbs tetoDaRbs;

    public AtlasService(AtlasRepositorio repositorio, AtlasContextoFabrica contextos, VerificadorDeAcesso verificador,
                        PlanoBSemLlm planoB, LoopDoAgente loop, TetoDiario teto, ItensDaConversa itens, DescarteDeRelatorios descarte,
                        RelatorioAtlasService relatorios, DisponibilidadeDoLlm llm, ModeloBase modelos, ModoDoAtlas modo,
                        RegistroDoTraco registro, TetoDaRbs tetoDaRbs) {
        this.repositorio = repositorio;
        this.contextos = contextos;
        this.verificador = verificador;
        this.planoB = planoB;
        this.loop = loop;
        this.teto = teto;
        this.itens = itens;
        this.descarte = descarte;
        this.relatorios = relatorios;
        this.llm = llm;
        this.modelos = modelos;
        this.modo = modo;
        this.registro = registro;
        this.tetoDaRbs = tetoDaRbs;
    }

    // ---------------------------------------------------------------------
    // Rota 42: abrir a conversa
    // ---------------------------------------------------------------------

    /**
     * Rota 42. Corpo opcional: vazio abre uma conversa vazia (itens vazios); {@code atalho} MONTAR_RELATORIO com {@code modelo}
     * abre e já roda o plano B. Ordem das respostas: 403 (RBS), 404 (parceria), 403 (aba), 422 (atalho ou modelo
     * desconhecido, ou só um dos dois). Com o relatório desligado ({@code atlas.relatorio.ligado=false}), {@code atalho} ou
     * {@code modelo} dão 422 em {@code campos.atalho}.
     */
    public ConversaDto abrirConversa(UsuarioAutenticado usuario, long parceriaId, AbrirConversaRequisicao corpo,
                                     HttpServletRequest requisicao) {
        verificador.exigirPatrocinador(usuario, VerificadorDeAcesso.SO_PATROCINADOR_USA_O_ATLAS);
        verificador.exigirParceria(usuario, parceriaId);
        verificador.exigirAbaLiberada(usuario, parceriaId, relatorios.abaDoModeloPadrao());
        String modelo = null;
        if (corpo != null && (corpo.atalho() != null || corpo.modelo() != null)) {
            exigirRelatorioLigado();
            modelo = validarAtalho(corpo.atalho(), corpo.modelo());
        }
        long conversaId = repositorio.criarConversa(parceriaId, usuario.id());
        if (modelo == null) {
            return new ConversaDto(conversaId, llm.disponivel(), modo.relatorioLigado(), modo.debugDeFerramentas(),
                    modo.diagnosticoNaMarca(), List.of());
        }
        return rodarPlanoB(usuario, repositorio.conversa(conversaId).orElseThrow(), modelo, requisicao);
    }

    // ---------------------------------------------------------------------
    // Rota 43: as conversas de quem pede
    // ---------------------------------------------------------------------

    /**
     * Rota 43. As conversas DESTE usuário nesta parceria, da mexida há menos tempo para a mais antiga, até 20. Antes de listar,
     * descarta os relatórios parados há mais de 7 dias.
     */
    public ConversasDaParceriaDto listarConversas(UsuarioAutenticado usuario, long parceriaId) {
        verificador.exigirPatrocinador(usuario, VerificadorDeAcesso.SO_PATROCINADOR_USA_O_ATLAS);
        verificador.exigirParceria(usuario, parceriaId);
        verificador.exigirAbaLiberada(usuario, parceriaId, relatorios.abaDoModeloPadrao());
        if (modo.relatorioLigado()) {
            descarte.varrer(repositorio.idsDasConversasDoUsuario(parceriaId, usuario.id()));
        }
        List<ConversaResumoDto> conversas = repositorio.conversasDoUsuario(parceriaId, usuario.id(), LIMITE_DE_CONVERSAS).stream()
                .map(c -> new ConversaResumoDto(c.id(), Datas.emBrasilia(c.criadaEm()), Datas.emBrasilia(c.atualizadaEm()),
                        c.relatorioId() == null || !modo.relatorioLigado() ? null
                                : new RelatorioDaConversaDto(c.relatorioId(), c.relatorioStatus())))
                .toList();
        return new ConversasDaParceriaDto(llm.disponivel(), modo.relatorioLigado(), conversas);
    }

    // ---------------------------------------------------------------------
    // Rota 44: uma mensagem (ou o botão) numa conversa que já existe
    // ---------------------------------------------------------------------

    /**
     * Rota 44. Com {@code atalho}: roda o plano B nesta conversa; se já há relatório MONTANDO nela, continua dele (reabre o
     * cartão com as lacunas que faltam); com cartão pendente, 409. Com {@code texto}: o loop do modelo de linguagem; sem modelo de
     * linguagem, 503 com a frase "use o botão"; com cartão aberto, a mensagem nova primeiro o fecha com {@code cancel}. Ordem das
     * respostas: 403 (RBS), 404 (conversa que não é sua), 403 (aba), 422 (texto vazio, com o caractere nulo ou acima de 1.000
     * caracteres; nem texto nem atalho; os dois), 503, 429 (teto diário da parceria; só o texto, o botão não conta), 409 (turno em andamento; cartão aberto
     * no atalho). Com o relatório desligado, {@code atalho} ou {@code modelo} dão 422 em {@code campos.atalho} e só o texto vale.
     */
    public ConversaDto enviarMensagem(UsuarioAutenticado usuario, long conversaId, MensagemRequisicao corpo,
                                      HttpServletRequest requisicao) {
        return terminar(abrirTurnoDaMensagem(usuario, conversaId, corpo, requisicao));
    }

    /**
     * Rota 44 em fluxo ({@code Accept: text/event-stream}). Tudo o que dá erro na rota 44 dá o mesmo erro aqui, antes de o fluxo
     * abrir: as conferências, o turno (409) e o que vem antes do modelo (o cartão fechado e a mensagem gravada; o botão inteiro).
     * Só então a resposta vira o fluxo, e o turno com o modelo roda em outra thread até o evento {@code fim}.
     */
    public SseEmitter enviarMensagemEmFluxo(UsuarioAutenticado usuario, long conversaId, MensagemRequisicao corpo,
                                            HttpServletRequest requisicao) {
        return transmitir(abrirTurnoDaMensagem(usuario, conversaId, corpo, new PedidoGuardado(requisicao)));
    }

    private TurnoAberto abrirTurnoDaMensagem(UsuarioAutenticado usuario, long conversaId, MensagemRequisicao corpo,
                                             HttpServletRequest requisicao) {
        LinhaDeConversa conversa = conversaDoDono(usuario, conversaId);
        if (conversa.parceriaId() == null) {
            return abrirTurnoDaRbs(usuario, conversa, corpo, requisicao);   // o Atlas da RBS: outro caminho, sem parceria nem relatório
        }
        Map<String, String> erros = new LinkedHashMap<>();
        boolean temTexto = corpo != null && corpo.texto() != null;
        boolean temAtalho = corpo != null && (corpo.atalho() != null || corpo.modelo() != null);
        if (temAtalho) {
            exigirRelatorioLigado();
        }
        String modelo = null;
        if (temTexto == temAtalho) {
            erros.put(temTexto ? "atalho" : "texto", temTexto ? "Envie o texto ou o atalho, e não os dois."
                    : modo.relatorioLigado() ? "Escreva a mensagem ou use o botão Montar relatório." : "Escreva a mensagem.");
        } else if (temTexto) {
            String erroDoTexto = erroDoTexto(corpo.texto());
            if (erroDoTexto != null) {
                erros.put("texto", erroDoTexto);
            }
        } else {
            modelo = validarAtalho(corpo.atalho(), corpo.modelo());
        }
        if (!erros.isEmpty()) {
            throw new DadosInvalidosException(erros);
        }
        if (temTexto) {
            if (!llm.disponivel()) {
                throw new ServicoIndisponivelException(llm.semModelo());   // sem provedor, o botão é o caminho
            }
            // O traço do diagnóstico abre aqui: a etapa G1_LIMITES cobre o teto diário (429) e a trava do turno (409).
            Traco traco = registro.abrir(conversa.parceriaId(), conversa.id());
            Etapa limites = traco.abrir(EtapaDoTraco.G1_LIMITES).dado("tetoPorParceria", teto.teto());
            try {
                limites.dado("usadasHoje", teto.exigirFolga(conversa.parceriaId()));   // 429: o teto diário de mensagens por parceria
            } catch (LimiteDeUsoException atingido) {
                limites.dado("motivo", "TETO_DIARIO").fim(ResultadoDaEtapa.RECUSADO);
                traco.encerrar(ResultadoDaEtapa.RECUSADO, "RECUSADO_ANTES_DO_TURNO", "TETO_DIARIO");
                throw atingido;
            }
            String texto = corpo.texto().strip();
            AtlasContexto contexto = contextos.para(usuario, conversa.parceriaId(), conversa.id(), requisicao).comTraco(traco);
            return comecarTurno(usuario, conversa, traco, limites, () -> loop.prepararConversa(contexto, texto),
                    ouvinte -> loop.conduzir(contexto, ouvinte));
        }
        String modeloDoAtalho = modelo;
        AtlasContexto contexto = contextos.para(usuario, conversa.parceriaId(), conversa.id(), requisicao);
        return comecarTurno(usuario, conversa, Traco.NENHUM, Traco.NENHUM.abrir(EtapaDoTraco.G1_LIMITES),
                () -> planoB.montar(contexto, modeloDoAtalho), null);
    }

    // ---------------------------------------------------------------------
    // Rota 45: a conversa inteira
    // ---------------------------------------------------------------------

    /** Rota 45. A conversa inteira, só para o dono. Antes de abrir, descarta o relatório dela parado há mais de 7 dias. */
    public ConversaDto verConversa(UsuarioAutenticado usuario, long conversaId) {
        LinhaDeConversa conversa = conversaDoDono(usuario, conversaId);
        if (modo.relatorioLigado() && conversa.parceriaId() != null) {
            descarte.varrer(List.of(conversaId));   // a conversa da RBS não tem relatório
        }
        return dto(conversa, itens.itens(usuario, conversa, 0));
    }

    // ---------------------------------------------------------------------
    // Rota 46: a resposta do cartão
    // ---------------------------------------------------------------------

    /**
     * Rota 46. {@code accept}, {@code decline} ou {@code cancel}. Sem modelo de linguagem, em accept e decline o plano B preenche
     * as frases-modelo e finaliza o relatório no mesmo pedido. Com modelo, depois de gravar os valores a resposta da ferramenta
     * pendente vai ao histórico, com o mesmo {@code tool_call_id}, e o loop retoma (com o teto diário da parceria atingido, o
     * cartão segue o plano B em vez de dar 429). Ordem das respostas: 403 (RBS), 404 (cartão que
     * não é seu), 403 (aba), 422 (ação), 409 (cartão já respondido; turno em andamento), 422 (valores).
     */
    public ConversaDto responderCartao(UsuarioAutenticado usuario, long solicitacaoId, RespostaDoCartaoRequisicao corpo,
                                       HttpServletRequest requisicao) {
        return terminar(abrirTurnoDoCartao(usuario, solicitacaoId, corpo, requisicao));
    }

    /**
     * Rota 46 em fluxo ({@code Accept: text/event-stream}). Os mesmos erros da rota 46 antes de o fluxo abrir, inclusive o 422
     * dos valores (gravados antes do fluxo); depois, o loop retoma em outra thread até o evento {@code fim}. Sem modelo ou com o
     * teto atingido, o plano B roda inteiro antes e o fluxo só traz o {@code fim}.
     */
    public SseEmitter responderCartaoEmFluxo(UsuarioAutenticado usuario, long solicitacaoId, RespostaDoCartaoRequisicao corpo,
                                             HttpServletRequest requisicao) {
        return transmitir(abrirTurnoDoCartao(usuario, solicitacaoId, corpo, new PedidoGuardado(requisicao)));
    }

    private TurnoAberto abrirTurnoDoCartao(UsuarioAutenticado usuario, long solicitacaoId, RespostaDoCartaoRequisicao corpo,
                                           HttpServletRequest requisicao) {
        verificador.exigirPatrocinador(usuario, VerificadorDeAcesso.SO_PATROCINADOR_USA_O_ATLAS);
        LinhaDeSolicitacao cartao = repositorio.solicitacao(solicitacaoId)
                .orElseThrow(() -> new NaoEncontradoException(RelatorioAtlasService.SOLICITACAO_NAO_ENCONTRADA));
        LinhaDeConversa conversa = conversaDoUsuario(usuario, cartao.conversaId(), RelatorioAtlasService.SOLICITACAO_NAO_ENCONTRADA);

        AcaoDoCartao acao = corpo == null ? null : AcaoDoCartao.de(corpo.acao());
        if (acao == null) {
            throw new DadosInvalidosException(Map.of("acao", "Use accept, decline ou cancel."));
        }
        List<ValorRequisicao> valores = corpo.valores() == null ? List.of() : corpo.valores();
        if (valores.size() > LIMITE_DE_VALORES) {
            throw new DadosInvalidosException(Map.of("valores", "Envie até " + LIMITE_DE_VALORES + " valores."));
        }
        if (!"PENDENTE".equals(cartao.status())) {
            throw new ConflitoException(RelatorioAtlasService.CARTAO_JA_RESPONDIDO);
        }
        AtlasContexto contexto = contextos.para(usuario, conversa.parceriaId(), conversa.id(), requisicao);
        long usadas = llm.disponivel() ? teto.usadasHoje(conversa.parceriaId()) : 0;
        if (llm.disponivel() && usadas < teto.teto()) {
            Traco traco = registro.abrir(conversa.parceriaId(), conversa.id());
            Etapa limites = traco.abrir(EtapaDoTraco.G1_LIMITES).dado("tetoPorParceria", teto.teto()).dado("usadasHoje", usadas)
                    .dado("retomada", true);
            AtlasContexto doTurno = contexto.comTraco(traco);
            return comecarTurno(usuario, conversa, traco, limites, () -> loop.prepararRetomada(doTurno, solicitacaoId, acao, valores),
                    ouvinte -> loop.conduzirRetomada(doTurno, ouvinte));
        }
        // Sem modelo, ou com o teto diário atingido: o cartão respondido segue o plano B (não gasta o modelo e a pessoa não fica
        // presa a um cartão aberto).
        return comecarTurno(usuario, conversa, Traco.NENHUM, Traco.NENHUM.abrir(EtapaDoTraco.G1_LIMITES),
                () -> planoB.responder(contexto, solicitacaoId, acao, valores), null);
    }

    // ---------------------------------------------------------------------
    // Rotas 47 e 48: o relatório
    // ---------------------------------------------------------------------

    /** Rota 47. Usuários da parceria e a RBS. */
    public RelatorioAtlasDto verRelatorio(UsuarioAutenticado usuario, long relatorioId) {
        return relatorios.ver(usuario, relatorioId);
    }

    /** Rota 48. Usuários da parceria e a RBS. */
    public List<RelatorioAtlasResumoDto> listarRelatorios(UsuarioAutenticado usuario, long parceriaId) {
        return relatorios.listar(usuario, parceriaId);
    }

    // ---------------------------------------------------------------------
    // Rota 65: descartar o relatório pela tela
    // ---------------------------------------------------------------------

    /**
     * Rota 65. Descarta o relatório de trabalho que a pessoa não quer mais: {@code MONTANDO} ou {@code AGUARDANDO_RESPOSTA} vira
     * {@code DESCARTADO}, o cartão pendente vira {@code CANCELADA} e a chamada que ele deixou aberta é fechada com {@code cancel}
     * (a conversa deixa de esperar a resposta e pode começar outro relatório). Devolve o mesmo corpo da rota 47. Ordem das
     * respostas: 403 (RBS), 404 (não existe, é de outra parceria ou de outra pessoa da mesma parceria), 403 (aba do modelo fechada
     * para a marca), 409 (já está pronto; turno em andamento na conversa). Já descartado: 200 sem mudar nada, sem nova linha no
     * histórico. A seleção é por id e dono, como nas rotas 45 e 46: só descarta quem criou o relatório (que é o dono da conversa),
     * e o outro Admin Patrocinador da parceria, que vê o relatório pela rota 47, recebe 404. O turno da conversa é pego durante o
     * descarte, como nas outras rotas do turno.
     */
    public RelatorioAtlasDto descartarRelatorio(UsuarioAutenticado usuario, long relatorioId, HttpServletRequest requisicao) {
        verificador.exigirPatrocinador(usuario, VerificadorDeAcesso.SO_PATROCINADOR_USA_O_ATLAS);
        LinhaDeRelatorio relatorio = relatorios.exigirAcessoDoDono(usuario, relatorioId);
        if ("PRONTO".equals(relatorio.status())) {
            throw new ConflitoException(DescarteDeRelatorios.JA_ESTA_PRONTO);
        }
        if (!"DESCARTADO".equals(relatorio.status())) {
            comTurno(relatorio.conversaId(), () -> descarte.descartarDaTela(relatorioId, usuario, requisicao));
        }
        return relatorios.ver(usuario, relatorioId);
    }

    // ---------------------------------------------------------------------
    // Apoio
    // ---------------------------------------------------------------------

    /**
     * Um turno já começado: a trava da conversa pega e o que vem antes do modelo já feito. Falta {@code resto} (o loop com o
     * modelo; nulo no plano B, que roda inteiro antes) e encerrar o turno, uma vez só.
     */
    private final class TurnoAberto {

        final UsuarioAutenticado usuario;
        final LinhaDeConversa conversa;
        final int antes;
        final Consumer<OuvinteDoTurno> resto;
        final Traco traco;
        private final AtomicBoolean encerrado = new AtomicBoolean();

        TurnoAberto(UsuarioAutenticado usuario, LinhaDeConversa conversa, int antes, Consumer<OuvinteDoTurno> resto, Traco traco) {
            this.usuario = usuario;
            this.conversa = conversa;
            this.antes = antes;
            this.resto = resto;
            this.traco = traco;
        }

        /** Solta a trava da conversa. Uma vez só: depois disso, outro pedido pode ter começado outro turno. */
        void encerrar() {
            if (encerrado.compareAndSet(false, true)) {
                repositorio.encerrarTurno(conversa.id());
            }
        }

        /** Os itens que o turno produziu, no objeto Conversa. */
        ConversaDto conversa() {
            return dto(conversa, itens.itens(usuario, conversa, antes));
        }
    }

    /**
     * Começa o turno (409 se outro está em andamento) e faz {@code antes} nele; se {@code antes} falha (o 422 dos valores do
     * cartão, por exemplo), o turno termina e o erro sobe, ainda antes de qualquer fluxo abrir.
     */
    private TurnoAberto comecarTurno(UsuarioAutenticado usuario, LinhaDeConversa conversa, Traco traco, Etapa limites,
                                     Runnable antes, Consumer<OuvinteDoTurno> resto) {
        int ordem = repositorio.ultimaOrdem(conversa.id());
        if (!repositorio.iniciarTurno(conversa.id())) {
            limites.dado("motivo", "TURNO_EM_ANDAMENTO").fim(ResultadoDaEtapa.RECUSADO);
            traco.encerrar(ResultadoDaEtapa.RECUSADO, "RECUSADO_ANTES_DO_TURNO", "TURNO_EM_ANDAMENTO");
            throw new ConflitoException(TURNO_EM_ANDAMENTO);
        }
        limites.fim(ResultadoDaEtapa.OK);   // a etapa dos limites termina com a trava na mão
        TurnoAberto turno = new TurnoAberto(usuario, conversa, ordem, resto, traco);
        try {
            antes.run();
        } catch (RuntimeException | Error erro) {
            traco.erro(erro);
            traco.encerrar();
            turno.encerrar();
            throw erro;
        }
        return turno;
    }

    /** A resposta JSON: o resto do turno aqui mesmo, o turno termina (também em erro) e saem os itens dele. */
    private ConversaDto terminar(TurnoAberto turno) {
        try {
            if (turno.resto != null) {
                turno.resto.accept(OuvinteDoTurno.NENHUM);
            }
        } finally {
            turno.traco.encerrar();   // o traço vai ao banco antes de a trava soltar e dos itens saírem (eles trazem o id do turno)
            turno.encerrar();
        }
        return turno.conversa();
    }

    /**
     * A resposta em fluxo: o resto do turno roda em outra thread, transmitindo; no fim, o turno termina e vai o evento
     * {@code fim} com os itens. Falha inesperada vira o evento {@code erro}. O cliente que sai no meio não para o turno.
     */
    private SseEmitter transmitir(TurnoAberto turno) {
        FluxoDoTurno fluxo = new FluxoDoTurno();
        try {
            turnosEmFluxo.execute(() -> {
                try {
                    if (turno.resto != null) {
                        turno.resto.accept(fluxo);
                    }
                    turno.traco.encerrar();   // o traço vai ao banco antes dos itens, que trazem o id do turno
                    ConversaDto fim = turno.conversa();
                    turno.encerrar();   // antes do fim: quem recebe o fim já pode mandar a próxima mensagem
                    fluxo.fim(fim);
                } catch (RuntimeException | Error erro) {
                    log.error("Atlas: falha no turno em fluxo da conversa {}", turno.conversa.id(), erro);
                    turno.traco.erro(erro);
                    turno.traco.encerrar();
                    turno.encerrar();
                    fluxo.erro(500, ERRO_NO_FLUXO);
                } finally {
                    turno.traco.encerrar();
                    turno.encerrar();
                }
            });
        } catch (RuntimeException naoComecou) {
            turno.encerrar();
            throw naoComecou;
        }
        return fluxo.emissor();
    }

    @PreDestroy
    void pararOsTurnosEmFluxo() {
        turnosEmFluxo.shutdown();
    }

    /** Roda o plano B no turno da conversa e devolve só os itens que ele produziu. */
    private ConversaDto rodarPlanoB(UsuarioAutenticado usuario, LinhaDeConversa conversa, String modelo,
                                    HttpServletRequest requisicao) {
        int antes = repositorio.ultimaOrdem(conversa.id());
        AtlasContexto contexto = contextos.para(usuario, conversa.parceriaId(), conversa.id(), requisicao);
        comTurno(conversa.id(), () -> {
            planoB.montar(contexto, modelo);
            return null;
        });
        return new ConversaDto(conversa.id(), llm.disponivel(), modo.relatorioLigado(), modo.debugDeFerramentas(),
                modo.diagnosticoNaMarca(), itens.itens(usuario, conversa, antes));
    }

    /**
     * Um turno por vez em cada conversa: começa com {@code UPDATE ... SET turno_desde = now()} (só passa se ninguém está num
     * turno, ou se o turno parado passou de 5 minutos) e termina limpando a coluna, também em erro. Sem isso, duas mensagens ao
     * mesmo tempo colidiriam na ordem das mensagens ou intercalariam o histórico.
     */
    private <T> T comTurno(long conversaId, Supplier<T> trabalho) {
        if (!repositorio.iniciarTurno(conversaId)) {
            throw new ConflitoException(TURNO_EM_ANDAMENTO);
        }
        try {
            return trabalho.get();
        } finally {
            repositorio.encerrarTurno(conversaId);
        }
    }

    /**
     * A conversa, só se for do usuário e de uma parceria dele; a de outro usuário (até da mesma parceria) é como a que não existe.
     * Com a conversa conferida, a aba do modelo tem de estar aberta (403).
     */
    private LinhaDeConversa conversaDoUsuario(UsuarioAutenticado usuario, long conversaId, String frase404) {
        verificador.exigirPatrocinador(usuario, VerificadorDeAcesso.SO_PATROCINADOR_USA_O_ATLAS);
        LinhaDeConversa conversa = repositorio.conversa(conversaId).orElseThrow(() -> new NaoEncontradoException(frase404));
        if (conversa.parceriaId() == null) {
            // Conversa de um evento, sem parceria (o Atlas da RBS, V15): não é de nenhuma marca, e para a marca é como a que não existe.
            throw new NaoEncontradoException(frase404);
        }
        try {
            verificador.exigirParceria(usuario, conversa.parceriaId());
        } catch (NaoEncontradoException semVinculo) {
            throw new NaoEncontradoException(frase404);
        }
        if (!conversa.usuarioId().equals(usuario.id())) {
            throw new NaoEncontradoException(frase404);
        }
        verificador.exigirAbaLiberada(usuario, conversa.parceriaId(), relatorios.abaDoModeloPadrao());
        return conversa;
    }

    /**
     * A conversa, só se for do usuário (a regra das rotas 44, 45 e 49): a da marca, de quem a abriu; a da RBS (sem parceria), da
     * pessoa da RBS que a abriu. A de outro usuário, a de outra parceria e a que não existe dão 404 ou 403 como na rota 45, e com a
     * aba do modelo fechada, 403. É o que o diagnóstico da conversa (rota 49) usa para conferir o dono.
     */
    public LinhaDeConversa conversaDoDono(UsuarioAutenticado usuario, long conversaId) {
        return usuario.ehRbs() ? conversaDaRbs(usuario, conversaId) : conversaDoUsuario(usuario, conversaId, CONVERSA_NAO_ENCONTRADA);
    }

    /**
     * A conversa que a RBS pode usar (Atlas da RBS, M8): a dela, sem parceria. Ordem das respostas: 403 (Atlas da RBS desligado, ou a
     * conversa é de uma parceria: a RBS não conversa pelas conversas das marcas, como sempre), 404 (a conversa não existe, ou é de
     * outra pessoa da RBS). [ISOLAMENTO] A RBS tem a conversa dela e mais nenhuma.
     */
    private LinhaDeConversa conversaDaRbs(UsuarioAutenticado usuario, long conversaId) {
        if (!modo.rbsLigado()) {
            throw new SemPermissaoException(VerificadorDeAcesso.SO_PATROCINADOR_USA_O_ATLAS);
        }
        LinhaDeConversa conversa = repositorio.conversa(conversaId).orElseThrow(() -> new NaoEncontradoException(CONVERSA_NAO_ENCONTRADA));
        if (conversa.parceriaId() != null) {
            throw new SemPermissaoException(VerificadorDeAcesso.SO_PATROCINADOR_USA_O_ATLAS);
        }
        if (!conversa.usuarioId().equals(usuario.id())) {
            throw new NaoEncontradoException(CONVERSA_NAO_ENCONTRADA);
        }
        return conversa;
    }

    /**
     * O erro do texto da mensagem, ou nulo se está bom: em branco, com o caractere nulo (que o PostgreSQL não guarda: gravar falharia
     * com 500, e a conferência vem antes de gravar qualquer coisa) ou acima de {@value #LIMITE_DO_TEXTO} caracteres.
     */
    private static String erroDoTexto(String texto) {
        if (texto.isBlank()) {
            return "Escreva a mensagem.";
        }
        if (texto.indexOf('\u0000') >= 0) {
            return "A mensagem tem caracteres inválidos.";
        }
        return texto.length() > LIMITE_DO_TEXTO ? "A mensagem tem no máximo 1.000 caracteres." : null;
    }

    /**
     * O objeto Conversa. Na conversa da RBS (sem parceria) não há relatório, e o diagnóstico está à vista dela quando o diagnóstico
     * está ligado; na da marca, o relatório é o do modo e o diagnóstico precisa também de {@code atlas.diagnostico.marca}.
     */
    private ConversaDto dto(LinhaDeConversa conversa, List<ItemDaConversa> itensDaConversa) {
        boolean daRbs = conversa.parceriaId() == null;
        return new ConversaDto(conversa.id(), llm.disponivel(), !daRbs && modo.relatorioLigado(), modo.debugDeFerramentas(),
                daRbs ? modo.diagnosticoLigado() : modo.diagnosticoNaMarca(), itensDaConversa);
    }

    /**
     * O turno da mensagem de texto na conversa da RBS (Atlas da RBS, M8). Ordem das respostas: 403 e 404 (a conversa, antes daqui),
     * 422 (atalho ou modelo, que a RBS não tem; texto vazio, com o caractere nulo ou acima de 1.000 caracteres), 503 (sem modelo de
     * linguagem), 429 (o teto diário da pessoa, {@code atlas.rbs.teto-diario}) e 409 (turno em andamento). O contexto não tem parceria,
     * nem cota, nem aba, nem JEV; o teto por parceria não se aplica.
     */
    private TurnoAberto abrirTurnoDaRbs(UsuarioAutenticado usuario, LinhaDeConversa conversa, MensagemRequisicao corpo,
                                        HttpServletRequest requisicao) {
        Map<String, String> erros = new LinkedHashMap<>();
        boolean temTexto = corpo != null && corpo.texto() != null;
        boolean temAtalho = corpo != null && (corpo.atalho() != null || corpo.modelo() != null);
        if (temAtalho) {
            erros.put("atalho", ATALHO_NA_RBS);
        }
        if (!temTexto) {
            if (!temAtalho) {
                erros.put("texto", "Escreva a mensagem.");
            }
        } else {
            String erroDoTexto = erroDoTexto(corpo.texto());
            if (erroDoTexto != null) {
                erros.put("texto", erroDoTexto);
            }
        }
        if (!erros.isEmpty()) {
            throw new DadosInvalidosException(erros);
        }
        if (!llm.disponivel()) {
            throw new ServicoIndisponivelException(DisponibilidadeDoLlm.SEM_MODELO_NO_CHAT);
        }
        Traco traco = registro.abrir(null, conversa.id());
        Etapa limites = traco.abrir(EtapaDoTraco.G1_LIMITES).dado("tetoPorUsuario", tetoDaRbs.teto());
        try {
            limites.dado("usadasHoje", tetoDaRbs.exigirFolga(usuario.id()));   // 429: o teto diário da pessoa da RBS
        } catch (LimiteDeUsoException atingido) {
            limites.dado("motivo", "TETO_DIARIO").fim(ResultadoDaEtapa.RECUSADO);
            traco.encerrar(ResultadoDaEtapa.RECUSADO, "RECUSADO_ANTES_DO_TURNO", "TETO_DIARIO");
            throw atingido;
        }
        String texto = corpo.texto().strip();
        AtlasContexto contexto = contextos.paraARbs(usuario, conversa.eventoId(), conversa.id(), requisicao).comTraco(traco);
        return comecarTurno(usuario, conversa, traco, limites, () -> loop.prepararConversa(contexto, texto),
                ouvinte -> loop.conduzir(contexto, ouvinte));
    }

    /** Com o relatório desligado, o atalho e o modelo não existem: 422 em {@code campos.atalho}. */
    private void exigirRelatorioLigado() {
        if (!modo.relatorioLigado()) {
            throw new DadosInvalidosException(Map.of("atalho", ModoDoAtlas.RELATORIO_INDISPONIVEL));
        }
    }

    /** O atalho do botão: MONTAR_RELATORIO com um modelo que existe. Devolve o modelo; senão 422. */
    private String validarAtalho(String atalho, String modelo) {
        Map<String, String> erros = new LinkedHashMap<>();
        if (!AtlasDtos.ATALHO_MONTAR_RELATORIO.equals(atalho)) {
            erros.put("atalho", "Atalho desconhecido. Use " + AtlasDtos.ATALHO_MONTAR_RELATORIO + ".");
        }
        if (modelo == null || modelo.isBlank()) {
            erros.put("modelo", "Informe o modelo.");
        } else if (!modelos.existe(modelo)) {
            erros.put("modelo", "Modelo desconhecido. Use " + ModeloBase.ATIVACAO_EVENTO + ".");
        }
        if (!erros.isEmpty()) {
            throw new DadosInvalidosException(erros);
        }
        return modelo;
    }
}
