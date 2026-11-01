package br.com.rbs.portal.documentos;

import br.com.rbs.portal.atlas.embeddings.ProvedorDeEmbeddings;
import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.Armazenamento;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.TipoDeArquivo;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.documentos.DocumentoDtos.ArquivoDoDocumentoDto;
import br.com.rbs.portal.documentos.DocumentoDtos.DocumentoDaMarcaDto;
import br.com.rbs.portal.documentos.DocumentoDtos.DocumentoDto;
import br.com.rbs.portal.documentos.DocumentoDtos.ParceriaDoDocumentoDto;
import br.com.rbs.portal.documentos.DocumentoDtos.TrechoDoDocumentoDto;
import br.com.rbs.portal.documentos.DocumentoRepositorio.Filtro;
import br.com.rbs.portal.documentos.DocumentoRepositorio.Linha;
import br.com.rbs.portal.documentos.DocumentoRepositorio.Travado;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Extracao;
import br.com.rbs.portal.documentos.ExtratorDeTexto.ExtracaoFalhouException;
import br.com.rbs.portal.materiais.MateriaisDtos.PaginaDto;
import br.com.rbs.portal.materiais.MateriaisDtos.UsuarioResumoDto;
import br.com.rbs.portal.parceria.ParceriaDtos.CotaDto;
import br.com.rbs.portal.parceria.ParceriaDtos.EventoDto;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Os documentos do Atlas (rotas 54 a 58, M3 do plano do Atlas completo). [ATLAS] Só a RBS envia, aprova, recusa, revoga e reindexa;
 * a marca vê um documento (rota 56) só pelo escopo dela e só o que a rota 56 mostra. [PERMISSAO] Quem não é da RBS recebe 403 antes
 * do corpo (PermissaoAntesDoCorpo) e aqui de novo. [HISTORICO] O envio grava CRIACAO e cada mudança de status grava ALTERACAO_STATUS,
 * sempre na entidade {@code documento} (a marca não vê essas linhas na rota 20).
 */
@Service
public class DocumentoService {

    static final Set<String> TIPOS = Set.of("MANUAL_OPERACOES", "PLANO_COMERCIAL", "ANEXO_PARCERIA", "FAQ", "COMUNICADO", "OUTRO");
    static final Set<String> ESCOPOS = Set.of("GLOBAL", "COTA", "PARCERIA");
    static final Set<String> STATUS = Set.of("AGUARDANDO_APROVACAO", "RECUSADO", "PROCESSANDO", "INDEXADO", "ERRO", "SUBSTITUIDO", "REVOGADO");
    static final Set<String> COTAS = Set.of("MASTER", "SUBCOTA_G", "SUBCOTA");
    private static final Pattern CHAVE = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final int LIMITE_TITULO = 200;
    private static final int LIMITE_CHAVE = 80;
    private static final int LIMITE_OBSERVACAO = 500;

    /** Os campos do envio como chegam (texto): a validação e a conversão são daqui. */
    public record DadosDoEnvio(String titulo, String chave, String tipo, String escopo, String eventoId, String cota, String parceriaId) {
    }

    private final DocumentoRepositorio repositorio;
    private final Armazenamento armazenamento;
    private final ExtratorDeTexto extrator;
    private final DetectorDeDadosPessoais detector;
    private final IngestaoService ingestao;
    private final ProvedorDeEmbeddings embeddings;
    private final VerificadorDeAcesso verificador;
    private final HistoricoService historico;
    private final TransactionTemplate transacao;

    public DocumentoService(DocumentoRepositorio repositorio, Armazenamento armazenamento, ExtratorDeTexto extrator,
                            DetectorDeDadosPessoais detector, IngestaoService ingestao, ProvedorDeEmbeddings embeddings,
                            VerificadorDeAcesso verificador, HistoricoService historico, PlatformTransactionManager gerenteDeTransacao) {
        this.repositorio = repositorio;
        this.armazenamento = armazenamento;
        this.extrator = extrator;
        this.detector = detector;
        this.ingestao = ingestao;
        this.embeddings = embeddings;
        this.verificador = verificador;
        this.historico = historico;
        this.transacao = new TransactionTemplate(gerenteDeTransacao);
    }

