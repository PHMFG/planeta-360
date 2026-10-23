package br.com.rbs.portal.pesquisa;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Consultas e gravações da pesquisa da ativação: a pesquisa da edição, as perguntas da marca e os resultados das aprovadas.
 * Atende o item 5 da Entrega 2. [ISOLAMENTO] Toda consulta de pergunta e de resultado filtra pelo id da parceria; quem
 * decide se o usuário pode ver aquela parceria é o PesquisaService (VerificadorDeAcesso). [PERMISSAO] A regra de quem
 * pode mudar o quê também fica no Service.
 */
@Repository
public class PesquisaRepositorio {

    /** A pergunta como a rota 23 a mostra, com quem enviou e o recado vigente da RBS. */
    public record LinhaDePergunta(Long id, Integer ordem, String texto, String status, String observacaoRbs,
                                  Long enviadaPorId, String enviadaPorNome, String enviadaPorPerfil,
                                  OffsetDateTime enviadaEm) {
    }

    /** A pergunta como o PUT e o PATCH precisam: com a linha travada (FOR UPDATE) até o fim da transação. */
    public record PerguntaTravada(Long id, Long parceriaId, Integer ordem, String texto, String status) {
    }

    public record LinhaDeResultado(Long perguntaId, Integer ordem, String alternativa, BigDecimal valor, String unidade,
                                   Integer baseRespondentes) {
    }

    public record LinhaDePesquisa(Long id, String nome, String escopo, String metodologia, Integer baseRespondentes,
                                  BigDecimal nivelConfianca) {
    }

    // O recado da RBS vem do histórico (não há coluna para ele): a última ALTERACAO_STATUS da pergunta, que só vale
    // enquanto o status atual é o que aquela decisão gerou. Reenviada (ENVIADA) ou ainda sem decisão, o recado fica nulo.
    private static final String PERGUNTAS = """
            SELECT q.id, q.ordem, q.texto, q.status,
                   CASE WHEN h.valor_novo = q.status THEN h.detalhe END AS observacao_rbs,
                   u.id AS enviada_por_id, u.nome AS enviada_por_nome, u.perfil AS enviada_por_perfil, q.enviada_em
            FROM pesquisa_pergunta q
            LEFT JOIN usuario u ON u.id = q.enviada_por
            LEFT JOIN LATERAL (SELECT valor_novo, detalhe FROM historico
                               WHERE entidade = 'pesquisa_pergunta' AND entidade_id = q.id AND acao = 'ALTERACAO_STATUS'
                               ORDER BY id DESC LIMIT 1) h ON TRUE
            """;

    private final JdbcClient jdbc;

    public PesquisaRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------------
    // Leitura (rota 23 e resposta das rotas 24 e 25)
    // ---------------------------------------------------------------------

