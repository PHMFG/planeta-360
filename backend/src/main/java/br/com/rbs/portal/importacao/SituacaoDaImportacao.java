package br.com.rbs.portal.importacao;

/**
 * Em que ponto está uma importação. A situação só anda para frente: LENDO para EM_PREVIA ou ERRO, EM_PREVIA para
 * CONFIRMADA ou DESCARTADA, e CONFIRMADA para RETIRADA (gatilho rn_importacao_transicao).
 */
public enum SituacaoDaImportacao {
    /** A leitura está rodando (ou na fila). */
    LENDO,
    /** A leitura acabou e as linhas propostas esperam a conferência da pessoa. */
    EM_PREVIA,
    /** A pessoa confirmou; o que foi aceito já está gravado. */
    CONFIRMADA,
    /** A prévia foi descartada sem gravar nada. */
    DESCARTADA,
    /** Uma importação confirmada foi retirada, e as linhas de veiculação dela saíram. */
    RETIRADA,
    /** A leitura falhou, com o motivo em "erro". */
    ERRO
}
