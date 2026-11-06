package br.com.rbs.portal.importacao;

import br.com.rbs.portal.atlas.embeddings.ProvedorDeEmbeddings;
import br.com.rbs.portal.atlas.embeddings.ProvedorDeEmbeddings.TextoDoTrecho;
import br.com.rbs.portal.atlas.jev.JevDoTurno;
import br.com.rbs.portal.atlas.jev.ServicoDoJev;
import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.Armazenamento;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.SemPermissaoException;
import br.com.rbs.portal.comum.TipoDeArquivo;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.documentos.DetectorDeDadosPessoais;
import br.com.rbs.portal.documentos.DetectorDeDadosPessoais.Suspeita;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Linha;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Pagina;
import br.com.rbs.portal.importacao.DestinoDeGravacao.ContextoDaGravacao;
import br.com.rbs.portal.importacao.DestinoDeGravacao.LinhaAceita;
import br.com.rbs.portal.importacao.DestinoDeGravacao.ResultadoDaGravacao;
import br.com.rbs.portal.importacao.ImportacaoDtos.AproveitamentoDaImportacaoDto;
import br.com.rbs.portal.importacao.ImportacaoDtos.ArquivoDaImportacaoDto;
import br.com.rbs.portal.importacao.ImportacaoDtos.ContagemDeLinhasDto;
import br.com.rbs.portal.importacao.ImportacaoDtos.CotaDaImportacaoDto;
import br.com.rbs.portal.importacao.ImportacaoDtos.EdicaoDeLinha;
import br.com.rbs.portal.importacao.ImportacaoDtos.EdicaoEmLote;
import br.com.rbs.portal.importacao.ImportacaoDtos.EventoDaImportacaoDto;
import br.com.rbs.portal.importacao.ImportacaoDtos.ImportacaoDetalhe;
import br.com.rbs.portal.importacao.ImportacaoDtos.ImportacaoResumo;
import br.com.rbs.portal.importacao.ImportacaoDtos.ItemDaLinha;
import br.com.rbs.portal.importacao.ImportacaoDtos.LinhasAlteradasDto;
import br.com.rbs.portal.importacao.ImportacaoDtos.NaoEntendidoDto;
import br.com.rbs.portal.importacao.ImportacaoDtos.PaginaDeImportacoes;
import br.com.rbs.portal.importacao.ImportacaoDtos.ParceriaDaImportacaoDto;
import br.com.rbs.portal.importacao.ImportacaoDtos.RejeicaoDaLinhaDto;
import br.com.rbs.portal.importacao.ImportacaoDtos.ResultadoDaConfirmacao;
import br.com.rbs.portal.importacao.ImportacaoDtos.SituacaoDaLinha;
import br.com.rbs.portal.importacao.ImportacaoDtos.SuspeitaDeDadoPessoalDto;
import br.com.rbs.portal.importacao.ImportacaoDtos.UsuarioDaImportacaoDto;
import br.com.rbs.portal.importacao.ImportacaoRepositorio.Importacao;
import br.com.rbs.portal.importacao.ImportacaoRepositorio.LinhaGravada;
import br.com.rbs.portal.importacao.ImportacaoRepositorio.Parceria;
import br.com.rbs.portal.importacao.ImportacaoRepositorio.PlanilhaDoPlano;
import br.com.rbs.portal.importacao.ImportacaoRepositorio.RejeicaoDeCarga;
import br.com.rbs.portal.importacao.ImportacaoRepositorio.TotaisDaCarga;
import br.com.rbs.portal.importacao.extracao.CasadorDoPlano;
import br.com.rbs.portal.importacao.extracao.ConferenciaDaExtracao;
import br.com.rbs.portal.importacao.extracao.SugestorDeDestino;
import br.com.rbs.portal.importacao.leitores.LeitorDeImagem;
import br.com.rbs.portal.materiais.MateriaisDtos.PaginaDto;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * O núcleo da importação (rotas 109 a 116 e 112b): cria a importação em LENDO, lê o arquivo na fila, guarda as linhas propostas,
 * confere, deixa a pessoa editar, aceitar, descartar e confirmar no destino, e retira uma importação confirmada. [IMPORTACAO]
 * Nada entra na base antes da confirmação (E13); nenhuma linha some: o que a conferência recusa fica com o motivo (E10).
 */
@Service
public class ImportacaoService {

    private static final Logger log = LoggerFactory.getLogger(ImportacaoService.class);
    static final String LEITURA_INTERROMPIDA = "Leitura interrompida: a API foi reiniciada antes de terminar. Importe o arquivo de novo.";
    private static final int LIMITE_DO_TEXTO = 100_000;
    private static final int LIMITE_DO_MOTIVO = 500;
    private static final Set<String> EXTENSOES = Set.of("xlsx", "xls", "csv", "docx", "doc", "pptx", "ppt", "pdf", "txt", "png", "jpg",
            "jpeg");
    private static final Set<DestinoDaImportacao> DESTINOS = Set.of(DestinoDaImportacao.values());
    private static final Set<SituacaoDaImportacao> SITUACOES = Set.of(SituacaoDaImportacao.values());
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAPA_DE_DADOS = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, OrigemDoValor>> MAPA_DE_ORIGENS = new TypeReference<>() {
    };

    private final ImportacaoRepositorio repositorio;
    private final Armazenamento armazenamento;
    private final FilaDeImportacao fila;
    private final DetectorDeDadosPessoais detector;
    private final List<LeitorDeConteudo> leitores;
    private final List<ExtratorDeLinhas> extratores;
    private final List<DestinoDeGravacao> destinos;
    private final ObjectProvider<ConferenciaDaExtracao> conferencia;
    private final ObjectProvider<CasadorDoPlano> casador;
    private final ProvedorDeEmbeddings embeddings;
    private final VerificadorDeAcesso verificador;
    private final HistoricoService historico;
    private final ServicoDoJev servicoJev;
    private final SugestorDeDestino sugestorDeDestino;
    private final TransactionTemplate transacao;

    public ImportacaoService(ImportacaoRepositorio repositorio, Armazenamento armazenamento, FilaDeImportacao fila,
                             DetectorDeDadosPessoais detector, List<LeitorDeConteudo> leitores, List<ExtratorDeLinhas> extratores,
                             List<DestinoDeGravacao> destinos, ObjectProvider<ConferenciaDaExtracao> conferencia,
                             ObjectProvider<CasadorDoPlano> casador, ProvedorDeEmbeddings embeddings,
                             VerificadorDeAcesso verificador, HistoricoService historico, ServicoDoJev servicoJev,
                             SugestorDeDestino sugestorDeDestino, PlatformTransactionManager gerenteDeTransacao) {
        this.repositorio = repositorio;
        this.armazenamento = armazenamento;
        this.fila = fila;
        this.detector = detector;
        this.leitores = leitores;
        this.extratores = extratores;
        this.destinos = destinos;
        this.conferencia = conferencia;
        this.casador = casador;
        this.embeddings = embeddings;
        this.verificador = verificador;
        this.historico = historico;
        this.servicoJev = servicoJev;
        this.sugestorDeDestino = sugestorDeDestino;
        this.transacao = new TransactionTemplate(gerenteDeTransacao);
    }

    // ---------------------------------------------------------------------
    // Rota 109: criar
    // ---------------------------------------------------------------------

