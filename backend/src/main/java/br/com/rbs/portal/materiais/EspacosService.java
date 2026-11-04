package br.com.rbs.portal.materiais;

import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.Armazenamento;
import br.com.rbs.portal.comum.ArquivoRecusadoException;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.TipoDeArquivo;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.materiais.EspacosDtos.EspacosDto;
import br.com.rbs.portal.materiais.EspacosDtos.FormularioDeIngressosDto;
import br.com.rbs.portal.materiais.EspacosDtos.PlanoComercialDto;
import br.com.rbs.portal.materiais.MateriaisDtos.ArquivoDto;
import br.com.rbs.portal.materiais.MateriaisDtos.ArquivoEnviadoDto;
import br.com.rbs.portal.materiais.MateriaisDtos.EntregaDto;
import br.com.rbs.portal.materiais.MateriaisDtos.UsuarioResumoDto;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * O plano comercial do Perfil (rotas 69 e 70) e os espaços de documento da marca (rotas 71 a 73): a compra de ingressos com valor de
 * patrocinador (formulário em branco da RBS e uma solicitação por envio da marca), o MIV e a logo. Atende R5.1.b, R5.1.c e R5.2.a
 * do plano final. [ISOLAMENTO] Parceria de outra marca dá 404. [PERMISSAO] A marca só mexe em aba aberta (403); o plano e o
 * formulário em branco são só da RBS. A parceria ENCERRADA recusa toda escrita (409). [HISTORICO] Cada envio entra no histórico, sem
 * dado pessoal, na mesma transação do arquivo.
 */
@Service
public class EspacosService {

    private static final int LIMITE_OBSERVACAO = 500;
    private static final String CATEGORIA_PLANO = "PLANO_COMERCIAL";
    private static final String CATEGORIA_FORMULARIO = "FORMULARIO_INGRESSOS";

    /** Os formatos do MIV e da logo. O .svg e o .ai só entram dentro de um .zip (o SVG pode carregar script). */
    private static final Set<TipoDeArquivo> FORMATOS_DA_MARCA = Set.of(TipoDeArquivo.PNG, TipoDeArquivo.JPG, TipoDeArquivo.JPEG,
            TipoDeArquivo.PDF, TipoDeArquivo.ZIP);

    static final String FRASE_DOS_FORMATOS = "Para o MIV e a logo, envie PNG, JPG, PDF ou ZIP. Arquivos .svg e .ai só dentro de um .zip.";
    static final String FRASE_DA_PARCERIA_ENCERRADA = "Esta parceria está encerrada e não aceita novos envios.";

    private final VerificadorDeAcesso verificador;
    private final EspacosRepositorio repositorio;
    private final EntregaRepositorio entregas;
    private final EntregaService entregaService;
    private final Armazenamento armazenamento;
    private final HistoricoService historico;

    public EspacosService(VerificadorDeAcesso verificador, EspacosRepositorio repositorio, EntregaRepositorio entregas,
                          EntregaService entregaService, Armazenamento armazenamento, HistoricoService historico) {
        this.verificador = verificador;
        this.repositorio = repositorio;
        this.entregas = entregas;
        this.entregaService = entregaService;
        this.armazenamento = armazenamento;
        this.historico = historico;
    }

    // ---------------------------------------------------------------------
    // Rotas 69 e 70: plano comercial
    // ---------------------------------------------------------------------

    /** Rota 69. [ISOLAMENTO] 404 para a parceria de outra marca. [PERMISSAO] 403 se o Perfil está fechado para a marca. */
    @Transactional(readOnly = true)
    public PlanoComercialDto planoComercial(UsuarioAutenticado usuario, long parceriaId) {
        verificador.exigirParceria(usuario, parceriaId);
        verificador.exigirAbaLiberada(usuario, parceriaId, EspacosDaMarca.ABA_PERFIL);
        return new PlanoComercialDto(parceriaId, repositorio.versoesAvulsas(parceriaId, CATEGORIA_PLANO));
    }

