package br.com.rbs.portal.parceria;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [PERMISSAO] Abas e cota: toda aba devolvida pela API traz {@code incluidaNaCota} (vem de cota_tipo_aba), para o front
 * separar "aba fora da sua cota" de "aba fechada pela RBS"; e a RBS não consegue liberar uma aba que a cota não inclui
 * (a decisão do projeto: Pesquisa só para a cota Master). Fechar continua permitido. Atende o item 3 da Entrega 2.
 * Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class AbasEDaCotaTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String ROXO = "marketing@roxo.example";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String FORA_DA_COTA = "Esta cota não inclui a aba Pesquisa da ativação. Para dar acesso, troque a cota da parceria.";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    long roxo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Roxo");
    }

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    String abasLiberadas(long parceriaId) {
        return jdbc.sql("""
                        SELECT string_agg(a.codigo, ',' ORDER BY a.ordem) FROM parceria_aba pab JOIN aba a ON a.id = pab.aba_id
                        WHERE pab.parceria_id = :p AND pab.liberada
                        """).param("p", parceriaId).query(String.class).single();
    }

    long contar(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    // ---------------------------------------------------------------------
    // incluidaNaCota em toda lista de abas
    // ---------------------------------------------------------------------

    @Test
    void euEOLoginTrazemIncluidaNaCotaEmCadaAba() {
        SessaoDeApi roxo = SessaoDeApi.entrar(mvc, ROXO);

        MvcTestResult eu = roxo.get("/api/auth/eu");

        assertThat(lerLista(eu, "$.parcerias[0].abas[*].incluidaNaCota")).hasSize(7);
        assertThat(lerLista(eu, "$.parcerias[0].abas[?(@.codigo=='PESQUISA')].incluidaNaCota")).containsExactly(false);
        assertThat(lerLista(eu, "$.parcerias[0].abas[?(@.codigo!='PESQUISA')].incluidaNaCota")).containsOnly(true);
        assertThat(lerLista(eu, "$.parcerias[0].abas[?(@.codigo=='PESQUISA')].liberada")).containsExactly(false);

        MvcTestResult login = SessaoDeApi.anonima(mvc).post("/api/auth/login",
                "{\"email\":\"marketing@roxo.example\",\"senha\":\"" + SessaoDeApi.SENHA + "\"}");
        assertThat(lerLista(login, "$.parcerias[0].abas[?(@.codigo=='PESQUISA')].incluidaNaCota")).containsExactly(false);
    }

    @Test
    void masterIncluiTodasAsAbas() {
        MvcTestResult eu = SessaoDeApi.entrar(mvc, AMARELO).get("/api/auth/eu");

        assertThat(lerLista(eu, "$.parcerias[0].abas[*].incluidaNaCota")).hasSize(7).containsOnly(true);
    }

    @Test
    void detalheDaParceriaEAEdicaoTrazemIncluidaNaCota() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        for (MvcTestResult resposta : List.of(
                rbs.get("/api/parcerias/{id}", roxo()),
                SessaoDeApi.entrar(mvc, ROXO).get("/api/parcerias/{id}", roxo()),
                rbs.patch("/api/parcerias/" + roxo(), "{\"nomeProduto\":\"Outro nome\"}"))) {
            assertThat(resposta).hasStatus(HttpStatus.OK);
            assertThat(lerLista(resposta, "$.abas[*].incluidaNaCota")).hasSize(7);
            assertThat(lerLista(resposta, "$.abas[?(@.codigo=='PESQUISA')].incluidaNaCota")).containsExactly(false);
            assertThat(lerLista(resposta, "$.abas[?(@.codigo!='PESQUISA')].incluidaNaCota")).containsOnly(true);
        }
    }

    @Test
    void parceriaCriadaTrazIncluidaNaCotaDaCotaEscolhida() {
        long evento2027 = contar("SELECT id FROM evento WHERE edicao = 2027");

        MvcTestResult criada = SessaoDeApi.entrar(mvc, RBS).post("/api/parcerias",
                "{\"patrocinador\":{\"nome\":\"Marca Nova\"},\"eventoId\":%d,\"cotaCodigo\":\"MASTER\"}".formatted(evento2027));

        assertThat(criada).hasStatus(HttpStatus.CREATED);
        assertThat(lerLista(criada, "$.abas[*].incluidaNaCota")).hasSize(7).containsOnly(true);
    }

    @Test
    void incluidaNaCotaVemDeCotaTipoAbaENaoDeUmaListaNoCodigo() {
        // Se a RBS decidir incluir a Pesquisa na Subcota G (basta uma linha em cota_tipo_aba), a API acompanha.
        jdbc.sql("""
                        INSERT INTO cota_tipo_aba (cota_tipo_id, aba_id)
                        SELECT ct.id, a.id FROM cota_tipo ct, aba a WHERE ct.codigo = 'SUBCOTA_G' AND a.codigo = 'PESQUISA'
                        """).update();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult detalhe = rbs.get("/api/parcerias/{id}", roxo());
        assertThat(lerLista(detalhe, "$.abas[?(@.codigo=='PESQUISA')].incluidaNaCota")).containsExactly(true);

        MvcTestResult libera = rbs.patch("/api/parcerias/" + roxo(), "{\"abas\":[{\"codigo\":\"PESQUISA\",\"liberada\":true}]}");
        assertThat(libera).hasStatus(HttpStatus.OK);
        assertThat(lerLista(libera, "$.abas[?(@.codigo=='PESQUISA')].liberada")).containsExactly(true);
    }

    // ---------------------------------------------------------------------
    // PATCH: não libera aba que a cota não inclui; fechar continua permitido
    // ---------------------------------------------------------------------

    @Test
    void rbsNaoLiberaPesquisaParaSubcotaG() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        String antes = abasLiberadas(roxo());
        long historicoAntes = contar("SELECT count(*) FROM historico WHERE acao = 'LIBERACAO_ABA'");

        MvcTestResult resposta = rbs.patch("/api/parcerias/" + roxo(), "{\"abas\":[{\"codigo\":\"PESQUISA\",\"liberada\":true}]}");

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(resposta.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(resposta, "$.title")).isEqualTo("Dados inválidos");
        assertThat(lerJson(resposta, "$.detail")).as("o front mostra o detail").isEqualTo(FORA_DA_COTA);
        assertThat(lerJson(resposta, "$.campos['abas[0].liberada']")).isEqualTo(FORA_DA_COTA);
        assertThat(abasLiberadas(roxo())).isEqualTo(antes);
        assertThat(contar("SELECT count(*) FROM historico WHERE acao = 'LIBERACAO_ABA'")).isEqualTo(historicoAntes);
        // E a marca continua sem enviar perguntas.
        assertThat(SessaoDeApi.entrar(mvc, ROXO).put("/api/parcerias/" + roxo() + "/pesquisa/perguntas",
                "{\"perguntas\":[{\"ordem\":1,\"texto\":\"Você visitou o espaço da marca?\"}]}")).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void recusaVaiPorInteiroNadaDoPatchFicaGravado() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        String antes = abasLiberadas(roxo());

        MvcTestResult resposta = rbs.patch("/api/parcerias/" + roxo(),
                "{\"nomeProduto\":\"Nome que não deve ficar\",\"abas\":[{\"codigo\":\"CORTESIAS\",\"liberada\":false},"
                        + "{\"codigo\":\"PESQUISA\",\"liberada\":true}]}");

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(resposta, "$.campos['abas[1].liberada']")).isEqualTo(FORA_DA_COTA);
        assertThat(abasLiberadas(roxo())).as("a Cortesias que vinha antes na lista também não fechou").isEqualTo(antes);
        assertThat(contar("SELECT count(*) FROM parceria WHERE nome_produto = 'Nome que não deve ficar'")).isZero();
    }

    @Test
    void erroDeCotaEOutroErroDeCampoVemJuntosEODetailVoltaAoPadrao() {
        MvcTestResult resposta = SessaoDeApi.entrar(mvc, RBS).patch("/api/parcerias/" + roxo(),
                "{\"patrocinador\":{\"nome\":\"  \"},\"abas\":[{\"codigo\":\"PESQUISA\",\"liberada\":true}]}");

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("Confira os campos destacados e tente de novo.");
        assertThat(lerJson(resposta, "$.campos['abas[0].liberada']")).isEqualTo(FORA_DA_COTA);
        assertThat(lerJson(resposta, "$.campos['patrocinador.nome']")).isEqualTo("Informe o nome do patrocinador.");
    }

    @Test
    void fecharContinuaPermitidoParaQualquerCota() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult masterFechaPesquisa = rbs.patch("/api/parcerias/" + amarelo(),
                "{\"abas\":[{\"codigo\":\"PESQUISA\",\"liberada\":false}]}");
        assertThat(masterFechaPesquisa).hasStatus(HttpStatus.OK);
        assertThat(lerLista(masterFechaPesquisa, "$.abas[?(@.codigo=='PESQUISA')].liberada")).containsExactly(false);

        MvcTestResult masterReabre = rbs.patch("/api/parcerias/" + amarelo(),
                "{\"abas\":[{\"codigo\":\"PESQUISA\",\"liberada\":true}]}");
        assertThat(masterReabre).as("a Master inclui a Pesquisa: pode reabrir").hasStatus(HttpStatus.OK);
        assertThat(lerLista(masterReabre, "$.abas[?(@.codigo=='PESQUISA')].liberada")).containsExactly(true);

        MvcTestResult subcotaFechaCortesias = rbs.patch("/api/parcerias/" + roxo(),
                "{\"abas\":[{\"codigo\":\"CORTESIAS\",\"liberada\":false}]}");
        assertThat(subcotaFechaCortesias).hasStatus(HttpStatus.OK);
    }

    @Test
    void abaForaDaCotaQueJaEstavaLiberadaPodeSerFechadaEReenviarOMesmoEstadoNaoDaErro() {
        // Estado de antes da regra (a RBS liberou a Pesquisa do Roxo quando ainda dava): fechar tem de funcionar, e o PATCH
        // que só repete "liberada" não pode travar a edição do resto da parceria.
        jdbc.sql("""
                        UPDATE parceria_aba SET liberada = TRUE WHERE parceria_id = :p
                        AND aba_id = (SELECT id FROM aba WHERE codigo = 'PESQUISA')
                        """).param("p", roxo()).update();
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult repetir = rbs.patch("/api/parcerias/" + roxo(),
                "{\"nomeProduto\":\"Edição comum\",\"abas\":[{\"codigo\":\"PESQUISA\",\"liberada\":true}]}");
        assertThat(repetir).hasStatus(HttpStatus.OK);
        assertThat(lerJson(repetir, "$.nomeProduto")).isEqualTo("Edição comum");
        assertThat(lerLista(repetir, "$.abas[?(@.codigo=='PESQUISA')].incluidaNaCota")).containsExactly(false);

        MvcTestResult fechar = rbs.patch("/api/parcerias/" + roxo(), "{\"abas\":[{\"codigo\":\"PESQUISA\",\"liberada\":false}]}");
        assertThat(fechar).hasStatus(HttpStatus.OK);
        assertThat(lerLista(fechar, "$.abas[?(@.codigo=='PESQUISA')].liberada")).containsExactly(false);

        MvcTestResult reabrir = rbs.patch("/api/parcerias/" + roxo(), "{\"abas\":[{\"codigo\":\"PESQUISA\",\"liberada\":true}]}");
        assertThat(reabrir).as("depois de fechada, não volta a abrir").hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void ocultarAbaForaDaCotaNaoDependeDaRegraDeLiberar() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);

        MvcTestResult resposta = rbs.patch("/api/parcerias/" + roxo(), "{\"abas\":[{\"codigo\":\"PESQUISA\",\"visivel\":false}]}");

        assertThat(resposta).hasStatus(HttpStatus.OK);
    }
}
