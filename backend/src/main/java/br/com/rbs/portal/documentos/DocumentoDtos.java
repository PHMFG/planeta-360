package br.com.rbs.portal.documentos;

import br.com.rbs.portal.materiais.MateriaisDtos.UsuarioResumoDto;
import br.com.rbs.portal.parceria.ParceriaDtos.CotaDto;
import br.com.rbs.portal.parceria.ParceriaDtos.EventoDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Corpos de entrada e saída das rotas 54 a 58 (documentos do Atlas), todos em record. [ATLAS] O formato é o contrato com o front
 * (docs/api.md): camelCase, datas com fuso de Brasília, listas sempre como lista. A marca nunca recebe {@link DocumentoDto}: a rota 56
 * devolve para ela só o {@link DocumentoDaMarcaDto}.
 */
public final class DocumentoDtos {

    public static final String PADRAO_TIPO = "MANUAL_OPERACOES|PLANO_COMERCIAL|ANEXO_PARCERIA|FAQ|COMUNICADO|OUTRO";
    public static final String PADRAO_ESCOPO = "GLOBAL|COTA|PARCERIA";
    public static final String PADRAO_STATUS = "AGUARDANDO_APROVACAO|RECUSADO|PROCESSANDO|INDEXADO|ERRO|SUBSTITUIDO|REVOGADO";
    public static final String PADRAO_ACAO = "APROVAR|RECUSAR|REVOGAR|REINDEXAR";

    private DocumentoDtos() {
    }

    /** O arquivo do documento (o que a rota 18 baixa, só para a RBS). */
    public record ArquivoDoDocumentoDto(Long id, String nomeOriginal, String mimeType, Long tamanhoBytes) {
    }

    /** A parceria de um documento de escopo PARCERIA. */
    public record ParceriaDoDocumentoDto(Long id, String patrocinador) {
    }

    /**
     * O documento como a RBS vê (rotas 54, 55, 56 e 57). {@code trechos} é a contagem dos trechos gravados (0 até indexar);
     * {@code modelo} e {@code dimensoes} são os dos embeddings que o indexaram; {@code modeloDiferente} é verdadeiro quando o documento
     * está indexado com um modelo que não é o de agora (a busca o ignora: é preciso reindexar, e a aba mostra o aviso);
     * {@code extracaoMs} e {@code embeddingsMs} são os tempos da ingestão; {@code erro} é o motivo, quando o status é ERRO.
     * {@code acoes} diz o que a RBS pode fazer agora com ele (as ações da rota 57).
     */
    public record DocumentoDto(Long id, String titulo, String chave, Integer versao, String tipo, String escopo, EventoDto evento,
                               CotaDto cota, ParceriaDoDocumentoDto parceria, String status, boolean contemDadosPessoais,
                               Integer paginas, Integer trechos, String modelo, Integer dimensoes, boolean modeloDiferente,
                               Integer extracaoMs, Integer embeddingsMs, String erro, ArquivoDoDocumentoDto arquivo,
                               UsuarioResumoDto enviadoPor, OffsetDateTime enviadoEm, UsuarioResumoDto aprovadoPor,
                               OffsetDateTime aprovadoEm, UsuarioResumoDto revogadoPor, OffsetDateTime revogadoEm, List<String> acoes) {
    }

    /**
     * O documento como a marca vê (rota 56): só o que ela precisa para abrir o documento. Nunca o erro, a suspeita de dado pessoal, a
     * autoria, o escopo, a chave nem o caminho do arquivo. {@code substituido} avisa que existe uma versão mais nova.
     */
    public record DocumentoDaMarcaDto(Long id, String titulo, String tipo, Integer versao, Integer paginas, boolean substituido) {
    }

    /**
     * Um trecho inteiro para o visualizador (rota 60): a página (ou o intervalo, quando o trecho atravessa páginas), a seção e o texto
     * como foi indexado, que a tela usa para destacar o trecho na página do PDF.
     */
    public record TrechoDoVisualizadorDto(Long id, Long documentoId, Integer ordem, Integer paginaInicio, Integer paginaFim,
                                          String secao, String texto) {
    }

    /** Um trecho na lista da RBS (rota 58): onde está, de que seção é, quanto pesa e o começo do texto. */
    public record TrechoDoDocumentoDto(Long id, Integer ordem, Integer paginaInicio, Integer paginaFim, String secao, Integer tokens,
                                       boolean ativo, String inicioDoTexto) {
    }

    /** [VALIDACAO] Corpo da rota 57. */
    public record MudarStatusRequisicao(
            @NotBlank(message = "Informe a ação.")
            @Pattern(regexp = PADRAO_ACAO, message = "Ação inválida. Use APROVAR, RECUSAR, REVOGAR ou REINDEXAR.")
            String acao,
            @Size(max = 500, message = "A observação tem no máximo 500 caracteres.")
            String observacao) {
    }
}
