package br.com.rbs.portal.parceria;

import br.com.rbs.portal.comum.FusoBrasilia;
import br.com.rbs.portal.parceria.ParceriaDtos.EmailAutorizadoDto;
import br.com.rbs.portal.parceria.ParceriaDtos.EmailResumoDto;
import br.com.rbs.portal.parceria.ParceriaDtos.UsuarioResumoDto;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL dos e-mails autorizados de cada parceria (o cadastro prévio que libera o primeiro acesso).
 * Atende o item 3 da Entrega 2. [PERMISSAO] Revogar não apaga a linha: grava revogado_em e o gatilho rn_email_revogado
 * remove o vínculo e invalida os códigos pendentes. Reautorizar zera revogado_em e o gatilho devolve o vínculo.
 */
@Repository
public class EmailAutorizadoRepositorio {

    /** A linha crua, para as regras decidirem o que fazer. ativo = já existe vínculo com a parceria. */
    public record Linha(Long id, String email, String vinculo, boolean revogado, boolean ativo) {
    }

    /** A conta de usuário que tem este e-mail, se existir. comSenha = já fez o primeiro acesso. */
    public record Conta(Long id, String perfil, boolean comSenha) {
    }

    private record LinhaDeListagem(Long id, String email, String vinculo, String situacao, Long usuarioId,
                                   Long autorizadoPorId, String autorizadoPorNome, String autorizadoPorPerfil,
                                   OffsetDateTime autorizadoEm, Long revogadoPorId, String revogadoPorNome,
                                   String revogadoPorPerfil, OffsetDateTime revogadoEm) {
    }

    // PENDENTE = autorizado e ainda sem vínculo; ATIVO = com vínculo (a pessoa já fez o primeiro acesso).
    private static final String SELECT_DETALHADO = """
            SELECT ea.id, ea.email, ea.vinculo,
                   CASE WHEN ea.revogado_em IS NOT NULL THEN 'REVOGADO'
                        WHEN EXISTS (SELECT 1 FROM parceria_usuario pu JOIN usuario uv ON uv.id = pu.usuario_id
                                     WHERE pu.parceria_id = ea.parceria_id AND lower(uv.email) = lower(ea.email)) THEN 'ATIVO'
                        ELSE 'PENDENTE' END AS situacao,
                   cu.id AS usuario_id,
                   aut.id AS autorizado_por_id, aut.nome AS autorizado_por_nome, aut.perfil AS autorizado_por_perfil,
                   ea.autorizado_em,
                   rev.id AS revogado_por_id, rev.nome AS revogado_por_nome, rev.perfil AS revogado_por_perfil, ea.revogado_em
            FROM email_autorizado ea
            JOIN usuario aut ON aut.id = ea.autorizado_por
            LEFT JOIN usuario rev ON rev.id = ea.revogado_por
            LEFT JOIN usuario cu ON lower(cu.email) = lower(ea.email)
            """;

    private static final String SELECT_LINHA = """
            SELECT ea.id, ea.email, ea.vinculo, (ea.revogado_em IS NOT NULL) AS revogado,
                   EXISTS (SELECT 1 FROM parceria_usuario pu JOIN usuario u ON u.id = pu.usuario_id
                           WHERE pu.parceria_id = ea.parceria_id AND lower(u.email) = lower(ea.email)) AS ativo
            FROM email_autorizado ea
            """;

    private final JdbcClient jdbc;

    public EmailAutorizadoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Rota 11: todos os e-mails da parceria, inclusive os revogados. */
    public List<EmailAutorizadoDto> listar(long parceriaId) {
        return jdbc.sql(SELECT_DETALHADO + " WHERE ea.parceria_id = :parceriaId ORDER BY ea.id")
                .param("parceriaId", parceriaId)
                .query(LinhaDeListagem.class)
                .list().stream().map(EmailAutorizadoRepositorio::paraDto).toList();
    }

    public EmailAutorizadoDto buscar(long emailId) {
        return paraDto(jdbc.sql(SELECT_DETALHADO + " WHERE ea.id = :id")
                .param("id", emailId)
                .query(LinhaDeListagem.class)
                .single());
    }

