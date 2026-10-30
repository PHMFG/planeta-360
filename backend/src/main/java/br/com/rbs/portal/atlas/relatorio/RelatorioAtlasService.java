package br.com.rbs.portal.atlas.relatorio;

import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeRelatorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeSolicitacao;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.relatorio.Especificacao.Bloco;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.CalculoDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.RelatorioAtlasDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.RelatorioAtlasResumoDto;
import br.com.rbs.portal.atlas.relatorio.ChecagemDeTexto.Veredito;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.SnapshotDto;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Resolucao;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Valor;
import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.dados.DadoInformadoService;
import br.com.rbs.portal.dados.DadosDtos.CampoDto;
import br.com.rbs.portal.dados.DadosDtos.ValorRequisicao;
import br.com.rbs.portal.dados.DadosDtos.VigenteDto;
import br.com.rbs.portal.dados.OrigemDoDado;
import br.com.rbs.portal.materiais.MateriaisDtos.UsuarioResumoDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

/**
 * O relatório do Atlas e as operações que o montam. [ATLAS] São as mesmas operações do plano B (o botão "Montar relatório") e das
 * ferramentas do modelo de linguagem da etapa seguinte, que só as embrulha: {@link #iniciar}, {@link #solicitarCampos},
 * {@link #responder}, {@link #preencherFrasesModelo}, {@link #definirTextos} e {@link #finalizar}. Cada uma é uma transação curta, com o {@code FOR UPDATE}
 * na linha do relatório valendo só dentro dela; nenhuma fica aberta durante trabalho longo. A parceria vem sempre do
 * {@link AtlasContexto} (da conversa), nunca do argumento. O Atlas monta e redige, mas não escreve número: onde o relatório
 * precisa de um, o bloco leva uma referência e a resolução o busca nos services do portal.
 * <p>
 * Máquina de estados (plano, seção 10.1): MONTANDO &rarr; AGUARDANDO_RESPOSTA (solicitarCampos) &rarr; MONTANDO (responder, com
 * accept, decline ou cancel) &rarr; PRONTO (finalizar, com o snapshot na mesma operação). Fora de MONTANDO nenhuma operação do
 * relatório roda: 409. O descarte (7 dias parado) é do {@code DescarteDeRelatorios}.
 * <p>
 * Lacuna é uma chave do catálogo de que o relatório precisa. Obrigatória resolvida = tem valor informado OU foi pedida num cartão
 * que a pessoa recusou ({@code decline}); o bloco que depende dela sai indisponível, nunca zero. {@code cancel} não resolve nada.
 * O modelo pode pedir no máximo {@value #LIMITE_DE_CARTOES} cartões por relatório; esgotado o limite, a lacuna vale como recusada.
 * O botão não tem esse limite (quem decide é a pessoa).
 */
@Service
public class RelatorioAtlasService {

    public static final int LIMITE_DE_CARTOES = 3;
    public static final int MAXIMO_DE_CAMPOS_NO_CARTAO = 8;
    public static final int MAXIMO_DE_OPERACOES_POR_EDICAO = 10;
    /** Na segunda recusa do mesmo bloco, ele fica com a frase-modelo. */
    static final int RECUSAS_ATE_A_FRASE_MODELO = 2;
    static final int LIMITE_DO_OBJETIVO = 300;
    static final int LIMITE_DA_MENSAGEM = 400;
    static final int LIMITE_DO_ID_DA_CHAMADA = 80;
    public static final String NAO_ENCONTRADO = "Não encontramos este relatório.";
    public static final String SOLICITACAO_NAO_ENCONTRADA = "Não encontramos esta solicitação.";
    public static final String CARTAO_JA_RESPONDIDO = "Este cartão já foi respondido.";
    public static final String INFORME_ESTE_NUMERO = "Informe este número.";

    /** As três respostas do cartão, com os mesmos nomes do formulário de elicitação do MCP (minúsculas no JSON). */
    public enum AcaoDoCartao {
        ACCEPT, DECLINE, CANCEL;

        public String nome() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** A ação pelo nome do JSON (accept, decline ou cancel); nula se não for um dos três. */
        public static AcaoDoCartao de(String nome) {
            if (nome != null) {
                for (AcaoDoCartao acao : values()) {
                    if (acao.nome().equals(nome)) {
                        return acao;
                    }
                }
            }
            return null;
        }
    }

    /** Uma chave do catálogo de que o relatório precisa e que ainda não tem valor. */
    public record Lacuna(String chave, String rotulo, String unidade, boolean obrigatoria) {
    }

    /** Um valor que o relatório já tem, para o modelo de linguagem saber o que existe (valor formatado, unidade e origem). */
    public record DadoDisponivel(String ref, String rotulo, String valor, String unidade, String origem) {
    }