    /**
     * Rota 109. Ordem das respostas: 403 (marca), 422 (campos), 413/415 do Armazenamento. A importação nasce LENDO, com o arquivo
     * guardado na categoria IMPORTACAO (a parceria só no alcance PARCERIA); a leitura começa depois do commit, na fila. O texto
     * colado vira um .txt.
     */
    @Transactional
    public ImportacaoResumo criar(UsuarioAutenticado usuario, MultipartFile arquivo, String eventoIdInformado,
                                  String alcanceInformado, String cotaInformada, Long parceriaId, String destinoInformado,
                                  String texto, HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        Map<String, String> campos = new LinkedHashMap<>();
        boolean nomeDeArquivo = arquivo != null && arquivo.getOriginalFilename() != null
                && !arquivo.getOriginalFilename().isBlank();
        boolean temArquivo = nomeDeArquivo && !arquivo.isEmpty();
        String textoLimpo = texto == null || texto.isBlank() ? null : texto.strip();
        if (nomeDeArquivo && arquivo.isEmpty()) {
            campos.put("arquivo", "O arquivo está vazio.");
        } else if (!temArquivo && textoLimpo == null) {
            campos.put("arquivo", "Envie um arquivo ou cole um texto.");
        }
        if (textoLimpo != null && textoLimpo.length() > LIMITE_DO_TEXTO) {
            campos.put("texto", "O texto tem no máximo " + LIMITE_DO_TEXTO + " caracteres.");
        }
        Long eventoId = numero(eventoIdInformado);
        ImportacaoRepositorio.Evento evento = null;
        if (eventoId == null) {
            campos.put("eventoId", "Informe a edição do festival (eventoId).");
        } else {
            evento = repositorio.evento(eventoId).orElse(null);
            if (evento == null) {
                campos.put("eventoId", "Esta edição do festival não existe.");
            }
        }
        AlcanceDaImportacao alcance = alcance(alcanceInformado, campos);
        Long cotaTipoId = null;
        Long parceriaIdFinal = null;
        if (alcance == AlcanceDaImportacao.COTA) {
            String cota = cotaInformada == null || cotaInformada.isBlank() ? null : cotaInformada.strip();
            if (cota == null) {
                campos.put("cota", "Informe a cota da importação.");
            } else {
                cotaTipoId = repositorio.cota(cota).map(ImportacaoRepositorio.Cota::id).orElse(null);
                if (cotaTipoId == null) {
                    campos.put("cota", "Cota inválida. Use MASTER, SUBCOTA_G ou SUBCOTA.");
                }
            }
        } else if (cotaInformada != null && !cotaInformada.isBlank()) {
            campos.put("cota", "Só a importação de escopo COTA leva cota.");
        }
        if (alcance == AlcanceDaImportacao.PARCERIA) {
            if (parceriaId == null) {
                campos.put("parceriaId", "Informe a parceria da importação.");
            } else {
                Parceria parceria = repositorio.parceria(parceriaId).orElse(null);
                if (parceria == null) {
                    campos.put("parceriaId", "Esta parceria não existe.");
                } else if (evento != null && parceria.eventoId() != evento.id()) {
                    campos.put("parceriaId", "Esta parceria não é da edição.");
                } else {
                    parceriaIdFinal = parceria.id();
                }
            }
        } else if (parceriaId != null) {
            campos.put("parceriaId", "Só a importação de escopo PARCERIA leva parceria.");
        }
        DestinoDaImportacao destino = destino(destinoInformado, campos);
        if (destino == DestinoDaImportacao.DADOS_ATIVACAO && alcance != AlcanceDaImportacao.PARCERIA) {
            campos.put("destino", "Os dados da ativação são de uma importação de parceria.");
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }

        byte[] conteudo;
        String nome;
        if (temArquivo) {
            String original = arquivo.getOriginalFilename();
            String extensao = TipoDeArquivo.extensaoDe(original);
            if (!EXTENSOES.contains(extensao)) {
                throw new br.com.rbs.portal.comum.ArquivoRecusadoException(org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "Este tipo de arquivo não é aceito na importação. Envie um destes: " + String.join(", ", EXTENSOES.stream().sorted().toList()) + ".");
            }
            conteudo = bytesDe(arquivo);
            nome = original;
        } else {
            conteudo = textoLimpo.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            nome = "texto-colado.txt";
        }

        var gravado = armazenamento.gravarImportacao(conteudo, nome, usuario.id(), parceriaIdFinal);
        long id = repositorio.inserir(eventoId, alcance, cotaTipoId, parceriaIdFinal,
                ImportacaoDtos.OrigemDaImportacao.RBS, gravado.id(), destino, usuario.id());
        historico.registrar(AcaoHistorico.CRIACAO, usuario, parceriaIdFinal, "importacao", id, null,
                gravado.nomeOriginal() + " (" + alcance + (destino == null ? "" : ", " + destino) + ")", null, requisicao);
        fila.depoisDoCommit(id);
        return resumo(repositorio.buscar(id).orElseThrow(), false);
    }

    // ---------------------------------------------------------------------
    // Rota 117: a planilha da agência, enviada pela marca
    // ---------------------------------------------------------------------

    /**
     * Rota 117. A marca (ou a RBS, por ela) manda a planilha da agência com a aba APLICACOES aberta: a importação nasce com
     * origem MARCA, alcance PARCERIA e destino fixo DADOS_ATIVACAO, no evento da parceria. Ordem das respostas: 404 (parceria
     * que não existe ou é de outra), 403 (aba fechada), 409 (parceria encerrada), 422 (arquivo ausente ou vazio), 415 (não é
     * planilha) e 413/415 do Armazenamento. A leitura roda na mesma fila da rota 109.
     */
    @Transactional
    public ImportacaoResumo criarDaMarca(UsuarioAutenticado usuario, long parceriaId, MultipartFile arquivo,
                                         HttpServletRequest requisicao) {
        verificador.exigirParceria(usuario, parceriaId);
        verificador.exigirAbaLiberada(usuario, parceriaId, "APLICACOES");
        Parceria parceria = repositorio.parceria(parceriaId).orElseThrow(ImportacaoService::naoEncontrada);
        if ("ENCERRADA".equals(parceria.situacao())) {
            throw new ConflitoException("Esta parceria está encerrada e não aceita novos envios.");
        }
        boolean nomeDeArquivo = arquivo != null && arquivo.getOriginalFilename() != null
                && !arquivo.getOriginalFilename().isBlank();
        if (!nomeDeArquivo) {
            throw new DadosInvalidosException(Map.of("arquivo", "Envie a planilha da agência na parte \"arquivo\" do formulário."));
        }
        if (arquivo.isEmpty()) {
            throw new DadosInvalidosException(Map.of("arquivo", "O arquivo está vazio."));
        }
        String nome = arquivo.getOriginalFilename();
        if (!Set.of("xlsx", "xls", "csv").contains(TipoDeArquivo.extensaoDe(nome))) {
            throw new br.com.rbs.portal.comum.ArquivoRecusadoException(org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "A planilha da agência é .xlsx, .xls ou .csv.");
        }

        var gravado = armazenamento.gravarImportacao(bytesDe(arquivo), nome, usuario.id(), parceriaId);
        long id = repositorio.inserir(parceria.eventoId(), AlcanceDaImportacao.PARCERIA, null, parceriaId,
                ImportacaoDtos.OrigemDaImportacao.MARCA, gravado.id(), DestinoDaImportacao.DADOS_ATIVACAO, usuario.id());
        historico.registrar(AcaoHistorico.CRIACAO, usuario, parceriaId, "importacao", id, null,
                gravado.nomeOriginal() + " (PARCERIA, DADOS_ATIVACAO)", null, requisicao);
        fila.depoisDoCommit(id);
        return resumo(repositorio.buscar(id).orElseThrow(), false);
    }

    // ---------------------------------------------------------------------
    // Rota 110: listar
    // ---------------------------------------------------------------------

    /** Rota 110. Só a RBS; a mais nova primeiro; filtros por edição e por uma ou várias situações. */
    @Transactional(readOnly = true)
    public PaginaDeImportacoes listar(UsuarioAutenticado usuario, Long eventoId, String situacaoInformada, int pagina, int tamanho) {
        verificador.exigirRbs(usuario);
        List<SituacaoDaImportacao> situacoes = situacoes(situacaoInformada);
        List<ImportacaoResumo> itens = repositorio.listar(eventoId, situacoes, pagina, tamanho).stream()
                .map(i -> resumo(i, false)).toList();
        return new PaginaDeImportacoes(itens, pagina, tamanho, repositorio.contar(eventoId, situacoes));
    }

    // ---------------------------------------------------------------------
    // Rota 111: o detalhe
    // ---------------------------------------------------------------------

    /** Rota 111. A RBS vê qualquer importação; a marca só a dela (e a de outra parceria é 404). */
    @Transactional(readOnly = true)
    public ImportacaoDetalhe detalhar(UsuarioAutenticado usuario, long id) {
        Importacao importacao = exigirAcesso(usuario, id);
        List<ItemDaLinha> itens = new ArrayList<>();
        List<NaoEntendidoDto> naoEntendidos = new ArrayList<>();
        for (LinhaGravada linha : repositorio.linhas(id)) {
            if (linha.naoEntendido()) {
                naoEntendidos.add(naoEntendido(linha));
            } else {
                itens.add(itemDaLinha(linha));
            }
        }
        return ImportacaoDetalhe.de(resumo(importacao, true), importacao.documentoId(), etapa(importacao), itens, naoEntendidos,
                resultado(importacao, itens));
    }

    // ---------------------------------------------------------------------
    // Rota 112: editar uma linha
    // ---------------------------------------------------------------------

