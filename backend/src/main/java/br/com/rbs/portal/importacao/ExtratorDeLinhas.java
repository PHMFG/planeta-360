package br.com.rbs.portal.importacao;

import java.util.List;

/**
 * Transforma o conteúdo lido em linhas propostas para um destino. Numa planilha o código aplica o mapeamento que o modelo
 * devolveu (nenhum número passa pelo modelo); em texto corrido o modelo propõe as linhas pela ferramenta própria, sempre com
 * a origem de cada valor.
 */
public interface ExtratorDeLinhas {

    /** Extrai as linhas do conteúdo para o destino, sem sair do alcance da importação. */
    List<LinhaProposta> extrair(ConteudoLido conteudo, DestinoDaImportacao destino, AlcanceDeUmaImportacao alcance);
}