    public record SecaoDoResumo(String id, String titulo, List<String> blocosRedigiveis) {
    }

    /** O que {@code relatorio_iniciar} devolve: compacto, só o que o modelo precisa para decidir o próximo passo. */
    public record ResumoDoRelatorio(long relatorioId, String modelo, String titulo, List<SecaoDoResumo> secoes,
                                    List<DadoDisponivel> dados, List<Lacuna> lacunasObrigatorias, List<Lacuna> lacunasOpcionais) {
    }

    /** O cartão aberto: o que o front desenha e o que a resposta vai amarrar ao pedido ({@code toolCallId}). */
    public record CartaoAberto(long solicitacaoId, long relatorioId, String toolCallId, String mensagem, List<String> chaves) {
    }

    /** Um valor do cartão respondido: a chave, a referência para usar nos textos e o valor vigente. */
    public record ValorDoCartao(String chave, String ref, Object valor) {
    }

    /** A resposta de {@code relatorio_solicitar_campos} na retomada: {@code {acao, campos:[{chave, ref, valor}]}}. */
    public record RespostaDoCartao(long solicitacaoId, long relatorioId, String toolCallId, AcaoDoCartao acao,
                                   List<ValorDoCartao> campos) {

        /** O JSON que vira a mensagem de ferramenta no histórico (e o resultado da ferramenta na retomada). */
        public String resultadoJson() {
            Map<String, Object> corpo = new LinkedHashMap<>();
            corpo.put("acao", acao.nome());
            corpo.put("campos", campos);
            return AtlasJson.escrever(corpo);
        }
    }

    /** Uma operação {@code definir_texto} de {@code relatorio_editar}: o bloco e o texto (com marcadores) que o modelo escreveu. */
    public record OperacaoDeTexto(String bloco, String texto) {
    }

    /**
     * O que aconteceu com uma operação: {@code aplicada}; {@code recusada} (com o motivo em {@code erro}, e o modelo tem mais uma
     * tentativa); ou {@code frase_modelo} (a segunda recusa do bloco: ele ficou com a frase-modelo do modelo-base).
     */
    public record ResultadoDaOperacao(int indice, String bloco, String resultado, String erro) {
    }

    /** O que {@code relatorio_editar} devolve: uma linha por operação e os blocos redigíveis que ainda estão sem texto. */
    public record ResultadoDaEdicao(long relatorioId, List<ResultadoDaOperacao> operacoes, List<String> blocosSemTexto) {
    }

    /** O que {@code relatorio_finalizar} devolve. */
    public record RelatorioFinalizado(long relatorioId, String titulo, String status) {

        public String resultadoJson() {
            return AtlasJson.escrever(this);
        }
    }

    private final AtlasRepositorio repositorio;
    private final ModeloBase modelos;
    private final ResolvedorDeReferencias resolvedor;
    private final MontadorDoRelatorio montador;
    private final DadoInformadoService dados;
    private final VerificadorDeAcesso verificador;
    private final HistoricoService historico;
    private final ChecagemDeTexto checagem;

    public RelatorioAtlasService(AtlasRepositorio repositorio, ModeloBase modelos, ResolvedorDeReferencias resolvedor,
                                 MontadorDoRelatorio montador, DadoInformadoService dados, VerificadorDeAcesso verificador,
                                 HistoricoService historico, ChecagemDeTexto checagem) {
        this.repositorio = repositorio;
        this.modelos = modelos;
        this.resolvedor = resolvedor;
        this.montador = montador;
        this.dados = dados;
        this.verificador = verificador;
        this.historico = historico;
        this.checagem = checagem;
    }

    // ---------------------------------------------------------------------
    // relatorio_iniciar
    // ---------------------------------------------------------------------

    /**
     * Cria o relatório da conversa a partir do modelo-base e devolve o resumo compacto. Ordem das respostas: 422 (modelo
     * desconhecido, objetivo com mais de 300 caracteres), 404 (parceria), 403 (aba do modelo fechada), 409 (a conversa já tem
     * um relatório em montagem: o botão continua dele). [HISTORICO] Grava CRIACAO na entidade atlas_relatorio.
     */
    @Transactional
    public ResumoDoRelatorio iniciar(AtlasContexto contexto, String modelo, String objetivo) {
        Especificacao base = modelos.obter(modelo).orElseThrow(() -> new DadosInvalidosException(
                Map.of("modelo", "Modelo desconhecido. Use " + ModeloBase.ATIVACAO_EVENTO + ".")));
        if (objetivo != null && objetivo.length() > LIMITE_DO_OBJETIVO) {
            throw new DadosInvalidosException(Map.of("objetivo", "O objetivo tem no máximo " + LIMITE_DO_OBJETIVO + " caracteres."));
        }
        verificador.exigirParceria(contexto.usuario(), contexto.parceriaId());
        verificador.exigirAbaLiberada(contexto.usuario(), contexto.parceriaId(), base.aba());
        repositorio.travarConversa(contexto.conversaId());
        if (repositorio.relatorioAtivoDaConversa(contexto.conversaId()).isPresent()) {
            throw new ConflitoException("Já existe um relatório sendo montado nesta conversa. Continue dele.");
        }
        long id = repositorio.criarRelatorio(contexto.parceriaId(), contexto.conversaId(), modelo, AtlasJson.escrever(base),
                contexto.usuario().id());
        historico.registrar(AcaoHistorico.CRIACAO, contexto.usuario(), contexto.parceriaId(), "atlas_relatorio", id, null,
                "MONTANDO", "Relatório \"" + base.titulo() + "\" iniciado pelo Atlas", contexto.requisicao());
        return resumo(contexto, id, base);
    }

