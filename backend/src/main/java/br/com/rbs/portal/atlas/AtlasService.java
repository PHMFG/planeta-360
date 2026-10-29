package br.com.rbs.portal.atlas;

import br.com.rbs.portal.atlas.AtlasDtos.AbrirConversaRequisicao;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaResumoDto;
import br.com.rbs.portal.atlas.AtlasDtos.ConversasDaParceriaDto;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.AtlasDtos.RelatorioDaConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.RespostaDoCartaoRequisicao;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeConversa;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeSolicitacao;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.contexto.AtlasContextoFabrica;
import br.com.rbs.portal.atlas.loop.DescarteDeRelatorios;
import br.com.rbs.portal.atlas.loop.ItensDaConversa;
import br.com.rbs.portal.atlas.loop.PlanoBSemLlm;
import br.com.rbs.portal.atlas.provedor.DisponibilidadeDoLlm;
import br.com.rbs.portal.atlas.relatorio.ModeloBase;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.RelatorioAtlasDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.RelatorioAtlasResumoDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.AcaoDoCartao;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.ServicoIndisponivelException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.dados.DadosDtos.ValorRequisicao;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * A conversa da marca com o Atlas e o cartão de perguntas (rotas 42 a 46), e a porta das rotas do relatório (47 e 48). [ATLAS]
 * Aqui mora o que é de cada pedido, não do relatório: quem pode (só o Admin Patrocinador conversa; a RBS só lê relatório), a
 * conversa é de quem a abriu, o turno de uma mensagem por vez em cada conversa e a escolha entre o plano B (o botão) e o modelo
 * de linguagem. [ISOLAMENTO] Parceria, conversa e cartão de outra pessoa dão 404 com a mesma frase de "não existe", também quando
 * é de outro usuário da mesma parceria. [PERMISSAO] Aba do modelo (APLICACOES) fechada dá 403 com as duas frases do projeto, em
 * todas as rotas, o relatório pronto incluído. A parceria é reconferida a cada pedido (vínculo revogado vale na chamada seguinte).
 * <p>
 * Este service NÃO abre transação: nenhuma fica aberta durante trabalho longo. O turno começa e termina com uma instrução cada
 * (confirmadas na hora), e o trabalho do plano B é uma transação curta própria. O turno termina também em erro ({@code finally}).
 */
@Service
public class AtlasService {

    /** O 409 de quem manda outra mensagem enquanto a conversa ainda responde a anterior. */
    public static final String TURNO_EM_ANDAMENTO = "O Atlas ainda está respondendo nesta conversa. Espere a resposta.";
    public static final String CONVERSA_NAO_ENCONTRADA = "Não encontramos esta conversa.";
    public static final int LIMITE_DO_TEXTO = 1000;
    public static final int LIMITE_DE_CONVERSAS = 20;
    static final int LIMITE_DE_VALORES = 20;

    private final AtlasRepositorio repositorio;
    private final AtlasContextoFabrica contextos;
    private final VerificadorDeAcesso verificador;
    private final PlanoBSemLlm planoB;
    private final ItensDaConversa itens;
    private final DescarteDeRelatorios descarte;
    private final RelatorioAtlasService relatorios;
    private final DisponibilidadeDoLlm llm;
    private final ModeloBase modelos;

    public AtlasService(AtlasRepositorio repositorio, AtlasContextoFabrica contextos, VerificadorDeAcesso verificador,
                        PlanoBSemLlm planoB, ItensDaConversa itens, DescarteDeRelatorios descarte,
                        RelatorioAtlasService relatorios, DisponibilidadeDoLlm llm, ModeloBase modelos) {
        this.repositorio = repositorio;
        this.contextos = contextos;
        this.verificador = verificador;
        this.planoB = planoB;
        this.itens = itens;
        this.descarte = descarte;
        this.relatorios = relatorios;
        this.llm = llm;
        this.modelos = modelos;
    }

    // ---------------------------------------------------------------------
    // Rota 42: abrir a conversa
    // ---------------------------------------------------------------------