    /** Rota 112. A edição de valores ou da linha do plano passa a linha a EDITADA e roda a conferência de novo. */
    @Transactional
    public ItemDaLinha editarLinha(UsuarioAutenticado usuario, long importacaoId, long linhaId, EdicaoDeLinha corpo,
                                   HttpServletRequest requisicao) {
        Importacao importacao = exigirAcesso(usuario, importacaoId);
        exigirEmPrevia(importacao);
        LinhaGravada linha = repositorio.linha(importacaoId, linhaId)
                .orElseThrow(() -> new NaoEncontradoException("Não encontramos esta linha da importação."));
        Map<String, String> campos = new LinkedHashMap<>();
        Map<String, Object> dados = lerDados(linha.dados());
        Long aproveitamentoId = linha.aproveitamentoId();
        boolean mudou = false;
        if (corpo != null && corpo.dados() != null && !corpo.dados().isEmpty()) {
            dados.putAll(corpo.dados());
            mudou = true;
        }
        if (corpo != null && corpo.aproveitamentoId() != null) {
            if (linha.parceriaId() == null) {
                campos.put("aproveitamentoId", "Esta linha ainda não tem uma marca.");
            } else if (repositorio.planilha(corpo.aproveitamentoId(), linha.parceriaId()).isEmpty()) {
                campos.put("aproveitamentoId", "Esta linha do plano não é da marca da linha.");
            } else {
                aproveitamentoId = corpo.aproveitamentoId();
                mudou = true;
            }
        }
        SituacaoDaLinha situacao = linha.situacao();
        if (corpo != null && corpo.situacao() != null) {
            if (corpo.situacao() != SituacaoDaLinha.ACEITA && corpo.situacao() != SituacaoDaLinha.DESCARTADA) {
                campos.put("situacao", "Use ACEITA ou DESCARTADA.");
            } else if (corpo.situacao() == SituacaoDaLinha.ACEITA && linha.situacao() == SituacaoDaLinha.RECUSADA) {
                throw new ConflitoException(motivoOuPadrao(linha.motivo()));
            } else {
                situacao = corpo.situacao();
            }
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }
        Long parceriaId = linha.parceriaId();
        Long sugeridoId = linha.aproveitamentoSugeridoId();
        BigDecimal semelhanca = linha.semelhanca();
        String motivo = situacao == SituacaoDaLinha.ACEITA || situacao == SituacaoDaLinha.DESCARTADA ? linha.motivo() : null;
        if (mudou && situacao != SituacaoDaLinha.DESCARTADA) {
            Map<String, OrigemDoValor> origens = lerOrigem(linha.origem());
            ConferenciaDaLinha conferida = conferir(importacao, linha.destino(),
                    new LinhaProposta(linha.destino(), dados, origens, ehDeTexto(origens)));
            parceriaId = conferida.parceriaId();
            sugeridoId = conferida.aproveitamentoSugeridoId();
            semelhanca = conferida.semelhanca();
            if (!conferida.problemas().isEmpty()) {
                situacao = SituacaoDaLinha.RECUSADA;
                motivo = String.join(" ", conferida.problemas());
            } else {
                motivo = null;
                if (situacao != SituacaoDaLinha.ACEITA) {
                    situacao = SituacaoDaLinha.EDITADA;
                }
            }
        }
        OffsetDateTime agora = OffsetDateTime.now(br.com.rbs.portal.comum.Datas.BRASILIA);
        repositorio.atualizarLinha(linha.id(), linha.destino(), JSON.writeValueAsString(dados), linha.origem(), situacao,
                motivo, parceriaId, sugeridoId, aproveitamentoId, semelhanca,
                mudou ? usuario.id() : linha.editadaPor(), mudou ? agora : linha.editadaEm());
        return itemDaLinha(repositorio.linha(importacaoId, linhaId).orElseThrow());
    }

    // ---------------------------------------------------------------------
    // Rota 112b: decidir em lote
    // ---------------------------------------------------------------------

    /** Rota 112b. Aceita ou descarta várias linhas de uma vez; as RECUSADA ficam de fora quando a decisão é "todas". */
    @Transactional
    public LinhasAlteradasDto editarEmLote(UsuarioAutenticado usuario, long importacaoId, EdicaoEmLote corpo) {
        Importacao importacao = exigirAcesso(usuario, importacaoId);
        exigirEmPrevia(importacao);
        Map<String, String> campos = new LinkedHashMap<>();
        if (corpo == null || corpo.situacao() == null) {
            campos.put("situacao", "Informe ACEITA ou DESCARTADA.");
        } else if (corpo.situacao() != SituacaoDaLinha.ACEITA && corpo.situacao() != SituacaoDaLinha.DESCARTADA) {
            campos.put("situacao", "Use ACEITA ou DESCARTADA.");
        }
        boolean todas = Boolean.TRUE.equals(corpo == null ? null : corpo.todas());
        if (!todas && (corpo == null || corpo.ids() == null || corpo.ids().isEmpty())) {
            campos.put("ids", "Informe as linhas ou use todas.");
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }
        OffsetDateTime agora = OffsetDateTime.now(br.com.rbs.portal.comum.Datas.BRASILIA);
        int alteradas = repositorio.atualizarSituacaoDasLinhas(importacaoId, todas ? null : corpo.ids(), corpo.situacao(),
                usuario.id(), agora);
        return new LinhasAlteradasDto(alteradas);
    }

    // ---------------------------------------------------------------------
    // Rota 113: confirmar
    // ---------------------------------------------------------------------

    /** Rota 113. Só a RBS confirma; a marca, só a importação dela no destino DADOS_ATIVACAO. */
    @Transactional
    public ImportacaoDetalhe confirmar(UsuarioAutenticado usuario, long id, List<Long> linhasEscolhidas,
                                       HttpServletRequest requisicao) {
        Importacao importacao = exigirAcesso(usuario, id);
        if (!usuario.ehRbs() && importacao.destino() != DestinoDaImportacao.DADOS_ATIVACAO) {
            throw new SemPermissaoException("A marca só confirma a importação de dados da ativação.");
        }
        exigirEmPrevia(importacao);
        List<LinhaGravada> todas = repositorio.linhas(id).stream().filter(l -> !l.naoEntendido()).toList();
        List<LinhaGravada> aceitas = todas.stream()
                .filter(l -> l.situacao() == SituacaoDaLinha.ACEITA)
                .filter(l -> linhasEscolhidas == null || linhasEscolhidas.isEmpty() || linhasEscolhidas.contains(l.id()))
                .toList();
        if (aceitas.isEmpty()) {
            throw new ConflitoException("Aceite ao menos uma linha antes de confirmar.");
        }
        DestinoDaImportacao destino = importacao.destino() != null ? importacao.destino() : aceitas.getFirst().destino();
        DestinoDeGravacao gravador = destinos.stream().filter(d -> d.destino() == destino).findFirst()
                .orElseThrow(() -> new ConflitoException("Este destino ainda não está disponível nesta versão."));
        ContextoDaGravacao contexto = new ContextoDaGravacao(id, importacao.eventoId(),
                new AlcanceDeUmaImportacao(importacao.eventoId(), importacao.alcance(), importacao.cotaTipoId(),
                        importacao.parceriaId()), importacao.arquivoId(), importacao.arquivoNome());
        // O modelo pode ter sugerido um destino diferente do escolhido; a confirmação usa o da importação.
        List<LinhaAceita> paraGravar = aceitas.stream()
                .map(l -> new LinhaAceita(l.id(), l.ordem(), l.parceriaId(), l.parceriaPatrocinador(),
                        l.aproveitamentoId() != null ? l.aproveitamentoId() : l.aproveitamentoSugeridoId(), proposta(l, destino)))
                .toList();
        ResultadoDaGravacao resultado = gravador.gravar(usuario, contexto, paraGravar);
        repositorio.marcarConfirmada(id, usuario.id(), resultado.cargaId());
        historico.registrar(AcaoHistorico.CRIACAO, usuario, importacao.parceriaId(), "importacao", id, "EM_PREVIA", "CONFIRMADA",
                destino + " · " + resultado.inseridas() + " inseridas, " + resultado.duplicadas() + " duplicadas, "
                        + resultado.rejeitadas() + " rejeitadas", requisicao);
        return detalhar(usuario, id);
    }

    // ---------------------------------------------------------------------
    // Rota 114: descartar
    // ---------------------------------------------------------------------

    /** Rota 114. Só em EM_PREVIA; a marca pode descartar a importação dela. */
    @Transactional
    public ImportacaoResumo descartar(UsuarioAutenticado usuario, long id, HttpServletRequest requisicao) {
        Importacao importacao = exigirAcesso(usuario, id);
        exigirEmPrevia(importacao);
        repositorio.marcarDescartada(id);
        historico.registrar(AcaoHistorico.ALTERACAO_STATUS, usuario, importacao.parceriaId(), "importacao", id, "EM_PREVIA",
                "DESCARTADA", null, requisicao);
        return resumo(repositorio.buscar(id).orElseThrow(), false);
    }

