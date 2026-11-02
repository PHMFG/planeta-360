package br.com.rbs.portal.atlas.diagnostico;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL do traço do diagnóstico ({@code atlas_traco}, V10). [ATLAS] Só SQL: quem decide permissão são os services
 * (VerificadorDeAcesso) e quem decide o que entra em {@code dados} é o {@link RegistroDoTraco}. As colunas JSONB entram por
 * {@code CAST(:x AS jsonb)} e saem como texto. Nada aqui abre transação: o {@link RegistroDoTraco} grava numa transação curta e
 * os services de leitura usam a de leitura.
 */
@Repository
public class TracoRepositorio {

    /** Uma etapa a gravar. {@code dadosJson} já vem sanitizado. */
    public record EtapaNova(Long parceriaId, long conversaId, UUID turno, Long mensagemId, int ordem, String etapa, String resultado,
                            OffsetDateTime iniciadaEm, int duracaoMs, String dadosJson) {
    }

    /**
     * Um turno como o banco o resume, com a parceria e o dono da conversa já ligados. {@code parceriaId} e {@code parceria} são nulos
     * no turno de uma conversa sem parceria (o Atlas da RBS, V15): por isso a parceria entra por junção externa. {@code iniciadoEm}
     * e {@code terminadoEm} cobrem as etapas sequenciais (o resumo do raciocínio, em paralelo, e a busca, que está dentro da
     * ferramenta, ficam de fora); {@code resultado} é o da etapa SAIDA (ERRO se o turno não a tem); {@code mensagemId} é a última mensagem do assistente que o turno tocou; os tokens e o custo
     * somam as etapas que os trazem em {@code dados}.
     */
    public record TurnoLido(UUID turno, Long conversaId, Long parceriaId, String parceria, Long usuarioId, String usuarioNome,
                            String usuarioPerfil, OffsetDateTime iniciadoEm, OffsetDateTime terminadoEm, Integer etapas, Long mensagemId,
                            String resultado, Long tokensEntrada, Long tokensSaida, BigDecimal custoUsd) {
    }

    /** Uma etapa como está no banco; {@code dados} é o JSON como texto. */
    public record EtapaLida(UUID turno, Long id, Integer ordem, String etapa, String resultado, OffsetDateTime iniciadaEm,
                            Integer duracaoMs, Long mensagemId, String dados) {
    }

    /** Filtros opcionais da lista de turnos. {@code de} (inclusive) e {@code ateExclusivo} limitam o começo do turno. */
    public record FiltroDeTurnos(Long parceriaId, OffsetDateTime de, OffsetDateTime ateExclusivo, String resultado) {
    }

    /** A latência e os erros de um provedor nas últimas 24 horas, do traço. As latências são das etapas que terminaram bem. */
    public record EstatisticasDoProvedor(Long chamadas, Long erros, Double p50, Double p95) {
    }

    /** O resumo de cada turno: o começo e o fim das etapas sequenciais, o resultado da SAIDA, os tokens e o custo. */
    private static final String TURNOS_ANTES = """
            WITH turnos AS (
                SELECT t.turno, t.conversa_id, t.parceria_id,
                       min(t.iniciada_em) FILTER (WHERE t.etapa NOT IN ('RESUMO', 'BUSCA', 'R3_FONTES', 'V2_COERENCIA', 'LACUNAS')) AS iniciado_em,
                       max(t.iniciada_em + t.duracao_ms * interval '1 millisecond') FILTER (WHERE t.etapa NOT IN ('RESUMO', 'BUSCA', 'R3_FONTES', 'V2_COERENCIA', 'LACUNAS')) AS terminado_em,
                       count(*) AS etapas,
                       max(t.mensagem_id) AS mensagem_id,
                       coalesce((array_agg(t.resultado ORDER BY t.ordem DESC) FILTER (WHERE t.etapa = 'SAIDA'))[1], 'ERRO') AS resultado,
                       coalesce(sum((t.dados ->> 'tokensEntrada')::bigint), 0) AS tokens_entrada,
                       coalesce(sum((t.dados ->> 'tokensSaida')::bigint), 0) AS tokens_saida,
                       coalesce(sum((t.dados ->> 'custoUsd')::numeric), 0) AS custo_usd
                FROM atlas_traco t
            """;

    private static final String TURNOS_DEPOIS = """
                GROUP BY t.turno, t.conversa_id, t.parceria_id
            )
            """;