    // ---------------------------------------------------------------------
    // Rota 54: enviar
    // ---------------------------------------------------------------------

    /**
     * Rota 54. Ordem das respostas: 403 (não é da RBS, antes de tudo), 422 (campos), 415 e 413 (PDF e TXT só, conferidos pela
     * assinatura; até 20 MB). A extração do texto roda na hora, <b>antes de abrir a transação</b> (o PDFBox é rápido): ela grava
     * {@code paginas} e roda o detector de dados pessoais. O documento nasce AGUARDANDO_APROVACAO, ou ERRO (sem aprovado_por) se não
     * houver texto, tiver senha ou estiver corrompido; esse caso se corrige com um novo envio, e não reindexando. A versão é a maior
     * do grupo mais 1, calculada com o grupo travado. Não é {@code @Transactional}: a gravação (arquivo, documento e histórico) é uma
     * transação curta, e se algo falhar nem o registro nem o arquivo em disco ficam.
     */
    public DocumentoDto enviar(UsuarioAutenticado usuario, String nomeDoArquivo, byte[] conteudo, DadosDoEnvio dados,
                               HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        Entrada entrada = validar(nomeDoArquivo, conteudo, dados);
        TipoDeArquivo tipo = armazenamento.conferirDocumento(conteudo, nomeDoArquivo);

        // A extração e o detector, sem transação.
        Extracao extracao = null;
        String erro = null;
        Integer paginas = null;
        try {
            extracao = extrator.extrair(tipo, conteudo);
            paginas = extracao.totalDePaginas();
        } catch (ExtracaoFalhouException semTexto) {
            erro = semTexto.getMessage();
            paginas = semTexto.paginas() != null && semTexto.paginas() >= 1 ? semTexto.paginas() : null;   // o CHECK do banco pede 1 ou mais
        }
        boolean dadosPessoais = extracao != null && detector.contemDadosPessoais(extracao.paginas());
        Integer extracaoMs = extracao == null ? null : (int) extracao.milissegundos();
        Integer totalDePaginas = paginas;
        String motivoDoErro = erro;

        long id = transacao.execute(estado -> gravar(usuario, nomeDoArquivo, conteudo, entrada, dadosPessoais, totalDePaginas, extracaoMs,
                motivoDoErro, requisicao));
        return paraDto(repositorio.buscar(id).orElseThrow());
    }

    private long gravar(UsuarioAutenticado usuario, String nomeDoArquivo, byte[] conteudo, Entrada e, boolean dadosPessoais, Integer paginas,
                        Integer extracaoMs, String erro, HttpServletRequest requisicao) {
        repositorio.travarGrupo(e.eventoId(), e.escopo(), e.cotaId(), e.parceriaId(), e.chave());
        int versao = repositorio.proximaVersao(e.eventoId(), e.escopo(), e.cotaId(), e.parceriaId(), e.chave());
        var arquivo = armazenamento.gravarDocumento(conteudo, nomeDoArquivo, usuario.id(), e.parceriaId());
        long id = repositorio.inserir(e.titulo(), e.chave(), versao, e.tipo(), e.escopo(), e.eventoId(), e.cotaId(), e.parceriaId(),
                arquivo.id(), dadosPessoais, paginas, extracaoMs, erro, usuario.id());
        historico.registrar(AcaoHistorico.CRIACAO, usuario, e.parceriaId(), "documento", id, null, e.titulo() + " (versão " + versao + ")",
                e.escopo() + " · " + e.chave() + (erro == null ? "" : " · " + erro) + (dadosPessoais ? " · suspeita de dados pessoais" : ""),
                requisicao);
        return id;
    }

    /** Os campos do envio, já conferidos e convertidos. */
    private record Entrada(String titulo, String chave, String tipo, String escopo, long eventoId, Integer cotaId, Long parceriaId) {
    }

