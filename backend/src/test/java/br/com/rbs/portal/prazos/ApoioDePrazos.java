package br.com.rbs.portal.prazos;

import br.com.rbs.portal.acesso.SessaoDeApi;
import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Ajudas dos testes dos prazos (rotas 62 a 64 e a ferramenta {@code consultar_prazos}): achar um documento do kit, gravar um prazo
 * direto no banco (sem passar pela rota, para os testes do Atlas, que confirmam de verdade) e apagar, no fim, o que o teste gravou.
 * Não faz parte da API.
 */
public final class ApoioDePrazos {

    private ApoioDePrazos() {
    }

    /** O id do documento do kit pelo título e pela versão (o D02 é "Manual de Operações 2027", versão 2). */
    public static long documento(JdbcClient jdbc, String titulo, int versao) {
        return jdbc.sql("SELECT id FROM documento WHERE titulo = :t AND versao = :v").param("t", titulo).param("v", versao)
                .query(Long.class).single();
    }

    public static long evento(JdbcClient jdbc, int edicao) {
        return jdbc.sql("SELECT id FROM evento WHERE nome = 'Planeta Atlântida' AND edicao = :e").param("e", edicao).query(Long.class)
                .single();
    }

    public static int cota(JdbcClient jdbc, String codigo) {
        return jdbc.sql("SELECT id FROM cota_tipo WHERE codigo = :c").param("c", codigo).query(Integer.class).single();
    }

    public static long rbs(JdbcClient jdbc) {
        return jdbc.sql("SELECT id FROM usuario WHERE email = 'b2b@rbs.example'").query(Long.class).single();
    }

    /** O maior id de prazo de agora: o que o teste gravar depois tem id maior, e {@link #apagarDepoisDe} o apaga. */
    public static long ultimoId(JdbcClient jdbc) {
        return jdbc.sql("SELECT coalesce(max(id), 0) FROM prazo").query(Long.class).single();
    }

    /** Apaga o que o teste gravou (a API não apaga prazo, mas o banco deixa; o gatilho só vale em UPDATE). */
    public static void apagarDepoisDe(JdbcClient jdbc, long ultimoId) {
        jdbc.sql("DELETE FROM prazo WHERE id > :u").param("u", ultimoId).update();
    }

    /** Um prazo GLOBAL da edição de 2027, direto no banco. Devolve o id. */
    public static long global(JdbcClient jdbc, String titulo, LocalDate data, Long documentoId, Integer pagina) {
        return inserir(jdbc, "GLOBAL", null, null, titulo, data, null, null, documentoId, pagina);
    }

    /** Um prazo COTA da edição de 2027 para a cota de código dado, direto no banco. */
    public static long daCota(JdbcClient jdbc, String cota, String titulo, LocalDate data, Long documentoId, Integer pagina) {
        return inserir(jdbc, "COTA", cota(jdbc, cota), null, titulo, data, null, null, documentoId, pagina);
    }

    /** Um prazo PARCERIA para a parceria do cliente em 2027 (por exemplo, "Cliente Amarelo"), direto no banco. */
    public static long daParceria(JdbcClient jdbc, String cliente, String titulo, LocalDate data, Long documentoId, Integer pagina) {
        return inserir(jdbc, "PARCERIA", null, SessaoDeApi.parceriaDe2027(jdbc, cliente), titulo, data, null, null, documentoId, pagina);
    }

    public static long inserir(JdbcClient jdbc, String escopo, Integer cotaTipoId, Long parceriaId, String titulo, LocalDate data,
                               LocalTime hora, String descricao, Long documentoId, Integer pagina) {
        return jdbc.sql("""
                        INSERT INTO prazo (evento_id, escopo, cota_tipo_id, parceria_id, titulo, data, hora, descricao, documento_id, pagina,
                                           criado_por)
                        VALUES (:evento, :escopo, CAST(:cota AS smallint), :parceria, :titulo, :data, :hora, :descricao, :documento, :pagina, :rbs)
                        RETURNING id
                        """)
                .param("evento", evento(jdbc, 2027)).param("escopo", escopo).param("cota", cotaTipoId).param("parceria", parceriaId)
                .param("titulo", titulo).param("data", data).param("hora", hora).param("descricao", descricao)
                .param("documento", documentoId).param("pagina", pagina).param("rbs", rbs(jdbc)).query(Long.class).single();
    }

    public static void desativar(JdbcClient jdbc, long prazoId) {
        jdbc.sql("UPDATE prazo SET ativo = FALSE WHERE id = :id").param("id", prazoId).update();
    }
}