    /**
     * Rota 42. Corpo opcional: vazio abre uma conversa vazia (itens vazios); {@code atalho} MONTAR_RELATORIO com {@code modelo}
     * abre e já roda o plano B. Ordem das respostas: 403 (RBS), 404 (parceria), 403 (aba), 422 (atalho ou modelo
     * desconhecido, ou só um dos dois).
     */
    public ConversaDto abrirConversa(UsuarioAutenticado usuario, long parceriaId, AbrirConversaRequisicao corpo,
                                     HttpServletRequest requisicao) {
        verificador.exigirPatrocinador(usuario, VerificadorDeAcesso.SO_PATROCINADOR_USA_O_ATLAS);
        verificador.exigirParceria(usuario, parceriaId);
        verificador.exigirAbaLiberada(usuario, parceriaId, relatorios.abaDoModeloPadrao());
        String modelo = null;
        if (corpo != null && (corpo.atalho() != null || corpo.modelo() != null)) {
            modelo = validarAtalho(corpo.atalho(), corpo.modelo());
        }
        long conversaId = repositorio.criarConversa(parceriaId, usuario.id());
        if (modelo == null) {
            return new ConversaDto(conversaId, llm.disponivel(), List.of());
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
        descarte.varrer(repositorio.idsDasConversasDoUsuario(parceriaId, usuario.id()));
        List<ConversaResumoDto> conversas = repositorio.conversasDoUsuario(parceriaId, usuario.id(), LIMITE_DE_CONVERSAS).stream()
                .map(c -> new ConversaResumoDto(c.id(), Datas.emBrasilia(c.criadaEm()), Datas.emBrasilia(c.atualizadaEm()),
                        c.relatorioId() == null ? null : new RelatorioDaConversaDto(c.relatorioId(), c.relatorioStatus())))
                .toList();
        return new ConversasDaParceriaDto(llm.disponivel(), conversas);
    }

    // ---------------------------------------------------------------------
    // Rota 44: uma mensagem (ou o botão) numa conversa que já existe
    // ---------------------------------------------------------------------

    /**
     * Rota 44. Com {@code atalho}: roda o plano B nesta conversa; se já há relatório MONTANDO nela, continua dele (reabre o
     * cartão com as lacunas que faltam); com cartão pendente, 409. Com {@code texto} e sem modelo de linguagem: 503 com a frase
     * "use o botão". Ordem das respostas: 403 (RBS), 404 (conversa que não é sua), 403 (aba), 422 (texto vazio ou acima de 1.000
     * caracteres; nem texto nem atalho; os dois), 503, 409 (turno em andamento; cartão aberto).
     */
    public ConversaDto enviarMensagem(UsuarioAutenticado usuario, long conversaId, MensagemRequisicao corpo,
                                      HttpServletRequest requisicao) {
        LinhaDeConversa conversa = conversaDoUsuario(usuario, conversaId, CONVERSA_NAO_ENCONTRADA);
        Map<String, String> erros = new LinkedHashMap<>();
        boolean temTexto = corpo != null && corpo.texto() != null;
        boolean temAtalho = corpo != null && (corpo.atalho() != null || corpo.modelo() != null);
        String modelo = null;
        if (temTexto == temAtalho) {
            erros.put(temTexto ? "atalho" : "texto", temTexto ? "Envie o texto ou o atalho, e não os dois."
                    : "Escreva a mensagem ou use o botão Montar relatório.");
        } else if (temTexto) {
            if (corpo.texto().isBlank()) {
                erros.put("texto", "Escreva a mensagem.");
            } else if (corpo.texto().length() > LIMITE_DO_TEXTO) {
                erros.put("texto", "A mensagem tem no máximo 1.000 caracteres.");
            }
        } else {
            modelo = validarAtalho(corpo.atalho(), corpo.modelo());
        }
        if (!erros.isEmpty()) {
            throw new DadosInvalidosException(erros);
        }
        if (temTexto) {
            // [ATLAS] A etapa seguinte troca esta frase pelo loop do modelo de linguagem; sem provedor, o botão é o caminho.
            throw new ServicoIndisponivelException(DisponibilidadeDoLlm.SEM_MODELO);
        }
        return rodarPlanoB(usuario, conversa, modelo, requisicao);
    }

    // ---------------------------------------------------------------------
    // Rota 45: a conversa inteira
    // ---------------------------------------------------------------------

    /** Rota 45. A conversa inteira, só para o dono. Antes de abrir, descarta o relatório dela parado há mais de 7 dias. */
    public ConversaDto verConversa(UsuarioAutenticado usuario, long conversaId) {
        LinhaDeConversa conversa = conversaDoUsuario(usuario, conversaId, CONVERSA_NAO_ENCONTRADA);
        descarte.varrer(List.of(conversaId));
        return new ConversaDto(conversaId, llm.disponivel(), itens.itens(usuario, conversa, 0));
    }

    // ---------------------------------------------------------------------
    // Rota 46: a resposta do cartão
    // ---------------------------------------------------------------------

    /**
     * Rota 46. {@code accept}, {@code decline} ou {@code cancel}. Em accept e decline o plano B preenche as frases-modelo e
     * finaliza o relatório no mesmo pedido. Ordem das respostas: 403 (RBS), 404 (cartão que não é seu), 403 (aba), 422 (ação),
     * 409 (cartão já respondido; turno em andamento), 422 (valores).
     */
    public ConversaDto responderCartao(UsuarioAutenticado usuario, long solicitacaoId, RespostaDoCartaoRequisicao corpo,
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
        int antes = repositorio.ultimaOrdem(conversa.id());
        AtlasContexto contexto = contextos.para(usuario, conversa.parceriaId(), conversa.id(), requisicao);
        comTurno(conversa.id(), () -> {
            planoB.responder(contexto, solicitacaoId, acao, valores);
            return null;
        });
        return new ConversaDto(conversa.id(), llm.disponivel(), itens.itens(usuario, conversa, antes));
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
    // Apoio
    // ---------------------------------------------------------------------

    /** Roda o plano B no turno da conversa e devolve só os itens que ele produziu. */
    private ConversaDto rodarPlanoB(UsuarioAutenticado usuario, LinhaDeConversa conversa, String modelo,
                                    HttpServletRequest requisicao) {
        int antes = repositorio.ultimaOrdem(conversa.id());
        AtlasContexto contexto = contextos.para(usuario, conversa.parceriaId(), conversa.id(), requisicao);
        comTurno(conversa.id(), () -> {
            planoB.montar(contexto, modelo);
            return null;
        });
        return new ConversaDto(conversa.id(), llm.disponivel(), itens.itens(usuario, conversa, antes));
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