    private ResumoDoRelatorio resumo(AtlasContexto contexto, long relatorioId, Especificacao especificacao) {
        Resolucao resolucao = resolvedor.abrir(contexto.usuario(), contexto.parceriaId(), especificacao);
        List<DadoDisponivel> disponiveis = new ArrayList<>();
        for (String ref : refsDeValor(especificacao)) {
            Valor valor = resolucao.valor(ref);
            if (valor.disponivel()) {
                disponiveis.add(new DadoDisponivel(ref, valor.rotulo(), valor.texto(), valor.unidade(), valor.origem()));
            }
        }
        List<SecaoDoResumo> secoes = new ArrayList<>();
        for (var secao : especificacao.secoes()) {
            secoes.add(new SecaoDoResumo(secao.id(), secao.titulo(), secao.blocos().stream()
                    .filter(b -> b instanceof Especificacao.Paragrafo p && p.redigivel()).map(Bloco::id).toList()));
        }
        List<Lacuna> lacunas = lacunasSemValor(especificacao, contexto.parceriaId());
        return new ResumoDoRelatorio(relatorioId, especificacao.modelo(), especificacao.titulo(), secoes, disponiveis,
                lacunas.stream().filter(Lacuna::obrigatoria).toList(), lacunas.stream().filter(l -> !l.obrigatoria()).toList());
    }

    /** As referências de valor do modelo, sem repetir, na ordem em que os blocos as usam. */
    static Set<String> refsDeValor(Especificacao especificacao) {
        Set<String> refs = new LinkedHashSet<>();
        for (Bloco bloco : especificacao.blocos()) {
            switch (bloco) {
                case Especificacao.Capa c -> {
                    if (c.subtitulo() != null) {
                        refs.add(c.subtitulo().ref());
                    }
                    if (c.edicao() != null) {
                        refs.add(c.edicao().ref());
                    }
                }
                case Especificacao.Paragrafo p -> {
                    refs.addAll(Referencias.marcadores(p.fraseModelo()));
                    refs.addAll(Referencias.marcadores(p.texto()));
                }
                case Especificacao.Kpi k -> refs.add(k.valor().ref());
                case Especificacao.KpiGrade g -> g.itens().forEach(i -> refs.add(i.valor().ref()));
                case Especificacao.CampoInformado c -> refs.add(c.valor().ref());
                default -> {
                }
            }
        }
        return refs;
    }

    // ---------------------------------------------------------------------
    // Lacunas
    // ---------------------------------------------------------------------

    /**
     * As lacunas do relatório que ainda não têm valor informado, obrigatórias primeiro, na ordem do modelo. É o que o plano B
     * pergunta no cartão. [PERMISSAO] Não confere permissão: o contexto já foi conferido.
     */
    @Transactional(readOnly = true)
    public List<Lacuna> lacunas(AtlasContexto contexto, long relatorioId) {
        LinhaDeRelatorio relatorio = relatorioDoContexto(contexto, relatorioId, false);
        return lacunasSemValor(ler(relatorio), contexto.parceriaId());
    }

    private List<Lacuna> lacunasSemValor(Especificacao especificacao, long parceriaId) {
        Map<String, CampoDto> catalogo = catalogo();
        Map<String, VigenteDto> vigentes = dados.vigentes(parceriaId);
        List<Lacuna> lacunas = new ArrayList<>();
        for (String chave : especificacao.chavesDeLacuna()) {
            CampoDto campo = catalogo.get(chave);
            if (campo == null) {
                throw new IllegalStateException("O modelo " + especificacao.modelo() + " pede o campo " + chave + ", que não está no catálogo.");
            }
            if (!vigentes.containsKey(chave)) {
                lacunas.add(new Lacuna(chave, campo.rotulo(), campo.unidade(), especificacao.lacunas().obrigatorias().contains(chave)));
            }
        }
        return lacunas;
    }

