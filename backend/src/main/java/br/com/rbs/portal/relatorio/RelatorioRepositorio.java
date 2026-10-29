package br.com.rbs.portal.relatorio;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Consultas e gravações do relatório: situação por parceria, versões, números (relatorio_indicador) e mudança de status.
 * Atende o item 6 da Entrega 2. [RELATORIO] Os números NUNCA são recalculados aqui: a geração é a função fn_gerar_relatorio
 * do banco, que copia as visões para relatorio_indicador. [PERMISSAO] Quem pode ver o quê é decidido no RelatorioService.
 */
@Repository
public class RelatorioRepositorio {

    /** A versão de um relatório sem os números: quem gerou, as datas de cada etapa e o nome do patrocinador. */
    public record CabecalhoDoRelatorio(Long id, Long parceriaId, String patrocinador, Integer versao, String status,
                                       OffsetDateTime geradoEm, Long geradoPorId, String geradoPorNome,
                                       String geradoPorPerfil, OffsetDateTime validadoEm,
                                       OffsetDateTime disponibilizadoEm) {
    }

    /** Um número do relatório já com os dados do catálogo (seção, nome e unidade). */
    public record LinhaDeIndicador(String secao, String codigo, String nome, String recorte, BigDecimal valor,
                                   String unidade, String baseCalculo, boolean disponivel) {
    }

    /**
     * Uma linha da rota 31, ainda sem a montagem do JSON. {@code relatorioId} é nulo quando a parceria não tem versão;
     * os campos "marcaVe" são da versão disponibilizada mais nova e ficam nulos quando nenhuma foi disponibilizada.
     */
    public record LinhaDeSituacao(Long parceriaId, String patrocinador, String cotaCodigo, String cotaNome, Long eventoId,
                                  String eventoNome, Integer edicao, Long relatorioId, Integer versao, String status,
                                  OffsetDateTime geradoEm, OffsetDateTime validadoEm, OffsetDateTime disponibilizadoEm,
                                  int disponiveis, int indisponiveis, Long marcaVeId, Integer marcaVeVersao,
                                  OffsetDateTime marcaVeEm, int faltantes) {
    }

    /** O estado de um relatório com a linha travada (SELECT ... FOR UPDATE), para a mudança de status. */
    public record EstadoDoRelatorio(Long parceriaId, Integer versao, String status) {
    }

    private static final String CABECALHO = """
            SELECT r.id, r.parceria_id, pat.nome_canonico AS patrocinador, r.versao, r.status, r.gerado_em,
                   g.id AS gerado_por_id, g.nome AS gerado_por_nome, g.perfil AS gerado_por_perfil,
                   r.validado_em, r.disponibilizado_em
            FROM relatorio r
            JOIN usuario g ON g.id = r.gerado_por
            JOIN parceria pa ON pa.id = r.parceria_id
            JOIN patrocinador pat ON pat.id = pa.patrocinador_id
            """;

    private final JdbcClient jdbc;

    public RelatorioRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------------
    // Rotas 31 e 32: a situação de cada parceria
    // ---------------------------------------------------------------------

    /** A edição mais nova do festival: o padrão do filtro eventoId. */
    public Optional<Long> eventoMaisNovo() {
        return jdbc.sql("SELECT id FROM evento ORDER BY edicao DESC, id DESC LIMIT 1").query(Long.class).optional();
    }

