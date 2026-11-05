package br.com.rbs.portal.relatorio;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.comum.ApoioDeDireitos;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * R8.2 (rotas 100 e 101): a RBS informa os números da edição ("O festival em números"). [RELATORIO] Com os números informados, o
 * indicador FESTIVAL_NUMERO deixa de sair como indisponível na versão do relatório gerada depois; as versões que já existiam não mudam
 * (o relatório é uma fotografia). O catálogo continua com 17 indicadores e as 6 seções. [PERMISSAO] Só a RBS. Cada teste é desfeito no
 * fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class ResultadosDaEdicaoTest extends ApoioDeDireitos {

    private static final String PUBLICO = "{\"indicador\":\"Público total\",\"valor\":80000,\"qualificador\":\"MAIS_DE\",\"unidade\":\"pessoas\",\"fonte\":\"RBS, balanço da edição\"}";
    private static final String MARCAS = "{\"indicador\":\"Marcas patrocinadoras\",\"valor\":39,\"unidade\":\"marcas\",\"fonte\":\"RBS\"}";

    private String resultados(long parceria) {
        return "/api/eventos/" + eventoDe(parceria) + "/resultados";
    }

    private MvcTestResult gerar(SessaoDeApi rbs, long parceria) {
        MvcTestResult resposta = rbs.post("/api/parcerias/" + parceria + "/relatorios", null);
        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        return resposta;
    }

    private static List<Object> festivalNumero(MvcTestResult relatorio, String campo) {
        return lerLista(relatorio, "$.secoes[*].indicadores[?(@.codigo=='FESTIVAL_NUMERO')]." + campo);
    }

    @Test
    void oIndicadorSaiDeIndisponivelDepoisDeInformarOsNumerosENaProximaVersao() {
        SessaoDeApi rbs = entrar(RBS);
        long amarelo = amarelo();
        assertThat(contar("SELECT count(*) FROM evento_resultado WHERE evento_id = ?", eventoDe(amarelo))).as("a amostra de 2027 não traz números").isZero();

        MvcTestResult antes = gerar(rbs, amarelo);
        assertThat(festivalNumero(antes, "disponivel")).containsExactly(false);
        assertThat(festivalNumero(antes, "valor")).containsOnlyNulls();

        assertThat(rbs.put(resultados(amarelo), PUBLICO)).hasStatus(HttpStatus.CREATED);
        assertThat(rbs.put(resultados(amarelo), MARCAS)).hasStatus(HttpStatus.CREATED);

        // A versão que já existia não muda; a nova traz os dois números, com o recorte, a unidade e o "mais de".
        long idAntes = ((Number) lerJson(antes, "$.id")).longValue();
        assertThat(festivalNumero(rbs.get("/api/parcerias/" + amarelo + "/relatorio?versao=" + lerJson(antes, "$.versao")), "disponivel")).containsExactly(false);
        MvcTestResult depois = gerar(rbs, amarelo);
        assertThat(festivalNumero(depois, "disponivel")).containsExactly(true, true);
        assertThat(festivalNumero(depois, "recorte")).containsExactlyInAnyOrder("Público total", "Marcas patrocinadoras");
        assertThat(festivalNumero(depois, "valor")).containsExactlyInAnyOrder(80000, 39);
        assertThat(lerLista(depois, "$.secoes[*].indicadores[?(@.recorte=='Público total')].baseCalculo")).containsExactly("mais de; RBS, balanço da edição");
        assertThat(contar("SELECT count(*) FROM relatorio_indicador WHERE relatorio_id = ? AND indicador_codigo = 'FESTIVAL_NUMERO' AND NOT disponivel", idAntes))
                .as("a fotografia antiga continua indisponível").isEqualTo(1);

        // As 6 seções e os 17 indicadores do catálogo continuam.
        assertThat(lerLista(depois, "$.secoes")).hasSize(6);
        assertThat(lerLista(depois, "$.secoes[*].indicadores[*].codigo").stream().distinct()).hasSize(17);
        assertThat(contar("SELECT count(*) FROM indicador")).isEqualTo(17);
    }

    @Test
    void informarDeNovoOMesmoIndicadorTrocaOValorSemRepetir() {
        SessaoDeApi rbs = entrar(RBS);
        long amarelo = amarelo();

        MvcTestResult primeiro = rbs.put(resultados(amarelo), PUBLICO);
        MvcTestResult segundo = rbs.put(resultados(amarelo), PUBLICO.replace("80000", "85000.5").replace("MAIS_DE", "EXATO"));

        assertThat(primeiro).hasStatus(HttpStatus.CREATED);
        assertThat(segundo).hasStatus(HttpStatus.OK);
        assertThat(lerJson(segundo, "$.id")).isEqualTo(lerJson(primeiro, "$.id"));
        MvcTestResult lista = rbs.get(resultados(amarelo));
        assertThat(lista).hasStatus(HttpStatus.OK);
        assertThat(lerLista(lista, "$[*].indicador")).containsExactly("Público total");
        assertThat(lerLista(lista, "$[*].valor")).containsExactly(85000.5);
        assertThat(lerLista(lista, "$[*].qualificador")).containsExactly("EXATO");
    }

    @Test
    void oPadraoDoQualificadorEExatoEOHistoricoGuardaOAntesEODepoisSemParceria() {
        SessaoDeApi rbs = entrar(RBS);
        long amarelo = amarelo();

        MvcTestResult primeiro = rbs.put(resultados(amarelo), MARCAS);
        rbs.put(resultados(amarelo), MARCAS.replace("39", "40"));

        assertThat(lerJson(primeiro, "$.qualificador")).isEqualTo("EXATO");
        long id = ((Number) lerJson(primeiro, "$.id")).longValue();
        assertThat(contar("SELECT count(*) FROM historico WHERE entidade = 'evento_resultado' AND entidade_id = ? AND parceria_id IS NULL AND acao = 'CRIACAO'", id)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT valor_anterior || ' > ' || valor_novo FROM historico WHERE entidade = 'evento_resultado' AND entidade_id = :id AND acao = 'EDICAO'")
                .param("id", id).query(String.class).single()).isEqualTo("39 marcas > 40 marcas");
    }

    @Test
    void valoresInvalidosDao422ComOCampoErrado() {
        SessaoDeApi rbs = entrar(RBS);
        String uri = resultados(amarelo());

        assertThat(lerJson(rbs.put(uri, PUBLICO.replace("80000", "-1")), "$.campos.valor")).isEqualTo("O valor tem de ser maior que zero.");
        assertThat(lerJson(rbs.put(uri, PUBLICO.replace("80000", "0")), "$.campos.valor")).isEqualTo("O valor tem de ser maior que zero.");
        assertThat(lerJson(rbs.put(uri, PUBLICO.replace("80000", "1.234")), "$.campos.valor")).isEqualTo("O valor tem no máximo 2 casas decimais.");
        assertThat(lerJson(rbs.put(uri, PUBLICO.replace("MAIS_DE", "QUASE")), "$.campos.qualificador")).isEqualTo("Use EXATO ou MAIS_DE.");
        assertThat(lerJson(rbs.put(uri, PUBLICO.replace("Público total", "")), "$.campos.indicador")).isNotNull();
        assertThat(lerJson(rbs.put(uri, PUBLICO.replace("Público total", "a".repeat(81))), "$.campos.indicador")).asString().contains("80");
        assertThat(lerJson(rbs.put(uri, PUBLICO.replace("pessoas", "u".repeat(21))), "$.campos.unidade")).asString().contains("20");
        assertThat(lerJson(rbs.put(uri, PUBLICO.replace("RBS, balanço da edição", "")), "$.campos.fonte")).isEqualTo("Informe de onde vem o número.");
        assertThat(lerJson(rbs.put(uri, "{}"), "$.campos.indicador")).isNotNull();
        assertThat(rbs.put(uri, PUBLICO.replace("Público total", "Pú\\u0000blico"))).as("NUL dá 422, não 500").hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(contar("SELECT count(*) FROM evento_resultado WHERE evento_id = ?", eventoDe(amarelo()))).isZero();
    }

    @Test
    void marcaRecebe403NasDuasRotasEEdicaoQueNaoExisteDa404() {
        SessaoDeApi marca = entrar(AMARELO);
        SessaoDeApi rbs = entrar(RBS);
        String uri = resultados(amarelo());

        assertThat(marca.get(uri)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(marca.put(uri, PUBLICO)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(rbs.get("/api/eventos/999999999/resultados")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(rbs.put("/api/eventos/999999999/resultados", PUBLICO)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(SessaoDeApi.anonima(mvc).get(uri)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void osNumerosSaoDaEdicaoENaoVazamParaOutra() {
        SessaoDeApi rbs = entrar(RBS);
        long amarelo = amarelo();
        long edicao2026 = jdbc.sql("SELECT id FROM evento WHERE edicao = 2026").query(Long.class).single();
        rbs.put(resultados(amarelo), PUBLICO.replace("Público total", "Só de 2027"));

        assertThat(lerLista(rbs.get("/api/eventos/" + edicao2026 + "/resultados"), "$[*].indicador")).doesNotContain("Só de 2027");
        assertThat(lerLista(rbs.get(resultados(amarelo)), "$[*].indicador")).contains("Só de 2027");
    }
}
