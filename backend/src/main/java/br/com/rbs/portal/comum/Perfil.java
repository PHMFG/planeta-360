package br.com.rbs.portal.comum;

/**
 * Os dois perfis do portal, com os mesmos nomes da coluna usuario.perfil. Atende o item 3 da Entrega 2.
 * [PERMISSAO] ADMIN_RBS enxerga todas as parcerias; ADMIN_PATROCINADOR só as parcerias em que tem vínculo.
 */
public enum Perfil {
    ADMIN_RBS,
    ADMIN_PATROCINADOR
}