    private static final String TURNOS_COM_DONO = """
            SELECT x.turno, x.conversa_id, x.parceria_id, p.nome_canonico AS parceria, c.usuario_id, u.nome AS usuario_nome,
                   u.perfil AS usuario_perfil, x.iniciado_em, x.terminado_em, x.etapas, x.mensagem_id, x.resultado,
                   x.tokens_entrada, x.tokens_saida, x.custo_usd
            FROM turnos x
            JOIN atlas_conversa c ON c.id = x.conversa_id
            LEFT JOIN parceria pa ON pa.id = x.parceria_id
            LEFT JOIN patrocinador p ON p.id = pa.patrocinador_id
            JOIN usuario u ON u.id = c.usuario_id
            """;

    private final JdbcClient jdbc;

    public TracoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Grava a etapa e devolve o id dela (para o resumo do raciocínio, que pode ser atualizado depois). */
    public long inserir(EtapaNova etapa) {
        return jdbc.sql("""
                        INSERT INTO atlas_traco (parceria_id, conversa_id, turno, mensagem_id, ordem, etapa, resultado, iniciada_em,
                                                 duracao_ms, dados)
                        VALUES (:parceria, :conversa, :turno, :mensagem, :ordem, :etapa, :resultado, :iniciadaEm, :duracao,
                                CAST(:dados AS jsonb))
                        RETURNING id
                        """)
                .param("parceria", etapa.parceriaId()).param("conversa", etapa.conversaId()).param("turno", etapa.turno())
                .param("mensagem", etapa.mensagemId()).param("ordem", etapa.ordem()).param("etapa", etapa.etapa())
                .param("resultado", etapa.resultado()).param("iniciadaEm", etapa.iniciadaEm()).param("duracao", etapa.duracaoMs())
                .param("dados", etapa.dadosJson())
                .query(Long.class).single();
    }

    /**
     * O turno (UUID, como texto) de cada mensagem do assistente da conversa que deixou traço: é como o item da conversa sabe a que
     * turno do diagnóstico pertence. Mensagem sem traço (a do botão, as de antes do diagnóstico) não aparece.
     */
    public Map<Long, String> turnosDasMensagens(long conversaId) {
        Map<Long, String> turnos = new HashMap<>();
        jdbc.sql("SELECT DISTINCT mensagem_id, CAST(turno AS text) AS turno FROM atlas_traco WHERE conversa_id = :conversa AND mensagem_id IS NOT NULL")
                .param("conversa", conversaId)
                .query((rs, n) -> Map.entry(rs.getLong("mensagem_id"), rs.getString("turno")))
                .list().forEach(e -> turnos.putIfAbsent(e.getKey(), e.getValue()));
        return turnos;
    }

    // ---------------------------------------------------------------------
    // Leitura (rotas 49 a 52)
    // ---------------------------------------------------------------------

    /** Os turnos de uma conversa, do mais antigo para o mais novo. */
    public List<TurnoLido> turnosDaConversa(long conversaId) {
        return jdbc.sql(TURNOS_ANTES + " WHERE t.conversa_id = :conversa " + TURNOS_DEPOIS + TURNOS_COM_DONO
                        + " ORDER BY x.iniciado_em, x.turno")
                .param("conversa", conversaId)
                .query(TurnoLido.class).list();
    }

    /** Um turno, ou nada se o id não existe. */
    public Optional<TurnoLido> turno(UUID turno) {
        return jdbc.sql(TURNOS_ANTES + " WHERE t.turno = :turno " + TURNOS_DEPOIS + TURNOS_COM_DONO)
                .param("turno", turno)
                .query(TurnoLido.class).optional();
    }

    /** Uma página de turnos, do mais novo para o mais antigo. */
    public List<TurnoLido> turnos(FiltroDeTurnos filtro, int pagina, int tamanho) {
        Map<String, Object> parametros = new LinkedHashMap<>();
        String dentro = dentro(filtro, parametros);
        String fora = fora(filtro, parametros);
        parametros.put("limite", tamanho);
        parametros.put("deslocamento", (long) pagina * tamanho);
        return jdbc.sql(TURNOS_ANTES + dentro + TURNOS_DEPOIS + TURNOS_COM_DONO + " WHERE TRUE " + fora
                        + " ORDER BY x.iniciado_em DESC, x.turno LIMIT :limite OFFSET :deslocamento")
                .params(parametros)
                .query(TurnoLido.class).list();
    }

    public long contarTurnos(FiltroDeTurnos filtro) {
        Map<String, Object> parametros = new LinkedHashMap<>();
        String dentro = dentro(filtro, parametros);
        String fora = fora(filtro, parametros);
        return jdbc.sql(TURNOS_ANTES + dentro + TURNOS_DEPOIS + " SELECT count(*) FROM turnos x WHERE TRUE " + fora)
                .params(parametros)
                .query(Long.class).single();
    }

