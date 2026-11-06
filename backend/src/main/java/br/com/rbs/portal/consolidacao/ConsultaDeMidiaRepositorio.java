package br.com.rbs.portal.consolidacao;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Consultas das rotas 105 a 108 (a mídia linha do plano × mês, com filtros). [RELATORIO] A linha vem de
 * {@code aproveitamento_previsto} e o exibido, o impacto e as contagens, de {@code vw_midia_detalhe}; sem o recorte por mês,
 * os meses da mesma linha do plano são somados uma vez só. [ISOLAMENTO] Quem decide o acesso é o ConsultaDeMidiaService; aqui
 * toda consulta da marca recebe o id da parceria, e a da RBS, a edição (e, se vierem, a parceria e a cota).
 */
@Repository
public class ConsultaDeMidiaRepositorio {

    /** A parceria da consulta: o nome da marca e a cota (a regra do mês depende dela). */
    public record Parceria(Long id, String patrocinador, String cota) {
    }

    /** Uma linha do plano de mídia com o que foi exibido nela (todos os meses ou só o mês pedido). */
    public record LinhaDoPlano(Long parceriaId, Long aproveitamentoId, String patrocinador, String cota, String veiculo,
                               String formato, String espacoPrograma, String tipoMidia, String unidade,
                               BigDecimal previsto, BigDecimal exibido, BigDecimal impacto, long exibicoes) {
    }

    /** O comprovante e o link de uma veiculação da linha (a mesma linha pode ter vários). */
    public record ArquivoDaLinha(Long aproveitamentoId, Long arquivoId, String nomeOriginal, String mimeType, String link) {
    }

    // [RELATORIO] O recorte por mês vale para as linhas do plano e para os comprovantes/links.
    private static final String RECORTE_DO_MES =
            " AND (CAST(:mes AS text) IS NULL OR d.mes = to_date(CAST(:mes AS text) || '-01', 'YYYY-MM-DD'))";

    private static final String RECORTE_DO_MES_DA_VEICULACAO =
            " AND (CAST(:mes AS text) IS NULL"
                    + " OR to_char(v.exibido_em AT TIME ZONE 'America/Sao_Paulo', 'YYYY-MM') = CAST(:mes AS text))";

    /**
     * [RELATORIO] Os filtros da linha do plano: veículo igual sem caixa nem acento; formato contém (também no espaço ou
     * programa); tipo e unidade iguais. O texto vai por position(), que não trata % e _ como curingas.
     */
    private static final String FILTROS_DA_LINHA = """
              AND (CAST(:veiculo AS text) IS NULL
                   OR unaccent(lower(btrim(ap.veiculo))) = unaccent(lower(btrim(CAST(:veiculo AS text)))))
              AND (CAST(:formato AS text) IS NULL
                   OR position(unaccent(lower(btrim(CAST(:formato AS text)))) in unaccent(lower(ap.formato))) > 0
                   OR position(unaccent(lower(btrim(CAST(:formato AS text)))) in unaccent(lower(coalesce(ap.espaco_programa, '')))) > 0)
              AND (CAST(:tipoMidia AS text) IS NULL
                   OR upper(btrim(ap.tipo_midia)) = upper(btrim(CAST(:tipoMidia AS text))))
              AND (CAST(:unidade AS text) IS NULL
                   OR upper(btrim(ap.unidade)) = upper(btrim(CAST(:unidade AS text))))
            """;

    private static final String FILTROS_DA_VISAO = """
              AND (CAST(:veiculo AS text) IS NULL
                   OR unaccent(lower(btrim(d.veiculo))) = unaccent(lower(btrim(CAST(:veiculo AS text)))))
              AND (CAST(:formato AS text) IS NULL
                   OR position(unaccent(lower(btrim(CAST(:formato AS text)))) in unaccent(lower(d.formato))) > 0
                   OR position(unaccent(lower(btrim(CAST(:formato AS text)))) in unaccent(lower(coalesce(d.espaco_programa, '')))) > 0)
              AND (CAST(:tipoMidia AS text) IS NULL
                   OR upper(btrim(d.tipo_midia)) = upper(btrim(CAST(:tipoMidia AS text))))
              AND (CAST(:unidade AS text) IS NULL
                   OR upper(btrim(d.unidade)) = upper(btrim(CAST(:unidade AS text))))
            """;