    private Map<String, CampoDto> catalogo() {
        Map<String, CampoDto> catalogo = new LinkedHashMap<>();
        dados.listarCampos(null).forEach(c -> catalogo.put(c.chave(), c));
        return catalogo;
    }

    // ---------------------------------------------------------------------
    // relatorio_solicitar_campos
    // ---------------------------------------------------------------------

    /**
     * Abre o cartão de perguntas pedido pelo modelo e pausa o relatório (AGUARDANDO_RESPOSTA). {@code chaves} são de 1 a 8 chaves
     * do catálogo da aba do modelo, sem repetir e sem campo de arquivo (o cartão não tem upload no MVP); {@code mensagem} tem até
     * 400 caracteres; {@code toolCallId} liga o cartão ao pedido de ferramenta na conversa. Ordem das respostas: 404 (relatório
     * de outra conversa), 422 (argumentos), 409 (o relatório não está em montagem, ou já teve três cartões).
     */
    @Transactional
    public CartaoAberto solicitarCampos(AtlasContexto contexto, long relatorioId, String mensagem, List<String> chaves,
                                        String toolCallId) {
        return abrirCartao(contexto, relatorioId, mensagem, chaves, toolCallId, true);
    }

    /** O cartão do botão "Montar relatório": o mesmo, sem o limite de três cartões (a pessoa decide quantas vezes continua). */
    @Transactional
    public CartaoAberto solicitarCamposDoBotao(AtlasContexto contexto, long relatorioId, String mensagem, List<String> chaves,
                                               String toolCallId) {
        return abrirCartao(contexto, relatorioId, mensagem, chaves, toolCallId, false);
    }

    private CartaoAberto abrirCartao(AtlasContexto contexto, long relatorioId, String mensagem, List<String> chaves,
                                     String toolCallId, boolean comLimite) {
        LinhaDeRelatorio relatorio = relatorioDoContexto(contexto, relatorioId, true);
        Especificacao especificacao = ler(relatorio);
        validarPedidoDeCartao(especificacao, mensagem, chaves, toolCallId);
        exigirMontando(relatorio);
        if (comLimite && repositorio.solicitacoesDoRelatorio(relatorioId).size() >= LIMITE_DE_CARTOES) {
            throw new ConflitoException("Já foram pedidos " + LIMITE_DE_CARTOES + " cartões neste relatório. Siga sem pedir de novo.");
        }
        long id = repositorio.criarSolicitacao(relatorioId, contexto.parceriaId(), toolCallId, mensagem.trim(),
                AtlasJson.escrever(chaves));
        repositorio.mudarStatus(relatorioId, "AGUARDANDO_RESPOSTA");
        return new CartaoAberto(id, relatorioId, toolCallId, mensagem.trim(), List.copyOf(chaves));
    }

    private void validarPedidoDeCartao(Especificacao especificacao, String mensagem, List<String> chaves, String toolCallId) {
        Map<String, String> erros = new LinkedHashMap<>();
        if (mensagem == null || mensagem.isBlank()) {
            erros.put("mensagem", "Escreva a mensagem do cartão.");
        } else if (mensagem.trim().length() > LIMITE_DA_MENSAGEM) {
            erros.put("mensagem", "A mensagem do cartão tem no máximo " + LIMITE_DA_MENSAGEM + " caracteres.");
        }
        if (toolCallId == null || toolCallId.isBlank() || toolCallId.length() > LIMITE_DO_ID_DA_CHAMADA) {
            erros.put("toolCallId", "O id do pedido tem de 1 a " + LIMITE_DO_ID_DA_CHAMADA + " caracteres.");
        }
        if (chaves == null || chaves.isEmpty() || chaves.size() > MAXIMO_DE_CAMPOS_NO_CARTAO) {
            erros.put("campos", "Peça de 1 a " + MAXIMO_DE_CAMPOS_NO_CARTAO + " campos.");
        } else {
            Map<String, CampoDto> catalogo = catalogo();
            Set<String> vistas = new LinkedHashSet<>();
            for (String chave : chaves) {
                CampoDto campo = chave == null ? null : catalogo.get(chave);
                if (chave == null || !vistas.add(chave)) {
                    erros.put("campos", "Campo repetido ou vazio no cartão.");
                } else if (campo == null || !campo.aba().equals(especificacao.aba())) {
                    erros.put("campos", "O campo " + chave + " não está no catálogo da aba " + especificacao.aba() + ".");
                } else if ("ARQUIVO".equals(campo.tipo())) {
                    erros.put("campos", "O campo " + chave + " recebe um arquivo, que o cartão não aceita.");
                }
            }
        }
        if (!erros.isEmpty()) {
            throw new DadosInvalidosException(erros);
        }
    }

