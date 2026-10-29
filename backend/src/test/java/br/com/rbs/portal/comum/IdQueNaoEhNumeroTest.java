package br.com.rbs.portal.comum;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [VALIDACAO] Id da URL que não é número (GET /api/parcerias/abc) e filtro numérico com texto (?pagina=abc) saem no padrão do
 * contrato, em português: o id vira 404 com a frase "não encontrado" da própria rota, e o filtro segue 400, dizendo qual
 * parâmetro está errado. Antes saía o 400 cru do Spring, em inglês ("Failed to convert 'id' with value: 'abc'").
 * Atende o item 3 da Entrega 2. Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class IdQueNaoEhNumeroTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    void confereNaoEncontrado(MvcTestResult resposta, String frase, String instancia) {
        assertThat(resposta).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(resposta.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(resposta, "$.type")).isEqualTo("about:blank");
        assertThat(lerJson(resposta, "$.title")).isEqualTo("Não encontrado");
        assertThat(lerJson(resposta, "$.detail")).isEqualTo(frase);
        assertThat(lerJson(resposta, "$.instance")).isEqualTo(instancia);
        assertThat(corpo(resposta)).doesNotContain("Failed to convert").doesNotContain("Bad Request");
    }

    @Test
    void idDeParceriaQueNaoEhNumeroDevolve404ComAFraseDeParceriaNaoEncontrada() {
        MvcTestResult rbs = SessaoDeApi.entrar(mvc, "b2b@rbs.example").get("/api/parcerias/abc");
        MvcTestResult patrocinador = SessaoDeApi.entrar(mvc, "marketing@amarelo.example").get("/api/parcerias/abc");
        MvcTestResult inexistente = SessaoDeApi.entrar(mvc, "marketing@amarelo.example").get("/api/parcerias/999999");

        confereNaoEncontrado(rbs, "Não encontramos esta parceria.", "/api/parcerias/abc");
        confereNaoEncontrado(patrocinador, "Não encontramos esta parceria.", "/api/parcerias/abc");
        assertThat(lerJson(patrocinador, "$.detail")).as("igual a um id que não existe").isEqualTo(lerJson(inexistente, "$.detail"));
    }

    @Test
    void idMuitoGrandeParaSerNumeroTambemDevolve404() {
        MvcTestResult resposta = SessaoDeApi.entrar(mvc, "b2b@rbs.example").get("/api/parcerias/99999999999999999999");

        confereNaoEncontrado(resposta, "Não encontramos esta parceria.", "/api/parcerias/99999999999999999999");
    }

    @Test
    void cadaRotaUsaAFraseDoSeuRecurso() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, "b2b@rbs.example");
        Map<String, MvcTestResult> respostas = new LinkedHashMap<>();
        respostas.put("Não encontramos esta parceria.|/api/parcerias/abc/entregas", rbs.get("/api/parcerias/abc/entregas"));
        respostas.put("Não encontramos esta parceria.|/api/parcerias/abc/pesquisa", rbs.get("/api/parcerias/abc/pesquisa"));
        respostas.put("Não encontramos esta parceria.|/api/parcerias/abc/historico", rbs.get("/api/parcerias/abc/historico"));
        respostas.put("Não encontramos esta parceria.|/api/parcerias/abc/consolidacao", rbs.get("/api/parcerias/abc/consolidacao"));
        respostas.put("Não encontramos este e-mail nesta parceria.|/api/parcerias/1/emails/abc", rbs.delete("/api/parcerias/1/emails/abc"));
        respostas.put("Não encontramos esta entrega.|/api/entregas/abc/status",
                rbs.patch("/api/entregas/abc/status", "{\"status\":\"APROVADO\",\"observacao\":\"ok\"}"));
        respostas.put("Não encontramos este arquivo.|/api/arquivos/abc", rbs.get("/api/arquivos/abc"));
        respostas.put("Não encontramos esta pergunta.|/api/pesquisa/perguntas/abc/status",
                rbs.patch("/api/pesquisa/perguntas/abc/status", "{\"decisao\":\"APROVAR\"}"));
        respostas.put("Não encontramos esta carga.|/api/cargas/abc/rejeicoes", rbs.get("/api/cargas/abc/rejeicoes"));
        respostas.put("Não encontramos este relatório.|/api/relatorios/abc/csv", rbs.get("/api/relatorios/abc/csv"));
        respostas.put("Não encontramos este relatório.|/api/relatorios/abc/status",
                rbs.patch("/api/relatorios/abc/status", "{\"status\":\"VALIDADO\"}"));

        respostas.forEach((chave, resposta) -> {
            String[] partes = chave.split("\\|");
            confereNaoEncontrado(resposta, partes[0], partes[1]);
        });
    }

    @Test
    void idQueNaoEhNumeroNumaRotaDaRbsContinuaDando403AoPatrocinador() {
        SessaoDeApi patrocinador = SessaoDeApi.entrar(mvc, "marketing@amarelo.example");

        assertThat(patrocinador.patch("/api/entregas/abc/status", "{}")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(patrocinador.get("/api/cargas/abc/rejeicoes")).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void filtroNumericoComTextoSegueDevolvendo400MasEmPortuguesEDizendoQualParametro() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, "b2b@rbs.example");

        MvcTestResult pagina = rbs.get("/api/entregas?pagina=abc");
        assertThat(pagina).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(pagina.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(pagina, "$.type")).isEqualTo("about:blank");
        assertThat(lerJson(pagina, "$.title")).isEqualTo("Requisição inválida");
        assertThat(lerJson(pagina, "$.detail")).isEqualTo("O parâmetro pagina precisa ser um número inteiro.");
        assertThat(lerJson(pagina, "$.instance")).isEqualTo("/api/entregas");

        MvcTestResult parceria = rbs.get("/api/entregas?parceriaId=abc");
        assertThat(parceria).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(lerJson(parceria, "$.detail")).isEqualTo("O parâmetro parceriaId precisa ser um número inteiro.");

        MvcTestResult booleano = rbs.get("/api/entregas?atrasadas=talvez");
        assertThat(booleano).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(lerJson(booleano, "$.detail")).isEqualTo("O parâmetro atrasadas precisa ser true ou false.");
        assertThat(corpo(booleano)).doesNotContain("Failed to convert").doesNotContain("Bad Request");
    }
}