    /**
     * Uma linha por parceria da edição, com a versão MAIS NOVA do relatório (LATERAL com ORDER BY versao DESC), quantos números
     * dela estão disponíveis e indisponíveis (o indicador do catálogo sem linha na versão conta como indisponível, como na
     * leitura do relatório), a versão que a marca vê (a disponibilizada mais nova, que pode ser anterior à
     * mais nova) e quantos itens a visão vw_faltantes aponta. [RELATORIO] "Sem relatório" é a situação de quem não tem
     * nenhuma versão.
     */
    public List<LinhaDeSituacao> situacoes(long eventoId, String statusFiltro) {
        return jdbc.sql("""
                        SELECT pa.id AS parceria_id, pat.nome_canonico AS patrocinador, ct.codigo AS cota_codigo,
                               ct.nome AS cota_nome, e.id AS evento_id, e.nome AS evento_nome, e.edicao,
                               r.id AS relatorio_id, r.versao, coalesce(r.status, 'SEM_RELATORIO') AS status,
                               r.gerado_em, r.validado_em, r.disponibilizado_em,
                               coalesce(ri.disponiveis, 0)::int AS disponiveis,
                               coalesce(ri.indisponiveis, 0)::int AS indisponiveis,
                               d.id AS marca_ve_id, d.versao AS marca_ve_versao, d.disponibilizado_em AS marca_ve_em,
                               coalesce(f.itens, 0)::int AS faltantes
                        FROM parceria pa
                        JOIN patrocinador pat ON pat.id = pa.patrocinador_id
                        JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id
                        JOIN evento e ON e.id = pa.evento_id
                        LEFT JOIN LATERAL (SELECT * FROM relatorio WHERE parceria_id = pa.id
                                           ORDER BY versao DESC LIMIT 1) r ON TRUE
                        LEFT JOIN LATERAL (SELECT id, versao, disponibilizado_em FROM relatorio
                                           WHERE parceria_id = pa.id AND status = 'DISPONIBILIZADO'
                                           ORDER BY versao DESC LIMIT 1) d ON TRUE
                        LEFT JOIN LATERAL (
                               SELECT (SELECT count(*) FROM relatorio_indicador WHERE relatorio_id = r.id AND disponivel) AS disponiveis,
                                      (SELECT count(*) FROM relatorio_indicador WHERE relatorio_id = r.id AND NOT disponivel)
                                      + (SELECT count(*) FROM indicador i WHERE NOT EXISTS (
                                             SELECT 1 FROM relatorio_indicador x
                                             WHERE x.relatorio_id = r.id AND x.indicador_codigo = i.codigo)) AS indisponiveis
                               WHERE r.id IS NOT NULL) ri ON TRUE
                        LEFT JOIN (SELECT parceria_id, count(*) AS itens FROM vw_faltantes GROUP BY parceria_id) f
                               ON f.parceria_id = pa.id
                        WHERE pa.evento_id = :eventoId
                          AND (CAST(:status AS text) IS NULL OR coalesce(r.status, 'SEM_RELATORIO') = CAST(:status AS text))
                        ORDER BY pat.nome_canonico, pa.id
                        """)
                .param("eventoId", eventoId)
                .param("status", statusFiltro)
                .query(LinhaDeSituacao.class)
                .list();
    }

    // ---------------------------------------------------------------------
    // Rotas 33 a 36: uma versão do relatório
    // ---------------------------------------------------------------------

    public Optional<CabecalhoDoRelatorio> buscar(long relatorioId) {
        return jdbc.sql(CABECALHO + "WHERE r.id = :id")
                .param("id", relatorioId)
                .query(CabecalhoDoRelatorio.class)
                .optional();
    }

    /** A versão mais nova da parceria, de qualquer status (o que a RBS vê por padrão). */
    public Optional<CabecalhoDoRelatorio> maisNovo(long parceriaId) {
        return jdbc.sql(CABECALHO + "WHERE r.parceria_id = :parceriaId ORDER BY r.versao DESC LIMIT 1")
                .param("parceriaId", parceriaId)
                .query(CabecalhoDoRelatorio.class)
                .optional();
    }

    /** [PERMISSAO] A versão disponibilizada mais nova: a única que o patrocinador enxerga. */
    public Optional<CabecalhoDoRelatorio> maisNovoDisponibilizado(long parceriaId) {
        return jdbc.sql(CABECALHO + "WHERE r.parceria_id = :parceriaId AND r.status = 'DISPONIBILIZADO' "
                        + "ORDER BY r.versao DESC LIMIT 1")
                .param("parceriaId", parceriaId)
                .query(CabecalhoDoRelatorio.class)
                .optional();
    }

