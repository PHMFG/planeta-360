package br.com.rbs.portal.banco;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Prova que o contexto sobe num portal_teste limpo e que o Flyway criou tudo: tabelas, regras, dados base e a amostra.
 * Atende o item 3 da Entrega 2 (back-end ligado ao banco do item 1).
 */
@TesteDeIntegracao
class CriacaoDoBancoTest {

    @Autowired
    JdbcClient jdbc;

    private long contar(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    @Test
    void flywayAplicouAsTrezeMigracoesEAAmostra() {
        // V1 a V13 versionadas; R__amostra repetível. Todas com sucesso.
        long sucesso = contar("SELECT count(*) FROM flyway_schema_history WHERE success");
        long total = contar("SELECT count(*) FROM flyway_schema_history");
        assertThat(total).isEqualTo(14);
        assertThat(sucesso).isEqualTo(14);
        assertThat(contar("SELECT count(*) FROM flyway_schema_history WHERE version IS NULL AND script LIKE '%R__amostra%'"))
                .as("a amostra de dev/test foi aplicada").isEqualTo(1);
    }

    @Test
    void estruturaDoBancoEstaCompleta() {
        assertThat(contar("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE' "
                + "AND table_name <> 'flyway_schema_history'")).as("tabelas").isEqualTo(48);
        assertThat(contar("SELECT count(*) FROM information_schema.views WHERE table_schema = 'public'")).as("visões").isEqualTo(8);
        assertThat(contar("SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal AND tgname LIKE 'rn\\_%'")).as("gatilhos de regra").isEqualTo(28);
    }

    @Test
    void asFuncoesDoPortalEstaoCriadas() {
        // 4 de carga e relatório, 2 de formatação (V4 e V6) e 3 dos documentos do Atlas (V11); a V12 só troca o corpo da fn_texto_de_busca. Os gatilhos têm a função tg_* à parte.
        assertThat(contar("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' "
                + "AND p.prokind = 'f' AND p.proname LIKE 'fn\\_%'")).as("funções fn_*").isEqualTo(9);
        assertThat(contar("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' "
                + "AND p.proname IN ('fn_texto_de_busca', 'fn_trava_grupo_documento', 'fn_publicar_documento')"))
                .as("funções da V11").isEqualTo(3);
    }

    @Test
    void amostraEDadosBaseEstaoCarregados() {
        assertThat(contar("SELECT count(*) FROM aba")).isEqualTo(7);
        assertThat(contar("SELECT count(*) FROM cota_tipo")).isEqualTo(3);
        assertThat(contar("SELECT count(*) FROM usuario")).as("2 Admin RBS e 4 Admin Patrocinador").isEqualTo(6);
        assertThat(contar("SELECT count(*) FROM parceria")).as("Amarelo, Roxo e Verde de 2027 mais o Amarelo de 2026").isEqualTo(4);
        assertThat(contar("SELECT count(*) FROM relatorio")).isEqualTo(3);
    }
}
