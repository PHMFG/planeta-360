package br.com.rbs.portal.parceria;

import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.EntregasDaMarca;
import br.com.rbs.portal.parceria.ParceriaDtos.CotaDto;
import br.com.rbs.portal.parceria.ParceriaDtos.EntregasResumoDto;
import br.com.rbs.portal.parceria.ParceriaDtos.EventoCompletoDto;
import br.com.rbs.portal.parceria.ParceriaDtos.EventoDto;
import br.com.rbs.portal.parceria.ParceriaDtos.PatrocinadorResumoDto;
import br.com.rbs.portal.parceria.ParceriaDtos.ParceriaResumoDto;
import br.com.rbs.portal.parceria.ParceriaDtos.RelatorioResumoDto;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL de eventos, patrocinadores, parcerias e abas. Atende o item 3 da Entrega 2. [ISOLAMENTO] A lista do patrocinador
 * já sai filtrada pelo vínculo (parceria_usuario) dentro da própria consulta; quem confere o acesso a UMA parceria é o
 * VerificadorDeAcesso, antes de qualquer método daqui que receba o id dela.
 */
@Repository
public class ParceriaRepositorio {

    /** Cota como está na tabela cota_tipo (o id é SMALLINT). */
    public record Cota(Integer id, String codigo, String nome) {
    }

    /** A parceria sem abas e sem e-mails, com o necessário para a rota 9 e para a edição. */
    public record ParceriaBase(Long id, Long patrocinadorId, String patrocinadorNome, String segmento, Long eventoId,
                               String eventoNome, Integer edicao, String cotaCodigo, String cotaNome, String nomeProduto,
                               BigDecimal valorInvestimento, String baseValor, String situacao, String atendimentoNome,
                               String atendimentoEmail, OffsetDateTime criadoEm) {
    }

    /**
     * Estado de uma aba na parceria (o id e o nome servem ao histórico). incluidaNaCota: a cota da parceria inclui a aba
     * (cota_tipo_aba); só a aba incluída pode ser liberada pela RBS.
     */
    public record AbaDaParceria(Integer abaId, String codigo, String nome, int ordem, boolean visivel, boolean liberada,
                                boolean incluidaNaCota) {
    }

    /** Filtros opcionais da lista (rota 7). usuarioId preenchido = só as parcerias deste usuário (o patrocinador). */
    public record FiltroDaLista(Long eventoId, String situacao, String nomeContem, Long usuarioId) {
    }

    private final JdbcClient jdbc;

    public ParceriaRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------------
    // Eventos, cotas e patrocinadores
    // ---------------------------------------------------------------------

    public List<EventoCompletoDto> eventos() {
        return jdbc.sql("""
                        SELECT id, nome, edicao, data_inicio, data_fim, local, credenciamento_url, credenciamento_inicio, credenciamento_fim, cortesias_prazo
                        FROM evento ORDER BY edicao DESC, id DESC
                        """)
                .query(EventoCompletoDto.class)
                .list();
    }

    public boolean eventoExiste(long eventoId) {
        return existe("SELECT EXISTS (SELECT 1 FROM evento WHERE id = :id)", eventoId);
    }

    public Optional<Cota> cotaPorCodigo(String codigo) {
        return jdbc.sql("SELECT id, codigo, nome FROM cota_tipo WHERE codigo = :codigo")
                .param("codigo", codigo)
                .query(Cota.class)
                .optional();
    }

    public boolean patrocinadorExiste(long patrocinadorId) {
        return existe("SELECT EXISTS (SELECT 1 FROM patrocinador WHERE id = :id)", patrocinadorId);
    }

