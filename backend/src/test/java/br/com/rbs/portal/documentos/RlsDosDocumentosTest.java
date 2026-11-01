package br.com.rbs.portal.documentos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.TesteDeIntegracao;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * [ATLAS] A RLS do papel atlas_ro nos documentos e nos trechos (V11), em conexões de verdade e transações CONFIRMADAS (esta classe não
 * é {@code @Transactional}): o contexto da busca é gravado com {@code set_config(..., true)} e o papel é trocado com
 * {@code SET LOCAL ROLE}. Usa o kit, confirmado no banco de teste, e só lê. Bloqueante: vazamento entre parcerias.
 * <ul>
 *   <li>com o contexto do Amarelo em 2027 (Master): vê o GLOBAL de 2027, a COTA Master e o anexo do Amarelo, e só isso;</li>
 *   <li>sem contexto, não vê nada, <b>inclusive numa conexão que já teve contexto</b> (o parâmetro fica como texto vazio, e o
 *       {@code NULLIF} da política evita o erro de conversão);</li>
 *   <li>{@code SELECT} em {@code parceria} dá 42501 e {@code INSERT} é negado.</li>
 * </ul>
 */
@TesteDeIntegracao
class RlsDosDocumentosTest {

    @Autowired
    DataSource dados;

    @Autowired
    JdbcClient jdbc;

    private long evento(int edicao) {
        return jdbc.sql("SELECT id FROM evento WHERE nome = 'Planeta Atlântida' AND edicao = :e").param("e", edicao).query(Long.class).single();
    }

    private long cota(String codigo) {
        return jdbc.sql("SELECT id FROM cota_tipo WHERE codigo = :c").param("c", codigo).query(Long.class).single();
    }

    private long parceria(String cliente) {
        return jdbc.sql("""
                        SELECT pa.id FROM parceria pa JOIN patrocinador p ON p.id = pa.patrocinador_id JOIN evento e ON e.id = pa.evento_id
                        WHERE p.nome_canonico = :c AND e.edicao = 2027
                        """).param("c", cliente).query(Long.class).single();
    }

    /** Os "título vVERSÃO" dos documentos que o atlas_ro vê, abrindo uma transação nova, trocando o papel e gravando o contexto dado. */
    private List<String> documentosVisiveis(Connection conexao, String evento, String cota, String parceria) throws SQLException {
        return consultar(conexao, evento, cota, parceria, "SELECT titulo || ' v' || versao FROM documento ORDER BY id");
    }

    private List<String> consultar(Connection conexao, String evento, String cota, String parceria, String sql) throws SQLException {
        conexao.setAutoCommit(false);
        try {
            try (var comando = conexao.createStatement()) {
                comando.execute("SET LOCAL ROLE atlas_ro");
            }
            if (evento != null || cota != null || parceria != null) {
                try (PreparedStatement contexto = conexao.prepareStatement(
                        "SELECT set_config('app.evento_id', ?, true), set_config('app.cota_tipo_id', ?, true), set_config('app.parceria_id', ?, true)")) {
                    contexto.setString(1, evento);
                    contexto.setString(2, cota);
                    contexto.setString(3, parceria);
                    contexto.execute();
                }
            }
            List<String> linhas = new ArrayList<>();
            try (var comando = conexao.createStatement(); ResultSet resultado = comando.executeQuery(sql)) {
                while (resultado.next()) {
                    linhas.add(resultado.getString(1));
                }
            }
            conexao.commit();
            return linhas;
        } catch (SQLException | RuntimeException erro) {
            conexao.rollback();
            throw erro;
        } finally {
            conexao.setAutoCommit(true);
        }
    }

    @Test
    void noContextoDoAmarelo2027VeOGlobalACotaMasterEOAnexoDeleESoIsso() throws SQLException {
        try (Connection conexao = dados.getConnection()) {
            List<String> titulos = documentosVisiveis(conexao, String.valueOf(evento(2027)), String.valueOf(cota("MASTER")),
                    String.valueOf(parceria("Cliente Amarelo")));

            assertThat(titulos).containsExactlyInAnyOrder("Manual de Operações 2027 v2", "FAQ do patrocinador 2027 v1",
                    "Comunicado 12/2026 · Janela extra de abastecimento v1", "Plano comercial · Cota Master 2027 v1",
                    "Anexo da parceria · Cliente Amarelo v1");
            // não vê: a v1 substituída (D01), as cotas Subcota G e Subcota, o anexo do Roxo, o manual de 2026, nem o D11 (aguardando),
            // o D12 (erro) e o D13 (revogado)
            assertThat(titulos).noneMatch(t -> t.contains("Subcota") || t.contains("Roxo") || t.contains("2026 v") || t.contains("Lista de credenciados")
                    || t.contains("Aviso digitalizado") || t.contains("Circular") || t.equals("Manual de Operações 2027 v1"));
        }
    }

