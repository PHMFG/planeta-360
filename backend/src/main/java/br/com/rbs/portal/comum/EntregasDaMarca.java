package br.com.rbs.portal.comum;

/**
 * [PERMISSAO] O SQL do que o patrocinador pode contar de entregas: só as de aba aberta (parceria_aba.liberada E visivel).
 * É o mesmo predicado da lista de entregas, e o resumo repete as contas de vw_entregas_resumo (V6) sobre essas entregas;
 * a RBS continua lendo a visão, com todas as abas. Mora aqui porque a listagem de parcerias e a consolidação, de pacotes
 * diferentes, precisam do mesmo resumo e as duas contas não podem se afastar.
 */
public final class EntregasDaMarca {

    private EntregasDaMarca() {
    }

    /** O trecho "aba aberta para a parceria" da entrega de apelido {@code e}, para entrar num WHERE ... AND. */
    public static final String ABA_ABERTA = "EXISTS (SELECT 1 FROM parceria_aba pab WHERE pab.parceria_id = e.parceria_id"
            + " AND pab.aba_id = e.aba_id AND pab.liberada AND pab.visivel)";

    /**
     * Subconsulta com as mesmas colunas de vw_entregas_resumo (parceria_id, total, nao_aplicaveis, aprovadas, pendentes,
     * pct_concluidas), contando só as abas abertas. Parceria sem entrega de aba aberta não tem linha, como a visão
     * não tem linha para parceria sem entrega. Vai entre parênteses no lugar da visão.
     */
    public static final String RESUMO = """
            SELECT e.parceria_id,
                   count(*)                                                  AS total,
                   count(*) FILTER (WHERE e.status = 'NAO_APLICAVEL')        AS nao_aplicaveis,
                   count(*) FILTER (WHERE e.status = 'APROVADO')             AS aprovadas,
                   count(*) FILTER (WHERE e.status NOT IN ('APROVADO','NAO_APLICAVEL')) AS pendentes,
                   fn_pct_truncado(100.0 * count(*) FILTER (WHERE e.status = 'APROVADO')
                         / nullif(count(*) FILTER (WHERE e.status <> 'NAO_APLICAVEL'), 0)) AS pct_concluidas
            FROM entrega e WHERE\s""" + ABA_ABERTA + " GROUP BY e.parceria_id";
}
