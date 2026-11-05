package br.com.rbs.portal.cortesias;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.AMARELO;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.RBS;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.VERDE;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.destinacao;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.eventoDe;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.totalDeDestinacoes;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.uriDestinacoes;
import static br.com.rbs.portal.cortesias.ApoioDeCortesias.verde;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [VALIDACAO] Número que o JSON traz com o tipo ou o tamanho errado é erro do CAMPO (422 com o caminho dele em "campos"), e não
 * "JSON inválido" (400) nem um valor cortado em silêncio: 1.5 num campo inteiro, texto num campo numérico, número acima do que cabe
 * e acima do máximo do banco. JSON quebrado de verdade segue 400. Cada teste é desfeito no fim.
 */
@TesteDeIntegracao
@Transactional
class NumeroInvalidoNoCorpoTest {

    private static final String GIGANTE = "99999999999999999999";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void decimalEmCampoInteiroDa422ENadaGrava() {
        long parceria = verde(jdbc);
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, VERDE);
        long antes = totalDeDestinacoes(jdbc, parceria);

        MvcTestResult resposta = marca.post(uriDestinacoes(parceria), destinacao("ARENA", "SABADO", "1.5", "Maria Souza"));

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(resposta, "$.campos.quantidade")).isEqualTo("Use um número inteiro.");
        assertThat(totalDeDestinacoes(jdbc, parceria)).isEqualTo(antes);
    }

    @Test
    void decimalEmCampoInteiroDeOutroPacoteTambemDa422() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long evento = eventoDe(jdbc, verde(jdbc));

        MvcTestResult resposta = rbs.post("/api/eventos/" + evento + "/pesquisas",
                "{\"nome\":\"Pesquisa X\",\"metodologia\":\"Entrevistas\",\"baseRespondentes\":400.5,\"nivelConfianca\":95}");

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(resposta, "$.campos.baseRespondentes")).isEqualTo("Use um número inteiro.");
    }

    @Test
    void numeroGiganteETextoEmCampoNumericoDao422ComOCampo() {
        long parceria = verde(jdbc);
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, VERDE);
        long antes = totalDeDestinacoes(jdbc, parceria);

        MvcTestResult gigante = marca.post(uriDestinacoes(parceria), destinacao("ARENA", "SABADO", GIGANTE, "Maria Souza"));
        MvcTestResult texto = marca.post(uriDestinacoes(parceria), destinacao("ARENA", "SABADO", "\"abc\"", "Maria Souza"));

        assertThat(gigante).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(gigante, "$.campos.quantidade")).isEqualTo("O número é grande demais.");
        assertThat(texto).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(texto, "$.campos.quantidade")).isEqualTo("Use um número inteiro.");
        assertThat(totalDeDestinacoes(jdbc, parceria)).isEqualTo(antes);
    }

    @Test
    void textoEmNumeroDentroDeListaTrazOCaminhoDoItem() {
        long parceria = verde(jdbc);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult resposta = rbs.post("/api/parcerias/" + parceria + "/posvenda",
                "{\"audiencias\":[{\"veiculo\":\"ATL TV\",\"metrica\":\"pessoas\",\"numero\":\"abc\",\"unidade\":\"pessoas\"}]}");

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(resposta, "$.campos['audiencias[0].numero']")).isEqualTo("Use um número válido.");
    }

    @Test
    void numeroAcimaDoMaximoDoBancoDizOMaximoENaoMaiorQueZero() {
        long amarelo = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult posVenda = rbs.post("/api/parcerias/" + amarelo + "/posvenda",
                "{\"audiencias\":[{\"veiculo\":\"ATL TV\",\"metrica\":\"pessoas\",\"numero\":" + GIGANTE + ",\"unidade\":\"pessoas\"}]}");
        MvcTestResult resultado = rbs.put("/api/eventos/" + eventoDe(jdbc, amarelo) + "/resultados",
                "{\"indicador\":\"Público total\",\"valor\":" + GIGANTE + ",\"unidade\":\"pessoas\",\"fonte\":\"RBS\"}");

        assertThat(posVenda).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(posVenda, "$.campos['audiencias[0].numero']"))
                .isEqualTo("O número passa do máximo: use menos de 100.000.000.000.000.");
        assertThat(resultado).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(resultado, "$.campos.valor")).isEqualTo("O valor passa do máximo: use menos de 100.000.000.000.000.");
    }

    @Test
    void jsonQuebradoDeVerdadeContinuaComOQuatrocentos() {
        long parceria = verde(jdbc);
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, VERDE);

        MvcTestResult resposta = marca.post(uriDestinacoes(parceria), "{\"setor\":\"ARENA\",\"quantidade\":");

        assertThat(resposta).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("O corpo da requisição está vazio ou não é um JSON válido.");
    }

    @Test
    void aPermissaoContinuaAntesDoCorpoComNumeroInvalido() {
        long parceria = verde(jdbc);

        MvcTestResult marcaNaRota = SessaoDeApi.entrar(mvc, AMARELO)
                .post("/api/eventos/" + eventoDe(jdbc, parceria) + "/pesquisas", "{\"baseRespondentes\":1.5}");
        MvcTestResult semSessao = mvc.post().uri(uriDestinacoes(parceria)).contentType("application/json")
                .content(destinacao("ARENA", "SABADO", "1.5", "Maria")).exchange();

        assertThat(marcaNaRota).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(semSessao.getResponse().getStatus()).isIn(401, 403);
    }
}
