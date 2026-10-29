package br.com.rbs.portal.materiais;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL de leitura do histórico (rotas 20, 21 e 22). Atende os itens 4 e 5 da Entrega 2. A gravação é do HistoricoService, no
 * pacote comum. [HISTORICO] A tabela só aceita INSERT; aqui só há SELECT. O ip é do tipo inet e sai com host(), que tira a máscara.
 */
@Repository
public class HistoricoConsultaRepositorio {

    /**
     * Filtros opcionais. {@code acoes} vazio = qualquer ação. {@code de} (inclusive) e {@code ateExclusivo} (o dia seguinte
     * ao filtro "ate", à meia-noite de Brasília) limitam {@code ocorrido_em}. {@code semAcessos} esconde LOGIN, LOGIN_FALHOU e
     * LOGOUT: eles são da conta, não da parceria, e só aparecem no histórico geral. {@code semEtapasInternas} esconde
     * GERACAO_RELATORIO e VALIDACAO: são etapas de trabalho da RBS (a marca só deve ver a DISPONIBILIZACAO).
     */
    public record Filtro(Long parceriaId, Long usuarioId, List<String> acoes, String entidade, OffsetDateTime de,
                         OffsetDateTime ateExclusivo, String texto, boolean semAcessos, boolean semEtapasInternas) {
    }

    /** Uma linha do histórico como o banco entrega, com a parceria e o usuário já ligados pelo nome. */
    public record Linha(Long id, Long parceriaId, String patrocinador, String acao, String entidade, Long entidadeId,
                        String valorAnterior, String valorNovo, String detalhe, Long usuarioId, String usuarioNome,
                        String usuarioPerfil, OffsetDateTime ocorridoEm, String ip, String userAgent) {
    }

    private static final String DE = """
            FROM historico h
            LEFT JOIN usuario u ON u.id = h.usuario_id
            LEFT JOIN parceria pa ON pa.id = h.parceria_id
            LEFT JOIN patrocinador pat ON pat.id = pa.patrocinador_id
            WHERE TRUE""";

    private static final String COLUNAS = """
            SELECT h.id, h.parceria_id, pat.nome_canonico AS patrocinador, h.acao, h.entidade, h.entidade_id,
                   h.valor_anterior, h.valor_novo, h.detalhe, u.id AS usuario_id, u.nome AS usuario_nome,
                   u.perfil AS usuario_perfil, h.ocorrido_em, host(h.ip) AS ip, h.user_agent
            """;

    private static final String ORDEM = " ORDER BY h.ocorrido_em DESC, h.id DESC";

    private final JdbcClient jdbc;

    public HistoricoConsultaRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long contar(Filtro filtro) {
        Map<String, Object> parametros = new LinkedHashMap<>();
        String onde = onde(filtro, parametros);
        return jdbc.sql("SELECT count(*) " + DE + onde).params(parametros).query(Long.class).single();
    }

    /** Uma página, da ação mais nova para a mais antiga. */
    public List<Linha> pagina(Filtro filtro, int pagina, int tamanho) {
        Map<String, Object> parametros = new LinkedHashMap<>();
        String onde = onde(filtro, parametros);
        parametros.put("limite", tamanho);
        parametros.put("deslocamento", (long) pagina * tamanho);
        return jdbc.sql(COLUNAS + DE + onde + ORDEM + " LIMIT :limite OFFSET :deslocamento")
                .params(parametros).query(Linha.class).list();
    }

    /** Todas as linhas do filtro, para o CSV (quem chama já conferiu o limite de linhas com {@link #contar}). */
    public List<Linha> todas(Filtro filtro) {
        Map<String, Object> parametros = new LinkedHashMap<>();
        String onde = onde(filtro, parametros);
        return jdbc.sql(COLUNAS + DE + onde + ORDEM).params(parametros).query(Linha.class).list();
    }

    /** Monta o WHERE só com os filtros que vieram. O texto entra por position(), que não trata % e _ como curingas. */
    private static String onde(Filtro f, Map<String, Object> parametros) {
        StringBuilder onde = new StringBuilder();
        if (f.parceriaId() != null) {
            onde.append(" AND h.parceria_id = :parceriaId");
            parametros.put("parceriaId", f.parceriaId());
        }
        if (f.usuarioId() != null) {
            onde.append(" AND h.usuario_id = :usuarioId");
            parametros.put("usuarioId", f.usuarioId());
        }
        if (!f.acoes().isEmpty()) {
            onde.append(" AND h.acao IN (:acoes)");
            parametros.put("acoes", f.acoes());
        }
        if (f.semAcessos()) {
            onde.append(" AND h.acao NOT IN ('LOGIN', 'LOGIN_FALHOU', 'LOGOUT')");
        }
        if (f.semEtapasInternas()) {
            onde.append(" AND h.acao NOT IN ('GERACAO_RELATORIO', 'VALIDACAO')");
        }
        if (f.entidade() != null) {
            onde.append(" AND h.entidade = :entidade");
            parametros.put("entidade", f.entidade());
        }
        if (f.de() != null) {
            onde.append(" AND h.ocorrido_em >= :de");
            parametros.put("de", f.de());
        }
        if (f.ateExclusivo() != null) {
            onde.append(" AND h.ocorrido_em < :ate");
            parametros.put("ate", f.ateExclusivo());
        }
        if (f.texto() != null) {
            onde.append(" AND (position(lower(:texto) IN lower(coalesce(h.detalhe, ''))) > 0"
                    + " OR position(lower(:texto) IN lower(coalesce(h.valor_anterior, ''))) > 0"
                    + " OR position(lower(:texto) IN lower(coalesce(h.valor_novo, ''))) > 0)");
            parametros.put("texto", f.texto());
        }
        return onde.toString();
    }
}
