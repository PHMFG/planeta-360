package br.com.rbs.portal.atlas;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL do Atlas: a conversa e o turno, as mensagens no formato de chat, o relatório, o cartão de perguntas, as contas e a trilha
 * das ferramentas. [ATLAS] Só SQL: quem decide permissão é o service (VerificadorDeAcesso) e quem decide a regra do relatório é o
 * RelatorioAtlasService. [ISOLAMENTO] Toda consulta de relatório e de cartão traz a parceria e o usuário junto, para o service
 * conferir sem uma segunda ida ao banco. [HISTORICO] As colunas JSONB entram por {@code CAST(:x AS jsonb)} e saem como texto.
 */
@Repository
public class AtlasRepositorio {

    /** A conversa, com o dono e o turno em andamento (nulo = livre). */
    public record LinhaDeConversa(Long id, Long parceriaId, Long usuarioId, OffsetDateTime criadaEm, OffsetDateTime turnoDesde) {
    }

    /** Uma conversa da lista da rota 43: quando foi mexida pela última vez e o relatório mais novo dela, se houver. */
    public record LinhaDeConversaResumo(Long id, OffsetDateTime criadaEm, OffsetDateTime atualizadaEm, Long relatorioId,
                                        String relatorioStatus) {
    }

    /**
     * Uma mensagem do histórico. {@code toolCalls} é o JSON dos pedidos de ferramenta do assistente (ou nulo) e
     * {@code reasoningContent} o raciocínio que o modelo mandou com a mensagem, se mandou (volta ao modelo na retomada).
     */
    public record LinhaDeMensagem(Long id, Integer ordem, String papel, String conteudo, String toolCalls, String toolCallId,
                                  OffsetDateTime criadaEm, String reasoningContent) {
    }

    /**
     * Um relatório com o autor e o patrocinador já resolvidos. {@code especificacao} e {@code snapshot} são o JSON como texto.
     */
    public record LinhaDeRelatorio(Long id, Long parceriaId, Long conversaId, String modelo, String status, String especificacao,
                                   Integer versaoEspecificacao, String snapshot, Long criadoPor, String criadoPorNome,
                                   String criadoPorPerfil, OffsetDateTime criadoEm, OffsetDateTime finalizadoEm,
                                   String patrocinador) {
    }

    /** Um relatório da lista da rota 48. */
    public record LinhaDeRelatorioResumo(Long id, String modelo, String titulo, String status, Long criadoPor, String criadoPorNome,
                                         String criadoPorPerfil, OffsetDateTime criadoEm, OffsetDateTime finalizadoEm) {
    }

    /** Um cartão com a conversa e o dono dela, para conferir o acesso sem outra consulta. {@code campos} é o JSON das chaves. */
    public record LinhaDeSolicitacao(Long id, Long relatorioId, Long parceriaId, Long conversaId, Long usuarioId, String toolCallId,
                                     String mensagem, String campos, String status, OffsetDateTime criadaEm,
                                     OffsetDateTime respondidaEm) {
    }

    /** Um relatório parado há tempo demais, a descartar. */
    public record Parado(Long relatorioId, Long conversaId) {
    }

    private static final String RELATORIO = """
            SELECT r.id, r.parceria_id, r.conversa_id, r.modelo, r.status, CAST(r.especificacao AS text) AS especificacao,
                   r.versao_especificacao, CAST(r.snapshot AS text) AS snapshot, r.criado_por, u.nome AS criado_por_nome,
                   u.perfil AS criado_por_perfil, r.criado_em, r.finalizado_em, p.nome_canonico AS patrocinador
            FROM atlas_relatorio r
            JOIN usuario u ON u.id = r.criado_por
            JOIN parceria pa ON pa.id = r.parceria_id
            JOIN patrocinador p ON p.id = pa.patrocinador_id
            """;

    private static final String SOLICITACAO = """
            SELECT s.id, s.relatorio_id, s.parceria_id, r.conversa_id, c.usuario_id, s.tool_call_id, s.mensagem,
                   CAST(s.campos AS text) AS campos, s.status, s.criada_em, s.respondida_em
            FROM atlas_solicitacao s
            JOIN atlas_relatorio r ON r.id = s.relatorio_id
            JOIN atlas_conversa c ON c.id = r.conversa_id
            """;

