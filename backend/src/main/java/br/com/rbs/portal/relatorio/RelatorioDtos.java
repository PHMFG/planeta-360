package br.com.rbs.portal.relatorio;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Corpos de entrada e saída das rotas de relatório (31 a 36), todos em record. Atende o item 6 da Entrega 2.
 * O formato é o contrato com o front (veja docs/api.md): camelCase, datas com fuso de Brasília, listas sempre como lista.
 */
public final class RelatorioDtos {

    private RelatorioDtos() {
    }

    public record CotaDto(String codigo, String nome) {
    }

    public record EventoDto(Long id, String nome, Integer edicao) {
    }

    /** Quem fez algo, como no resto da API: id, nome e perfil. */
    public record UsuarioResumoDto(Long id, String nome, String perfil) {
    }

    /** A versão mais nova do relatório de uma parceria, com a contagem de números disponíveis e indisponíveis. */
    public record ResumoDoRelatorioDto(Long id, Integer versao, String status, OffsetDateTime geradoEm,
                                       OffsetDateTime validadoEm, OffsetDateTime disponibilizadoEm,
                                       int indicadoresDisponiveis, int indicadoresIndisponiveis) {
    }

    /** A versão que a marca vê: a disponibilizada mais nova, com a data em que a RBS a liberou. */
    public record DisponibilizadaDto(Long id, Integer versao, OffsetDateTime disponibilizadoEm) {
    }

    /**
     * Uma linha da rota 31: a situação do relatório de uma parceria. {@code situacao} é SEM_RELATORIO, RASCUNHO, VALIDADO ou
     * DISPONIBILIZADO (a da versão mais nova); {@code relatorio} é nulo quando não há versão; {@code disponibilizada} é a
     * versão disponibilizada mais nova (a que a marca vê, mesmo quando a mais nova é um rascunho) ou nulo se nenhuma foi
     * disponibilizada; {@code faltantes} vem de vw_faltantes.
     */
    public record SituacaoDto(Long parceriaId, String patrocinador, CotaDto cota, EventoDto evento, String situacao,
                              ResumoDoRelatorioDto relatorio, DisponibilizadaDto disponibilizada, int faltantes) {
    }

    /**
     * Um número do relatório. [RELATORIO] {@code valor} é nulo quando {@code disponivel} é falso: número sem dado na base
     * nunca vira zero nem é estimado.
     */
    public record IndicadorDto(String codigo, String nome, String recorte, BigDecimal valor, String unidade,
                               String baseCalculo, boolean disponivel) {
    }

    /** Os indicadores de uma seção do catálogo (por exemplo, "Cumprimento das entregas"). */
    public record SecaoDto(String secao, List<IndicadorDto> indicadores) {
    }

    /** O relatório completo (rotas 33, 34 e 35). {@code geradoPor} vai nulo para o patrocinador: é dado interno da RBS. */
    public record RelatorioDto(Long id, Long parceriaId, Integer versao, String status, OffsetDateTime geradoEm,
                               UsuarioResumoDto geradoPor, OffsetDateTime validadoEm, OffsetDateTime disponibilizadoEm,
                               List<SecaoDto> secoes) {
    }

    /** [VALIDACAO] Corpo da rota 34. A ordem das etapas quem confere é o banco; aqui só se vê se o nome do status existe. */
    public record MudarStatusRequisicao(
            @NotBlank(message = "Informe o status.")
            @Pattern(regexp = "RASCUNHO|VALIDADO|DISPONIBILIZADO",
                    message = "Status inválido. Use VALIDADO ou DISPONIBILIZADO.")
            String status) {

        /** Tira os espaços das pontas antes da validação. */
        public MudarStatusRequisicao {
            status = status == null ? null : status.trim();
        }
    }
}
