package br.com.rbs.portal.consolidacao;

import br.com.rbs.portal.comum.EntregasDaMarca;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Consultas da consolidação (previsto x exibido, aplicações, entregas, cortesias, faltantes) e do painel da RBS.
 * Atende os itens 5 e 7 da Entrega 2. [RELATORIO] Os números vêm das visões do banco (vw_midia_unidade, vw_midia_veiculo,
 * vw_aplicacoes, vw_entregas_resumo, vw_cortesias, vw_faltantes), as mesmas que a geração do relatório copia; assim a
 * tela e o relatório nunca discordam (exceção: o resumo de entregas do patrocinador, que só conta aba aberta).
 * [ISOLAMENTO] Toda consulta de parceria recebe o id dela; quem decide se o usuário pode ver aquela parceria é o
 * ConsolidacaoService.
 */
@Repository
public class ConsolidacaoRepositorio {

    // [RELATORIO] Todo percentual deste repositório (e das visões que ele lê) é cortado na primeira casa por fn_pct_truncado,
    // nunca arredondado: nenhum número parece maior do que é, e só chega a 100 quem está completo. Passar de 100 vale.

    public record LinhaDeUnidade(String unidade, BigDecimal previsto, BigDecimal exibido, BigDecimal percentual) {
    }

    public record LinhaDeVeiculo(String veiculo, String unidade, BigDecimal previsto, BigDecimal exibido,
                                 BigDecimal percentual) {
    }

    public record LinhaDoPlano(Long aproveitamentoId, String veiculo, String formato, String espacoPrograma,
                               String unidade, BigDecimal previsto, BigDecimal exibido, BigDecimal percentual) {
    }

    public record LinhaDeAplicacoes(BigDecimal previstas, BigDecimal comprovadas, Long fotos, BigDecimal percentual) {
    }

    public record LinhaDeEntregas(Long total, Long aprovadas, Long pendentes, Long naoAplicaveis,
                                  BigDecimal percentualConcluido) {
    }

    public record LinhaDeCortesias(Long disponiveis, Long destinadas, BigDecimal percentual) {
    }

    public record LinhaDeFaltante(Long parceriaId, String patrocinador, String tipo, String detalhe) {
    }

    public record LinhaDeEvento(Long id, String nome, Integer edicao) {
    }

    public record LinhaDeParcerias(long total, long ativas, long semUsuario) {
    }

    /** Quantas entregas há em cada status, ou quantas parcerias estão em cada situação de relatório. */
    public record Contagem(String chave, long total) {
    }

    public record LinhaDeEntregasDoPainel(long esperandoAnalise, long atrasadas) {
    }

    public record LinhaDeCarga(Long id, String tipo, OffsetDateTime executadaEm, int linhasInseridas, int linhasRejeitadas) {
    }

    /**
     * Do mais para o menos importante. [PENDENTE] A RBS ainda não definiu uma ordem: aqui vale a ordem em que o contrato
     * lista os tipos (docs/api.md, rota 29). Tipo novo que ainda não esteja na lista vai para o fim.
     */
    private static final String ORDEM_DOS_TIPOS = """
            CASE f.tipo WHEN 'MIDIA_SEM_EXIBICAO' THEN 1 WHEN 'MIDIA_ABAIXO_DO_PREVISTO' THEN 2
                        WHEN 'APLICACAO_SEM_COMPROVACAO' THEN 3 WHEN 'PESQUISA_SEM_PERGUNTAS' THEN 4
                        WHEN 'PERGUNTA_SEM_RESULTADO' THEN 5 WHEN 'ENTREGA_PENDENTE' THEN 6
                        WHEN 'PARCERIA_SEM_USUARIO' THEN 7 WHEN 'FESTIVAL_SEM_NUMEROS' THEN 8 ELSE 9 END""";

    // A mesma ordem em toda lista de unidades: inserções, CPM, aplicações.
    private static final String ORDEM_DAS_UNIDADES =
            "CASE unidade WHEN 'INSERCAO' THEN 1 WHEN 'CPM' THEN 2 ELSE 3 END";