    private static final String COLUNAS_DA_LINHA = """
            SELECT ap.id AS aproveitamento_id, ap.parceria_id, p.nome_canonico AS patrocinador, ct.codigo AS cota,
                   ap.veiculo, ap.formato, ap.espaco_programa, ap.tipo_midia, ap.unidade,
                   CAST(ap.quantidade_prevista AS numeric(14,2)) AS previsto,
                   CAST(coalesce(sum(d.exibido), 0) AS numeric(14,2)) AS exibido,
                   sum(d.impacto) AS impacto,
                   CAST(coalesce(sum(d.exibicoes), 0) AS bigint) AS exibicoes
            FROM aproveitamento_previsto ap
            JOIN parceria pa ON pa.id = ap.parceria_id
            JOIN patrocinador p ON p.id = pa.patrocinador_id
            JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id
            LEFT JOIN vw_midia_detalhe d ON d.aproveitamento_id = ap.id
            """;

    private static final String AGRUPAMENTO = """
            GROUP BY ap.id, ap.parceria_id, p.nome_canonico, ct.codigo, ap.veiculo, ap.formato, ap.espaco_programa,
                     ap.tipo_midia, ap.unidade, ap.quantidade_prevista
            """;

    private final JdbcClient jdbc;

    public ConsultaDeMidiaRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Parceria> parceria(long parceriaId) {
        return jdbc.sql("""
                        SELECT pa.id, pat.nome_canonico AS patrocinador, ct.codigo AS cota
                        FROM parceria pa JOIN patrocinador pat ON pat.id = pa.patrocinador_id
                        JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id
                        WHERE pa.id = :id
                        """)
                .param("id", parceriaId)
                .query(Parceria.class)
                .optional();
    }

    /** A edição (ano) do evento, ou vazio se ele não existe. */
    public Optional<Integer> edicaoDoEvento(long eventoId) {
        return jdbc.sql("SELECT edicao FROM evento WHERE id = :id").param("id", eventoId).query(Integer.class).optional();
    }

    /** A edição mais nova do festival: o padrão do filtro eventoId da rota 107. */
    public Optional<Long> eventoMaisNovo() {
        return jdbc.sql("SELECT id FROM evento ORDER BY edicao DESC, id DESC LIMIT 1").query(Long.class).optional();
    }

    // ---------------------------------------------------------------------
    // Rota 105: uma parceria
    // ---------------------------------------------------------------------

    public List<LinhaDoPlano> linhasDaParceria(long parceriaId, String mes, String veiculo, String formato,
                                               String tipoMidia, String unidade) {
        return jdbc.sql(COLUNAS_DA_LINHA + RECORTE_DO_MES
                        + " WHERE ap.categoria = 'MIDIA' AND ap.parceria_id = :parceriaId" + FILTROS_DA_LINHA
                        + AGRUPAMENTO
                        + " ORDER BY ap.veiculo, ap.formato, ap.espaco_programa NULLS FIRST, ap.id")
                .param("parceriaId", parceriaId)
                .param("mes", mes)
                .param("veiculo", veiculo)
                .param("formato", formato)
                .param("tipoMidia", tipoMidia)
                .param("unidade", unidade)
                .query(LinhaDoPlano.class)
                .list();
    }

    /** Os meses com exibição na parceria, em ordem; os outros filtros valem, o recorte por mês não. */
    public List<String> mesesDaParceria(long parceriaId, String veiculo, String formato, String tipoMidia, String unidade) {
        return jdbc.sql("""
                        SELECT DISTINCT to_char(d.mes, 'YYYY-MM') AS mes
                        FROM vw_midia_detalhe d
                        WHERE d.parceria_id = :parceriaId AND d.mes IS NOT NULL
                        """ + FILTROS_DA_VISAO + " ORDER BY 1")
                .param("parceriaId", parceriaId)
                .param("veiculo", veiculo)
                .param("formato", formato)
                .param("tipoMidia", tipoMidia)
                .param("unidade", unidade)
                .query(String.class)
                .list();
    }

    public List<ArquivoDaLinha> arquivosDaParceria(long parceriaId, String mes, String veiculo, String formato,
                                                   String tipoMidia, String unidade) {
        return jdbc.sql(COLUNAS_DOS_ARQUIVOS + " WHERE ap.categoria = 'MIDIA' AND ap.parceria_id = :parceriaId"
                        + RECORTE_DO_MES_DA_VEICULACAO + FILTROS_DA_LINHA
                        + " ORDER BY v.aproveitamento_id, v.exibido_em DESC, v.id DESC")
                .param("parceriaId", parceriaId)
                .param("mes", mes)
                .param("veiculo", veiculo)
                .param("formato", formato)
                .param("tipoMidia", tipoMidia)
                .param("unidade", unidade)
                .query(ArquivoDaLinha.class)
                .list();
    }

    // ---------------------------------------------------------------------
    // Rota 107: a RBS, por edição
    // ---------------------------------------------------------------------

