package br.com.rbs.portal.importacao;

/**
 * Para onde uma linha importada vai depois de confirmada. É o mesmo destino sugerido pelo JEV ou pelo modelo e o escolhido
 * pela pessoa na prévia; "só consulta" guarda o arquivo no Atlas sem gravar números.
 */
public enum DestinoDaImportacao {
    /** Veiculações da mídia (as exibições e os totais mensais). */
    VEICULACAO,
    /** Comprovantes das exibições e das aplicações de marca. */
    COMPROVANTE,
    /** Resultados da pesquisa (alternativas, notas, contagens e respostas de texto). */
    PESQUISA,
    /** Números gerais da edição (evento_resultado). */
    NUMEROS_EDICAO,
    /** Dados informados pela marca na ativação (o catálogo de campos). */
    DADOS_ATIVACAO,
    /** Plano comercial (aproveitamento_previsto). */
    PLANO,
    /** Só a leitura: o arquivo entra no Atlas e nas fontes, e nenhum número é gravado. */
    SO_CONSULTA
}
