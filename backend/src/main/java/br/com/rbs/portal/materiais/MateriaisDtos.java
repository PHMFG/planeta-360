package br.com.rbs.portal.materiais;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Corpos de entrada e saída das rotas 14 a 22 (entregas, arquivos e histórico), todos em record. Atende o item 4 da Entrega 2.
 * O formato é o contrato com o front (docs/api.md): camelCase, datas com fuso de Brasília, listas sempre como lista.
 */
public final class MateriaisDtos {

    /** Os códigos das 7 abas, na ordem do menu (tabela aba). */
    public static final String PADRAO_ABA = "PERFIL|APLICACOES|MIDIA|CORTESIAS|CREDENCIAMENTO|PESQUISA|CONTRATO";
    public static final String PADRAO_STATUS = "NAO_INICIADO|AGUARDANDO_ENVIO|ENVIADO|EM_ANALISE|AJUSTE_SOLICITADO|APROVADO|NAO_APLICAVEL";
    public static final String PADRAO_PAPEL = "DOCUMENTO_INGRESSOS|MIV|LOGO";
    public static final String PADRAO_STATUS_INICIAL = "NAO_INICIADO|AGUARDANDO_ENVIO|NAO_APLICAVEL";

    private MateriaisDtos() {
    }

    /** Quem fez algo, como no resto da API: id, nome e perfil. */
    public record UsuarioResumoDto(Long id, String nome, String perfil) {
    }

    /**
     * Uma versão de um arquivo da entrega (objeto "Arquivo" do contrato). {@code observacao} é o recado que quem enviou
     * escreveu junto com esta versão (nulo quando não escreveu nada).
     */
    public record ArquivoDto(Long id, Long entregaId, String nomeOriginal, String mimeType, Long tamanhoBytes, Integer versao,
                             String categoria, UsuarioResumoDto enviadoPor, OffsetDateTime enviadoEm, String observacao) {
    }

    /**
     * Uma versão da entrega, de arquivo ou de conteúdo (R5.3), na lista única {@code versoes}: {@code tipo} é ARQUIVO, TEXTO ou LINK.
     * Os campos que não valem para o tipo vão nulos: nomeOriginal, mimeType, tamanhoBytes e categoria são do arquivo; titulo, texto, url
     * e host, do conteúdo ({@code host} é o domínio do link, sem o "www."). {@code versao} segue uma sequência só por entrega.
     */
    public record VersaoDto(String tipo, Long id, Integer versao, UsuarioResumoDto enviadoPor, OffsetDateTime enviadoEm,
                            String observacao, String nomeOriginal, String mimeType, Long tamanhoBytes, String categoria,
                            String titulo, String texto, String url, String host) {
    }

    /** Corpo da rota 86. Os campos são conferidos no ConteudoDaEntregaService, para juntar todos os erros numa resposta só. */
    public record ConteudoRequisicao(String tipo, String titulo, String texto, String url, String observacao) {
    }

    /** A resposta da rota 86: a versão de conteúdo gravada e o status em que a entrega ficou. */
    public record ConteudoEnviadoDto(Long id, Long entregaId, String tipo, Integer versao, String titulo, String texto, String url,
                                     String host, String observacao, UsuarioResumoDto enviadoPor, OffsetDateTime enviadoEm,
                                     String statusDaEntrega) {
    }

    /**
     * Um link da rota 87. {@code origem} é CARGA (a veiculação carregada pela RBS) ou ENVIO (link enviado como conteúdo de uma
     * entrega de Mídia). Da carga vêm veiculo, formato, espacoPrograma e exibidoEm; do envio, entregaId, entregaTitulo, titulo,
     * observacao e enviadoPor. {@code data} é a data de exibição (CARGA) ou do envio (ENVIO), e ordena a lista.
     */
    public record LinkDeMidiaDto(String origem, String url, String host, OffsetDateTime data, String veiculo, String formato,
                                 String espacoPrograma, Long entregaId, String entregaTitulo, String titulo, String observacao,
                                 UsuarioResumoDto enviadoPor) {
    }

    /** A resposta da rota 87. */
    public record LinksDeMidiaDto(Long parceriaId, List<LinkDeMidiaDto> itens) {
    }

    /** A resposta da rota 17: o Arquivo mais o status em que a entrega ficou depois do envio. */
    public record ArquivoEnviadoDto(Long id, Long entregaId, String nomeOriginal, String mimeType, Long tamanhoBytes,
                                    Integer versao, String categoria, UsuarioResumoDto enviadoPor, OffsetDateTime enviadoEm,
                                    String observacao, String statusDaEntrega) {
    }

