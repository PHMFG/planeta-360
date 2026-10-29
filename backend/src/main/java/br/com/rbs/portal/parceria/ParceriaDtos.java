package br.com.rbs.portal.parceria;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Corpos de entrada e saída das rotas de eventos, parcerias e e-mails autorizados, todos em record.
 * Atende o item 3 da Entrega 2. O formato é o contrato com o front (docs/api.md, rotas 6 a 13): camelCase, listas
 * sempre como lista. Os códigos (cota, vínculo, situação) são texto, para um valor inválido sair como 422 com "campos".
 */
public final class ParceriaDtos {

    private ParceriaDtos() {
    }

    /** O @Email do Bean Validation aceita "a@b"; o banco (CHECK em email_autorizado) exige um ponto no domínio. */
    static final String PADRAO_EMAIL = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";
    static final String PADRAO_VINCULO = "MARCA|AGENCIA";
    static final String PADRAO_BASE_VALOR = "BRUTO_TABELA|LIQUIDO";
    static final String PADRAO_SITUACAO = "EM_NEGOCIACAO|ATIVA|ENCERRADA|CANCELADA";

    // ---------------------------------------------------------------------
    // Saída: objetos que se repetem
    // ---------------------------------------------------------------------

    public record CotaDto(String codigo, String nome) {
    }

    public record EventoDto(Long id, String nome, Integer edicao) {
    }

    /** Rota 6: a edição do festival com datas e local (as de 2027 ainda são nulas). */
    public record EventoCompletoDto(Long id, String nome, Integer edicao, LocalDate dataInicio, LocalDate dataFim, String local) {
    }

    /**
     * Uma das 7 abas. liberada = liberada E visível para esta parceria (o front mostra o cadeado quando é false).
     * incluidaNaCota = a cota da parceria inclui a aba (cota_tipo_aba); false = "fora da sua cota", e a RBS não consegue liberar.
     */
    public record AbaDto(String codigo, String nome, int ordem, boolean liberada, boolean incluidaNaCota) {
    }

    /** Quem fez algo (autorizou, revogou). */
    public record UsuarioResumoDto(Long id, String nome, String perfil) {
    }

    public record PatrocinadorResumoDto(Long id, String nome) {
    }

    public record PatrocinadorDto(Long id, String nome, String segmento) {
    }

    // ---------------------------------------------------------------------
    // Saída: rota 7 (lista) e rotas 8, 9 e 10 (detalhe)
    // ---------------------------------------------------------------------

    /** pendentes = entregas fora de APROVADO e NAO_APLICAVEL. percentualConcluido é nulo se não há entrega aplicável. */
    public record EntregasResumoDto(long total, long aprovadas, long pendentes, long naoAplicaveis, BigDecimal percentualConcluido) {
    }

    public record RelatorioResumoDto(Long id, Integer versao, String status) {
    }

    /** usuarios é nulo para o patrocinador (dado interno da RBS). */
    public record ParceriaResumoDto(Long id, PatrocinadorResumoDto patrocinador, EventoDto evento, CotaDto cota,
                                    String nomeProduto, String situacao, EntregasResumoDto entregas, Integer usuarios,
                                    RelatorioResumoDto relatorio) {
    }

    /** E-mail autorizado como a rota 9 mostra: só os não revogados. situacao = ATIVO ou PENDENTE. */
    public record EmailResumoDto(Long id, String email, String vinculo, String situacao) {
    }

    /** O mesmo e-mail na resposta de POST /api/parcerias, que traz o código de ativação (só ali, uma vez). */
    public record EmailResumoComCodigoDto(Long id, String email, String vinculo, String situacao, String codigoAtivacao,
                                          OffsetDateTime codigoExpiraEm) {
    }

    /** Dados da parceria, abas e e-mails. E é EmailResumoDto no GET e EmailResumoComCodigoDto na criação. */
    public record ParceriaDetalheDto<E>(Long id, PatrocinadorDto patrocinador, EventoDto evento, CotaDto cota,
                                        String nomeProduto, BigDecimal valorInvestimento, String baseValor, String situacao,
                                        OffsetDateTime criadoEm, List<AbaDto> abas, List<E> emailsAutorizados) {
    }

    // ---------------------------------------------------------------------
    // Saída: rotas 11 e 12 (e-mails autorizados, visão da RBS)
    // ---------------------------------------------------------------------

    /** situacao: PENDENTE, ATIVO ou REVOGADO. usuarioId é nulo enquanto não houver conta. */
    public record EmailAutorizadoDto(Long id, String email, String vinculo, String situacao, Long usuarioId,
                                     UsuarioResumoDto autorizadoPor, OffsetDateTime autorizadoEm,
                                     UsuarioResumoDto revogadoPor, OffsetDateTime revogadoEm) {
    }