    private final JdbcClient jdbc;

    public AtlasRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------------
    // Conversa e turno
    // ---------------------------------------------------------------------

    public long criarConversa(long parceriaId, long usuarioId) {
        return jdbc.sql("INSERT INTO atlas_conversa (parceria_id, usuario_id) VALUES (:parceria, :usuario) RETURNING id")
                .param("parceria", parceriaId).param("usuario", usuarioId)
                .query(Long.class).single();
    }

    public Optional<LinhaDeConversa> conversa(long conversaId) {
        return jdbc.sql("SELECT id, parceria_id, usuario_id, criada_em, turno_desde FROM atlas_conversa WHERE id = :id")
                .param("id", conversaId)
                .query(LinhaDeConversa.class).optional();
    }

    /**
     * Começa o turno da conversa: só consegue se ninguém está num turno, ou se o turno parado passou de 5 minutos (travou e a
     * trava venceu). [ATLAS] Devolve falso quando outro turno está em andamento. Uma instrução só: o próprio banco decide quem
     * chega primeiro.
     */
    public boolean iniciarTurno(long conversaId) {
        return jdbc.sql("""
                        UPDATE atlas_conversa SET turno_desde = now()
                        WHERE id = :id AND (turno_desde IS NULL OR turno_desde < now() - interval '5 minutes')
                        """)
                .param("id", conversaId)
                .update() == 1;
    }

    /** Termina o turno, com ou sem erro. */
    public void encerrarTurno(long conversaId) {
        jdbc.sql("UPDATE atlas_conversa SET turno_desde = NULL WHERE id = :id").param("id", conversaId).update();
    }

    /** Trava a linha da conversa até o fim da transação: quem grava mensagem espera a vez, e a {@code ordem} não colide. */
    public void travarConversa(long conversaId) {
        jdbc.sql("SELECT id FROM atlas_conversa WHERE id = :id FOR UPDATE").param("id", conversaId).query(Long.class).optional();
    }

    /** As conversas de uma pessoa nesta parceria, da mexida há menos tempo para a mais antiga. */
    public List<LinhaDeConversaResumo> conversasDoUsuario(long parceriaId, long usuarioId, int limite) {
        return jdbc.sql("""
                        SELECT c.id, c.criada_em,
                               greatest(c.criada_em, coalesce((SELECT max(m.criada_em) FROM atlas_mensagem m WHERE m.conversa_id = c.id),
                                                              c.criada_em)) AS atualizada_em,
                               r.id AS relatorio_id, r.status AS relatorio_status
                        FROM atlas_conversa c
                        LEFT JOIN LATERAL (SELECT id, status FROM atlas_relatorio WHERE conversa_id = c.id
                                           ORDER BY id DESC LIMIT 1) r ON TRUE
                        WHERE c.parceria_id = :parceria AND c.usuario_id = :usuario
                        ORDER BY atualizada_em DESC, c.id DESC
                        LIMIT :limite
                        """)
                .param("parceria", parceriaId).param("usuario", usuarioId).param("limite", limite)
                .query(LinhaDeConversaResumo.class).list();
    }

    /** Os ids das conversas de uma pessoa nesta parceria (para o descarte). */
    public List<Long> idsDasConversasDoUsuario(long parceriaId, long usuarioId) {
        return jdbc.sql("SELECT id FROM atlas_conversa WHERE parceria_id = :parceria AND usuario_id = :usuario")
                .param("parceria", parceriaId).param("usuario", usuarioId)
                .query(Long.class).list();
    }

    // ---------------------------------------------------------------------
    // Mensagens
    // ---------------------------------------------------------------------

    /** A ordem da última mensagem da conversa (0 se não tem nenhuma). */
    public int ultimaOrdem(long conversaId) {
        return jdbc.sql("SELECT coalesce(max(ordem), 0) FROM atlas_mensagem WHERE conversa_id = :id")
                .param("id", conversaId)
                .query(Integer.class).single();
    }