    /** O recado que a RBS escreveu na última mudança de status que tinha texto (vem do histórico). */
    public record ObservacaoDto(String texto, String por, OffsetDateTime em) {
    }

    /**
     * Objeto "Entrega" do contrato ({@code papel} é nulo na entrega comum). Os {@code arquivos} são só os arquivos; as {@code versoes}
     * juntam arquivos, textos e links numa lista única. As duas vêm da versão mais nova para a mais antiga.
     */
    public record EntregaDto(Long id, Long parceriaId, String abaCodigo, String papel, String titulo, String descricao, String status,
                             LocalDate prazo, OffsetDateTime criadoEm, OffsetDateTime atualizadoEm, List<ArquivoDto> arquivos,
                             List<VersaoDto> versoes, ObservacaoDto ultimaObservacao) {
    }

    /** [VALIDACAO] Corpo da rota 15. A aba e o status inicial só valem se forem códigos que existem. */
    public record CriarEntregaRequisicao(
            @NotBlank(message = "Informe a aba.")
            @Pattern(regexp = PADRAO_ABA, message = "Aba inválida. Use PERFIL, APLICACOES, MIDIA, CORTESIAS, CREDENCIAMENTO, PESQUISA ou CONTRATO.")
            String abaCodigo,
            @NotBlank(message = "Informe o título.")
            @Size(max = 160, message = "O título tem no máximo 160 caracteres.")
            String titulo,
            @Size(max = 2000, message = "A descrição tem no máximo 2000 caracteres.")
            String descricao,
            LocalDate prazo,
            @Pattern(regexp = PADRAO_STATUS_INICIAL, message = "O status inicial deve ser NAO_INICIADO, AGUARDANDO_ENVIO ou NAO_APLICAVEL.")
            String status,
            @Pattern(regexp = PADRAO_PAPEL, message = "Papel inválido. Use DOCUMENTO_INGRESSOS, MIV ou LOGO.")
            String papel) {
    }

    /** [VALIDACAO] Corpo da rota 19. A observação obrigatória (pedir ajuste, reabrir) é conferida no EntregaService. */
    public record MudarStatusRequisicao(
            @NotBlank(message = "Informe o novo status.")
            @Pattern(regexp = PADRAO_STATUS, message = "Status inválido.")
            String status,
            @Size(max = 500, message = "A observação tem no máximo 500 caracteres.")
            String observacao) {
    }

    /** A parceria de uma linha da fila de entregas (rota 16); {@code cota} é o código (MASTER, SUBCOTA_G, SUBCOTA). */
    public record ParceriaDaFilaDto(Long id, String patrocinador, String cota) {
    }

    /** Uma linha da fila de trabalho da RBS (rota 16). */
    public record ItemDaFilaDto(Long id, ParceriaDaFilaDto parceria, String abaCodigo, String papel, String titulo, String status,
                                LocalDate prazo, boolean atrasada, OffsetDateTime atualizadoEm, OffsetDateTime ultimoEnvioEm) {
    }

    /** Uma página de resultados: {@code {"itens": [...], "pagina": 0, "tamanho": 50, "total": 137}}. */
    public record PaginaDto<T>(List<T> itens, int pagina, int tamanho, long total) {
    }

    /** Uma linha do histórico da parceria (rota 20). {@code ip} e {@code userAgent} vão nulos para o patrocinador. */
    public record HistoricoDto(Long id, String acao, String entidade, Long entidadeId, String valorAnterior, String valorNovo,
                               String detalhe, UsuarioResumoDto usuario, OffsetDateTime ocorridoEm, String ip, String userAgent) {
    }

    /** A parceria de uma linha do histórico geral (rota 21). */
    public record ParceriaDoHistoricoDto(Long id, String patrocinador) {
    }

    /** Uma linha do histórico geral (rota 21): como a da rota 20, mais a parceria (nula em login, logout e falha de login). */
    public record HistoricoGeralDto(Long id, ParceriaDoHistoricoDto parceria, String acao, String entidade, Long entidadeId,
                                    String valorAnterior, String valorNovo, String detalhe, UsuarioResumoDto usuario,
                                    OffsetDateTime ocorridoEm, String ip, String userAgent) {
    }
}
