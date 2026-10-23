package br.com.rbs.portal.comum;

/**
 * As ações que o histórico aceita, com os mesmos nomes do CHECK ck_historico_acao no banco. Atende o item 5 da Entrega 2.
 * [HISTORICO] Usar o enum evita gravar uma ação com nome errado: o banco recusaria a linha inteira.
 */
public enum AcaoHistorico {
    CRIACAO,
    ENVIO_ARQUIVO,
    NOVA_VERSAO,
    ALTERACAO_STATUS,
    EDICAO,
    LIBERACAO_ABA,
    EXCLUSAO,
    GERACAO_RELATORIO,
    VALIDACAO,
    DISPONIBILIZACAO,
    LOGIN,
    LOGIN_FALHOU,
    LOGOUT,
    DOWNLOAD,
    AUTORIZACAO,
    REVOGACAO,
    ATIVACAO,
    CARGA
}
