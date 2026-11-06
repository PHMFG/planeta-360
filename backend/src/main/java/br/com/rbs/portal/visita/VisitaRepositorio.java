package br.com.rbs.portal.visita;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * A última visita de cada pessoa a cada aba (V22) e o que mudou desde então. [PESQUISA] O "novo" é calculado no banco, com
 * os carimbos que cada tabela já tem: a carga que trouxe a veiculação ou o resultado, o arquivo do comprovante, a linha de
 * histórico da mudança de status da entrega, o perfil e a disponibilização do relatório.
 */
@Repository
public class VisitaRepositorio {

    /** Uma linha por aba, na ordem do menu, com a última visita da pessoa (ou nula) e se há novidade desde ela. */
    public record LinhaDeNovidade(String aba, OffsetDateTime vistoEm, boolean novo) {
    }

    private final JdbcClient jdbc;

    public VisitaRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Grava (ou atualiza) a visita da pessoa àquela aba com o agora do banco. */
    public void registrar(long usuarioId, long parceriaId, String aba) {
        jdbc.sql("""
                        INSERT INTO visita_aba (usuario_id, parceria_id, aba, visto_em)
                        VALUES (:usuarioId, :parceriaId, :aba, now())
                        ON CONFLICT (usuario_id, parceria_id, aba) DO UPDATE SET visto_em = now()
                        """)
                .param("usuarioId", usuarioId)
                .param("parceriaId", parceriaId)
                .param("aba", aba)
                .update();
    }

    /**
     * O estado das oito abas. Sem visita registrada, {@code novo} é verdadeiro se existe qualquer um dos eventos da aba; com
     * visita, só conta o evento posterior a ela.
     */
    public List<LinhaDeNovidade> novidades(long usuarioId, long parceriaId) {
        return jdbc.sql("""
                        WITH abas(aba, ordem) AS (
                            VALUES ('PERFIL', 1), ('APLICACOES', 2), ('MIDIA', 3), ('CORTESIAS', 4),
                                   ('CREDENCIAMENTO', 5), ('PESQUISA', 6), ('CONTRATO', 7), ('RELATORIO', 8)
                        ),
                        visitas AS (
                            SELECT aba, visto_em FROM visita_aba
                            WHERE usuario_id = :usuarioId AND parceria_id = :parceriaId
                        ),
                        eventos AS (
                            -- MIDIA: entrou veiculação (o carimbo é a carga; sem ela, a data da exibição)
                            SELECT 'MIDIA' AS aba, coalesce(c.executada_em, v.exibido_em) AS quando
                            FROM veiculacao v
                            JOIN aproveitamento_previsto ap ON ap.id = v.aproveitamento_id
                            LEFT JOIN carga c ON c.id = v.carga_id
                            WHERE ap.parceria_id = :parceriaId
                            UNION ALL
                            -- MIDIA: entrou o comprovante da veiculação (o carimbo é o arquivo)
                            SELECT 'MIDIA', a.enviado_em
                            FROM veiculacao v
                            JOIN aproveitamento_previsto ap ON ap.id = v.aproveitamento_id
                            JOIN arquivo a ON a.id = v.comprovante_arquivo_id
                            WHERE ap.parceria_id = :parceriaId
                            UNION ALL
                            -- Todas as abas do menu: mudou o status de uma entrega daquela aba
                            SELECT a.codigo, h.ocorrido_em
                            FROM historico h
                            JOIN entrega e ON e.id = h.entidade_id
                            JOIN aba a ON a.id = e.aba_id
                            WHERE h.entidade = 'entrega' AND h.acao = 'ALTERACAO_STATUS' AND e.parceria_id = :parceriaId
                            UNION ALL
                            -- PESQUISA: entrou resultado (o carimbo é a carga)
                            SELECT 'PESQUISA', c.executada_em
                            FROM pesquisa_resultado r
                            JOIN carga c ON c.id = r.carga_id
                            WHERE r.parceria_id = :parceriaId
                            UNION ALL
                            -- PESQUISA: entrou perfil (a carga ou a importação que o trouxe)
                            SELECT 'PESQUISA', coalesce(c.executada_em, i.confirmada_em, i.criada_em)
                            FROM pesquisa_perfil pf
                            LEFT JOIN carga c ON c.id = pf.carga_id
                            LEFT JOIN importacao i ON i.id = pf.importacao_id
                            WHERE pf.parceria_id = :parceriaId
                            UNION ALL
                            -- RELATORIO: o relatório foi disponibilizado
                            SELECT 'RELATORIO', r.disponibilizado_em
                            FROM relatorio r
                            WHERE r.parceria_id = :parceriaId AND r.status = 'DISPONIBILIZADO'
                              AND r.disponibilizado_em IS NOT NULL
                        )
                        SELECT ab.aba, vi.visto_em, EXISTS (
                                   SELECT 1 FROM eventos ev
                                   WHERE ev.aba = ab.aba
                                     AND (vi.visto_em IS NULL OR ev.quando > vi.visto_em)) AS novo
                        FROM abas ab
                        LEFT JOIN visitas vi ON vi.aba = ab.aba
                        ORDER BY ab.ordem
                        """)
                .param("usuarioId", usuarioId)
                .param("parceriaId", parceriaId)
                .query(LinhaDeNovidade.class)
                .list();
    }
}