    // ---------------------------------------------------------------------
    // Rota 115: retirar
    // ---------------------------------------------------------------------

    /** Rota 115. Só a RBS, só uma importação CONFIRMADA. A veiculação apaga as linhas que ela gravou (Y1). */
    @Transactional
    public ImportacaoResumo retirar(UsuarioAutenticado usuario, long id, HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        Importacao importacao = exigirAcesso(usuario, id);
        if (importacao.situacao() != SituacaoDaImportacao.CONFIRMADA) {
            throw new ConflitoException("Só uma importação confirmada pode ser retirada.");
        }
        DestinoDaImportacao destino = importacao.destino();
        String detalhe;
        switch (destino) {
            case VEICULACAO -> detalhe = repositorio.apagarVeiculacoesDaImportacao(id) + " linha(s) de veiculação apagada(s)";
            case COMPROVANTE -> detalhe = repositorio.limparComprovantesDaImportacao(id) + " comprovante(s) desligado(s)";
            case PESQUISA -> {
                int perfil = repositorio.apagarPerfilDaImportacao(id);
                int texto = repositorio.apagarTextoDaImportacao(id);
                int resultados = importacao.cargaId() == null ? 0
                        : repositorio.apagarPesquisaResultadoDaCarga(importacao.cargaId());
                detalhe = (perfil + texto + resultados) + " linha(s) de pesquisa apagada(s)";
            }
            case NUMEROS_EDICAO, DADOS_ATIVACAO, PLANO, SO_CONSULTA -> throw new ConflitoException(
                    "Esta importação não se retira; corrija o valor na tela.");
            default -> throw new ConflitoException("Esta importação não se retira; corrija o valor na tela.");
        }
        repositorio.marcarRetirada(id, usuario.id());
        historico.registrar(AcaoHistorico.EDICAO, usuario, importacao.parceriaId(), "importacao", id, "CONFIRMADA", "RETIRADA",
                detalhe, requisicao);
        return resumo(repositorio.buscar(id).orElseThrow(), false);
    }

    // ---------------------------------------------------------------------
    // Rota 116: a origem para o visualizador
    // ---------------------------------------------------------------------

    /** Rota 116. A planilha vem com a grade em volta da célula; o texto, com o antes e o depois; a imagem, só o arquivo. */
    @Transactional(readOnly = true)
    public ImportacaoDtos.OrigemDaLinha origem(UsuarioAutenticado usuario, long importacaoId, long linhaId, String campo) {
        Importacao importacao = exigirAcesso(usuario, importacaoId);
        LinhaGravada linha = repositorio.linha(importacaoId, linhaId)
                .orElseThrow(() -> new NaoEncontradoException("Não encontramos esta linha da importação."));
        Map<String, OrigemDoValor> origens = lerOrigem(linha.origem());
        String escolhido = campo == null || campo.isBlank() ? origens.keySet().stream().findFirst().orElse(null) : campo;
        OrigemDoValor origem = escolhido == null ? null : origens.get(escolhido);
        if (origem == null) {
            throw new NaoEncontradoException("Esta linha não tem a origem do campo " + (campo == null ? "" : campo) + ".");
        }
        String tipo = TipoDeArquivo.extensaoDe(importacao.arquivoNome()).toUpperCase(Locale.ROOT);
        // A rota 116 fala PNG ou JPG; o .jpeg é o mesmo JPG.
        if ("JPEG".equals(tipo)) {
            tipo = "JPG";
        }
        Path caminho = armazenamento.localizar(importacao.arquivoCaminho())
                .orElseThrow(() -> new NaoEncontradoException("O arquivo desta importação não está mais disponível."));
        String extensao = TipoDeArquivo.extensaoDe(importacao.arquivoNome());
        if (List.of("xlsx", "xls", "csv").contains(extensao)) {
            LeitorDeConteudo leitor = leitores.stream().filter(l -> l.aceita(extensao)).findFirst()
                    .orElseThrow(() -> new NaoEncontradoException("Este arquivo não é uma planilha."));
            ConteudoLido conteudo = leitor.ler(caminho);
            return planilhaDaOrigem(importacao, conteudo, origem, tipo);
        }
        if (List.of("txt", "docx", "pptx", "pdf").contains(extensao)) {
            LeitorDeConteudo leitor = leitores.stream().filter(l -> l.aceita(extensao)).findFirst()
                    .orElseThrow(() -> new NaoEncontradoException("Este arquivo não é um texto."));
            try {
                return textoDaOrigem(importacao, leitor.ler(caminho), origem, tipo);
            } catch (LeituraFalhouException semTexto) {
                // O PDF escaneado não tem texto para o visualizador de texto; a tela mostra a imagem, e a página vem do localizador.
                return new ImportacaoDtos.OrigemDaLinha(importacao.arquivoId(), tipo, origem.localizador(), origem.trecho(), null, null,
                        paginaDoLocalizador(origem.localizador()));
            }
        }
        return new ImportacaoDtos.OrigemDaLinha(importacao.arquivoId(), tipo, origem.localizador(), origem.trecho(), null, null, null);
    }

    private ImportacaoDtos.OrigemDaLinha planilhaDaOrigem(Importacao importacao, ConteudoLido conteudo, OrigemDoValor origem,
                                                          String tipo) {
        String localizador = origem.localizador() == null ? "" : origem.localizador();
        String[] partes = localizador.split("!", 2);
        if (partes.length != 2) {
            return new ImportacaoDtos.OrigemDaLinha(importacao.arquivoId(), tipo, localizador, origem.trecho(), null, null, null);
        }
        for (var aba : br.com.rbs.portal.importacao.leitores.LeitorDePlanilha.abas(conteudo)) {
            if (!aba.nome().equals(partes[0])) {
                continue;
            }
            try {
                var referencia = new org.apache.poi.ss.util.CellReference(partes[1]);
                int inicioLinha = Math.max(0, referencia.getRow() - 3);
                int fimLinha = Math.min(aba.linhas().size() - 1, inicioLinha + 8);
                int inicioColuna = Math.max(0, referencia.getCol() - 5);
                int ultimaColuna = aba.linhas().stream().mapToInt(List::size).max().orElse(0) - 1;
                int fimColuna = Math.min(ultimaColuna, inicioColuna + 8);
                List<List<String>> grade = new ArrayList<>();
                for (int r = inicioLinha; r <= fimLinha; r++) {
                    List<String> linha = aba.linhas().get(r);
                    List<String> valores = new ArrayList<>();
                    for (int c = inicioColuna; c <= fimColuna; c++) {
                        valores.add(c < linha.size() && linha.get(c) != null ? linha.get(c) : "");
                    }
                    grade.add(valores);
                }
                return new ImportacaoDtos.OrigemDaLinha(importacao.arquivoId(), tipo, localizador, origem.trecho(),
                        new ImportacaoDtos.PlanilhaDaOrigemDto(aba.nome(), partes[1], inicioLinha + 1,
                                org.apache.poi.ss.util.CellReference.convertNumToColString(inicioColuna), grade), null, null);
            } catch (RuntimeException invalida) {
                break;
            }
        }
        return new ImportacaoDtos.OrigemDaLinha(importacao.arquivoId(), tipo, localizador, origem.trecho(), null, null, null);
    }

    private static ImportacaoDtos.OrigemDaLinha textoDaOrigem(Importacao importacao, ConteudoLido conteudo, OrigemDoValor origem,
                                                              String tipo) {
        List<Pedaco> pedacos = conteudo.pedacos();
        int indice = -1;
        for (int i = 0; i < pedacos.size(); i++) {
            if (pedacos.get(i).localizador().equals(origem.localizador())) {
                indice = i;
                break;
            }
        }
        if (indice < 0) {
            return new ImportacaoDtos.OrigemDaLinha(importacao.arquivoId(), tipo, origem.localizador(), origem.trecho(), null, null, null);
        }
        String antes = indice > 0 ? pedacos.get(indice - 1).texto() : "";
        String depois = indice + 1 < pedacos.size() ? pedacos.get(indice + 1).texto() : "";
        if (antes.length() > 300) {
            antes = antes.substring(antes.length() - 300);
        }
        if (depois.length() > 300) {
            depois = depois.substring(0, 300);
        }
        return new ImportacaoDtos.OrigemDaLinha(importacao.arquivoId(), tipo, origem.localizador(), origem.trecho(), null,
                new ImportacaoDtos.TextoDaOrigemDto(antes, origem.trecho(), depois), paginaDoLocalizador(origem.localizador()));
    }