    /** [VALIDACAO] Junta os erros de campo numa resposta só (422). */
    private Entrada validar(String nomeDoArquivo, byte[] conteudo, DadosDoEnvio dados) {
        Map<String, String> campos = new LinkedHashMap<>();
        if (conteudo == null || conteudo.length == 0 || nomeDoArquivo == null || nomeDoArquivo.isBlank()) {
            campos.put("arquivo", "Envie o documento (PDF ou TXT) na parte \"arquivo\" do formulário.");
        }
        String titulo = limpo(dados.titulo());
        if (titulo == null) {
            campos.put("titulo", "Informe o título do documento.");
        } else if (titulo.length() > LIMITE_TITULO) {
            campos.put("titulo", "O título tem no máximo " + LIMITE_TITULO + " caracteres.");
        } else if (temNulo(titulo)) {
            campos.put("titulo", "O título tem caracteres inválidos.");
        }
        String chave = limpo(dados.chave());
        if (chave == null) {
            campos.put("chave", "Informe a chave do documento, como manual-operacoes.");
        } else if (chave.length() > LIMITE_CHAVE || !CHAVE.matcher(chave).matches()) {
            campos.put("chave", "A chave usa só letras minúsculas sem acento, números e hífen, como manual-operacoes (até " + LIMITE_CHAVE + " caracteres).");
        }
        String tipo = limpo(dados.tipo());
        if (tipo == null || !TIPOS.contains(tipo)) {
            campos.put("tipo", "Tipo inválido. Use MANUAL_OPERACOES, PLANO_COMERCIAL, ANEXO_PARCERIA, FAQ, COMUNICADO ou OUTRO.");
        }
        String escopo = limpo(dados.escopo());
        Long eventoId = null;
        Integer cotaId = null;
        Long parceriaId = null;
        if (escopo == null || !ESCOPOS.contains(escopo)) {
            campos.put("escopo", "Escopo inválido. Use GLOBAL, COTA ou PARCERIA.");
        } else {
            String cota = limpo(dados.cota());
            String parceria = limpo(dados.parceriaId());
            String evento = limpo(dados.eventoId());
            Long eventoInformado = numero(evento);
            if (evento != null && eventoInformado == null) {
                campos.put("eventoId", "A edição (eventoId) precisa ser um número.");
            }
            if ("PARCERIA".equals(escopo)) {
                parceriaId = numero(parceria);
                if (parceria == null || parceriaId == null) {
                    campos.put("parceriaId", "Informe a parceria (parceriaId) do documento.");
                    parceriaId = null;
                } else {
                    Long eventoDaParceria = repositorio.eventoDaParceria(parceriaId).orElse(null);
                    if (eventoDaParceria == null) {
                        campos.put("parceriaId", "Esta parceria não existe.");
                        parceriaId = null;
                    } else if (eventoInformado != null && !eventoInformado.equals(eventoDaParceria)) {
                        campos.put("eventoId", "A edição do documento é a da parceria. Deixe o eventoId em branco ou use o da parceria.");
                    } else {
                        eventoId = eventoDaParceria;
                    }
                }
                if (cota != null) {
                    campos.put("cota", "Documento de parceria não leva cota: a parceria já tem a dela.");
                }
            } else {
                if (evento == null) {
                    campos.put("eventoId", "Informe a edição do festival (eventoId).");
                } else if (eventoInformado != null) {
                    if (repositorio.eventoExiste(eventoInformado)) {
                        eventoId = eventoInformado;
                    } else {
                        campos.put("eventoId", "Esta edição do festival não existe.");
                    }
                }
                if (parceria != null) {
                    campos.put("parceriaId", "Só o documento de escopo PARCERIA leva parceria.");
                }
                if ("COTA".equals(escopo)) {
                    if (cota == null || !COTAS.contains(cota)) {
                        campos.put("cota", "Informe a cota: MASTER, SUBCOTA_G ou SUBCOTA.");
                    } else {
                        cotaId = repositorio.cotaPorCodigo(cota).orElse(null);
                    }
                } else if (cota != null) {
                    campos.put("cota", "Só o documento de escopo COTA leva cota.");
                }
            }
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }
        return new Entrada(titulo, chave, tipo, escopo, eventoId, cotaId, parceriaId);
    }

