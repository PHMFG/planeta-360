package br.com.rbs.portal.importacao;

import br.com.rbs.portal.importacao.ImportacaoDtos.NaoEntendidoDto;
import java.math.BigDecimal;
import java.util.List;

/**
 * O que a extração devolve: as linhas propostas, o que a leitura não entendeu (título, cabeçalho, total) e, quando o extrator
 * sugere o destino, o destino e a confiança. Nenhum número passa pelo modelo: o extrator só devolve o que o código leu.
 */
public record ResultadoDaExtracao(List<LinhaProposta> linhas, List<NaoEntendidoDto> naoEntendidos,
                                  DestinoDaImportacao destinoSugerido, BigDecimal confianca) {

    public ResultadoDaExtracao {
        linhas = linhas == null ? List.of() : List.copyOf(linhas);
        naoEntendidos = naoEntendidos == null ? List.of() : List.copyOf(naoEntendidos);
    }

    public static ResultadoDaExtracao vazio() {
        return new ResultadoDaExtracao(List.of(), List.of(), null, null);
    }
}
