package br.com.rbs.portal.dados;

/**
 * De onde veio um dado informado, com os mesmos nomes do CHECK de dado_informado.origem: PORTAL é o formulário da aba (rota 41)
 * e ATLAS é o cartão de perguntas do assistente. [HISTORICO] A origem fica gravada em cada valor e à vista na rota 40, junto
 * de quem informou e quando: o relatório mostra de onde vem cada número.
 */
public enum OrigemDoDado {
    PORTAL,
    ATLAS
}
