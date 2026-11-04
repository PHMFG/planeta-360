package br.com.rbs.portal.cortesias;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL da aba Cortesias (rotas 80 a 85). [ISOLAMENTO] Toda consulta recebe o id da parceria; quem decide se o usuário pode vê-la é o
 * CortesiaService. [CORRIDA] A cota é a trava: toda gravação que mexe no que uma cota tem destinado primeiro faz
 * {@code SELECT ... FOR UPDATE} na linha dela ({@link #travarCota}), e só então soma o destinado e confere. O gatilho
 * {@code rn_cortesia_limite} do banco trava a mesma linha e é a rede de segurança. [DADO PESSOAL] O nome do destinatário só passa
 * por aqui para a tabela {@code cortesia_destinacao} e de volta para a rota 80; nenhuma outra consulta o seleciona.
 */
@Repository
public class CortesiaRepositorio {

    public record ParceriaBase(Long id, Long eventoId, String situacao) {
    }

    public record LinhaDaCota(Long id, String setor, String dia, Integer quantidade, Long destinadas, String acesso,
                              boolean comShuttle) {
    }

    public record LinhaDaDestinacao(Long id, Long cotaId, String destinatario, Integer quantidade, Long usuarioId, String usuarioNome,
                                    String usuarioPerfil, OffsetDateTime registradoEm) {
        @Override
        public String toString() {
            return "LinhaDaDestinacao[id=" + id + ", destinatario=(omitido)]";
        }
    }

    public record LinhaDeTotais(Long cota, Long destinadas, BigDecimal percentual) {
    }

    /** A cota travada: o id, o setor e o dia (para a frase do histórico) e o que ela tem hoje. */
    public record CotaTravada(Long id, Integer quantidade, boolean comShuttle, String acesso) {
    }

    /** Uma destinação de uma parceria, achada pelo id: de que cota é e quanto tem (sem o nome). */
    public record DestinacaoDaParceria(Long id, Long cotaId, String setor, String dia) {
    }

    public record DestinacaoTravada(Long id, Integer quantidade) {
    }

    public record EventoBase(Long id, Integer edicao, LocalDate cortesiasPrazo) {
    }

    private final JdbcClient jdbc;

    public CortesiaRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------------
    // Leitura
    // ---------------------------------------------------------------------

    public Optional<ParceriaBase> parceria(long parceriaId) {
        return jdbc.sql("SELECT id, evento_id, situacao FROM parceria WHERE id = :id")
                .param("id", parceriaId).query(ParceriaBase.class).optional();
    }

    public Optional<EventoBase> evento(long eventoId) {
        return jdbc.sql("SELECT id, edicao, cortesias_prazo FROM evento WHERE id = :id")
                .param("id", eventoId).query(EventoBase.class).optional();
    }

    public Optional<EventoBase> eventoTravado(long eventoId) {
        return jdbc.sql("SELECT id, edicao, cortesias_prazo FROM evento WHERE id = :id FOR UPDATE")
                .param("id", eventoId).query(EventoBase.class).optional();
    }

    /** As cotas da parceria com o que já foi destinado, na ordem da tela: setor (Arena, Camarote, Premium, Lounge) e dia. */
    public List<LinhaDaCota> cotas(long parceriaId) {
        return jdbc.sql("""
                        SELECT cc.id, cc.setor, cc.dia, cc.quantidade, CAST(coalesce(d.destinadas, 0) AS bigint) AS destinadas,
                               cc.acesso, cc.com_shuttle
                        FROM cortesia_cota cc
                        LEFT JOIN (SELECT cortesia_cota_id, sum(quantidade) AS destinadas FROM cortesia_destinacao
                                   GROUP BY cortesia_cota_id) d ON d.cortesia_cota_id = cc.id
                        WHERE cc.parceria_id = :parceriaId
                        ORDER BY CASE cc.setor WHEN 'ARENA' THEN 1 WHEN 'CAMAROTE' THEN 2 WHEN 'PREMIUM' THEN 3 ELSE 4 END,
                                 CASE cc.dia WHEN 'PASSAPORTE' THEN 1 WHEN 'SEXTA' THEN 2 ELSE 3 END, cc.id
                        """)
                .param("parceriaId", parceriaId).query(LinhaDaCota.class).list();
    }

    /** As destinações da parceria, da mais antiga para a mais nova, com quem as registrou. */
    public List<LinhaDaDestinacao> destinacoes(long parceriaId) {
        return jdbc.sql("""
                        SELECT d.id, d.cortesia_cota_id AS cota_id, d.destinatario_nome AS destinatario, d.quantidade,
                               u.id AS usuario_id, u.nome AS usuario_nome, u.perfil AS usuario_perfil, d.registrado_em
                        FROM cortesia_destinacao d
                        JOIN cortesia_cota cc ON cc.id = d.cortesia_cota_id
                        JOIN usuario u ON u.id = d.registrado_por
                        WHERE cc.parceria_id = :parceriaId
                        ORDER BY d.registrado_em, d.id
                        """)
                .param("parceriaId", parceriaId).query(LinhaDaDestinacao.class).list();
    }

    /** Os mesmos números de {@code cortesias} na rota 29 (a visão vw_cortesias, com fn_pct_truncado). Vazio se não há cota. */
    public Optional<LinhaDeTotais> totais(long parceriaId) {
        return jdbc.sql("""
                        SELECT CAST(disponiveis AS bigint) AS cota, CAST(destinadas AS bigint) AS destinadas,
                               fn_pct_truncado(100.0 * destinadas / nullif(disponiveis, 0)) AS percentual
                        FROM vw_cortesias WHERE parceria_id = :parceriaId
                        """)
                .param("parceriaId", parceriaId).query(LinhaDeTotais.class).optional();
    }

    public LocalDate prazoDaEdicao(long eventoId) {
        return jdbc.sql("SELECT cortesias_prazo FROM evento WHERE id = :id").param("id", eventoId)
                .query(LocalDate.class).optional().orElse(null);
    }

    // ---------------------------------------------------------------------
    // Cota (rota 81) e destinação (rotas 83 a 85)
    // ---------------------------------------------------------------------

    /** A cota da parceria naquele setor e dia, com a linha travada até o fim da transação. Vazio se a parceria não tem essa cota. */
    public Optional<CotaTravada> travarCota(long parceriaId, String setor, String dia) {
        return jdbc.sql("""
                        SELECT id, quantidade, com_shuttle, acesso FROM cortesia_cota
                        WHERE parceria_id = :parceriaId AND setor = :setor AND dia = :dia FOR UPDATE
                        """)
                .param("parceriaId", parceriaId).param("setor", setor).param("dia", dia).query(CotaTravada.class).optional();
    }

    /** A cota pelo id, travada. Vazia se a cota foi retirada no meio do caminho. */
    public Optional<CotaTravada> travarCotaPorId(long cotaId) {
        return jdbc.sql("SELECT id, quantidade, com_shuttle, acesso FROM cortesia_cota WHERE id = :id FOR UPDATE")
                .param("id", cotaId).query(CotaTravada.class).optional();
    }

    public long destinadas(long cotaId) {
        return jdbc.sql("SELECT CAST(coalesce(sum(quantidade), 0) AS bigint) FROM cortesia_destinacao WHERE cortesia_cota_id = :id")
                .param("id", cotaId).query(Long.class).single();
    }

    /** O que as outras destinações da cota já usam (a de {@code exceto} não conta: é a que está sendo mudada). */
    public long destinadasExceto(long cotaId, long exceto) {
        return jdbc.sql("""
                        SELECT CAST(coalesce(sum(quantidade), 0) AS bigint) FROM cortesia_destinacao
                        WHERE cortesia_cota_id = :id AND id <> :exceto
                        """)
                .param("id", cotaId).param("exceto", exceto).query(Long.class).single();
    }

    public long inserirCota(long parceriaId, String setor, String dia, int quantidade, boolean comShuttle, String acesso) {
        return jdbc.sql("""
                        INSERT INTO cortesia_cota (parceria_id, setor, dia, quantidade, com_shuttle, acesso)
                        VALUES (:parceriaId, :setor, :dia, :quantidade, :comShuttle, :acesso) RETURNING id
                        """)
                .param("parceriaId", parceriaId).param("setor", setor).param("dia", dia).param("quantidade", quantidade)
                .param("comShuttle", comShuttle).param("acesso", acesso).query(Long.class).single();
    }

    public void atualizarCota(long cotaId, int quantidade, boolean comShuttle, String acesso) {
        jdbc.sql("UPDATE cortesia_cota SET quantidade = :quantidade, com_shuttle = :comShuttle, acesso = :acesso WHERE id = :id")
                .param("quantidade", quantidade).param("comShuttle", comShuttle).param("acesso", acesso).param("id", cotaId).update();
    }

    public void apagarCota(long cotaId) {
        jdbc.sql("DELETE FROM cortesia_cota WHERE id = :id").param("id", cotaId).update();
    }

    /** A destinação, se for da parceria. Sem trava: serve para achar a cota, que é quem se trava primeiro. */
    public Optional<DestinacaoDaParceria> destinacaoDaParceria(long parceriaId, long destinacaoId) {
        return jdbc.sql("""
                        SELECT d.id, d.cortesia_cota_id AS cota_id, cc.setor, cc.dia
                        FROM cortesia_destinacao d JOIN cortesia_cota cc ON cc.id = d.cortesia_cota_id
                        WHERE d.id = :id AND cc.parceria_id = :parceriaId
                        """)
                .param("id", destinacaoId).param("parceriaId", parceriaId).query(DestinacaoDaParceria.class).optional();
    }

    /** A destinação travada (com a cota já travada por quem chama). Vazia se foi retirada no meio do caminho. */
    public Optional<DestinacaoTravada> travarDestinacao(long destinacaoId) {
        return jdbc.sql("SELECT id, quantidade FROM cortesia_destinacao WHERE id = :id FOR UPDATE")
                .param("id", destinacaoId).query(DestinacaoTravada.class).optional();
    }

    public long inserirDestinacao(long cotaId, String destinatario, int quantidade, long usuarioId) {
        return jdbc.sql("""
                        INSERT INTO cortesia_destinacao (cortesia_cota_id, destinatario_nome, quantidade, registrado_por)
                        VALUES (:cotaId, :nome, :quantidade, :usuarioId) RETURNING id
                        """)
                .param("cotaId", cotaId).param("nome", destinatario).param("quantidade", quantidade).param("usuarioId", usuarioId)
                .query(Long.class).single();
    }

    public void atualizarDestinacao(long destinacaoId, String destinatario, int quantidade) {
        jdbc.sql("UPDATE cortesia_destinacao SET destinatario_nome = :nome, quantidade = :quantidade WHERE id = :id")
                .param("nome", destinatario).param("quantidade", quantidade).param("id", destinacaoId).update();
    }

    /** O nome atual, só para a rota 84 decidir se o nome mudou (nunca vai ao histórico nem ao log). */
    public String nomeDaDestinacao(long destinacaoId) {
        return jdbc.sql("SELECT destinatario_nome FROM cortesia_destinacao WHERE id = :id").param("id", destinacaoId)
                .query(String.class).single();
    }

    public void apagarDestinacao(long destinacaoId) {
        jdbc.sql("DELETE FROM cortesia_destinacao WHERE id = :id").param("id", destinacaoId).update();
    }

    // ---------------------------------------------------------------------
    // Prazo (rota 82)
    // ---------------------------------------------------------------------

    public void gravarPrazo(long eventoId, LocalDate prazo) {
        jdbc.sql("UPDATE evento SET cortesias_prazo = CAST(:prazo AS date) WHERE id = :id").param("prazo", prazo).param("id", eventoId).update();
    }
}
