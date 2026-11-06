package br.com.rbs.portal.importacao;

import java.util.List;

/**
 * Um destino da importação. Diz o destino que trata e confere uma linha proposta, sem gravar nada: a lista de problemas vazia
 * significa que a linha pode ser aceita, e cada problema vira o motivo de uma linha recusada na prévia.
 */
public interface DestinoDeGravacao {

    /** O destino que este gravador atende. */
    DestinoDaImportacao destino();

    /** Confere a linha no alcance da importação e devolve os problemas, em português; lista vazia quando ela está boa. */
    List<String> problemas(LinhaProposta linha, AlcanceDeUmaImportacao alcance);
}
