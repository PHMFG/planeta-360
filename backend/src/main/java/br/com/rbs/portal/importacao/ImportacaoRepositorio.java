package br.com.rbs.portal.importacao;

import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.importacao.ImportacaoDtos.OrigemDaImportacao;
import br.com.rbs.portal.importacao.ImportacaoDtos.SituacaoDaLinha;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * O SQL da importação (rotas 109 a 116). [IMPORTACAO] Nenhuma regra de negócio mora aqui: o repositório grava e lê as importações,
 * as linhas propostas e as cargas dos destinos. As transições e o alcance são do banco (V21); o service confere antes e devolve o
 * 409 com a frase certa.
 */
@Repository
public class ImportacaoRepositorio {

    /** A edição da importação. */
    public record Evento(long id, String nome, int edicao) {
    }

    /** Uma cota do catálogo. */
    public record Cota(long id, String codigo, String nome) {
    }

    /** Uma parceria da edição, com a marca e a cota. */
    public record Parceria(long id, long eventoId, String patrocinador, Long cotaTipoId, String cotaCodigo, String cotaNome,
                           String situacao) {
    }

    /** Uma planilha pronta do plano comercial, para o casador e para a carga de veiculação. */
    public record PlanilhaDoPlano(long id, String veiculo, String formato, String espacoPrograma) {
    }

    /** A importação como o banco a guarda, com os nomes que a resposta mostra. */
    public record Importacao(long id, long eventoId, String eventoNome, int eventoEdicao, AlcanceDaImportacao alcance,
                             Long cotaTipoId, String cotaCodigo, String cotaNome, Long parceriaId, String parceriaPatrocinador,
                             OrigemDaImportacao origem, long arquivoId, String arquivoNome, String arquivoMime, long arquivoTamanho,
                             String arquivoCaminho, Long documentoId, DestinoDaImportacao destinoSugerido,
                             DestinoDaImportacao destino, BigDecimal confianca, SituacaoDaImportacao situacao, String erro,
                             Long cargaId, Long criadaPor, String criadaPorNome, OffsetDateTime criadaEm, Long confirmadaPor,
                             OffsetDateTime confirmadaEm, Long retiradaPor, OffsetDateTime retiradaEm) {
    }

    /** Uma linha proposta como o banco a guarda, com o jsonb já em texto. */
    public record LinhaGravada(long id, long importacaoId, int ordem, DestinoDaImportacao destino, String dados, String origem,
                        SituacaoDaLinha situacao, String motivo, Long parceriaId, String parceriaPatrocinador,
                        Long aproveitamentoSugeridoId, Long aproveitamentoId, BigDecimal semelhanca, Long editadaPor,
                        String editadaPorNome, OffsetDateTime editadaEm, boolean naoEntendido) {
    }

    /** O resumo de uma carga, para o resultado da confirmação. */
    public record TotaisDaCarga(Long cargaId, int lidas, int inseridas, int duplicadas, int rejeitadas) {
    }

    private static final String SELECT_IMPORTACAO = """
            SELECT i.id, i.evento_id, e.nome AS evento_nome, e.edicao AS evento_edicao,
                   i.alcance, i.cota_tipo_id, ct.codigo AS cota_codigo, ct.nome AS cota_nome,
                   i.parceria_id, pat.nome_canonico AS parceria_patrocinador, i.origem,
                   i.arquivo_id, a.nome_original AS arquivo_nome, a.mime_type AS arquivo_mime,
                   a.tamanho_bytes AS arquivo_tamanho, a.caminho AS arquivo_caminho,
                   i.documento_id, i.destino_sugerido, i.destino, i.confianca, i.situacao, i.erro, i.carga_id,
                   i.criada_por, uc.nome AS criada_por_nome, i.criada_em,
                   i.confirmada_por, i.confirmada_em, i.retirada_por, i.retirada_em
            FROM importacao i
            JOIN evento e ON e.id = i.evento_id
            LEFT JOIN cota_tipo ct ON ct.id = i.cota_tipo_id
            LEFT JOIN parceria pa ON pa.id = i.parceria_id
            LEFT JOIN patrocinador pat ON pat.id = pa.patrocinador_id
            JOIN arquivo a ON a.id = i.arquivo_id
            JOIN usuario uc ON uc.id = i.criada_por
            """;

    private static final String SELECT_LINHA = """
            SELECT l.id, l.importacao_id, l.ordem, l.destino, l.dados::text AS dados, l.origem::text AS origem, l.situacao,
                   l.motivo, l.parceria_id, pat.nome_canonico AS parceria_patrocinador, l.aproveitamento_sugerido_id,
                   l.aproveitamento_id, l.semelhanca, l.editada_por, ue.nome AS editada_por_nome, l.editada_em,
                   (l.dados @> '{"naoEntendido": true}'::jsonb) AS nao_entendido
            FROM importacao_linha l
            LEFT JOIN parceria pa ON pa.id = l.parceria_id
            LEFT JOIN patrocinador pat ON pat.id = pa.patrocinador_id
            LEFT JOIN usuario ue ON ue.id = l.editada_por
            """;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    public ImportacaoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------------
    // A edição, a cota e a parceria
    // ---------------------------------------------------------------------

    public Optional<Evento> evento(long id) {
        return jdbc.sql("SELECT id, nome, edicao FROM evento WHERE id = :id").param("id", id).query(Evento.class).optional();
    }