    /**
     * Rota 70: a RBS envia uma nova versão do plano. A versão é a maior mais 1, calculada com a linha da parceria travada (FOR UPDATE):
     * dois envios juntos viram v2 e v3. O plano é arquivo avulso, não entrega: não conta como pendente.
     */
    @Transactional
    public ArquivoDto enviarPlano(UsuarioAutenticado usuario, long parceriaId, MultipartFile arquivo, String observacaoInformada,
                                  HttpServletRequest requisicao) {
        return enviarAvulso(usuario, parceriaId, arquivo, observacaoInformada, requisicao, CATEGORIA_PLANO, "Plano comercial");
    }

    /** Rota 73: a RBS publica o formulário em branco da compra de ingressos, versionado como o plano. */
    @Transactional
    public ArquivoDto publicarFormulario(UsuarioAutenticado usuario, long parceriaId, MultipartFile arquivo, String observacaoInformada,
                                         HttpServletRequest requisicao) {
        return enviarAvulso(usuario, parceriaId, arquivo, observacaoInformada, requisicao, CATEGORIA_FORMULARIO,
                "Formulário de ingressos");
    }

    private ArquivoDto enviarAvulso(UsuarioAutenticado usuario, long parceriaId, MultipartFile arquivo, String observacaoInformada,
                                    HttpServletRequest requisicao, String categoria, String detalhe) {
        verificador.exigirRbs(usuario);
        verificador.exigirParceria(usuario, parceriaId);
        exigirParceriaAberta(parceriaId, true);
        String observacao = conferirCampos(arquivo, observacaoInformada);

        var gravado = armazenamento.gravarVersionadoNaAba(bytesDe(arquivo), arquivo.getOriginalFilename(), categoria, usuario.id(),
                parceriaId, EspacosDaMarca.ABA_PERFIL, observacao);
        int versao = gravado.versao();
        historico.registrar(versao == 1 ? AcaoHistorico.ENVIO_ARQUIVO : AcaoHistorico.NOVA_VERSAO, usuario, parceriaId, "arquivo",
                gravado.id(), versao == 1 ? null : "versão " + (versao - 1), "versão " + versao, detalhe, requisicao);
        return paraDto(gravado, usuario);
    }

    // ---------------------------------------------------------------------
    // Rota 71: os espaços
    // ---------------------------------------------------------------------

    /**
     * Rota 71. Sem {@code aba}, a marca precisa ter o Perfil (formulário e solicitações) E Aplicações (MIV e logo) abertos, senão 403;
     * com {@code aba}, só aquela, e a resposta traz só a parte dela (as outras vêm vazias ou nulas). A RBS vê tudo.
     */
    @Transactional(readOnly = true)
    public EspacosDto espacos(UsuarioAutenticado usuario, long parceriaId, String abaPedida) {
        verificador.exigirParceria(usuario, parceriaId);
        boolean comPerfil = abaPedida == null || EspacosDaMarca.ABA_PERFIL.equals(abaPedida);
        boolean comAplicacoes = abaPedida == null || EspacosDaMarca.ABA_APLICACOES.equals(abaPedida);
        if (comPerfil) {
            verificador.exigirAbaLiberada(usuario, parceriaId, EspacosDaMarca.ABA_PERFIL);
        }
        if (comAplicacoes) {
            verificador.exigirAbaLiberada(usuario, parceriaId, EspacosDaMarca.ABA_APLICACOES);
        }
        List<ArquivoDto> formulario = List.of();
        List<EntregaDto> solicitacoes = List.of();
        EntregaDto miv = null;
        EntregaDto logo = null;
        if (comPerfil) {
            formulario = repositorio.versoesAvulsas(parceriaId, CATEGORIA_FORMULARIO);
            solicitacoes = entregaService.montar(entregas.daParceria(parceriaId, EspacosDaMarca.ABA_PERFIL, null, false).stream()
                    .filter(e -> EspacosDaMarca.DOCUMENTO_INGRESSOS.equals(e.papel())).toList());
        }
        if (comAplicacoes) {
            List<EntregaDto> unicas = entregaService.montar(entregas.daParceria(parceriaId, EspacosDaMarca.ABA_APLICACOES, null, false)
                    .stream().filter(e -> e.papel() != null && EspacosDaMarca.UNICOS.contains(e.papel())).toList());
            for (EntregaDto unica : unicas) {
                if (EspacosDaMarca.MIV.equals(unica.papel())) {
                    miv = unica;
                } else {
                    logo = unica;
                }
            }
        }
        return new EspacosDto(parceriaId, new FormularioDeIngressosDto(formulario), solicitacoes, miv, logo);
    }

