package br.com.rbs.portal.atlas.diagnostico;

/** Como uma etapa terminou ({@code atlas_traco.resultado}). */
public enum ResultadoDaEtapa {
    OK,
    /** Uma regra disse não (o teto diário, a checagem do texto, o limite de chamadas). */
    RECUSADO,
    ERRO,
    /** O JEV decidiu sem agir (o modo sombra). */
    SOMBRA,
    /** A etapa não rodou, ou ainda não terminou (o resumo do raciocínio pendente, com {@code dados.pendente}). */
    PULADO
}