    /** Grava a mensagem na próxima ordem da conversa e devolve o id. Chamar dentro da transação, com a conversa travada. */
    public long inserirMensagem(long conversaId, String papel, String conteudo, String toolCallsJson, String toolCallId,
                                String reasoningContent) {
        int ordem = ultimaOrdem(conversaId) + 1;
        return jdbc.sql("""
                        INSERT INTO atlas_mensagem (conversa_id, ordem, papel, conteudo, tool_calls, tool_call_id, reasoning_content)
                        VALUES (:conversa, :ordem, :papel, :conteudo, CAST(:calls AS jsonb), :toolCallId, :raciocinio) RETURNING id
                        """)
                .param("conversa", conversaId).param("ordem", ordem).param("papel", papel).param("conteudo", conteudo)
                .param("calls", toolCallsJson).param("toolCallId", toolCallId).param("raciocinio", reasoningContent)
                .query(Long.class).single();
    }

    public List<LinhaDeMensagem> mensagens(long conversaId) {
        return jdbc.sql("""
                        SELECT id, ordem, papel, conteudo, CAST(tool_calls AS text) AS tool_calls, tool_call_id, criada_em,
                               reasoning_content
                        FROM atlas_mensagem WHERE conversa_id = :id ORDER BY ordem
                        """)
                .param("id", conversaId)
                .query(LinhaDeMensagem.class).list();
    }

    // ---------------------------------------------------------------------
    // Trilha das ferramentas
    // ---------------------------------------------------------------------

    /** Registra uma ferramenta que rodou (ou que ficou esperando, com {@code resultadoJson} nulo). */
    public void registrarChamada(long mensagemId, String ferramenta, String argumentosJson, String resultadoJson, String erro,
                                 Integer duracaoMs) {
        jdbc.sql("""
                        INSERT INTO atlas_tool_call (mensagem_id, ferramenta, argumentos, resultado, erro, duracao_ms)
                        VALUES (:mensagem, :ferramenta, CAST(:argumentos AS jsonb), CAST(:resultado AS jsonb), :erro, :duracao)
                        """)
                .param("mensagem", mensagemId).param("ferramenta", ferramenta).param("argumentos", argumentosJson)
                .param("resultado", resultadoJson).param("erro", erro).param("duracao", duracaoMs)
                .update();
    }

    /** Põe o resultado numa ferramenta que esperava (o cartão respondido), achada pelo pedido que a abriu. */
    public void concluirChamada(long conversaId, String toolCallId, String ferramenta, String resultadoJson) {
        jdbc.sql("""
                        UPDATE atlas_tool_call SET resultado = CAST(:resultado AS jsonb)
                        WHERE id = (SELECT t.id FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id
                                    WHERE m.conversa_id = :conversa AND m.papel = 'assistant' AND t.ferramenta = :ferramenta
                                      AND t.resultado IS NULL AND m.tool_calls @> CAST(:filtro AS jsonb)
                                    ORDER BY t.id DESC LIMIT 1)
                        """)
                .param("resultado", resultadoJson).param("conversa", conversaId).param("ferramenta", ferramenta)
                .param("filtro", "[{\"id\":" + AtlasJson.escrever(toolCallId) + "}]")
                .update();
    }

    // ---------------------------------------------------------------------
    // Relatório
    // ---------------------------------------------------------------------

    public Optional<LinhaDeRelatorio> relatorio(long relatorioId) {
        return jdbc.sql(RELATORIO + " WHERE r.id = :id")
                .param("id", relatorioId)
                .query(LinhaDeRelatorio.class).optional();
    }

    /** O mesmo, travando a linha do relatório até o fim da transação. */
    public Optional<LinhaDeRelatorio> travarRelatorio(long relatorioId) {
        return jdbc.sql(RELATORIO + " WHERE r.id = :id FOR UPDATE OF r")
                .param("id", relatorioId)
                .query(LinhaDeRelatorio.class).optional();
    }

    /** O relatório em montagem (ou esperando o cartão) da conversa, se houver: no máximo um. */
    public Optional<LinhaDeRelatorio> relatorioAtivoDaConversa(long conversaId) {
        return jdbc.sql(RELATORIO + " WHERE r.conversa_id = :id AND r.status IN ('MONTANDO','AGUARDANDO_RESPOSTA')")
                .param("id", conversaId)
                .query(LinhaDeRelatorio.class).optional();
    }

