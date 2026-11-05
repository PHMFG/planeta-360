package br.com.rbs.portal.acesso;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * U10: a rota 4 (e o login, rota 2) diz, em cada parceria, o vínculo de quem está logado (MARCA ou AGENCIA, de parceria_usuario),
 * para o cabeçalho dizer "Agência" quando for o caso. A RBS não tem parceria, então não tem vínculo.
 */
@TesteDeIntegracao
@Transactional
class VinculoNoEuTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void agenciaDoAmareloVeVinculoAgenciaEAMarcaVeMarca() {
        MvcTestResult agencia = SessaoDeApi.entrar(mvc, "atendimento@agencia-x.example").get("/api/auth/eu");
        MvcTestResult marca = SessaoDeApi.entrar(mvc, "marketing@amarelo.example").get("/api/auth/eu");

        assertThat(lerJson(agencia, "$.parcerias[0].patrocinador")).isEqualTo("Cliente Amarelo");
        assertThat(lerJson(agencia, "$.parcerias[0].vinculo")).isEqualTo("AGENCIA");
        assertThat(lerJson(marca, "$.parcerias[0].vinculo")).isEqualTo("MARCA");
    }

    @Test
    void oLoginTambemTrazOVinculoEARbsNaoTemParcerias() {
        MvcTestResult login = SessaoDeApi.anonima(mvc).post("/api/auth/login",
                "{\"email\":\"atendimento@agencia-x.example\",\"senha\":\"" + SessaoDeApi.SENHA + "\"}");
        assertThat(lerJson(login, "$.parcerias[0].vinculo")).isEqualTo("AGENCIA");

        MvcTestResult rbs = SessaoDeApi.entrar(mvc, "b2b@rbs.example").get("/api/auth/eu");
        assertThat(lerLista(rbs, "$.parcerias")).isEmpty();
    }
}
