/**
 * Área prazos: os prazos da edição, da cota e da parceria que o Atlas consulta (rotas 62 a 64). A RBS cadastra um prazo com a data, a
 * hora e, se quiser, o documento e a página de onde ele saiu; o Atlas lê os ativos pela ferramenta {@code consultar_prazos} (pacote
 * {@code atlas/ferramentas}), junto do prazo das entregas. A tabela e as regras dela moram na V13: o escopo, o alcance contra o
 * documento e o "só se desativa".
 */
package br.com.rbs.portal.prazos;
