package br.com.rbs.portal.materiais;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bordas achadas pelos auditores, uma por teste: caractere de controle onde ia ao banco (500), número que estoura a conta de página,
 * permissão que precisa vir antes do corpo (rotas 71, 83, 84 e 86), 403 antes do 415 na rota 76 e endereço com espaço invisível.
 * Cada teste é desfeito no fim.
 */
@TesteDeIntegracao
@Transactional
class BordasDaAuditoriaTest extends ApoioDeEspacos {

    private long entregaDe(long parceria, String aba) {
        return jdbc.sql("SELECT e.id FROM entrega e JOIN aba a ON a.id = e.aba_id WHERE e.parceria_id = :p AND a.codigo = :a LIMIT 1")
                .param("p", parceria).param("a", aba).query(Long.class).single();
    }

    @Test
    void caractereNulNaObservacaoDoEspacoDa422() {
        long verde = parceriaDo("Cliente Verde");
        SessaoDeApi marca = entrar(VERDE);

        MvcTestResult resposta = enviarPara(marca, "/api/parcerias/" + verde + "/espacos/LOGO/arquivos", "logo.png", png(),
                "observacao", "a\u0000b");

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(resposta, "$.campos.observacao")).isEqualTo("A observação tem caracteres inválidos.");
    }

    @Test
    void codigoDeCotaComNulDa422NaPreviaENaTroca() {
        long amarelo = parceriaDo("Cliente Amarelo");
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult troca = rbs.put("/api/parcerias/" + amarelo + "/cota", "{\"cotaCodigo\":\"MAS\\u0000TER\",\"ciente\":true}");
        MvcTestResult previa = rbs.get("/api/parcerias/" + amarelo + "/cota/previa?para=MAS%00TER");

        assertThat(troca).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(troca, "$.campos.cotaCodigo")).isNotNull();
        assertThat(previa).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void paginaGiganteNaFilaDePerguntasNaoEstouraAConta() {
        MvcTestResult resposta = entrar(RBS).get("/api/pesquisa/perguntas?pagina=2147483647&tamanho=50");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.itens")).isEqualTo(java.util.List.of());
    }

    @Test
    void corpoQuebradoDeQuemNaoTemAcessoRecebe404Ou403NasRotas83E84E86() {
        long amarelo = parceriaDo("Cliente Amarelo");
        long entregaDoAmarelo = entregaDe(amarelo, "MIDIA");
        SessaoDeApi verde = entrar(VERDE);   // marca de outra parceria
        String destinacoes = "/api/parcerias/" + amarelo + "/cortesias/destinacoes";

        assertThat(verde.post(destinacoes, "{quebrado")).as("83 de outra marca").hasStatus(HttpStatus.NOT_FOUND);
        assertThat(verde.post(destinacoes, "{\"quantidade\":1.5}")).as("83, decimal").hasStatus(HttpStatus.NOT_FOUND);
        assertThat(verde.patch(destinacoes + "/1", "{quebrado")).as("84 de outra marca").hasStatus(HttpStatus.NOT_FOUND);
        assertThat(verde.post("/api/entregas/" + entregaDoAmarelo + "/conteudos", "{quebrado")).as("86 de outra marca")
                .hasStatus(HttpStatus.NOT_FOUND);

        fecharAba(amarelo, "CORTESIAS", false, true);
        fecharAba(amarelo, "MIDIA", false, true);
        SessaoDeApi marca = entrar(AMARELO);
        assertThat(marca.post(destinacoes, "{quebrado")).as("83, aba fechada").hasStatus(HttpStatus.FORBIDDEN);
        assertThat(marca.patch(destinacoes + "/1", "{\"quantidade\":\"abc\"}")).as("84, aba fechada").hasStatus(HttpStatus.FORBIDDEN);
        assertThat(marca.post("/api/entregas/" + entregaDoAmarelo + "/conteudos", "{quebrado")).as("86, aba fechada")
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void abaInvalidaNosEspacosNaoVemAntesDoQuatrocentosEQuatroOuDoTresZeroTres() {
        long amarelo = parceriaDo("Cliente Amarelo");

        assertThat(entrar(VERDE).get("/api/parcerias/" + amarelo + "/espacos?aba=lixo")).hasStatus(HttpStatus.NOT_FOUND);

        fecharAba(amarelo, "PERFIL", false, true);
        fecharAba(amarelo, "APLICACOES", false, true);
        assertThat(entrar(AMARELO).get("/api/parcerias/" + amarelo + "/espacos?aba=PERFIL")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(entrar(AMARELO).get("/api/parcerias/" + amarelo + "/espacos?aba=lixo")).as("filtro inválido: só o 404 vem antes")
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(entrar(RBS).get("/api/parcerias/" + amarelo + "/espacos?aba=lixo")).as("a RBS vê o erro do filtro")
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void contratoComAbaFechadaDa403AntesDoQuatrocentosEQuinze() {
        long amarelo = parceriaDo("Cliente Amarelo");
        fecharAba(amarelo, "CONTRATO", false, true);

        MvcTestResult json = entrar(AMARELO).post("/api/parcerias/" + amarelo + "/contrato/arquivo", "{}");

        assertThat(json).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void enderecoComEspacoInvisivelDa422NoPosVendaENoCredenciamento() {
        long amarelo = parceriaDo("Cliente Amarelo");
        SessaoDeApi rbs = entrar(RBS);
        for (String invisivel : new String[] {"\\u00a0", "\\u2028", "\\u200b", "\\u0085"}) {
            MvcTestResult posVenda = rbs.post("/api/parcerias/" + amarelo + "/posvenda",
                    "{\"links\":[{\"tipo\":\"OUTRO\",\"titulo\":\"x\",\"url\":\"https://exemplo.example/a" + invisivel + "b\"}]}");
            assertThat(posVenda).as("pós-venda " + invisivel).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(lerJson(posVenda, "$.campos['links[0].url']")).isNotNull();

            long evento = jdbc.sql("SELECT evento_id FROM parceria WHERE id = :p").param("p", amarelo).query(Long.class).single();
            MvcTestResult credenciamento = rbs.patch("/api/eventos/" + evento + "/credenciamento",
                    "{\"openStaffUrl\":\"https://exemplo.example/a" + invisivel + "b\"}");
            assertThat(credenciamento).as("credenciamento " + invisivel).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(lerJson(credenciamento, "$.campos.openStaffUrl")).isNotNull();
        }
    }
}