    public Optional<CabecalhoDoRelatorio> porVersao(long parceriaId, int versao) {
        return jdbc.sql(CABECALHO + "WHERE r.parceria_id = :parceriaId AND r.versao = :versao")
                .param("parceriaId", parceriaId)
                .param("versao", versao)
                .query(CabecalhoDoRelatorio.class)
                .optional();
    }

    /**
     * Os números da versão, com seção, nome e unidade do catálogo. [RELATORIO] Parte do catálogo (tabela indicador), não das
     * linhas gravadas: o indicador sem linha nesta versão sai como indisponível (sem recorte, valor nulo), então a leitura
     * sempre traz as 6 seções e os 17 indicadores, até de versões geradas antes de a geração registrar todos eles. A ordem
     * é montada pelo RelatorioService.
     */
    public List<LinhaDeIndicador> indicadores(long relatorioId) {
        return jdbc.sql("""
                        SELECT i.secao, i.codigo, i.nome, coalesce(ri.recorte, '') AS recorte, ri.valor, i.unidade,
                               CASE WHEN ri.indicador_codigo IS NULL THEN 'sem registro na base' ELSE ri.base_calculo END AS base_calculo,
                               coalesce(ri.disponivel, FALSE) AS disponivel
                        FROM indicador i
                        LEFT JOIN relatorio_indicador ri ON ri.indicador_codigo = i.codigo AND ri.relatorio_id = :relatorioId
                        """)
                .param("relatorioId", relatorioId)
                .query(LinhaDeIndicador.class)
                .list();
    }

    /**
     * [RELATORIO] Gera uma nova versão pela função do banco (a parceria é travada lá dentro, então duas gerações ao mesmo
     * tempo ganham versões diferentes). A própria função grava o GERACAO_RELATORIO no histórico, com a versão e o autor.
     */
    public long gerar(long parceriaId, long usuarioId) {
        return jdbc.sql("SELECT fn_gerar_relatorio(:parceriaId, :usuarioId)")
                .param("parceriaId", parceriaId)
                .param("usuarioId", usuarioId)
                .query(Long.class)
                .single();
    }

    /** Trava a linha do relatório até o fim da transação, para o status lido ser o mesmo que será alterado. */
    public Optional<EstadoDoRelatorio> travar(long relatorioId) {
        return jdbc.sql("SELECT parceria_id, versao, status FROM relatorio WHERE id = :id FOR UPDATE")
                .param("id", relatorioId)
                .query(EstadoDoRelatorio.class)
                .optional();
    }

    /**
     * [RELATORIO] Muda o status e grava quem fez e quando na etapa certa. A ORDEM (RASCUNHO, VALIDADO, DISPONIBILIZADO, sem
     * pular nem voltar) não é conferida aqui: quem a impõe é o gatilho rn_relatorio_ordem_status do banco, que recusa o
     * resto com a frase pronta. Pular direto para DISPONIBILIZADO recusa antes de qualquer CHECK, porque o gatilho roda primeiro.
     * Pedir o status que o relatório já tem não altera nada (AND status <> :status): quem validou, e quando, fica como estava.
     */
    public void atualizarStatus(long relatorioId, String novoStatus, long usuarioId) {
        jdbc.sql("""
                        UPDATE relatorio SET status = :status,
                               validado_por = CASE WHEN :status = 'VALIDADO' THEN CAST(:usuarioId AS bigint) ELSE validado_por END,
                               validado_em = CASE WHEN :status = 'VALIDADO' THEN now() ELSE validado_em END,
                               disponibilizado_por = CASE WHEN :status = 'DISPONIBILIZADO' THEN CAST(:usuarioId AS bigint)
                                                          ELSE disponibilizado_por END,
                               disponibilizado_em = CASE WHEN :status = 'DISPONIBILIZADO' THEN now() ELSE disponibilizado_em END
                        WHERE id = :id AND status <> :status
                        """)
                .param("status", novoStatus)
                .param("usuarioId", usuarioId)
                .param("id", relatorioId)
                .update();
    }
}
