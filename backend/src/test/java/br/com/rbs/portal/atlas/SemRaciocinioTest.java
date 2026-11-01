package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.conversa;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.eventos;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.falarEmFluxo;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.fim;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.nomes;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.raciociniosGravados;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.resumosGravados;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.ApoioDoFluxo.Evento;
import br.com.rbs.portal.atlas.ferramentas.RelatorioFerramentas;
import br.com.rbs.portal.atlas.provedor.ServidorDeLlmFalso;
import br.com.rbs.portal.atlas.provedor.ServidorDeLlmFalso.Fluxo;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * [ATLAS] Com {@code atlas.raciocinio=false} não há raciocínio: a ida vai com {@code "thinking": {"type": "disabled"}}, nenhum
 * {@code pensando}, nenhum {@code resumo}, nenhum item {@code raciocinio} e nenhuma chamada de resumo ao provedor. Vale também
 * quando o provedor manda um {@code reasoning_content} mesmo assim: ele não é transmitido, gravado nem resumido. Contexto próprio
 * por causa da propriedade. Não é {@code @Transactional} (veja o AtlasFluxoTest).
 */
@TesteDeIntegracao
class SemRaciocinioTest {

    private static final String CHAVE = "chave-de-mentira-so-do-teste";
    private static final String SENTINELA = "SENTINELA-DO-RACIOCINIO-CRU";
    private static final ServidorDeLlmFalso SERVIDOR = ServidorDeLlmFalso.iniciar();

    @DynamicPropertySource
    static void ligarOModeloNoServidorFalso(DynamicPropertyRegistry registro) {
        registro.add("spring.ai.model.chat", () -> "openai");
        registro.add("spring.ai.openai.api-key", () -> CHAVE);
        registro.add("spring.ai.openai.base-url", SERVIDOR::urlBase);
        registro.add("spring.ai.openai.max-retries", () -> "0");
        registro.add("atlas.raciocinio", () -> "false");
    }

    @AfterAll
    static void derrubarOServidor() {
        SERVIDOR.close();
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void guardarOQueJaExiste() {
        SERVIDOR.limpar();
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagarOQueOTesteConfirmou() {
        limpeza.apagar();
    }

    @Test
    void semRaciocinioNaoHaPensandoResumoNemChamadaDeResumo() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        SERVIDOR.responderEmFluxo(new Fluxo().texto("Os números de ").texto("{{parc:patrocinador}}").texto(" estão no portal.").fim("stop"));

        List<Evento> eventos = eventos(falarEmFluxo(pessoa, conversa, "Como estou?"));

        assertThat(nomes(eventos)).doesNotContain("pensando", "resumo", "raciocinio");
        assertThat(fim(eventos).<List<String>>ler("$.itens[*].tipo")).containsExactly("usuario", "texto");
        assertThat(SERVIDOR.recebidas()).hasSize(1);
        assertThat(JsonPath.<Object>read(SERVIDOR.ultima().corpo(), "$.thinking")).isEqualTo(Map.of("type", "disabled"));
        assertThat(SERVIDOR.resumosRecebidos()).isEmpty();
        assertThat(raciociniosGravados(jdbc, conversa)).containsOnlyNulls();
        assertThat(resumosGravados(jdbc, conversa)).containsOnlyNulls();
    }

    @Test
    void mesmoSeOProvedorMandarRaciocinioEleNaoESaidoNemGravadoNemResumido() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversaFluxo = conversa(pessoa, jdbc);
        SERVIDOR.responderEmFluxo(new Fluxo().raciocinio(SENTINELA).texto("Os números de ").texto("{{parc:patrocinador}}")
                .texto(" estão no portal.").fim("stop"));

        MvcTestResult emFluxo = falarEmFluxo(pessoa, conversaFluxo, "Como estou?");

        assertThat(nomes(eventos(emFluxo))).doesNotContain("pensando", "resumo", "raciocinio");
        assertThat(corpo(emFluxo)).doesNotContain(SENTINELA);
        assertThat(fim(eventos(emFluxo)).<List<String>>ler("$.itens[*].tipo")).containsExactly("usuario", "texto");

        // e em JSON, com uma ferramenta no meio
        long conversaJson = conversa(pessoa, jdbc);
        SERVIDOR.responder(ServidorDeLlmFalso.chamada("call_x", RelatorioFerramentas.CONSULTAR, "{}", SENTINELA));
        SERVIDOR.responder(ServidorDeLlmFalso.texto("Os números de {{parc:patrocinador}} estão no portal."));
        MvcTestResult json = pessoa.post("/api/atlas/conversas/" + conversaJson + "/mensagens", "{\"texto\":\"Como estou?\"}");

        assertThat(json).hasStatus(HttpStatus.OK);
        assertThat(lerLista(json, "$.itens[*].tipo")).containsExactly("usuario", "texto");
        assertThat(corpo(json)).doesNotContain(SENTINELA);
        assertThat(SERVIDOR.resumosRecebidos()).isEmpty();
        assertThat(raciociniosGravados(jdbc, conversaFluxo)).containsOnlyNulls();
        assertThat(raciociniosGravados(jdbc, conversaJson)).containsOnlyNulls();
        assertThat(SERVIDOR.recebidas()).allSatisfy(r -> assertThat(r.corpo()).doesNotContain(SENTINELA));
    }
}
