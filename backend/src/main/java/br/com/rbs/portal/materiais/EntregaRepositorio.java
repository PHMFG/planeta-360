package br.com.rbs.portal.materiais;

import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.EntregasDaMarca;
import br.com.rbs.portal.materiais.MateriaisDtos.ArquivoDto;
import br.com.rbs.portal.materiais.MateriaisDtos.ItemDaFilaDto;
import br.com.rbs.portal.materiais.MateriaisDtos.ObservacaoDto;
import br.com.rbs.portal.materiais.MateriaisDtos.PaginaDto;
import br.com.rbs.portal.materiais.MateriaisDtos.ParceriaDaFilaDto;
import br.com.rbs.portal.materiais.MateriaisDtos.UsuarioResumoDto;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL das entregas: lista por parceria, fila da RBS, criação, status, arquivos (versões) e última observação. Atende o
 * item 4 da Entrega 2. [ISOLAMENTO] Nenhum método daqui confere o acesso do usuário: quem chama (EntregaService,
 * ArquivoService) passa antes pelo VerificadorDeAcesso e pelas regras de aba liberada.
 */
@Repository
public class EntregaRepositorio {

    /** A entrega como está no banco, com o código da aba; as datas ainda em UTC (o service converte para Brasília). */
    public record EntregaBase(Long id, Long parceriaId, Integer abaId, String abaCodigo, String papel, String titulo,
                              String descricao, String status, LocalDate prazo, OffsetDateTime criadoEm,
                              OffsetDateTime atualizadoEm) {
    }

    /** Filtros da fila da RBS (rota 16); tudo opcional. {@code status} vazio = qualquer status. */
    public record FiltroDaFila(List<String> status, Long parceriaId, Long eventoId, String abaCodigo, boolean atrasadas,
                               String titulo) {
    }

    private static final String COLUNAS_DA_ENTREGA = """
            e.id, e.parceria_id, e.aba_id, ab.codigo AS aba_codigo, e.papel, e.titulo, e.descricao, e.status, e.prazo, e.criado_em,
            e.atualizado_em""";

    private final JdbcClient jdbc;

    public EntregaRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------------
    // Uma entrega
    // ---------------------------------------------------------------------

    /** {@code travar} = FOR UPDATE: duas alterações da mesma entrega (envio, status) esperam uma pela outra. */
    public Optional<EntregaBase> buscar(long entregaId, boolean travar) {
        return jdbc.sql("SELECT " + COLUNAS_DA_ENTREGA + " FROM entrega e JOIN aba ab ON ab.id = e.aba_id WHERE e.id = :id"
                        + (travar ? " FOR UPDATE OF e" : ""))
                .param("id", entregaId)
                .query(EntregaBase.class)
                .optional();
    }

    public Optional<Integer> abaId(String codigo) {
        return jdbc.sql("SELECT id FROM aba WHERE codigo = :codigo").param("codigo", codigo).query(Integer.class).optional();
    }

    public long inserir(long parceriaId, int abaId, String titulo, String descricao, String status, LocalDate prazo) {
        return jdbc.sql("""
                        INSERT INTO entrega (parceria_id, aba_id, titulo, descricao, status, prazo)
                        VALUES (:parceriaId, :abaId, :titulo, :descricao, :status, :prazo)
                        RETURNING id
                        """)
                .param("parceriaId", parceriaId)
                .param("abaId", abaId)
                .param("titulo", titulo)
                .param("descricao", descricao)
                .param("status", status)
                .param("prazo", prazo)
                .query(Long.class)
                .single();
    }

    /**
     * Cria a entrega de um espaço com papel (rota 15). MIV e LOGO têm uma só por parceria (uq_entrega_papel): se já existe, não cria
     * nada e devolve vazio (quem chama responde 409). O documento de ingressos não tem unicidade.
     */
    public Optional<Long> inserirComPapel(long parceriaId, int abaId, String papel, String titulo, String descricao, String status,
                                          LocalDate prazo) {
        return jdbc.sql("""
                        INSERT INTO entrega (parceria_id, aba_id, papel, titulo, descricao, status, prazo)
                        VALUES (:parceriaId, :abaId, :papel, :titulo, :descricao, :status, :prazo)
                        ON CONFLICT DO NOTHING
                        RETURNING id
                        """)
                .param("parceriaId", parceriaId)
                .param("abaId", abaId)
                .param("papel", papel)
                .param("titulo", titulo)
                .param("descricao", descricao)
                .param("status", status)
                .param("prazo", prazo)
                .query(Long.class)
                .optional();
    }

    /** Troca o status e marca a hora da mudança (a coluna atualizado_em não tem gatilho). */
    public void atualizarStatus(long entregaId, String status) {
        jdbc.sql("UPDATE entrega SET status = :status, atualizado_em = now() WHERE id = :id")
                .param("status", status)
                .param("id", entregaId)
                .update();
    }

