/**
 * Área documentos: os documentos do Atlas (manuais, planos comerciais, anexos de parceria, FAQ). A RBS envia um PDF ou TXT, aprova, e
 * ele vira trechos com embeddings, versão, escopo e RLS (rotas 54 a 58). O {@code ExtratorDeTexto}, o {@code DivisorDeTrechos} e o
 * {@code DetectorDeDadosPessoais} leem o arquivo; o {@code IngestaoService} indexa numa fila em segundo plano; os embeddings moram em
 * {@code atlas/embeddings}. A busca (M4) lê só o que o papel atlas_ro enxerga: documento indexado, da edição, da cota e da parceria.
 */
package br.com.rbs.portal.documentos;