    /** A página de um localizador "página N, linha L" (ou "página N"); nula nos outros formatos. */
    private static Integer paginaDoLocalizador(String localizador) {
        if (localizador == null) {
            return null;
        }
        java.util.regex.Matcher achado = java.util.regex.Pattern.compile("^página (\\d+)").matcher(localizador);
        return achado.find() ? Integer.valueOf(achado.group(1)) : null;
    }

    // ---------------------------------------------------------------------
    // A fila: ler e propor
    // ---------------------------------------------------------------------

    /** Recupera, na subida, o LENDO que sobrou de uma queda. */
    public int recuperarOrfaos() {
        int quantos = transacao.execute(estado -> repositorio.orfaosViramErro(LEITURA_INTERROMPIDA));
        if (quantos > 0) {
            log.warn("Importação: {} importação(ões) estavam em leitura quando a API caiu e foram para ERRO.", quantos);
        }
        return quantos;
    }

    /**
     * A tarefa da fila: lê o arquivo (sem transação), roda o detector, extrai as linhas, grava a prévia e o documento. Qualquer
     * falha vira ERRO com o motivo; nunca fica preso em LENDO.
     */
    public void processar(long id) {
        try {
            Importacao importacao = repositorio.buscar(id).orElse(null);
            if (importacao == null || importacao.situacao() != SituacaoDaImportacao.LENDO) {
                return;
            }
            Path caminho = armazenamento.localizar(importacao.arquivoCaminho())
                    .orElseThrow(() -> new LeituraFalhouException("O arquivo da importação não está mais no armazenamento. Envie de novo."));
            byte[] bytes = Files.readAllBytes(caminho);
            String extensao = TipoDeArquivo.extensaoDe(importacao.arquivoNome());
            LeitorDeConteudo leitor = leitores.stream().filter(l -> l.aceita(extensao)).findFirst()
                    .orElseThrow(() -> new LeituraFalhouException("Este formato ainda não é lido nesta versão (" + extensao + ")."));
            ConteudoLido conteudo;
            try {
                conteudo = leitor.ler(caminho);
            } catch (LeituraFalhouException semTexto) {
                // PDF sem texto (escaneado): a leitura por imagem transcreve cada página e a extração segue sobre a transcrição (E9).
                if ("pdf".equals(extensao) && br.com.rbs.portal.documentos.ExtratorDeTexto.SEM_TEXTO.equals(semTexto.getMessage())) {
                    LeitorDeImagem imagens = leitores.stream().filter(LeitorDeImagem.class::isInstance).map(LeitorDeImagem.class::cast)
                            .findFirst().orElseThrow(() -> semTexto);
                    conteudo = imagens.lerPdfEscaneado(caminho);
                } else {
                    throw semTexto;
                }
            }
            List<Pagina> paginas = paginas(conteudo);
            List<Suspeita> suspeitas = detector.analisarConversa(paginas);
            String primeirasLinhas = primeirasLinhas(conteudo);
            // Com suspeita de dado pessoal nada vai ao modelo nem ao JEV (E4).
            JevDoTurno jev = suspeitas.isEmpty() ? new JevDoTurno() : JevDoTurno.NENHUM;
            Optional<ServicoDoJev.SugestaoDoArquivo> doJev = servicoJev.arquivoDestino(jev, id, importacao.arquivoNome(),
                    extensao.toUpperCase(Locale.ROOT), primeirasLinhas);
            // O cabeçalho reconhecido em código (a planilha de pesquisa) vale antes da extensão e dispensa o modelo.
            DestinoDaImportacao porExtensao = destinoSugerido(extensao);
            DestinoDaImportacao sugerido = sugestaoDoConteudo(conteudo, porExtensao);
            boolean peloConteudo = sugerido != porExtensao;
            BigDecimal confianca = new BigDecimal(peloConteudo ? "0.900" : "0.800");
            if (importacao.destino() == null) {
                ServicoDoJev.SugestaoDoArquivo doJevQueAgiu = doJev.filter(ServicoDoJev.SugestaoDoArquivo::agiu).orElse(null);
                DestinoDaImportacao doJevDestino = doJevQueAgiu == null ? null : destinoDaEscolha(doJevQueAgiu.escolha());
                if (doJevDestino != null) {
                    sugerido = doJevDestino;
                    confianca = BigDecimal.valueOf(doJevQueAgiu.confianca()).setScale(3, java.math.RoundingMode.HALF_UP);
                } else if (suspeitas.isEmpty() && !peloConteudo) {
                    Optional<SugestorDeDestino.Sugestao> doModelo = sugestorDeDestino.sugerir(importacao.arquivoNome(),
                            extensao.toUpperCase(Locale.ROOT), primeirasLinhas);
                    if (doModelo.isPresent()) {
                        sugerido = doModelo.get().destino();
                        confianca = doModelo.get().confianca();
                    }
                }
            }
            DestinoDaImportacao destino = importacao.destino() != null ? importacao.destino() : sugerido;
            ResultadoDaExtracao extracao = extrair(conteudo, destino, importacao, suspeitas);
            if (extracao.destinoSugerido() != null && importacao.destino() == null) {
                sugerido = extracao.destinoSugerido();
                destino = sugerido;
            }
            if (extracao.confianca() != null) {
                confianca = extracao.confianca();
            }
            DestinoDaImportacao destinoFinal = destino;
            DestinoDaImportacao sugeridoFinal = sugerido;
            BigDecimal confiancaFinal = confianca;
            ConteudoLido conteudoFinal = conteudo;
            try {
                transacao.executeWithoutResult(estado -> gravarPrevia(importacao, conteudoFinal, bytes, suspeitas, extracao,
                        sugeridoFinal, destinoFinal, confiancaFinal));
            } finally {
                servicoJev.gravarDoArquivo(jev, id);
            }
        } catch (Throwable erro) {
            marcarErro(id, motivoDe(erro), erro);
        }
    }

    private void gravarPrevia(Importacao importacao, ConteudoLido conteudo, byte[] bytes, List<Suspeita> suspeitas,
                              ResultadoDaExtracao extracao, DestinoDaImportacao sugerido, DestinoDaImportacao destino,
                              BigDecimal confianca) {
        int ordem = 1;
        // A mesma linha duas vezes no arquivo (mesma marca, veículo, formato, data e quantidade) contaria a exibição em dobro: a
        // segunda vem recusada, apontando a primeira (achado da rodada do Atlas da RBS, 06/11).
        Map<String, String> vistas = new HashMap<>();
        for (LinhaProposta proposta : extracao.linhas()) {
            ConferenciaDaLinha conferenciaDaLinha = conferir(importacao, destino, proposta);
            List<String> problemas = new ArrayList<>(conferenciaDaLinha.problemas());
            String dados = JSON.writeValueAsString(proposta.dados());
            OrigemDoValor onde = proposta.origem().get("veiculo");
            String repetida = destino == DestinoDaImportacao.VEICULACAO
                    ? vistas.putIfAbsent(dados, onde != null && onde.localizador() != null ? onde.localizador() : "a linha " + ordem)
                    : null;
            if (repetida != null) {
                problemas.add("Repete " + repetida.replaceFirst("!.*?(\\d+)$", ", linha $1") + " deste arquivo.");
            }
            SituacaoDaLinha situacao = problemas.isEmpty() ? SituacaoDaLinha.PROPOSTA : SituacaoDaLinha.RECUSADA;
            repositorio.inserirLinha(importacao.id(), ordem++, destino, dados,
                    JSON.writeValueAsString(proposta.origem()), situacao,
                    problemas.isEmpty() ? null : String.join(" ", problemas),
                    conferenciaDaLinha.parceriaId(), conferenciaDaLinha.aproveitamentoSugeridoId(),
                    conferenciaDaLinha.semelhanca());
        }
        for (NaoEntendidoDto naoEntendido : extracao.naoEntendidos()) {
            Map<String, Object> dados = new LinkedHashMap<>();
            dados.put("naoEntendido", true);
            dados.put("localizador", naoEntendido.localizador());
            dados.put("trecho", naoEntendido.trecho());
            repositorio.inserirLinha(importacao.id(), ordem++, destino, JSON.writeValueAsString(dados), "{}",
                    SituacaoDaLinha.DESCARTADA, naoEntendido.motivo(), null, null, null);
        }
        Long documentoId = criarDocumento(importacao, conteudo, bytes, suspeitas);
        if (documentoId != null) {
            repositorio.gravarDocumento(importacao.id(), documentoId);
        }
        repositorio.marcarEmPrevia(importacao.id(), sugerido, destino, confianca);
    }

