package br.com.rbs.portal.importacao;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import java.util.List;
import java.util.Map;

/**
 * Um destino da importação. Diz o destino que trata, confere uma linha proposta e grava as linhas aceitas quando a importação é
 * confirmada. A lista de problemas vazia significa que a linha pode ser aceita; cada problema vira o motivo de uma linha recusada
 * na prévia. Nenhuma gravação acontece antes da confirmação (E13).
 */
public interface DestinoDeGravacao {

    /** O destino que este gravador atende. */
    DestinoDaImportacao destino();

    /** Confere a linha no alcance da importação e devolve os problemas, em português; lista vazia quando ela está boa. */
    List<String> problemas(LinhaProposta linha, AlcanceDeUmaImportacao alcance);

    /**
     * Confere o conjunto das linhas, para os destinos em que uma linha depende da outra (cadastros ≤ visitantes). A chave é o
     * índice da linha na lista; os destinos sem regra de conjunto não devolvem nada.
     */
    default Map<Integer, List<String>> problemasDoConjunto(List<LinhaProposta> linhas, AlcanceDeUmaImportacao alcance) {
        return Map.of();
    }

    /**
     * Grava as linhas aceitas, numa carga nova quando o destino usa a área de passagem. Devolve os totais da carga; o destino que
     * só guarda o documento devolve as linhas como lidas e nenhuma inserida.
     */
    ResultadoDaGravacao gravar(UsuarioAutenticado usuario, ContextoDaGravacao contexto, List<LinhaAceita> linhas);

    /**
     * Uma linha aceita na confirmação: o id no banco, a ordem na importação, a marca resolvida, a linha do plano escolhida (ou a
     * sugerida) e o que ela propôs.
     */
    record LinhaAceita(long id, int ordem, Long parceriaId, String patrocinador, Long aproveitamentoId, LinhaProposta proposta) {
    }

    /**
     * O que o destino precisa saber para gravar.
     *
     * @param importacaoId a importação confirmada
     * @param eventoId     a edição
     * @param alcance      o alcance da importação
     * @param arquivoId    o arquivo guardado pelo Armazenamento (é o arquivo da carga)
     * @param nomeArquivo  o nome original, para o histórico
     */
    record ContextoDaGravacao(long importacaoId, long eventoId, AlcanceDeUmaImportacao alcance, long arquivoId,
                              String nomeArquivo) {
    }

    /**
     * O resultado da gravação, no formato dos totais da carga (E13).
     *
     * @param cargaId     a carga criada, ou nulo no destino que não usa a área de passagem
     * @param lidas       as linhas que o destino tentou gravar
     * @param inseridas   as linhas gravadas
     * @param duplicadas  as linhas que já estavam na base
     * @param rejeitadas  as linhas rejeitadas na gravação
     * @param rejeicoes   o motivo de cada rejeição, pela ordem da linha
     */
    record ResultadoDaGravacao(Long cargaId, int lidas, int inseridas, int duplicadas, int rejeitadas, List<Rejeicao> rejeicoes) {
        public ResultadoDaGravacao {
            rejeicoes = rejeicoes == null ? List.of() : List.copyOf(rejeicoes);
        }
    }

    /** Uma linha rejeitada na gravação, pela ordem em que ela está na prévia. */
    record Rejeicao(int ordem, String motivo) {
    }
}
