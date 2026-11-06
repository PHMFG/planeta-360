package br.com.rbs.portal.importacao;

/**
 * De onde saiu o valor de um campo da linha proposta: o localizador no arquivo e o trecho que o comprova. A conferência em
 * código compara o valor com o trecho antes de a linha ser aceita, e a prévia abre o arquivo no localizador.
 *
 * @param localizador onde o valor está ("Aba!B7", "slide 3", "00:03:12")
 * @param trecho      o texto da fonte que contém o valor
 */
public record OrigemDoValor(String localizador, String trecho) {
}