    private ResultadoDaExtracao extrair(ConteudoLido conteudo, DestinoDaImportacao destino, Importacao importacao,
                                        List<Suspeita> suspeitas) {
        if (destino == DestinoDaImportacao.SO_CONSULTA) {
            return ResultadoDaExtracao.vazio();
        }
        ExtratorDeLinhas extrator = extratores.stream().filter(e -> e.aceita(conteudo)).findFirst().orElse(null);
        if (extrator == null) {
            return ResultadoDaExtracao.vazio();
        }
        AlcanceDeUmaImportacao alcance = new AlcanceDeUmaImportacao(importacao.eventoId(), importacao.alcance(),
                importacao.cotaTipoId(), importacao.parceriaId());
        return suspeitas.isEmpty() ? extrator.extrairComDetalhe(conteudo, destino, alcance)
                : extrator.extrairSemModeloComDetalhe(conteudo, destino, alcance);
    }

    private ResultadoDoCasamento casar(long parceriaId, Map<String, Object> dados) {
        CasadorDoPlano casadorDoPlano = casador.getIfAvailable();
        if (casadorDoPlano == null) {
            return new ResultadoDoCasamento(null, null, null);
        }
        CasadorDoPlano.Resultado resultado = casadorDoPlano.casar(parceriaId, texto(dados.get("veiculo")),
                texto(dados.get("formato")), texto(dados.get("espaco_programa")));
        if (resultado.problema() != null) {
            return new ResultadoDoCasamento(null, null, resultado.problema());
        }
        if (resultado.aproveitamentoId() == null) {
            return new ResultadoDoCasamento(null, null, "Escolha a linha do plano.");
        }
        return new ResultadoDoCasamento(resultado.aproveitamentoId(), resultado.semelhanca(), null);
    }

    private record ResultadoDoCasamento(Long aproveitamentoId, BigDecimal semelhanca, String problema) {
    }

    /** O que a conferência devolve para uma linha: a marca resolvida, a linha do plano sugerida e os motivos de recusa. */
    /** A frase do conflito do mês (M2), ou nulo: a linha mensal contra exibições do mês na base, e a exibição contra o total. */
    private String conflitoDoMes(Long aproveitamentoId, Map<String, Object> dados) {
        if (aproveitamentoId == null) {
            return null;
        }
        String mes = texto(dados.get("mes"));
        String exibidoEm = texto(dados.get("exibido_em"));
        if (mes != null && mes.matches("\\d{4}-\\d{2}")) {
            return repositorio.haExibicoesNoMes(aproveitamentoId, mes)
                    ? "Esta linha do plano já tem exibições em " + mes + " na base; o total do mês e as exibições não se somam. "
                            + "Use um ou outro: retire a importação das exibições ou deixe esta linha de fora."
                    : null;
        }
        if (exibidoEm != null && exibidoEm.length() >= 7 && exibidoEm.substring(0, 7).matches("\\d{4}-\\d{2}")) {
            String doMes = exibidoEm.substring(0, 7);
            return repositorio.haTotalDoMes(aproveitamentoId, doMes)
                    ? "Esta linha do plano já tem o total de " + doMes + " na base; o total do mês e as exibições não se somam. "
                            + "Use um ou outro: retire a importação do total ou deixe esta linha de fora."
                    : null;
        }
        return null;
    }

    private record ConferenciaDaLinha(Long parceriaId, Long aproveitamentoSugeridoId, BigDecimal semelhanca, List<String> problemas) {
    }

    private ConferenciaDaLinha conferir(Importacao importacao, DestinoDaImportacao destino, LinhaProposta proposta) {
        Long parceriaId = null;
        String marca = texto(proposta.dados().get("patrocinador"));
        List<String> problemas = new ArrayList<>();
        if (destino == DestinoDaImportacao.VEICULACAO || destino == DestinoDaImportacao.PLANO
                || destino == DestinoDaImportacao.COMPROVANTE
                || (destino == DestinoDaImportacao.PESQUISA && marca != null && !marca.isBlank())) {
            Parceria parceria = repositorio.parceriaDaMarca(importacao.eventoId(), marca).orElse(null);
            if (parceria == null) {
                problemas.add("Marca não encontrada nesta edição.");
            } else if (!noAlcance(importacao, parceria)) {
                // A linha recusada continua na prévia, mas sem parceria_id: o gatilho rn_importacao_linha_alcance recusaria a
                // gravação da linha de uma marca fora do alcance.
                problemas.add("A marca " + parceria.patrocinador() + " está fora do alcance da importação.");
            } else {
                parceriaId = parceria.id();
            }
        } else if (destino == DestinoDaImportacao.DADOS_ATIVACAO) {
            if (importacao.alcance() != AlcanceDaImportacao.PARCERIA || importacao.parceriaId() == null) {
                problemas.add("Os dados da ativação são de uma importação de parceria.");
            } else {
                parceriaId = importacao.parceriaId();
            }
        }
        Long sugeridoId = null;
        BigDecimal semelhanca = null;
        if (problemas.isEmpty() && destino == DestinoDaImportacao.VEICULACAO && parceriaId != null) {
            ResultadoDoCasamento casamento = casar(parceriaId, proposta.dados());
            if (casamento.problema() != null) {
                problemas.add(casamento.problema());
            } else {
                sugeridoId = casamento.aproveitamentoId();
                semelhanca = casamento.semelhanca();
                // M2: o total do mês e as exibições do mesmo mês nunca se somam. A carga recusaria a linha na confirmação; a
                // prévia já avisa, com o que está na base (achado da rodada H1).
                String conflito = conflitoDoMes(sugeridoId, proposta.dados());
                if (conflito != null) {
                    problemas.add(conflito);
                }
            }
        }
        AlcanceDeUmaImportacao alcance = new AlcanceDeUmaImportacao(importacao.eventoId(), importacao.alcance(),
                importacao.cotaTipoId(), importacao.parceriaId());
        ConferenciaDaExtracao conferidor = conferencia.getIfAvailable();
        if (conferidor != null && problemas.isEmpty()) {
            problemas.addAll(conferidor.problemas(proposta, alcance, parceriaId));
        }
        DestinoDeGravacao gravador = destinos.stream().filter(d -> d.destino() == destino).findFirst().orElse(null);
        if (gravador != null && problemas.isEmpty()) {
            problemas.addAll(gravador.problemas(proposta, alcance));
        }
        return new ConferenciaDaLinha(parceriaId, sugeridoId, semelhanca, problemas);
    }

    // ---------------------------------------------------------------------
    // O documento da importação (E15)
    // ---------------------------------------------------------------------

