package br.com.rbs.portal.aplicacoes;

import br.com.rbs.portal.materiais.MateriaisDtos.UsuarioResumoDto;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Formatos de saída das rotas 37 e 38 (aba Aplicações: o plano de aplicações de marca, linha a linha, e as fotos de
 * comprovação), em record. [RELATORIO] O formato é o contrato com o front (docs/api.md): camelCase e datas com o fuso de
 * Brasília. Os totais são os mesmos da rota 29 (campo {@code aplicacoes} da consolidação): vêm da mesma visão do banco.
 */
public final class AplicacaoDtos {

    private AplicacaoDtos() {
    }

    /**
     * Os números da rota 29 para a aba. Tudo nulo quando a parceria não tem aplicação de marca no plano comercial (nunca zero
     * inventado). {@code percentual} é comprovadas ÷ previstas × 100, cortado na primeira casa.
     */
    public record TotaisDto(BigDecimal previstas, BigDecimal comprovadas, Long fotos, BigDecimal percentual) {
    }

    /** Uma foto de comprovação de uma linha do plano. {@code arquivoId} é o que a rota 18 baixa. */
    public record ComprovacaoDto(Long id, Long arquivoId, String nomeOriginal, String descricao, UsuarioResumoDto registradoPor,
                                 OffsetDateTime registradoEm) {
    }

    /**
     * Uma linha do plano de aplicações. {@code comprovadas} = o menor entre o previsto e o número de fotos (a foto a mais não
     * conta como aplicação a mais); {@code fotos} é o número de fotos de verdade.
     */
    public record LinhaDto(Long id, String bloco, String veiculo, String formato, String espacoPrograma, String janela,
                           BigDecimal previsto, BigDecimal comprovadas, Long fotos, List<ComprovacaoDto> comprovacoes) {
    }

    /** A resposta da rota 37. Sem plano de aplicações: {@code totais} com todos os campos nulos e {@code linhas} vazia. */
    public record AplicacoesDto(Long parceriaId, TotaisDto totais, List<LinhaDto> linhas) {
    }
}