    // ---------------------------------------------------------------------
    // Rota 72: enviar para um espaço
    // ---------------------------------------------------------------------

    /**
     * Rota 72. Papel, aba e categoria são fixos (a categoria do corpo é ignorada). Ordem das respostas: 404 (parceria de outra marca ou
     * papel que não existe), 403 (aba fechada), 409 (parceria encerrada), 422 (arquivo ausente ou vazio, observação longa), 415 e 413
     * (formato e tamanho). MIV e logo: o primeiro envio cria a entrega, os seguintes são versões (a marca devolve a entrega a ENVIADO em
     * qualquer status). Documento de ingressos: cada envio cria uma solicitação nova, em ENVIADO.
     */
    @Transactional
    public ArquivoEnviadoDto enviarParaEspaco(UsuarioAutenticado usuario, long parceriaId, String papel, MultipartFile arquivo,
                                              String observacaoInformada, HttpServletRequest requisicao) {
        verificador.exigirParceria(usuario, parceriaId);
        if (!EspacosDaMarca.papelValido(papel)) {
            throw new NaoEncontradoException("Não encontramos este espaço.");
        }
        String abaCodigo = EspacosDaMarca.abaDoPapel(papel);
        verificador.exigirAbaLiberada(usuario, parceriaId, abaCodigo);
        boolean solicitacaoNova = EspacosDaMarca.DOCUMENTO_INGRESSOS.equals(papel);
        // A solicitação de ingressos é numerada ("Solicitação de ingressos N"): a parceria fica travada até o fim do envio.
        exigirParceriaAberta(parceriaId, solicitacaoNova);
        String observacao = conferirCampos(arquivo, observacaoInformada);
        if (!solicitacaoNova) {
            conferirFormatoDaMarca(arquivo.getOriginalFilename());
        }
        byte[] bytes = bytesDe(arquivo);
        int abaId = entregas.abaId(abaCodigo).orElseThrow();
        String categoria = EspacosDaMarca.categoriaDoPapel(papel);

        long entregaId;
        String statusAntes;
        if (solicitacaoNova) {
            String titulo = "Solicitação de ingressos " + (repositorio.contarEntregasDoPapel(parceriaId, papel) + 1);
            entregaId = entregas.inserirComPapel(parceriaId, abaId, papel, titulo, null, "ENVIADO", null).orElseThrow();
            historico.registrar(AcaoHistorico.CRIACAO, usuario, parceriaId, "entrega", entregaId, null, "ENVIADO",
                    titulo + " (aba " + abaCodigo + ")", requisicao);
            statusAntes = "ENVIADO";
        } else {
            // O primeiro envio cria a entrega; dois primeiros envios juntos: o segundo não cria e relê a linha com FOR UPDATE.
            boolean criou = repositorio.criarEspacoUnico(parceriaId, abaId, papel, EspacosDaMarca.tituloDoPapel(papel));
            entregaId = repositorio.travarEspacoUnico(parceriaId, papel).orElseThrow();
            if (criou) {
                historico.registrar(AcaoHistorico.CRIACAO, usuario, parceriaId, "entrega", entregaId, null, "AGUARDANDO_ENVIO",
                        EspacosDaMarca.tituloDoPapel(papel) + " (aba " + abaCodigo + ")", requisicao);
            }
            statusAntes = entregas.buscar(entregaId, false).orElseThrow().status();
        }

        var gravado = armazenamento.gravarNaEntrega(bytes, arquivo.getOriginalFilename(), categoria, usuario.id(), entregaId,
                observacao);
        int versao = gravado.versao();
        String novo = gravado.nomeOriginal() + " (versão " + versao + ")";
        historico.registrar(versao == 1 ? AcaoHistorico.ENVIO_ARQUIVO : AcaoHistorico.NOVA_VERSAO, usuario, parceriaId, "entrega",
                entregaId, versao == 1 ? null : "versão " + (versao - 1), novo, observacao, requisicao);

        // A marca (ou o envio que abre uma solicitação) põe a entrega em ENVIADO; o retorno da RBS num espaço que já existe não mexe no status.
        String status = statusAntes;
        if (!usuario.ehRbs() && !"ENVIADO".equals(statusAntes)) {
            entregas.atualizarStatus(entregaId, "ENVIADO");
            historico.registrar(AcaoHistorico.ALTERACAO_STATUS, usuario, parceriaId, "entrega", entregaId, statusAntes, "ENVIADO",
                    "envio automático: " + novo, requisicao);
            status = "ENVIADO";
        } else {
            entregas.marcarComoAtualizada(entregaId);
        }
        return new ArquivoEnviadoDto(gravado.id(), entregaId, gravado.nomeOriginal(), gravado.mimeType(), gravado.tamanhoBytes(), versao,
                gravado.categoria(), new UsuarioResumoDto(usuario.id(), usuario.nome(), usuario.perfil().name()),
                Datas.emBrasilia(gravado.enviadoEm()), gravado.observacao(), status);
    }