    private Long criarDocumento(Importacao importacao, ConteudoLido conteudo, byte[] bytes, List<Suspeita> suspeitas) {
        boolean dadosPessoais = !suspeitas.isEmpty();
        if (conteudo.pedacos().isEmpty()) {
            return null;
        }
        String escopo = switch (importacao.alcance()) {
            case EDICAO -> "GLOBAL";
            case COTA -> "COTA";
            case PARCERIA -> "PARCERIA";
        };
        String chave = "importacao-" + importacao.id();
        String titulo = importacao.arquivoNome().length() > 200 ? importacao.arquivoNome().substring(0, 200)
                : importacao.arquivoNome();
        repositorio.travarGrupoDeDocumento(importacao.eventoId(), escopo, importacao.cotaTipoId(), importacao.parceriaId(), chave);
        int versao = repositorio.proximaVersaoDeDocumento(importacao.eventoId(), escopo, importacao.cotaTipoId(),
                importacao.parceriaId(), chave);
        // Só a RBS aprova o documento: o da marca fica aguardando, porque o aprovador tem de ser um usuário da RBS.
        Long aprovadoPor = importacao.origem() == ImportacaoDtos.OrigemDaImportacao.RBS ? importacao.criadaPor() : null;
        // O gatilho do documento exige arquivo da categoria DOCUMENTO; o arquivo da importação é IMPORTACAO. A cópia tem o mesmo
        // conteúdo e um nome novo no disco.
        var arquivoDoDocumento = armazenamento.gravarDocumentoImportado(bytes, importacao.arquivoNome(), importacao.criadaPor(),
                importacao.parceriaId());
        long documentoId = repositorio.inserirDocumento(titulo, chave, versao, escopo, importacao.eventoId(),
                importacao.cotaTipoId(), importacao.parceriaId(), arquivoDoDocumento.id(), dadosPessoais, null,
                importacao.criadaPor(), aprovadoPor);
        if (dadosPessoais) {
            List<Map<String, Object>> lista = suspeitas.stream()
                    .map(s -> Map.<String, Object>of("pagina", s.pagina(), "tipo", s.tipo())).toList();
            repositorio.gravarSuspeitasDoDocumento(documentoId, JSON.writeValueAsString(lista));
        }
        if (aprovadoPor == null || dadosPessoais || !embeddings.ligado()) {
            // Sem aprovação (a importação da marca, no futuro), com suspeita de dado pessoal ou com os embeddings desligados, o
            // documento fica aguardando: não entra em PROCESSANDO nem tem trechos indexados (o gatilho recusa os dois). A importação
            // em si segue em EM_PREVIA; o documento continua como fonte, sem busca vetorial.
            if (!embeddings.ligado() && !dadosPessoais) {
                log.warn("Importação: o documento {} fica aguardando porque os embeddings estão desligados ({}).", documentoId,
                        embeddings.motivoDeDesligado());
            }
            return documentoId;
        }
        repositorio.aprovarDocumento(documentoId, aprovadoPor);
        List<TextoDoTrecho> textos = conteudo.pedacos().stream()
                .map(p -> new TextoDoTrecho(p.localizador(), p.texto())).toList();
        List<float[]> vetores;
        try {
            vetores = embeddings.paraTrechos(titulo, textos);
        } catch (RuntimeException semVetor) {
            log.warn("Importação: os embeddings não responderam para o documento {}; ele fica aguardando aprovação.", documentoId);
            return documentoId;
        }
        if (vetores.size() != conteudo.pedacos().size()) {
            log.warn("Importação: os embeddings voltaram em número diferente dos trechos ({} para {}); o documento {} fica aguardando.",
                    vetores.size(), conteudo.pedacos().size(), documentoId);
            return documentoId;
        }
        for (int i = 0; i < conteudo.pedacos().size(); i++) {
            Pedaco pedaco = conteudo.pedacos().get(i);
            repositorio.inserirTrechoDoDocumento(documentoId, i + 1, pedaco.localizador(), pedaco.texto(),
                    Math.max(1, pedaco.texto().length() / 4), ProvedorDeEmbeddings.comoTextoDoBanco(vetores.get(i)));
        }
        repositorio.gravarModeloDoDocumento(documentoId, embeddings.modelo(), ProvedorDeEmbeddings.DIMENSOES);
        repositorio.publicarDocumento(documentoId);
        return documentoId;
    }

    // ---------------------------------------------------------------------
    // Apoio
    // ---------------------------------------------------------------------

    private Importacao exigirAcesso(UsuarioAutenticado usuario, long id) {
        Importacao importacao = repositorio.buscar(id).orElseThrow(ImportacaoService::naoEncontrada);
        if (!usuario.ehRbs()) {
            if (importacao.origem() != ImportacaoDtos.OrigemDaImportacao.MARCA || importacao.parceriaId() == null
                    || !repositorio.temVinculo(usuario.id(), importacao.parceriaId())) {
                throw naoEncontrada();
            }
        }
        return importacao;
    }

    private static void exigirEmPrevia(Importacao importacao) {
        if (importacao.situacao() != SituacaoDaImportacao.EM_PREVIA) {
            throw new ConflitoException("A importação está " + fraseDaSituacao(importacao.situacao())
                    + " e não muda mais. Retire a importação e importe de novo.");
        }
    }

    private ImportacaoResumo resumo(Importacao i, boolean comSuspeitas) {
        Map<String, Object> contagens = repositorio.contagensPorSituacao(i.id());
        ContagemDeLinhasDto linhas = new ContagemDeLinhasDto(numero(contagens.get("total")), numero(contagens.get("propostas")),
                numero(contagens.get("editadas")), numero(contagens.get("aceitas")), numero(contagens.get("descartadas")),
                numero(contagens.get("recusadas")));
        return new ImportacaoResumo(i.id(), i.situacao(), i.origem(),
                new ArquivoDaImportacaoDto(i.arquivoId(), i.arquivoNome(), TipoDeArquivo.extensaoDe(i.arquivoNome()).toUpperCase(Locale.ROOT),
                        i.arquivoTamanho()),
                new EventoDaImportacaoDto(i.eventoId(), i.eventoNome(), i.eventoEdicao()), i.alcance(),
                i.cotaTipoId() == null ? null : new CotaDaImportacaoDto(i.cotaTipoId(), i.cotaCodigo(), i.cotaNome()),
                i.parceriaId() == null ? null : new ParceriaDaImportacaoDto(i.parceriaId(), i.parceriaPatrocinador()), i.destino(),
                i.destinoSugerido(), i.confianca(), linhas,
                i.criadaPor() == null ? null : new UsuarioDaImportacaoDto(i.criadaPor(), i.criadaPorNome()), i.criadaEm(),
                i.confirmadaEm(), i.retiradaEm(), i.erro(), comSuspeitas ? suspeitas(i.documentoId()) : List.of());
    }

    private List<SuspeitaDeDadoPessoalDto> suspeitas(Long documentoId) {
        if (documentoId == null) {
            return List.of();
        }
        return repositorio.suspeitasDoDocumento(documentoId).stream().map(s -> new SuspeitaDeDadoPessoalDto(s.pagina(), s.tipo())).toList();
    }

    private ItemDaLinha itemDaLinha(LinhaGravada linha) {
        Map<String, Object> dados = lerDados(linha.dados());
        Map<String, OrigemDoValor> origem = lerOrigem(linha.origem());
        ParceriaDaImportacaoDto parceria = linha.parceriaId() == null ? null
                : new ParceriaDaImportacaoDto(linha.parceriaId(), linha.parceriaPatrocinador());
        return new ItemDaLinha(linha.id(), linha.ordem(), linha.destino(), linha.situacao(), linha.motivo(), dados, origem, parceria,
                aproveitamento(linha.aproveitamentoSugeridoId(), linha.semelhanca(), linha.parceriaId()),
                aproveitamento(linha.aproveitamentoId(), null, linha.parceriaId()),
                linha.editadaPor() == null ? null : new UsuarioDaImportacaoDto(linha.editadaPor(), linha.editadaPorNome()),
                linha.editadaEm());
    }

    private AproveitamentoDaImportacaoDto aproveitamento(Long id, BigDecimal semelhanca, Long parceriaId) {
        if (id == null) {
            return null;
        }
        Optional<PlanilhaDoPlano> planilha = parceriaId == null ? repositorio.planilha(id) : repositorio.planilha(id, parceriaId);
        return planilha.map(p -> new AproveitamentoDaImportacaoDto(p.id(), p.veiculo(), p.formato(), p.espacoPrograma(), semelhanca))
                .orElse(null);
    }

    private NaoEntendidoDto naoEntendido(LinhaGravada linha) {
        Map<String, Object> dados = lerDados(linha.dados());
        return new NaoEntendidoDto(texto(dados.get("localizador")), texto(dados.get("trecho")), linha.motivo());
    }

    private ResultadoDaConfirmacao resultado(Importacao importacao, List<ItemDaLinha> itens) {
        if (importacao.situacao() != SituacaoDaImportacao.CONFIRMADA && importacao.situacao() != SituacaoDaImportacao.RETIRADA) {
            return null;
        }
        if (importacao.cargaId() != null) {
            TotaisDaCarga totais = repositorio.totaisDaCarga(importacao.cargaId()).orElse(null);
            List<RejeicaoDaLinhaDto> rejeicoes = repositorio.rejeicoesDaCarga(importacao.cargaId()).stream()
                    .map(r -> new RejeicaoDaLinhaDto(r.ordem(), r.motivo())).toList();
            if (totais != null) {
                // A carga conta só os RESULTADO; o perfil e o texto livre, que não passam pela área de passagem, entram na
                // mesma conta para a tela dizer quantas linhas foram salvas.
                int foraDaCarga = importacao.destino() == DestinoDaImportacao.PESQUISA ? (int) itens.stream()
                        .filter(i -> i.situacao() == SituacaoDaLinha.ACEITA)
                        .filter(i -> {
                            String tipo = i.dados() == null ? null : texto(i.dados().get("tipo"));
                            return "PERFIL".equalsIgnoreCase(tipo) || "TEXTO".equalsIgnoreCase(tipo);
                        }).count() : 0;
                return new ResultadoDaConfirmacao(totais.lidas() + foraDaCarga, totais.inseridas() + foraDaCarga,
                        totais.duplicadas(), totais.rejeitadas(), rejeicoes);
            }
        }
        int aceitas = (int) itens.stream().filter(i -> i.situacao() == SituacaoDaLinha.ACEITA).count();
        return new ResultadoDaConfirmacao(itens.size(), aceitas, 0, 0, List.of());
    }

