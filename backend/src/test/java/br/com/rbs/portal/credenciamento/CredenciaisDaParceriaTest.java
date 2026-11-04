package br.com.rbs.portal.credenciamento;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.comum.ApoioDeDireitos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * R5.5.a e R5.5.b (rotas 77 a 79): a grade de credenciais que a RBS preenche, o que a marca vê em "Sua liberação" e o endereço do Open
 * Staff e o período de cadastro da edição. [CREDENCIAMENTO] O endereço é um link externo: só https, sem usuario@ antes do host. Cada
 * teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class CredenciaisDaParceriaTest extends ApoioDeDireitos {

    private static final String CREDENCIAIS = "/api/parcerias/%d/credenciais";

    private String credenciais(long parceria) {
        return CREDENCIAIS.formatted(parceria);
    }

    private String edicao(long parceria) {
        return "/api/eventos/" + eventoDe(parceria) + "/credenciamento";
    }

    private static String item(String setor, String beneficiario, String quantidade) {
        return "{\"setor\":\"%s\",\"beneficiario\":\"%s\",\"quantidadePorDia\":%s}".formatted(setor, beneficiario, quantidade);
    }

    private static String grade(String... itens) {
        return "{\"credenciais\":[" + String.join(",", itens) + "]}";
    }

    private static String urlEm(String url) {
        return "{\"openStaffUrl\":" + (url == null ? "null" : "\"" + url.replace("\\", "\\\\") + "\"") + "}";
    }

    // ---------------------------------------------------------------------
    // R5.5.a · a grade
    // ---------------------------------------------------------------------

    @Test
    void marcaVeAsCredenciaisDaAmostraEOTotalPorDia() {
        MvcTestResult resposta = entrar(AMARELO).get(credenciais(amarelo()));

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.parceriaId")).isEqualTo((int) amarelo());
        assertThat(lerJson(resposta, "$.totalPorDia")).isEqualTo(115);
        assertThat(lerLista(resposta, "$.credenciais")).hasSize(6);
        assertThat(lerJson(resposta, "$.credenciais[0].setor")).isEqualTo("ARENA");
        assertThat(lerJson(resposta, "$.credenciais[0].beneficiario")).isEqualTo("MARCA");
        assertThat(lerJson(resposta, "$.credenciais[0].quantidadePorDia")).isEqualTo(20);
        assertThat(lerJson(resposta, "$.openStaffUrl")).as("a amostra usa o domínio reservado example.com").isEqualTo("https://example.com/");
        assertThat(lerJson(resposta, "$.credenciamentoInicio")).isEqualTo("2026-12-01");
        assertThat(lerJson(resposta, "$.credenciamentoFim")).isEqualTo("2026-12-20");
    }

    @Test
    void rbsGravaAGradeEAMarcaVeSetorBeneficiarioQuantidadeEPeriodo() {
        SessaoDeApi rbs = entrar(RBS);
        assertThat(rbs.patch(edicao(roxo()), "{\"inicio\":\"2027-01-05\",\"fim\":\"2027-01-20\"}")).hasStatus(HttpStatus.OK);

        MvcTestResult gravada = rbs.patch(credenciais(roxo()), grade(item("ARENA", "GERAL", "10"), item("CAMAROTE", "MARCA", "4"),
                item("PREMIUM", "AGENCIA", "2")));

        assertThat(gravada).hasStatus(HttpStatus.OK);
        assertThat(lerJson(gravada, "$.totalPorDia")).isEqualTo(16);
        MvcTestResult daMarca = entrar(ROXO).get(credenciais(roxo()));
        assertThat(daMarca).hasStatus(HttpStatus.OK);
        assertThat(lerJson(daMarca, "$.totalPorDia")).isEqualTo(16);
        assertThat(lerLista(daMarca, "$.credenciais[*].setor")).containsExactly("ARENA", "CAMAROTE", "PREMIUM");
        assertThat(lerJson(daMarca, "$.credenciais[0].quantidadePorDia")).as("Arena/Geral de 5 para 10").isEqualTo(10);
        assertThat(lerJson(daMarca, "$.credenciamentoInicio")).isEqualTo("2027-01-05");
        assertThat(lerJson(daMarca, "$.credenciamentoFim")).isEqualTo("2027-01-20");
        assertThat(lerJson(daMarca, "$.eventoId")).isEqualTo((int) eventoDe(roxo()));
    }

    @Test
    void quantidadeZeroRetiraALinhaEIgualNaoGravaHistorico() {
        SessaoDeApi rbs = entrar(RBS);
        long antes = contar("SELECT count(*) FROM historico WHERE entidade = 'credencial_cota'");

        MvcTestResult igual = rbs.patch(credenciais(amarelo()), grade(item("ARENA", "MARCA", "20")));
        assertThat(igual).hasStatus(HttpStatus.OK);
        assertThat(contar("SELECT count(*) FROM historico WHERE entidade = 'credencial_cota'")).isEqualTo(antes);

        MvcTestResult retirada = rbs.patch(credenciais(amarelo()), grade(item("ARENA", "MARCA", "0"), item("ARENA", "GERAL", "0")));
        assertThat(retirada).hasStatus(HttpStatus.OK);
        assertThat(lerJson(retirada, "$.totalPorDia")).isEqualTo(95);
        assertThat(lerLista(retirada, "$.credenciais")).hasSize(5);
        List<Map<String, Object>> linhas = jdbc.sql("SELECT acao, valor_anterior, valor_novo, detalhe, parceria_id FROM historico "
                + "WHERE entidade = 'credencial_cota' ORDER BY id").query().listOfRows();
        assertThat(linhas).hasSize((int) antes + 1);
        Map<String, Object> ultima = linhas.get(linhas.size() - 1);
        assertThat(ultima).containsEntry("acao", "EDICAO").containsEntry("valor_anterior", "20").containsEntry("detalhe", "Arena · marca");
        assertThat(ultima.get("valor_novo")).isNull();
        assertThat(((Number) ultima.get("parceria_id")).longValue()).isEqualTo(amarelo());
    }

    @Test
    void quantidadeForaDoLimiteOuQueNaoEInteiraDa422NuncaUm500() {
        SessaoDeApi rbs = entrar(RBS);
        long total = contar("SELECT sum(quantidade_por_dia) FROM credencial_cota WHERE parceria_id = ?", amarelo());

        for (String quantidade : List.of("-1", "32768", "99999999999999999999999", "1e12", "2.5", "null")) {
            MvcTestResult resposta = rbs.patch(credenciais(amarelo()), grade(item("ARENA", "MARCA", quantidade)));
            assertThat(resposta).as("quantidade " + quantidade).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(lerJson(resposta, "$.campos['credenciais[0].quantidadePorDia']")).isNotNull();
        }
        assertThat(rbs.patch(credenciais(amarelo()), grade(item("ARENA", "MARCA", "32767")))).as("o limite do SMALLINT").hasStatus(HttpStatus.OK);
        assertThat(contar("SELECT sum(quantidade_por_dia) FROM credencial_cota WHERE parceria_id = ?", amarelo()))
                .isEqualTo(total - 20 + 32767);
    }

    @Test
    void setorBeneficiarioListaRepetidaOuGrandeDemaisDao422ENadaMuda() {
        SessaoDeApi rbs = entrar(RBS);
        long total = contar("SELECT sum(quantidade_por_dia) FROM credencial_cota WHERE parceria_id = ?", amarelo());

        MvcTestResult setor = rbs.patch(credenciais(amarelo()), grade(item("ARENA", "MARCA", "99"), item("PALCO", "MARCA", "1")));
        assertThat(setor).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(setor, "$.campos['credenciais[1].setor']")).isNotNull();
        assertThat(rbs.patch(credenciais(amarelo()), grade(item("ARENA", "CHEFE", "1")))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(rbs.patch(credenciais(amarelo()), grade(item("ARE\\u0000NA", "MARCA", "1")))).as("NUL no setor").hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(rbs.patch(credenciais(amarelo()), grade(item("ARENA", "MARCA", "5"), item("ARENA", "MARCA", "6"))))
                .as("par repetido").hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        List<String> muitos = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            muitos.add(item("ARENA", "MARCA", "1"));
        }
        assertThat(rbs.patch(credenciais(amarelo()), grade(muitos.toArray(String[]::new)))).as("25 itens").hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        for (String vazio : List.of("{}", "{\"credenciais\":[]}", "{\"credenciais\":null}")) {
            assertThat(rbs.patch(credenciais(amarelo()), vazio)).as(vazio).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        assertThat(contar("SELECT sum(quantidade_por_dia) FROM credencial_cota WHERE parceria_id = ?", amarelo()))
                .as("tudo ou nada: o item válido da primeira chamada não ficou").isEqualTo(total);
    }

    @Test
    void parceriaEncerradaRecusaAGradeEOutraMarcaRecebe404() {
        SessaoDeApi rbs = entrar(RBS);
        encerrar(roxo());

        MvcTestResult encerrada = rbs.patch(credenciais(roxo()), grade(item("ARENA", "GERAL", "9")));

        assertThat(encerrada).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(encerrada, "$.detail")).isEqualTo("Esta parceria está encerrada e não aceita alterações.");
        assertThat(entrar(ROXO).get(credenciais(roxo()))).as("a leitura continua").hasStatus(HttpStatus.OK);
        assertThat(entrar(AMARELO).get(credenciais(roxo()))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(rbs.get(credenciais(999_999))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(rbs.patch(credenciais(999_999), grade(item("ARENA", "GERAL", "9")))).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void abaDoCredenciamentoFechadaOuOcultaDa403ParaAMarcaEARbsContinuaPassando() {
        fechar(roxo(), "CREDENCIAMENTO");

        MvcTestResult fechada = entrar(ROXO).get(credenciais(roxo()));

        assertThat(fechada).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(fechada, "$.detail")).isEqualTo(FECHADA);
        assertThat(entrar(RBS).get(credenciais(roxo()))).hasStatus(HttpStatus.OK);
        jdbc.sql("UPDATE parceria_aba SET liberada = TRUE WHERE parceria_id = :p").param("p", roxo()).update();
        esconder(roxo(), "CREDENCIAMENTO");
        assertThat(entrar(ROXO).get(credenciais(roxo()))).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void marcaRecebe403NaGradeEnoEdicaoAntesDeLerOCorpo() {
        SessaoDeApi marca = entrar(ROXO);

        for (String corpo : List.of("", "{}", "isto nao e json", grade(item("ARENA", "GERAL", "-5")), urlEm("http://x.com"))) {
            assertThat(marca.patch(credenciais(roxo()), corpo)).as("grade com [" + corpo + "]").hasStatus(HttpStatus.FORBIDDEN);
            assertThat(marca.patch(edicao(roxo()), corpo)).as("edição com [" + corpo + "]").hasStatus(HttpStatus.FORBIDDEN);
            assertThat(marca.patch(credenciais(999_999), corpo)).hasStatus(HttpStatus.FORBIDDEN);
        }
        assertThat(contar("SELECT quantidade_por_dia FROM credencial_cota WHERE parceria_id = ?", roxo())).isEqualTo(5);
        assertThat(SessaoDeApi.anonima(mvc).get(credenciais(roxo()))).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    // ---------------------------------------------------------------------
    // R5.5.b · o Open Staff e o período
    // ---------------------------------------------------------------------

    @Test
    void rbsInformaOEnderecoHttpsEAMarcaOVeNaRotaDasCredenciais() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult gravado = rbs.patch(edicao(roxo()), urlEm("https://example.com/"));

        assertThat(gravado).hasStatus(HttpStatus.OK);
        assertThat(lerJson(gravado, "$.credenciamentoUrl")).isEqualTo("https://example.com/");
        assertThat(lerJson(gravado, "$.edicao")).isEqualTo(2027);
        assertThat(lerJson(entrar(ROXO).get(credenciais(roxo())), "$.openStaffUrl")).isEqualTo("https://example.com/");
        assertThat(lerJson(entrar(AMARELO).get(credenciais(amarelo())), "$.openStaffUrl")).as("é da edição: vale para todas as marcas")
                .isEqualTo("https://example.com/");
        // A RBS vê o mesmo na lista de edições (rota 6).
        MvcTestResult eventos = rbs.get("/api/eventos");
        assertThat(lerLista(eventos, "$[?(@.edicao==2027)].credenciamentoUrl")).containsExactly("https://example.com/");
    }

    @Test
    void enderecosValidosComArrobaDepoisDoHostEPortaPassam() {
        SessaoDeApi rbs = entrar(RBS);

        for (String url : List.of("https://example.com", "https://openstaff.example.com/evento?edicao=2027#entrada", "https://example.com/@perfil",
                "https://example.com:8443/x", "https://exemplo.com.br/credenciamento/atlantida")) {
            assertThat(rbs.patch(edicao(roxo()), urlEm(url))).as(url).hasStatus(HttpStatus.OK);
        }
    }

    @Test
    void httpJavascriptUsuarioArrobaEspacoControleBidiOuMuitoGrandeDao422() {
        SessaoDeApi rbs = entrar(RBS);
        assertThat(rbs.patch(edicao(roxo()), urlEm("https://example.com/"))).hasStatus(HttpStatus.OK);

        List<String> ruins = List.of("http://example.com/", "javascript:alert(1)", "ftp://example.com/", "//example.com/", "example.com",
                "HTTP://example.com/", "https://", "https:///x", "https://usuario@example.com/", "https://usuario:senha@example.com/",
                "https://example.com@evil.com/", "https://example.com\\@evil.com/", "https://exa mple.com/", "https://example.com/a b",
                "https://example.com/\\u0000", "https://example.com/\\u0007", "https://example.com/\\u202e", "https://\\u200eexample.com/",
                "https://example.com:abc/", "https://" + "a".repeat(500) + ".com/");
        for (String url : ruins) {
            MvcTestResult resposta = rbs.patch(edicao(roxo()), "{\"openStaffUrl\":\"" + url.replace("\\\\", "\\\\\\\\").replace("\\@", "\\\\@") + "\"}");
            assertThat(resposta).as("endereço [" + url + "]").hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(lerJson(resposta, "$.campos.openStaffUrl")).isNotNull();
        }
        assertThat(rbs.patch(edicao(roxo()), "{\"openStaffUrl\":123}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(entrar(ROXO).get(credenciais(roxo())), "$.openStaffUrl")).as("nada ruim ficou gravado").isEqualTo("https://example.com/");
        assertThat(contar("SELECT count(*) FROM evento WHERE credenciamento_url !~ '^https://'")).isZero();
    }

    @Test
    void vazioOuNuloLimpaEOQueNaoVemNoCorpoNaoMuda() {
        SessaoDeApi rbs = entrar(RBS);
        assertThat(rbs.patch(edicao(roxo()), "{\"openStaffUrl\":\"https://example.com/\",\"inicio\":\"2027-01-05\",\"fim\":\"2027-01-20\"}"))
                .hasStatus(HttpStatus.OK);

        MvcTestResult soPeriodo = rbs.patch(edicao(roxo()), "{\"fim\":\"2027-01-25\"}");
        assertThat(lerJson(soPeriodo, "$.credenciamentoUrl")).isEqualTo("https://example.com/");
        assertThat(lerJson(soPeriodo, "$.credenciamentoInicio")).isEqualTo("2027-01-05");
        assertThat(lerJson(soPeriodo, "$.credenciamentoFim")).isEqualTo("2027-01-25");

        MvcTestResult limpo = rbs.patch(edicao(roxo()), "{\"openStaffUrl\":\"\"}");
        assertThat(lerJson(limpo, "$.credenciamentoUrl")).isNull();
        assertThat(lerJson(limpo, "$.credenciamentoFim")).isEqualTo("2027-01-25");
        assertThat(lerJson(rbs.patch(edicao(roxo()), "{\"openStaffUrl\":null,\"inicio\":null,\"fim\":\"\"}"), "$.credenciamentoFim")).isNull();
        assertThat(lerJson(entrar(ROXO).get(credenciais(roxo())), "$.openStaffUrl")).isNull();
    }

    @Test
    void periodoComFimAntesDoInicioOuDataInvalidaDa422() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult invertido = rbs.patch(edicao(roxo()), "{\"inicio\":\"2027-01-20\",\"fim\":\"2027-01-05\"}");
        assertThat(invertido).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(invertido, "$.campos.fim")).isNotNull();
        assertThat(rbs.patch(edicao(roxo()), "{\"inicio\":\"2027-01-20\",\"fim\":\"2027-01-20\"}")).as("o mesmo dia vale").hasStatus(HttpStatus.OK);
        // O fim também é conferido contra o início que já estava gravado.
        assertThat(rbs.patch(edicao(roxo()), "{\"fim\":\"2027-01-01\"}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        for (String data : List.of("amanha", "2027-02-30", "27-01-05", "1999-12-31", "2101-01-01")) {
            assertThat(rbs.patch(edicao(roxo()), "{\"inicio\":\"" + data + "\"}")).as("data " + data).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        assertThat(rbs.patch(edicao(roxo()), "[]")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void edicaoQueNaoExisteDa404EAMudancaFicaNoHistoricoSemParceria() {
        SessaoDeApi rbs = entrar(RBS);
        long evento = eventoDe(roxo());

        assertThat(rbs.patch("/api/eventos/999999/credenciamento", urlEm("https://example.com/"))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(rbs.patch(edicao(roxo()), urlEm("https://openstaff.example.com/"))).hasStatus(HttpStatus.OK);
        assertThat(rbs.patch(edicao(roxo()), urlEm("https://openstaff.example.com/"))).as("igual não repete").hasStatus(HttpStatus.OK);

        List<Map<String, Object>> linhas = jdbc.sql("SELECT acao, parceria_id, valor_novo, detalhe FROM historico WHERE entidade = 'evento' AND entidade_id = :e "
                + "AND detalhe LIKE '%credenciamento%'").param("e", evento).query().listOfRows();
        assertThat(linhas).hasSize(1);
        assertThat(linhas.get(0)).containsEntry("acao", "EDICAO").containsEntry("valor_novo", "https://openstaff.example.com/")
                .containsEntry("detalhe", "endereço do credenciamento");
        assertThat(linhas.get(0).get("parceria_id")).as("a marca não vê esta linha").isNull();
        assertThat(corpo(entrar(ROXO).get("/api/parcerias/" + roxo() + "/historico"))).doesNotContain("endereço do credenciamento");
    }
}