    // ---------------------------------------------------------------------
    // A resposta do cartão
    // ---------------------------------------------------------------------

    /**
     * Responde o cartão. {@code accept} grava os valores como dado informado com origem ATLAS (pelo mesmo DadoInformadoService e
     * as mesmas validações da rota 41: 422 com {@code campos} pela chave) e o cartão vira RESPONDIDA; {@code decline} vira
     * RECUSADA e as lacunas pedidas contam como recusadas; {@code cancel} vira CANCELADA. Nos três o relatório volta a
     * MONTANDO. Ordem das respostas: 404 (cartão que não existe ou é de outra conversa), 409 ("Este cartão já foi respondido."),
     * 422 (valor de chave que não está no cartão; obrigatória sem valor: "Informe este número."; ou erro de valor do
     * DadoInformadoService). Tudo ou nada: com erro, nada é gravado. Devolve o resultado da ferramenta que estava pendente.
     */
    @Transactional
    public RespostaDoCartao responder(AtlasContexto contexto, long solicitacaoId, AcaoDoCartao acao, List<ValorRequisicao> valores) {
        LinhaDeSolicitacao previa = repositorio.solicitacao(solicitacaoId)
                .orElseThrow(() -> new NaoEncontradoException(SOLICITACAO_NAO_ENCONTRADA));
        LinhaDeRelatorio relatorio;
        try {
            relatorio = relatorioDoContexto(contexto, previa.relatorioId(), true);
        } catch (NaoEncontradoException deOutraConversa) {
            throw new NaoEncontradoException(SOLICITACAO_NAO_ENCONTRADA);   // cartão de outra conversa: a mesma frase de inexistente
        }
        LinhaDeSolicitacao solicitacao = repositorio.travarSolicitacao(solicitacaoId)
                .orElseThrow(() -> new NaoEncontradoException(SOLICITACAO_NAO_ENCONTRADA));
        if (!"PENDENTE".equals(solicitacao.status())) {
            throw new ConflitoException(CARTAO_JA_RESPONDIDO);
        }
        Especificacao especificacao = ler(relatorio);
        List<String> chavesDoCartao = AtlasJson.ler(solicitacao.campos(), new TypeReference<List<String>>() {
        });

        List<ValorDoCartao> campos = List.of();
        String statusDoCartao;
        switch (acao) {
            case ACCEPT -> {
                campos = aceitar(contexto, especificacao, chavesDoCartao, valores == null ? List.of() : valores);
                statusDoCartao = "RESPONDIDA";
            }
            case DECLINE -> statusDoCartao = "RECUSADA";
            default -> statusDoCartao = "CANCELADA";
        }
        repositorio.responderSolicitacao(solicitacaoId, statusDoCartao);
        repositorio.mudarStatus(relatorio.id(), "MONTANDO");
        return new RespostaDoCartao(solicitacaoId, relatorio.id(), solicitacao.toolCallId(), acao, campos);
    }

    private List<ValorDoCartao> aceitar(AtlasContexto contexto, Especificacao especificacao, List<String> chavesDoCartao,
                                        List<ValorRequisicao> valores) {
        Map<String, String> erros = new LinkedHashMap<>();
        Set<String> enviadas = new LinkedHashSet<>();
        for (int i = 0; i < valores.size(); i++) {
            ValorRequisicao valor = valores.get(i);
            if (valor == null || valor.chave() == null || valor.chave().isBlank()) {
                erros.put("valores[" + i + "].chave", "Informe a chave do campo.");
            } else {
                enviadas.add(valor.chave());
                if (!chavesDoCartao.contains(valor.chave())) {
                    erros.put(valor.chave(), "Este campo não está no cartão.");
                }
            }
        }
        Map<String, VigenteDto> vigentes = dados.vigentes(contexto.parceriaId());
        for (String chave : especificacao.lacunas().obrigatorias()) {
            if (chavesDoCartao.contains(chave) && !enviadas.contains(chave) && !vigentes.containsKey(chave)) {
                erros.put(chave, INFORME_ESTE_NUMERO);
            }
        }
        if (!erros.isEmpty()) {
            throw new DadosInvalidosException(erros);
        }
        if (!valores.isEmpty()) {
            dados.gravar(contexto.usuario(), contexto.parceriaId(), valores, OrigemDoDado.ATLAS, contexto.requisicao());
        }
        Map<String, VigenteDto> depois = dados.vigentes(contexto.parceriaId());
        List<ValorDoCartao> campos = new ArrayList<>();
        for (String chave : chavesDoCartao) {
            if (depois.containsKey(chave)) {
                campos.add(new ValorDoCartao(chave, "dado:" + chave, depois.get(chave).valor()));
            }
        }
        return campos;
    }

