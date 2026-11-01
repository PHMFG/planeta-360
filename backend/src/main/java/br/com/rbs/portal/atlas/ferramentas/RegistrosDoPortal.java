package br.com.rbs.portal.atlas.ferramentas;

import br.com.rbs.portal.comum.EntregasDaMarca;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * O SQL das ferramentas de consulta ao portal ({@code listar_entregas}, {@code consultar_cota}, {@code consultar_prazos}; plano do
 * Atlas completo, M5). [ATLAS] [ISOLAMENTO] [PERMISSAO] Toda consulta daqui recebe a parceria da conversa (nunca um argumento do
 * modelo) e confere a aba <b>no banco, a cada chamada</b> ({@link EntregasDaMarca#ABA_ABERTA}: liberada E visível), não pela foto do
 * contexto: fechar uma aba vale na chamada seguinte, e o que é de aba fechada nem sai da consulta. Selecione-se coluna por coluna:
 * nenhuma consulta daqui traz o valor do investimento da parceria.
 */
@Repository
public class RegistrosDoPortal {

    /** Uma entrega com a aba, a data de atualização (no dia de Brasília) e o último recado da RBS, se houver. */
    public record Entrega(Long id, String aba, String abaNome, String titulo, String status, LocalDate prazo, LocalDate atualizadoEm,
                          String observacaoDaRbs) {
    }

    private final JdbcClient jdbc;

    public RegistrosDoPortal(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Os códigos das abas e o nome de cada uma, na ordem do menu. */
    public Map<String, String> abas() {
        Map<String, String> abas = new LinkedHashMap<>();
        for (String[] aba : jdbc.sql("SELECT codigo, nome FROM aba ORDER BY ordem")
                .query((rs, n) -> new String[]{rs.getString("codigo"), rs.getString("nome")}).list()) {
            abas.put(aba[0], aba[1]);
        }
        return abas;
    }

    /** A aba está aberta para a parceria (liberada e visível)? Aba que não existe ou sem linha na parceria conta como fechada. */
    public boolean abaAberta(long parceriaId, String aba) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM parceria_aba pab JOIN aba a ON a.id = pab.aba_id
                                       WHERE pab.parceria_id = :parceria AND a.codigo = :aba AND pab.liberada AND pab.visivel)
                        """)
                .param("parceria", parceriaId).param("aba", aba).query(Boolean.class).single();
    }

    // ---------------------------------------------------------------------
    // listar_entregas
    // ---------------------------------------------------------------------

    private static final String ENTREGAS_DA_MARCA = """
            FROM entrega e JOIN aba a ON a.id = e.aba_id
            WHERE e.parceria_id = :parceria AND %s
              AND (CAST(:status AS text) IS NULL OR e.status = :status)
              AND (CAST(:aba AS text) IS NULL OR a.codigo = :aba)
            """.formatted(EntregasDaMarca.ABA_ABERTA);

    /**
     * As entregas da parceria em aba aberta, as pendentes (nem aprovadas nem não aplicáveis) primeiro e pelo prazo mais próximo (sem
     * prazo por último), depois a ordem das abas. {@code status} e {@code aba} são filtros opcionais. O recado da RBS é o da última
     * mudança de status feita por ela que tinha texto (o mesmo que a tela da entrega mostra).
     */
    public List<Entrega> entregas(long parceriaId, String status, String aba, int limite) {
        return jdbc.sql("""
                        SELECT e.id, a.codigo AS aba, a.nome AS aba_nome, e.titulo, e.status, e.prazo,
                               (e.atualizado_em AT TIME ZONE 'America/Sao_Paulo')::date AS atualizado_em,
                               (SELECT h.detalhe FROM historico h JOIN usuario u ON u.id = h.usuario_id
                                 WHERE h.entidade = 'entrega' AND h.entidade_id = e.id AND h.acao = 'ALTERACAO_STATUS'
                                   AND u.perfil = 'ADMIN_RBS' AND btrim(coalesce(h.detalhe, '')) <> ''
                                 ORDER BY h.ocorrido_em DESC, h.id DESC LIMIT 1) AS observacao_da_rbs
                        """ + ENTREGAS_DA_MARCA + """
                        ORDER BY (e.status IN ('APROVADO', 'NAO_APLICAVEL')), e.prazo NULLS LAST, a.ordem, e.id
                        LIMIT :limite
                        """)
                .param("parceria", parceriaId).param("status", status).param("aba", aba).param("limite", limite)
                .query(Entrega.class).list();
    }

    public long contarEntregas(long parceriaId, String status, String aba) {
        return jdbc.sql("SELECT count(*) " + ENTREGAS_DA_MARCA)
                .param("parceria", parceriaId).param("status", status).param("aba", aba).query(Long.class).single();
    }
}
