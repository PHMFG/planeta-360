package br.com.rbs.portal.consolidacao;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Consultas da comprovação mensal de mídia (rotas 88 a 91, R3.2 e R3.3). [RELATORIO] A conta é a soma de
 * {@code veiculacao.quantidade} por mês (da data de exibição, no horário de Brasília), tipo de mídia e unidade do plano: unidades
 * diferentes ficam sempre em linhas separadas. O previsto de cada linha é o do plano inteiro para aquele tipo e unidade (não há
 * previsto por mês). [ISOLAMENTO] Toda consulta recebe o id da parceria; quem decide o acesso é o ComprovacaoMensalService.
 */
@Repository
public class ComprovacaoMensalRepositorio {

    /** Uma linha: mês (AAAA-MM), tipo de mídia, unidade, o exibido no mês e o previsto do plano para o tipo e a unidade. */
    public record LinhaMensal(String mes, String tipoMidia, String unidade, BigDecimal exibido, BigDecimal previsto) {
    }

    public record Parceria(Long id, String patrocinador, String cota, int edicao) {
    }

    public record Master(Long id, String patrocinador) {
    }

    private final JdbcClient jdbc;

    public ComprovacaoMensalRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Parceria> parceria(long parceriaId) {
        return jdbc.sql("""
                        SELECT pa.id, pat.nome_canonico AS patrocinador, ct.codigo AS cota, e.edicao
                        FROM parceria pa JOIN patrocinador pat ON pat.id = pa.patrocinador_id
                        JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id JOIN evento e ON e.id = pa.evento_id
                        WHERE pa.id = :id
                        """)
                .param("id", parceriaId)
                .query(Parceria.class)
                .optional();
    }

    public List<LinhaMensal> linhas(long parceriaId) {
        return jdbc.sql("""
                        SELECT to_char(v.exibido_em AT TIME ZONE 'America/Sao_Paulo', 'YYYY-MM') AS mes,
                               ap.tipo_midia, ap.unidade, CAST(sum(v.quantidade) AS numeric(14,2)) AS exibido,
                               (SELECT CAST(sum(p.quantidade_prevista) AS numeric(14,2)) FROM aproveitamento_previsto p
                                WHERE p.parceria_id = ap.parceria_id AND p.categoria = 'MIDIA'
                                  AND p.tipo_midia = ap.tipo_midia AND p.unidade = ap.unidade) AS previsto
                        FROM veiculacao v JOIN aproveitamento_previsto ap ON ap.id = v.aproveitamento_id
                        WHERE ap.parceria_id = :parceriaId AND ap.categoria = 'MIDIA'
                        GROUP BY 1, ap.parceria_id, ap.tipo_midia, ap.unidade
                        ORDER BY 1,
                                 CASE ap.tipo_midia WHEN 'ASSINADA' THEN 1 WHEN 'PROPRIA' THEN 2 WHEN 'MERCHANDISING' THEN 3
                                                    WHEN 'BRANDED' THEN 4 ELSE 5 END,
                                 CASE ap.unidade WHEN 'INSERCAO' THEN 1 WHEN 'CPM' THEN 2 ELSE 3 END
                        """)
                .param("parceriaId", parceriaId)
                .query(LinhaMensal.class)
                .list();
    }

    public Optional<Integer> edicaoDoEvento(long eventoId) {
        return jdbc.sql("SELECT edicao FROM evento WHERE id = :id").param("id", eventoId).query(Integer.class).optional();
    }

    /** As parcerias Master ATIVAS da edição, por nome. */
    public List<Master> masters(long eventoId) {
        return jdbc.sql("""
                        SELECT pa.id, pat.nome_canonico AS patrocinador
                        FROM parceria pa JOIN patrocinador pat ON pat.id = pa.patrocinador_id
                        JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id
                        WHERE pa.evento_id = :eventoId AND ct.codigo = 'MASTER' AND pa.situacao = 'ATIVA'
                        ORDER BY pat.nome_canonico, pa.id
                        """)
                .param("eventoId", eventoId)
                .query(Master.class)
                .list();
    }
}