    // ---------------------------------------------------------------------
    // Rotas 55 e 56: listar e ver
    // ---------------------------------------------------------------------

    /**
     * Rota 55. [PERMISSAO] Só a RBS. A lista vem do envio mais novo para o mais antigo, com filtros opcionais por edição, escopo, status,
     * tipo, chave e um pedaço do título. Filtro com valor fora da lista dá 422.
     */
    @Transactional(readOnly = true)
    public PaginaDto<DocumentoDto> listar(UsuarioAutenticado usuario, Long eventoId, String escopo, String status, String tipo, String chave,
                                          String q, int pagina, int tamanho) {
        verificador.exigirRbs(usuario);
        Map<String, String> campos = new LinkedHashMap<>();
        String escopoLimpo = escolhido(escopo, ESCOPOS, "escopo", "Escopo inválido. Use GLOBAL, COTA ou PARCERIA.", campos);
        String statusLimpo = escolhido(status, STATUS, "status", "Status inválido. Use AGUARDANDO_APROVACAO, RECUSADO, PROCESSANDO, INDEXADO, ERRO, SUBSTITUIDO ou REVOGADO.", campos);
        String tipoLimpo = escolhido(tipo, TIPOS, "tipo", "Tipo inválido. Use MANUAL_OPERACOES, PLANO_COMERCIAL, ANEXO_PARCERIA, FAQ, COMUNICADO ou OUTRO.", campos);
        if (temNulo(chave)) {
            campos.put("chave", "A chave tem caracteres inválidos.");
        }
        if (temNulo(q)) {
            campos.put("q", "A busca pelo título tem caracteres inválidos.");
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }
        Filtro filtro = new Filtro(eventoId, escopoLimpo, statusLimpo, tipoLimpo, limpo(chave), limpo(q));
        List<DocumentoDto> itens = repositorio.pagina(filtro, pagina, tamanho).stream().map(this::paraDto).toList();
        return new PaginaDto<>(itens, pagina, tamanho, repositorio.contar(filtro));
    }

    /**
     * Rota 56. A RBS vê o documento inteiro ({@link DocumentoDto}), em qualquer status. A marca vê só {@link DocumentoDaMarcaDto}, e só se
     * o documento é GLOBAL da edição de alguma parceria dela, COTA da cota dela nessa edição ou PARCERIA da própria parceria, e está
     * INDEXADO ou SUBSTITUIDO. [ISOLAMENTO] Todo o resto (inexistente, de outra edição, cota ou parceria, aguardando, recusado,
     * com erro, revogado) dá 404, com a mesma frase.
     */
    @Transactional(readOnly = true)
    public Object ver(UsuarioAutenticado usuario, long id) {
        if (usuario.ehRbs()) {
            return paraDto(repositorio.buscar(id).orElseThrow(DocumentoService::naoEncontrado));
        }
        var linha = repositorio.paraAMarca(id, usuario.id()).orElseThrow(DocumentoService::naoEncontrado);
        return new DocumentoDaMarcaDto(linha.id(), linha.titulo(), linha.tipo(), linha.versao(), linha.paginas(), linha.substituido());
    }

    // ---------------------------------------------------------------------
    // Rota 57: aprovar, recusar, revogar, reindexar
    // ---------------------------------------------------------------------

