package br.com.rbs.portal.aplicacoes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL da aba Aplicações (rotas 37 e 38): o plano de aplicações de marca linha a linha, as fotos de comprovação e a gravação
 * de uma foto. [RELATORIO] Os totais vêm da mesma visão da consolidação (vw_aplicacoes, com fn_pct_truncado) e as contas por
 * linha repetem as dela, uma linha de cada vez: assim a tela e a rota 29 nunca discordam. [ISOLAMENTO] Toda consulta recebe o
 * id da parceria; quem decide se o usuário pode vê-la é o AplicacaoService.
 */
@Repository
public class AplicacaoRepositorio {

    public record LinhaDeTotais(BigDecimal previstas, BigDecimal comprovadas, Long fotos, BigDecimal percentual) {
    }

    public record LinhaDoPlano(Long id, String bloco, String veiculo, String formato, String espacoPrograma, String janela,
                               BigDecimal previsto, BigDecimal comprovadas, Long fotos) {
    }

    public record LinhaDeComprovacao(Long id, Long aproveitamentoId, Long arquivoId, String nomeOriginal, String descricao,
                                     Long usuarioId, String usuarioNome, String usuarioPerfil, OffsetDateTime registradoEm) {
    }

    /** A linha do plano como a gravação precisa: de que categoria é e como se chama (para o histórico). */
    public record LinhaBase(Long id, String categoria, String formato) {
    }

    /** O que o banco devolve ao gravar uma foto: o id da comprovação e a data, que é do banco. */
    public record ComprovacaoGravada(Long id, OffsetDateTime registradoEm) {
    }

    private final JdbcClient jdbc;

    public AplicacaoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------------
    // Rota 37
    // ---------------------------------------------------------------------

    /** Os mesmos números de {@code aplicacoes} na rota 29; vazio se a parceria não tem aplicação de marca no plano. */
    public Optional<LinhaDeTotais> totais(long parceriaId) {
        return jdbc.sql("""
                        SELECT CAST(previstas AS numeric(14,2)) AS previstas, CAST(comprovadas AS numeric(14,2)) AS comprovadas,
                               CAST(fotos AS bigint) AS fotos, fn_pct_truncado(100 * comprovadas / previstas) AS percentual
                        FROM vw_aplicacoes WHERE parceria_id = :parceriaId
                        """)
                .param("parceriaId", parceriaId)
                .query(LinhaDeTotais.class)
                .optional();
    }

    /**
     * As linhas de aplicação de marca do plano, por bloco, formato e id. Mesma conta da visão, sem agrupar: a linha conta como
     * comprovada até o limite do previsto (o menor entre o previsto e as fotos).
     */
    public List<LinhaDoPlano> linhas(long parceriaId) {
        return jdbc.sql("""
                        SELECT ap.id, ap.bloco, ap.veiculo, ap.formato, ap.espaco_programa, ap.janela,
                               ap.quantidade_prevista AS previsto,
                               CAST(least(ap.quantidade_prevista, coalesce(c.fotos, 0)) AS numeric(14,2)) AS comprovadas,
                               coalesce(c.fotos, 0) AS fotos
                        FROM aproveitamento_previsto ap
                        LEFT JOIN (SELECT aproveitamento_id, count(*) AS fotos
                                   FROM comprovacao_marca GROUP BY aproveitamento_id) c ON c.aproveitamento_id = ap.id
                        WHERE ap.parceria_id = :parceriaId AND ap.categoria = 'APLICACAO_MARCA'
                        ORDER BY ap.bloco, ap.formato, ap.id
                        """)
                .param("parceriaId", parceriaId)
                .query(LinhaDoPlano.class)
                .list();
    }

    /** Todas as fotos da parceria, da mais antiga para a mais nova dentro de cada linha do plano. */
    public List<LinhaDeComprovacao> comprovacoes(long parceriaId) {
        return jdbc.sql("""
                        SELECT cm.id, cm.aproveitamento_id, cm.arquivo_id, a.nome_original, cm.descricao,
                               u.id AS usuario_id, u.nome AS usuario_nome, u.perfil AS usuario_perfil, cm.registrado_em
                        FROM comprovacao_marca cm
                        JOIN arquivo a ON a.id = cm.arquivo_id
                        JOIN usuario u ON u.id = cm.registrado_por
                        WHERE cm.parceria_id = :parceriaId
                        ORDER BY cm.aproveitamento_id, cm.registrado_em, cm.id
                        """)
                .param("parceriaId", parceriaId)
                .query(LinhaDeComprovacao.class)
                .list();
    }

    // ---------------------------------------------------------------------
    // Rota 38
    // ---------------------------------------------------------------------

    /** A linha do plano, só se for desta parceria (a de outra parceria é como a que não existe). */
    public Optional<LinhaBase> linha(long parceriaId, long aproveitamentoId) {
        return jdbc.sql("SELECT id, categoria, formato FROM aproveitamento_previsto WHERE id = :id AND parceria_id = :parceriaId")
                .param("id", aproveitamentoId)
                .param("parceriaId", parceriaId)
                .query(LinhaBase.class)
                .optional();
    }

    /**
     * Grava a foto na linha. O gatilho rn_comprovacao_valida preenche a parceria e confere a categoria da linha e do arquivo;
     * as chaves compostas da V1 garantem que o arquivo e a linha são da mesma parceria.
     */
    public ComprovacaoGravada inserir(long aproveitamentoId, long arquivoId, String descricao, long usuarioId) {
        return jdbc.sql("""
                        INSERT INTO comprovacao_marca (aproveitamento_id, arquivo_id, descricao, registrado_por)
                        VALUES (:aproveitamento, :arquivo, :descricao, :usuario)
                        RETURNING id, registrado_em
                        """)
                .param("aproveitamento", aproveitamentoId)
                .param("arquivo", arquivoId)
                .param("descricao", descricao)
                .param("usuario", usuarioId)
                .query(ComprovacaoGravada.class)
                .single();
    }
}
