package br.com.rbs.portal.importacao;

import java.util.List;
import java.util.Map;

/**
 * O que um leitor devolve de um arquivo: o tipo do arquivo, os pedaços de texto com o localizador de cada um e a estrutura,
 * que numa planilha guarda as abas e as células e em texto vem vazia. É esse conteúdo que o extrator recebe, nunca o
 * arquivo inteiro.
 *
 * @param tipoDeArquivo o formato lido ("XLSX", "DOCX", "PDF", "TXT"...)
 * @param pedacos       os pedaços de texto, na ordem da leitura
 * @param estrutura     as abas e as células de uma planilha; vazia nos outros formatos
 */
public record ConteudoLido(String tipoDeArquivo, List<Pedaco> pedacos, Map<String, Object> estrutura) {
}
