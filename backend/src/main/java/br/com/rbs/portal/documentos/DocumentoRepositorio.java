package br.com.rbs.portal.documentos;

import br.com.rbs.portal.documentos.DetectorDeDadosPessoais.Suspeita;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL dos documentos do Atlas (rotas 54 a 58 e a ingestão). [ATLAS] Cada método é uma consulta ou um comando curto; quem decide a
 * transação é o service. As regras do ciclo (status, aprovação, dado pessoal, coerência, revogação) moram nos gatilhos da V11: aqui
 * só se grava, e o banco recusa o que não pode (a API traduz a recusa em 409 com a frase do gatilho).
 */
@Repository
public class DocumentoRepositorio {

    /** Uma linha de documento como a RBS vê, com a edição, a cota, a parceria, o arquivo e as pessoas já ligados pelo nome. */
    public record Linha(Long id, String titulo, String chave, Integer versao, String tipo, String escopo, Long eventoId,
                        String eventoNome, Integer eventoEdicao, String cotaCodigo, String cotaNome, Long parceriaId,
                        String patrocinador, String status, boolean contemDadosPessoais, Integer paginas, Integer trechos,
                        String modeloEmbedding, Integer dimensoes, Integer extracaoMs, Integer embeddingsMs, String erro,
                        Long arquivoId, String arquivoNome, String arquivoMime, Long arquivoTamanho, Long enviadoPor,
                        String enviadoPorNome, String enviadoPorPerfil, OffsetDateTime enviadoEm, Long aprovadoPor,
                        String aprovadoPorNome, String aprovadoPorPerfil, OffsetDateTime aprovadoEm, Long revogadoPor,
                        String revogadoPorNome, String revogadoPorPerfil, OffsetDateTime revogadoEm, String paginasIgnoradas,
                        String suspeitas) {
    }

    /** O que a rota 56 mostra à marca. */
    public record LinhaDaMarca(Long id, String titulo, String tipo, Integer versao, Integer paginas, boolean substituido) {
    }

    /** O documento travado para uma mudança de status. */
    public record Travado(Long id, String titulo, Integer versao, String status, Long parceriaId, Long aprovadoPor,
                          boolean contemDadosPessoais) {
    }

    /** O que a ingestão precisa saber do documento. */
    public record ParaIngestao(Long id, String titulo, Integer versao, String status, Long parceriaId, Long aprovadoPor,
                               String arquivoNome, String arquivoCaminho, String paginasIgnoradas) {
    }

    /** Um trecho na lista da RBS. */
    public record LinhaDeTrecho(Long id, Integer ordem, Integer paginaInicio, Integer paginaFim, String secaoCaminho,
                                Integer tokens, boolean ativo, String inicioDoTexto) {
    }

    /** O trecho pronto para gravar: posição, texto e o vetor (em texto do PostgreSQL). */
    public record TrechoParaGravar(int ordem, int paginaInicio, int paginaFim, String secaoCaminho, String texto, int tokens,
                                   String embedding) {
    }

    /** O documento órfão que a subida da API passou a ERRO. */
    public record Orfao(Long id, Long parceriaId, Long aprovadoPor) {
    }

    /** Filtros da lista (todos opcionais). {@code q} procura no título, sem diferenciar maiúsculas. */
    public record Filtro(Long eventoId, String escopo, String status, String tipo, String chave, String q) {
    }