    @Test
    void osTrechosSeguemOMesmoCaminhoEOsDeDocumentoInativoNaoAparecem() throws SQLException {
        try (Connection conexao = dados.getConnection()) {
            String evento = String.valueOf(evento(2027));
            String master = String.valueOf(cota("MASTER"));
            String amarelo = String.valueOf(parceria("Cliente Amarelo"));

            List<String> doAmarelo = consultar(conexao, evento, master, amarelo,
                    "SELECT DISTINCT d.titulo || ' v' || d.versao FROM documento d WHERE d.id IN (SELECT documento_id FROM documento_trecho) ORDER BY 1");
            List<String> deTodos = consultar(conexao, evento, master, amarelo,
                    "SELECT count(*)::text FROM documento_trecho");
            List<String> inativos = consultar(conexao, evento, master, amarelo, "SELECT count(*)::text FROM documento_trecho WHERE NOT ativo");

            assertThat(doAmarelo).as("os trechos visíveis são dos mesmos 5 documentos").hasSize(5);
            assertThat(Long.parseLong(deTodos.get(0))).isEqualTo(jdbc.sql("""
                            SELECT count(*) FROM documento_trecho t JOIN documento d ON d.id = t.documento_id
                            WHERE t.ativo AND d.status = 'INDEXADO' AND t.evento_id = :evento
                              AND (t.escopo = 'GLOBAL' OR (t.escopo = 'COTA' AND t.cota_tipo_id = :cota) OR (t.escopo = 'PARCERIA' AND t.parceria_id = :parceria))
                            """).param("evento", evento(2027)).param("cota", cota("MASTER")).param("parceria", parceria("Cliente Amarelo"))
                    .query(Long.class).single());
            assertThat(inativos).as("trecho inativo (v1 substituída, revogada) nunca aparece").containsExactly("0");
        }
    }

    @Test
    void noContextoDoRoxoVeASubcotaGEOAnexoDeleENaoOQueEDoAmarelo() throws SQLException {
        try (Connection conexao = dados.getConnection()) {
            List<String> titulos = documentosVisiveis(conexao, String.valueOf(evento(2027)), String.valueOf(cota("SUBCOTA_G")),
                    String.valueOf(parceria("Cliente Roxo")));

            assertThat(titulos).containsExactlyInAnyOrder("Manual de Operações 2027 v2", "FAQ do patrocinador 2027 v1",
                    "Comunicado 12/2026 · Janela extra de abastecimento v1", "Plano comercial · Subcota G 2027 v1",
                    "Anexo da parceria · Cliente Roxo v1");
        }
    }

    @Test
    void noContextoDeUmaMarcaDeSubcotaVeOPlanoDaSubcotaEOutraEdicaoSoVeOGlobalDela() throws SQLException {
        try (Connection conexao = dados.getConnection()) {
            assertThat(documentosVisiveis(conexao, String.valueOf(evento(2027)), String.valueOf(cota("SUBCOTA")), String.valueOf(parceria("Cliente Verde"))))
                    .containsExactlyInAnyOrder("Manual de Operações 2027 v2", "FAQ do patrocinador 2027 v1",
                            "Comunicado 12/2026 · Janela extra de abastecimento v1", "Plano comercial · Subcota 2027 v1");
            // a edição de 2026 só tem o manual de 2026; os documentos de 2027 não aparecem nela, nem o anexo de uma parceria de 2027
            assertThat(documentosVisiveis(conexao, String.valueOf(evento(2026)), String.valueOf(cota("MASTER")), String.valueOf(parceria("Cliente Amarelo"))))
                    .containsExactly("Manual de Operações 2026 v1");
        }
    }

    @Test
    void semContextoNaoVeNadaEIssoVaiAteNumaConexaoQueJaTeveContexto() throws SQLException {
        try (Connection conexao = dados.getConnection()) {
            // uma conexão que acabou de ter o contexto do Amarelo (transação confirmada)
            assertThat(documentosVisiveis(conexao, String.valueOf(evento(2027)), String.valueOf(cota("MASTER")), String.valueOf(parceria("Cliente Amarelo"))))
                    .isNotEmpty();

            // a mesma conexão, numa transação nova, sem gravar contexto: o parâmetro ficou como texto vazio, e a política devolve zero linhas
            // (e não erro de conversão de '' para número)
            assertThat(consultar(conexao, null, null, null, "SELECT titulo FROM documento")).isEmpty();
            assertThat(consultar(conexao, null, null, null, "SELECT texto FROM documento_trecho")).isEmpty();
            // com o contexto vazio de propósito, o mesmo
            assertThat(documentosVisiveis(conexao, "", "", "")).isEmpty();
        }
    }

