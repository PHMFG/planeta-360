package br.com.rbs.portal.importacao;

/**
 * Um pedaço de texto lido do arquivo, com o localizador de onde ele saiu. Numa planilha, o localizador é a célula
 * ("Aba!B7"); num PDF, a página; num slide, o número; num áudio, o minuto.
 *
 * @param texto       o texto do pedaço, já extraído
 * @param localizador onde ele está no arquivo ("Aba!B7", "página 4", "slide 3", "00:03:12")
 * @param ordem       a ordem do pedaço na leitura, a partir de 1
 */
public record Pedaco(String texto, String localizador, int ordem) {
}