    // ---------------------------------------------------------------------
    // Texto dos blocos redigíveis
    // ---------------------------------------------------------------------

    /**
     * O plano B: preenche com a frase-modelo cada bloco redigível que ainda não tem texto (redigido por MODELO). A frase só leva
     * marcadores, então também não escreve número. Não mexe em quem já tem texto.
     */
    @Transactional
    public void preencherFrasesModelo(AtlasContexto contexto, long relatorioId) {
        LinhaDeRelatorio relatorio = relatorioDoContexto(contexto, relatorioId, true);
        exigirMontando(relatorio);
        Especificacao especificacao = ler(relatorio);
        boolean mudou = false;
        for (Especificacao.Paragrafo paragrafo : especificacao.redigiveis()) {
            if (paragrafo.texto() == null || paragrafo.texto().isBlank()) {
                especificacao = especificacao.comTexto(paragrafo.id(), paragrafo.fraseModelo(), "MODELO");
                mudou = true;
            }
        }
        if (mudou && !repositorio.atualizarEspecificacao(relatorioId, AtlasJson.escrever(especificacao), relatorio.versaoEspecificacao())) {
            throw new ConflitoException("O relatório mudou enquanto era preenchido. Tente de novo.");
        }
    }

    /**
     * {@code relatorio_editar}, operação {@code definir_texto}: grava o texto que o modelo escreveu em cada bloco redigível, se ele
     * passar na {@link ChecagemDeTexto} (sem algarismo fora de marcador, marcadores que resolvem, até 3 frases e 600 caracteres,
     * sem HTML nem URL). Parágrafo redigido sai com {@code redigidoPor} ATLAS. A recusa não é erro da chamada: volta ao modelo no
     * resultado da operação, e ele tem mais uma tentativa; na segunda recusa do mesmo bloco, o bloco fica com a frase-modelo
     * (MODELO). Bloco que não existe ou não é redigível é recusado sem contar. As operações valem uma a uma, na ordem, e a
     * especificação é gravada uma vez (trava otimista). Ordem das respostas: 404 (relatório de outra conversa), 403 (aba do modelo
     * fechada), 409 (o relatório não está em montagem; ou mudou enquanto era editado).
     */
    @Transactional
    public ResultadoDaEdicao definirTextos(AtlasContexto contexto, long relatorioId, List<OperacaoDeTexto> operacoes) {
        LinhaDeRelatorio relatorio = relatorioDoContexto(contexto, relatorioId, true);
        Especificacao original = ler(relatorio);
        verificador.exigirAbaLiberada(contexto.usuario(), contexto.parceriaId(), original.aba());
        exigirMontando(relatorio);

        Resolucao resolucao = resolvedor.abrir(contexto.usuario(), contexto.parceriaId(), original);
        Especificacao especificacao = original;
        List<ResultadoDaOperacao> resultados = new ArrayList<>();
        for (int i = 0; i < operacoes.size(); i++) {
            OperacaoDeTexto operacao = operacoes.get(i);
            var bloco = especificacao.bloco(operacao.bloco());
            if (bloco.isEmpty() || !(bloco.get() instanceof Especificacao.Paragrafo paragrafo) || !paragrafo.redigivel()) {
                resultados.add(new ResultadoDaOperacao(i, operacao.bloco(), "recusada", "O bloco " + operacao.bloco()
                        + " não existe ou não é redigível. Os blocos redigíveis são: " + String.join(", ",
                        especificacao.redigiveis().stream().map(Especificacao.Paragrafo::id).toList()) + "."));
                continue;
            }
            Veredito veredito = checagem.paragrafo(resolucao, operacao.texto());
            if (veredito.aprovado()) {
                especificacao = especificacao.comTexto(paragrafo.id(), veredito.texto(), "ATLAS");
                resultados.add(new ResultadoDaOperacao(i, paragrafo.id(), "aplicada", null));
            } else if (paragrafo.recusas() + 1 >= RECUSAS_ATE_A_FRASE_MODELO) {
                especificacao = especificacao.comTexto(paragrafo.id(), paragrafo.fraseModelo(), "MODELO");
                resultados.add(new ResultadoDaOperacao(i, paragrafo.id(), "frase_modelo", veredito.motivo()
                        + " Este bloco já foi recusado duas vezes e ficou com a frase-modelo; não precisa reescrevê-lo."));
            } else {
                especificacao = especificacao.comRecusas(paragrafo.id(), paragrafo.recusas() + 1);
                resultados.add(new ResultadoDaOperacao(i, paragrafo.id(), "recusada", veredito.motivo()
                        + " Escreva este bloco de novo; se a segunda redação também for recusada, ele fica com a frase-modelo."));
            }
        }
        if (!especificacao.equals(original)
                && !repositorio.atualizarEspecificacao(relatorioId, AtlasJson.escrever(especificacao), relatorio.versaoEspecificacao())) {
            throw new ConflitoException("O relatório mudou enquanto era editado. Tente de novo.");
        }
        List<String> semTexto = especificacao.redigiveis().stream().filter(p -> p.texto() == null || p.texto().isBlank())
                .map(Especificacao.Paragrafo::id).toList();
        return new ResultadoDaEdicao(relatorioId, resultados, semTexto);
    }