    private final JdbcClient jdbc;

    public ConsolidacaoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------------
    // Rota 29: consolidação de uma parceria
    // ---------------------------------------------------------------------

    public List<LinhaDeUnidade> porUnidade(long parceriaId) {
        return jdbc.sql("""
                        SELECT unidade, CAST(previsto AS numeric(14,2)) AS previsto, CAST(exibido AS numeric(14,2)) AS exibido,
                               pct_entrega AS percentual
                        FROM vw_midia_unidade WHERE parceria_id = :parceriaId ORDER BY\s""" + ORDEM_DAS_UNIDADES)
                .param("parceriaId", parceriaId)
                .query(LinhaDeUnidade.class)
                .list();
    }

    public List<LinhaDeVeiculo> porVeiculo(long parceriaId) {
        return jdbc.sql("""
                        SELECT veiculo, unidade, CAST(previsto AS numeric(14,2)) AS previsto,
                               CAST(exibido AS numeric(14,2)) AS exibido, pct_entrega AS percentual
                        FROM vw_midia_veiculo WHERE parceria_id = :parceriaId ORDER BY veiculo,\s""" + ORDEM_DAS_UNIDADES)
                .param("parceriaId", parceriaId)
                .query(LinhaDeVeiculo.class)
                .list();
    }

    /**
     * Cada linha do plano de mídia com a soma das exibições dela. Mesma conta da visão, só que sem agrupar por veículo.
     */
    public List<LinhaDoPlano> linhasDoPlano(long parceriaId) {
        return jdbc.sql("""
                        SELECT ap.id AS aproveitamento_id, ap.veiculo, ap.formato, ap.espaco_programa, ap.unidade,
                               ap.quantidade_prevista AS previsto,
                               CAST(coalesce(v.exibido, 0) AS numeric(14,2)) AS exibido,
                               fn_pct_truncado(100 * coalesce(v.exibido, 0) / ap.quantidade_prevista) AS percentual
                        FROM aproveitamento_previsto ap
                        LEFT JOIN (SELECT aproveitamento_id, sum(quantidade) AS exibido
                                   FROM veiculacao GROUP BY aproveitamento_id) v ON v.aproveitamento_id = ap.id
                        WHERE ap.parceria_id = :parceriaId AND ap.categoria = 'MIDIA'
                        ORDER BY ap.veiculo, ap.formato, ap.espaco_programa NULLS FIRST, ap.id
                        """)
                .param("parceriaId", parceriaId)
                .query(LinhaDoPlano.class)
                .list();
    }

    public Optional<LinhaDeAplicacoes> aplicacoes(long parceriaId) {
        return jdbc.sql("""
                        SELECT CAST(previstas AS numeric(14,2)) AS previstas, CAST(comprovadas AS numeric(14,2)) AS comprovadas,
                               CAST(fotos AS bigint) AS fotos, fn_pct_truncado(100 * comprovadas / previstas) AS percentual
                        FROM vw_aplicacoes WHERE parceria_id = :parceriaId
                        """)
                .param("parceriaId", parceriaId)
                .query(LinhaDeAplicacoes.class)
                .optional();
    }

    /**
     * O resumo de entregas. [PERMISSAO] Com {@code soAbasAbertas} (patrocinador) conta só as entregas de aba aberta, com as
     * mesmas contas da visão; sem ele (RBS) lê vw_entregas_resumo, com todas as abas. Sem entrega contada, vem vazio.
     */
    public Optional<LinhaDeEntregas> entregas(long parceriaId, boolean soAbasAbertas) {
        String origem = soAbasAbertas ? "(" + EntregasDaMarca.RESUMO + ")" : "vw_entregas_resumo";
        return jdbc.sql("SELECT er.total, er.aprovadas, er.pendentes, er.nao_aplicaveis, er.pct_concluidas AS percentual_concluido"
                        + " FROM " + origem + " er WHERE er.parceria_id = :parceriaId")
                .param("parceriaId", parceriaId)
                .query(LinhaDeEntregas.class)
                .optional();
    }

