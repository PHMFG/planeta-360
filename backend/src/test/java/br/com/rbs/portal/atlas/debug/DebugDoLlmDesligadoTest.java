package br.com.rbs.portal.atlas.debug;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * [ATLAS] Sem {@code atlas.debug.chaves=true} (o contexto de teste normal, como em produção), as rotas de debug da chave do
 * modelo de linguagem não existem: as quatro respondem 404, mesmo com sessão e CSRF.
 */
@TesteDeIntegracao
class DebugDoLlmDesligadoTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void asQuatroRotasDeDebugDao404() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, "b2b@rbs.example");
        String chave = "{\"chave\":\"chave-de-mentira-so-do-teste\"}";

        assertThat(rbs.get("/api/debug/llm")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(rbs.put("/api/debug/llm", chave)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(rbs.delete("/api/debug/llm")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(rbs.post("/api/debug/llm/teste", null)).hasStatus(HttpStatus.NOT_FOUND);
    }
}
