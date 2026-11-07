package br.com.rbs.portal.atlas.jev;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * [ATLAS] O modo e o limiar de cada ponto do JEV (M7): o padrão de {@code atlas.jev.modo}, o override de
 * {@code atlas.jev.<ponto>.modo}, o conversor único das duas grafias e o limiar. Sem contexto do Spring: só a configuração.
 */
class ConfiguracaoDoJevTest {

    @Test
    void oConversorUnicoAceitaAsDuasGrafiasEQualquerCaixa() {
        assertThat(ModoDoJev.de("none")).isEqualTo(ModoDoJev.DESLIGADO);
        assertThat(ModoDoJev.de("DESLIGADO")).isEqualTo(ModoDoJev.DESLIGADO);
        assertThat(ModoDoJev.de("sombra")).isEqualTo(ModoDoJev.SOMBRA);
        assertThat(ModoDoJev.de("SOMBRA")).isEqualTo(ModoDoJev.SOMBRA);
        assertThat(ModoDoJev.de(" Sombra ")).isEqualTo(ModoDoJev.SOMBRA);
        assertThat(ModoDoJev.de("ativo")).isEqualTo(ModoDoJev.ATIVO);
        assertThat(ModoDoJev.de("ATIVO")).isEqualTo(ModoDoJev.ATIVO);
    }

    @Test
    void valorQueNaoEConhecidoNuncaLigaOJev() {
        assertThat(ModoDoJev.de(null)).isEqualTo(ModoDoJev.DESLIGADO);
        assertThat(ModoDoJev.de("")).isEqualTo(ModoDoJev.DESLIGADO);
        assertThat(ModoDoJev.de("ativado")).isEqualTo(ModoDoJev.DESLIGADO);
        assertThat(ModoDoJev.de("true")).isEqualTo(ModoDoJev.DESLIGADO);
        assertThat(ModoDoJev.conhecido("none")).isTrue();
        assertThat(ModoDoJev.conhecido("ATIVO")).isTrue();
        assertThat(ModoDoJev.conhecido("ativado")).isFalse();
    }

    @Test
    void osModosDizemSeOJevEConsultadoEQuandoAge() {
        assertThat(ModoDoJev.DESLIGADO.consulta()).isFalse();
        assertThat(ModoDoJev.SOMBRA.consulta()).isTrue();
        assertThat(ModoDoJev.SOMBRA.age()).isFalse();
        assertThat(ModoDoJev.ATIVO.age()).isTrue();
    }

    @Test
    void semNadaConfiguradoTodosOsPontosFicamDesligados() {
        ConfiguracaoDoJev config = new ConfiguracaoDoJev(new MockEnvironment());

        for (PontoDoJev ponto : PontoDoJev.values()) {
            assertThat(config.modo(ponto)).as(ponto.nome()).isEqualTo(ModoDoJev.DESLIGADO);
            assertThat(config.limiar(ponto)).as(ponto.nome()).isEqualTo(ponto.limiarPadrao());
        }
        assertThat(config.algumLigado()).isFalse();
    }

    @Test
    void oModoPadraoVaiParaTodosOsPontos() {
        ConfiguracaoDoJev config = new ConfiguracaoDoJev(new MockEnvironment().withProperty("atlas.jev.modo", "sombra"));

        for (PontoDoJev ponto : PontoDoJev.values()) {
            assertThat(config.modo(ponto)).as(ponto.nome()).isEqualTo(ModoDoJev.SOMBRA);
        }
        assertThat(config.algumLigado()).isTrue();
    }

    @Test
    void cadaPontoPodeTrocarOModoEOLimiar() {
        ConfiguracaoDoJev config = new ConfiguracaoDoJev(new MockEnvironment()
                .withProperty("atlas.jev.modo", "SOMBRA")
                .withProperty("atlas.jev.intencao.modo", "ATIVO")
                .withProperty("atlas.jev.intencao.limiar", "0.95")
                .withProperty("atlas.jev.fontes.modo", "none")
                .withProperty("atlas.jev.lacunas.limiar", "0.4"));

        assertThat(config.modo(PontoDoJev.INTENCAO)).isEqualTo(ModoDoJev.ATIVO);
        assertThat(config.limiar(PontoDoJev.INTENCAO)).isEqualTo(0.95);
        assertThat(config.modo(PontoDoJev.FONTES)).isEqualTo(ModoDoJev.DESLIGADO);
        assertThat(config.modo(PontoDoJev.CARTAO)).isEqualTo(ModoDoJev.SOMBRA);
        assertThat(config.modo(PontoDoJev.LACUNAS)).isEqualTo(ModoDoJev.SOMBRA);
        assertThat(config.limiar(PontoDoJev.LACUNAS)).isEqualTo(0.4);
        assertThat(config.limiar(PontoDoJev.RESPOSTA)).isEqualTo(PontoDoJev.RESPOSTA.limiarPadrao());
    }

    @Test
    void limiarForaDeZeroAUmOuQueNaoENumeroValeOPadrao() {
        ConfiguracaoDoJev config = new ConfiguracaoDoJev(new MockEnvironment()
                .withProperty("atlas.jev.intencao.limiar", "1.5")
                .withProperty("atlas.jev.cartao.limiar", "alto")
                .withProperty("atlas.jev.fontes.limiar", "-0.1"));

        assertThat(config.limiar(PontoDoJev.INTENCAO)).isEqualTo(PontoDoJev.INTENCAO.limiarPadrao());
        assertThat(config.limiar(PontoDoJev.CARTAO)).isEqualTo(PontoDoJev.CARTAO.limiarPadrao());
        assertThat(config.limiar(PontoDoJev.FONTES)).isEqualTo(PontoDoJev.FONTES.limiarPadrao());
    }

    @Test
    void osPontosTemOsNomesDaConfiguracaoEDoArquivoDePerguntas() {
        assertThat(java.util.Arrays.stream(PontoDoJev.values()).map(PontoDoJev::nome))
                .containsExactly("intencao", "cartao", "fontes", "resposta", "coerencia", "lacunas", "arquivo_destino");
    }
}