    private static final String COLUNAS = """
            SELECT d.id, d.titulo, d.chave, d.versao, d.tipo, d.escopo, d.evento_id, ev.nome AS evento_nome, ev.edicao AS evento_edicao,
                   ct.codigo AS cota_codigo, ct.nome AS cota_nome, d.parceria_id, pat.nome_canonico AS patrocinador, d.status,
                   d.contem_dados_pessoais, d.paginas, d.trechos, d.modelo_embedding, d.dimensoes, d.extracao_ms, d.embeddings_ms,
                   d.erro, a.id AS arquivo_id, a.nome_original AS arquivo_nome, a.mime_type AS arquivo_mime,
                   a.tamanho_bytes AS arquivo_tamanho, d.enviado_por, ue.nome AS enviado_por_nome, ue.perfil AS enviado_por_perfil,
                   d.enviado_em, d.aprovado_por, ua.nome AS aprovado_por_nome, ua.perfil AS aprovado_por_perfil, d.aprovado_em,
                   d.revogado_por, ur.nome AS revogado_por_nome, ur.perfil AS revogado_por_perfil, d.revogado_em,
                   array_to_string(d.paginas_ignoradas, ',') AS paginas_ignoradas,
                   (SELECT string_agg((e.item ->> 'pagina') || ':' || (e.item ->> 'tipo'), ',' ORDER BY e.ordem)
                      FROM jsonb_array_elements(d.suspeitas) WITH ORDINALITY AS e(item, ordem)) AS suspeitas
            """;

    private static final String DE = """
            FROM documento d
            JOIN evento ev ON ev.id = d.evento_id
            LEFT JOIN cota_tipo ct ON ct.id = d.cota_tipo_id
            LEFT JOIN parceria pa ON pa.id = d.parceria_id
            LEFT JOIN patrocinador pat ON pat.id = pa.patrocinador_id
            JOIN arquivo a ON a.id = d.arquivo_id
            JOIN usuario ue ON ue.id = d.enviado_por
            LEFT JOIN usuario ua ON ua.id = d.aprovado_por
            LEFT JOIN usuario ur ON ur.id = d.revogado_por
            WHERE TRUE""";

    private final JdbcClient jdbc;

    public DocumentoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------------
    // Leitura
    // ---------------------------------------------------------------------

    public Optional<Linha> buscar(long id) {
        return jdbc.sql(COLUNAS + DE + " AND d.id = :id").param("id", id).query(Linha.class).optional();
    }

    public long contar(Filtro filtro) {
        Map<String, Object> parametros = new LinkedHashMap<>();
        String onde = onde(filtro, parametros);
        return jdbc.sql("SELECT count(*) " + DE + onde).params(parametros).query(Long.class).single();
    }

    /** Uma página, do envio mais novo para o mais antigo. */
    public List<Linha> pagina(Filtro filtro, int pagina, int tamanho) {
        Map<String, Object> parametros = new LinkedHashMap<>();
        String onde = onde(filtro, parametros);
        parametros.put("limite", tamanho);
        parametros.put("deslocamento", (long) pagina * tamanho);
        return jdbc.sql(COLUNAS + DE + onde + " ORDER BY d.enviado_em DESC, d.id DESC LIMIT :limite OFFSET :deslocamento")
                .params(parametros).query(Linha.class).list();
    }

    private static String onde(Filtro f, Map<String, Object> parametros) {
        StringBuilder onde = new StringBuilder();
        if (f.eventoId() != null) {
            onde.append(" AND d.evento_id = :eventoId");
            parametros.put("eventoId", f.eventoId());
        }
        if (f.escopo() != null) {
            onde.append(" AND d.escopo = :escopo");
            parametros.put("escopo", f.escopo());
        }
        if (f.status() != null) {
            onde.append(" AND d.status = :status");
            parametros.put("status", f.status());
        }
        if (f.tipo() != null) {
            onde.append(" AND d.tipo = :tipo");
            parametros.put("tipo", f.tipo());
        }
        if (f.chave() != null) {
            onde.append(" AND d.chave = :chave");
            parametros.put("chave", f.chave());
        }
        if (f.q() != null) {
            onde.append(" AND position(lower(:q) IN lower(d.titulo)) > 0");
            parametros.put("q", f.q());
        }
        return onde.toString();
    }