    public long criarRelatorio(long parceriaId, long conversaId, String modelo, String especificacaoJson, long usuarioId) {
        return jdbc.sql("""
                        INSERT INTO atlas_relatorio (parceria_id, conversa_id, modelo, status, especificacao, criado_por)
                        VALUES (:parceria, :conversa, :modelo, 'MONTANDO', CAST(:especificacao AS jsonb), :usuario) RETURNING id
                        """)
                .param("parceria", parceriaId).param("conversa", conversaId).param("modelo", modelo)
                .param("especificacao", especificacaoJson).param("usuario", usuarioId)
                .query(Long.class).single();
    }

    /** Grava a especificação nova se a versão ainda é a que quem editou leu (trava otimista). Falso = alguém passou na frente. */
    public boolean atualizarEspecificacao(long relatorioId, String especificacaoJson, int versaoEsperada) {
        return jdbc.sql("""
                        UPDATE atlas_relatorio SET especificacao = CAST(:especificacao AS jsonb),
                                                   versao_especificacao = versao_especificacao + 1
                        WHERE id = :id AND versao_especificacao = :versao
                        """)
                .param("especificacao", especificacaoJson).param("id", relatorioId).param("versao", versaoEsperada)
                .update() == 1;
    }

    public void mudarStatus(long relatorioId, String status) {
        jdbc.sql("UPDATE atlas_relatorio SET status = :status WHERE id = :id").param("status", status).param("id", relatorioId).update();
    }

    /** A saída do relatório: snapshot, finalizado_em e status PRONTO na mesma instrução (o gatilho exige que seja uma só). */
    public boolean finalizarRelatorio(long relatorioId, String snapshotJson) {
        return jdbc.sql("""
                        UPDATE atlas_relatorio SET status = 'PRONTO', snapshot = CAST(:snapshot AS jsonb), finalizado_em = now()
                        WHERE id = :id AND status = 'MONTANDO'
                        """)
                .param("snapshot", snapshotJson).param("id", relatorioId)
                .update() == 1;
    }

    /** Os relatórios da parceria (todos os usuários dela), do mais novo para o mais antigo. */
    public List<LinhaDeRelatorioResumo> relatoriosDaParceria(long parceriaId) {
        return jdbc.sql("""
                        SELECT r.id, r.modelo, r.especificacao ->> 'titulo' AS titulo, r.status, r.criado_por,
                               u.nome AS criado_por_nome, u.perfil AS criado_por_perfil, r.criado_em, r.finalizado_em
                        FROM atlas_relatorio r JOIN usuario u ON u.id = r.criado_por
                        WHERE r.parceria_id = :parceria
                        ORDER BY r.criado_em DESC, r.id DESC
                        """)
                .param("parceria", parceriaId)
                .query(LinhaDeRelatorioResumo.class).list();
    }