    /** Resposta de POST /api/parcerias/{id}/emails. codigoAtivacao é nulo quando a pessoa já tem conta (o acesso volta na hora). */
    public record EmailAutorizadoComCodigoDto(Long id, String email, String vinculo, String situacao, Long usuarioId,
                                              UsuarioResumoDto autorizadoPor, OffsetDateTime autorizadoEm,
                                              UsuarioResumoDto revogadoPor, OffsetDateTime revogadoEm,
                                              String codigoAtivacao, OffsetDateTime codigoExpiraEm) {

        static EmailAutorizadoComCodigoDto de(EmailAutorizadoDto e, String codigo, OffsetDateTime expiraEm) {
            return new EmailAutorizadoComCodigoDto(e.id(), e.email(), e.vinculo(), e.situacao(), e.usuarioId(),
                    e.autorizadoPor(), e.autorizadoEm(), e.revogadoPor(), e.revogadoEm(), codigo, expiraEm);
        }
    }

    // ---------------------------------------------------------------------
    // [VALIDACAO] Entrada: rota 8 (criar)
    // ---------------------------------------------------------------------

    /** Existente: só o id. Novo: nome (e segmento, opcional). A API confere que veio um dos dois. */
    public record PatrocinadorRequisicao(
            Long id,
            @Size(max = 120, message = "O nome tem no máximo 120 caracteres.") String nome,
            @Size(max = 80, message = "O segmento tem no máximo 80 caracteres.") String segmento) {
    }

    public record EmailNovoRequisicao(
            @NotBlank(message = "Informe o e-mail.")
            @Email(message = "E-mail inválido.")
            @Pattern(regexp = PADRAO_EMAIL, message = "E-mail inválido.")
            @Size(max = 254, message = "O e-mail tem no máximo 254 caracteres.")
            String email,
            @NotBlank(message = "Informe o vínculo: MARCA ou AGENCIA.")
            @Pattern(regexp = PADRAO_VINCULO, message = "O vínculo deve ser MARCA ou AGENCIA.")
            String vinculo) {

        /** Espaços nas pontas saem antes da validação (quem copia e cola costuma levar um junto). */
        public EmailNovoRequisicao {
            email = email == null ? null : email.trim();
            vinculo = vinculo == null ? null : vinculo.trim();
        }
    }

    public record CriarParceriaRequisicao(
            @NotNull(message = "Informe o patrocinador.") @Valid PatrocinadorRequisicao patrocinador,
            @NotNull(message = "Informe o evento.") Long eventoId,
            @NotBlank(message = "Informe a cota.") String cotaCodigo,
            @Size(max = 120, message = "O nome do produto tem no máximo 120 caracteres.") String nomeProduto,
            @DecimalMin(value = "0", message = "O valor não pode ser negativo.")
            @Digits(integer = 12, fraction = 2, message = "Use até 12 dígitos antes da vírgula e 2 casas decimais.")
            BigDecimal valorInvestimento,
            @Pattern(regexp = PADRAO_BASE_VALOR, message = "A base do valor deve ser BRUTO_TABELA ou LIQUIDO.")
            String baseValor,
            @Size(max = 50, message = "Autorize até 50 e-mails por vez.") @Valid List<EmailNovoRequisicao> emails) {
    }

    // ---------------------------------------------------------------------
    // [VALIDACAO] Entrada: rota 10 (editar). Todos os campos são opcionais; nulo = "não muda".
    // ---------------------------------------------------------------------

    public record PatrocinadorEdicaoRequisicao(
            @Size(max = 120, message = "O nome tem no máximo 120 caracteres.") String nome,
            @Size(max = 80, message = "O segmento tem no máximo 80 caracteres.") String segmento) {
    }

    public record AbaEdicaoRequisicao(
            @NotBlank(message = "Informe o código da aba.") String codigo,
            Boolean liberada,
            Boolean visivel) {
    }

    public record EditarParceriaRequisicao(
            @Valid PatrocinadorEdicaoRequisicao patrocinador,
            @Size(max = 120, message = "O nome do produto tem no máximo 120 caracteres.") String nomeProduto,
            @DecimalMin(value = "0", message = "O valor não pode ser negativo.")
            @Digits(integer = 12, fraction = 2, message = "Use até 12 dígitos antes da vírgula e 2 casas decimais.")
            BigDecimal valorInvestimento,
            @Pattern(regexp = PADRAO_BASE_VALOR, message = "A base do valor deve ser BRUTO_TABELA ou LIQUIDO.")
            String baseValor,
            @Pattern(regexp = PADRAO_SITUACAO, message = "A situação deve ser EM_NEGOCIACAO, ATIVA, ENCERRADA ou CANCELADA.")
            String situacao,
            @Valid List<AbaEdicaoRequisicao> abas) {
    }

    // ---------------------------------------------------------------------
    // [VALIDACAO] Entrada: rota 12 (autorizar e-mail)
    // ---------------------------------------------------------------------

    public record AutorizarEmailRequisicao(
            @NotBlank(message = "Informe o e-mail.")
            @Email(message = "E-mail inválido.")
            @Pattern(regexp = PADRAO_EMAIL, message = "E-mail inválido.")
            @Size(max = 254, message = "O e-mail tem no máximo 254 caracteres.")
            String email,
            @NotBlank(message = "Informe o vínculo: MARCA ou AGENCIA.")
            @Pattern(regexp = PADRAO_VINCULO, message = "O vínculo deve ser MARCA ou AGENCIA.")
            String vinculo) {

        public AutorizarEmailRequisicao {
            email = email == null ? null : email.trim();
            vinculo = vinculo == null ? null : vinculo.trim();
        }
    }
}