    // ---------------------------------------------------------------------
    // relatorio_finalizar
    // ---------------------------------------------------------------------

    /**
     * Fecha o relatório: confere que todo bloco redigível tem texto e que cada lacuna obrigatória está resolvida (valor
     * informado, ou recusada no cartão, ou limite de cartões esgotado), resolve tudo com o perfil de quem finaliza, grava o
     * snapshot (que leva também as contas com os valores) e as linhas de {@code atlas_calculo}, e muda o status para PRONTO na
     * mesma instrução. [HISTORICO] Grava EDICAO na entidade atlas_relatorio. Ordem das respostas: 404, 403 (aba do modelo
     * fechada), 409 (não está em montagem; falta texto; lacuna obrigatória nunca pedida ou ainda sem resposta).
     */
    @Transactional
    public RelatorioFinalizado finalizar(AtlasContexto contexto, long relatorioId) {
        LinhaDeRelatorio relatorio = relatorioDoContexto(contexto, relatorioId, true);
        Especificacao especificacao = ler(relatorio);
        verificador.exigirAbaLiberada(contexto.usuario(), contexto.parceriaId(), especificacao.aba());
        exigirMontando(relatorio);

        List<String> semTexto = especificacao.redigiveis().stream().filter(p -> p.texto() == null || p.texto().isBlank())
                .map(Especificacao.Paragrafo::id).toList();
        if (!semTexto.isEmpty()) {
            throw new ConflitoException("Faltam os textos dos blocos: " + String.join(", ", semTexto) + ".");
        }
        exigirLacunasResolvidas(contexto, relatorio, especificacao);

        SnapshotDto snapshot = montador.montar(especificacao, resolvedor.abrir(contexto.usuario(), contexto.parceriaId(), especificacao));
        for (CalculoDto conta : snapshot.calculos()) {
            repositorio.inserirCalculo(relatorioId, conta.nome(), conta.script(), AtlasJson.escrever(conta.variaveis()),
                    conta.resultado(), conta.unidade());
        }
        if (!repositorio.finalizarRelatorio(relatorioId, AtlasJson.escrever(snapshot))) {
            throw new ConflitoException("O relatório mudou enquanto era finalizado. Tente de novo.");
        }
        historico.registrar(AcaoHistorico.EDICAO, contexto.usuario(), contexto.parceriaId(), "atlas_relatorio", relatorioId,
                "MONTANDO", "PRONTO", "Relatório \"" + especificacao.titulo() + "\" finalizado pelo Atlas", contexto.requisicao());
        return new RelatorioFinalizado(relatorioId, especificacao.titulo(), "PRONTO");
    }

    private void exigirLacunasResolvidas(AtlasContexto contexto, LinhaDeRelatorio relatorio, Especificacao especificacao) {
        List<LinhaDeSolicitacao> cartoes = repositorio.solicitacoesDoRelatorio(relatorio.id());
        if (cartoes.size() >= LIMITE_DE_CARTOES) {
            return;   // esgotado o limite de cartões, a lacuna que sobrou vale como recusada
        }
        Set<String> recusadas = new LinkedHashSet<>();
        for (LinhaDeSolicitacao cartao : cartoes) {
            if ("RECUSADA".equals(cartao.status())) {
                recusadas.addAll(AtlasJson.ler(cartao.campos(), new TypeReference<List<String>>() {
                }));
            }
        }
        Map<String, VigenteDto> vigentes = dados.vigentes(contexto.parceriaId());
        Map<String, CampoDto> catalogo = catalogo();
        List<String> abertas = especificacao.lacunas().obrigatorias().stream()
                .filter(chave -> !vigentes.containsKey(chave) && !recusadas.contains(chave))
                .map(chave -> catalogo.get(chave).rotulo()).toList();
        if (!abertas.isEmpty()) {
            throw new ConflitoException("Faltam respostas para: " + String.join(", ", abertas)
                    + ". Peça com relatorio_solicitar_campos antes de finalizar.");
        }
    }

    // ---------------------------------------------------------------------
    // Rotas 47 e 48: ler
    // ---------------------------------------------------------------------

