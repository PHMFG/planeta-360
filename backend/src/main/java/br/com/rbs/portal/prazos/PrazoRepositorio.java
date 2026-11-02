package br.com.rbs.portal.prazos;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL dos prazos (rotas 62 a 64). [ATLAS] Cada método é uma consulta ou um comando curto; quem decide a transação é o service. As
 * regras da tabela (escopo, página, alcance contra o documento, só se desativa) moram na V13: aqui só se grava, e o banco recusa o que
 * não pode. A leitura que o Atlas faz (a ferramenta {@code consultar_prazos}) é de outro pacote.
 */
@Repository
public class PrazoRepositorio {

    /** Um prazo com a cota, a parceria, o documento e quem cadastrou já ligados pelo nome. */
    public record Linha(Long id, Long eventoId, String escopo, String cotaCodigo, String cotaNome, Long parceriaId, String patrocinador,
                        String titulo, LocalDate data, LocalTime hora, String descricao, Long documentoId, String documentoTitulo,
                        Integer documentoVersao, String documentoStatus, Integer pagina, boolean ativo, Long criadoPor,
                        String criadoPorNome, String criadoPorPerfil, OffsetDateTime criadoEm) {
    }

    /** O que o service precisa saber do documento de origem para conferir a edição, a página e o alcance. */
    public record DocumentoDeOrigem(Long id, Long eventoId, String escopo, Integer cotaTipoId, String cotaNome, Long parceriaId,
                                    String patrocinador, Integer paginas) {
    }

    /** A edição e a cota de uma parceria, para conferir o prazo de escopo PARCERIA. */
    public record ParceriaDoPrazo(Long id, Long eventoId, Integer cotaTipoId) {
    }

    /** O prazo travado para desativar. */
    public record Travado(Long id, String titulo, LocalDate data, Long parceriaId, boolean ativo) {
    }

    private static final String COLUNAS = """
            SELECT pz.id, pz.evento_id, pz.escopo, ct.codigo AS cota_codigo, ct.nome AS cota_nome, pz.parceria_id,
                   pat.nome_canonico AS patrocinador, pz.titulo, pz.data, pz.hora, pz.descricao, pz.documento_id,
                   d.titulo AS documento_titulo, d.versao AS documento_versao, d.status AS documento_status, pz.pagina, pz.ativo,
                   pz.criado_por, u.nome AS criado_por_nome, pz.criado_por_perfil, pz.criado_em
            FROM prazo pz
            LEFT JOIN cota_tipo ct ON ct.id = pz.cota_tipo_id
            LEFT JOIN parceria pa ON pa.id = pz.parceria_id
            LEFT JOIN patrocinador pat ON pat.id = pa.patrocinador_id
            LEFT JOIN documento d ON d.id = pz.documento_id
            JOIN usuario u ON u.id = pz.criado_por
            """;

    private final JdbcClient jdbc;

    public PrazoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean eventoExiste(long eventoId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM evento WHERE id = :id)").param("id", eventoId).query(Boolean.class).single();
    }

    public Optional<Integer> cotaPorCodigo(String codigo) {
        return jdbc.sql("SELECT id FROM cota_tipo WHERE codigo = :codigo").param("codigo", codigo).query(Integer.class).optional();
    }

    public Optional<ParceriaDoPrazo> parceria(long id) {
        return jdbc.sql("SELECT id, evento_id, cota_tipo_id FROM parceria WHERE id = :id").param("id", id).query(ParceriaDoPrazo.class)
                .optional();
    }

    public Optional<DocumentoDeOrigem> documento(long id) {
        return jdbc.sql("""
                        SELECT d.id, d.evento_id, d.escopo, d.cota_tipo_id, ct.nome AS cota_nome, d.parceria_id,
                               pat.nome_canonico AS patrocinador, d.paginas
                        FROM documento d
                        LEFT JOIN cota_tipo ct ON ct.id = d.cota_tipo_id
                        LEFT JOIN parceria pa ON pa.id = d.parceria_id
                        LEFT JOIN patrocinador pat ON pat.id = pa.patrocinador_id
                        WHERE d.id = :id
                        """)
                .param("id", id).query(DocumentoDeOrigem.class).optional();
    }

    public long inserir(long eventoId, String escopo, Integer cotaTipoId, Long parceriaId, String titulo, LocalDate data, LocalTime hora,
                        String descricao, Long documentoId, Integer pagina, long criadoPor) {
        return jdbc.sql("""
                        INSERT INTO prazo (evento_id, escopo, cota_tipo_id, parceria_id, titulo, data, hora, descricao, documento_id, pagina,
                                           criado_por)
                        VALUES (:evento, :escopo, CAST(:cota AS smallint), :parceria, :titulo, :data, :hora, :descricao, :documento, :pagina,
                                :criadoPor)
                        RETURNING id
                        """)
                .param("evento", eventoId).param("escopo", escopo).param("cota", cotaTipoId).param("parceria", parceriaId)
                .param("titulo", titulo).param("data", data).param("hora", hora).param("descricao", descricao)
                .param("documento", documentoId).param("pagina", pagina).param("criadoPor", criadoPor).query(Long.class).single();
    }

    public Optional<Linha> buscar(long id) {
        return jdbc.sql(COLUNAS + " WHERE pz.id = :id").param("id", id).query(Linha.class).optional();
    }

    /** Os prazos da edição por data, hora (o dia inteiro, sem hora, por último) e título; só os ativos, a menos que se peça todos. */
    public List<Linha> daEdicao(long eventoId, boolean incluirInativos) {
        return jdbc.sql(COLUNAS + " WHERE pz.evento_id = :evento" + (incluirInativos ? "" : " AND pz.ativo")
                        + " ORDER BY pz.data, pz.hora NULLS LAST, pz.titulo, pz.id")
                .param("evento", eventoId).query(Linha.class).list();
    }

    /** Trava a linha do prazo para desativar (dois cliques ao mesmo tempo gravam um histórico só). */
    public Optional<Travado> travar(long id) {
        return jdbc.sql("SELECT id, titulo, data, parceria_id, ativo FROM prazo WHERE id = :id FOR UPDATE").param("id", id)
                .query(Travado.class).optional();
    }

    public void desativar(long id) {
        jdbc.sql("UPDATE prazo SET ativo = FALSE WHERE id = :id").param("id", id).update();
    }
}