    private LinhaProposta proposta(LinhaGravada linha, DestinoDaImportacao destino) {
        Map<String, OrigemDoValor> origens = lerOrigem(linha.origem());
        return new LinhaProposta(destino, lerDados(linha.dados()), origens, ehDeTexto(origens));
    }

    /** A linha veio de texto corrido? O localizador de uma planilha é "Aba!Célula"; os outros formatos não têm célula. */
    private static boolean ehDeTexto(Map<String, OrigemDoValor> origens) {
        return origens.values().stream().anyMatch(o -> o.localizador() != null
                && !o.localizador().matches(".+![A-Z]{1,3}\\d+"));
    }

    private Map<String, Object> lerDados(String json) {
        try {
            return json == null || json.isBlank() ? new LinkedHashMap<>() : JSON.readValue(json, MAPA_DE_DADOS);
        } catch (RuntimeException erro) {
            return new LinkedHashMap<>();
        }
    }

    private Map<String, OrigemDoValor> lerOrigem(String json) {
        try {
            return json == null || json.isBlank() ? new LinkedHashMap<>() : JSON.readValue(json, MAPA_DE_ORIGENS);
        } catch (RuntimeException erro) {
            return new LinkedHashMap<>();
        }
    }

    private static List<Pagina> paginas(ConteudoLido conteudo) {
        List<Pagina> paginas = new ArrayList<>();
        for (Pedaco pedaco : conteudo.pedacos()) {
            paginas.add(new Pagina(pedaco.ordem(), List.of(new Linha(pedaco.texto(), 0, List.of(), false))));
        }
        return paginas;
    }

    private static boolean noAlcance(Importacao importacao, Parceria parceria) {
        if (parceria.eventoId() != importacao.eventoId()) {
            return false;
        }
        return switch (importacao.alcance()) {
            case EDICAO -> true;
            case COTA -> importacao.cotaTipoId() != null && importacao.cotaTipoId().equals(parceria.cotaTipoId());
            case PARCERIA -> importacao.parceriaId() != null && importacao.parceriaId() == parceria.id();
        };
    }

    /** A sugestão do próprio conteúdo (a planilha de pesquisa), antes da sugestão por extensão. */
    private DestinoDaImportacao sugestaoDoConteudo(ConteudoLido conteudo, DestinoDaImportacao porExtensao) {
        for (ExtratorDeLinhas extrator : extratores) {
            if (extrator.aceita(conteudo)) {
                DestinoDaImportacao doConteudo = extrator.sugerirDestino(conteudo);
                if (doConteudo != null) {
                    return doConteudo;
                }
            }
        }
        return porExtensao;
    }

    private static DestinoDaImportacao destinoSugerido(String extensao) {
        return switch (extensao) {
            case "csv", "xlsx", "xls" -> DestinoDaImportacao.VEICULACAO;
            case "png", "jpg", "jpeg" -> DestinoDaImportacao.COMPROVANTE;
            case "pdf" -> DestinoDaImportacao.NUMEROS_EDICAO;
            default -> DestinoDaImportacao.SO_CONSULTA;
        };
    }

    /** A escolha do JEV (ou do modelo) em minúsculas, como no perguntas.json, no enum do destino; nula se não for um destino. */
    private static DestinoDaImportacao destinoDaEscolha(String escolha) {
        if (escolha == null || escolha.isBlank()) {
            return null;
        }
        try {
            return DestinoDaImportacao.valueOf(escolha.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalido) {
            return null;
        }
    }

    /** As primeiras linhas que o JEV e o modelo do destino veem: até 15 linhas (ou pedaços), no máximo 1.500 caracteres. */
    private static String primeirasLinhas(ConteudoLido conteudo) {
        List<String> linhas = new ArrayList<>();
        var abas = br.com.rbs.portal.importacao.leitores.LeitorDePlanilha.abas(conteudo);
        if (!abas.isEmpty()) {
            var aba = abas.getFirst();
            for (int r = 0; r < Math.min(15, aba.linhas().size()); r++) {
                linhas.add(String.join(" | ", aba.linhas().get(r)));
            }
        } else {
            for (int i = 0; i < Math.min(15, conteudo.pedacos().size()); i++) {
                linhas.add(conteudo.pedacos().get(i).texto());
            }
        }
        String texto = String.join("\n", linhas);
        return texto.length() > 1_500 ? texto.substring(0, 1_500) : texto;
    }

    private static String etapa(Importacao importacao) {
        return importacao.situacao() == SituacaoDaImportacao.LENDO ? "LENDO_ARQUIVO" : null;
    }

    private static String fraseDaSituacao(SituacaoDaImportacao situacao) {
        return switch (situacao) {
            case LENDO -> "em leitura";
            case EM_PREVIA -> "em prévia";
            case CONFIRMADA -> "confirmada";
            case DESCARTADA -> "descartada";
            case RETIRADA -> "retirada";
            case ERRO -> "com erro";
        };
    }

    private static String motivoOuPadrao(String motivo) {
        return motivo == null || motivo.isBlank() ? "A linha foi recusada na conferência e não pode ser aceita sem correção." : motivo;
    }

    private void marcarErro(long id, String motivo, Throwable erro) {
        if (!(erro instanceof LeituraFalhouException)) {
            log.error("Importação: a leitura da importação {} falhou", id, erro);
        } else {
            log.warn("Importação: a leitura da importação {} falhou: {}", id, motivo);
        }
        try {
            transacao.executeWithoutResult(estado -> repositorio.marcarErro(id, motivo));
        } catch (Throwable naoGravou) {
            log.error("Importação: não consegui gravar o erro da importação {}", id, naoGravou);
        }
    }

    private static String motivoDe(Throwable erro) {
        String motivo = erro instanceof LeituraFalhouException || erro instanceof br.com.rbs.portal.comum.DadosInvalidosException
                ? erro.getMessage() : null;
        if (motivo == null || motivo.isBlank()) {
            motivo = "Falha inesperada na leitura (" + erro.getClass().getSimpleName() + "). Veja o log da API.";
        }
        motivo = motivo.replace("\u0000", "");
        return motivo.length() > LIMITE_DO_MOTIVO ? motivo.substring(0, LIMITE_DO_MOTIVO) : motivo;
    }

    private static String texto(Object valor) {
        return valor == null ? null : String.valueOf(valor);
    }

    private static int numero(Object valor) {
        return valor instanceof Number n ? n.intValue() : 0;
    }

    private static Long numero(String valor) {
        try {
            return valor == null || valor.isBlank() ? null : Long.valueOf(valor.strip());
        } catch (NumberFormatException naoEhNumero) {
            return null;
        }
    }

    private static AlcanceDaImportacao alcance(String valor, Map<String, String> campos) {
        if (valor == null || valor.isBlank()) {
            campos.put("alcance", "Informe o alcance: EDICAO, COTA ou PARCERIA.");
            return null;
        }
        try {
            return AlcanceDaImportacao.valueOf(valor.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalido) {
            campos.put("alcance", "Alcance inválido. Use EDICAO, COTA ou PARCERIA.");
            return null;
        }
    }

    private static DestinoDaImportacao destino(String valor, Map<String, String> campos) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return DestinoDaImportacao.valueOf(valor.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalido) {
            campos.put("destino", "Destino inválido. Use VEICULACAO, COMPROVANTE, PESQUISA, NUMEROS_EDICAO, DADOS_ATIVACAO, PLANO "
                    + "ou SO_CONSULTA.");
            return null;
        }
    }

    private static List<SituacaoDaImportacao> situacoes(String valor) {
        if (valor == null || valor.isBlank()) {
            return List.of();
        }
        List<SituacaoDaImportacao> situacoes = new ArrayList<>();
        for (String parte : valor.split(",")) {
            try {
                situacoes.add(SituacaoDaImportacao.valueOf(parte.strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException invalido) {
                throw new DadosInvalidosException(Map.of("situacao", "Situação inválida. Use LENDO, EM_PREVIA, CONFIRMADA, DESCARTADA, "
                        + "RETIRADA ou ERRO."));
            }
        }
        return situacoes;
    }

    private static byte[] bytesDe(MultipartFile arquivo) {
        try {
            return arquivo.getBytes();
        } catch (IOException erro) {
            throw new LeituraFalhouException("Não consegui ler o arquivo enviado.", erro);
        }
    }

    private static NaoEncontradoException naoEncontrada() {
        return new NaoEncontradoException("Não encontramos esta importação.");
    }
}