    public Optional<Cota> cota(String codigoOuId) {
        return jdbc.sql("""
                        SELECT id, codigo, nome FROM cota_tipo
                        WHERE lower(codigo) = lower(:valor) OR CAST(id AS text) = :valor
                        """)
                .param("valor", codigoOuId == null ? "" : codigoOuId.strip()).query(Cota.class).optional();
    }

    public Optional<Cota> cota(long id) {
        return jdbc.sql("SELECT id, codigo, nome FROM cota_tipo WHERE id = :id").param("id", id).query(Cota.class).optional();
    }

    public Optional<Parceria> parceria(long id) {
        return jdbc.sql("""
                        SELECT pa.id, pa.evento_id, p.nome_canonico AS patrocinador, pa.cota_tipo_id,
                               ct.codigo AS cota_codigo, ct.nome AS cota_nome, pa.situacao
                        FROM parceria pa
                        JOIN patrocinador p ON p.id = pa.patrocinador_id
                        LEFT JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id
                        WHERE pa.id = :id
                        """).param("id", id).query(Parceria.class).optional();
    }

    /** A parceria da marca na edição, pelo nome canônico ou por uma grafia alternativa. */
    public Optional<Parceria> parceriaDaMarca(long eventoId, String marca) {
        if (marca == null || marca.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT pa.id, pa.evento_id, p.nome_canonico AS patrocinador, pa.cota_tipo_id,
                               ct.codigo AS cota_codigo, ct.nome AS cota_nome, pa.situacao
                        FROM parceria pa
                        JOIN patrocinador p ON p.id = pa.patrocinador_id
                        LEFT JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id
                        WHERE pa.evento_id = :evento
                          AND (lower(btrim(p.nome_canonico)) = lower(btrim(:marca))
                               OR EXISTS (SELECT 1 FROM patrocinador_alias al
                                          WHERE al.patrocinador_id = p.id AND lower(btrim(al.alias)) = lower(btrim(:marca))))
                        ORDER BY pa.id LIMIT 1
                        """)
                .param("evento", eventoId).param("marca", marca).query(Parceria.class).optional();
    }

    /** Se a pessoa tem vínculo vigente com a parceria (a mesma regra do VerificadorDeAcesso). */
    public boolean temVinculo(long usuarioId, long parceriaId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM parceria_usuario pu JOIN usuario u ON u.id = pu.usuario_id
                                       WHERE pu.parceria_id = :parceria AND pu.usuario_id = :usuario AND u.ativo)
                        """).param("parceria", parceriaId).param("usuario", usuarioId).query(Boolean.class).single();
    }

