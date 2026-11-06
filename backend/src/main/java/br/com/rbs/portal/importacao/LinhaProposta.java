package br.com.rbs.portal.importacao;

import java.util.Map;

/**
 * Uma linha que a extração propôs, no formato do destino, com a origem de cada campo. É o que a conferência valida, o que a
 * prévia mostra e o que a confirmação grava (pela área de carga e pela função do destino).
 *
 * @param destino  para onde a linha vai
 * @param dados    os valores da linha, com os nomes de campo do destino
 * @param origem   de onde saiu cada valor, por campo
 * @param deTexto  se a linha veio de texto corrido (e não de células de planilha); a conferência confere todos os valores, não só
 *                 os números e as datas
 */
public record LinhaProposta(DestinoDaImportacao destino, Map<String, Object> dados, Map<String, OrigemDoValor> origem,
                            boolean deTexto) {

    public LinhaProposta {
        dados = dados == null ? Map.of() : dados;
        origem = origem == null ? Map.of() : origem;
    }

    /** A linha de uma planilha ou de uma leitura tabular (só números e datas precisam de origem). */
    public LinhaProposta(DestinoDaImportacao destino, Map<String, Object> dados, Map<String, OrigemDoValor> origem) {
        this(destino, dados, origem, false);
    }
}