    /**
     * O documento que a marca pode ver (rota 56): indexado ou substituído, da edição e do escopo de alguma parceria a que ela tem
     * vínculo vigente. GLOBAL vale para a edição; COTA, para a cota da parceria nessa edição; PARCERIA, só para a própria parceria.
     * Qualquer outra coisa (não indexado, revogado, de outra edição, cota ou parceria, inexistente) não volta.
     */
    public Optional<LinhaDaMarca> paraAMarca(long id, long usuarioId) {
        return jdbc.sql("""
                        SELECT d.id, d.titulo, d.tipo, d.versao, d.paginas, (d.status = 'SUBSTITUIDO') AS substituido
                        FROM documento d
                        WHERE d.id = :id AND %s
                        """.formatted(VISIVEL_PARA_A_MARCA))
                .param("id", id).param("usuarioId", usuarioId).query(LinhaDaMarca.class).optional();
    }

    /** A regra da rota 56, a mesma do visualizador (rotas 59 e 60): indexado ou substituído, no escopo de uma parceria do usuário. */
    private static final String VISIVEL_PARA_A_MARCA = """
            d.status IN ('INDEXADO', 'SUBSTITUIDO')
                          AND EXISTS (SELECT 1 FROM parceria_usuario pu
                                      JOIN usuario u ON u.id = pu.usuario_id
                                      JOIN parceria p ON p.id = pu.parceria_id
                                      WHERE pu.usuario_id = :usuarioId AND u.ativo AND p.evento_id = d.evento_id
                                        AND (d.escopo = 'GLOBAL'
                                             OR (d.escopo = 'COTA' AND p.cota_tipo_id = d.cota_tipo_id)
                                             OR (d.escopo = 'PARCERIA' AND p.id = d.parceria_id)))""";

    /**
     * O arquivo de um documento para o visualizador (rota 59). {@code parceriaId} é o do documento de escopo PARCERIA (nulo nos outros):
     * é o que o histórico do download grava.
     */
    public record LinhaDoConteudo(Long id, Long parceriaId, String arquivoNome, String arquivoMime, String arquivoCaminho) {
    }

    private static final String CONTEUDO = """
            SELECT d.id, d.parceria_id, a.nome_original AS arquivo_nome, a.mime_type AS arquivo_mime, a.caminho AS arquivo_caminho
            FROM documento d JOIN arquivo a ON a.id = d.arquivo_id
            """;

    /** O arquivo do documento, em qualquer status (a RBS abre o recusado, o revogado e o que deu erro). */
    public Optional<LinhaDoConteudo> conteudo(long id) {
        return jdbc.sql(CONTEUDO + " WHERE d.id = :id").param("id", id).query(LinhaDoConteudo.class).optional();
    }

    /** O arquivo do documento que a marca pode ver (a regra da rota 56): o resto, inclusive o revogado, não volta. */
    public Optional<LinhaDoConteudo> conteudoParaAMarca(long id, long usuarioId) {
        return jdbc.sql(CONTEUDO + " WHERE d.id = :id AND " + VISIVEL_PARA_A_MARCA).param("id", id).param("usuarioId", usuarioId)
                .query(LinhaDoConteudo.class).optional();
    }

    /** Um trecho com o texto inteiro, para o destaque do visualizador (rota 60). */
    public record LinhaDoTrechoInteiro(Long id, Long documentoId, Integer ordem, Integer paginaInicio, Integer paginaFim,
                                       String secaoCaminho, String texto) {
    }

    /** O trecho {@code trechoId} do documento {@code documentoId}; trecho de outro documento não volta. */
    public Optional<LinhaDoTrechoInteiro> trechoInteiro(long documentoId, long trechoId) {
        return jdbc.sql("""
                        SELECT id, documento_id, ordem, pagina_inicio, pagina_fim, secao_caminho, texto
                        FROM documento_trecho WHERE documento_id = :documento AND id = :trecho
                        """)
                .param("documento", documentoId).param("trecho", trechoId).query(LinhaDoTrechoInteiro.class).optional();
    }

    public long contarTrechos(long documentoId) {
        return jdbc.sql("SELECT count(*) FROM documento_trecho WHERE documento_id = :id").param("id", documentoId)
                .query(Long.class).single();
    }

