package br.com.rbs.portal.materiais;

import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.Armazenamento;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.SemPermissaoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.materiais.ArquivoRepositorio.ArquivoParaDownload;
import br.com.rbs.portal.materiais.EntregaRepositorio.EntregaBase;
import br.com.rbs.portal.materiais.MateriaisDtos.ArquivoEnviadoDto;
import br.com.rbs.portal.materiais.MateriaisDtos.UsuarioResumoDto;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Envio e download de arquivos das entregas (rotas 17 e 18). Atende o item 4 da Entrega 2: cada envio guarda autor, data e
 * versão, e o histórico registra o envio. [UPLOAD] [ISOLAMENTO] [PERMISSAO] [HISTORICO] O tamanho, a extensão e o conteúdo
 * são conferidos pelo Armazenamento; aqui ficam as regras do negócio: quem envia, em que status, com que categoria.
 */
@Service
public class ArquivoService {

    /**
     * Categorias que o patrocinador pode usar (as demais do banco são só da RBS). O CONTRATO saiu da lista (R5.7.b): o contrato
     * assinado é da RBS e entra pela rota 76, que também aponta o contrato da parceria para ele.
     */
    static final Set<String> CATEGORIAS_DO_PATROCINADOR = Set.of("MATERIAL_MARCA", "MATERIAL_MIDIA", "DOCUMENTO_INGRESSOS", "OUTRO");

    /**
     * As categorias que a rota 17 aceita: as do patrocinador e as só da RBS. O CHECK ck_arquivo_categoria tem mais uma, a DOCUMENTO,
     * que é só dos documentos do Atlas (rota 54): fica de fora de propósito, e a rota 17 a recusa como categoria inválida.
     */
    static final Set<String> CATEGORIAS = Set.of("PLANO_COMERCIAL", "MATERIAL_MARCA", "MATERIAL_MIDIA", "RELATORIO_VEICULACAO",
            "COMPROVACAO", "PESQUISA", "CONTRATO", "DOCUMENTO_INGRESSOS", "RELATORIO_PARCERIA", "OUTRO");

    /** Status em que o patrocinador ainda pode enviar. Depois de EM_ANALISE, APROVADO ou NAO_APLICAVEL, 409. */
    private static final Set<String> ACEITAM_ENVIO = Set.of("NAO_INICIADO", "AGUARDANDO_ENVIO", "AJUSTE_SOLICITADO", "ENVIADO");

    private static final int LIMITE_OBSERVACAO = 500;

    /** O que o controller precisa para devolver o download: onde está o arquivo e como apresentá-lo. */
    public record ArquivoBaixado(Path caminho, String nomeOriginal, String mimeType, long tamanhoBytes) {
    }

    private final AcessoAEntregas acesso;
    private final EntregaRepositorio entregas;
    private final ArquivoRepositorio arquivos;
    private final Armazenamento armazenamento;
    private final VerificadorDeAcesso verificador;
    private final HistoricoService historico;

    public ArquivoService(AcessoAEntregas acesso, EntregaRepositorio entregas, ArquivoRepositorio arquivos,
                          Armazenamento armazenamento, VerificadorDeAcesso verificador, HistoricoService historico) {
        this.acesso = acesso;
        this.entregas = entregas;
        this.arquivos = arquivos;
        this.armazenamento = armazenamento;
        this.verificador = verificador;
        this.historico = historico;
    }