    @Test
    void numaConexaoNovaQueNuncaTeveContextoTambemNaoVeNada() throws SQLException {
        try (Connection conexao = dados.getConnection()) {
            assertThat(consultar(conexao, null, null, null, "SELECT titulo FROM documento")).isEmpty();
            assertThat(consultar(conexao, null, null, null, "SELECT texto FROM documento_trecho")).isEmpty();
        }
    }

    @Test
    void oContextoDeUmaMarcaNaoVazaParaOutraNaMesmaConexao() throws SQLException {
        try (Connection conexao = dados.getConnection()) {
            String evento = String.valueOf(evento(2027));
            List<String> amarelo = documentosVisiveis(conexao, evento, String.valueOf(cota("MASTER")), String.valueOf(parceria("Cliente Amarelo")));
            List<String> roxo = documentosVisiveis(conexao, evento, String.valueOf(cota("SUBCOTA_G")), String.valueOf(parceria("Cliente Roxo")));

            assertThat(amarelo).contains("Anexo da parceria · Cliente Amarelo v1").doesNotContain("Anexo da parceria · Cliente Roxo v1");
            assertThat(roxo).contains("Anexo da parceria · Cliente Roxo v1").doesNotContain("Anexo da parceria · Cliente Amarelo v1");
        }
    }

    @Test
    void oAtlasRoNaoLeNadaDoPortalENaoGrava() throws SQLException {
        try (Connection conexao = dados.getConnection()) {
            for (String tabela : List.of("parceria", "arquivo", "usuario", "dado_informado", "atlas_mensagem", "historico", "entrega")) {
                assertThatThrownBy(() -> consultar(conexao, null, null, null, "SELECT count(*) FROM " + tabela))
                        .as("SELECT em " + tabela).isInstanceOfSatisfying(SQLException.class, erro -> assertThat(erro.getSQLState()).isEqualTo("42501"));
            }
            for (String comando : List.of("INSERT INTO documento_trecho (documento_id, ordem, pagina_inicio, pagina_fim, texto, tokens, embedding) "
                            + "VALUES (1, 1, 1, 1, 'x', 1, NULL)",
                    "UPDATE documento SET titulo = 'x'", "DELETE FROM documento_trecho", "DELETE FROM documento")) {
                assertThatThrownBy(() -> consultar(conexao, null, null, null, comando))
                        .as(comando).isInstanceOfSatisfying(SQLException.class, erro -> assertThat(erro.getSQLState()).isEqualTo("42501"));
            }
        }
        assertThat(jdbc.sql("SELECT count(*) FROM documento").query(Long.class).single()).as("nada foi gravado nem apagado").isGreaterThanOrEqualTo(13);
    }

    @Test
    void oDonoDoBancoNaoFicaSobARlsEAIngestaoSegueIgual() {
        assertThat(jdbc.sql("SELECT count(*) FROM documento").query(Long.class).single()).isGreaterThanOrEqualTo(13);
        assertThat(jdbc.sql("SELECT count(*) FROM documento WHERE status <> 'INDEXADO'").query(Long.class).single())
                .as("o dono vê também o aguardando, o erro, o revogado e o substituído").isGreaterThanOrEqualTo(4);
        assertThat(jdbc.sql("SELECT count(*) FROM documento_trecho WHERE NOT ativo").query(Long.class).single()).isGreaterThan(0);
    }

    @Test
    void textoDeBuscaEmParametroLigadoNaoMexeNoContextoNemNaRls() throws SQLException {
        // ataque 14: SQL ou set_config no texto da busca. A busca usa parâmetros com bind; aqui prova que um texto assim, como PARÂMETRO, é só texto.
        try (Connection conexao = dados.getConnection()) {
            conexao.setAutoCommit(false);
            try (var troca = conexao.createStatement()) {
                troca.execute("SET LOCAL ROLE atlas_ro");
            }
            try (PreparedStatement consulta = conexao.prepareStatement(
                    "SELECT count(*) FROM documento_trecho WHERE tsv @@ plainto_tsquery('pt_sem_acento', fn_texto_de_busca(?))")) {
                consulta.setString(1, "'); SELECT set_config('app.evento_id', '2', true); --");
                try (ResultSet resultado = consulta.executeQuery()) {
                    resultado.next();
                    assertThat(resultado.getLong(1)).as("sem contexto, nada").isZero();
                }
            }
            try (var estado = conexao.createStatement(); ResultSet r = estado.executeQuery("SELECT coalesce(current_setting('app.evento_id', true), '')")) {
                r.next();
                assertThat(r.getString(1)).as("o contexto continua sem valor").isEmpty();
            }
            conexao.rollback();
            conexao.setAutoCommit(true);
        }
    }
}
