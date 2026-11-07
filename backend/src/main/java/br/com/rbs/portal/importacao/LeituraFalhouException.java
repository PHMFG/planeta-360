package br.com.rbs.portal.importacao;

/**
 * A leitura de um arquivo não terminou: formato que o leitor não entende, arquivo corrompido, limite de planilha estourado ou
 * conteúdo que não dá para extrair. A importação vai a ERRO com esta frase, que é mostrada na tela.
 */
public class LeituraFalhouException extends RuntimeException {

    public LeituraFalhouException(String motivo) {
        super(motivo);
    }

    public LeituraFalhouException(String motivo, Throwable causa) {
        super(motivo, causa);
    }
}
