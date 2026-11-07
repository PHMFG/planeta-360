package br.com.rbs.portal.importacao;

import java.nio.file.Path;

/**
 * Lê um arquivo de um formato e devolve o texto com o localizador de cada pedaço. Há um leitor por família de formato
 * (planilha, Word, slides, PDF, imagem e texto); a fila de importação escolhe o primeiro que aceita a extensão.
 */
public interface LeitorDeConteudo {

    /** Diz se este leitor entende a extensão (sem o ponto: "xlsx", "pdf", "txt"...). */
    boolean aceita(String extensao);

    /** Lê o arquivo e devolve os pedaços e a estrutura; falha com exceção própria quando o formato não abre. */
    ConteudoLido ler(Path arquivo);
}