    /**
     * O nome já é de outro patrocinador? Sem diferenciar maiúsculas (como o índice uq_patrocinador_nome) E sem diferença de
     * espaços: o nome recebido já vem normalizado (ParceriaService.nomeNormalizado) e o do banco é normalizado aqui na
     * consulta, para pegar também nomes antigos gravados com espaços repetidos. Ignora o próprio id na edição.
     */
    public boolean nomeDePatrocinadorEmUso(String nome, Long exceto) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM patrocinador
                                       WHERE lower(btrim(regexp_replace(nome_canonico, '\\s+', ' ', 'g'))) = lower(:nome)
                                       AND (CAST(:exceto AS BIGINT) IS NULL OR id <> :exceto))
                        """)
                .param("nome", nome)
                .param("exceto", exceto)
                .query(Boolean.class)
                .single();
    }

    public long criarPatrocinador(String nome, String segmento) {
        return jdbc.sql("INSERT INTO patrocinador (nome_canonico, segmento) VALUES (:nome, :segmento) RETURNING id")
                .param("nome", nome)
                .param("segmento", segmento)
                .query(Long.class)
                .single();
    }

    public void atualizarPatrocinador(long patrocinadorId, String nome, String segmento) {
        jdbc.sql("UPDATE patrocinador SET nome_canonico = :nome, segmento = :segmento WHERE id = :id")
                .param("nome", nome)
                .param("segmento", segmento)
                .param("id", patrocinadorId)
                .update();
    }

    // ---------------------------------------------------------------------
    // Parceria
    // ---------------------------------------------------------------------

    public boolean parceriaDaEdicaoExiste(long eventoId, long patrocinadorId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM parceria WHERE evento_id = :eventoId AND patrocinador_id = :patrocinadorId)")
                .param("eventoId", eventoId)
                .param("patrocinadorId", patrocinadorId)
                .query(Boolean.class)
                .single();
    }

    /** Insere a parceria; o gatilho rn_parceria_abas_padrao cria as 7 abas conforme cota_tipo_aba. */
    public long criarParceria(long eventoId, long patrocinadorId, int cotaTipoId, String nomeProduto,
                              BigDecimal valorInvestimento, String baseValor) {
        return jdbc.sql("""
                        INSERT INTO parceria (evento_id, patrocinador_id, cota_tipo_id, nome_produto, valor_investimento, base_valor)
                        VALUES (:eventoId, :patrocinadorId, :cotaTipoId, :nomeProduto, :valor, :base)
                        RETURNING id
                        """)
                .param("eventoId", eventoId)
                .param("patrocinadorId", patrocinadorId)
                .param("cotaTipoId", cotaTipoId)
                .param("nomeProduto", nomeProduto)
                .param("valor", valorInvestimento)
                .param("base", baseValor)
                .query(Long.class)
                .single();
    }

    /** paraEditar = true trava a linha (FOR UPDATE): dois PATCH ao mesmo tempo esperam um pelo outro. */
    public Optional<ParceriaBase> buscarBase(long parceriaId, boolean paraEditar) {
        return jdbc.sql("""
                        SELECT pa.id, pat.id AS patrocinador_id, pat.nome_canonico AS patrocinador_nome, pat.segmento,
                               e.id AS evento_id, e.nome AS evento_nome, e.edicao, ct.codigo AS cota_codigo, ct.nome AS cota_nome,
                               pa.nome_produto, pa.valor_investimento, pa.base_valor, pa.situacao,
                               pa.atendimento_nome, pa.atendimento_email, pa.criado_em
                        FROM parceria pa
                        JOIN patrocinador pat ON pat.id = pa.patrocinador_id
                        JOIN evento e ON e.id = pa.evento_id
                        JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id
                        WHERE pa.id = :id
                        """ + (paraEditar ? " FOR UPDATE OF pa" : ""))
                .param("id", parceriaId)
                .query(ParceriaBase.class)
                .optional()
                .map(b -> new ParceriaBase(b.id(), b.patrocinadorId(), b.patrocinadorNome(), b.segmento(), b.eventoId(),
                        b.eventoNome(), b.edicao(), b.cotaCodigo(), b.cotaNome(), b.nomeProduto(), b.valorInvestimento(),
                        b.baseValor(), b.situacao(), b.atendimentoNome(), b.atendimentoEmail(), Datas.emBrasilia(b.criadoEm())));
    }

    /** [U7] O atendimento da marca (nome e e-mail): a RBS edita no Perfil e o FAQ da marca mostra. */
    public void atualizarAtendimento(long parceriaId, String atendimentoNome, String atendimentoEmail) {
        jdbc.sql("UPDATE parceria SET atendimento_nome = :nome, atendimento_email = :email WHERE id = :id")
                .param("nome", atendimentoNome)
                .param("email", atendimentoEmail)
                .param("id", parceriaId)
                .update();
    }

    public void atualizarParceria(long parceriaId, String nomeProduto, BigDecimal valorInvestimento, String baseValor,
                                  String situacao) {
        jdbc.sql("""
                        UPDATE parceria SET nome_produto = :nomeProduto, valor_investimento = :valor, base_valor = :base,
                                            situacao = :situacao
                        WHERE id = :id
                        """)
                .param("nomeProduto", nomeProduto)
                .param("valor", valorInvestimento)
                .param("base", baseValor)
                .param("situacao", situacao)
                .param("id", parceriaId)
                .update();
    }

    // ---------------------------------------------------------------------
    // Abas
    // ---------------------------------------------------------------------

    /** As 7 abas da parceria, em ordem, com o estado cru (visivel e liberada separados). */
    public List<AbaDaParceria> abasDaParceria(long parceriaId) {
        return jdbc.sql("""
                        SELECT a.id AS aba_id, a.codigo, a.nome, a.ordem, pab.visivel, pab.liberada,
                               EXISTS (SELECT 1 FROM cota_tipo_aba cta
                                       WHERE cta.cota_tipo_id = pa.cota_tipo_id AND cta.aba_id = a.id) AS incluida_na_cota
                        FROM parceria_aba pab
                        JOIN parceria pa ON pa.id = pab.parceria_id
                        JOIN aba a ON a.id = pab.aba_id
                        WHERE pab.parceria_id = :id
                        ORDER BY a.ordem
                        """)
                .param("id", parceriaId)
                .query(AbaDaParceria.class)
                .list();
    }

    public void atualizarAba(long parceriaId, int abaId, boolean liberada, boolean visivel) {
        jdbc.sql("UPDATE parceria_aba SET liberada = :liberada, visivel = :visivel WHERE parceria_id = :parceriaId AND aba_id = :abaId")
                .param("liberada", liberada)
                .param("visivel", visivel)
                .param("parceriaId", parceriaId)
                .param("abaId", abaId)
                .update();
    }

    // ---------------------------------------------------------------------
    // Troca de cota (rotas 102 e 104)
    // ---------------------------------------------------------------------

    /** Os códigos das abas que a cota inclui (cota_tipo_aba). */
    public java.util.Set<String> abasDaCota(int cotaTipoId) {
        return new java.util.LinkedHashSet<>(jdbc.sql("""
                        SELECT a.codigo FROM cota_tipo_aba cta JOIN aba a ON a.id = cta.aba_id
                        WHERE cta.cota_tipo_id = :cota ORDER BY a.ordem
                        """)
                .param("cota", cotaTipoId)
                .query(String.class)
                .list());
    }

    /** A versão disponibilizada mais nova do relatório da parceria, se houver. */
    public record RelatorioDisponibilizado(Integer versao, OffsetDateTime disponibilizadoEm) {
    }

    public Optional<RelatorioDisponibilizado> relatorioDisponibilizado(long parceriaId) {
        return jdbc.sql("""
                        SELECT versao, disponibilizado_em FROM relatorio
                        WHERE parceria_id = :id AND status = 'DISPONIBILIZADO' ORDER BY versao DESC LIMIT 1
                        """)
                .param("id", parceriaId)
                .query(RelatorioDisponibilizado.class)
                .optional()
                .map(r -> new RelatorioDisponibilizado(r.versao(), Datas.emBrasilia(r.disponibilizadoEm())));
    }

    public void atualizarCota(long parceriaId, int cotaTipoId) {
        jdbc.sql("UPDATE parceria SET cota_tipo_id = :cota WHERE id = :id")
                .param("cota", cotaTipoId)
                .param("id", parceriaId)
                .update();
    }

    // ---------------------------------------------------------------------
    // Lista (rota 7)
    // ---------------------------------------------------------------------

    private record LinhaDaLista(Long id, Long patrocinadorId, String patrocinadorNome, Long eventoId, String eventoNome,
                                Integer edicao, String cotaCodigo, String cotaNome, String nomeProduto, String situacao,
                                Long total, Long aprovadas, Long pendentes, Long naoAplicaveis, BigDecimal percentual,
                                Integer usuarios, Long relatorioId, Integer relatorioVersao, String relatorioStatus) {
    }

    /**
     * As parcerias com o resumo de entregas, o número de usuários e a versão mais nova do relatório.
     * [ISOLAMENTO] Com usuarioId (patrocinador): só as parcerias em que ele tem vínculo, os usuários vêm nulos e o
     * relatório só conta se a RBS o disponibilizou. [PERMISSAO] O resumo de entregas do patrocinador conta só as entregas
     * de aba aberta (EntregasDaMarca.RESUMO, sem nenhuma vem zerado e sem percentual); o da RBS lê vw_entregas_resumo,
     * com todas as abas. Os filtros entram no SQL só quando vieram (parâmetro nulo no
     * PostgreSQL pede CAST), e o texto da busca vai por position(), que não trata % e _ como curingas.
     */
    public List<ParceriaResumoDto> listar(FiltroDaLista filtro) {
        boolean doPatrocinador = filtro.usuarioId() != null;
        StringBuilder sql = new StringBuilder("""
                SELECT pa.id, pat.id AS patrocinador_id, pat.nome_canonico AS patrocinador_nome, e.id AS evento_id,
                       e.nome AS evento_nome, e.edicao, ct.codigo AS cota_codigo, ct.nome AS cota_nome, pa.nome_produto,
                       pa.situacao, coalesce(er.total, 0) AS total, coalesce(er.aprovadas, 0) AS aprovadas,
                       coalesce(er.pendentes, 0) AS pendentes, coalesce(er.nao_aplicaveis, 0) AS nao_aplicaveis,
                       er.pct_concluidas AS percentual,
                       (SELECT count(*) FROM parceria_usuario pu WHERE pu.parceria_id = pa.id) AS usuarios,
                       rel.id AS relatorio_id, rel.versao AS relatorio_versao, rel.status AS relatorio_status
                FROM parceria pa
                JOIN patrocinador pat ON pat.id = pa.patrocinador_id
                JOIN evento e ON e.id = pa.evento_id
                JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id
                LEFT JOIN\s""");
        sql.append(doPatrocinador ? "(" + EntregasDaMarca.RESUMO + ")" : "vw_entregas_resumo");
        sql.append("""
                 er ON er.parceria_id = pa.id
                LEFT JOIN LATERAL (SELECT r.id, r.versao, r.status FROM relatorio r
                                   WHERE r.parceria_id = pa.id
                """);
        sql.append(doPatrocinador ? " AND r.status = 'DISPONIBILIZADO'" : "");
        sql.append("""
                                   ORDER BY r.versao DESC LIMIT 1) rel ON TRUE
                WHERE TRUE
                """);
        Map<String, Object> parametros = new LinkedHashMap<>();
        if (doPatrocinador) {
            sql.append(" AND EXISTS (SELECT 1 FROM parceria_usuario pu JOIN usuario u ON u.id = pu.usuario_id"
                    + " WHERE pu.parceria_id = pa.id AND pu.usuario_id = :usuarioId AND u.ativo)");
            parametros.put("usuarioId", filtro.usuarioId());
        }
        if (filtro.eventoId() != null) {
            sql.append(" AND pa.evento_id = :eventoId");
            parametros.put("eventoId", filtro.eventoId());
        }
        if (filtro.situacao() != null) {
            sql.append(" AND pa.situacao = :situacao");
            parametros.put("situacao", filtro.situacao());
        }
        if (filtro.nomeContem() != null) {
            sql.append(" AND position(lower(:nome) IN lower(pat.nome_canonico)) > 0");
            parametros.put("nome", filtro.nomeContem());
        }
        sql.append(" ORDER BY e.edicao DESC, pat.nome_canonico, pa.id");

        return jdbc.sql(sql.toString()).params(parametros).query(LinhaDaLista.class).list().stream()
                .map(l -> paraResumo(l, doPatrocinador)).toList();
    }

    private static ParceriaResumoDto paraResumo(LinhaDaLista l, boolean doPatrocinador) {
        return new ParceriaResumoDto(l.id(), new PatrocinadorResumoDto(l.patrocinadorId(), l.patrocinadorNome()),
                new EventoDto(l.eventoId(), l.eventoNome(), l.edicao()), new CotaDto(l.cotaCodigo(), l.cotaNome()),
                l.nomeProduto(), l.situacao(),
                new EntregasResumoDto(l.total(), l.aprovadas(), l.pendentes(), l.naoAplicaveis(), l.percentual()),
                doPatrocinador ? null : l.usuarios(),
                l.relatorioId() == null ? null : new RelatorioResumoDto(l.relatorioId(), l.relatorioVersao(), l.relatorioStatus()));
    }

    private boolean existe(String sql, long id) {
        return jdbc.sql(sql).param("id", id).query(Boolean.class).single();
    }
}