    /**
     * Rota 57. As ações e de onde cada uma sai: APROVAR (aguardando aprovação), RECUSAR (aguardando aprovação ou com erro), REVOGAR
     * (indexado ou substituído) e REINDEXAR (com erro). Fora disso, 409. Aprovar e reindexar põem o documento em PROCESSANDO e a
     * ingestão começa depois do commit; a resposta traz PROCESSANDO. O banco recusa o que as regras não deixam, com a frase do gatilho
     * (409): aprovar documento com suspeita de dado pessoal, e reindexar documento que nunca foi aprovado. Grava ALTERACAO_STATUS.
     */
    @Transactional
    public DocumentoDto mudarStatus(UsuarioAutenticado usuario, long id, String acaoInformada, String observacaoInformada,
                                    HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        Map<String, String> campos = new LinkedHashMap<>();
        String acao = acaoInformada == null ? "" : acaoInformada.strip().toUpperCase(Locale.ROOT);
        if (!Set.of("APROVAR", "RECUSAR", "REVOGAR", "REINDEXAR").contains(acao)) {
            campos.put("acao", "Ação inválida. Use APROVAR, RECUSAR, REVOGAR ou REINDEXAR.");
        }
        String observacao = limpo(observacaoInformada);
        if (observacao != null && observacao.length() > LIMITE_OBSERVACAO) {
            campos.put("observacao", "A observação tem no máximo " + LIMITE_OBSERVACAO + " caracteres.");
        } else if (temNulo(observacao)) {
            campos.put("observacao", "A observação tem caracteres inválidos.");
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }
        Travado atual = repositorio.travar(id).orElseThrow(DocumentoService::naoEncontrado);
        String novo;
        switch (acao) {
            case "APROVAR" -> {
                exigirStatus(atual, Set.of("AGUARDANDO_APROVACAO"), "Só um documento aguardando aprovação pode ser aprovado.");
                repositorio.aprovar(id, usuario.id());
                ingestao.depoisDoCommit(id);
                novo = "PROCESSANDO";
            }
            case "RECUSAR" -> {
                exigirStatus(atual, Set.of("AGUARDANDO_APROVACAO", "ERRO"), "Só um documento aguardando aprovação ou com erro pode ser recusado.");
                repositorio.recusar(id);
                novo = "RECUSADO";
            }
            case "REVOGAR" -> {
                exigirStatus(atual, Set.of("INDEXADO", "SUBSTITUIDO"), "Só um documento indexado ou substituído pode ser revogado.");
                repositorio.revogar(id, usuario.id());
                novo = "REVOGADO";
            }
            default -> {
                exigirStatus(atual, Set.of("ERRO"), "Só um documento com erro pode ser reindexado.");
                repositorio.reindexar(id);
                ingestao.depoisDoCommit(id);
                novo = "PROCESSANDO";
            }
        }
        String detalhe = observacao;
        if (acao.equals("REINDEXAR")) {
            detalhe = observacao == null ? "reindexação" : "reindexação: " + observacao;
        }
        historico.registrar(AcaoHistorico.ALTERACAO_STATUS, usuario, atual.parceriaId(), "documento", id, atual.status(), novo, detalhe,
                requisicao);
        return paraDto(repositorio.buscar(id).orElseThrow());
    }

    private static void exigirStatus(Travado documento, Set<String> permitidos, String frase) {
        if (!permitidos.contains(documento.status())) {
            throw new ConflitoException(frase + " Este documento está " + fraseDoStatus(documento.status()) + ".");
        }
    }

    // ---------------------------------------------------------------------
    // Rota 58: os trechos
    // ---------------------------------------------------------------------

    /** Rota 58. [PERMISSAO] Só a RBS. Os trechos do documento em ordem (também os inativos), com o começo do texto. */
    @Transactional(readOnly = true)
    public PaginaDto<TrechoDoDocumentoDto> trechos(UsuarioAutenticado usuario, long id, int pagina, int tamanho) {
        verificador.exigirRbs(usuario);
        if (repositorio.buscar(id).isEmpty()) {
            throw naoEncontrado();
        }
        List<TrechoDoDocumentoDto> itens = repositorio.trechos(id, pagina, tamanho).stream()
                .map(t -> new TrechoDoDocumentoDto(t.id(), t.ordem(), t.paginaInicio(), t.paginaFim(), t.secaoCaminho(), t.tokens(), t.ativo(),
                        t.inicioDoTexto()))
                .toList();
        return new PaginaDto<>(itens, pagina, tamanho, repositorio.contarTrechos(id));
    }

    // ---------------------------------------------------------------------
    // Apoio
    // ---------------------------------------------------------------------