    /**
     * Rota 17. Ordem das respostas: 404 (entrega de outra parceria ou que não existe), 403 (aba fechada), 422 (parte
     * "arquivo" ausente ou vazia, categoria ou observação inválida), 403 (categoria só da RBS), 409 (status que não aceita
     * envio), 413/415 (tamanho, extensão ou conteúdo, conferidos pelo Armazenamento).
     * [HISTORICO] ENVIO_ARQUIVO no primeiro envio, NOVA_VERSAO nos seguintes e ALTERACAO_STATUS quando o status muda,
     * tudo na mesma transação do arquivo: se algo falhar, nem o registro nem o arquivo em disco ficam. A observação
     * fica ligada à versão (aparece na lista de versões da entrega) e também no detalhe do histórico.
     */
    @Transactional
    public ArquivoEnviadoDto enviar(UsuarioAutenticado usuario, long entregaId, MultipartFile arquivo, String categoriaInformada,
                                    String observacaoInformada, HttpServletRequest requisicao) {
        EntregaBase entrega = acesso.exigir(usuario, entregaId, true);
        acesso.exigirAbaLiberada(usuario, entrega);

        // [VALIDACAO] Junta os erros de campo numa resposta só.
        Map<String, String> campos = new LinkedHashMap<>();
        if (arquivo == null || arquivo.isEmpty()) {
            campos.put("arquivo", "Envie o arquivo na parte \"arquivo\" do formulário.");
        }
        String categoria = categoriaInformada == null || categoriaInformada.isBlank()
                ? (usuario.ehRbs() ? "OUTRO" : "MATERIAL_MARCA") : categoriaInformada.trim();
        if (!CATEGORIAS.contains(categoria)) {
            // Só as categorias que ESTE perfil pode usar: a marca não precisa saber que existem as que são só da RBS.
            Set<String> doPerfil = usuario.ehRbs() ? CATEGORIAS : CATEGORIAS_DO_PATROCINADOR;
            campos.put("categoria", "Categoria inválida. Use uma destas: " + String.join(", ", new TreeSet<>(doPerfil)) + ".");
        }
        String observacao = observacaoInformada == null || observacaoInformada.isBlank() ? null : observacaoInformada.trim();
        if (observacao != null && observacao.length() > LIMITE_OBSERVACAO) {
            campos.put("observacao", "A observação tem no máximo " + LIMITE_OBSERVACAO + " caracteres.");
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }

        // [PERMISSAO] Categorias da RBS (plano comercial, comprovação, relatório...) o patrocinador não usa.
        if (!usuario.ehRbs() && !CATEGORIAS_DO_PATROCINADOR.contains(categoria)) {
            throw new SemPermissaoException("A categoria " + categoria + " é só da RBS. Use "
                    + String.join(", ", new TreeSet<>(CATEGORIAS_DO_PATROCINADOR)) + ".");
        }
        // O retorno da RBS vale em qualquer status; o envio do patrocinador, só enquanto a entrega espera material.
        if (!usuario.ehRbs() && !ACEITAM_ENVIO.contains(entrega.status())) {
            throw new ConflitoException(frasePorStatus(entrega.status()));
        }

        var gravado = armazenamento.gravarNaEntrega(bytesDe(arquivo), arquivo.getOriginalFilename(), categoria, usuario.id(),
                entregaId, observacao);

        int versao = gravado.versao();
        String novo = gravado.nomeOriginal() + " (versão " + versao + ")";
        historico.registrar(versao == 1 ? AcaoHistorico.ENVIO_ARQUIVO : AcaoHistorico.NOVA_VERSAO, usuario, entrega.parceriaId(),
                "entrega", entregaId, versao == 1 ? null : "versão " + (versao - 1), novo, observacao, requisicao);

        // O envio do patrocinador põe a entrega em ENVIADO; o retorno da RBS não mexe no status.
        String status = entrega.status();
        if (!usuario.ehRbs() && !"ENVIADO".equals(status)) {
            entregas.atualizarStatus(entregaId, "ENVIADO");
            historico.registrar(AcaoHistorico.ALTERACAO_STATUS, usuario, entrega.parceriaId(), "entrega", entregaId, status,
                    "ENVIADO", "envio automático: " + novo, requisicao);
            status = "ENVIADO";
        } else {
            entregas.marcarComoAtualizada(entregaId);
        }

        return new ArquivoEnviadoDto(gravado.id(), entregaId, gravado.nomeOriginal(), gravado.mimeType(), gravado.tamanhoBytes(),
                versao, gravado.categoria(), new UsuarioResumoDto(usuario.id(), usuario.nome(), usuario.perfil().name()),
                Datas.emBrasilia(gravado.enviadoEm()), gravado.observacao(), status);
    }