    /** O filtro que vale antes de agrupar: a parceria. */
    private static String dentro(FiltroDeTurnos filtro, Map<String, Object> parametros) {
        if (filtro.parceriaId() == null) {
            return " ";
        }
        parametros.put("parceria", filtro.parceriaId());
        return " WHERE t.parceria_id = :parceria ";
    }

    /** Os filtros que valem sobre o turno já resumido: o resultado e o período do começo dele. */
    private static String fora(FiltroDeTurnos filtro, Map<String, Object> parametros) {
        StringBuilder fora = new StringBuilder();
        if (filtro.resultado() != null) {
            fora.append(" AND x.resultado = :resultado");
            parametros.put("resultado", filtro.resultado());
        }
        if (filtro.de() != null) {
            fora.append(" AND x.iniciado_em >= :de");
            parametros.put("de", filtro.de());
        }
        if (filtro.ateExclusivo() != null) {
            fora.append(" AND x.iniciado_em < :ate");
            parametros.put("ate", filtro.ateExclusivo());
        }
        return fora.toString();
    }

    /**
     * O {@code dados} como a RBS o lê: inteiro (já sanitizado na gravação). O texto cru de um erro (a mensagem da exceção, o corpo
     * da resposta do provedor) é diagnóstico de quem opera a API.
     */
    private static final String DADOS_DA_RBS = "CAST(t.dados AS text)";

    /**
     * O {@code dados} como a marca o lê (rota 49): sem o texto cru de erro. A mensagem de uma exceção pode ser SQL ou outro detalhe
     * interno, e o corpo do erro de um provedor pode trazer o que ele quiser dizer; a marca fica com o nome da exceção e o resto da
     * etapa. {@code ERRO} guarda só {@code excecao} (lista fechada, para um campo novo nunca vazar sem querer); nas outras etapas
     * saem {@code detalhe} (o erro do provedor em {@code LLM_IDA}) e {@code mensagem}. Feito no SQL para os números do JSON
     * ficarem como foram gravados.
     */
    private static final String DADOS_DA_MARCA = """
            CAST(CASE WHEN t.etapa = 'ERRO'
                      THEN jsonb_strip_nulls(jsonb_build_object('excecao', t.dados -> 'excecao'))
                      ELSE t.dados - ARRAY['detalhe', 'mensagem'] END AS text)""";

    /**
     * As etapas destes turnos, na ordem de cada um. {@code paraAMarca}: o {@code dados} sem o texto cru de erro (rota 49); falso, a
     * RBS (rotas 50 e 51).
     */
    public List<EtapaLida> etapasDosTurnos(Collection<UUID> turnos, boolean paraAMarca) {
        if (turnos.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT t.turno, t.id, t.ordem, t.etapa, t.resultado, t.iniciada_em, t.duracao_ms, t.mensagem_id,
                               %s AS dados
                        FROM atlas_traco t WHERE t.turno IN (:turnos) ORDER BY t.turno, t.ordem
                        """.formatted(paraAMarca ? DADOS_DA_MARCA : DADOS_DA_RBS))
                .param("turnos", turnos)
                .query(EtapaLida.class).list();
    }

    /**
     * Chamadas, erros e latência (p50 e p95, em ms) das etapas dadas nas últimas 24 horas. A etapa {@code PULADO} (o ponto do JEV que
     * não chegou a chamar ninguém) não é chamada; a latência é a das que o provedor respondeu ({@code OK}, {@code RECUSADO} ou
     * {@code SOMBRA}).
     */
    public EstatisticasDoProvedor estatisticas(Collection<String> etapas) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE resultado <> 'PULADO') AS chamadas,
                               count(*) FILTER (WHERE resultado = 'ERRO') AS erros,
                               percentile_cont(0.5) WITHIN GROUP (ORDER BY duracao_ms) FILTER (WHERE resultado IN ('OK', 'RECUSADO', 'SOMBRA')) AS p50,
                               percentile_cont(0.95) WITHIN GROUP (ORDER BY duracao_ms) FILTER (WHERE resultado IN ('OK', 'RECUSADO', 'SOMBRA')) AS p95
                        FROM atlas_traco
                        WHERE etapa IN (:etapas) AND iniciada_em >= now() - interval '24 hours'
                        """)
                .param("etapas", etapas)
                .query(EstatisticasDoProvedor.class).single();
    }

    /** O resumo do raciocínio que terminou depois do fim do turno: a linha dele passa de pendente ao resultado de verdade. */
    public void atualizar(long id, String resultado, int duracaoMs, String dadosJson) {
        jdbc.sql("UPDATE atlas_traco SET resultado = :resultado, duracao_ms = :duracao, dados = CAST(:dados AS jsonb) WHERE id = :id")
                .param("resultado", resultado).param("duracao", duracaoMs).param("dados", dadosJson).param("id", id)
                .update();
    }
}
