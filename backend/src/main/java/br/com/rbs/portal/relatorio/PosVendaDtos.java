package br.com.rbs.portal.relatorio;

import br.com.rbs.portal.relatorio.RelatorioDtos.UsuarioResumoDto;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Corpos das rotas 96 a 99 (o anexo "Pós-venda da edição" do relatório). O formato é o contrato com o front (docs/api.md): camelCase,
 * datas com fuso de Brasília, listas sempre como lista.
 */
public final class PosVendaDtos {

    private PosVendaDtos() {
    }

    /** Um link do anexo: {@code tipo} é AFTERMOVIE, CLIPPING ou OUTRO. O endereço é só http ou https. */
    public record LinkDto(String tipo, String titulo, String url) {
    }

    /** A audiência de um veículo: ex. "ATL TV", "pessoas alcançadas", 1600000, "pessoas". */
    public record AudienciaDto(String veiculo, String metrica, BigDecimal numero, String unidade) {
    }

    /** Um post da parceria (R5.3.d): o link de uma veiculação comprovada, com o veículo e o formato do plano. */
    public record LinkDePostDto(String veiculo, String formato, OffsetDateTime exibidoEm, String url) {
    }

    /**
     * Uma versão do anexo. {@code publicadoPor} e {@code retiradoEm} vão nulos para a marca: quem publicou é dado interno da RBS, e a marca
     * só vê a versão em vigor, que por definição não foi retirada.
     */
    public record AnexoDto(Long id, Integer versao, String texto, List<LinkDto> links, List<AudienciaDto> audiencias,
                           OffsetDateTime publicadoEm, UsuarioResumoDto publicadoPor) {
    }

    /**
     * Rota 96. {@code anexo} é a versão em vigor (a mais nova, se não foi retirada) ou nulo; {@code relatorioDisponibilizado} diz se a
     * marca já vê o relatório. A marca só recebe esta resposta com os dois; a RBS a recebe sempre, para ver o que a marca veria.
     */
    public record PosVendaDaParceriaDto(Long parceriaId, Long eventoId, boolean relatorioDisponibilizado, AnexoDto anexo,
                                        List<LinkDePostDto> linksDePosts) {
    }

    /** Rota 98: uma versão do anexo de uma parceria da edição, retiradas inclusive, com quem e quando. */
    public record PosVendaDaEdicaoDto(Long id, Long parceriaId, String patrocinador, Integer versao, String situacao, String texto,
                                      List<LinkDto> links, List<AudienciaDto> audiencias, OffsetDateTime publicadoEm,
                                      UsuarioResumoDto publicadoPor, OffsetDateTime retiradoEm, UsuarioResumoDto retiradoPor) {
    }

    /** Corpo da rota 97. Tudo é opcional, mas é preciso ao menos um dos três. */
    public record PublicarRequisicao(String texto, List<LinkDto> links, List<AudienciaDto> audiencias) {
    }

    /** Rotas 100 e 101: um número da edição ("O festival em números"). */
    public record ResultadoDto(Long id, String indicador, BigDecimal valor, String qualificador, String unidade, String fonte) {
    }

    /** Corpo da rota 101. {@code qualificador} é EXATO (o padrão) ou MAIS_DE. */
    public record InformarResultadoRequisicao(String indicador, BigDecimal valor, String qualificador, String unidade, String fonte) {
    }
}