    /** A linha do banco como a RBS vê, com as ações que ela pode fazer agora. */
    DocumentoDto paraDto(Linha l) {
        EventoDto evento = new EventoDto(l.eventoId(), l.eventoNome(), l.eventoEdicao());
        CotaDto cota = l.cotaCodigo() == null ? null : new CotaDto(l.cotaCodigo(), l.cotaNome());
        ParceriaDoDocumentoDto parceria = l.parceriaId() == null ? null : new ParceriaDoDocumentoDto(l.parceriaId(), l.patrocinador());
        boolean modeloDiferente = "INDEXADO".equals(l.status()) && l.modeloEmbedding() != null && embeddings.ligado()
                && !l.modeloEmbedding().equals(embeddings.modelo());
        return new DocumentoDto(l.id(), l.titulo(), l.chave(), l.versao(), l.tipo(), l.escopo(), evento, cota, parceria, l.status(),
                l.contemDadosPessoais(), l.paginas(), l.trechos(), l.modeloEmbedding(), l.dimensoes(), modeloDiferente, l.extracaoMs(),
                l.embeddingsMs(), l.erro(), new ArquivoDoDocumentoDto(l.arquivoId(), l.arquivoNome(), l.arquivoMime(), l.arquivoTamanho()),
                pessoa(l.enviadoPor(), l.enviadoPorNome(), l.enviadoPorPerfil()), Datas.emBrasilia(l.enviadoEm()),
                pessoa(l.aprovadoPor(), l.aprovadoPorNome(), l.aprovadoPorPerfil()), Datas.emBrasilia(l.aprovadoEm()),
                pessoa(l.revogadoPor(), l.revogadoPorNome(), l.revogadoPorPerfil()), Datas.emBrasilia(l.revogadoEm()), acoes(l));
    }

    /** O que a RBS pode fazer agora (as ações da rota 57). Aprovar some quando há suspeita de dado pessoal; reindexar, quando o documento nunca foi aprovado. */
    private static List<String> acoes(Linha l) {
        return switch (l.status()) {
            case "AGUARDANDO_APROVACAO" -> l.contemDadosPessoais() ? List.of("RECUSAR") : List.of("APROVAR", "RECUSAR");
            case "ERRO" -> l.aprovadoPor() == null || l.contemDadosPessoais() ? List.of("RECUSAR") : List.of("REINDEXAR", "RECUSAR");
            case "INDEXADO", "SUBSTITUIDO" -> List.of("REVOGAR");
            default -> List.of();
        };
    }

    private static UsuarioResumoDto pessoa(Long id, String nome, String perfil) {
        return id == null ? null : new UsuarioResumoDto(id, nome, perfil);
    }

    static String fraseDoStatus(String status) {
        return switch (status) {
            case "AGUARDANDO_APROVACAO" -> "aguardando aprovação";
            case "RECUSADO" -> "recusado";
            case "PROCESSANDO" -> "em processamento";
            case "INDEXADO" -> "indexado";
            case "ERRO" -> "com erro";
            case "SUBSTITUIDO" -> "substituído por uma versão mais nova";
            default -> "revogado";
        };
    }

    private static String escolhido(String valor, Set<String> permitidos, String campo, String frase, Map<String, String> campos) {
        String limpo = limpo(valor);
        if (limpo != null && !permitidos.contains(limpo)) {
            campos.put(campo, frase);
            return null;
        }
        return limpo;
    }

    private static String limpo(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }

    /** O PostgreSQL não guarda o caractere nulo (U+0000): um texto com ele falharia na gravação com 500, e aqui vira 422. */
    private static boolean temNulo(String texto) {
        return texto != null && texto.indexOf('\u0000') >= 0;
    }

    private static Long numero(String texto) {
        if (texto == null) {
            return null;
        }
        try {
            return Long.valueOf(texto);
        } catch (NumberFormatException naoEhNumero) {
            return null;
        }
    }

    private static NaoEncontradoException naoEncontrado() {
        return new NaoEncontradoException("Não encontramos este documento.");
    }
}
