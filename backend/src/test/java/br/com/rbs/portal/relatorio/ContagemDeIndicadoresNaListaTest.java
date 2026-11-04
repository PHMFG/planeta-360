package br.com.rbs.portal.relatorio;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.ApoioDeTeste;
import br.com.rbs.portal.ApoioDeTeste.Csrf;
import br.com.rbs.portal.TesteDeIntegracao;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/** A lista de relatórios (rota 31) conta indicadores do catálogo, não linhas de recorte. [RELATORIO] */
@TesteDeIntegracao
@Transactional
class ContagemDeIndicadoresNaListaTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void listaContaIndicadoresENaoLinhasDeRecorte() {
        MockHttpSession rbs = new MockHttpSession();
        Csrf csrf = ApoioDeTeste.pedirCsrf(mvc);
        MvcTestResult login = mvc.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("X-XSRF-TOKEN", csrf.token()).header("User-Agent", "TesteDeContagem/1.0").cookie(csrf.cookie())
                .session(rbs).content("{\"email\":\"b2b@rbs.example\",\"senha\":\"senha123\"}").exchange();
        assertThat(login).hasStatus(HttpStatus.OK);

        long catalogo = jdbc.sql("SELECT count(*) FROM indicador").query(Long.class).single();
        long amarelo = jdbc.sql("""
                SELECT pa.id FROM parceria pa JOIN patrocinador p ON p.id = pa.patrocinador_id
                JOIN evento e ON e.id = pa.evento_id WHERE p.nome_canonico = 'Cliente Amarelo' AND e.edicao = 2027
                """).query(Long.class).single();
        long relatorio = jdbc.sql("SELECT id FROM relatorio WHERE parceria_id = :p ORDER BY versao DESC LIMIT 1")
                .param("p", amarelo).query(Long.class).single();

        // Pré-condição: há indicador com mais de uma linha (vários recortes), então linhas > indicadores.
        long linhas = jdbc.sql("SELECT count(*) FROM relatorio_indicador WHERE relatorio_id = :r")
                .param("r", relatorio).query(Long.class).single();
        long comLinha = jdbc.sql("SELECT count(DISTINCT indicador_codigo) FROM relatorio_indicador WHERE relatorio_id = :r")
                .param("r", relatorio).query(Long.class).single();
        assertThat(linhas).as("a amostra do Amarelo tem indicador com vários recortes").isGreaterThan(comLinha);

        MvcTestResult resposta = mvc.get().uri("/api/relatorios").header("User-Agent", "TesteDeContagem/1.0")
                .session(rbs).exchange();
        assertThat(resposta).hasStatus(HttpStatus.OK);
        int disponiveis = (Integer) lerJson(resposta, "$[0].relatorio.indicadoresDisponiveis");
        int indisponiveis = (Integer) lerJson(resposta, "$[0].relatorio.indicadoresIndisponiveis");

        assertThat(disponiveis + indisponiveis).as("a soma é o total do catálogo").isEqualTo((int) catalogo);
        assertThat(disponiveis).isLessThanOrEqualTo((int) catalogo);
        assertThat(indisponiveis).isLessThanOrEqualTo((int) catalogo);
    }
}
