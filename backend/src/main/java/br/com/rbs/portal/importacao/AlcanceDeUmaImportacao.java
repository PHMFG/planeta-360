package br.com.rbs.portal.importacao;

/**
 * O alcance concreto de uma importação: a edição e, conforme o caso, a cota ou a parceria. O extrator e os destinos usam
 * este record para recusar a linha de uma marca que está fora do alcance.
 *
 * @param eventoId   a edição escolhida
 * @param alcance    a edição inteira, uma cota ou uma parceria
 * @param cotaTipoId a cota, quando o alcance é COTA; nula nos outros
 * @param parceriaId a parceria, quando o alcance é PARCERIA; nula nos outros
 */
public record AlcanceDeUmaImportacao(long eventoId, AlcanceDaImportacao alcance, Long cotaTipoId, Long parceriaId) {
}
