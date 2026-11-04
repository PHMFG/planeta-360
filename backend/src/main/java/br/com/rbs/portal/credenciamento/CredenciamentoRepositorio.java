package br.com.rbs.portal.credenciamento;

import br.com.rbs.portal.credenciamento.CredenciamentoDtos.CredencialDto;
import br.com.rbs.portal.parceria.ParceriaDtos.EventoCompletoDto;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL das credenciais e do credenciamento da edição (rotas 77 a 79). [CREDENCIAMENTO] Quem decide a transação é o service. */
@Repository
public class CredenciamentoRepositorio {

    /** A parceria, a edição dela e o credenciamento da edição. */
    public record Base(Long parceriaId, Long eventoId, String situacao, String openStaffUrl, LocalDate inicio, LocalDate fim) {
    }

    /** O credenciamento de uma edição, como está agora. */
    public record DaEdicao(String url, LocalDate inicio, LocalDate fim) {
    }

    private final JdbcClient jdbc;

    public CredenciamentoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Trava a parceria (dois PATCH da mesma grade não se misturam) e lê a edição dela. */
    public Optional<Base> travarParceria(long parceriaId) {
        return jdbc.sql("""
                        SELECT pa.id AS parceria_id, pa.evento_id, pa.situacao, e.credenciamento_url AS open_staff_url,
                               e.credenciamento_inicio AS inicio, e.credenciamento_fim AS fim
                        FROM parceria pa JOIN evento e ON e.id = pa.evento_id WHERE pa.id = :id FOR UPDATE OF pa
                        """)
                .param("id", parceriaId).query(Base.class).optional();
    }

    public Optional<Base> base(long parceriaId) {
        return jdbc.sql("""
                        SELECT pa.id AS parceria_id, pa.evento_id, pa.situacao, e.credenciamento_url AS open_staff_url,
                               e.credenciamento_inicio AS inicio, e.credenciamento_fim AS fim
                        FROM parceria pa JOIN evento e ON e.id = pa.evento_id WHERE pa.id = :id
                        """)
                .param("id", parceriaId).query(Base.class).optional();
    }

    public List<CredencialDto> credenciais(long parceriaId) {
        return jdbc.sql("""
                        SELECT id, setor, beneficiario, quantidade_por_dia FROM credencial_cota WHERE parceria_id = :id
                        ORDER BY array_position(ARRAY['ARENA','CAMAROTE','BAIXO_CAMAROTE','PREMIUM','SALA_IMPRENSA','SALA_CONTEUDO'], setor),
                                 array_position(ARRAY['MARCA','AGENCIA','EQUIPE_CONTEUDO','GERAL'], beneficiario)
                        """)
                .param("id", parceriaId).query(CredencialDto.class).list();
    }

    public Optional<CredencialDto> credencial(long parceriaId, String setor, String beneficiario) {
        return jdbc.sql("SELECT id, setor, beneficiario, quantidade_por_dia FROM credencial_cota WHERE parceria_id = :p AND setor = :s AND beneficiario = :b")
                .param("p", parceriaId).param("s", setor).param("b", beneficiario).query(CredencialDto.class).optional();
    }

    public long inserir(long parceriaId, String setor, String beneficiario, int quantidade) {
        return jdbc.sql("INSERT INTO credencial_cota (parceria_id, setor, beneficiario, quantidade_por_dia) VALUES (:p, :s, :b, :q) RETURNING id")
                .param("p", parceriaId).param("s", setor).param("b", beneficiario).param("q", quantidade).query(Long.class).single();
    }

    public void alterar(long id, int quantidade) {
        jdbc.sql("UPDATE credencial_cota SET quantidade_por_dia = :q WHERE id = :id").param("q", quantidade).param("id", id).update();
    }

    public void remover(long id) {
        jdbc.sql("DELETE FROM credencial_cota WHERE id = :id").param("id", id).update();
    }

    // ---------------------------------------------------------------------
    // A edição (rota 79)
    // ---------------------------------------------------------------------

    /** Trava a edição e lê o credenciamento dela; vazio = a edição não existe. */
    public Optional<DaEdicao> travarEdicao(long eventoId) {
        return jdbc.sql("SELECT credenciamento_url AS url, credenciamento_inicio AS inicio, credenciamento_fim AS fim FROM evento WHERE id = :id FOR UPDATE")
                .param("id", eventoId).query(DaEdicao.class).optional();
    }

    public void gravarCredenciamento(long eventoId, String url, LocalDate inicio, LocalDate fim) {
        jdbc.sql("UPDATE evento SET credenciamento_url = :url, credenciamento_inicio = :inicio, credenciamento_fim = :fim WHERE id = :id")
                .param("url", url).param("inicio", inicio).param("fim", fim).param("id", eventoId).update();
    }

    public EventoCompletoDto evento(long eventoId) {
        return jdbc.sql("""
                        SELECT id, nome, edicao, data_inicio, data_fim, local, credenciamento_url, credenciamento_inicio, credenciamento_fim, cortesias_prazo
                        FROM evento WHERE id = :id
                        """)
                .param("id", eventoId).query(EventoCompletoDto.class).single();
    }
}
