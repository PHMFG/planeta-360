package br.com.rbs.portal.pesquisa;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Consultas e gravações da abertura da pesquisa da edição (rotas 92 a 95). [PERMISSAO] Quem pode chamar é conferido no
 * PesquisaAberturaService (e antes do corpo, no PermissaoAntesDoCorpo). Sem migração: usa as tabelas e o índice
 * {@code uq_pesquisa_ativacao} da V16.
 */
@Repository
public class PesquisaAberturaRepositorio {

    public record LinhaDePesquisaDaEdicao(Long id, Long eventoId, String nome, String escopo, String metodologia,
                                          Integer baseRespondentes, BigDecimal nivelConfianca, int rascunho, int enviadas,
                                          int aprovadas, int resultados) {
    }

    public record LinhaDaFila(Long id, Long parceriaId, String patrocinador, Long eventoId, Integer edicao, Long pesquisaId,
                              Integer ordem, String texto, String status, Long enviadaPorId, String enviadaPorNome,
                              String enviadaPorPerfil, OffsetDateTime enviadaEm) {
    }

    private static final String PESQUISA = """
            SELECT p.id, p.evento_id, p.nome, p.escopo, p.metodologia, p.base_respondentes, p.nivel_confianca,
                   (SELECT count(*) FROM pesquisa_pergunta q WHERE q.pesquisa_id = p.id AND q.status = 'RASCUNHO') AS rascunho,
                   (SELECT count(*) FROM pesquisa_pergunta q WHERE q.pesquisa_id = p.id AND q.status = 'ENVIADA') AS enviadas,
                   (SELECT count(*) FROM pesquisa_pergunta q WHERE q.pesquisa_id = p.id AND q.status = 'APROVADA') AS aprovadas,
                   (SELECT count(*) FROM pesquisa_resultado r WHERE r.pesquisa_id = p.id) AS resultados
            FROM pesquisa p
            """;

    private final JdbcClient jdbc;

    public PesquisaAberturaRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A edição (para a frase de erro) ou vazio se não existir. */
    public Optional<Short> edicaoDoEvento(long eventoId) {
        return jdbc.sql("SELECT edicao FROM evento WHERE id = :id").param("id", eventoId).query(Short.class).optional();
    }

    /** Trava a linha da edição até o fim da transação: duas aberturas ao mesmo tempo passam uma de cada vez. */
    public void travarEdicao(long eventoId) {
        jdbc.sql("SELECT id FROM evento WHERE id = :id FOR NO KEY UPDATE").param("id", eventoId).query().listOfRows();
    }

    /** O nome da pesquisa da ativação, se já aberta (para a frase do 409). */
    public Optional<String> nomeDaAtivacao(long eventoId) {
        return jdbc.sql("SELECT nome FROM pesquisa WHERE evento_id = :e AND escopo = 'ATIVACAO' ORDER BY id LIMIT 1")
                .param("e", eventoId).query(String.class).optional();
    }

