package br.com.rbs.portal.pesquisa;

import static br.com.rbs.portal.pesquisa.PesquisaDtos.LIMITE_DE_PERGUNTAS;

import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.SemPermissaoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.pesquisa.PesquisaDtos.DecisaoRequisicao;
import br.com.rbs.portal.pesquisa.PesquisaDtos.EnviarPerguntasRequisicao;
import br.com.rbs.portal.pesquisa.PesquisaDtos.PerguntaDto;
import br.com.rbs.portal.pesquisa.PesquisaDtos.PerguntaRequisicao;
import br.com.rbs.portal.pesquisa.PesquisaDtos.PesquisaDaParceriaDto;
import br.com.rbs.portal.pesquisa.PesquisaDtos.PesquisaDto;
import br.com.rbs.portal.pesquisa.PesquisaDtos.ResultadoDto;
import br.com.rbs.portal.pesquisa.PesquisaDtos.UsuarioResumoDto;
import br.com.rbs.portal.pesquisa.PesquisaRepositorio.LinhaDePergunta;
import br.com.rbs.portal.pesquisa.PesquisaRepositorio.LinhaDePesquisa;
import br.com.rbs.portal.pesquisa.PesquisaRepositorio.PerguntaTravada;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regras da pesquisa da ativação: o patrocinador envia até 3 perguntas, a RBS aprova ou pede ajuste, e só pergunta
 * aprovada mostra resultado. Atende o item 5 da Entrega 2. [VALIDACAO] [PERMISSAO] [ISOLAMENTO] [HISTORICO]
 * A pergunta aprovada nunca muda: o texto que a RBS aprovou é o texto sobre o qual a carga de resultados foi feita.
 */
@Service
public class PesquisaService {

    static final String ABA_NAO_LIBERADA = "Esta aba ainda não foi liberada para a sua parceria.";

    private static final String ENTIDADE = "pesquisa_pergunta";

    private final PesquisaRepositorio repositorio;
    private final VerificadorDeAcesso verificador;
    private final HistoricoService historico;

    public PesquisaService(PesquisaRepositorio repositorio, VerificadorDeAcesso verificador, HistoricoService historico) {
        this.repositorio = repositorio;
        this.verificador = verificador;
        this.historico = historico;
    }

    // ---------------------------------------------------------------------
    // Rota 23: ver a pesquisa
    // ---------------------------------------------------------------------

    /**
     * Rota 23. [ISOLAMENTO] Parceria de outro patrocinador: 404. [PERMISSAO] O patrocinador só entra se a aba PESQUISA
     * estiver liberada para a parceria dele (senão 403); a RBS vê sempre, e {@code abaLiberada} diz como a aba está.
     */
    @Transactional(readOnly = true)
    public PesquisaDaParceriaDto ver(UsuarioAutenticado usuario, long parceriaId) {
        verificador.exigirParceria(usuario, parceriaId);
        boolean liberada = repositorio.abaLiberada(parceriaId);
        if (!usuario.ehRbs() && !liberada) {
            throw new SemPermissaoException(ABA_NAO_LIBERADA);
        }
        return montar(parceriaId, liberada);
    }

    // ---------------------------------------------------------------------
    // Rota 24: o patrocinador envia as perguntas
    // ---------------------------------------------------------------------

    /** O que fazer com uma pergunta da lista, decidido antes de gravar qualquer coisa. */
    private enum Acao { INSERIR, REENVIAR, APAGAR }

    private record Passo(Acao acao, PerguntaTravada atual, int ordem, String texto) {
    }

