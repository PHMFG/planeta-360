package br.com.rbs.portal.pesquisa;

import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.materiais.MateriaisDtos.PaginaDto;
import br.com.rbs.portal.pesquisa.AberturaDtos.AbrirPesquisaRequisicao;
import br.com.rbs.portal.pesquisa.AberturaDtos.ContagemDePerguntasDto;
import br.com.rbs.portal.pesquisa.AberturaDtos.EditarPesquisaRequisicao;
import br.com.rbs.portal.pesquisa.AberturaDtos.PerguntaDaFilaDto;
import br.com.rbs.portal.pesquisa.AberturaDtos.PesquisaDaEdicaoDto;
import br.com.rbs.portal.pesquisa.PesquisaAberturaRepositorio.LinhaDaFila;
import br.com.rbs.portal.pesquisa.PesquisaAberturaRepositorio.LinhaDePesquisaDaEdicao;
import br.com.rbs.portal.pesquisa.PesquisaDtos.UsuarioResumoDto;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A RBS abre e edita a pesquisa da edição pela tela, e acompanha a fila das perguntas que as marcas enviaram (rotas 92 a 95).
 * [PERMISSAO] Só a RBS (403 antes do corpo). [VALIDACAO] Uma pesquisa da ativação por edição: a segunda dá 409, conferido aqui
 * (com a frase clara) e, por baixo, pelo índice {@code uq_pesquisa_ativacao} da V16. [HISTORICO] Abrir grava CRIACAO e editar grava
 * EDICAO por campo, na entidade {@code pesquisa}, sem parceria (a marca não vê essas linhas na rota 20).
 */
@Service
public class PesquisaAberturaService {

    static final Set<String> ESCOPOS = Set.of("ATIVACAO", "GERAL");
    static final Set<String> STATUS_DA_FILA = Set.of("RASCUNHO", "ENVIADA", "APROVADA");
    static final int LIMITE_NOME = 120;
    static final int MINIMO_NOME = 3;
    static final int LIMITE_METODOLOGIA = 160;
    private static final long LIMITE_BASE = 2_000_000_000L;
    private static final String ENTIDADE = "pesquisa";
    private static final String EDICAO_NAO_ENCONTRADA = "Não encontramos esta edição do festival.";

    private final PesquisaAberturaRepositorio repositorio;
    private final VerificadorDeAcesso verificador;
    private final HistoricoService historico;

    public PesquisaAberturaService(PesquisaAberturaRepositorio repositorio, VerificadorDeAcesso verificador,
                                   HistoricoService historico) {
        this.repositorio = repositorio;
        this.verificador = verificador;
        this.historico = historico;
    }

    // ---------------------------------------------------------------------
    // Rota 92: listar
    // ---------------------------------------------------------------------

    /** Rota 92. As pesquisas da edição, a da ativação primeiro. Edição que não existe dá 404. */
    @Transactional(readOnly = true)
    public List<PesquisaDaEdicaoDto> listar(UsuarioAutenticado usuario, long eventoId) {
        verificador.exigirRbs(usuario);
        exigirEdicao(eventoId);
        return repositorio.daEdicao(eventoId).stream().map(PesquisaAberturaService::paraDto).toList();
    }

    // ---------------------------------------------------------------------
    // Rota 93: abrir
    // ---------------------------------------------------------------------

    /**
     * Rota 93. Ordem das respostas: 403 (não é da RBS), 404 (edição), 422 (todos os campos errados numa resposta só), 409 (nome
     * repetido na edição; segunda pesquisa da ativação). Abrir a da ativação liga, na mesma transação, as perguntas que as marcas
     * da edição já tinham enviado (resposta: {@code perguntasLigadas}); a pesquisa geral abre sem ligar nada.
     */
    @Transactional
    public PesquisaDaEdicaoDto abrir(UsuarioAutenticado usuario, long eventoId, AbrirPesquisaRequisicao corpo,
                                     HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        short edicao = exigirEdicao(eventoId);
        Map<String, String> campos = new LinkedHashMap<>();
        String escopo = corpo.escopo() == null || corpo.escopo().isBlank() ? "ATIVACAO" : corpo.escopo().trim();
        if (!ESCOPOS.contains(escopo)) {
            campos.put("escopo", "Escopo inválido. Use ATIVACAO ou GERAL.");
        }
        String nome = nome(corpo.nome(), true, campos);
        String metodologia = metodologia(corpo.metodologia(), campos);
        Long base = base(corpo.baseRespondentes(), campos);
        BigDecimal confianca = confianca(corpo.nivelConfianca(), campos);
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }

        repositorio.travarEdicao(eventoId);
        if ("ATIVACAO".equals(escopo)) {
            var aberta = repositorio.nomeDaAtivacao(eventoId);
            if (aberta.isPresent()) {
                throw new ConflitoException("A edição " + edicao + " já tem a pesquisa de ativação aberta (\"" + aberta.get()
                        + "\"). Edite essa pesquisa em vez de abrir outra.");
            }
        }
        if (repositorio.nomeJaUsado(eventoId, nome, 0)) {
            throw new ConflitoException("Já existe uma pesquisa com este nome na edição " + edicao + ". Escolha outro nome.");
        }

        long id = repositorio.inserir(eventoId, nome, escopo, metodologia, base, confianca);
        int ligadas = "ATIVACAO".equals(escopo) ? repositorio.ligarPerguntasSemPesquisa(id, eventoId) : 0;
        historico.registrar(AcaoHistorico.CRIACAO, usuario, null, ENTIDADE, id, null, nome,
                "escopo " + escopo + " · edição " + edicao + (ligadas > 0 ? " · " + ligadas + " pergunta(s) ligada(s)" : ""),
                requisicao);
        return paraDto(repositorio.buscar(id).orElseThrow()).comPerguntasLigadas(ligadas);
    }

    // ---------------------------------------------------------------------
    // Rota 94: editar
    // ---------------------------------------------------------------------

    /**
     * Rota 94. O escopo e a edição não mudam. O que vier nulo fica como está; metodologia só com espaços limpa o campo. Corpo sem
     * nenhum campo dá 422. Nome repetido na edição dá 409. [HISTORICO] Uma linha EDICAO por campo que mudou.
     */
    @Transactional
    public PesquisaDaEdicaoDto editar(UsuarioAutenticado usuario, long pesquisaId, EditarPesquisaRequisicao corpo,
                                      HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        LinhaDePesquisaDaEdicao atual = repositorio.travar(pesquisaId)
                .orElseThrow(() -> new NaoEncontradoException("Não encontramos esta pesquisa."));
        Map<String, String> campos = new LinkedHashMap<>();
        if (corpo.nome() == null && corpo.metodologia() == null && corpo.baseRespondentes() == null
                && corpo.nivelConfianca() == null) {
            campos.put("corpo", "Informe ao menos um campo para mudar: nome, metodologia, baseRespondentes ou nivelConfianca.");
        }
        String nome = corpo.nome() == null ? atual.nome() : nome(corpo.nome(), true, campos);
        String metodologia = corpo.metodologia() == null ? atual.metodologia() : metodologia(corpo.metodologia(), campos);
        Long base = corpo.baseRespondentes() == null ? (atual.baseRespondentes() == null ? null : atual.baseRespondentes().longValue())
                : base(corpo.baseRespondentes(), campos);
        BigDecimal confianca = corpo.nivelConfianca() == null ? atual.nivelConfianca() : confianca(corpo.nivelConfianca(), campos);
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }
        if (!Objects.equals(nome, atual.nome()) && repositorio.nomeJaUsado(atual.eventoId(), nome, pesquisaId)) {
            throw new ConflitoException("Já existe uma pesquisa com este nome nesta edição. Escolha outro nome.");
        }

        repositorio.atualizar(pesquisaId, nome, metodologia, base, confianca);
        mudou("nome", atual.nome(), nome, usuario, pesquisaId, requisicao);
        mudou("metodologia", atual.metodologia(), metodologia, usuario, pesquisaId, requisicao);
        mudou("baseRespondentes", atual.baseRespondentes(), base, usuario, pesquisaId, requisicao);
        mudou("nivelConfianca", atual.nivelConfianca(), confianca, usuario, pesquisaId, requisicao);
        return paraDto(repositorio.buscar(pesquisaId).orElseThrow());
    }

    private void mudou(String campo, Object antes, Object depois, UsuarioAutenticado usuario, long pesquisaId,
                       HttpServletRequest requisicao) {
        boolean igual = antes instanceof BigDecimal a && depois instanceof BigDecimal d ? a.compareTo(d) == 0
                : antes instanceof Number a && depois instanceof Number d ? a.longValue() == d.longValue()
                : Objects.equals(antes, depois);
        if (!igual) {
            historico.registrar(AcaoHistorico.EDICAO, usuario, null, ENTIDADE, pesquisaId,
                    antes == null ? null : antes.toString(), depois == null ? null : depois.toString(), campo, requisicao);
        }
    }

    // ---------------------------------------------------------------------
    // Rota 95: a fila de perguntas
    // ---------------------------------------------------------------------

    /**
     * Rota 95. As perguntas de todas as parcerias no status pedido (padrão {@code ENVIADA}), da que espera há mais tempo para a
     * mais nova, com a marca e a edição. {@code eventoId} filtra a edição (404 se não existir). Quem decide continua sendo a rota 25.
     */
    @Transactional(readOnly = true)
    public PaginaDto<PerguntaDaFilaDto> fila(UsuarioAutenticado usuario, String status, Long eventoId, int pagina, int tamanho) {
        verificador.exigirRbs(usuario);
        String filtro = status == null || status.isBlank() ? "ENVIADA" : status.trim();
        if (!STATUS_DA_FILA.contains(filtro)) {
            throw new DadosInvalidosException(Map.of("status", "Status inválido. Use RASCUNHO, ENVIADA ou APROVADA."));
        }
        if (eventoId != null) {
            exigirEdicao(eventoId);
        }
        List<PerguntaDaFilaDto> itens = repositorio.fila(filtro, eventoId, tamanho, pagina * tamanho).stream()
                .map(PesquisaAberturaService::paraPergunta).toList();
        return new PaginaDto<>(itens, pagina, tamanho, repositorio.contarFila(filtro, eventoId));
    }

    // ---------------------------------------------------------------------
    // Validação dos campos
    // ---------------------------------------------------------------------

    private static String nome(String valor, boolean obrigatorio, Map<String, String> campos) {
        String limpo = limpar(valor, "nome", campos);
        if (limpo == null || limpo.isEmpty()) {
            if (!campos.containsKey("nome") && obrigatorio) {
                campos.put("nome", "Informe o nome da pesquisa.");
            }
            return null;
        }
        if (limpo.length() < MINIMO_NOME || limpo.length() > LIMITE_NOME) {
            campos.put("nome", "O nome da pesquisa precisa ter de " + MINIMO_NOME + " a " + LIMITE_NOME + " caracteres.");
            return null;
        }
        return limpo;
    }

    private static String metodologia(String valor, Map<String, String> campos) {
        String limpo = limpar(valor, "metodologia", campos);
        if (limpo == null || limpo.isEmpty()) {
            return null;
        }
        if (limpo.length() > LIMITE_METODOLOGIA) {
            campos.put("metodologia", "A metodologia pode ter até " + LIMITE_METODOLOGIA + " caracteres.");
            return null;
        }
        return limpo;
    }

    /** Texto de uma linha: sem NUL nem outro caractere de controle, com os espaços das pontas fora. */
    private static String limpar(String valor, String campo, Map<String, String> campos) {
        if (valor == null) {
            return null;
        }
        if (valor.codePoints().anyMatch(PesquisaAberturaService::proibido)) {
            campos.put(campo, "Este campo tem caracteres que não podem ser usados.");
            return null;
        }
        return valor.replaceAll("[\\p{Zs}]+", " ").trim();
    }

    private static boolean proibido(int ponto) {
        int tipo = Character.getType(ponto);
        return tipo == Character.CONTROL || tipo == Character.SURROGATE || ponto == 0x200E || ponto == 0x200F
                || (ponto >= 0x202A && ponto <= 0x202E) || (ponto >= 0x2066 && ponto <= 0x2069);
    }

    private static Long base(Long valor, Map<String, String> campos) {
        if (valor != null && (valor <= 0 || valor > LIMITE_BASE)) {
            campos.put("baseRespondentes", "A base de respondentes é um número inteiro maior que zero.");
            return null;
        }
        return valor;
    }

    private static BigDecimal confianca(BigDecimal valor, Map<String, String> campos) {
        if (valor == null) {
            return null;
        }
        if (valor.signum() <= 0 || valor.compareTo(BigDecimal.valueOf(100)) >= 0 || valor.stripTrailingZeros().scale() > 2) {
            campos.put("nivelConfianca", "O nível de confiança fica entre 0 e 100 (sem incluir os extremos), com até 2 casas decimais.");
            return null;
        }
        return valor;
    }

    // ---------------------------------------------------------------------
    // Montagem
    // ---------------------------------------------------------------------

    private short exigirEdicao(long eventoId) {
        return repositorio.edicaoDoEvento(eventoId).orElseThrow(() -> new NaoEncontradoException(EDICAO_NAO_ENCONTRADA));
    }

    private static PesquisaDaEdicaoDto paraDto(LinhaDePesquisaDaEdicao l) {
        return new PesquisaDaEdicaoDto(l.id(), l.eventoId(), l.nome(), l.escopo(), l.metodologia(), l.baseRespondentes(),
                l.nivelConfianca(), new ContagemDePerguntasDto(l.rascunho(), l.enviadas(), l.aprovadas()), l.resultados(), null);
    }

    private static PerguntaDaFilaDto paraPergunta(LinhaDaFila l) {
        UsuarioResumoDto enviadaPor = l.enviadaPorId() == null ? null
                : new UsuarioResumoDto(l.enviadaPorId(), l.enviadaPorNome(), l.enviadaPorPerfil());
        return new PerguntaDaFilaDto(l.id(), l.parceriaId(), l.patrocinador(), l.eventoId(), l.edicao(), l.pesquisaId(), l.ordem(),
                l.texto(), l.status(), enviadaPor, Datas.emBrasilia(l.enviadaEm()));
    }
}