    public List<LinhaDoPlano> linhasDaRbs(long eventoId, Long parceriaId, String cota, String mes, String veiculo,
                                          String formato, String tipoMidia, String unidade) {
        return jdbc.sql(COLUNAS_DA_LINHA + RECORTE_DO_MES
                        + " WHERE ap.categoria = 'MIDIA' AND pa.evento_id = :eventoId"
                        + " AND (CAST(:parceriaId AS bigint) IS NULL OR ap.parceria_id = CAST(:parceriaId AS bigint))"
                        + " AND (CAST(:cota AS text) IS NULL OR ct.codigo = CAST(:cota AS text))"
                        + FILTROS_DA_LINHA + AGRUPAMENTO
                        + " ORDER BY p.nome_canonico, ap.veiculo, ap.formato, ap.espaco_programa NULLS FIRST, ap.id")
                .param("eventoId", eventoId)
                .param("parceriaId", parceriaId)
                .param("cota", cota)
                .param("mes", mes)
                .param("veiculo", veiculo)
                .param("formato", formato)
                .param("tipoMidia", tipoMidia)
                .param("unidade", unidade)
                .query(LinhaDoPlano.class)
                .list();
    }

    public List<String> mesesDaRbs(long eventoId, Long parceriaId, String cota, String veiculo, String formato,
                                   String tipoMidia, String unidade) {
        return jdbc.sql("""
                        SELECT DISTINCT to_char(d.mes, 'YYYY-MM') AS mes
                        FROM vw_midia_detalhe d
                        JOIN parceria pa ON pa.id = d.parceria_id
                        JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id
                        WHERE pa.evento_id = :eventoId AND d.mes IS NOT NULL
                          AND (CAST(:parceriaId AS bigint) IS NULL OR d.parceria_id = CAST(:parceriaId AS bigint))
                          AND (CAST(:cota AS text) IS NULL OR ct.codigo = CAST(:cota AS text))
                        """ + FILTROS_DA_VISAO + " ORDER BY 1")
                .param("eventoId", eventoId)
                .param("parceriaId", parceriaId)
                .param("cota", cota)
                .param("veiculo", veiculo)
                .param("formato", formato)
                .param("tipoMidia", tipoMidia)
                .param("unidade", unidade)
                .query(String.class)
                .list();
    }

    public List<ArquivoDaLinha> arquivosDaRbs(long eventoId, Long parceriaId, String cota, String mes, String veiculo,
                                              String formato, String tipoMidia, String unidade) {
        return jdbc.sql(COLUNAS_DOS_ARQUIVOS + " WHERE ap.categoria = 'MIDIA' AND pa.evento_id = :eventoId"
                        + " AND (CAST(:parceriaId AS bigint) IS NULL OR ap.parceria_id = CAST(:parceriaId AS bigint))"
                        + " AND (CAST(:cota AS text) IS NULL OR ct.codigo = CAST(:cota AS text))"
                        + RECORTE_DO_MES_DA_VEICULACAO + FILTROS_DA_LINHA
                        + " ORDER BY v.aproveitamento_id, v.exibido_em DESC, v.id DESC")
                .param("eventoId", eventoId)
                .param("parceriaId", parceriaId)
                .param("cota", cota)
                .param("mes", mes)
                .param("veiculo", veiculo)
                .param("formato", formato)
                .param("tipoMidia", tipoMidia)
                .param("unidade", unidade)
                .query(ArquivoDaLinha.class)
                .list();
    }

    // ---------------------------------------------------------------------
    // Comprovantes e links
    // ---------------------------------------------------------------------

    /**
     * [RELATORIO] O comprovante é o arquivo da veiculação ou, quando a linha veio de uma importação de comprovante, o
     * arquivo dessa importação. O link é o da exibição (a API só devolve os que passam na regra do HostDoLink).
     */
    private static final String COLUNAS_DOS_ARQUIVOS = """
            SELECT v.aproveitamento_id, a.id AS arquivo_id, a.nome_original, a.mime_type, v.link
            FROM veiculacao v
            JOIN aproveitamento_previsto ap ON ap.id = v.aproveitamento_id
            JOIN parceria pa ON pa.id = ap.parceria_id
            JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id
            LEFT JOIN importacao_linha il ON il.id = v.importacao_linha_id
            LEFT JOIN importacao i ON i.id = il.importacao_id AND i.destino = 'COMPROVANTE'
            LEFT JOIN arquivo a ON a.id = coalesce(v.comprovante_arquivo_id,
                 CASE WHEN i.destino = 'COMPROVANTE' THEN i.arquivo_id END)
            """;
}
