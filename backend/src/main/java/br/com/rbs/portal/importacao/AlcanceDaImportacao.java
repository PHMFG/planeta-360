package br.com.rbs.portal.importacao;

/**
 * De quem são as linhas de uma importação: a edição inteira, uma cota ou uma parceria. Uma linha de uma marca fora do
 * alcance é recusada na conferência (gatilho rn_importacao_linha_alcance).
 */
public enum AlcanceDaImportacao {
    /** Todas as marcas da edição. */
    EDICAO,
    /** As marcas de uma cota na edição (Master, Subcota G ou Subcota). */
    COTA,
    /** Uma só parceria (a importação que a marca faz em Aplicações, por exemplo). */
    PARCERIA
}