    /**
     * Os relatórios em montagem ou esperando cartão, destas conversas, sem nenhuma mensagem nova há mais de {@code dias} dias
     * (a última atividade é a mensagem mais nova da conversa, ou a criação do relatório). Trava as linhas; as que outro pedido
     * está mexendo ficam de fora (não estão paradas).
     */
    public List<Parado> relatoriosParados(List<Long> conversaIds, int dias) {
        if (conversaIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT r.id AS relatorio_id, r.conversa_id
                        FROM atlas_relatorio r
                        WHERE r.conversa_id IN (:conversas) AND r.status IN ('MONTANDO','AGUARDANDO_RESPOSTA')
                          AND greatest(r.criado_em, coalesce((SELECT max(m.criada_em) FROM atlas_mensagem m
                                                              WHERE m.conversa_id = r.conversa_id), r.criado_em))
                              < now() - make_interval(days => :dias)
                        ORDER BY r.id
                        FOR UPDATE OF r SKIP LOCKED
                        """)
                .param("conversas", conversaIds).param("dias", dias)
                .query(Parado.class).list();
    }

    // ---------------------------------------------------------------------
    // Cartão de perguntas
    // ---------------------------------------------------------------------

    public long criarSolicitacao(long relatorioId, long parceriaId, String toolCallId, String mensagem, String camposJson) {
        return jdbc.sql("""
                        INSERT INTO atlas_solicitacao (relatorio_id, parceria_id, tool_call_id, mensagem, campos, status)
                        VALUES (:relatorio, :parceria, :toolCallId, :mensagem, CAST(:campos AS jsonb), 'PENDENTE') RETURNING id
                        """)
                .param("relatorio", relatorioId).param("parceria", parceriaId).param("toolCallId", toolCallId)
                .param("mensagem", mensagem).param("campos", camposJson)
                .query(Long.class).single();
    }

    public Optional<LinhaDeSolicitacao> solicitacao(long solicitacaoId) {
        return jdbc.sql(SOLICITACAO + " WHERE s.id = :id")
                .param("id", solicitacaoId)
                .query(LinhaDeSolicitacao.class).optional();
    }

    /** Trava a linha do cartão até o fim da transação (a do relatório já deve estar travada por quem chama). */
    public Optional<LinhaDeSolicitacao> travarSolicitacao(long solicitacaoId) {
        return jdbc.sql(SOLICITACAO + " WHERE s.id = :id FOR UPDATE OF s")
                .param("id", solicitacaoId)
                .query(LinhaDeSolicitacao.class).optional();
    }

    /** Todos os cartões da conversa, do mais antigo para o mais novo. */
    public List<LinhaDeSolicitacao> solicitacoesDaConversa(long conversaId) {
        return jdbc.sql(SOLICITACAO + " WHERE r.conversa_id = :id ORDER BY s.id")
                .param("id", conversaId)
                .query(LinhaDeSolicitacao.class).list();
    }

    public List<LinhaDeSolicitacao> solicitacoesDoRelatorio(long relatorioId) {
        return jdbc.sql(SOLICITACAO + " WHERE s.relatorio_id = :id ORDER BY s.id")
                .param("id", relatorioId)
                .query(LinhaDeSolicitacao.class).list();
    }

    public void responderSolicitacao(long solicitacaoId, String status) {
        jdbc.sql("UPDATE atlas_solicitacao SET status = :status, respondida_em = now() WHERE id = :id")
                .param("status", status).param("id", solicitacaoId)
                .update();
    }

    // ---------------------------------------------------------------------
    // Contas
    // ---------------------------------------------------------------------

    public void inserirCalculo(long relatorioId, String nome, String script, String variaveisJson, BigDecimal resultado,
                               String unidade) {
        jdbc.sql("""
                        INSERT INTO atlas_calculo (relatorio_id, nome, script, variaveis, resultado, unidade)
                        VALUES (:relatorio, :nome, :script, CAST(:variaveis AS jsonb), :resultado, :unidade)
                        """)
                .param("relatorio", relatorioId).param("nome", nome).param("script", script).param("variaveis", variaveisJson)
                .param("resultado", resultado).param("unidade", unidade)
                .update();
    }

    // ---------------------------------------------------------------------
    // Apoio
    // ---------------------------------------------------------------------

    /** O nome da marca da parceria, como o prompt do modelo o recebe (o nome, nunca o id). */
    public String nomeDaMarca(long parceriaId) {
        return jdbc.sql("SELECT p.nome_canonico FROM parceria pa JOIN patrocinador p ON p.id = pa.patrocinador_id WHERE pa.id = :id")
                .param("id", parceriaId)
                .query(String.class).single();
    }

    /** O código da cota da parceria (MASTER, SUBCOTA_G ou SUBCOTA). */
    public String codigoDaCota(long parceriaId) {
        return jdbc.sql("SELECT ct.codigo FROM parceria pa JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id WHERE pa.id = :id")
                .param("id", parceriaId)
                .query(String.class).single();
    }

    /**
     * Os números no padrão do portal, pela mesma função do banco que o relatório oficial usa ({@code fn_numero_br}: milhar com
     * ponto, decimal com vírgula, sem zeros sobrando). Uma consulta só para todos os valores.
     */
    public List<String> formatarNumeros(List<BigDecimal> valores) {
        if (valores.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < valores.size(); i++) {
            sql.append(i == 0 ? "" : ", ").append("fn_numero_br(CAST(:v").append(i).append(" AS numeric))");
        }
        var consulta = jdbc.sql(sql.toString());
        for (int i = 0; i < valores.size(); i++) {
            consulta = consulta.param("v" + i, valores.get(i));
        }
        return consulta.query((rs, n) -> {
            List<String> formatados = new ArrayList<>();
            for (int i = 1; i <= valores.size(); i++) {
                formatados.add(rs.getString(i));
            }
            return formatados;
        }).single();
    }
}
