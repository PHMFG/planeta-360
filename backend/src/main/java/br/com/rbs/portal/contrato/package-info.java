/**
 * Área contrato (R5.7, rotas 74 a 76): quem assina pela marca e o contrato final assinado, com versões. ContratoController recebe os
 * pedidos; ContratoService guarda as regras; ContratoRepositorio tem o SQL. O signatário (nome, cargo e e-mail) é dado pessoal de
 * terceiro: fica só na tabela {@code contrato}, nunca no histórico, no log, no Atlas, no JEV nem no relatório. O arquivo e o disco
 * são do Armazenamento (pacote comum).
 */
package br.com.rbs.portal.contrato;