    /** Os trechos do documento, na ordem, com o começo do texto (240 caracteres). */
    public List<LinhaDeTrecho> trechos(long documentoId, int pagina, int tamanho) {
        return jdbc.sql("""
                        SELECT id, ordem, pagina_inicio, pagina_fim, secao_caminho, tokens, ativo, left(texto, 240) AS inicio_do_texto
                        FROM documento_trecho WHERE documento_id = :id ORDER BY ordem LIMIT :limite OFFSET :deslocamento
                        """)
                .param("id", documentoId).param("limite", tamanho).param("deslocamento", (long) pagina * tamanho)
                .query(LinhaDeTrecho.class).list();
    }

    // ---------------------------------------------------------------------
    // Edição, parceria, cota e o grupo
    // ---------------------------------------------------------------------

    public Optional<Long> eventoDaEdicao(int edicao) {
        return jdbc.sql("SELECT id FROM evento WHERE nome = 'Planeta Atlântida' AND edicao = :edicao").param("edicao", edicao)
                .query(Long.class).optional();
    }

    public boolean eventoExiste(long eventoId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM evento WHERE id = :id)").param("id", eventoId).query(Boolean.class).single();
    }

    /** A edição (id do evento) de uma parceria, ou vazio se ela não existe. */
    public Optional<Long> eventoDaParceria(long parceriaId) {
        return jdbc.sql("SELECT evento_id FROM parceria WHERE id = :id").param("id", parceriaId).query(Long.class).optional();
    }

    public Optional<Integer> cotaPorCodigo(String codigo) {
        return jdbc.sql("SELECT id FROM cota_tipo WHERE codigo = :codigo").param("codigo", codigo).query(Integer.class).optional();
    }

    public Optional<Long> parceriaDoPatrocinador(String nomeCanonico, int edicao) {
        return jdbc.sql("""
                        SELECT pa.id FROM parceria pa JOIN patrocinador p ON p.id = pa.patrocinador_id JOIN evento e ON e.id = pa.evento_id
                        WHERE p.nome_canonico = :nome AND e.edicao = :edicao
                        """)
                .param("nome", nomeCanonico).param("edicao", edicao).query(Long.class).optional();
    }

    /** Trava o grupo (edição, escopo, cota, parceria, chave) até o fim da transação: dois envios do mesmo grupo não pegam a mesma versão. */
    public void travarGrupo(long eventoId, String escopo, Integer cotaId, Long parceriaId, String chave) {
        jdbc.sql("SELECT fn_trava_grupo_documento(:evento, :escopo, CAST(:cota AS smallint), :parceria, :chave)")
                .param("evento", eventoId).param("escopo", escopo).param("cota", cotaId).param("parceria", parceriaId)
                .param("chave", chave).query().singleRow();
    }

    /** A versão do próximo documento do grupo: a maior que ele já tem mais 1. */
    public int proximaVersao(long eventoId, String escopo, Integer cotaId, Long parceriaId, String chave) {
        return jdbc.sql("""
                        SELECT coalesce(max(versao), 0) + 1 FROM documento
                        WHERE evento_id = :evento AND escopo = :escopo AND cota_tipo_id IS NOT DISTINCT FROM CAST(:cota AS smallint)
                          AND parceria_id IS NOT DISTINCT FROM CAST(:parceria AS bigint) AND chave = :chave
                        """)
                .param("evento", eventoId).param("escopo", escopo).param("cota", cotaId).param("parceria", parceriaId)
                .param("chave", chave).query(Integer.class).single();
    }

    /** Se o grupo já tem a versão (a amostra e o kit só criam o que ainda não existe). */
    public boolean existeNoGrupo(long eventoId, String escopo, Integer cotaId, Long parceriaId, String chave, int versao) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM documento
                                       WHERE evento_id = :evento AND escopo = :escopo AND cota_tipo_id IS NOT DISTINCT FROM CAST(:cota AS smallint)
                                         AND parceria_id IS NOT DISTINCT FROM CAST(:parceria AS bigint) AND chave = :chave AND versao = :versao)
                        """)
                .param("evento", eventoId).param("escopo", escopo).param("cota", cotaId).param("parceria", parceriaId)
                .param("chave", chave).param("versao", versao).query(Boolean.class).single();
    }

    // ---------------------------------------------------------------------
    // Envio
    // ---------------------------------------------------------------------

    /** O envio sem páginas fora do Atlas e sem suspeita registrada (os testes de ingestão montam documentos assim). */
    public long inserir(String titulo, String chave, int versao, String tipo, String escopo, long eventoId, Integer cotaId,
                        Long parceriaId, long arquivoId, boolean contemDadosPessoais, Integer paginas, Integer extracaoMs,
                        String erro, long enviadoPor) {
        return inserir(titulo, chave, versao, tipo, escopo, eventoId, cotaId, parceriaId, arquivoId, contemDadosPessoais, paginas,
                extracaoMs, erro, enviadoPor, List.of(), List.of());
    }

    /** Grava o documento. {@code erro} preenchido faz o documento nascer em ERRO (sem aprovado_por); nulo, aguardando aprovação. */
    public long inserir(String titulo, String chave, int versao, String tipo, String escopo, long eventoId, Integer cotaId,
                        Long parceriaId, long arquivoId, boolean contemDadosPessoais, Integer paginas, Integer extracaoMs,
                        String erro, long enviadoPor, List<Integer> paginasForaDoAtlas, List<Suspeita> suspeitas) {
        return jdbc.sql("""
                        INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, cota_tipo_id, parceria_id, arquivo_id,
                                               status, contem_dados_pessoais, paginas, extracao_ms, erro, enviado_por,
                                               paginas_ignoradas, suspeitas)
                        VALUES (:titulo, :chave, :versao, :tipo, :escopo, :evento, CAST(:cota AS smallint), :parceria, :arquivo,
                                CASE WHEN CAST(:erro AS text) IS NULL THEN 'AGUARDANDO_APROVACAO' ELSE 'ERRO' END,
                                :dados, :paginas, :extracaoMs, CAST(:erro AS text), :enviadoPor,
                                CAST(:ignoradas AS smallint[]), CAST(:suspeitas AS jsonb))
                        RETURNING id
                        """)
                .param("titulo", titulo).param("chave", chave).param("versao", versao).param("tipo", tipo).param("escopo", escopo)
                .param("evento", eventoId).param("cota", cotaId).param("parceria", parceriaId).param("arquivo", arquivoId)
                .param("dados", contemDadosPessoais).param("paginas", paginas).param("extracaoMs", extracaoMs).param("erro", erro)
                .param("enviadoPor", enviadoPor).param("ignoradas", PaginasForaDoAtlas.paraArray(paginasForaDoAtlas))
                .param("suspeitas", PaginasForaDoAtlas.suspeitasParaJson(suspeitas)).query(Long.class).single();
    }

    /**
     * Grava as páginas fora do Atlas e a suspeita recalculada sem elas (rota 103), no documento travado. O gatilho confere a lista e só
     * deixa mudar enquanto o documento aguarda aprovação.
     */
    public void gravarPaginasForaDoAtlas(long id, List<Integer> paginas, boolean contemDadosPessoais, List<Suspeita> suspeitas) {
        jdbc.sql("""
                        UPDATE documento SET paginas_ignoradas = CAST(:ignoradas AS smallint[]), contem_dados_pessoais = :dados,
                                             suspeitas = CAST(:suspeitas AS jsonb)
                        WHERE id = :id
                        """)
                .param("id", id).param("ignoradas", PaginasForaDoAtlas.paraArray(paginas)).param("dados", contemDadosPessoais)
                .param("suspeitas", PaginasForaDoAtlas.suspeitasParaJson(suspeitas)).update();
    }

    // ---------------------------------------------------------------------
    // Mudança de status
    // ---------------------------------------------------------------------

    /** Trava a linha do documento para a mudança de status (dois cliques ao mesmo tempo não se atropelam). */
    public Optional<Travado> travar(long id) {
        return jdbc.sql("""
                        SELECT id, titulo, versao, status, parceria_id, aprovado_por, contem_dados_pessoais
                        FROM documento WHERE id = :id FOR UPDATE
                        """)
                .param("id", id).query(Travado.class).optional();
    }

    /** Aprovar: quem aprovou (da RBS) e PROCESSANDO. Se o documento tem suspeita de dado pessoal, o gatilho recusa. */
    public void aprovar(long id, long usuarioId) {
        jdbc.sql("""
                        UPDATE documento SET status = 'PROCESSANDO', aprovado_por = :usuario, aprovado_por_perfil = 'ADMIN_RBS',
                                             aprovado_em = now(), erro = NULL
                        WHERE id = :id
                        """)
                .param("id", id).param("usuario", usuarioId).update();
    }

    /** Reindexar: volta de ERRO para PROCESSANDO. O gatilho recusa se o documento nunca foi aprovado ou tem dado pessoal. */
    public void reindexar(long id) {
        jdbc.sql("UPDATE documento SET status = 'PROCESSANDO', erro = NULL WHERE id = :id").param("id", id).update();
    }

    public void recusar(long id) {
        jdbc.sql("UPDATE documento SET status = 'RECUSADO' WHERE id = :id").param("id", id).update();
    }

    /** Revogar: os trechos se desligam na hora (gatilho), na mesma transação. */
    public void revogar(long id, long usuarioId) {
        jdbc.sql("UPDATE documento SET status = 'REVOGADO', revogado_por = :usuario, revogado_em = now() WHERE id = :id")
                .param("id", id).param("usuario", usuarioId).update();
    }

    // ---------------------------------------------------------------------
    // Ingestão
    // ---------------------------------------------------------------------

    public Optional<ParaIngestao> paraIngestao(long id) {
        return jdbc.sql("""
                        SELECT d.id, d.titulo, d.versao, d.status, d.parceria_id, d.aprovado_por, a.nome_original AS arquivo_nome,
                               a.caminho AS arquivo_caminho,
                               array_to_string(d.paginas_ignoradas, ',') AS paginas_ignoradas
                        FROM documento d JOIN arquivo a ON a.id = d.arquivo_id WHERE d.id = :id
                        """)
                .param("id", id).query(ParaIngestao.class).optional();
    }

    public Optional<String> statusDe(long id) {
        return jdbc.sql("SELECT status FROM documento WHERE id = :id").param("id", id).query(String.class).optional();
    }

    public int removerTrechosInativos(long documentoId) {
        return jdbc.sql("DELETE FROM documento_trecho WHERE documento_id = :id AND NOT ativo").param("id", documentoId).update();
    }

    /** Grava os trechos, inativos; o vetor vai como texto e o PostgreSQL o lê com CAST(... AS vector). */
    public void inserirTrechos(long documentoId, List<TrechoParaGravar> trechos) {
        for (TrechoParaGravar t : trechos) {
            jdbc.sql("""
                            INSERT INTO documento_trecho (documento_id, ordem, pagina_inicio, pagina_fim, secao_caminho, texto, tokens, embedding)
                            VALUES (:documento, :ordem, :de, :ate, :secao, :texto, :tokens, CAST(:embedding AS vector))
                            """)
                    .param("documento", documentoId).param("ordem", t.ordem()).param("de", t.paginaInicio())
                    .param("ate", t.paginaFim()).param("secao", t.secaoCaminho()).param("texto", t.texto())
                    .param("tokens", t.tokens()).param("embedding", t.embedding()).update();
        }
    }

    /** O resultado da extração e dos embeddings: páginas, tempos, modelo e dimensões. */
    public void gravarResultadoDaIngestao(long id, int paginas, int extracaoMs, String modelo, int dimensoes, int embeddingsMs) {
        jdbc.sql("""
                        UPDATE documento SET paginas = :paginas, extracao_ms = :extracao, modelo_embedding = :modelo, dimensoes = :dimensoes,
                                             embeddings_ms = :embeddings
                        WHERE id = :id
                        """)
                .param("id", id).param("paginas", paginas).param("extracao", extracaoMs).param("modelo", modelo)
                .param("dimensoes", dimensoes).param("embeddings", embeddingsMs).update();
    }

    /**
     * Trava o grupo do documento até o fim da transação, com a mesma chave de {@link #travarGrupo} e de {@code fn_publicar_documento}.
     * Quem vai publicar chama isto ANTES de {@link #indexadosMaisAntigosDoGrupo(long)}: a lista só vale depois de a trava ser sua.
     */
    public void travarGrupoDoDocumento(long id) {
        jdbc.sql("SELECT fn_trava_grupo_documento(d.evento_id, d.escopo, d.cota_tipo_id, d.parceria_id, d.chave) FROM documento d WHERE d.id = :id")
                .param("id", id).query().singleRow();
    }

    /** Os documentos INDEXADOS mais antigos do mesmo grupo: os que a publicação deste vai passar a SUBSTITUIDO. */
    public List<Long> indexadosMaisAntigosDoGrupo(long id) {
        return jdbc.sql("""
                        SELECT o.id FROM documento d JOIN documento o ON o.evento_id = d.evento_id AND o.escopo = d.escopo
                          AND o.cota_tipo_id IS NOT DISTINCT FROM d.cota_tipo_id AND o.parceria_id IS NOT DISTINCT FROM d.parceria_id
                          AND o.chave = d.chave
                        WHERE d.id = :id AND o.status = 'INDEXADO' AND o.versao < d.versao ORDER BY o.versao
                        """)
                .param("id", id).query(Long.class).list();
    }

    /** A versão e o documento mais novo do grupo que já está INDEXADO (a publicação de uma velha vira SUBSTITUIDO). */
    public void publicar(long id) {
        jdbc.sql("SELECT fn_publicar_documento(:id)").param("id", id).query().singleRow();
    }

    /**
     * ERRO com o motivo, só se ainda estiver PROCESSANDO. Devolve o documento (para o histórico), ou vazio se ele não estava mais
     * PROCESSANDO. Não precisa de uma leitura antes: a falha pode ter sido justamente a leitura do documento.
     */
    public Optional<Orfao> marcarErro(long id, String motivo) {
        return jdbc.sql("""
                        UPDATE documento SET status = 'ERRO', erro = :motivo WHERE id = :id AND status = 'PROCESSANDO'
                        RETURNING id, parceria_id, aprovado_por
                        """)
                .param("id", id).param("motivo", motivo).query(Orfao.class).optional();
    }

    /** Na subida da API: PROCESSANDO que sobrou de uma queda vira ERRO (a fila fica na memória e se perdeu). */
    public List<Orfao> orfaosViramErro(String motivo) {
        return jdbc.sql("""
                        UPDATE documento SET status = 'ERRO', erro = :motivo WHERE status = 'PROCESSANDO'
                        RETURNING id, parceria_id, aprovado_por
                        """)
                .param("motivo", motivo).query(Orfao.class).list();
    }

    /** Quantos documentos INDEXADOS foram indexados por um modelo que não é o de agora. */
    public long contarIndexadosComOutroModelo(String modeloAtual) {
        return jdbc.sql("SELECT count(*) FROM documento WHERE status = 'INDEXADO' AND modelo_embedding IS DISTINCT FROM :modelo")
                .param("modelo", modeloAtual).query(Long.class).single();
    }

    public List<Long> idsPorStatus(String status) {
        return new ArrayList<>(jdbc.sql("SELECT id FROM documento WHERE status = :status ORDER BY id").param("status", status)
                .query(Long.class).list());
    }
}
