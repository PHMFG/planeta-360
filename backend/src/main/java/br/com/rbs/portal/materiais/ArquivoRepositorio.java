package br.com.rbs.portal.materiais;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL de consulta de um arquivo para o download (rota 18). Atende o item 4 da Entrega 2. A gravação do arquivo é do
 * Armazenamento (pacote comum), que também serve à carga de planilhas.
 */
@Repository
public class ArquivoRepositorio {

    /**
     * O arquivo como o download precisa. {@code parceriaId} nulo = arquivo geral da RBS. {@code relatorioNaoDisponibilizado}
     * é verdadeiro quando o arquivo é o PDF de um relatório que a RBS ainda não disponibilizou. {@code abaCodigo} é a aba a
     * que o arquivo pertence (a da entrega, por exemplo) e é nulo para o arquivo que não é de nenhuma aba.
     */
    public record ArquivoParaDownload(Long id, Long parceriaId, String nomeOriginal, String mimeType, String caminho,
                                      Integer versao, boolean relatorioNaoDisponibilizado, String abaCodigo) {
    }

    private final JdbcClient jdbc;

    public ArquivoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ArquivoParaDownload> buscar(long arquivoId) {
        return jdbc.sql("""
                        SELECT a.id, a.parceria_id, a.nome_original, a.mime_type, a.caminho, a.versao,
                               EXISTS (SELECT 1 FROM relatorio r WHERE r.arquivo_id = a.id
                                       AND r.status <> 'DISPONIBILIZADO') AS relatorio_nao_disponibilizado,
                               ab.codigo AS aba_codigo
                        FROM arquivo a LEFT JOIN aba ab ON ab.id = a.aba_id WHERE a.id = :id
                        """)
                .param("id", arquivoId)
                .query(ArquivoParaDownload.class)
                .optional();
    }
}
