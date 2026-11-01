package br.com.rbs.portal.atlas;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * {@code reasoningContent} o raciocínio que o modelo mandou com a mensagem, se mandou (volta ao modelo na retomada; cru, nunca
     * vai à API nem ao log). {@code resumoDoRaciocinio} é o resumo dele que a conversa mostra (V9), ou nulo.
     */
    public record LinhaDeMensagem(Long id, Integer ordem, String papel, String conteudo, String toolCalls, String toolCallId,
                                  OffsetDateTime criadaEm, String reasoningContent, String resumoDoRaciocinio) {

        /** Sem o raciocínio cru: um {@code toString} (de um log, de uma mensagem de erro) não pode levá-lo. */
        @Override
        public String toString() {
            return "LinhaDeMensagem[id=" + id + ", ordem=" + ordem + ", papel=" + papel + "]";
        }
    }

    /** Uma linha da trilha das ferramentas ({@code atlas_tool_call}) de uma mensagem do assistente: só o que o debug precisa. */
    public record LinhaDeChamada(Long mensagemId, String ferramenta, String erro, Integer duracaoMs) {
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
    /** A última coisa que a pessoa escreveu na conversa (vale como origem dos números do turno), ou vazio se ainda não escreveu. */
    public Optional<String> ultimaMensagemDaPessoa(long conversaId) {
        return jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :id AND papel = 'user' ORDER BY ordem DESC LIMIT 1")
                .param("id", conversaId).query(String.class).optional();
    }

    /**
     * O que a pessoa escreveu antes da última mensagem dela, da mais nova para a mais antiga, até {@code limite} (o botão "Montar
     * relatório" também grava uma mensagem {@code user}, mas não foi a pessoa que a escreveu: {@code textoDoBotao} a deixa de fora).
     */
    public List<String> mensagensAnterioresDaPessoa(long conversaId, int limite, String textoDoBotao) {
        return jdbc.sql("""
                        SELECT conteudo FROM atlas_mensagem
                        WHERE conversa_id = :id AND papel = 'user' AND conteudo <> :botao
                          AND ordem < coalesce((SELECT max(ordem) FROM atlas_mensagem WHERE conversa_id = :id AND papel = 'user'), 0)
                        ORDER BY ordem DESC LIMIT :limite
                        """)
                .param("id", conversaId).param("botao", textoDoBotao).param("limite", limite).query(String.class).list();
    }

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

    /**
     * Quantas mensagens do assistente que vieram do modelo de linguagem foram gravadas hoje (dia de Brasília) nas conversas da
     * parceria. As do botão (plano B) têm id de pedido {@code botao_...} e não entram: não gastam o modelo. É o contador do
     * teto diário.
     */
    public long mensagensDoModeloHoje(long parceriaId) {
        return jdbc.sql("""
                        SELECT count(*) FROM atlas_mensagem m JOIN atlas_conversa c ON c.id = m.conversa_id
                        WHERE c.parceria_id = :parceria AND m.papel = 'assistant'
                          AND m.criada_em >= (date_trunc('day', now() AT TIME ZONE 'America/Sao_Paulo') AT TIME ZONE 'America/Sao_Paulo')
                          AND NOT coalesce(jsonb_path_exists(m.tool_calls, '$[*].id ? (@ like_regex "^botao_")'), false)
                        """)
                .param("parceria", parceriaId)
                .query(Long.class).single();
    }

    /** Quanto uma parceria gastou do teto diário hoje (as mesmas mensagens que {@link #mensagensDoModeloHoje}). */
    public record UsoDoTeto(Long parceriaId, String parceria, Long usadas) {
    }

    /** O uso do teto diário de cada parceria que gastou alguma mensagem hoje, da que mais gastou para a que menos gastou. */
    public List<UsoDoTeto> mensagensDoModeloHojePorParceria() {
        return jdbc.sql("""
                        SELECT c.parceria_id, p.nome_canonico AS parceria, count(*) AS usadas
                        FROM atlas_mensagem m
                        JOIN atlas_conversa c ON c.id = m.conversa_id
                        JOIN parceria pa ON pa.id = c.parceria_id
                        JOIN patrocinador p ON p.id = pa.patrocinador_id
                        WHERE m.papel = 'assistant'
                          AND m.criada_em >= (date_trunc('day', now() AT TIME ZONE 'America/Sao_Paulo') AT TIME ZONE 'America/Sao_Paulo')
                          AND NOT coalesce(jsonb_path_exists(m.tool_calls, '$[*].id ? (@ like_regex "^botao_")'), false)
                        GROUP BY c.parceria_id, p.nome_canonico
                        ORDER BY usadas DESC, p.nome_canonico
                        """)
                .query(UsoDoTeto.class).list();
    }

    public List<LinhaDeMensagem> mensagens(long conversaId) {
        return jdbc.sql("""
                        SELECT id, ordem, papel, conteudo, CAST(tool_calls AS text) AS tool_calls, tool_call_id, criada_em,
                               reasoning_content, resumo_do_raciocinio
                        FROM atlas_mensagem WHERE conversa_id = :id ORDER BY ordem
                        """)
                .param("id", conversaId)
                .query(LinhaDeMensagem.class).list();
    }

    /**
     * Grava o resumo do raciocínio de uma mensagem do assistente (V9): um {@code UPDATE} curto, de quem gerou o resumo, fora de
     * qualquer transação do turno. Falso se a mensagem não existe (ou não tem raciocínio): nada é gravado.
     */
    public boolean gravarResumoDoRaciocinio(long mensagemId, String resumo) {
        return jdbc.sql("""
                        UPDATE atlas_mensagem SET resumo_do_raciocinio = :resumo
                        WHERE id = :id AND papel = 'assistant' AND reasoning_content IS NOT NULL
                        """)
                .param("resumo", resumo).param("id", mensagemId)
                .update() == 1;
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

    /**
     * A trilha das ferramentas da conversa, na ordem em que foram gravadas (o debug das ferramentas). A trilha tem uma linha por
     * pedido de ferramenta de cada mensagem do assistente, na ordem do pedido.
     */
    public List<LinhaDeChamada> chamadasDaConversa(long conversaId) {
        return jdbc.sql("""
                        SELECT t.mensagem_id, t.ferramenta, t.erro, t.duracao_ms
                        FROM atlas_tool_call t JOIN atlas_mensagem m ON m.id = t.mensagem_id
                        WHERE m.conversa_id = :id ORDER BY t.id
                        """)
                .param("id", conversaId)
                .query(LinhaDeChamada.class).list();
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
    // Fontes citadas (V12)
    // ---------------------------------------------------------------------

    /** Grava a fonte de trecho que uma mensagem do assistente citou; {@code dados} guarda o título e a seção como estavam. */
    public void inserirFonteDeTrecho(long mensagemId, String marcador, long trechoId, long documentoId, int versao, int pagina,
                                     String titulo, String secao) {
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("titulo", titulo);
        dados.put("secao", secao);
        jdbc.sql("""
                        INSERT INTO atlas_fonte (mensagem_id, marcador, tipo, trecho_id, documento_id, versao, pagina, dados)
                        VALUES (:mensagem, :marcador, 'TRECHO', :trecho, :documento, :versao, :pagina, CAST(:dados AS jsonb))
                        """)
                .param("mensagem", mensagemId).param("marcador", marcador).param("trecho", trechoId).param("documento", documentoId)
                .param("versao", versao).param("pagina", pagina).param("dados", AtlasJson.escrever(dados)).update();
    }

    /**
     * Grava a fonte de registro do portal ({@code [R#]}) que uma mensagem citou. O CHECK da tabela proíbe, no registro, as colunas do
     * documento: se o registro veio de um documento, o {@code documentoId}, a {@code versao} e a {@code pagina} ficam em {@code dados},
     * junto do título e da seção.
     */
    public void inserirFonteDeRegistro(long mensagemId, String marcador, String entidade, long id, Map<String, Object> dados) {
        jdbc.sql("""
                        INSERT INTO atlas_fonte (mensagem_id, marcador, tipo, registro_entidade, registro_id, dados)
                        VALUES (:mensagem, :marcador, 'REGISTRO', :entidade, :id, CAST(:dados AS jsonb))
                        """)
                .param("mensagem", mensagemId).param("marcador", marcador).param("entidade", entidade).param("id", id)
                .param("dados", AtlasJson.escrever(dados)).update();
    }

    /** Grava a fonte de conta ({@code [C#]}, do {@code calcular} ou do {@code contar_dias}): a conta inteira vai em {@code dados}. */
    public void inserirFonteDeCalculo(long mensagemId, String marcador, Map<String, Object> dados) {
        jdbc.sql("""
                        INSERT INTO atlas_fonte (mensagem_id, marcador, tipo, dados)
                        VALUES (:mensagem, :marcador, 'CALCULO', CAST(:dados AS jsonb))
                        """)
                .param("mensagem", mensagemId).param("marcador", marcador).param("dados", AtlasJson.escrever(dados)).update();
    }

    /**
     * Uma fonte citada numa mensagem, como a conversa a mostra. {@code substituido} e {@code revogado} são o estado de AGORA do
     * documento (ele pode ter mudado depois da resposta). {@code documentoId}, {@code versao} e {@code pagina} são das colunas num
     * trecho e de {@code dados} num registro que veio de um documento. {@code titulo} é o do trecho ou do registro e, na conta, o
     * nome dela. {@code dados} é o JSON cru (a conta, para a releitura resolver o {@code {{calc:nome}}} daquela mensagem).
     */
    public record LinhaDeFonte(Long mensagemId, String marcador, String tipo, Long trechoId, Long documentoId, Integer versao,
                               Integer pagina, String titulo, String secao, boolean substituido, boolean revogado, String dados) {
    }

    /** O documento a que a fonte aponta: a coluna, no trecho, ou o {@code documentoId} de {@code dados}, no registro. */
    private static final String DOCUMENTO_DA_FONTE = """
            coalesce(f.documento_id, CASE WHEN jsonb_typeof(f.dados -> 'documentoId') = 'number'
                                          THEN (f.dados ->> 'documentoId')::bigint END)""";

    /** As fontes de todas as mensagens da conversa, por mensagem e pelo número do marcador. */
    public List<LinhaDeFonte> fontesDaConversa(long conversaId) {
        return jdbc.sql("""
                        SELECT f.mensagem_id, f.marcador, f.tipo, f.trecho_id,
                               %s AS documento_id,
                               coalesce(f.versao, CASE WHEN jsonb_typeof(f.dados -> 'versao') = 'number' THEN (f.dados ->> 'versao')::int END) AS versao,
                               coalesce(f.pagina, CASE WHEN jsonb_typeof(f.dados -> 'pagina') = 'number' THEN (f.dados ->> 'pagina')::int END) AS pagina,
                               coalesce(f.dados ->> 'titulo', f.dados ->> 'nome') AS titulo, f.dados ->> 'secao' AS secao,
                               coalesce(d.status = 'SUBSTITUIDO', FALSE) AS substituido, coalesce(d.status = 'REVOGADO', FALSE) AS revogado,
                               f.dados::text AS dados
                        FROM atlas_fonte f
                        JOIN atlas_mensagem m ON m.id = f.mensagem_id
                        LEFT JOIN documento d ON d.id = %s
                        WHERE m.conversa_id = :conversa
                        ORDER BY f.mensagem_id, f.id
                        """.formatted(DOCUMENTO_DA_FONTE, DOCUMENTO_DA_FONTE))
                .param("conversa", conversaId).query(LinhaDeFonte.class).list();
    }

    /** Um documento que a conversa já citou e que deixou de valer (revogado, ou substituído por uma versão mais nova). */
    public record DocumentoRetirado(String titulo, Integer versao) {
    }

    /**
     * Os documentos que alguma resposta desta conversa citou (pela {@code atlas_fonte}) e que hoje estão revogados ou substituídos. O
     * prompt do turno avisa o modelo de cada um, para ele não repetir o que disse deles.
     */
    public List<DocumentoRetirado> documentosCitadosQueSairamDoAr(long conversaId) {
        return jdbc.sql("""
                        SELECT DISTINCT d.titulo, d.versao
                        FROM atlas_fonte f
                        JOIN atlas_mensagem m ON m.id = f.mensagem_id
                        JOIN documento d ON d.id = %s
                        WHERE m.conversa_id = :conversa AND d.status IN ('REVOGADO', 'SUBSTITUIDO')
                        ORDER BY d.titulo, d.versao
                        """.formatted(DOCUMENTO_DA_FONTE))
                .param("conversa", conversaId).query(DocumentoRetirado.class).list();
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

    /**
     * O relatório mais novo da conversa foi descartado? Então a conversa não tem relatório em andamento (o criar recusa um segundo
     * em andamento, e o que está em andamento é sempre o mais novo). Um relatório pronto depois do descartado desfaz a resposta.
     */
    public boolean ultimoRelatorioDescartado(long conversaId) {
        return jdbc.sql("SELECT status = 'DESCARTADO' FROM atlas_relatorio WHERE conversa_id = :id ORDER BY id DESC LIMIT 1")
                .param("id", conversaId).query(Boolean.class).optional().orElse(false);
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

    /**
     * As contas do {@code calcular} que a conversa gravou para o relatório (M5), como o JSON de {@code ContaDoChat.comoDados()}. As
     * linhas das fórmulas fixas guardam em {@code variaveis} uma lista; as do chat, um objeto: é assim que se distinguem.
     */
    public List<String> contasDoChatDoRelatorio(long relatorioId) {
        return jdbc.sql("SELECT variaveis::text FROM atlas_calculo WHERE relatorio_id = :relatorio AND jsonb_typeof(variaveis) = 'object' ORDER BY id")
                .param("relatorio", relatorioId).query(String.class).list();
    }

    /** O relatório já tem uma conta com este nome (do chat, ou uma fórmula fixa já gravada)? */
    public boolean calculoExiste(long relatorioId, String nome) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM atlas_calculo WHERE relatorio_id = :relatorio AND nome = :nome)")
                .param("relatorio", relatorioId).param("nome", nome).query(Boolean.class).single();
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

    /** O evento (a edição do festival), a cota e o ano da parceria: o que a busca nos documentos usa para saber o que a marca enxerga. */
    public record EscopoDaParceria(Long eventoId, Integer cotaTipoId, String cotaCodigo, Integer edicao) {
    }

    /** O escopo da parceria nos documentos (M4): a edição do festival e a cota. Parceria sempre tem os dois. */
    public EscopoDaParceria escopoDaParceria(long parceriaId) {
        return jdbc.sql("""
                        SELECT pa.evento_id, pa.cota_tipo_id, ct.codigo AS cota_codigo, ev.edicao
                        FROM parceria pa JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id JOIN evento ev ON ev.id = pa.evento_id
                        WHERE pa.id = :id
                        """)
                .param("id", parceriaId)
                .query(EscopoDaParceria.class).single();
    }

    /** O mesmo escopo, ou vazio se a parceria não existe (a busca de teste da RBS recebe o id de quem digitou). */
    public Optional<EscopoDaParceria> escopoDaParceriaSeExistir(long parceriaId) {
        return jdbc.sql("""
                        SELECT pa.evento_id, pa.cota_tipo_id, ct.codigo AS cota_codigo, ev.edicao
                        FROM parceria pa JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id JOIN evento ev ON ev.id = pa.evento_id
                        WHERE pa.id = :id
                        """)
                .param("id", parceriaId)
                .query(EscopoDaParceria.class).optional();
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