    /**
     * Rota 18. [ISOLAMENTO] Arquivo que não existe, de outra parceria ou geral da RBS (sem parceria) dá 404 ao patrocinador,
     * sempre com a mesma frase. [PERMISSAO] Arquivo de uma aba fechada para a parceria dá 403 ao patrocinador. O arquivo
     * que sumiu do armazenamento dá 404. [HISTORICO] DOWNLOAD só quando o arquivo existe e vai ser entregue.
     */
    @Transactional
    public ArquivoBaixado baixar(UsuarioAutenticado usuario, long arquivoId, HttpServletRequest requisicao) {
        ArquivoParaDownload arquivo = arquivos.buscar(arquivoId).orElseThrow(ArquivoService::naoEncontrado);
        // [ISOLAMENTO] O arquivo de um documento do Atlas (M3) não sai pela rota 18 para a marca, nem o do anexo da própria parceria:
        // um documento recusado, não aprovado ou revogado (como o D11, com CPFs) sairia por aqui. A marca só lê documento pelo
        // visualizador, que confere o status e o escopo.
        if (arquivo.documentoId() != null && !usuario.ehRbs()) {
            throw naoEncontrado();
        }
        if (arquivo.parceriaId() == null) {
            if (!usuario.ehRbs()) {
                throw naoEncontrado();
            }
        } else {
            try {
                verificador.exigirParceria(usuario, arquivo.parceriaId());
            } catch (NaoEncontradoException deOutraParceria) {
                throw naoEncontrado();
            }
        }
        // [RELATORIO] O PDF de um relatório só chega ao patrocinador depois de disponibilizado.
        if (!usuario.ehRbs() && arquivo.relatorioNaoDisponibilizado()) {
            throw naoEncontrado();
        }
        // [PERMISSAO] O arquivo de uma aba fechada não sai pelo download, como a entrega dele não sai pela lista: 403 com a
        // mesma regra e as mesmas frases (cota que não inclui a aba, ou aba que a RBS ainda não liberou). A RBS baixa tudo.
        if (arquivo.parceriaId() != null && arquivo.abaCodigo() != null) {
            verificador.exigirAbaLiberada(usuario, arquivo.parceriaId(), arquivo.abaCodigo());
        }
        Path caminho = armazenamento.localizar(arquivo.caminho()).orElseThrow(
                () -> new NaoEncontradoException("Este arquivo não está mais disponível. Avise a equipe da RBS."));
        long tamanho;
        try {
            tamanho = Files.size(caminho);
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }

        // O download de um arquivo de documento fica na entidade documento: a marca não vê essas linhas na rota 20.
        if (arquivo.documentoId() != null) {
            historico.registrar(AcaoHistorico.DOWNLOAD, usuario, arquivo.parceriaId(), "documento", arquivo.documentoId(), null,
                    arquivo.nomeOriginal(), null, requisicao);
        } else {
            historico.registrar(AcaoHistorico.DOWNLOAD, usuario, arquivo.parceriaId(), "arquivo", arquivoId, null,
                    arquivo.nomeOriginal() + " (versão " + arquivo.versao() + ")", null, requisicao);
        }
        return new ArquivoBaixado(caminho, arquivo.nomeOriginal(), arquivo.mimeType(), tamanho);
    }

    private static NaoEncontradoException naoEncontrado() {
        return new NaoEncontradoException("Não encontramos este arquivo.");
    }

    private static String frasePorStatus(String status) {
        return switch (status) {
            case "EM_ANALISE" -> "Esta entrega está em análise pela RBS e não aceita novos arquivos agora.";
            case "APROVADO" -> "Esta entrega já foi aprovada e não aceita novos arquivos.";
            case "NAO_APLICAVEL" -> "Esta entrega foi marcada como não aplicável e não aceita arquivos.";
            default -> "Esta entrega não aceita novos arquivos no status " + status + ".";
        };
    }

    private static byte[] bytesDe(MultipartFile arquivo) {
        try {
            return arquivo.getBytes();
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }
    }
}
