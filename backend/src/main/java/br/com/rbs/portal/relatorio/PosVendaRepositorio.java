package br.com.rbs.portal.relatorio;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL do pós-venda da parceria (rotas 96 a 99) e dos números da edição (rotas 100 e 101). [RELATORIO] Quem decide a transação é o
 * service. Os links e as audiências são colunas JSONB: aqui entram e saem como texto JSON, e o service os transforma em objetos.
 */
@Repository
public class PosVendaRepositorio {

    /** Uma versão do anexo, como o banco a guarda (as duas listas ainda em texto JSON). */
    public record Linha(Long id, Long parceriaId, String patrocinador, Long eventoId, Integer versao, String situacao, String texto,
                        String links, String audiencias, OffsetDateTime publicadoEm, Long publicadoPor, String publicadoPorNome,
                        String publicadoPorPerfil, OffsetDateTime retiradoEm, Long retiradoPor, String retiradoPorNome,
                        String retiradoPorPerfil) {
    }

    /** A parceria travada: situação e edição. */
    public record ParceriaTravada(String situacao, Long eventoId) {
    }

    /** Um link de veiculação comprovada. */
    public record PostComLink(String veiculo, String formato, OffsetDateTime exibidoEm, String link) {
    }

    /** Um número da edição. */
    public record Resultado(Long id, String indicador, BigDecimal valor, String qualificador, String unidade, String fonte) {
    }

    private static final String SELECT = """
            SELECT pv.id, pv.parceria_id, p.nome_canonico AS patrocinador, pv.evento_id, pv.versao, pv.situacao, pv.texto,
                   pv.links::text AS links, pv.audiencias::text AS audiencias, pv.publicado_em, pv.publicado_por,
                   up.nome AS publicado_por_nome, up.perfil AS publicado_por_perfil, pv.retirado_em, pv.retirado_por,
                   ur.nome AS retirado_por_nome, ur.perfil AS retirado_por_perfil
            FROM evento_posvenda pv
            JOIN parceria pa ON pa.id = pv.parceria_id JOIN patrocinador p ON p.id = pa.patrocinador_id
            JOIN usuario up ON up.id = pv.publicado_por LEFT JOIN usuario ur ON ur.id = pv.retirado_por
            """;

    private final JdbcClient jdbc;

    public PosVendaRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Trava a parceria (serializa duas publicações) e devolve a situação e a edição dela; vazio = a parceria não existe. */
    public Optional<ParceriaTravada> travarParceria(long parceriaId) {
        return jdbc.sql("SELECT situacao, evento_id FROM parceria WHERE id = :id FOR UPDATE").param("id", parceriaId)
                .query(ParceriaTravada.class).optional();
    }

    public Optional<Long> eventoDaParceria(long parceriaId) {
        return jdbc.sql("SELECT evento_id FROM parceria WHERE id = :id").param("id", parceriaId).query(Long.class).optional();
    }

