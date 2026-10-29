package br.com.rbs.portal.consolidacao;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Objetos de saída das rotas 29 (consolidação da parceria) e 30 (painel da RBS), todos em record. Atende os itens 5 e 7
 * da Entrega 2. O formato é o contrato com o front (docs/api.md). [RELATORIO] Unidades de mídia (INSERCAO, CPM,
 * APLICACAO) nunca são somadas entre si: cada total traz a sua unidade. Seção sem dado vem com listas vazias e totais nulos.
 */
public final class ConsolidacaoDtos {

    private ConsolidacaoDtos() {
    }

    // ---------------------------------------------------------------------
    // Rota 29: consolidação da parceria
    // ---------------------------------------------------------------------

    /** Previsto x exibido de uma unidade (todas as linhas do plano na mesma unidade). {@code percentual} = exibido ÷ previsto × 100. */
    public record TotalPorUnidadeDto(String unidade, BigDecimal previsto, BigDecimal exibido, BigDecimal percentual) {
    }

    public record TotalPorVeiculoDto(String veiculo, String unidade, BigDecimal previsto, BigDecimal exibido,
                                     BigDecimal percentual) {
    }

    /** Uma linha do plano comercial de mídia, com o que foi exibido nela. */
    public record LinhaDoPlanoDto(Long aproveitamentoId, String veiculo, String formato, String espacoPrograma,
                                  String unidade, BigDecimal previsto, BigDecimal exibido, BigDecimal percentual) {
    }

    public record MidiaDto(List<TotalPorUnidadeDto> porUnidade, List<TotalPorVeiculoDto> porVeiculo,
                           List<LinhaDoPlanoDto> linhas) {
    }

    /** Aplicações de marca no evento. Tudo nulo quando o plano comercial não tem aplicação. */
    public record AplicacoesDto(BigDecimal previstas, BigDecimal comprovadas, Long fotos, BigDecimal percentual) {
    }

    /** Entregas da parceria. Tudo nulo quando ainda não há entrega cadastrada. */
    public record EntregasDto(Long total, Long aprovadas, Long pendentes, Long naoAplicaveis,
                              BigDecimal percentualConcluido) {
    }

    /** Cortesias da cota. Tudo nulo quando a cota não tem cortesia cadastrada. */
    public record CortesiasDto(Long disponiveis, Long destinadas, BigDecimal percentual) {
    }

    /** Um item de vw_faltantes: o que impede ou enfraquece o relatório. */
    public record FaltanteDto(String tipo, String detalhe) {
    }

    public record ConsolidacaoDto(Long parceriaId, MidiaDto midia, AplicacoesDto aplicacoes, EntregasDto entregas,
                                  CortesiasDto cortesias, List<FaltanteDto> faltantes) {
    }

    // ---------------------------------------------------------------------
    // Rota 30: painel da RBS
    // ---------------------------------------------------------------------

    public record EventoDto(Long id, String nome, Integer edicao) {
    }

    public record ParceriasDoPainelDto(long total, long ativas, long semUsuario) {
    }

    /** {@code porStatus} traz sempre os 7 status, com zero nos que não têm entrega. */
    public record EntregasDoPainelDto(Map<String, Long> porStatus, long esperandoAnalise, long atrasadas) {
    }

    /**
     * A situação da versão mais nova do relatório de cada parceria da edição. {@code marcaVe} é outra conta: as parcerias
     * com pelo menos uma versão disponibilizada, mesmo que a mais nova seja rascunho ou validada (a marca vê a anterior).
     */
    public record RelatoriosDoPainelDto(long semRelatorio, long rascunho, long validado, long disponibilizado, long marcaVe) {
    }

    /** {@code tipo} usa o nome da API: o MIDIA do banco sai como VEICULACAO. */
    public record UltimaCargaDto(Long id, String tipo, OffsetDateTime executadaEm, int linhasInseridas,
                                 int linhasRejeitadas) {
    }

    public record PendenciaDto(Long parceriaId, String patrocinador, String tipo, String detalhe) {
    }

    public record PainelDto(EventoDto evento, ParceriasDoPainelDto parcerias, EntregasDoPainelDto entregas,
                            RelatoriosDoPainelDto relatorios, UltimaCargaDto ultimaCarga, List<PendenciaDto> pendencias) {
    }
}
