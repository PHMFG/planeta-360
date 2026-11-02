package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.RBS;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] O diagnóstico DESLIGADO, que é o padrão e o que a suíte usa (bloqueante do M2): as rotas 49 a 53 respondem 404 a quem pode
 * usá-las (como uma rota que não existe), a permissão continua vindo antes (a marca recebe 403 nas da RBS e a RBS, 403 na da marca), a
 * conversa diz {@code diagnosticoDisponivel: false} e não traz {@code turno}, e um turno completo não grava uma linha em
 * {@code atlas_traco}. Cada teste é desfeito no fim (@Transactional).
 */
@TesteComLlmRoteirizado
@Transactional
class DiagnosticoDesligadoTest {

    private static final String NAO_ENCONTRADO = "Não encontramos o que você pediu.";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProvedorRoteirizado modelo;

    @BeforeEach
    void zerarORoteiro() {
        modelo.limpar();
    }

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    long abrirConversa(SessaoDeApi pessoa) {
        MvcTestResult resposta = pessoa.post("/api/parcerias/" + amarelo() + "/atlas/conversas", "{}");
        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        return ((Number) lerJson(resposta, "$.conversaId")).longValue();
    }

    @Test
    void umTurnoCompletoNaoGravaTracoNemDizQueHaDiagnostico() {
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = abrirConversa(marca);
        modelo.chamadaComRaciocinio("Preciso consultar.", LoopDoAgenteTest.CONSULTAR, "{}").texto("Os dados estão no portal.");
        long antes = jdbc.sql("SELECT count(*) FROM atlas_traco").query(Long.class).single();

        MvcTestResult turno = marca.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Como estou?\"}");

        assertThat(turno).hasStatus(HttpStatus.OK);
        assertThat(lerJson(turno, "$.diagnosticoDisponivel")).isEqualTo(false);
        assertThat(corpo(turno)).as("nenhum item leva o id do turno").doesNotContain("\"turno\"");
        assertThat(lerJson(marca.get("/api/atlas/conversas/" + conversa), "$.diagnosticoDisponivel")).isEqualTo(false);
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_traco").query(Long.class).single()).isEqualTo(antes);
        // a conversa abre com o campo falso também
        assertThat(lerJson(marca.post("/api/parcerias/" + amarelo() + "/atlas/conversas", "{}"), "$.diagnosticoDisponivel")).isEqualTo(false);
    }

    @Test
    void respostaDeFerramentaSemOPedidoNaoDerrubaAConversaComODiagnosticoDesligado() {
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = abrirConversa(marca);
        jdbc.sql("INSERT INTO atlas_mensagem (conversa_id, ordem, papel, conteudo, tool_call_id) VALUES (:c, 1, 'tool', '{}', 'sem_pedido')")
                .param("c", conversa).update();

        MvcTestResult completa = marca.get("/api/atlas/conversas/" + conversa);

        assertThat(completa).hasStatus(HttpStatus.OK);
        assertThat(lerJson(completa, "$.diagnosticoDisponivel")).isEqualTo(false);
    }

    @Test
    void asRotasDoDiagnosticoRespondem404AQuemPodeUsaLasComoSeNaoExistissem() {
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = abrirConversa(marca);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        // a marca dona da conversa, na rota 49
        MvcTestResult daMarca = marca.get("/api/atlas/conversas/" + conversa + "/diagnostico");
        assertThat(daMarca).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(daMarca, "$.detail")).isEqualTo("Não encontramos esta conversa.");
        // a RBS, nas rotas 50 a 53
        for (MvcTestResult resposta : java.util.List.of(rbs.get("/api/atlas/diagnostico/turnos"),
                rbs.get("/api/atlas/diagnostico/turnos/" + UUID.randomUUID()), rbs.get("/api/atlas/diagnostico/turnos/nao-e-um-uuid"),
                rbs.get("/api/atlas/provedores"), rbs.post("/api/atlas/provedores/llm/teste", null),
                rbs.post("/api/atlas/provedores/jev/teste", null), rbs.post("/api/atlas/provedores/gemini/teste", null),
                rbs.post("/api/atlas/provedores/inexistente/teste", null))) {
            assertThat(resposta).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo(NAO_ENCONTRADO);
        }
        assertThat(modelo.pedidos()).as("o teste do llm não chamou o modelo com o diagnóstico desligado").isEmpty();
    }

    @Test
    void aPermissaoContinuaVindoAntesDoNaoEncontrado() {
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long conversa = abrirConversa(marca);

        assertThat(marca.get("/api/atlas/diagnostico/turnos")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(marca.get("/api/atlas/provedores")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(marca.post("/api/atlas/provedores/llm/teste", null)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(rbs.get("/api/atlas/conversas/" + conversa + "/diagnostico")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(SessaoDeApi.anonima(mvc).get("/api/atlas/provedores")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aPaginacaoInvalidaDaRbsDa422MesmoComODiagnosticoDesligadoEOsOutrosFiltrosDao404() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        // pagina e tamanho são conferidos antes de a rota chegar ao service, que é quem sabe que o diagnóstico está desligado
        for (String uri : java.util.List.of("/api/atlas/diagnostico/turnos?tamanho=999", "/api/atlas/diagnostico/turnos?tamanho=0",
                "/api/atlas/diagnostico/turnos?pagina=-1")) {
            assertThat(rbs.get(uri)).as(uri).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        // os filtros de data e de resultado são conferidos no service, depois do 404
        for (String uri : java.util.List.of("/api/atlas/diagnostico/turnos?de=ontem", "/api/atlas/diagnostico/turnos?resultado=TALVEZ",
                "/api/atlas/diagnostico/turnos?de=%2B300000-01-01", "/api/atlas/diagnostico/turnos")) {
            MvcTestResult resposta = rbs.get(uri);
            assertThat(resposta).as(uri).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo(NAO_ENCONTRADO);
        }
    }
}