    public Optional<LinhaDeCortesias> cortesias(long parceriaId) {
        return jdbc.sql("""
                        SELECT CAST(disponiveis AS bigint) AS disponiveis, CAST(destinadas AS bigint) AS destinadas,
                               fn_pct_truncado(100.0 * destinadas / nullif(disponiveis, 0)) AS percentual
                        FROM vw_cortesias WHERE parceria_id = :parceriaId
                        """)
                .param("parceriaId", parceriaId)
                .query(LinhaDeCortesias.class)
                .optional();
    }

    /**
     * Os faltantes da parceria, do mais para o menos importante. {@code tipos} limita a lista (o patrocinador só vê os
     * que ele mesmo resolve); nulo traz todos. [PERMISSAO] Quem escolhe o filtro é o Service.
     */
    public List<LinhaDeFaltante> faltantes(long parceriaId, List<String> tipos) {
        return jdbc.sql("""
                        SELECT f.parceria_id, pat.nome_canonico AS patrocinador, f.tipo, f.detalhe
                        FROM vw_faltantes f
                        JOIN parceria pa ON pa.id = f.parceria_id
                        JOIN patrocinador pat ON pat.id = pa.patrocinador_id
                        WHERE f.parceria_id = :parceriaId AND (CAST(:filtrar AS boolean) IS NOT TRUE OR f.tipo IN (:tipos))
                        ORDER BY\s""" + ORDEM_DOS_TIPOS + ", f.detalhe")
                .param("parceriaId", parceriaId)
                .param("filtrar", tipos != null)
                .param("tipos", tipos != null ? tipos : List.of(""))
                .query(LinhaDeFaltante.class)
                .list();
    }

    // ---------------------------------------------------------------------
    // Rota 30: painel da RBS
    // ---------------------------------------------------------------------

    /** A edição pedida, ou a mais nova do festival quando {@code eventoId} é nulo. */
    public Optional<LinhaDeEvento> evento(Long eventoId) {
        if (eventoId != null) {
            return jdbc.sql("SELECT id, nome, edicao FROM evento WHERE id = :id")
                    .param("id", eventoId)
                    .query(LinhaDeEvento.class)
                    .optional();
        }
        return jdbc.sql("SELECT id, nome, edicao FROM evento ORDER BY edicao DESC, id DESC LIMIT 1")
                .query(LinhaDeEvento.class)
                .optional();
    }

    /** "Sem usuário" é a mesma conta de PARCERIA_SEM_USUARIO em vw_faltantes: nenhum vínculo gravado. */
    public LinhaDeParcerias parcerias(long eventoId) {
        return jdbc.sql("""
                        SELECT count(*) AS total,
                               count(*) FILTER (WHERE pa.situacao = 'ATIVA') AS ativas,
                               count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM parceria_usuario pu
                                                                  WHERE pu.parceria_id = pa.id)) AS sem_usuario
                        FROM parceria pa WHERE pa.evento_id = :eventoId
                        """)
                .param("eventoId", eventoId)
                .query(LinhaDeParcerias.class)
                .single();
    }

    public List<Contagem> entregasPorStatus(long eventoId) {
        return jdbc.sql("""
                        SELECT e.status AS chave, count(*) AS total
                        FROM entrega e JOIN parceria pa ON pa.id = e.parceria_id
                        WHERE pa.evento_id = :eventoId GROUP BY e.status
                        """)
                .param("eventoId", eventoId)
                .query(Contagem.class)
                .list();
    }

