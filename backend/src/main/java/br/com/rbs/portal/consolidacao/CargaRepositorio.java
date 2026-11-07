package br.com.rbs.portal.consolidacao;

import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.consolidacao.CargaDtos.CargaDto;
import br.com.rbs.portal.consolidacao.CargaDtos.RejeicaoDto;
import br.com.rbs.portal.consolidacao.CargaDtos.UsuarioResumoDto;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL das cargas de planilha (rotas 26 a 28): cria a carga, grava as linhas na área de passagem (stg_veiculacao,
 * stg_pesquisa e stg_plano), chama as funções do banco que validam e inserem sem duplicar, e lê as cargas e as rejeições.
 * Atende o item 5 da Entrega 2. [CARGA] [DUPLICIDADE] Quem rejeita e deduplica é a função do banco; aqui não há essa regra.
 * No banco a veiculação tem tipo MIDIA e o plano comercial PLANO_COMERCIAL; para o contrato da API são VEICULACAO e PLANO.
 */
@Repository
public class CargaRepositorio {

    /**
     * Uma linha da planilha de veiculação já convertida; o que não converteu está nulo (a função do banco rejeita). Os
     * campos "Texto" guardam o texto original só do que não converteu: com eles a rejeição diz o formato esperado.
     */
    record LinhaDeVeiculacao(int linha, String patrocinador, String veiculo, String formato, String espacoPrograma,
                             OffsetDateTime exibidoEm, BigDecimal quantidade, String link, String exibidoEmTexto,
                             String quantidadeTexto, BigDecimal impacto, String impactoTexto, String mes) {
    }

    /** O mesmo para a planilha de pesquisa: o que não converteu está nulo e o texto original vai nos campos "Texto". */
    record LinhaDePesquisa(int linha, String patrocinador, Integer pergunta, String indicador, String alternativa,
                           BigDecimal valor, String unidade, Integer baseRespondentes, String perguntaTexto,
                           String valorTexto, String baseRespondentesTexto) {
    }

    /**
     * Uma linha do plano comercial já convertida; a quantidade que não converteu está nula (a função do banco rejeita) e o
     * texto original dela vai em {@code quantidadeTexto}. {@code fontePagina} fora de 1 a 32767 está nula (a página é opcional).
     */
    record LinhaDePlano(int linha, String patrocinador, String categoria, String bloco, String veiculo, String formato,
                        String espacoPrograma, String janela, String tipoMidia, BigDecimal quantidadePrevista,
                        String quantidadeTexto, String unidade, Short fontePagina) {
    }

    private static final String COLUNAS_DA_CARGA = """
            SELECT c.id,
                   CASE c.tipo WHEN 'MIDIA' THEN 'VEICULACAO' WHEN 'PLANO_COMERCIAL' THEN 'PLANO' ELSE c.tipo END AS tipo, c.evento_id, c.arquivo_id,
                   u.id AS usuario_id, u.nome AS usuario_nome, u.perfil AS usuario_perfil, c.executada_em, c.processada_em,
                   c.linhas_lidas, c.linhas_inseridas, c.linhas_duplicadas, c.linhas_rejeitadas
            FROM carga c JOIN usuario u ON u.id = c.executada_por
            """;

    // Só as cargas de planilha que a API expõe: veiculação (MIDIA), pesquisa e plano comercial. O tipo COMPROVACAO do banco não tem carga.
    private static final String FILTRO = """
            WHERE c.tipo IN ('MIDIA', 'PESQUISA', 'PLANO_COMERCIAL')
              AND (CAST(:tipo AS text) IS NULL OR c.tipo = :tipo)
              AND (CAST(:eventoId AS bigint) IS NULL OR c.evento_id = :eventoId)
            """;

    private final JdbcClient jdbc;
    private final JdbcTemplate lote;

    public CargaRepositorio(JdbcClient jdbc, JdbcTemplate lote) {
        this.jdbc = jdbc;
        this.lote = lote;
    }

    // ---------------------------------------------------------------------
    // Rota 26: criar e processar
    // ---------------------------------------------------------------------

    /** A edição (ano) do evento, ou vazio se o evento não existe. */
    public Optional<Integer> edicaoDoEvento(long eventoId) {
        return jdbc.sql("SELECT edicao FROM evento WHERE id = :id").param("id", eventoId).query(Integer.class).optional();
    }

    /** A pesquisa da edição com aquele escopo (a mais antiga, se houver mais de uma); vazio se a RBS ainda não a abriu. */
    public Optional<Long> pesquisaDaEdicao(long eventoId, String escopo) {
        return jdbc.sql("SELECT id FROM pesquisa WHERE evento_id = :evento AND escopo = :escopo ORDER BY id LIMIT 1")
                .param("evento", eventoId).param("escopo", escopo).query(Long.class).optional();
    }