    // ---------------------------------------------------------------------
    // Apoios
    // ---------------------------------------------------------------------

    /** A parceria encerrada recusa escrita. {@code travar} = FOR UPDATE da linha da parceria (versão e número em série). */
    private void exigirParceriaAberta(long parceriaId, boolean travar) {
        String situacao = repositorio.situacaoDaParceria(parceriaId, travar)
                .orElseThrow(() -> new NaoEncontradoException("Não encontramos esta parceria."));
        if ("ENCERRADA".equals(situacao)) {
            throw new ConflitoException(FRASE_DA_PARCERIA_ENCERRADA);
        }
    }

    /** [VALIDACAO] Junta os erros de campo (arquivo ausente ou vazio, observação longa) numa resposta só; devolve a observação limpa. */
    private static String conferirCampos(MultipartFile arquivo, String observacaoInformada) {
        Map<String, String> campos = new LinkedHashMap<>();
        if (arquivo == null || arquivo.isEmpty()) {
            campos.put("arquivo", "Envie o arquivo na parte \"arquivo\" do formulário.");
        }
        String observacao = observacaoInformada == null || observacaoInformada.isBlank() ? null : observacaoInformada.trim();
        if (observacao != null && observacao.length() > LIMITE_OBSERVACAO) {
            campos.put("observacao", "A observação tem no máximo " + LIMITE_OBSERVACAO + " caracteres.");
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }
        return observacao;
    }

    /** [UPLOAD] MIV e logo: PNG, JPG, PDF ou ZIP. O Armazenamento ainda confere os bytes de cada um. */
    private static void conferirFormatoDaMarca(String nomeOriginal) {
        boolean aceito = TipoDeArquivo.daExtensao(nomeOriginal).filter(FORMATOS_DA_MARCA::contains).isPresent();
        if (!aceito) {
            throw new ArquivoRecusadoException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, FRASE_DOS_FORMATOS);
        }
    }

    private static ArquivoDto paraDto(Arquivo gravado, UsuarioAutenticado usuario) {
        return new ArquivoDto(gravado.id(), gravado.entregaId(), gravado.nomeOriginal(), gravado.mimeType(), gravado.tamanhoBytes(),
                gravado.versao(), gravado.categoria(), new UsuarioResumoDto(usuario.id(), usuario.nome(), usuario.perfil().name()),
                Datas.emBrasilia(gravado.enviadoEm()), gravado.observacao());
    }

    private static byte[] bytesDe(MultipartFile arquivo) {
        try {
            return arquivo.getBytes();
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }
    }
}