    /**
     * Rota 24. O PUT substitui o conjunto: a lista que chega é a lista que a marca quer. [PERMISSAO] Só o Admin
     * Patrocinador da parceria (a RBS recebe 403) e só com a aba liberada. [VALIDACAO] A pergunta nova ou alterada fica
     * ENVIADA; a devolvida para ajuste (RASCUNHO) volta a ENVIADA ao ser enviada de novo; a ENVIADA sem mudança continua
     * como está; a APROVADA nunca é alterada nem retirada (409). Primeiro se monta o plano inteiro, depois se grava, então
     * um 409 não deixa a lista pela metade. [HISTORICO] Uma linha EDICAO por pergunta que mudou, na mesma transação.
     */
    @Transactional
    public PesquisaDaParceriaDto enviarPerguntas(UsuarioAutenticado usuario, long parceriaId,
                                                 EnviarPerguntasRequisicao corpo, HttpServletRequest requisicao) {
        if (usuario.ehRbs()) {
            throw new SemPermissaoException("Só o Admin Patrocinador da parceria envia as perguntas da pesquisa.");
        }
        verificador.exigirParceria(usuario, parceriaId);
        if (!repositorio.abaLiberada(parceriaId)) {
            throw new SemPermissaoException(ABA_NAO_LIBERADA);
        }
        conferirOrdensDiferentes(corpo.perguntas());

        repositorio.travarParceria(parceriaId);
        Map<Integer, PerguntaTravada> atuais = repositorio.perguntasTravadas(parceriaId).stream()
                .collect(Collectors.toMap(PerguntaTravada::ordem, Function.identity()));
        List<Passo> plano = montarPlano(corpo.perguntas(), atuais);

        Long pesquisaId = repositorio.pesquisaDaAtivacao(parceriaId).map(LinhaDePesquisa::id).orElse(null);
        for (Passo passo : plano) {
            executar(passo, usuario, parceriaId, pesquisaId, requisicao);
        }
        return montar(parceriaId, true);
    }

    /** [VALIDACAO] A ordem não se repete. O erro aponta o item repetido, igual à validação automática do corpo. */
    private static void conferirOrdensDiferentes(List<PerguntaRequisicao> perguntas) {
        Set<Integer> vistas = new HashSet<>();
        Map<String, String> campos = new LinkedHashMap<>();
        for (int i = 0; i < perguntas.size(); i++) {
            int ordem = perguntas.get(i).ordem();
            if (!vistas.add(ordem)) {
                campos.put("perguntas[" + i + "].ordem", "A ordem " + ordem + " já foi usada por outra pergunta da lista.");
            }
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }
    }

    /**
     * Compara a lista nova com a que está no banco, pela ordem. [VALIDACAO] Aprovada alterada ou retirada é conflito,
     * com a frase do contrato; o conflito sai antes de qualquer gravação.
     */
    private static List<Passo> montarPlano(List<PerguntaRequisicao> novas, Map<Integer, PerguntaTravada> atuais) {
        List<Passo> plano = new ArrayList<>();
        Set<Integer> ordensDaLista = new HashSet<>();
        novas.stream().sorted(Comparator.comparing(PerguntaRequisicao::ordem)).forEach(nova -> {
            ordensDaLista.add(nova.ordem());
            PerguntaTravada atual = atuais.get(nova.ordem());
            if (atual == null) {
                plano.add(new Passo(Acao.INSERIR, null, nova.ordem(), nova.texto()));
            } else if ("APROVADA".equals(atual.status())) {
                if (!atual.texto().equals(nova.texto())) {
                    throw jaAprovada(atual.ordem());
                }
            } else if ("RASCUNHO".equals(atual.status()) || !atual.texto().equals(nova.texto())) {
                plano.add(new Passo(Acao.REENVIAR, atual, nova.ordem(), nova.texto()));
            }
            // ENVIADA com o mesmo texto: nada a fazer, a pergunta continua esperando a RBS.
        });
        atuais.values().stream().sorted(Comparator.comparing(PerguntaTravada::ordem)).forEach(atual -> {
            if (!ordensDaLista.contains(atual.ordem())) {
                if ("APROVADA".equals(atual.status())) {
                    throw jaAprovada(atual.ordem());
                }
                plano.add(new Passo(Acao.APAGAR, atual, atual.ordem(), null));
            }
        });
        return plano;
    }

    private static ConflitoException jaAprovada(int ordem) {
        return new ConflitoException(
                "A pergunta " + ordem + " já foi aprovada pela RBS e não pode mais ser alterada.");
    }

