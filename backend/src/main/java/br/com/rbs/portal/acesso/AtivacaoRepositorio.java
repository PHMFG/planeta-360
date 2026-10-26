package br.com.rbs.portal.acesso;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL do primeiro acesso: os códigos de ativação, a criação da conta e o vínculo com as parcerias.
 * Atende o item 3 da Entrega 2. [PERMISSAO] Do código só existe o hash SHA-256 (coluna codigo_hash); o texto
 * que a RBS repassa nunca chega aqui. Quem chama já abriu a transação (AtivacaoService).
 */
@Repository
public class AtivacaoRepositorio {

    /** Um código ainda não usado nem invalidado, com o que a ativação precisa saber dele. */
    public record CodigoVigente(Long id, Long emailAutorizadoId, Long parceriaId, String email, String vinculo,
                                String codigoHash, int tentativas, boolean vencido) {
    }

    /** Uma autorização de e-mail que não foi revogada. */
    public record Autorizacao(Long id, Long parceriaId, String vinculo) {
    }

    private final JdbcClient jdbc;

    public AtivacaoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Os códigos pendentes do e-mail, em todas as parcerias, de autorização não revogada. FOR UPDATE: duas ativações
     * com o mesmo código ao mesmo tempo esperam uma pela outra, e só a primeira vale.
     */
    public List<CodigoVigente> codigosVigentes(String email) {
        return jdbc.sql("""
                        SELECT ca.id, ca.email_autorizado_id, ea.parceria_id, ea.email, ea.vinculo, ca.codigo_hash,
                               ca.tentativas, (ca.expira_em <= now()) AS vencido
                        FROM codigo_ativacao ca
                        JOIN email_autorizado ea ON ea.id = ca.email_autorizado_id
                        WHERE lower(ea.email) = lower(:email) AND ea.revogado_em IS NULL
                          AND ca.usado_em IS NULL AND ca.invalidado_em IS NULL
                        ORDER BY ca.id
                        FOR UPDATE OF ca
                        """)
                .param("email", email)
                .query(CodigoVigente.class)
                .list();
    }

    /**
     * [PERMISSAO] Conta um erro de digitação em cada código pendente do e-mail. Para de contar no limite: o código
     * fica bloqueado e a RBS precisa gerar outro (que nasce com zero erros).
     */
    public void registrarErro(String email, int limite) {
        jdbc.sql("""
                        UPDATE codigo_ativacao ca SET tentativas = ca.tentativas + 1
                        FROM email_autorizado ea
                        WHERE ea.id = ca.email_autorizado_id AND lower(ea.email) = lower(:email) AND ea.revogado_em IS NULL
                          AND ca.usado_em IS NULL AND ca.invalidado_em IS NULL AND ca.tentativas < :limite
                        """)
                .param("email", email)
                .param("limite", limite)
                .update();
    }

    /** As autorizações vivas do e-mail (uma por parceria): a pessoa ganha acesso a todas ao ativar a conta. */
    public List<Autorizacao> autorizacoesVivas(String email) {
        return jdbc.sql("""
                        SELECT id, parceria_id, vinculo FROM email_autorizado
                        WHERE lower(email) = lower(:email) AND revogado_em IS NULL
                        ORDER BY id
                        """)
                .param("email", email)
                .query(Autorizacao.class)
                .list();
    }

    public long criarUsuario(String nome, String email, String senhaHash) {
        return jdbc.sql("""
                        INSERT INTO usuario (nome, email, senha_hash, perfil) VALUES (:nome, :email, :senhaHash, 'ADMIN_PATROCINADOR')
                        RETURNING id
                        """)
                .param("nome", nome)
                .param("email", email)
                .param("senhaHash", senhaHash)
                .query(Long.class)
                .single();
    }

    /** Conta que existia sem senha (nunca fez o primeiro acesso): define a senha e, se veio, o nome. */
    public void definirSenha(long usuarioId, String senhaHash, String nome) {
        jdbc.sql("UPDATE usuario SET senha_hash = :senhaHash, nome = coalesce(:nome, nome) WHERE id = :usuarioId")
                .param("senhaHash", senhaHash)
                .param("nome", nome)
                .param("usuarioId", usuarioId)
                .update();
    }

    /** O gatilho rn_vinculo_exige_autorizacao confere que o e-mail está autorizado e não revogado nesta parceria. */
    public void vincular(long parceriaId, long usuarioId, String vinculo) {
        jdbc.sql("""
                        INSERT INTO parceria_usuario (parceria_id, usuario_id, vinculo) VALUES (:parceriaId, :usuarioId, :vinculo)
                        ON CONFLICT DO NOTHING
                        """)
                .param("parceriaId", parceriaId)
                .param("usuarioId", usuarioId)
                .param("vinculo", vinculo)
                .update();
    }

    /** Uso único: fecha os códigos pendentes dessas autorizações (o usado e os das outras parcerias do mesmo e-mail). */
    public void marcarUsados(List<Long> emailAutorizadoIds) {
        jdbc.sql("""
                        UPDATE codigo_ativacao SET usado_em = now()
                        WHERE email_autorizado_id IN (:ids) AND usado_em IS NULL AND invalidado_em IS NULL
                        """)
                .param("ids", emailAutorizadoIds)
                .update();
    }

    /** Reenvio: só um código pode estar vigente por autorização (índice uq_codigo_vigente), então o anterior cai. */
    public void invalidarVigentes(long emailAutorizadoId) {
        jdbc.sql("""
                        UPDATE codigo_ativacao SET invalidado_em = now()
                        WHERE email_autorizado_id = :id AND usado_em IS NULL AND invalidado_em IS NULL
                        """)
                .param("id", emailAutorizadoId)
                .update();
    }

    /** Guarda só o hash. Devolve a validade, que o banco calculou (agora + dias). */
    public OffsetDateTime inserirCodigo(long emailAutorizadoId, String codigoHash, int diasDeValidade) {
        return jdbc.sql("""
                        INSERT INTO codigo_ativacao (email_autorizado_id, codigo_hash, expira_em)
                        VALUES (:id, :hash, now() + make_interval(days => :dias))
                        RETURNING expira_em
                        """)
                .param("id", emailAutorizadoId)
                .param("hash", codigoHash)
                .param("dias", diasDeValidade)
                .query(OffsetDateTime.class)
                .single();
    }
}
