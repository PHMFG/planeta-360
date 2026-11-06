package br.com.rbs.portal.consolidacao;

import java.math.BigDecimal;
import java.util.List;

/**
 * Objetos de saída das rotas 105 a 108 (a consulta da mídia com filtros). [RELATORIO] Unidades de mídia nunca são somadas
 * entre si: cada linha e cada total trazem a sua unidade. [PERMISSAO] A marca de cota que não é Master não recebe o recorte
 * por mês: o {@link ConsultaDeMidiaSemMesDto} não tem {@code meses} nem {@code mes} nas linhas.
 */
public final class ConsultaDeMidiaDtos {

    private ConsultaDeMidiaDtos() {
    }

    /** Um comprovante da linha: o arquivo da veiculação (ou o da importação de comprovante). */
    public record ComprovanteDto(Long arquivoId, String nome, String tipo) {
    }

    /** Os filtros como vieram, para a tela repetir o recorte. */
    public record FiltrosDto(String mes, String veiculo, String formato, String tipoMidia, String unidade) {
    }

    /** Os filtros da RBS, que tem também a edição, a parceria e a cota. */
    public record FiltrosDaRbsDto(Long eventoId, Long parceriaId, String cota, String mes, String veiculo, String formato,
                                  String tipoMidia, String unidade) {
    }

    /** Previsto x exibido de uma unidade; {@code percentual} é cortado na primeira casa, nunca arredondado. */
    public record TotalPorUnidadeDto(String unidade, BigDecimal previsto, BigDecimal exibido, BigDecimal percentual,
                                     BigDecimal impacto) {
    }

    /**
     * Uma linha do plano de mídia. Sem {@code mes}, a linha soma todos os meses e {@code mes} vai nulo; com o recorte, só
     * aquele mês (e a linha do plano sem exibição nele vem com {@code exibido} 0, mas continua aparecendo). O previsto é
     * sempre o da linha do plano inteira. {@code impacto} é nulo quando nenhuma exibição da linha tem impacto.
     */
    public record LinhaDeMidiaDto(Long aproveitamentoId, String veiculo, String formato, String espacoPrograma,
                                  String tipoMidia, String unidade, String mes, BigDecimal previsto, BigDecimal exibido,
                                  BigDecimal percentual, BigDecimal impacto, long exibicoes,
                                  List<ComprovanteDto> comprovantes, List<String> links) {
    }

    /** A mesma linha para a marca de outra cota: sem o mês, porque o recorte é das Master (M7). */
    public record LinhaDeMidiaSemMesDto(Long aproveitamentoId, String veiculo, String formato, String espacoPrograma,
                                        String tipoMidia, String unidade, BigDecimal previsto, BigDecimal exibido,
                                        BigDecimal percentual, BigDecimal impacto, long exibicoes,
                                        List<ComprovanteDto> comprovantes, List<String> links) {
    }

    /** Rota 105: a mídia da parceria, com os meses que têm exibição e os totais por unidade. */
    public record ConsultaDeMidiaDto(Long parceriaId, FiltrosDto filtros, List<String> meses,
                                     List<TotalPorUnidadeDto> totais, List<LinhaDeMidiaDto> linhas) {
    }

    /** Rota 105 para a marca de outra cota: sem {@code meses} e sem {@code mes} nas linhas. */
    public record ConsultaDeMidiaSemMesDto(Long parceriaId, FiltrosDto filtros, List<TotalPorUnidadeDto> totais,
                                           List<LinhaDeMidiaSemMesDto> linhas) {
    }

    /** A parceria da linha na consulta da RBS. */
    public record ParceriaDaLinhaDto(Long id, String patrocinador, String cota) {
    }

    /** A linha da consulta da RBS: a mesma da 105, com a marca e a cota da parceria. */
    public record LinhaDaRbsDto(Long aproveitamentoId, ParceriaDaLinhaDto parceria, String veiculo, String formato,
                                String espacoPrograma, String tipoMidia, String unidade, String mes, BigDecimal previsto,
                                BigDecimal exibido, BigDecimal percentual, BigDecimal impacto, long exibicoes,
                                List<ComprovanteDto> comprovantes, List<String> links) {
    }

    /** Rota 107: a mídia de todas as parcerias filtradas da edição. */
    public record ConsultaDaRbsDto(FiltrosDaRbsDto filtros, List<String> meses, List<TotalPorUnidadeDto> totais,
                                   List<LinhaDaRbsDto> linhas) {
    }
}