    /**
     * Esperando análise = ENVIADO + EM_ANALISE (a bola está com a RBS). Atrasada = prazo já vencido e a marca ainda deve
     * algo: NAO_INICIADO, AGUARDANDO_ENVIO ou AJUSTE_SOLICITADO. Entrega já enviada não conta como atraso da marca.
     * {@code hoje} é o dia em Brasília.
     */
    public LinhaDeEntregasDoPainel entregasDoPainel(long eventoId, LocalDate hoje) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE e.status IN ('ENVIADO','EM_ANALISE')) AS esperando_analise,
                               count(*) FILTER (WHERE e.prazo < :hoje
                                                  AND e.status IN ('NAO_INICIADO','AGUARDANDO_ENVIO','AJUSTE_SOLICITADO')) AS atrasadas
                        FROM entrega e JOIN parceria pa ON pa.id = e.parceria_id WHERE pa.evento_id = :eventoId
                        """)
                .param("eventoId", eventoId)
                .param("hoje", hoje)
                .query(LinhaDeEntregasDoPainel.class)
                .single();
    }

    /**
     * Quantas parcerias da edição estão em cada situação da versão MAIS NOVA do relatório (SEM_RELATORIO, RASCUNHO...).
     * Não diz o que a marca vê: com a v3 em rascunho e a v2 disponibilizada, a parceria conta como RASCUNHO aqui
     * (veja {@link #relatoriosQueAMarcaVe}).
     */
    public List<Contagem> relatoriosPorSituacao(long eventoId) {
        return jdbc.sql("""
                        SELECT coalesce(r.status, 'SEM_RELATORIO') AS chave, count(*) AS total
                        FROM parceria pa
                        LEFT JOIN LATERAL (SELECT status FROM relatorio WHERE parceria_id = pa.id
                                           ORDER BY versao DESC LIMIT 1) r ON TRUE
                        WHERE pa.evento_id = :eventoId GROUP BY 1
                        """)
                .param("eventoId", eventoId)
                .query(Contagem.class)
                .list();
    }

    /** Quantas parcerias da edição têm ao menos uma versão disponibilizada: o que a marca de fato consegue ver. */
    public long relatoriosQueAMarcaVe(long eventoId) {
        return jdbc.sql("""
                        SELECT count(*) FROM parceria pa
                        WHERE pa.evento_id = :eventoId
                          AND EXISTS (SELECT 1 FROM relatorio r WHERE r.parceria_id = pa.id AND r.status = 'DISPONIBILIZADO')
                        """)
                .param("eventoId", eventoId)
                .query(Long.class)
                .single();
    }

    /**
     * A carga mais nova da edição, só de mídia ou de pesquisa: a carga do plano comercial (que enche o previsto) não ocupa o
     * card "última carga" do painel, que fala de exibição e de resultado.
     */
    public Optional<LinhaDeCarga> ultimaCarga(long eventoId) {
        return jdbc.sql("""
                        SELECT id, tipo, executada_em, linhas_inseridas, linhas_rejeitadas
                        FROM carga WHERE evento_id = :eventoId AND tipo IN ('MIDIA', 'PESQUISA')
                        ORDER BY executada_em DESC, id DESC LIMIT 1
                        """)
                .param("eventoId", eventoId)
                .query(LinhaDeCarga.class)
                .optional();
    }

    /** Os faltantes de todas as parcerias da edição, os {@code limite} mais importantes primeiro. */
    public List<LinhaDeFaltante> pendencias(long eventoId, int limite) {
        return jdbc.sql("""
                        SELECT f.parceria_id, pat.nome_canonico AS patrocinador, f.tipo, f.detalhe
                        FROM vw_faltantes f
                        JOIN parceria pa ON pa.id = f.parceria_id
                        JOIN patrocinador pat ON pat.id = pa.patrocinador_id
                        WHERE pa.evento_id = :eventoId
                        ORDER BY\s""" + ORDEM_DOS_TIPOS + ", pat.nome_canonico, f.parceria_id, f.detalhe LIMIT :limite")
                .param("eventoId", eventoId)
                .param("limite", limite)
                .query(LinhaDeFaltante.class)
                .list();
    }
}
