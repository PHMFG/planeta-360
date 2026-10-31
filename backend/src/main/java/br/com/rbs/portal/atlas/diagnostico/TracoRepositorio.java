package br.com.rbs.portal.atlas.diagnostico;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL do traço do diagnóstico ({@code atlas_traco}, V10). [ATLAS] Só SQL: quem decide permissão são os services
 * (VerificadorDeAcesso) e quem decide o que entra em {@code dados} é o {@link RegistroDoTraco}. As colunas JSONB entram por
 * {@code CAST(:x AS jsonb)} e saem como texto. Nada aqui abre transação: o {@link RegistroDoTraco} grava numa transação curta e
 * os services de leitura usam a de leitura.
 */
@Repository
public class TracoRepositorio {

    /** Uma etapa a gravar. {@code dadosJson} já vem sanitizado. */
    public record EtapaNova(long parceriaId, long conversaId, UUID turno, Long mensagemId, int ordem, String etapa, String resultado,
                            OffsetDateTime iniciadaEm, int duracaoMs, String dadosJson) {
    }

    private final JdbcClient jdbc;

    public TracoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Grava a etapa e devolve o id dela (para o resumo do raciocínio, que pode ser atualizado depois). */
    public long inserir(EtapaNova etapa) {
        return jdbc.sql("""
                        INSERT INTO atlas_traco (parceria_id, conversa_id, turno, mensagem_id, ordem, etapa, resultado, iniciada_em,
                                                 duracao_ms, dados)
                        VALUES (:parceria, :conversa, :turno, :mensagem, :ordem, :etapa, :resultado, :iniciadaEm, :duracao,
                                CAST(:dados AS jsonb))
                        RETURNING id
                        """)
                .param("parceria", etapa.parceriaId()).param("conversa", etapa.conversaId()).param("turno", etapa.turno())
                .param("mensagem", etapa.mensagemId()).param("ordem", etapa.ordem()).param("etapa", etapa.etapa())
                .param("resultado", etapa.resultado()).param("iniciadaEm", etapa.iniciadaEm()).param("duracao", etapa.duracaoMs())
                .param("dados", etapa.dadosJson())
                .query(Long.class).single();
    }

    /**
     * O turno (UUID, como texto) de cada mensagem do assistente da conversa que deixou traço: é como o item da conversa sabe a que
     * turno do diagnóstico pertence. Mensagem sem traço (a do botão, as de antes do diagnóstico) não aparece.
     */
    public Map<Long, String> turnosDasMensagens(long conversaId) {
        Map<Long, String> turnos = new HashMap<>();
        jdbc.sql("SELECT DISTINCT mensagem_id, CAST(turno AS text) AS turno FROM atlas_traco WHERE conversa_id = :conversa AND mensagem_id IS NOT NULL")
                .param("conversa", conversaId)
                .query((rs, n) -> Map.entry(rs.getLong("mensagem_id"), rs.getString("turno")))
                .list().forEach(e -> turnos.putIfAbsent(e.getKey(), e.getValue()));
        return turnos;
    }

    /** O resumo do raciocínio que terminou depois do fim do turno: a linha dele passa de pendente ao resultado de verdade. */
    public void atualizar(long id, String resultado, int duracaoMs, String dadosJson) {
        jdbc.sql("UPDATE atlas_traco SET resultado = :resultado, duracao_ms = :duracao, dados = CAST(:dados AS jsonb) WHERE id = :id")
                .param("resultado", resultado).param("duracao", duracaoMs).param("dados", dadosJson).param("id", id)
                .update();
    }
}
