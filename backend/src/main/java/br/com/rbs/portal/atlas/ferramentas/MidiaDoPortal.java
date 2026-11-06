package br.com.rbs.portal.atlas.ferramentas;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * O SQL da consulta de mídia do Atlas (plano da base da RBS, AC1 e AC2): as linhas do plano de mídia e o que já foi exibido, lidos da
 * {@code vw_midia_detalhe} da V20. [ATLAS] [ISOLAMENTO] Os filtros de marca e edição entram sempre por parâmetro do servidor (a
 * parceria da conversa, ou a marca que a RBS escolheu), nunca direto do texto do modelo. A conta separa as unidades: inserção, CPM e
 * aplicação nunca se somam. O percentual é o {@code fn_pct_truncado} do portal (corta na primeira casa, sem arredondar).
 */
@Repository
public class MidiaDoPortal {

    /**
     * O recorte da consulta. {@code eventoId} e {@code cota} são da conversa da RBS; {@code parceriaId} é a parceria da conversa (a
     * marca) ou a que a RBS escolheu. {@code mes} é o primeiro dia do mês, ou nulo para todos os meses.
     */
    public record Filtro(Long eventoId, Long parceriaId, String cota, LocalDate mes, String veiculo, String formato, String tipoMidia,
                         String unidade) {
    }

    /**
     * Uma linha do plano de mídia com o que foi exibido no recorte e, quando houver, a origem importada da veiculação: o arquivo e o
     * localizador da célula/linha que gerou a linha. {@code total} é quantas linhas a consulta teria sem o limite.
     */
    public record Linha(Long aproveitamentoId, Long parceriaId, String patrocinador, String cota, String veiculo, String formato,
                        String espacoPrograma, String tipoMidia, String unidade, String mes, BigDecimal previsto,
                        BigDecimal exibido, BigDecimal percentual, BigDecimal impacto, String arquivo,
                        String localizador, Long total) {
    }

    /** O total de uma unidade no recorte, com a mesma regra de percentual e de impacto nulo. */
    public record Total(String unidade, BigDecimal previsto, BigDecimal exibido, BigDecimal percentual, BigDecimal impacto) {
    }

    /**
     * O plano de mídia filtrado. A linha do plano sem nenhuma veiculação no recorte continua aparecendo, com exibido zero (o
     * contratado não some); o impacto é nulo quando todas as exibições do recorte são nulas.
     */
    private static final String PLANO = """
            WITH plano AS (
                SELECT ap.id AS aproveitamento_id, ap.parceria_id, pat.nome_canonico AS patrocinador, ct.codigo AS cota,
                       ap.veiculo, ap.formato, ap.espaco_programa, ap.tipo_midia, ap.unidade,
                       ap.quantidade_prevista AS previsto
                FROM aproveitamento_previsto ap
                JOIN parceria pa ON pa.id = ap.parceria_id
                JOIN patrocinador pat ON pat.id = pa.patrocinador_id
                JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id
                WHERE ap.categoria = 'MIDIA'
                  AND (CAST(:evento AS bigint) IS NULL OR pa.evento_id = CAST(:evento AS bigint))
                  AND (CAST(:parceria AS bigint) IS NULL OR ap.parceria_id = CAST(:parceria AS bigint))
                  AND (CAST(:cota AS text) IS NULL OR ct.codigo = CAST(:cota AS text))
                  AND (CAST(:veiculo AS text) IS NULL
                       OR public.unaccent(lower(ap.veiculo)) = public.unaccent(lower(CAST(:veiculo AS text))))
                  AND (CAST(:formato AS text) IS NULL
                       OR public.unaccent(lower(ap.formato)) LIKE '%' || public.unaccent(lower(CAST(:formato AS text))) || '%'
                       OR public.unaccent(lower(coalesce(ap.espaco_programa, '')))
                          LIKE '%' || public.unaccent(lower(CAST(:formato AS text))) || '%')
                  AND (CAST(:tipoMidia AS text) IS NULL OR ap.tipo_midia = CAST(:tipoMidia AS text))
                  AND (CAST(:unidade AS text) IS NULL OR ap.unidade = CAST(:unidade AS text))
            ),
            midia AS (
                SELECT pl.*, coalesce(sum(v.exibido), 0) AS exibido, sum(v.impacto) AS impacto
                FROM plano pl
                LEFT JOIN vw_midia_detalhe v ON v.aproveitamento_id = pl.aproveitamento_id
                     AND (CAST(:mes AS date) IS NULL OR v.mes = CAST(:mes AS date))
                GROUP BY pl.aproveitamento_id, pl.parceria_id, pl.patrocinador, pl.cota, pl.veiculo, pl.formato,
                         pl.espaco_programa, pl.tipo_midia, pl.unidade, pl.previsto
            )
            """;

    private static final String LINHAS = PLANO + """
            SELECT m.aproveitamento_id, m.parceria_id, m.patrocinador, m.cota, m.veiculo, m.formato, m.espaco_programa,
                   m.tipo_midia, m.unidade, m.previsto, m.exibido,
                   fn_pct_truncado(100.0 * m.exibido / m.previsto) AS percentual, m.impacto,
                   to_char(CAST(:mes AS date), 'YYYY-MM') AS mes,
                   imp.arquivo, imp.localizador, count(*) OVER () AS total
            FROM midia m
            LEFT JOIN LATERAL (
                SELECT a.nome_original AS arquivo, il.origem -> 'quantidade' ->> 'localizador' AS localizador
                FROM veiculacao vv
                JOIN importacao_linha il ON il.id = vv.importacao_linha_id
                JOIN importacao i ON i.id = il.importacao_id
                JOIN arquivo a ON a.id = i.arquivo_id
                WHERE vv.aproveitamento_id = m.aproveitamento_id
                  AND (CAST(:mes AS date) IS NULL
                       OR date_trunc('month', vv.exibido_em AT TIME ZONE 'America/Sao_Paulo')::date = CAST(:mes AS date))
                ORDER BY vv.id
                LIMIT 1
            ) imp ON TRUE
            ORDER BY m.patrocinador, m.veiculo, m.formato, m.aproveitamento_id
            LIMIT :limite
            """;

    private static final String TOTAIS = PLANO + """
            SELECT m.unidade, sum(m.previsto) AS previsto, sum(m.exibido) AS exibido,
                   fn_pct_truncado(100.0 * sum(m.exibido) / sum(m.previsto)) AS percentual, sum(m.impacto) AS impacto
            FROM midia m
            GROUP BY m.unidade
            ORDER BY CASE m.unidade WHEN 'INSERCAO' THEN 1 WHEN 'CPM' THEN 2 ELSE 3 END
            """;

    private final JdbcClient jdbc;

    public MidiaDoPortal(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Linha> linhas(Filtro filtro, int limite) {
        return consulta(LINHAS, filtro).param("limite", limite).query(Linha.class).list();
    }

    public List<Total> totais(Filtro filtro) {
        return consulta(TOTAIS, filtro).query(Total.class).list();
    }

    private org.springframework.jdbc.core.simple.JdbcClient.StatementSpec consulta(String sql, Filtro filtro) {
        return jdbc.sql(sql).param("evento", filtro.eventoId()).param("parceria", filtro.parceriaId())
                .param("cota", filtro.cota()).param("mes", filtro.mes()).param("veiculo", filtro.veiculo())
                .param("formato", filtro.formato()).param("tipoMidia", filtro.tipoMidia()).param("unidade", filtro.unidade());
    }

}