    /** Cria a carga ainda sem totais; {@code tipo} é o do banco (MIDIA, PESQUISA ou PLANO_COMERCIAL). A função preenche o resto. */
    public long criar(String tipo, long arquivoId, long eventoId, long usuarioId) {
        return jdbc.sql("""
                        INSERT INTO carga (tipo, arquivo_id, evento_id, executada_por)
                        VALUES (:tipo, :arquivo, :evento, :usuario) RETURNING id
                        """)
                .param("tipo", tipo).param("arquivo", arquivoId).param("evento", eventoId).param("usuario", usuarioId)
                .query(Long.class).single();
    }

    /** Grava um lote de linhas em stg_veiculacao, com o número da linha no arquivo. */
    public void gravarVeiculacoes(long cargaId, List<LinhaDeVeiculacao> linhas) {
        lote.batchUpdate("""
                INSERT INTO stg_veiculacao (carga_id, linha, patrocinador, veiculo, formato, espaco_programa, exibido_em,
                                            quantidade, link, exibido_em_texto, quantidade_texto, impacto, impacto_texto, mes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                LinhaDeVeiculacao l = linhas.get(i);
                ps.setLong(1, cargaId);
                ps.setInt(2, l.linha());
                texto(ps, 3, l.patrocinador());
                texto(ps, 4, l.veiculo());
                texto(ps, 5, l.formato());
                texto(ps, 6, l.espacoPrograma());
                if (l.exibidoEm() == null) {
                    ps.setNull(7, Types.TIMESTAMP_WITH_TIMEZONE);
                } else {
                    ps.setObject(7, l.exibidoEm());
                }
                decimal(ps, 8, l.quantidade());
                texto(ps, 9, l.link());
                texto(ps, 10, l.exibidoEmTexto());
                texto(ps, 11, l.quantidadeTexto());
                decimal(ps, 12, l.impacto());
                texto(ps, 13, l.impactoTexto());
                texto(ps, 14, l.mes());
            }

            @Override
            public int getBatchSize() {
                return linhas.size();
            }
        });
    }

    /** Grava um lote de linhas em stg_pesquisa, com o número da linha no arquivo. */
    public void gravarPesquisa(long cargaId, List<LinhaDePesquisa> linhas) {
        lote.batchUpdate("""
                INSERT INTO stg_pesquisa (carga_id, linha, patrocinador, pergunta, indicador, alternativa, valor, unidade,
                                          base_respondentes, pergunta_texto, valor_texto, base_respondentes_texto)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                LinhaDePesquisa l = linhas.get(i);
                ps.setLong(1, cargaId);
                ps.setInt(2, l.linha());
                texto(ps, 3, l.patrocinador());
                inteiro(ps, 4, l.pergunta());
                texto(ps, 5, l.indicador());
                texto(ps, 6, l.alternativa());
                decimal(ps, 7, l.valor());
                texto(ps, 8, l.unidade());
                inteiro(ps, 9, l.baseRespondentes());
                texto(ps, 10, l.perguntaTexto());
                texto(ps, 11, l.valorTexto());
                texto(ps, 12, l.baseRespondentesTexto());
            }

            @Override
            public int getBatchSize() {
                return linhas.size();
            }
        });
    }

    /** Grava um lote de linhas em stg_plano, com o número da linha no arquivo. */
    public void gravarPlano(long cargaId, List<LinhaDePlano> linhas) {
        lote.batchUpdate("""
                INSERT INTO stg_plano (carga_id, linha, patrocinador, categoria, bloco, veiculo, formato, espaco_programa, janela,
                                       tipo_midia, quantidade_prevista, quantidade_texto, unidade, fonte_pagina)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                LinhaDePlano l = linhas.get(i);
                ps.setLong(1, cargaId);
                ps.setInt(2, l.linha());
                texto(ps, 3, l.patrocinador());
                texto(ps, 4, l.categoria());
                texto(ps, 5, l.bloco());
                texto(ps, 6, l.veiculo());
                texto(ps, 7, l.formato());
                texto(ps, 8, l.espacoPrograma());
                texto(ps, 9, l.janela());
                texto(ps, 10, l.tipoMidia());
                decimal(ps, 11, l.quantidadePrevista());
                texto(ps, 12, l.quantidadeTexto());
                texto(ps, 13, l.unidade());
                if (l.fontePagina() == null) {
                    ps.setNull(14, Types.SMALLINT);
                } else {
                    ps.setShort(14, l.fontePagina());
                }
            }

            @Override
            public int getBatchSize() {
                return linhas.size();
            }
        });
    }

    /** fn_carregar_veiculacoes: rejeita, vincula, insere sem duplicar e grava os totais em carga. */
    public void processarVeiculacoes(long cargaId, long eventoId) {
        jdbc.sql("SELECT * FROM fn_carregar_veiculacoes(:carga, :evento)")
                .param("carga", cargaId).param("evento", eventoId).query().listOfRows();
    }

    /** fn_carregar_plano: o mesmo, para as linhas do plano comercial de uma edição (previsto de mídia e de aplicações). */
    public void processarPlano(long cargaId, long eventoId) {
        jdbc.sql("SELECT * FROM fn_carregar_plano(:carga, :evento)")
                .param("carga", cargaId).param("evento", eventoId).query().listOfRows();
    }

    /** fn_carregar_pesquisa: o mesmo, para os resultados da pesquisa de uma edição. */
    public void processarPesquisa(long cargaId, long pesquisaId) {
        jdbc.sql("SELECT * FROM fn_carregar_pesquisa(:carga, :pesquisa)")
                .param("carga", cargaId).param("pesquisa", pesquisaId).query().listOfRows();
    }

    // ---------------------------------------------------------------------
    // Rotas 27 e 28: leitura
    // ---------------------------------------------------------------------

    public Optional<CargaDto> buscar(long id) {
        return jdbc.sql(COLUNAS_DA_CARGA + " WHERE c.id = :id").param("id", id).query(CargaRepositorio::carga).optional();
    }

    /** {@code tipo} do banco (MIDIA, PESQUISA ou PLANO_COMERCIAL) ou nulo; {@code eventoId} nulo = todas as edições. */
    public long contar(String tipo, Long eventoId) {
        return jdbc.sql("SELECT count(*) FROM carga c " + FILTRO).param("tipo", tipo).param("eventoId", eventoId)
                .query(Long.class).single();
    }

    /** Uma página, da carga mais nova para a mais antiga. A ordem é pelo id, que cresce a cada carga. */
    public List<CargaDto> pagina(String tipo, Long eventoId, int pagina, int tamanho) {
        return jdbc.sql(COLUNAS_DA_CARGA + FILTRO + " ORDER BY c.id DESC LIMIT :limite OFFSET :deslocamento")
                .param("tipo", tipo).param("eventoId", eventoId)
                .param("limite", tamanho).param("deslocamento", (long) pagina * tamanho)
                .query(CargaRepositorio::carga).list();
    }

    public boolean existe(long cargaId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM carga WHERE id = :id AND tipo IN ('MIDIA', 'PESQUISA', 'PLANO_COMERCIAL'))")
                .param("id", cargaId).query(Boolean.class).single();
    }

    public long contarRejeicoes(long cargaId) {
        return jdbc.sql("SELECT count(*) FROM carga_rejeicao WHERE carga_id = :id").param("id", cargaId)
                .query(Long.class).single();
    }

    /** Uma página das rejeições, na ordem das linhas do arquivo (o id desempata linhas com mais de um motivo). */
    public List<RejeicaoDto> rejeicoes(long cargaId, int pagina, int tamanho) {
        return jdbc.sql("""
                        SELECT linha, campo, motivo, conteudo FROM carga_rejeicao WHERE carga_id = :id
                        ORDER BY linha, id LIMIT :limite OFFSET :deslocamento
                        """)
                .param("id", cargaId).param("limite", tamanho).param("deslocamento", (long) pagina * tamanho)
                .query(RejeicaoDto.class).list();
    }

    // ---------------------------------------------------------------------
    // Ajudas
    // ---------------------------------------------------------------------

    private static CargaDto carga(ResultSet rs, int numero) throws SQLException {
        return new CargaDto(rs.getLong("id"), rs.getString("tipo"), (Long) rs.getObject("evento_id"),
                (Long) rs.getObject("arquivo_id"),
                new UsuarioResumoDto(rs.getLong("usuario_id"), rs.getString("usuario_nome"), rs.getString("usuario_perfil")),
                Datas.emBrasilia(rs.getObject("executada_em", OffsetDateTime.class)),
                Datas.emBrasilia(rs.getObject("processada_em", OffsetDateTime.class)),
                rs.getInt("linhas_lidas"), rs.getInt("linhas_inseridas"), rs.getInt("linhas_duplicadas"),
                rs.getInt("linhas_rejeitadas"));
    }

    private static void texto(PreparedStatement ps, int posicao, String valor) throws SQLException {
        if (valor == null) {
            ps.setNull(posicao, Types.VARCHAR);
        } else {
            ps.setString(posicao, valor);
        }
    }

    private static void decimal(PreparedStatement ps, int posicao, BigDecimal valor) throws SQLException {
        if (valor == null) {
            ps.setNull(posicao, Types.NUMERIC);
        } else {
            ps.setBigDecimal(posicao, valor);
        }
    }

    private static void inteiro(PreparedStatement ps, int posicao, Integer valor) throws SQLException {
        if (valor == null) {
            ps.setNull(posicao, Types.INTEGER);
        } else {
            ps.setInt(posicao, valor);
        }
    }
}
