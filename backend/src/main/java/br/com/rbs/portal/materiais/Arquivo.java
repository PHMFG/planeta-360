package br.com.rbs.portal.materiais;

import java.time.OffsetDateTime;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Entidade da tabela arquivo: o repositório de arquivos, uma linha por versão enviada. Atende o item 1 da Entrega 2.
 * [UPLOAD] No banco ficam só os dados e o caminho; o conteúdo fica na pasta de armazenamento.
 * [ISOLAMENTO] parceriaId nulo significa arquivo geral da RBS (por exemplo, a fonte da pesquisa do festival).
 * {@code observacao} é o recado que quem enviou escreveu junto com esta versão (nulo se não escreveu).
 * [ATLAS-DEPOIS] As colunas de cifra entram em migração própria quando o Atlas for ligado.
 */
@Table("arquivo")
public record Arquivo(
        @Id Long id,
        Long parceriaId,
        Integer abaId,
        Long entregaId,
        String categoria,
        String nomeOriginal,
        String mimeType,
        Long tamanhoBytes,
        String caminho,
        String hashSha256,
        Integer versao,
        Long enviadoPor,
        OffsetDateTime enviadoEm,
        String observacao) {
}