    /** Marca que houve movimento na entrega (um arquivo novo) sem mudar o status. */
    public void marcarComoAtualizada(long entregaId) {
        jdbc.sql("UPDATE entrega SET atualizado_em = now() WHERE id = :id").param("id", entregaId).update();
    }

    // ---------------------------------------------------------------------
    // Rota 14: entregas de uma parceria
    // ---------------------------------------------------------------------

    /**
     * As entregas da parceria, na ordem da aba e, dentro dela, na de criação. {@code soAbasLiberadas} (o patrocinador)
     * esconde as entregas das abas que a RBS ainda não liberou. aba e status são opcionais.
     */
    public List<EntregaBase> daParceria(long parceriaId, String abaCodigo, String status, boolean soAbasLiberadas) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUNAS_DA_ENTREGA + """

                FROM entrega e JOIN aba ab ON ab.id = e.aba_id
                WHERE e.parceria_id = :parceriaId""");
        Map<String, Object> parametros = new LinkedHashMap<>();
        parametros.put("parceriaId", parceriaId);
        if (abaCodigo != null) {
            sql.append(" AND ab.codigo = :aba");
            parametros.put("aba", abaCodigo);
        }
        if (status != null) {
            sql.append(" AND e.status = :status");
            parametros.put("status", status);
        }
        if (soAbasLiberadas) {
            sql.append(" AND ").append(EntregasDaMarca.ABA_ABERTA);
        }
        sql.append(" ORDER BY ab.ordem, e.criado_em, e.id");
        return jdbc.sql(sql.toString()).params(parametros).query(EntregaBase.class).list();
    }

    // ---------------------------------------------------------------------
    // Arquivos e observações das entregas
    // ---------------------------------------------------------------------

    private record LinhaDeArquivo(Long id, Long entregaId, String nomeOriginal, String mimeType, Long tamanhoBytes,
                                  Integer versao, String categoria, OffsetDateTime enviadoEm, String observacao,
                                  Long usuarioId, String usuarioNome, String usuarioPerfil) {
    }

    /** Os arquivos de cada entrega, da versão mais nova para a mais antiga (inclui o retorno da RBS). */
    public Map<Long, List<ArquivoDto>> arquivosDas(List<Long> entregaIds) {
        Map<Long, List<ArquivoDto>> porEntrega = new LinkedHashMap<>();
        if (entregaIds.isEmpty()) {
            return porEntrega;
        }
        jdbc.sql("""
                        SELECT a.id, a.entrega_id, a.nome_original, a.mime_type, a.tamanho_bytes, a.versao, a.categoria,
                               a.enviado_em, a.observacao, u.id AS usuario_id, u.nome AS usuario_nome, u.perfil AS usuario_perfil
                        FROM arquivo a JOIN usuario u ON u.id = a.enviado_por
                        WHERE a.entrega_id IN (:ids)
                        ORDER BY a.entrega_id, a.versao DESC, a.id DESC
                        """)
                .param("ids", entregaIds)
                .query(LinhaDeArquivo.class)
                .list()
                .forEach(l -> porEntrega.computeIfAbsent(l.entregaId(), k -> new ArrayList<>()).add(
                        new ArquivoDto(l.id(), l.entregaId(), l.nomeOriginal(), l.mimeType(), l.tamanhoBytes(), l.versao(),
                                l.categoria(), new UsuarioResumoDto(l.usuarioId(), l.usuarioNome(), l.usuarioPerfil()),
                                Datas.emBrasilia(l.enviadoEm()), l.observacao())));
        return porEntrega;
    }

    private record LinhaDeObservacao(Long entidadeId, String detalhe, String nome, OffsetDateTime ocorridoEm) {
    }

    /**
     * [HISTORICO] A última observação de cada entrega: o texto da última mudança de status feita pela RBS que tinha
     * recado. Mudança sem texto, e a que o envio do patrocinador provoca, não apagam o recado anterior.
     */
    public Map<Long, ObservacaoDto> ultimasObservacoes(List<Long> entregaIds) {
        Map<Long, ObservacaoDto> porEntrega = new LinkedHashMap<>();
        if (entregaIds.isEmpty()) {
            return porEntrega;
        }
        jdbc.sql("""
                        SELECT DISTINCT ON (h.entidade_id) h.entidade_id, h.detalhe, u.nome, h.ocorrido_em
                        FROM historico h JOIN usuario u ON u.id = h.usuario_id
                        WHERE h.entidade = 'entrega' AND h.acao = 'ALTERACAO_STATUS' AND u.perfil = 'ADMIN_RBS'
                          AND btrim(coalesce(h.detalhe, '')) <> '' AND h.entidade_id IN (:ids)
                        ORDER BY h.entidade_id, h.ocorrido_em DESC, h.id DESC
                        """)
                .param("ids", entregaIds)
                .query(LinhaDeObservacao.class)
                .list()
                .forEach(l -> porEntrega.put(l.entidadeId(), new ObservacaoDto(l.detalhe(), l.nome(), Datas.emBrasilia(l.ocorridoEm()))));
        return porEntrega;
    }

    // ---------------------------------------------------------------------
    // Rota 16: fila de trabalho da RBS
    // ---------------------------------------------------------------------

    private record LinhaDaFila(Long id, Long parceriaId, String patrocinador, String cota, String abaCodigo, String papel,
                               String titulo, String status, LocalDate prazo, Boolean atrasada, OffsetDateTime atualizadoEm,
                               OffsetDateTime ultimoEnvioEm) {
    }

    /**
     * A fila de entregas de todas as parcerias. Ordem: primeiro as que esperam análise (ENVIADO e EM_ANALISE), das que
     * esperam há mais tempo (último envio do patrocinador) para as mais novas; depois as demais, pelo prazo mais próximo
     * (sem prazo por último). "Atrasada" = prazo anterior a {@code hoje} e entrega ainda pendente (nem APROVADO nem
     * NAO_APLICAVEL, a mesma conta de vw_entregas_resumo). O texto do título entra por position(), que não trata % e _ como curingas.
     */
    public PaginaDto<ItemDaFilaDto> fila(FiltroDaFila filtro, LocalDate hoje, int pagina, int tamanho) {
        String de = """
                FROM entrega e
                JOIN aba ab ON ab.id = e.aba_id
                JOIN parceria pa ON pa.id = e.parceria_id
                JOIN patrocinador pat ON pat.id = pa.patrocinador_id
                JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id
                LEFT JOIN LATERAL (SELECT max(a.enviado_em) AS ultimo
                                   FROM arquivo a JOIN usuario u ON u.id = a.enviado_por
                                   WHERE a.entrega_id = e.id AND u.perfil = 'ADMIN_PATROCINADOR') env ON TRUE
                WHERE TRUE""";
        StringBuilder onde = new StringBuilder();
        Map<String, Object> parametros = new LinkedHashMap<>();
        if (!filtro.status().isEmpty()) {
            onde.append(" AND e.status IN (:status)");
            parametros.put("status", filtro.status());
        }
        if (filtro.parceriaId() != null) {
            onde.append(" AND e.parceria_id = :parceriaId");
            parametros.put("parceriaId", filtro.parceriaId());
        }
        if (filtro.eventoId() != null) {
            onde.append(" AND pa.evento_id = :eventoId");
            parametros.put("eventoId", filtro.eventoId());
        }
        if (filtro.abaCodigo() != null) {
            onde.append(" AND ab.codigo = :aba");
            parametros.put("aba", filtro.abaCodigo());
        }
        if (filtro.atrasadas()) {
            onde.append(" AND e.prazo < :hoje AND e.status NOT IN ('APROVADO', 'NAO_APLICAVEL')");
            parametros.put("hoje", hoje);
        }
        if (filtro.titulo() != null) {
            onde.append(" AND position(lower(:titulo) IN lower(e.titulo)) > 0");
            parametros.put("titulo", filtro.titulo());
        }

        long total = jdbc.sql("SELECT count(*) " + de + onde).params(parametros).query(Long.class).single();
        Map<String, Object> comPagina = new LinkedHashMap<>(parametros);
        comPagina.put("hojeDaConta", hoje);
        comPagina.put("limite", tamanho);
        comPagina.put("deslocamento", (long) pagina * tamanho);
        String ordem = """

                ORDER BY (e.status IN ('ENVIADO', 'EM_ANALISE')) DESC,
                         CASE WHEN e.status IN ('ENVIADO', 'EM_ANALISE') THEN coalesce(env.ultimo, e.atualizado_em) END ASC,
                         e.prazo ASC NULLS LAST, e.id
                LIMIT :limite OFFSET :deslocamento
                """;
        List<ItemDaFilaDto> itens = jdbc.sql("""
                        SELECT e.id, pa.id AS parceria_id, pat.nome_canonico AS patrocinador, ct.codigo AS cota,
                               ab.codigo AS aba_codigo, e.papel, e.titulo, e.status, e.prazo,
                               (e.prazo < :hojeDaConta AND e.status NOT IN ('APROVADO', 'NAO_APLICAVEL')) AS atrasada,
                               e.atualizado_em, env.ultimo AS ultimo_envio_em
                        """ + de + onde + ordem)
                .params(comPagina)
                .query(LinhaDaFila.class)
                .list().stream()
                .map(l -> new ItemDaFilaDto(l.id(), new ParceriaDaFilaDto(l.parceriaId(), l.patrocinador(), l.cota()),
                        l.abaCodigo(), l.papel(), l.titulo(), l.status(), l.prazo(), Boolean.TRUE.equals(l.atrasada()),
                        Datas.emBrasilia(l.atualizadoEm()), Datas.emBrasilia(l.ultimoEnvioEm())))
                .toList();
        return new PaginaDto<>(itens, pagina, tamanho, total);
    }
}