    private void executar(Passo passo, UsuarioAutenticado usuario, long parceriaId, Long pesquisaId,
                          HttpServletRequest requisicao) {
        switch (passo.acao()) {
            case INSERIR -> {
                long id = repositorio.inserir(parceriaId, pesquisaId, passo.ordem(), passo.texto(), usuario.id());
                historico.registrar(AcaoHistorico.EDICAO, usuario, parceriaId, ENTIDADE, id, null, passo.texto(),
                        "pergunta " + passo.ordem() + " enviada para a aprovação da RBS", requisicao);
            }
            case REENVIAR -> {
                PerguntaTravada atual = passo.atual();
                repositorio.reenviar(atual.id(), pesquisaId, passo.texto(), usuario.id());
                String oQue = atual.texto().equals(passo.texto()) ? "devolvida e enviada de novo" : "alterada e enviada de novo";
                historico.registrar(AcaoHistorico.EDICAO, usuario, parceriaId, ENTIDADE, atual.id(), atual.texto(),
                        passo.texto(), "pergunta " + passo.ordem() + " " + oQue + " para a aprovação da RBS", requisicao);
            }
            case APAGAR -> {
                PerguntaTravada atual = passo.atual();
                repositorio.apagar(atual.id());
                historico.registrar(AcaoHistorico.EDICAO, usuario, parceriaId, ENTIDADE, atual.id(), atual.texto(), null,
                        "pergunta " + passo.ordem() + " retirada da lista", requisicao);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Rota 25: a RBS decide
    // ---------------------------------------------------------------------

    /**
     * Rota 25. [PERMISSAO] Só a RBS (o patrocinador recebe 403, antes de qualquer consulta). [VALIDACAO] A decisão vale
     * só para pergunta ENVIADA (senão 409); pedir ajuste exige a observação, que a marca lê em observacaoRbs.
     * [HISTORICO] ALTERACAO_STATUS com a observação em "detalhe", na mesma transação.
     * [PENDENTE] Hoje qualquer Admin RBS decide; a RBS ainda vai dizer se é só o time de pesquisa.
     */
    @Transactional
    public PerguntaDto decidir(UsuarioAutenticado usuario, long perguntaId, DecisaoRequisicao corpo,
                               HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        boolean aprovar = "APROVAR".equals(corpo.decisao());
        if (!aprovar && corpo.observacao() == null) {
            throw new DadosInvalidosException(Map.of("observacao", "Diga à marca o que precisa ser ajustado na pergunta."));
        }
        PerguntaTravada atual = repositorio.travarPergunta(perguntaId)
                .orElseThrow(() -> new NaoEncontradoException("Não encontramos esta pergunta."));
        verificador.exigirParceria(usuario, atual.parceriaId());
        if (!"ENVIADA".equals(atual.status())) {
            throw new ConflitoException("A RBS só decide sobre pergunta enviada, e a pergunta " + atual.ordem()
                    + " está " + situacaoPorExtenso(atual.status()) + ".");
        }

        String novoStatus = aprovar ? "APROVADA" : "RASCUNHO";
        repositorio.mudarStatus(perguntaId, novoStatus);
        historico.registrar(AcaoHistorico.ALTERACAO_STATUS, usuario, atual.parceriaId(), ENTIDADE, perguntaId,
                atual.status(), novoStatus, corpo.observacao(), requisicao);
        return paraPergunta(repositorio.pergunta(perguntaId).orElseThrow());
    }

    private static String situacaoPorExtenso(String status) {
        return switch (status) {
            case "APROVADA" -> "aprovada";
            case "RASCUNHO" -> "em rascunho (devolvida para ajuste ou ainda não enviada)";
            default -> status.toLowerCase();
        };
    }

    // ---------------------------------------------------------------------
    // Montagem do objeto da rota 23
    // ---------------------------------------------------------------------

    private PesquisaDaParceriaDto montar(long parceriaId, boolean abaLiberada) {
        PesquisaDto pesquisa = repositorio.pesquisaDaAtivacao(parceriaId)
                .map(p -> new PesquisaDto(p.id(), p.nome(), p.escopo(), p.metodologia(), p.baseRespondentes(),
                        p.nivelConfianca()))
                .orElse(null);
        List<PerguntaDto> perguntas = repositorio.perguntas(parceriaId).stream().map(PesquisaService::paraPergunta).toList();
        List<ResultadoDto> resultados = repositorio.resultados(parceriaId).stream()
                .map(r -> new ResultadoDto(r.perguntaId(), r.ordem(), r.alternativa(), r.valor(), r.unidade(),
                        r.baseRespondentes()))
                .toList();
        return new PesquisaDaParceriaDto(parceriaId, abaLiberada, LIMITE_DE_PERGUNTAS, pesquisa, perguntas, resultados);
    }

    private static PerguntaDto paraPergunta(LinhaDePergunta l) {
        UsuarioResumoDto enviadaPor = l.enviadaPorId() == null ? null
                : new UsuarioResumoDto(l.enviadaPorId(), l.enviadaPorNome(), l.enviadaPorPerfil());
        return new PerguntaDto(l.id(), l.ordem(), l.texto(), l.status(), l.observacaoRbs(), enviadaPor,
                Datas.emBrasilia(l.enviadaEm()));
    }
}
