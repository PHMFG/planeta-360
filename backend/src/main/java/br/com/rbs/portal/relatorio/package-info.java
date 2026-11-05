/**
 * Área relatorio: geração, validação e disponibilização do relatório por parceria, e a exportação em CSV. Rotas 31 a 36
 * (etapa 7): RelatorioController (rotas), RelatorioService (regras), RelatorioRepositorio (consultas) e RelatorioDtos.
 * A entidade Relatorio mapeia a tabela; os números de cada versão ficam em relatorio_indicador.
 * O anexo "Pós-venda da edição" (R8.1, rotas 96 a 99) e os números da edição (R8.2, rotas 100 e 101) vêm depois das 6 seções e não as
 * tocam: PosVendaController (rotas), PosVendaService (regras), PosVendaRepositorio (SQL) e PosVendaDtos. A tabela evento_posvenda é da V18.
 */
package br.com.rbs.portal.relatorio;