    public boolean eventoExiste(long eventoId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM evento WHERE id = :id)").param("id", eventoId).query(Boolean.class).single();
    }

    public int proximaVersao(long parceriaId) {
        return jdbc.sql("SELECT coalesce(max(versao), 0) + 1 FROM evento_posvenda WHERE parceria_id = :p").param("p", parceriaId)
                .query(Integer.class).single();
    }

    public long inserir(long parceriaId, long eventoId, int versao, String texto, String linksJson, String audienciasJson, long publicadoPor) {
        return jdbc.sql("""
                        INSERT INTO evento_posvenda (parceria_id, evento_id, versao, texto, links, audiencias, publicado_por)
                        VALUES (:parceriaId, :eventoId, :versao, :texto, CAST(:links AS jsonb), CAST(:audiencias AS jsonb), :por)
                        RETURNING id
                        """)
                .param("parceriaId", parceriaId).param("eventoId", eventoId).param("versao", versao).param("texto", texto)
                .param("links", linksJson).param("audiencias", audienciasJson).param("por", publicadoPor)
                .query(Long.class).single();
    }

    /** A versão em vigor: a mais nova da parceria, se não foi retirada. */
    public Optional<Linha> vigente(long parceriaId) {
        return jdbc.sql(SELECT + """
                        WHERE pv.parceria_id = :p AND pv.situacao = 'PUBLICADO'
                          AND pv.versao = (SELECT max(x.versao) FROM evento_posvenda x WHERE x.parceria_id = :p)
                        """)
                .param("p", parceriaId).query(Linha.class).optional();
    }

    public List<Linha> daEdicao(long eventoId) {
        return jdbc.sql(SELECT + "WHERE pv.evento_id = :e ORDER BY p.nome_canonico, pv.parceria_id, pv.versao DESC")
                .param("e", eventoId).query(Linha.class).list();
    }

    /** Trava a linha (a retirada de dois pedidos ao mesmo tempo passa uma vez só). */
    public Optional<Linha> travar(long id) {
        return jdbc.sql(SELECT + "WHERE pv.id = :id FOR UPDATE OF pv").param("id", id).query(Linha.class).optional();
    }

    public void retirar(long id, long retiradoPor) {
        jdbc.sql("UPDATE evento_posvenda SET situacao = 'RETIRADO', retirado_em = now(), retirado_por = :por WHERE id = :id")
                .param("id", id).param("por", retiradoPor).update();
    }

    /** [RELATORIO] Existe versão DISPONIBILIZADA do relatório desta parceria? É o que a marca precisa para ver o anexo. */
    public boolean temRelatorioDisponibilizado(long parceriaId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM relatorio WHERE parceria_id = :p AND status = 'DISPONIBILIZADO')")
                .param("p", parceriaId).query(Boolean.class).single();
    }

    /** Os links das veiculações comprovadas da parceria (R5.3.d), do mais antigo ao mais novo, sem repetir o mesmo endereço. */
    public List<PostComLink> linksDeVeiculacao(long parceriaId) {
        return jdbc.sql("""
                        SELECT ap.veiculo, ap.formato, min(v.exibido_em) AS exibido_em, v.link
                        FROM veiculacao v JOIN aproveitamento_previsto ap ON ap.id = v.aproveitamento_id
                        WHERE ap.parceria_id = :p AND v.link IS NOT NULL AND btrim(v.link) <> ''
                        GROUP BY ap.veiculo, ap.formato, v.link
                        ORDER BY min(v.exibido_em), v.link
                        """)
                .param("p", parceriaId).query(PostComLink.class).list();
    }

    // ---------------------------------------------------------------------
    // Os números da edição (evento_resultado)
    // ---------------------------------------------------------------------

    public List<Resultado> resultados(long eventoId) {
        return jdbc.sql("SELECT id, indicador, valor, qualificador, unidade, fonte FROM evento_resultado WHERE evento_id = :e ORDER BY indicador")
                .param("e", eventoId).query(Resultado.class).list();
    }

    public Optional<Resultado> resultado(long eventoId, String indicador) {
        return jdbc.sql("SELECT id, indicador, valor, qualificador, unidade, fonte FROM evento_resultado WHERE evento_id = :e AND indicador = :i")
                .param("e", eventoId).param("i", indicador).query(Resultado.class).optional();
    }

    /** Grava o número da edição; o mesmo indicador na mesma edição troca o valor (upsert). Devolve o id. */
    public long gravarResultado(long eventoId, String indicador, BigDecimal valor, String qualificador, String unidade, String fonte) {
        return jdbc.sql("""
                        INSERT INTO evento_resultado (evento_id, indicador, valor, qualificador, unidade, fonte)
                        VALUES (:e, :indicador, :valor, :qualificador, :unidade, :fonte)
                        ON CONFLICT (evento_id, indicador) DO UPDATE SET valor = EXCLUDED.valor, qualificador = EXCLUDED.qualificador,
                            unidade = EXCLUDED.unidade, fonte = EXCLUDED.fonte
                        RETURNING id
                        """)
                .param("e", eventoId).param("indicador", indicador).param("valor", valor).param("qualificador", qualificador)
                .param("unidade", unidade).param("fonte", fonte).query(Long.class).single();
    }
}