    /** O nome já é de outra pesquisa da edição? {@code exceto} é a própria pesquisa (0 se não houver). */
    public boolean nomeJaUsado(long eventoId, String nome, long exceto) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM pesquisa WHERE evento_id = :e AND nome = :nome AND id <> :exceto)")
                .param("e", eventoId).param("nome", nome).param("exceto", exceto).query(Boolean.class).single();
    }

    public List<LinhaDePesquisaDaEdicao> daEdicao(long eventoId) {
        return jdbc.sql(PESQUISA + "WHERE p.evento_id = :e ORDER BY CASE p.escopo WHEN 'ATIVACAO' THEN 0 ELSE 1 END, p.id")
                .param("e", eventoId).query(LinhaDePesquisaDaEdicao.class).list();
    }

    public Optional<LinhaDePesquisaDaEdicao> buscar(long pesquisaId) {
        return jdbc.sql(PESQUISA + "WHERE p.id = :id").param("id", pesquisaId).query(LinhaDePesquisaDaEdicao.class).optional();
    }

    /** Trava a linha da pesquisa (FOR UPDATE) e a devolve. */
    public Optional<LinhaDePesquisaDaEdicao> travar(long pesquisaId) {
        jdbc.sql("SELECT id FROM pesquisa WHERE id = :id FOR UPDATE").param("id", pesquisaId).query().listOfRows();
        return buscar(pesquisaId);
    }

    public long inserir(long eventoId, String nome, String escopo, String metodologia, Long base, BigDecimal confianca) {
        return jdbc.sql("""
                        INSERT INTO pesquisa (evento_id, nome, escopo, metodologia, base_respondentes, nivel_confianca)
                        VALUES (:e, :nome, :escopo, :metodologia, :base, :confianca) RETURNING id
                        """)
                .param("e", eventoId).param("nome", nome).param("escopo", escopo)
                .param("metodologia", metodologia).param("base", base == null ? null : base.intValue())
                .param("confianca", confianca).query(Long.class).single();
    }

    /**
     * Liga à nova pesquisa da ativação as perguntas que as marcas da edição já enviaram antes de ela existir. O gatilho
     * {@code rn_pergunta_pesquisa} confere a edição e o escopo. Devolve quantas ligou.
     */
    public int ligarPerguntasSemPesquisa(long pesquisaId, long eventoId) {
        return jdbc.sql("""
                        UPDATE pesquisa_pergunta q SET pesquisa_id = :pesquisa
                        FROM parceria pa WHERE q.parceria_id = pa.id AND pa.evento_id = :e AND q.pesquisa_id IS NULL
                        """).param("pesquisa", pesquisaId).param("e", eventoId).update();
    }

    public void atualizar(long pesquisaId, String nome, String metodologia, Long base, BigDecimal confianca) {
        jdbc.sql("""
                        UPDATE pesquisa SET nome = :nome, metodologia = :metodologia, base_respondentes = :base,
                                            nivel_confianca = :confianca WHERE id = :id
                        """)
                .param("nome", nome).param("metodologia", metodologia).param("base", base == null ? null : base.intValue())
                .param("confianca", confianca).param("id", pesquisaId).update();
    }

    // ---------------------------------------------------------------------
    // Rota 95: a fila
    // ---------------------------------------------------------------------

    public long contarFila(String status, Long eventoId) {
        return jdbc.sql("""
                        SELECT count(*) FROM pesquisa_pergunta q JOIN parceria pa ON pa.id = q.parceria_id
                        WHERE q.status = :status AND (CAST(:e AS bigint) IS NULL OR pa.evento_id = :e)
                        """).param("status", status).param("e", eventoId).query(Long.class).single();
    }

    /** As mais antigas primeiro (a que espera há mais tempo sai no topo). */
    public List<LinhaDaFila> fila(String status, Long eventoId, int limite, int deslocamento) {
        return jdbc.sql("""
                        SELECT q.id, q.parceria_id, pt.nome_canonico AS patrocinador, ev.id AS evento_id, ev.edicao,
                               q.pesquisa_id, q.ordem, q.texto, q.status,
                               u.id AS enviada_por_id, u.nome AS enviada_por_nome, u.perfil AS enviada_por_perfil, q.enviada_em
                        FROM pesquisa_pergunta q
                        JOIN parceria pa ON pa.id = q.parceria_id
                        JOIN patrocinador pt ON pt.id = pa.patrocinador_id
                        JOIN evento ev ON ev.id = pa.evento_id
                        LEFT JOIN usuario u ON u.id = q.enviada_por
                        WHERE q.status = :status AND (CAST(:e AS bigint) IS NULL OR pa.evento_id = :e)
                        ORDER BY q.enviada_em NULLS LAST, q.id
                        LIMIT :limite OFFSET :deslocamento
                        """)
                .param("status", status).param("e", eventoId).param("limite", limite).param("deslocamento", deslocamento)
                .query(LinhaDaFila.class).list();
    }
}