    /**
     * [PERMISSAO] A aba PESQUISA está liberada para a parceria? Conta como liberada só se estiver liberada E visível
     * (docs/api.md, objeto Aba). Parceria sem a linha da aba conta como não liberada.
     */
    public boolean abaLiberada(long parceriaId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM parceria_aba pa JOIN aba a ON a.id = pa.aba_id
                            WHERE pa.parceria_id = :parceriaId AND a.codigo = 'PESQUISA' AND pa.liberada AND pa.visivel)
                        """)
                .param("parceriaId", parceriaId)
                .query(Boolean.class)
                .single();
    }

    /**
     * A pesquisa da ativação da edição da parceria (escopo ATIVACAO). Se um dia houver mais de uma na mesma edição, vale a
     * mais antiga. Nula enquanto a RBS não abriu a pesquisa daquela edição.
     */
    public Optional<LinhaDePesquisa> pesquisaDaAtivacao(long parceriaId) {
        return jdbc.sql("""
                        SELECT p.id, p.nome, p.escopo, p.metodologia, p.base_respondentes, p.nivel_confianca
                        FROM pesquisa p JOIN parceria pa ON pa.evento_id = p.evento_id
                        WHERE pa.id = :parceriaId AND p.escopo = 'ATIVACAO'
                        ORDER BY p.id LIMIT 1
                        """)
                .param("parceriaId", parceriaId)
                .query(LinhaDePesquisa.class)
                .optional();
    }

    public List<LinhaDePergunta> perguntas(long parceriaId) {
        return jdbc.sql(PERGUNTAS + "WHERE q.parceria_id = :parceriaId ORDER BY q.ordem")
                .param("parceriaId", parceriaId)
                .query(LinhaDePergunta.class)
                .list();
    }

    public Optional<LinhaDePergunta> pergunta(long perguntaId) {
        return jdbc.sql(PERGUNTAS + "WHERE q.id = :id")
                .param("id", perguntaId)
                .query(LinhaDePergunta.class)
                .optional();
    }

    /** [VALIDACAO] Só resultado de pergunta APROVADA aparece; a base de respondentes vem em toda linha. */
    public List<LinhaDeResultado> resultados(long parceriaId) {
        return jdbc.sql("""
                        SELECT r.pergunta_id, q.ordem, r.alternativa, r.valor, r.unidade, r.base_respondentes
                        FROM pesquisa_resultado r JOIN pesquisa_pergunta q ON q.id = r.pergunta_id
                        WHERE q.parceria_id = :parceriaId AND q.status = 'APROVADA'
                        ORDER BY q.ordem, r.id
                        """)
                .param("parceriaId", parceriaId)
                .query(LinhaDeResultado.class)
                .list();
    }

    // ---------------------------------------------------------------------
    // Gravação (rotas 24 e 25)
    // ---------------------------------------------------------------------

    /**
     * Trava a linha da parceria até o fim da transação: dois PUT da mesma marca ao mesmo tempo passam um de cada vez.
     * FOR NO KEY UPDATE não atrapalha quem só cria linhas que apontam para a parceria.
     */
    public void travarParceria(long parceriaId) {
        jdbc.sql("SELECT id FROM parceria WHERE id = :id FOR NO KEY UPDATE").param("id", parceriaId).query().listOfRows();
    }

    /**
     * As perguntas da parceria com as linhas travadas. Se a RBS estiver aprovando uma delas neste instante, o PUT espera a
     * decisão terminar e então vê o status novo (APROVADA), em vez de alterar um texto que acabou de ser aprovado.
     */
    public List<PerguntaTravada> perguntasTravadas(long parceriaId) {
        return jdbc.sql("""
                        SELECT id, parceria_id, ordem, texto, status FROM pesquisa_pergunta
                        WHERE parceria_id = :parceriaId ORDER BY ordem FOR UPDATE
                        """)
                .param("parceriaId", parceriaId)
                .query(PerguntaTravada.class)
                .list();
    }

    public Optional<PerguntaTravada> travarPergunta(long perguntaId) {
        return jdbc.sql("SELECT id, parceria_id, ordem, texto, status FROM pesquisa_pergunta WHERE id = :id FOR UPDATE")
                .param("id", perguntaId)
                .query(PerguntaTravada.class)
                .optional();
    }

    /** Pergunta nova: nasce ENVIADA, esperando a RBS. {@code pesquisaId} é nulo se a RBS ainda não abriu a pesquisa. */
    public long inserir(long parceriaId, Long pesquisaId, int ordem, String texto, long usuarioId) {
        return jdbc.sql("""
                        INSERT INTO pesquisa_pergunta (parceria_id, pesquisa_id, ordem, texto, status, enviada_por, enviada_em)
                        VALUES (:parceriaId, :pesquisaId, :ordem, :texto, 'ENVIADA', :usuarioId, now())
                        RETURNING id
                        """)
                .param("parceriaId", parceriaId)
                .param("pesquisaId", pesquisaId)
                .param("ordem", ordem)
                .param("texto", texto)
                .param("usuarioId", usuarioId)
                .query(Long.class)
                .single();
    }

    /**
     * Pergunta alterada ou devolvida e reenviada: volta a ENVIADA. Se ela ainda não estava ligada a uma pesquisa e agora
     * existe uma, liga (a carga de resultados só aceita pergunta da mesma pesquisa).
     */
    public void reenviar(long perguntaId, Long pesquisaId, String texto, long usuarioId) {
        jdbc.sql("""
                        UPDATE pesquisa_pergunta
                        SET texto = :texto, status = 'ENVIADA', enviada_por = :usuarioId, enviada_em = now(),
                            pesquisa_id = coalesce(pesquisa_id, :pesquisaId)
                        WHERE id = :id
                        """)
                .param("texto", texto)
                .param("usuarioId", usuarioId)
                .param("pesquisaId", pesquisaId)
                .param("id", perguntaId)
                .update();
    }

    /** Tira da lista uma pergunta que ainda não foi aprovada (a aprovada o Service nem deixa chegar aqui). */
    public void apagar(long perguntaId) {
        jdbc.sql("DELETE FROM pesquisa_pergunta WHERE id = :id AND status <> 'APROVADA'").param("id", perguntaId).update();
    }

    /** Decisão da RBS: APROVADA ou RASCUNHO (pedido de ajuste). */
    public void mudarStatus(long perguntaId, String novoStatus) {
        jdbc.sql("UPDATE pesquisa_pergunta SET status = :status WHERE id = :id")
                .param("status", novoStatus)
                .param("id", perguntaId)
                .update();
    }
}
