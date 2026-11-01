package br.com.rbs.portal.atlas.ferramentas;

import br.com.rbs.portal.comum.EntregasDaMarca;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /**
     * [ISOLAMENTO] O documento {@code d} é visível para a parceria {@code p}: indexado, da edição dela e do escopo dela (GLOBAL da
     * edição, COTA da cota dela, PARCERIA dela). A mesma regra da RLS do {@code atlas_ro} e da rota 56, repetida aqui porque estas
     * consultas rodam como o resto do portal, e não como o papel da busca.
     */
    static final String DOCUMENTO_VISIVEL = """
            d.status = 'INDEXADO' AND d.evento_id = p.evento_id
              AND (d.escopo = 'GLOBAL'
                   OR (d.escopo = 'COTA' AND d.cota_tipo_id = p.cota_tipo_id)
                   OR (d.escopo = 'PARCERIA' AND d.parceria_id = p.id))""";

    /** A cota, o produto e a edição da parceria. Nunca o valor do investimento nem a base dele. */
    public record DadosDaCota(Long parceriaId, String cotaCodigo, String cotaNome, String produto, String eventoNome, Integer edicao) {
    }

    /** Uma aba da parceria: aberta para a marca (liberada e visível) e se a cota a inclui. */
    public record AbaDaCota(String codigo, String nome, boolean aberta, boolean naCota) {
    }

    /** Uma linha do plano comercial previsto. */
    public record Previsto(Long id, String categoria, String bloco, String veiculo, String formato, String espacoPrograma, String janela,
                           String tipoMidia, BigDecimal quantidadePrevista, String unidade) {
    }

    public record Cortesia(Long id, String setor, String dia, Integer quantidade, boolean comShuttle, String acesso) {
    }

    public record Credencial(Long id, String setor, String beneficiario, Integer quantidadePorDia) {
    }

    /** O documento do plano comercial que serve de origem ao previsto. */
    public record PlanoComercial(Long documentoId, String titulo, Integer versao) {
    }

    /**
     * Um prazo que a ferramenta devolve: ou da tabela {@code prazo} (com o documento e a página de origem, se tiver) ou do prazo de
     * uma entrega (com o nome da aba). {@code diasRestantes} é a diferença de datas calculada no banco, e {@code total} é quantas
     * linhas a consulta teria sem o limite.
     */
    public record Prazo(String tipo, Long id, String titulo, LocalDate data, LocalTime hora, String descricao, Long documentoId,
                        String documentoTitulo, Integer documentoVersao, Integer pagina, String abaNome, Integer diasRestantes, Long total) {
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

    // ---------------------------------------------------------------------
    // consultar_cota
    // ---------------------------------------------------------------------

    public Optional<DadosDaCota> cota(long parceriaId) {
        return jdbc.sql("""
                        SELECT p.id AS parceria_id, ct.codigo AS cota_codigo, ct.nome AS cota_nome, p.nome_produto AS produto,
                               ev.nome AS evento_nome, ev.edicao
                        FROM parceria p JOIN cota_tipo ct ON ct.id = p.cota_tipo_id JOIN evento ev ON ev.id = p.evento_id
                        WHERE p.id = :parceria
                        """)
                .param("parceria", parceriaId).query(DadosDaCota.class).optional();
    }

    /** As sete abas na ordem do menu, com o estado de cada uma para a parceria. */
    public List<AbaDaCota> abasDaCota(long parceriaId) {
        return jdbc.sql("""
                        SELECT a.codigo, a.nome, coalesce(pab.liberada AND pab.visivel, FALSE) AS aberta,
                               EXISTS (SELECT 1 FROM cota_tipo_aba cta WHERE cta.cota_tipo_id = p.cota_tipo_id AND cta.aba_id = a.id) AS na_cota
                        FROM parceria p CROSS JOIN aba a
                        LEFT JOIN parceria_aba pab ON pab.parceria_id = p.id AND pab.aba_id = a.id
                        WHERE p.id = :parceria ORDER BY a.ordem
                        """)
                .param("parceria", parceriaId).query(AbaDaCota.class).list();
    }

    public long contarPrevisto(long parceriaId, List<String> categorias) {
        return jdbc.sql("SELECT count(*) FROM aproveitamento_previsto WHERE parceria_id = :parceria AND categoria IN (:categorias)")
                .param("parceria", parceriaId).param("categorias", categorias).query(Long.class).single();
    }

    /** O previsto das categorias dadas (as de aba aberta), na ordem em que o plano foi carregado. */
    public List<Previsto> previsto(long parceriaId, List<String> categorias, int limite) {
        return jdbc.sql("""
                        SELECT id, categoria, bloco, veiculo, formato, espaco_programa, janela, tipo_midia, quantidade_prevista, unidade
                        FROM aproveitamento_previsto WHERE parceria_id = :parceria AND categoria IN (:categorias)
                        ORDER BY id LIMIT :limite
                        """)
                .param("parceria", parceriaId).param("categorias", categorias).param("limite", limite).query(Previsto.class).list();
    }

    public List<Cortesia> cortesias(long parceriaId) {
        return jdbc.sql("SELECT id, setor, dia, quantidade, com_shuttle, acesso FROM cortesia_cota WHERE parceria_id = :parceria ORDER BY id")
                .param("parceria", parceriaId).query(Cortesia.class).list();
    }

    public List<Credencial> credenciais(long parceriaId) {
        return jdbc.sql("SELECT id, setor, beneficiario, quantidade_por_dia FROM credencial_cota WHERE parceria_id = :parceria ORDER BY id")
                .param("parceria", parceriaId).query(Credencial.class).list();
    }

    /**
     * O plano comercial indexado e visível para a parceria, se houver: o mais específico (o da parceria, depois o da cota, depois o
     * da edição) e, nele, a versão mais nova. Documento revogado, substituído, ainda sem aprovação ou de outra cota nunca serve.
     */
    public Optional<PlanoComercial> planoComercial(long parceriaId) {
        return jdbc.sql("""
                        SELECT d.id AS documento_id, d.titulo, d.versao
                        FROM parceria p JOIN documento d ON d.tipo = 'PLANO_COMERCIAL' AND %s
                        WHERE p.id = :parceria
                        ORDER BY CASE d.escopo WHEN 'PARCERIA' THEN 0 WHEN 'COTA' THEN 1 ELSE 2 END, d.versao DESC, d.id DESC
                        LIMIT 1
                        """.formatted(DOCUMENTO_VISIVEL))
                .param("parceria", parceriaId).query(PlanoComercial.class).optional();
    }

    // ---------------------------------------------------------------------
    // consultar_prazos
    // ---------------------------------------------------------------------

    /**
     * Os prazos da parceria na janela, por data, hora e título: os da tabela {@code prazo} (da edição, da cota e da própria parceria,
     * ativos, e com o documento de origem indexado e visível para ela, se tiverem) e o prazo das entregas de aba aberta que não estão
     * aprovadas nem marcadas como não aplicáveis. {@code hoje} é o dia de Brasília do turno; {@code diasRestantes} é a data menos ele,
     * em SQL. Vencido (antes de hoje) só com {@code incluirVencidos}. A revogação de um documento vale na hora: a leitura olha o
     * status dele a cada chamada, sem cache.
     */
    public List<Prazo> prazos(long parceriaId, LocalDate hoje, int proximosDias, boolean incluirVencidos, int limite) {
        return jdbc.sql(PRAZOS_DA_MARCA + """
                        SELECT tipo, id, titulo, data, hora, descricao, documento_id, documento_titulo, documento_versao, pagina, aba_nome,
                               data - CAST(:hoje AS date) AS dias_restantes, count(*) OVER () AS total
                        FROM todos
                        WHERE data - CAST(:hoje AS date) <= :janela AND (CAST(:vencidos AS boolean) OR data >= CAST(:hoje AS date))
                        ORDER BY data, hora NULLS LAST, titulo, tipo, id
                        LIMIT :limite
                        """)
                .param("parceria", parceriaId).param("hoje", hoje).param("janela", proximosDias).param("vencidos", incluirVencidos)
                .param("limite", limite).query(Prazo.class).list();
    }

    /**
     * Os próximos prazos <b>depois</b> da janela de {@code proximosDias} (o primeiro dia de fora é {@code proximosDias + 1}), por
     * data, até {@code limite}, com as mesmas regras de {@link #prazos}: o documento de origem indexado e visível, e a aba aberta. Serve
     * à janela vazia, para o modelo não dizer "não há prazo" sendo que o primeiro está logo depois dela.
     */
    public List<Prazo> prazosDepoisDaJanela(long parceriaId, LocalDate hoje, int proximosDias, int limite) {
        return jdbc.sql(PRAZOS_DA_MARCA + """
                        SELECT tipo, id, titulo, data, hora, descricao, documento_id, documento_titulo, documento_versao, pagina, aba_nome,
                               data - CAST(:hoje AS date) AS dias_restantes, count(*) OVER () AS total
                        FROM todos
                        WHERE data - CAST(:hoje AS date) > :janela
                        ORDER BY data, hora NULLS LAST, titulo, tipo, id
                        LIMIT :limite
                        """)
                .param("parceria", parceriaId).param("hoje", hoje).param("janela", proximosDias).param("limite", limite)
                .query(Prazo.class).list();
    }

    /** Os prazos da parceria (cadastro da RBS e entregas), antes do filtro de janela: a CTE {@code todos}. */
    private static final String PRAZOS_DA_MARCA = """
            WITH todos AS (
                SELECT 'prazo' AS tipo, pz.id, pz.titulo, pz.data, pz.hora, pz.descricao, d.id AS documento_id,
                       d.titulo AS documento_titulo, d.versao AS documento_versao, pz.pagina, NULL::text AS aba_nome
                FROM parceria p
                JOIN prazo pz ON pz.evento_id = p.evento_id AND pz.ativo
                     AND (pz.escopo = 'GLOBAL'
                          OR (pz.escopo = 'COTA' AND pz.cota_tipo_id = p.cota_tipo_id)
                          OR (pz.escopo = 'PARCERIA' AND pz.parceria_id = p.id))
                LEFT JOIN documento d ON d.id = pz.documento_id
                WHERE p.id = :parceria AND (pz.documento_id IS NULL OR (%s))
              UNION ALL
                SELECT 'entrega', e.id, e.titulo, e.prazo, NULL::time, left(e.descricao, 300), NULL::bigint, NULL::text,
                       NULL::int, NULL::int, a.nome
                FROM entrega e JOIN aba a ON a.id = e.aba_id
                WHERE e.parceria_id = :parceria AND e.prazo IS NOT NULL AND e.status NOT IN ('APROVADO', 'NAO_APLICAVEL')
                  AND %s
            )
            """.formatted(DOCUMENTO_VISIVEL, EntregasDaMarca.ABA_ABERTA);
}