    /** Rota 9: só os e-mails não revogados, no formato curto. */
    public List<EmailResumoDto> vigentes(long parceriaId) {
        return listar(parceriaId).stream()
                .filter(e -> !"REVOGADO".equals(e.situacao()))
                .map(e -> new EmailResumoDto(e.id(), e.email(), e.vinculo(), e.situacao()))
                .toList();
    }

    /** O e-mail desta parceria (sem diferenciar maiúsculas, como o índice uq_email_autorizado). FOR UPDATE: trava a linha. */
    public Optional<Linha> buscarPorEmail(long parceriaId, String email) {
        return jdbc.sql(SELECT_LINHA + " WHERE ea.parceria_id = :parceriaId AND lower(ea.email) = lower(:email) FOR UPDATE OF ea")
                .param("parceriaId", parceriaId)
                .param("email", email)
                .query(Linha.class)
                .optional();
    }

    /** O e-mail pelo id, mas só se for desta parceria: o id de outra parceria responde "não existe". */
    public Optional<Linha> buscarPorId(long parceriaId, long emailId) {
        return jdbc.sql(SELECT_LINHA + " WHERE ea.parceria_id = :parceriaId AND ea.id = :id FOR UPDATE OF ea")
                .param("parceriaId", parceriaId)
                .param("id", emailId)
                .query(Linha.class)
                .optional();
    }

    public long inserir(long parceriaId, String email, String vinculo, long autorizadoPor) {
        return jdbc.sql("""
                        INSERT INTO email_autorizado (parceria_id, email, vinculo, autorizado_por)
                        VALUES (:parceriaId, :email, :vinculo, :autorizadoPor)
                        RETURNING id
                        """)
                .param("parceriaId", parceriaId)
                .param("email", email)
                .param("vinculo", vinculo)
                .param("autorizadoPor", autorizadoPor)
                .query(Long.class)
                .single();
    }

    /** Reautorizar: zera a revogação, grava quem autorizou agora e o vínculo escolhido. O gatilho devolve o acesso de quem já tem conta. */
    public void reautorizar(long emailId, String vinculo, long autorizadoPor) {
        jdbc.sql("""
                        UPDATE email_autorizado SET revogado_em = NULL, revogado_por = NULL, vinculo = :vinculo,
                                                    autorizado_por = :autorizadoPor, autorizado_em = now()
                        WHERE id = :id
                        """)
                .param("vinculo", vinculo)
                .param("autorizadoPor", autorizadoPor)
                .param("id", emailId)
                .update();
    }

    public void atualizarVinculo(long emailId, String vinculo) {
        jdbc.sql("UPDATE email_autorizado SET vinculo = :vinculo WHERE id = :id")
                .param("vinculo", vinculo)
                .param("id", emailId)
                .update();
    }

    /** Revoga sem apagar. O gatilho rn_email_revogado remove o vínculo e invalida os códigos pendentes. */
    public void revogar(long emailId, long revogadoPor) {
        jdbc.sql("UPDATE email_autorizado SET revogado_em = now(), revogado_por = :revogadoPor WHERE id = :id")
                .param("revogadoPor", revogadoPor)
                .param("id", emailId)
                .update();
    }

    public Optional<Conta> contaDoEmail(String email) {
        return jdbc.sql("SELECT id, perfil, (senha_hash IS NOT NULL) AS com_senha FROM usuario WHERE lower(email) = lower(:email)")
                .param("email", email)
                .query(Conta.class)
                .optional();
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

    private static EmailAutorizadoDto paraDto(LinhaDeListagem l) {
        UsuarioResumoDto autorizadoPor = new UsuarioResumoDto(l.autorizadoPorId(), l.autorizadoPorNome(), l.autorizadoPorPerfil());
        UsuarioResumoDto revogadoPor = l.revogadoPorId() == null ? null
                : new UsuarioResumoDto(l.revogadoPorId(), l.revogadoPorNome(), l.revogadoPorPerfil());
        return new EmailAutorizadoDto(l.id(), l.email(), l.vinculo(), l.situacao(), l.usuarioId(), autorizadoPor,
                FusoBrasilia.em(l.autorizadoEm()), revogadoPor, FusoBrasilia.em(l.revogadoEm()));
    }
}
