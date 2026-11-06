package br.com.rbs.portal.importacao;

import java.util.List;

/**
 * Transforma o conteúdo lido em linhas propostas para um destino. Numa planilha o código aplica o mapeamento que o modelo
 * devolveu (nenhum número passa pelo modelo); em texto corrido o modelo propõe as linhas pela ferramenta própria, sempre com a
 * origem de cada valor.
 */
public interface ExtratorDeLinhas {

    /** Se este extrator entende o conteúdo (a planilha é do extrator tabular; o texto corrido, do extrator livre). */
    default boolean aceita(ConteudoLido conteudo) {
        return true;
    }

    /** Extrai as linhas do conteúdo para o destino, sem sair do alcance da importação. */
    List<LinhaProposta> extrair(ConteudoLido conteudo, DestinoDaImportacao destino, AlcanceDeUmaImportacao alcance);

    /** A extração com o que não foi entendido e a sugestão do destino; o padrão descarta os dois. */
    default ResultadoDaExtracao extrairComDetalhe(ConteudoLido conteudo, DestinoDaImportacao destino, AlcanceDeUmaImportacao alcance) {
        return new ResultadoDaExtracao(extrair(conteudo, destino, alcance), List.of(), null, null);
    }

    /**
     * A extração que não chama modelo: só o que o código entende, como o CSV com o cabeçalho padrão (E7). É o caminho de uma
     * importação com suspeita de dado pessoal (E4): nada do conteúdo sai para o provedor.
     */
    default List<LinhaProposta> extrairSemModelo(ConteudoLido conteudo, DestinoDaImportacao destino, AlcanceDeUmaImportacao alcance) {
        return List.of();
    }

    /** A extração sem modelo com o que não foi entendido; o padrão descarta os dois. */
    default ResultadoDaExtracao extrairSemModeloComDetalhe(ConteudoLido conteudo, DestinoDaImportacao destino,
                                                          AlcanceDeUmaImportacao alcance) {
        return new ResultadoDaExtracao(extrairSemModelo(conteudo, destino, alcance), List.of(), null, null);
    }
}
