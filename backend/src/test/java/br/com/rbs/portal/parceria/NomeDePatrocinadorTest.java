package br.com.rbs.portal.parceria;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
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
 * [VALIDACAO] [DUPLICIDADE] O nome do patrocinador é normalizado (sem espaços nas pontas e com os repetidos juntados) ao
 * cadastrar e ao editar, e comparado já normalizado e sem diferença de maiúsculas: "Cliente  Verde" e "  cliente  ROXO "
 * não criam um segundo patrocinador ao lado de "Cliente Verde" e "Cliente Roxo". Atende o item 3 da Entrega 2.
 * Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class NomeDePatrocinadorTest {

    private static final String JA_EXISTE = "Já existe um patrocinador com este nome. Escolha-o na lista \"Patrocinador\".";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    long contarPatrocinadores() {
        return jdbc.sql("SELECT count(*) FROM patrocinador").query(Long.class).single();
    }

    String nomeNoBanco(long patrocinadorId) {
        return jdbc.sql("SELECT nome_canonico FROM patrocinador WHERE id = :id").param("id", patrocinadorId).query(String.class).single();
    }

    MvcTestResult criar(String nomeEmJson) {
        long evento2027 = jdbc.sql("SELECT id FROM evento WHERE edicao = 2027").query(Long.class).single();
        return SessaoDeApi.entrar(mvc, "b2b@rbs.example").post("/api/parcerias",
                "{\"patrocinador\":{\"nome\":\"%s\"},\"eventoId\":%d,\"cotaCodigo\":\"SUBCOTA\"}".formatted(nomeEmJson, evento2027));
    }

    @Test
    void espacoDuploNoMeioNaoCriaOutroPatrocinador() {
        long antes = contarPatrocinadores();

        MvcTestResult resposta = criar("Cliente  Verde");

        assertThat(resposta).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo(JA_EXISTE);
        assertThat(contarPatrocinadores()).isEqualTo(antes);
    }

    @Test
    void espacosNasPontasEMaiusculasNaoCriamOutroPatrocinador() {
        long antes = contarPatrocinadores();

        MvcTestResult resposta = criar("  cliente  ROXO ");

        assertThat(resposta).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo(JA_EXISTE);
        assertThat(contarPatrocinadores()).isEqualTo(antes);
    }

    @Test
    void tabulacaoEEspacoNaoQuebravelTambemContamComoEspaco() {
        assertThat(criar("Cliente\\tVerde")).hasStatus(HttpStatus.CONFLICT);
        assertThat(criar("Cliente\\u00A0Verde")).hasStatus(HttpStatus.CONFLICT);
        assertThat(criar("\\u00A0Cliente \\u2003 Verde\\u00A0")).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void nomeNovoEGuardadoNormalizadoEOMesmoNomeDepoisNaoPassa() {
        MvcTestResult criada = criar("  Marca   Nova \\t Brasil ");

        assertThat(criada).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(criada, "$.patrocinador.nome")).isEqualTo("Marca Nova Brasil");
        long patrocinador = ((Number) lerJson(criada, "$.patrocinador.id")).longValue();
        assertThat(nomeNoBanco(patrocinador)).isEqualTo("Marca Nova Brasil");
        String historico = jdbc.sql("SELECT valor_novo FROM historico WHERE acao = 'CRIACAO' AND entidade = 'patrocinador' AND entidade_id = :id")
                .param("id", patrocinador).query(String.class).single();
        assertThat(historico).isEqualTo("Marca Nova Brasil");

        MvcTestResult repetida = criar(" marca nova  BRASIL");
        assertThat(repetida).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(repetida, "$.detail")).isEqualTo(JA_EXISTE);
    }

    @Test
    void nomeJaGravadoComEspacosDuplosNoBancoTambemEEncontrado() {
        jdbc.sql("INSERT INTO patrocinador (nome_canonico) VALUES ('Cliente   Azul ')").update();   // dado antigo, de antes da regra
        long antes = contarPatrocinadores();

        MvcTestResult resposta = criar("cliente azul");

        assertThat(resposta).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo(JA_EXISTE);
        assertThat(contarPatrocinadores()).isEqualTo(antes);
    }

    @Test
    void edicaoNormalizaONomeEComparaSemEspacosNemMaiusculas() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, "b2b@rbs.example");
        long amarelo = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");

        MvcTestResult repetido = rbs.patch("/api/parcerias/" + amarelo, "{\"patrocinador\":{\"nome\":\"  cliente   ROXO \"}}");
        assertThat(repetido).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(repetido, "$.detail")).isEqualTo("Já existe um patrocinador com este nome.");

        MvcTestResult novo = rbs.patch("/api/parcerias/" + amarelo, "{\"patrocinador\":{\"nome\":\"  Cliente   Amarelo   S.A. \"}}");
        assertThat(novo).hasStatus(HttpStatus.OK);
        assertThat(lerJson(novo, "$.patrocinador.nome")).isEqualTo("Cliente Amarelo S.A.");
        assertThat(jdbc.sql("SELECT count(*) FROM patrocinador WHERE nome_canonico = 'Cliente Amarelo S.A.'").query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void nomeIgualAoAtualSoComEspacosDiferentesNaoMudaNadaNemGravaHistorico() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, "b2b@rbs.example");
        long amarelo = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        long antes = jdbc.sql("SELECT count(*) FROM historico WHERE acao = 'EDICAO' AND parceria_id = :p").param("p", amarelo)
                .query(Long.class).single();

        MvcTestResult resposta = rbs.patch("/api/parcerias/" + amarelo, "{\"patrocinador\":{\"nome\":\" Cliente   Amarelo \"}}");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.patrocinador.nome")).isEqualTo("Cliente Amarelo");
        assertThat(jdbc.sql("SELECT count(*) FROM historico WHERE acao = 'EDICAO' AND parceria_id = :p").param("p", amarelo)
                .query(Long.class).single()).isEqualTo(antes);
    }

    @Test
    void soMudarAsMaiusculasDoProprioNomeEPermitido() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, "b2b@rbs.example");
        long amarelo = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");

        MvcTestResult resposta = rbs.patch("/api/parcerias/" + amarelo, "{\"patrocinador\":{\"nome\":\"CLIENTE AMARELO\"}}");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.patrocinador.nome")).isEqualTo("CLIENTE AMARELO");
    }
}
