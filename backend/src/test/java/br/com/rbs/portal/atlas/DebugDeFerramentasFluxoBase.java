package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.conversa;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.eventos;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.falarEmFluxo;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.fim;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.nomes;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.pedido;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.ApoioDoFluxo.Evento;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * [ATLAS] O evento {@code ferramenta} da resposta em fluxo com o debug das ferramentas ligado, nos dois modos do relatório: vem logo
 * que a ferramenta termina (depois da {@code etapa} dela e antes de qualquer texto da ida seguinte), tem o mesmo corpo do item do
 * {@code fim} sem o {@code tipo}, e o raciocínio cru não aparece em evento nenhum. Não é {@code @Transactional}: o turno em fluxo
 * roda em outra thread (veja o ApoioDoFluxo). Não faz parte da API.
 */
abstract class DebugDeFerramentasFluxoBase {

    static final String SENTINELA = "SENTINELA-DO-RACIOCINIO-CRU";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProvedorRoteirizado modelo;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void guardarOQueJaExiste() {
        modelo.limpar();
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagarOQueOTesteConfirmou() {
        limpeza.apagar();
    }

    @Test
    void oEventoFerramentaVemLogoQueAFerramentaTerminaEIgualAoItemDoFim() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        modelo.chamadas(null, SENTINELA, pedido(LoopDoAgenteTest.CONSULTAR, "{}"), pedido(LoopDoAgenteTest.CONSULTAR, "{\"parceria_id\":1}"))
                .texto("Os números de {{parc:patrocinador}} estão no portal.");

        MvcTestResult resposta = falarEmFluxo(pessoa, conversa, "Como estou?");

        List<Evento> eventos = eventos(resposta);
        List<String> nomes = nomes(eventos);
        assertThat(nomes.stream().filter("ferramenta"::equals)).hasSize(2);
        // etapa -> ferramenta, por chamada, e os dois antes de qualquer texto: a próxima ida só vem depois
        int primeiraEtapa = nomes.indexOf("etapa");
        int primeiraFerramenta = nomes.indexOf("ferramenta");
        int ultimaFerramenta = nomes.lastIndexOf("ferramenta");
        assertThat(primeiraEtapa).isGreaterThanOrEqualTo(0).isLessThan(primeiraFerramenta);
        assertThat(ultimaFerramenta).isLessThan(nomes.indexOf("texto")).isLessThan(nomes.indexOf("fim"));
        assertThat(nomes.get(nomes.size() - 1)).isEqualTo("fim");

        List<Evento> ferramentas = eventos.stream().filter(e -> e.nome().equals("ferramenta")).toList();
        Map<String, Object> primeira = JsonPath.read(ferramentas.get(0).dados(), "$");
        assertThat(primeira.keySet()).containsExactly("nome", "argumentos", "resultado", "erro", "duracaoMs");
        assertThat(primeira).containsEntry("nome", LoopDoAgenteTest.CONSULTAR).containsEntry("argumentos", "{}").containsEntry("erro", false);
        assertThat((String) primeira.get("resultado")).contains("\"dados\"");
        assertThat(ferramentas.get(1).<Boolean>ler("$.erro")).isTrue();
        assertThat(ferramentas.get(1).<String>ler("$.argumentos")).isEqualTo("{\"parceria_id\":1}");

        // o fim traz os mesmos dois, como item, com o tipo na frente
        Evento fim = fim(eventos);
        assertThat(fim.<Boolean>ler("$.debugDeFerramentas")).isTrue();
        assertThat(fim.<List<String>>ler("$.itens[*].tipo")).containsSubsequence("usuario", "ferramenta", "ferramenta", "texto");
        for (int i = 0; i < 2; i++) {
            List<Map<String, Object>> itensDoFim = JsonPath.read(fim.dados(), "$.itens[?(@.tipo=='ferramenta')]");
            Map<String, Object> doFim = itensDoFim.get(i);
            Map<String, Object> doEvento = JsonPath.read(ferramentas.get(i).dados(), "$");
            assertThat(doFim).containsEntry("tipo", "ferramenta");
            assertThat(doFim.entrySet().stream().filter(e -> !e.getKey().equals("tipo")).toList()).containsExactlyElementsOf(doEvento.entrySet());
        }

        // o raciocínio cru fica no servidor
        assertThat(corpo(resposta)).doesNotContain(SENTINELA);
    }

    @Test
    void semFerramentaNoTurnoNaoHaEventoFerramenta() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        modelo.texto("Oi, sou o Atlas.");

        List<Evento> eventos = eventos(falarEmFluxo(pessoa, conversa, "Oi"));

        assertThat(nomes(eventos)).doesNotContain("ferramenta");
        assertThat(fim(eventos).<Boolean>ler("$.debugDeFerramentas")).isTrue();
    }
}
