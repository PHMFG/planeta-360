package br.com.rbs.portal.atlas.busca;

import br.com.rbs.portal.atlas.embeddings.ProvedorDeEmbeddings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A busca nos documentos do Atlas: duas listas de 30 trechos, uma por vetor e outra por texto, fundidas por RRF (plano do Atlas
 * completo, M4; nós R2 do v1 e as camadas 4 e 5 do isolamento). [ATLAS] [ISOLAMENTO] Roda como o papel {@code atlas_ro}, que só lê
 * as duas tabelas de documentos e só o que a RLS deixa ver, e o filtro de escopo vai também no {@code WHERE}, montado aqui em código:
 * a RLS é a rede de proteção, o filtro é a primeira linha.
 * <ul>
 *   <li><b>Transação nova e só de leitura.</b> O embedding da consulta é calculado ANTES, fora de transação (quem chama passa o
 *       vetor). A busca abre a própria transação ({@code REQUIRES_NEW}, {@code readOnly}) e confere o papel; ver
 *       {@link ContextoDeRls}. Falha fechada: sem transação nova, ou com outro {@code current_user}, nada é devolvido. A transação
 *       de quem chamou continua com o papel dele.</li>
 *   <li><b>Lista vetorial</b> ({@code embedding <=> :vetor}) num CTE {@code MATERIALIZED}, com a posição recalculada pela distância
 *       fora dele: com a busca iterativa em {@code relaxed_order} a lista pode vir um pouco fora de ordem, como o README do pgvector
 *       recomenda tratar. Só trechos do documento indexado com o mesmo modelo de embeddings da consulta (a busca ignora os de outro
 *       modelo e conta quantos ignorou).</li>
 *   <li><b>Lista textual</b> com os termos ligados por <b>OU</b>, ordenada por {@code ts_rank_cd}. A consulta vira os lexemas da
 *       mesma função e da mesma configuração que montaram o {@code tsv} ({@code fn_texto_de_busca} e {@code pt_sem_acento}), um por
 *       linha de {@code tsquery}, sempre com bind: a pergunta inteira exigiria todos os termos e "Até quando preciso informar os
 *       geradores na Arena" não casaria com o trecho que tem a resposta (conferido em 30/10/2026). Consulta sem nenhum termo útil
 *       (só palavras vazias, só pontuação) não busca nada.</li>
 *   <li><b>RRF</b>: {@code 1 / (60 + posição)} em cada lista; devolve os {@code k} melhores (padrão {@value #K_PADRAO}).</li>
 *   <li><b>Sem embeddings</b> (sem chave, desligado, disjuntor aberto): só a lista textual, com {@code degradado}.</li>
 * </ul>
 */
@Component
public class BuscaHibrida {

    public static final int TAMANHO_DAS_LISTAS = 30;
    public static final int K_PADRAO = 6;
    public static final int LIMITE_DE_TEMPO_EM_MS = 8_000;
    static final int CONSTANTE_DO_RRF = 60;

    /**
     * O que a conversa enxerga: a edição, a cota e a parceria (do {@code AtlasContexto}, nunca de argumento de ferramenta). A conversa
     * da RBS (M8) não tem marca nem cota: o escopo dela é {@link #soGlobal}, só os documentos {@code GLOBAL} da edição.
     */
    public record Escopo(long eventoId, int cotaTipoId, long parceriaId) {

        /** O "sem cota" do escopo só global. Nenhuma cota nem parceria de verdade tem id zero (as chaves começam em 1). */
        public static final int SEM_COTA = 0;
        public static final long SEM_PARCERIA = 0;
        /** Os marcadores do escopo de todos os escopos da edição (o Atlas da RBS, desde a base da RBS). */
        public static final int TODAS_AS_COTAS = -1;
        public static final long TODAS_AS_PARCERIAS = -1;

        /** O escopo do Atlas da RBS: os documentos {@code GLOBAL} desta edição, e nenhum de cota nem de parceria. */
        public static Escopo soGlobal(long eventoId) {
            return new Escopo(eventoId, SEM_COTA, SEM_PARCERIA);
        }

        /**
         * [ISOLAMENTO] O escopo do Atlas da RBS em toda a base: todos os documentos da edição, globais, de cota e de parceria. Só a
         * conversa da RBS o usa; a marca nunca.
         */
        public static Escopo todaAEdicao(long eventoId) {
            return new Escopo(eventoId, TODAS_AS_COTAS, TODAS_AS_PARCERIAS);
        }

        /** Este escopo enxerga só os documentos {@code GLOBAL} da edição? */
        public boolean soGlobal() {
            return cotaTipoId == SEM_COTA && parceriaId == SEM_PARCERIA;
        }

        /** Este escopo enxerga todos os documentos da edição (o Atlas da RBS em toda a base)? */
        public boolean todaAEdicao() {
            return cotaTipoId == TODAS_AS_COTAS && parceriaId == TODAS_AS_PARCERIAS;
        }
    }

    /**
     * Um pedido de busca. {@code vetor} é a consulta já transformada em vetor (nulo: sem embeddings, só a lista textual) e
     * {@code modeloDoVetor} o modelo que o gerou. {@code tipo} (um dos tipos de documento) filtra; nulo, não filtra.
     */
    public record Pedido(Escopo escopo, String consulta, String tipo, int k, float[] vetor, String modeloDoVetor) {
    }

    /**
     * Um trecho achado e o que as duas listas disseram dele. A posição e a distância (ou a nota) são nulas na lista em que ele não
     * entrou. {@code rrf} é a soma das duas contribuições.
     */
    public record Candidato(long trechoId, long documentoId, String documento, int versao, String tipo, String parceria, int pagina,
                            int paginaFim, String secao, String texto, int tokens, Integer posicaoVetorial, Double distancia,
                            Integer posicaoTextual, Double nota, double rrf) {
    }

    /**
     * O resultado: as duas listas (cada uma na ordem dela), os {@code escolhidos} (os {@code k} do RRF), se a busca foi
     * {@code degradada} (sem vetor), se a consulta não tinha termo útil e quanto levou o SQL. {@code trechosDeOutroModelo} diz quantos
     * trechos visíveis foram ignorados na lista vetorial por terem sido indexados com outro modelo.
     */
    public record ResultadoDaBusca(String consulta, int k, boolean degradado, boolean semTermoUtil, List<Candidato> vetorial,
                                   List<Candidato> textual, List<Candidato> escolhidos, long trechosDeOutroModelo, long sqlMs) {

        static ResultadoDaBusca semTermo(String consulta, int k, boolean degradado, long sqlMs) {
            return new ResultadoDaBusca(consulta, k, degradado, true, List.of(), List.of(), List.of(), 0, sqlMs);
        }
    }

    private static final String BASE_DO_FILTRO = """
            t.ativo AND d.status = 'INDEXADO' AND t.evento_id = :evento
                  AND (t.escopo = 'GLOBAL'
                       OR (t.escopo = 'COTA' AND t.cota_tipo_id = :cota)
                       OR (t.escopo = 'PARCERIA' AND t.parceria_id = :parceria))""";

    /** O mesmo filtro para o escopo só global (o Atlas da RBS): nenhuma cota, nenhuma parceria, só o {@code GLOBAL} da edição. */
    private static final String FILTRO_SO_GLOBAL = """
            t.ativo AND d.status = 'INDEXADO' AND t.evento_id = :evento AND t.escopo = 'GLOBAL'""";

    /**
     * O filtro do Atlas da RBS em toda a base: todos os escopos da edição. [ISOLAMENTO] O {@code WHERE} é a primeira linha de
     * defesa e vale sozinho: só a RBS monta este escopo.
     */
    private static final String FILTRO_TODA_A_EDICAO = """
            t.ativo AND d.status = 'INDEXADO' AND t.evento_id = :evento""";

    /** O filtro do {@code WHERE} montado aqui em código, a primeira linha de defesa (a RLS é a segunda). */
    private static String baseDoFiltro(Escopo escopo) {
        if (escopo.todaAEdicao()) {
            return FILTRO_TODA_A_EDICAO;
        }
        return escopo.soGlobal() ? FILTRO_SO_GLOBAL : BASE_DO_FILTRO;
    }

    private static final String LEXEMAS = """
            SELECT array_to_string(ARRAY(
                       SELECT '''' || replace(replace(l, '\\', '\\\\'), '''', '''''') || ''''
                       FROM unnest(tsvector_to_array(to_tsvector('pt_sem_acento', fn_texto_de_busca(:texto)))) AS l), ' | ')
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate transacao;
    private final String papel;

    @Autowired
    public BuscaHibrida(JdbcClient jdbc, PlatformTransactionManager gerenteDeTransacao) {
        this(jdbc, gerenteDeTransacao, ContextoDeRls.PAPEL);
    }

    /** Para os testes provarem que a busca confere o papel: com {@code papel} diferente de {@code atlas_ro}, ela não busca nada. */
    BuscaHibrida(JdbcClient jdbc, PlatformTransactionManager gerenteDeTransacao, String papel) {
        this.jdbc = jdbc;
        this.papel = papel;
        this.transacao = new TransactionTemplate(gerenteDeTransacao);
        this.transacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transacao.setReadOnly(true);
    }

    /**
     * Busca. Abre uma transação nova e só de leitura (a de quem chamou, se houver, fica suspensa e continua com o papel dele), roda
     * como {@code atlas_ro} e devolve as listas. [ISOLAMENTO] Qualquer problema com o papel ou com a transação sobe como exceção:
     * nunca devolve trecho que a RLS não conferiu.
     */
    public ResultadoDaBusca buscar(Pedido pedido) {
        return transacao.execute(status -> buscarNaTransacao(status.isNewTransaction(), pedido));
    }

    /**
     * Os documentos que o escopo enxerga, como o {@code atlas_ro} os vê (a RLS e o filtro do servidor): título, versão, tipo, escopo e
     * quantos trechos ativos cada um tem. É o "o que a parceria enxerga" da busca de teste da RBS (rota 61). Mesma regra da busca:
     * transação nova, só de leitura, papel conferido.
     */
    public List<DocumentoVisivel> documentosVisiveis(Escopo escopo) {
        return transacao.execute(status -> {
            exigirTransacaoNova(status.isNewTransaction());
            ContextoDeRls.aplicar(jdbc, papel, escopo, LIMITE_DE_TEMPO_EM_MS);
            return jdbc.sql("""
                            SELECT d.id, d.titulo, d.versao, d.tipo, d.escopo, count(*) AS trechos
                            FROM documento_trecho t JOIN documento d ON d.id = t.documento_id
                            WHERE %s
                            GROUP BY d.id, d.titulo, d.versao, d.tipo, d.escopo
                            ORDER BY d.escopo, d.titulo, d.versao, d.id
                            """.formatted(baseDoFiltro(escopo)))
                    .param("evento", escopo.eventoId()).param("cota", escopo.cotaTipoId()).param("parceria", escopo.parceriaId())
                    .query(DocumentoVisivel.class).list();
        });
    }

    /** Um documento que o escopo enxerga: o que a RBS confere na busca de teste. */
    public record DocumentoVisivel(long id, String titulo, int versao, String tipo, String escopo, long trechos) {
    }

    private static void exigirTransacaoNova(boolean transacaoNova) {
        if (!transacaoNova || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("A busca nos documentos só roda numa transação nova e só de leitura: fora dela o papel "
                    + ContextoDeRls.PAPEL + " não vale e a RLS não se aplica. Nenhuma busca foi feita.");
        }
    }

    /** O corpo da busca, só dentro de uma transação nova. Sem ela, ou com outro papel, falha fechada. */
    ResultadoDaBusca buscarNaTransacao(boolean transacaoNova, Pedido pedido) {
        exigirTransacaoNova(transacaoNova);
        long inicio = System.nanoTime();
        // O PostgreSQL não guarda o caractere nulo: uma consulta com ele falharia no bind. Ele não diz nada sobre o assunto.
        pedido = new Pedido(pedido.escopo(), pedido.consulta().replace("\u0000", ""), pedido.tipo(), pedido.k(), pedido.vetor(),
                pedido.modeloDoVetor());
        ContextoDeRls.aplicar(jdbc, papel, pedido.escopo(), LIMITE_DE_TEMPO_EM_MS);
        int k = Math.max(1, Math.min(pedido.k(), TAMANHO_DAS_LISTAS));
        boolean degradado = pedido.vetor() == null;

        String lexemas = jdbc.sql(LEXEMAS).param("texto", pedido.consulta()).query(String.class).single();
        if (lexemas == null || lexemas.isBlank()) {
            return ResultadoDaBusca.semTermo(pedido.consulta(), k, degradado, (System.nanoTime() - inicio) / 1_000_000);
        }
        String filtro = baseDoFiltro(pedido.escopo()) + (pedido.tipo() == null ? "" : "\n                  AND d.tipo = :tipo");
        String vetorial = degradado ? """
                SELECT CAST(NULL AS bigint) AS id, CAST(NULL AS float8) AS distancia, CAST(NULL AS bigint) AS posicao WHERE FALSE
                """ : """
                SELECT id, distancia, row_number() OVER (ORDER BY distancia, id) AS posicao FROM (
                    SELECT t.id, t.embedding <=> CAST(:vetor AS vector) AS distancia
                    FROM documento_trecho t JOIN documento d ON d.id = t.documento_id
                    WHERE %s AND d.modelo_embedding = :modelo
                    ORDER BY t.embedding <=> CAST(:vetor AS vector) LIMIT %d) v
                """.formatted(filtro, TAMANHO_DAS_LISTAS);
        String sql = """
                WITH vetorial AS MATERIALIZED (%s),
                textual AS (
                    SELECT id, nota, row_number() OVER (ORDER BY nota DESC, id) AS posicao FROM (
                        SELECT t.id, ts_rank_cd(t.tsv, q.consulta) AS nota
                        FROM documento_trecho t JOIN documento d ON d.id = t.documento_id
                             CROSS JOIN (SELECT CAST(:lexemas AS tsquery) AS consulta) q
                        WHERE %s AND t.tsv @@ q.consulta
                        ORDER BY nota DESC, t.id LIMIT %d) x)
                SELECT t.id, t.documento_id, d.titulo AS documento, d.versao, d.tipo, pat.nome_canonico AS parceria,
                       t.pagina_inicio AS pagina, t.pagina_fim,
                       t.secao_caminho AS secao, t.texto, t.tokens, v.posicao AS posicao_vetorial, v.distancia,
                       x.posicao AS posicao_textual, x.nota,
                       coalesce(CAST(1.0 AS float8) / (%d + v.posicao), 0) + coalesce(CAST(1.0 AS float8) / (%d + x.posicao), 0) AS rrf
                FROM documento_trecho t
                JOIN documento d ON d.id = t.documento_id
                LEFT JOIN parceria pa ON pa.id = d.parceria_id
                LEFT JOIN patrocinador pat ON pat.id = pa.patrocinador_id
                LEFT JOIN vetorial v ON v.id = t.id
                LEFT JOIN textual x ON x.id = t.id
                WHERE v.id IS NOT NULL OR x.id IS NOT NULL
                ORDER BY rrf DESC, t.id
                """.formatted(vetorial, filtro, TAMANHO_DAS_LISTAS, CONSTANTE_DO_RRF, CONSTANTE_DO_RRF);

        var consulta = jdbc.sql(sql).param("evento", pedido.escopo().eventoId()).param("cota", pedido.escopo().cotaTipoId())
                .param("parceria", pedido.escopo().parceriaId()).param("lexemas", lexemas);
        if (pedido.tipo() != null) {
            consulta = consulta.param("tipo", pedido.tipo());
        }
        if (!degradado) {
            consulta = consulta.param("vetor", ProvedorDeEmbeddings.comoTextoDoBanco(pedido.vetor())).param("modelo", pedido.modeloDoVetor());
        }
        List<Candidato> todos = consulta.query((rs, n) -> new Candidato(rs.getLong("id"), rs.getLong("documento_id"),
                rs.getString("documento"), rs.getInt("versao"), rs.getString("tipo"), rs.getString("parceria"), rs.getInt("pagina"),
                rs.getInt("pagina_fim"), rs.getString("secao"), rs.getString("texto"), rs.getInt("tokens"),
                inteiroOuNulo(rs, "posicao_vetorial"), decimalOuNulo(rs, "distancia"), inteiroOuNulo(rs, "posicao_textual"),
                decimalOuNulo(rs, "nota"), rs.getDouble("rrf"))).list();

        long deOutroModelo = 0;
        if (!degradado) {
            var contagem = jdbc.sql("""
                            SELECT count(*) FROM documento_trecho t JOIN documento d ON d.id = t.documento_id
                            WHERE %s AND d.modelo_embedding IS DISTINCT FROM :modelo
                            """.formatted(filtro)).param("evento", pedido.escopo().eventoId()).param("cota", pedido.escopo().cotaTipoId())
                    .param("parceria", pedido.escopo().parceriaId()).param("modelo", pedido.modeloDoVetor());
            if (pedido.tipo() != null) {
                contagem = contagem.param("tipo", pedido.tipo());
            }
            deOutroModelo = contagem.query(Long.class).single();
        }
        List<Candidato> listaVetorial = new ArrayList<>(todos.stream().filter(c -> c.posicaoVetorial() != null)
                .sorted(Comparator.comparingInt(Candidato::posicaoVetorial)).toList());
        List<Candidato> listaTextual = new ArrayList<>(todos.stream().filter(c -> c.posicaoTextual() != null)
                .sorted(Comparator.comparingInt(Candidato::posicaoTextual)).toList());
        List<Candidato> escolhidos = todos.stream().limit(k).toList();
        return new ResultadoDaBusca(pedido.consulta(), k, degradado, false, listaVetorial, listaTextual, escolhidos, deOutroModelo,
                (System.nanoTime() - inicio) / 1_000_000);
    }

    private static Integer inteiroOuNulo(java.sql.ResultSet rs, String coluna) throws java.sql.SQLException {
        Object valor = rs.getObject(coluna);
        return valor == null ? null : ((Number) valor).intValue();
    }

    private static Double decimalOuNulo(java.sql.ResultSet rs, String coluna) throws java.sql.SQLException {
        Object valor = rs.getObject(coluna);
        return valor == null ? null : ((Number) valor).doubleValue();
    }
}