    public boolean parceriaExiste(long parceriaId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM parceria WHERE id = :id)").param("id", parceriaId)
                .query(Boolean.class).single();
    }

    // ---------------------------------------------------------------------
    // A importação
    // ---------------------------------------------------------------------

    public long inserir(long eventoId, AlcanceDaImportacao alcance, Long cotaTipoId, Long parceriaId, OrigemDaImportacao origem,
                        long arquivoId, DestinoDaImportacao destino, long criadaPor) {
        return jdbc.sql("""
                        INSERT INTO importacao (evento_id, alcance, cota_tipo_id, parceria_id, origem, arquivo_id, destino,
                                                situacao, criada_por)
                        VALUES (:evento, :alcance, CAST(:cota AS smallint), :parceria, :origem, :arquivo, :destino, 'LENDO', :criadaPor)
                        RETURNING id
                        """)
                .param("evento", eventoId).param("alcance", alcance.name()).param("cota", cotaTipoId)
                .param("parceria", parceriaId).param("origem", origem.name()).param("arquivo", arquivoId)
                .param("destino", destino == null ? null : destino.name()).param("criadaPor", criadaPor)
                .query(Long.class).single();
    }

    public Optional<Importacao> buscar(long id) {
        return jdbc.sql(SELECT_IMPORTACAO + " WHERE i.id = :id").param("id", id).query(ImportacaoRepositorio::importacao).optional();
    }

    public List<Importacao> listar(Long eventoId, List<SituacaoDaImportacao> situacoes, int pagina, int tamanho) {
        String situacoesSql = situacoes == null || situacoes.isEmpty() ? null
                : situacoes.stream().map(Enum::name).toList().toString().replace("[", "{").replace("]", "}");
        return jdbc.sql(SELECT_IMPORTACAO + """
                         WHERE (CAST(:evento AS bigint) IS NULL OR i.evento_id = :evento)
                           AND (CAST(:situacoes AS text[]) IS NULL OR i.situacao = ANY(CAST(:situacoes AS text[])))
                         ORDER BY i.criada_em DESC, i.id DESC LIMIT :limite OFFSET :deslocamento
                         """)
                .param("evento", eventoId).param("situacoes", situacoesSql)
                .param("limite", tamanho).param("deslocamento", (long) pagina * tamanho)
                .query(ImportacaoRepositorio::importacao).list();
    }

    public long contar(Long eventoId, List<SituacaoDaImportacao> situacoes) {
        String situacoesSql = situacoes == null || situacoes.isEmpty() ? null
                : situacoes.stream().map(Enum::name).toList().toString().replace("[", "{").replace("]", "}");
        return jdbc.sql("""
                        SELECT count(*) FROM importacao i
                        WHERE (CAST(:evento AS bigint) IS NULL OR i.evento_id = :evento)
                          AND (CAST(:situacoes AS text[]) IS NULL OR i.situacao = ANY(CAST(:situacoes AS text[])))
                        """).param("evento", eventoId).param("situacoes", situacoesSql).query(Long.class).single();
    }

    public void marcarEmPrevia(long id, DestinoDaImportacao destinoSugerido, DestinoDaImportacao destino, BigDecimal confianca) {
        jdbc.sql("""
                        UPDATE importacao SET situacao = 'EM_PREVIA', destino_sugerido = :sugerido, destino = :destino,
                                             confianca = :confianca, erro = NULL
                        WHERE id = :id AND situacao = 'LENDO'
                        """)
                .param("id", id).param("sugerido", destinoSugerido == null ? null : destinoSugerido.name())
                .param("destino", destino == null ? null : destino.name()).param("confianca", confianca).update();
    }

    public void marcarErro(long id, String motivo) {
        jdbc.sql("UPDATE importacao SET situacao = 'ERRO', erro = :motivo WHERE id = :id AND situacao = 'LENDO'")
                .param("id", id).param("motivo", motivo).update();
    }

    public void marcarConfirmada(long id, long usuarioId, Long cargaId) {
        jdbc.sql("""
                        UPDATE importacao SET situacao = 'CONFIRMADA', confirmada_por = :usuario, confirmada_em = now(),
                                             carga_id = :carga
                        WHERE id = :id AND situacao = 'EM_PREVIA'
                        """).param("id", id).param("usuario", usuarioId).param("carga", cargaId).update();
    }

    public void marcarDescartada(long id) {
        jdbc.sql("UPDATE importacao SET situacao = 'DESCARTADA' WHERE id = :id AND situacao = 'EM_PREVIA'")
                .param("id", id).update();
    }

    public void marcarRetirada(long id, long usuarioId) {
        jdbc.sql("UPDATE importacao SET situacao = 'RETIRADA', retirada_por = :usuario, retirada_em = now() WHERE id = :id")
                .param("id", id).param("usuario", usuarioId).update();
    }

    public void gravarDocumento(long id, Long documentoId) {
        jdbc.sql("UPDATE importacao SET documento_id = :documento WHERE id = :id")
                .param("id", id).param("documento", documentoId).update();
    }

    /** O LENDO que sobrou de uma queda vira ERRO: a fila fica na memória. */
    public int orfaosViramErro(String motivo) {
        return jdbc.sql("UPDATE importacao SET situacao = 'ERRO', erro = :motivo WHERE situacao = 'LENDO'")
                .param("motivo", motivo).update();
    }

    // ---------------------------------------------------------------------
    // As linhas propostas
    // ---------------------------------------------------------------------

    public long inserirLinha(long importacaoId, int ordem, DestinoDaImportacao destino, String dadosJson, String origemJson,
                             SituacaoDaLinha situacao, String motivo, Long parceriaId, Long aproveitamentoSugeridoId,
                             BigDecimal semelhanca) {
        return jdbc.sql("""
                        INSERT INTO importacao_linha (importacao_id, ordem, destino, dados, origem, situacao, motivo, parceria_id,
                                                      aproveitamento_sugerido_id, semelhanca)
                        VALUES (:importacao, :ordem, :destino, CAST(:dados AS jsonb), CAST(:origem AS jsonb), :situacao, :motivo,
                                :parceria, :sugerido, :semelhanca)
                        RETURNING id
                        """)
                .param("importacao", importacaoId).param("ordem", ordem).param("destino", destino.name())
                .param("dados", dadosJson).param("origem", origemJson).param("situacao", situacao.name())
                .param("motivo", motivo).param("parceria", parceriaId).param("sugerido", aproveitamentoSugeridoId)
                .param("semelhanca", semelhanca).query(Long.class).single();
    }

    public List<LinhaGravada> linhas(long importacaoId) {
        return jdbc.sql(SELECT_LINHA + " WHERE l.importacao_id = :id ORDER BY l.ordem")
                .param("id", importacaoId).query(ImportacaoRepositorio::linha).list();
    }

    public Optional<LinhaGravada> linha(long importacaoId, long linhaId) {
        return jdbc.sql(SELECT_LINHA + " WHERE l.importacao_id = :importacao AND l.id = :id")
                .param("importacao", importacaoId).param("id", linhaId).query(ImportacaoRepositorio::linha).optional();
    }

    public void atualizarLinha(long id, DestinoDaImportacao destino, String dadosJson, String origemJson, SituacaoDaLinha situacao,
                               String motivo, Long parceriaId, Long aproveitamentoSugeridoId, Long aproveitamentoId,
                               BigDecimal semelhanca, Long editadaPor, OffsetDateTime editadaEm) {
        jdbc.sql("""
                        UPDATE importacao_linha SET destino = :destino, dados = CAST(:dados AS jsonb),
                               origem = CAST(:origem AS jsonb), situacao = :situacao, motivo = :motivo, parceria_id = :parceria,
                               aproveitamento_sugerido_id = :sugerido, aproveitamento_id = :aproveitamento,
                               semelhanca = :semelhanca, editada_por = :editadaPor, editada_em = :editadaEm
                        WHERE id = :id
                        """)
                .param("id", id).param("destino", destino.name()).param("dados", dadosJson).param("origem", origemJson)
                .param("situacao", situacao.name()).param("motivo", motivo).param("parceria", parceriaId)
                .param("sugerido", aproveitamentoSugeridoId).param("aproveitamento", aproveitamentoId)
                .param("semelhanca", semelhanca).param("editadaPor", editadaPor).param("editadaEm", editadaEm).update();
    }

    public int atualizarSituacaoDasLinhas(long importacaoId, List<Long> ids, SituacaoDaLinha situacao, Long editadaPor,
                                          OffsetDateTime editadaEm) {
        String idsSql = ids == null || ids.isEmpty() ? null : ids.toString().replace("[", "{").replace("]", "}");
        return jdbc.sql("""
                        UPDATE importacao_linha SET situacao = :situacao, editada_por = :editadaPor, editada_em = :editadaEm
                        WHERE importacao_id = :importacao
                          AND (CAST(:ids AS bigint[]) IS NULL OR id = ANY(CAST(:ids AS bigint[])))
                          AND (CAST(:ids AS bigint[]) IS NOT NULL OR situacao IN ('PROPOSTA', 'EDITADA'))
                        """)
                .param("importacao", importacaoId).param("ids", idsSql).param("situacao", situacao.name())
                .param("editadaPor", editadaPor).param("editadaEm", editadaEm).update();
    }

    public long contarLinhas(long importacaoId) {
        return jdbc.sql("SELECT count(*) FROM importacao_linha WHERE importacao_id = :id "
                + "AND NOT (dados @> '{\"naoEntendido\": true}'::jsonb)")
                .param("id", importacaoId).query(Long.class).single();
    }

    // ---------------------------------------------------------------------
    // O plano comercial
    // ---------------------------------------------------------------------

    public List<PlanilhaDoPlano> planoDaParceria(long parceriaId) {
        return jdbc.sql("""
                        SELECT id, veiculo, formato, espaco_programa FROM aproveitamento_previsto
                        WHERE parceria_id = :parceria AND categoria = 'MIDIA' ORDER BY id
                        """).param("parceria", parceriaId).query(PlanilhaDoPlano.class).list();
    }

    public Optional<PlanilhaDoPlano> planilha(long id, long parceriaId) {
        return jdbc.sql("""
                        SELECT id, veiculo, formato, espaco_programa FROM aproveitamento_previsto
                        WHERE id = :id AND parceria_id = :parceria AND categoria = 'MIDIA'
                        """).param("id", id).param("parceria", parceriaId).query(PlanilhaDoPlano.class).optional();
    }

    public Optional<PlanilhaDoPlano> planilha(long id) {
        return jdbc.sql("SELECT id, veiculo, formato, espaco_programa FROM aproveitamento_previsto WHERE id = :id")
                .param("id", id).query(PlanilhaDoPlano.class).optional();
    }

    public Optional<Long> parceriaDaPlanilha(long id) {
        return jdbc.sql("SELECT parceria_id FROM aproveitamento_previsto WHERE id = :id").param("id", id)
                .query(Long.class).optional();
    }

    // ---------------------------------------------------------------------
    // A pesquisa (destino PESQUISA)
    // ---------------------------------------------------------------------

    /** A pesquisa da edição com aquele escopo (ATIVACAO ou GERAL); vazia enquanto a RBS não a abre. */
    /** M2: há exibição (não total mensal) desta linha do plano no mês AAAA-MM, no horário de Brasília? */
    public boolean haExibicoesNoMes(long aproveitamentoId, String mes) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM veiculacao WHERE aproveitamento_id = :a AND periodo = 'EXIBICAO'
                               AND to_char(exibido_em AT TIME ZONE 'America/Sao_Paulo', 'YYYY-MM') = :mes)""")
                .param("a", aproveitamentoId).param("mes", mes).query(Boolean.class).single();
    }

    /** M2: há o total mensal desta linha do plano no mês AAAA-MM? */
    public boolean haTotalDoMes(long aproveitamentoId, String mes) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM veiculacao WHERE aproveitamento_id = :a AND periodo = 'MES'
                               AND to_char(mes_referencia, 'YYYY-MM') = :mes)""")
                .param("a", aproveitamentoId).param("mes", mes).query(Boolean.class).single();
    }

    public Optional<Long> pesquisaDaEdicao(long eventoId, String escopo) {
        return jdbc.sql("SELECT id FROM pesquisa WHERE evento_id = :evento AND escopo = :escopo ORDER BY id LIMIT 1")
                .param("evento", eventoId).param("escopo", escopo).query(Long.class).optional();
    }

    /** Uma pergunta da pesquisa da marca, pela ordem; a lista de alternativas vem como o JSONB gravado. */
    public Optional<PerguntaDaImportacao> perguntaDaMarca(long pesquisaId, long parceriaId, int ordem) {
        return jdbc.sql("""
                        SELECT id, tipo_resposta, status, alternativas::text AS alternativas
                        FROM pesquisa_pergunta
                        WHERE pesquisa_id = :pesquisa AND parceria_id = :parceria AND ordem = :ordem
                        """)
                .param("pesquisa", pesquisaId).param("parceria", parceriaId).param("ordem", ordem)
                .query(PerguntaDaImportacao.class).optional();
    }

    /** Uma pergunta da pesquisa, para conferir a existência e a aprovação na prévia. */
    public record PerguntaDaImportacao(long id, String tipoResposta, String status, String alternativas) {
    }

    /** Grava uma linha em stg_pesquisa; o número da linha é a ordem da linha na importação. */
    public void gravarStgPesquisa(long cargaId, int numero, String patrocinador, Integer pergunta, String indicador,
                                  String alternativa, BigDecimal valor, String unidade, Integer baseRespondentes) {
        jdbc.sql("""
                        INSERT INTO stg_pesquisa (carga_id, linha, patrocinador, pergunta, indicador, alternativa, valor, unidade,
                                                  base_respondentes)
                        VALUES (:carga, :linha, :patrocinador, :pergunta, :indicador, :alternativa, :valor, :unidade, :base)
                        """)
                .param("carga", cargaId).param("linha", numero).param("patrocinador", patrocinador)
                .param("pergunta", pergunta).param("indicador", indicador).param("alternativa", alternativa)
                .param("valor", valor).param("unidade", unidade).param("base", baseRespondentes).update();
    }

    public void processarPesquisa(long cargaId, long pesquisaId) {
        jdbc.sql("SELECT * FROM fn_carregar_pesquisa(:carga, :pesquisa)").param("carga", cargaId)
                .param("pesquisa", pesquisaId).query().listOfRows();
    }

    /** Troca o perfil anterior da mesma pesquisa, parceria, dimensão e categoria (a chave natural da tabela). */
    public int apagarPerfil(long pesquisaId, Long parceriaId, String dimensao, String categoria) {
        return jdbc.sql("""
                        DELETE FROM pesquisa_perfil
                        WHERE pesquisa_id = :pesquisa
                          AND parceria_id IS NOT DISTINCT FROM CAST(:parceria AS bigint)
                          AND dimensao = :dimensao AND categoria = :categoria
                        """)
                .param("pesquisa", pesquisaId).param("parceria", parceriaId).param("dimensao", dimensao)
                .param("categoria", categoria).update();
    }

    /** Grava uma categoria do perfil demográfico, com a importação de origem. */
    public long inserirPerfil(long pesquisaId, Long parceriaId, String dimensao, String categoria, BigDecimal valor,
                              int baseRespondentes, long importacaoId) {
        return jdbc.sql("""
                        INSERT INTO pesquisa_perfil (pesquisa_id, parceria_id, dimensao, categoria, valor, base_respondentes,
                                                     importacao_id)
                        VALUES (:pesquisa, CAST(:parceria AS bigint), :dimensao, :categoria, :valor, :base, :importacao)
                        RETURNING id
                        """)
                .param("pesquisa", pesquisaId).param("parceria", parceriaId).param("dimensao", dimensao)
                .param("categoria", categoria).param("valor", valor).param("base", baseRespondentes)
                .param("importacao", importacaoId).query(Long.class).single();
    }

    /** Grava uma resposta de texto livre já agregada, com a suspeita de dado pessoal e a importação de origem. */
    public long inserirRespostaTexto(long pesquisaId, long parceriaId, long perguntaId, String texto, int contagem,
                                     boolean suspeita, long importacaoId) {
        return jdbc.sql("""
                        INSERT INTO pesquisa_resposta_texto (pesquisa_id, parceria_id, pergunta_id, texto, contagem,
                                                            suspeita_dado_pessoal, importacao_id)
                        VALUES (:pesquisa, :parceria, :pergunta, :texto, :contagem, :suspeita, :importacao)
                        RETURNING id
                        """)
                .param("pesquisa", pesquisaId).param("parceria", parceriaId).param("pergunta", perguntaId)
                .param("texto", texto).param("contagem", contagem).param("suspeita", suspeita)
                .param("importacao", importacaoId).query(Long.class).single();
    }

    /** Os resultados de uma carga de pesquisa (a retirada de uma importação confirmada). */
    public int apagarPesquisaResultadoDaCarga(long cargaId) {
        return jdbc.sql("DELETE FROM pesquisa_resultado WHERE carga_id = :carga").param("carga", cargaId).update();
    }

    public int apagarPerfilDaImportacao(long importacaoId) {
        return jdbc.sql("DELETE FROM pesquisa_perfil WHERE importacao_id = :id").param("id", importacaoId).update();
    }

    public int apagarTextoDaImportacao(long importacaoId) {
        return jdbc.sql("DELETE FROM pesquisa_resposta_texto WHERE importacao_id = :id").param("id", importacaoId).update();
    }

    // ---------------------------------------------------------------------
    // A carga da veiculação e do plano
    // ---------------------------------------------------------------------

    public long criarCarga(String tipo, long arquivoId, long eventoId, long usuarioId) {
        return jdbc.sql("""
                        INSERT INTO carga (tipo, arquivo_id, evento_id, executada_por)
                        VALUES (:tipo, :arquivo, :evento, :usuario) RETURNING id
                        """).param("tipo", tipo).param("arquivo", arquivoId).param("evento", eventoId)
                .param("usuario", usuarioId).query(Long.class).single();
    }

    /** Grava uma linha em stg_veiculacao; o número da linha é a ordem da linha na importação. */
    public void gravarStgVeiculacao(long cargaId, int numero, String patrocinador, String veiculo, String formato,
                                    String espacoPrograma, OffsetDateTime exibidoEm, String mes, BigDecimal quantidade,
                                    BigDecimal impacto, String link) {
        jdbc.sql("""
                        INSERT INTO stg_veiculacao (carga_id, linha, patrocinador, veiculo, formato, espaco_programa, exibido_em,
                                                    quantidade, link, impacto, mes)
                        VALUES (:carga, :linha, :patrocinador, :veiculo, :formato, :espaco, :exibido, :quantidade, :link,
                                :impacto, :mes)
                        """)
                .param("carga", cargaId).param("linha", numero).param("patrocinador", patrocinador).param("veiculo", veiculo)
                .param("formato", formato).param("espaco", espacoPrograma).param("exibido", exibidoEm)
                .param("quantidade", quantidade).param("link", link).param("impacto", impacto).param("mes", mes).update();
    }

    public void processarVeiculacoes(long cargaId, long eventoId) {
        jdbc.sql("SELECT * FROM fn_carregar_veiculacoes(:carga, :evento)").param("carga", cargaId).param("evento", eventoId)
                .query().listOfRows();
    }

    /** Liga cada veiculação nova (da carga) à linha da importação que a gerou (E14). */
    public void ligarVeiculacoesDaLinha(long cargaId, long linhaId, long aproveitamentoId, OffsetDateTime exibidoEm,
                                        String mes, BigDecimal quantidade, String link, BigDecimal impacto) {
        jdbc.sql("""
                        UPDATE veiculacao v SET importacao_linha_id = :linha
                        WHERE v.carga_id = :carga AND v.aproveitamento_id = :aproveitamento AND v.importacao_linha_id IS NULL
                          AND ((CAST(:mes AS text) IS NOT NULL AND v.periodo = 'MES'
                                AND v.mes_referencia = (CAST(:mes AS text) || '-01')::date)
                               OR (CAST(:mes AS text) IS NULL AND v.periodo = 'EXIBICAO' AND v.exibido_em = :exibido))
                          AND v.quantidade = :quantidade
                          AND v.link IS NOT DISTINCT FROM CAST(:link AS text)
                          AND v.impacto IS NOT DISTINCT FROM CAST(:impacto AS numeric)
                        """)
                .param("carga", cargaId).param("linha", linhaId).param("aproveitamento", aproveitamentoId)
                .param("exibido", exibidoEm).param("mes", mes).param("quantidade", quantidade).param("link", link)
                .param("impacto", impacto).update();
    }

    public void gravarStgPlano(long cargaId, int numero, String patrocinador, String categoria, String bloco, String veiculo,
                               String formato, String espacoPrograma, String janela, String tipoMidia,
                               BigDecimal quantidadePrevista, String unidade, Short fontePagina) {
        jdbc.sql("""
                        INSERT INTO stg_plano (carga_id, linha, patrocinador, categoria, bloco, veiculo, formato, espaco_programa,
                                               janela, tipo_midia, quantidade_prevista, unidade, fonte_pagina)
                        VALUES (:carga, :linha, :patrocinador, :categoria, :bloco, :veiculo, :formato, :espaco, :janela, :tipo,
                                :quantidade, :unidade, :pagina)
                        """)
                .param("carga", cargaId).param("linha", numero).param("patrocinador", patrocinador)
                .param("categoria", categoria).param("bloco", bloco).param("veiculo", veiculo).param("formato", formato)
                .param("espaco", espacoPrograma).param("janela", janela).param("tipo", tipoMidia)
                .param("quantidade", quantidadePrevista).param("unidade", unidade).param("pagina", fontePagina).update();
    }

    public void processarPlano(long cargaId, long eventoId) {
        jdbc.sql("SELECT * FROM fn_carregar_plano(:carga, :evento)").param("carga", cargaId).param("evento", eventoId)
                .query().listOfRows();
    }

    public Optional<TotaisDaCarga> totaisDaCarga(long cargaId) {
        return jdbc.sql("""
                        SELECT id, linhas_lidas, linhas_inseridas, linhas_duplicadas, linhas_rejeitadas
                        FROM carga WHERE id = :id
                        """).param("id", cargaId)
                .query((rs, n) -> new TotaisDaCarga(rs.getLong("id"), rs.getInt("linhas_lidas"), rs.getInt("linhas_inseridas"),
                        rs.getInt("linhas_duplicadas"), rs.getInt("linhas_rejeitadas"))).optional();
    }

    public List<RejeicaoDeCarga> rejeicoesDaCarga(long cargaId) {
        return jdbc.sql("SELECT linha, motivo FROM carga_rejeicao WHERE carga_id = :id ORDER BY linha, id")
                .param("id", cargaId)
                .query((rs, n) -> new RejeicaoDeCarga(rs.getInt("linha"), rs.getString("motivo"))).list();
    }

    /** Uma rejeição da carga, para o resultado da confirmação. */
    public record RejeicaoDeCarga(int ordem, String motivo) {
    }

    public int apagarVeiculacoesDaLinha(long linhaId) {
        return jdbc.sql("DELETE FROM veiculacao WHERE importacao_linha_id = :id").param("id", linhaId).update();
    }

    public int apagarVeiculacoesDaImportacao(long importacaoId) {
        return jdbc.sql("""
                        DELETE FROM veiculacao WHERE importacao_linha_id IN
                            (SELECT id FROM importacao_linha WHERE importacao_id = :id)
                        """).param("id", importacaoId).update();
    }

    // ---------------------------------------------------------------------
    // O comprovante (destino COMPROVANTE)
    // ---------------------------------------------------------------------

    /** Uma exibição da marca numa data, com o veículo e o formato da linha do plano, para o comprovante casar. */
    public record VeiculacaoDaData(long id, long aproveitamentoId, String veiculo, String formato) {
    }

    public List<VeiculacaoDaData> veiculacoesDaParceriaNaData(long parceriaId, java.time.LocalDate data) {
        return jdbc.sql("""
                        SELECT v.id, v.aproveitamento_id, a.veiculo, a.formato
                        FROM veiculacao v
                        JOIN aproveitamento_previsto a ON a.id = v.aproveitamento_id
                        WHERE a.parceria_id = :parceria
                          AND (v.exibido_em AT TIME ZONE 'America/Sao_Paulo')::date = :data
                        ORDER BY v.id
                        """).param("parceria", parceriaId).param("data", data).query(VeiculacaoDaData.class).list();
    }

    public Optional<Long> veiculacaoDoPlanoNaData(long parceriaId, long aproveitamentoId, java.time.LocalDate data) {
        return jdbc.sql("""
                        SELECT v.id
                        FROM veiculacao v
                        JOIN aproveitamento_previsto a ON a.id = v.aproveitamento_id
                        WHERE a.parceria_id = :parceria AND v.aproveitamento_id = :aproveitamento
                          AND (v.exibido_em AT TIME ZONE 'America/Sao_Paulo')::date = :data
                        ORDER BY v.id LIMIT 1
                        """).param("parceria", parceriaId).param("aproveitamento", aproveitamentoId).param("data", data)
                .query(Long.class).optional();
    }

    public Optional<String> caminhoDoArquivo(long arquivoId) {
        return jdbc.sql("SELECT caminho FROM arquivo WHERE id = :id").param("id", arquivoId)
                .query(String.class).optional();
    }

    public void gravarComprovante(long veiculacaoId, long arquivoId) {
        jdbc.sql("UPDATE veiculacao SET comprovante_arquivo_id = :arquivo WHERE id = :veiculacao")
                .param("arquivo", arquivoId).param("veiculacao", veiculacaoId).update();
    }

    /** Os vínculos de comprovante que esta importação pôs: cada arquivo criado por ela leva a marca em {@code observacao}. */
    public int limparComprovantesDaImportacao(long importacaoId) {
        return jdbc.sql("""
                        UPDATE veiculacao SET comprovante_arquivo_id = NULL
                        WHERE comprovante_arquivo_id IN
                            (SELECT id FROM arquivo WHERE observacao = :marca)
                        """).param("marca", "importacao:" + importacaoId).update();
    }

    // ---------------------------------------------------------------------
    // O documento da importação (E15)
    // ---------------------------------------------------------------------

    public void travarGrupoDeDocumento(long eventoId, String escopo, Long cotaId, Long parceriaId, String chave) {
        jdbc.sql("SELECT fn_trava_grupo_documento(:evento, :escopo, CAST(:cota AS smallint), :parceria, :chave)")
                .param("evento", eventoId).param("escopo", escopo).param("cota", cotaId).param("parceria", parceriaId)
                .param("chave", chave).query().singleRow();
    }

    public int proximaVersaoDeDocumento(long eventoId, String escopo, Long cotaId, Long parceriaId, String chave) {
        return jdbc.sql("""
                        SELECT coalesce(max(versao), 0) + 1 FROM documento
                        WHERE evento_id = :evento AND escopo = :escopo AND cota_tipo_id IS NOT DISTINCT FROM CAST(:cota AS smallint)
                          AND parceria_id IS NOT DISTINCT FROM :parceria AND chave = :chave
                        """).param("evento", eventoId).param("escopo", escopo).param("cota", cotaId).param("parceria", parceriaId)
                .param("chave", chave).query(Integer.class).single();
    }

    public long inserirDocumento(String titulo, String chave, int versao, String escopo, long eventoId, Long cotaId, Long parceriaId,
                                 long arquivoId, boolean dadosPessoais, Integer paginas, long enviadoPor, boolean soRbs) {
        return jdbc.sql("""
                        INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, cota_tipo_id, parceria_id, arquivo_id,
                                               status, contem_dados_pessoais, paginas, enviado_por, so_rbs)
                        VALUES (:titulo, :chave, :versao, 'OUTRO', :escopo, :evento, CAST(:cota AS smallint), :parceria, :arquivo,
                                'AGUARDANDO_APROVACAO', :dados, :paginas, :enviado, :soRbs)
                        RETURNING id
                        """)
                .param("titulo", titulo).param("chave", chave).param("versao", versao).param("escopo", escopo)
                .param("evento", eventoId).param("cota", cotaId).param("parceria", parceriaId).param("arquivo", arquivoId)
                .param("dados", dadosPessoais).param("paginas", paginas).param("enviado", enviadoPor).param("soRbs", soRbs)
                .query(Long.class).single();
    }

    /** Grava as suspeitas de dado pessoal (E4) no documento, para a prévia dizer onde elas estão. */
    public void gravarSuspeitasDoDocumento(long documentoId, String suspeitasJson) {
        jdbc.sql("UPDATE documento SET suspeitas = CAST(:suspeitas AS jsonb) WHERE id = :id")
                .param("id", documentoId).param("suspeitas", suspeitasJson).update();
    }

    /** Aprova o documento da importação e o põe em PROCESSANDO, para os trechos e a publicação (o gatilho exige quem aprovou). */
    public void aprovarDocumento(long documentoId, long aprovadoPor) {
        jdbc.sql("""
                        UPDATE documento SET status = 'PROCESSANDO', aprovado_por = :aprovado, aprovado_por_perfil = 'ADMIN_RBS',
                                             aprovado_em = now()
                        WHERE id = :id AND status = 'AGUARDANDO_APROVACAO'
                        """).param("id", documentoId).param("aprovado", aprovadoPor).update();
    }

    public void inserirTrechoDoDocumento(long documentoId, int ordem, String localizador, String texto, int tokens,
                                         String embedding) {
        jdbc.sql("""
                        INSERT INTO documento_trecho (documento_id, ordem, pagina_inicio, pagina_fim, secao_caminho, texto, tokens,
                                                      embedding, localizador, ativo)
                        VALUES (:documento, :ordem, 1, 1, :secao, :texto, :tokens, CAST(:embedding AS vector), :localizador, FALSE)
                        """)
                .param("documento", documentoId).param("ordem", ordem).param("secao", localizador)
                .param("texto", texto).param("tokens", tokens).param("embedding", embedding)
                .param("localizador", localizador).update();
    }

    public void gravarModeloDoDocumento(long documentoId, String modelo, int dimensoes) {
        jdbc.sql("UPDATE documento SET modelo_embedding = :modelo, dimensoes = :dimensoes WHERE id = :id")
                .param("id", documentoId).param("modelo", modelo).param("dimensoes", dimensoes).update();
    }

    public void publicarDocumento(long documentoId) {
        jdbc.sql("SELECT fn_publicar_documento(:id)").param("id", documentoId).query().singleRow();
    }

    public void apagarTrechosDoDocumento(long documentoId) {
        jdbc.sql("DELETE FROM documento_trecho WHERE documento_id = :id").param("id", documentoId).update();
    }

    /** Uma suspeita de dado pessoal gravada no documento (E4), para a prévia dizer onde ela está. */
    public record Suspeita(int pagina, String tipo) {
    }

    public List<Suspeita> suspeitasDoDocumento(long documentoId) {
        String json = jdbc.sql("SELECT suspeitas::text FROM documento WHERE id = :id").param("id", documentoId)
                .query(String.class).optional().orElse(null);
        if (json == null || json.isBlank() || "[]".equals(json)) {
            return List.of();
        }
        try {
            List<Map<String, Object>> itens = JSON.readValue(json, new tools.jackson.core.type.TypeReference<>() {
            });
            return itens.stream().map(item -> new Suspeita(((Number) item.getOrDefault("pagina", 1)).intValue(),
                    String.valueOf(item.getOrDefault("tipo", "CONTATOS")))).toList();
        } catch (RuntimeException invalido) {
            return List.of();
        }
    }

    // ---------------------------------------------------------------------
    // Os leitores de linha
    // ---------------------------------------------------------------------

    private static Importacao importacao(ResultSet rs, int numero) throws SQLException {
        return new Importacao(rs.getLong("id"), rs.getLong("evento_id"), rs.getString("evento_nome"), rs.getInt("evento_edicao"),
                AlcanceDaImportacao.valueOf(rs.getString("alcance")), longOuNulo(rs, "cota_tipo_id"),
                rs.getString("cota_codigo"), rs.getString("cota_nome"),
                (Long) rs.getObject("parceria_id"), rs.getString("parceria_patrocinador"),
                OrigemDaImportacao.valueOf(rs.getString("origem")), rs.getLong("arquivo_id"), rs.getString("arquivo_nome"),
                rs.getString("arquivo_mime"), rs.getLong("arquivo_tamanho"), rs.getString("arquivo_caminho"),
                (Long) rs.getObject("documento_id"), enumOuNulo(rs.getString("destino_sugerido"), DestinoDaImportacao.class),
                enumOuNulo(rs.getString("destino"), DestinoDaImportacao.class), rs.getBigDecimal("confianca"),
                SituacaoDaImportacao.valueOf(rs.getString("situacao")), rs.getString("erro"),
                (Long) rs.getObject("carga_id"), (Long) rs.getObject("criada_por"), rs.getString("criada_por_nome"),
                Datas.emBrasilia(rs.getObject("criada_em", OffsetDateTime.class)),
                (Long) rs.getObject("confirmada_por"), Datas.emBrasilia(rs.getObject("confirmada_em", OffsetDateTime.class)),
                (Long) rs.getObject("retirada_por"), Datas.emBrasilia(rs.getObject("retirada_em", OffsetDateTime.class)));
    }

    private static LinhaGravada linha(ResultSet rs, int numero) throws SQLException {
        return new LinhaGravada(rs.getLong("id"), rs.getLong("importacao_id"), rs.getInt("ordem"),
                DestinoDaImportacao.valueOf(rs.getString("destino")), rs.getString("dados"), rs.getString("origem"),
                SituacaoDaLinha.valueOf(rs.getString("situacao")), rs.getString("motivo"),
                (Long) rs.getObject("parceria_id"), rs.getString("parceria_patrocinador"),
                (Long) rs.getObject("aproveitamento_sugerido_id"), (Long) rs.getObject("aproveitamento_id"),
                rs.getBigDecimal("semelhanca"), (Long) rs.getObject("editada_por"), rs.getString("editada_por_nome"),
                Datas.emBrasilia(rs.getObject("editada_em", OffsetDateTime.class)), rs.getBoolean("nao_entendido"));
    }

    private static <E extends Enum<E>> E enumOuNulo(String valor, Class<E> tipo) {
        return valor == null ? null : Enum.valueOf(tipo, valor);
    }

    private static Long longOuNulo(ResultSet rs, String coluna) throws SQLException {
        Object valor = rs.getObject(coluna);
        return valor == null ? null : rs.getLong(coluna);
    }

    /** Só para os testes de integração e para o diagnóstico: a importação sem nomes de usuário. */
    public Map<String, Object> contagensPorSituacao(long importacaoId) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE situacao = 'PROPOSTA') AS propostas,
                               count(*) FILTER (WHERE situacao = 'EDITADA') AS editadas,
                               count(*) FILTER (WHERE situacao = 'ACEITA') AS aceitas,
                               count(*) FILTER (WHERE situacao = 'DESCARTADA') AS descartadas,
                               count(*) FILTER (WHERE situacao = 'RECUSADA') AS recusadas,
                               count(*) AS total
                        FROM importacao_linha WHERE importacao_id = :id
                          AND NOT (dados @> '{"naoEntendido": true}'::jsonb)
                        """).param("id", importacaoId).query().singleRow();
    }
}