    /**
     * Rota 47. O snapshot se o relatório está PRONTO; a resolução ao vivo, com o perfil de quem pede, se ainda está montando (ou
     * foi descartado). Ordem das respostas: 404 (não existe ou é de outra parceria, a mesma frase), 403 (aba do modelo fechada
     * para a marca: vale também para o relatório pronto). Uma só leitura (REPEATABLE READ).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RelatorioAtlasDto ver(UsuarioAutenticado usuario, long relatorioId) {
        LinhaDeRelatorio relatorio = repositorio.relatorio(relatorioId).orElseThrow(() -> new NaoEncontradoException(NAO_ENCONTRADO));
        exigirParceriaDoRecurso(usuario, relatorio.parceriaId());
        Especificacao especificacao = ler(relatorio);
        verificador.exigirAbaLiberada(usuario, relatorio.parceriaId(), especificacao.aba());
        SnapshotDto conteudo = "PRONTO".equals(relatorio.status()) && relatorio.snapshot() != null
                ? AtlasJson.ler(relatorio.snapshot(), SnapshotDto.class)
                : montador.montar(especificacao, resolvedor.abrir(usuario, relatorio.parceriaId(), especificacao));
        return new RelatorioAtlasDto(relatorio.id(), relatorio.parceriaId(), relatorio.patrocinador(), relatorio.modelo(),
                especificacao.titulo(), relatorio.status(),
                new UsuarioResumoDto(relatorio.criadoPor(), relatorio.criadoPorNome(), relatorio.criadoPorPerfil()),
                Datas.emBrasilia(relatorio.criadoEm()), Datas.emBrasilia(relatorio.finalizadoEm()),
                RelatorioAtlasDtos.SELO, conteudo.secoes());
    }

    /**
     * Rota 48. Os relatórios do Atlas da parceria (de todos os usuários dela), do mais novo para o mais antigo. Ordem das
     * respostas: 404 (parceria), 403 (aba do modelo fechada para a marca).
     */
    @Transactional(readOnly = true)
    public List<RelatorioAtlasResumoDto> listar(UsuarioAutenticado usuario, long parceriaId) {
        verificador.exigirParceria(usuario, parceriaId);
        verificador.exigirAbaLiberada(usuario, parceriaId, abaDoModeloPadrao());
        return repositorio.relatoriosDaParceria(parceriaId).stream()
                .map(r -> new RelatorioAtlasResumoDto(r.id(), r.modelo(), r.titulo(), r.status(),
                        new UsuarioResumoDto(r.criadoPor(), r.criadoPorNome(), r.criadoPorPerfil()),
                        Datas.emBrasilia(r.criadoEm()), Datas.emBrasilia(r.finalizadoEm())))
                .toList();
    }

    /** A aba do modelo do MVP (APLICACOES): a que precisa estar aberta para a marca ver qualquer coisa do Atlas. */
    public String abaDoModeloPadrao() {
        return modelos.obter(ModeloBase.ATIVACAO_EVENTO).orElseThrow().aba();
    }

    /** A parceria do recurso, com a frase de "não encontrado" do relatório (e não a de parceria) para quem não é dela. */
    private void exigirParceriaDoRecurso(UsuarioAutenticado usuario, long parceriaId) {
        try {
            verificador.exigirParceria(usuario, parceriaId);
        } catch (NaoEncontradoException alheia) {
            throw new NaoEncontradoException(NAO_ENCONTRADO);
        }
    }

    // ---------------------------------------------------------------------
    // Apoio
    // ---------------------------------------------------------------------

    /**
     * O relatório, só se for desta conversa e desta parceria (o de outra é como o que não existe: 404). [ISOLAMENTO] Com
     * {@code travar}, a linha fica travada até o fim da transação (FOR UPDATE).
     */
    private LinhaDeRelatorio relatorioDoContexto(AtlasContexto contexto, long relatorioId, boolean travar) {
        var achado = travar ? repositorio.travarRelatorio(relatorioId) : repositorio.relatorio(relatorioId);
        LinhaDeRelatorio relatorio = achado.orElseThrow(() -> new NaoEncontradoException(NAO_ENCONTRADO));
        if (relatorio.parceriaId() != contexto.parceriaId() || relatorio.conversaId() != contexto.conversaId()) {
            throw new NaoEncontradoException(NAO_ENCONTRADO);
        }
        return relatorio;
    }

    private static void exigirMontando(LinhaDeRelatorio relatorio) {
        switch (relatorio.status()) {
            case "MONTANDO" -> {
            }
            case "AGUARDANDO_RESPOSTA" -> throw new ConflitoException("O relatório está aguardando a resposta do cartão.");
            case "PRONTO" -> throw new ConflitoException("Este relatório já está pronto.");
            default -> throw new ConflitoException("Este relatório foi descartado.");
        }
    }

    private static Especificacao ler(LinhaDeRelatorio relatorio) {
        return AtlasJson.ler(relatorio.especificacao(), Especificacao.class);
    }
}
