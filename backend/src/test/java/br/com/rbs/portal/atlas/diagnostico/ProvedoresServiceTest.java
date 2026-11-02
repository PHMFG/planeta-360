package br.com.rbs.portal.atlas.diagnostico;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.ModoDoAtlas;
import br.com.rbs.portal.atlas.diagnostico.DiagnosticoDtos.ProvedoresDto;
import br.com.rbs.portal.atlas.diagnostico.DiagnosticoDtos.TesteDeProvedorDto;
import br.com.rbs.portal.atlas.diagnostico.TracoRepositorio.EstatisticasDoProvedor;
import br.com.rbs.portal.atlas.disjuntor.DisjuntoresDosProvedores;
import br.com.rbs.portal.atlas.loop.TetoDiario;
import br.com.rbs.portal.atlas.provedor.FabricaDeProvedor;
import br.com.rbs.portal.atlas.provedor.ProvedorAtual;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.Perfil;
import br.com.rbs.portal.comum.SemPermissaoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

/**
 * [ATLAS] O {@link ProvedoresService} sem o contexto do Spring: o que as rotas 52 e 53 fazem quando não há modelo de linguagem
 * nenhum (origem NENHUM), quando o provedor está fora do ar (falha de rede, sem a chave na mensagem), quando a chave tem um caractere
 * que o HTTP não aceita e quando quem chama não é da RBS. Os testes com os servidores falsos estão em ProvedoresTest.
 */
class ProvedoresServiceTest {

    private static final String CHAVE = "chave-de-mentira-so-do-teste-1234";
    private static final UsuarioAutenticado RBS = new UsuarioAutenticado(1L, "Equipe B2B RBS", "b2b@rbs.example", Perfil.ADMIN_RBS);
    private static final UsuarioAutenticado MARCA = new UsuarioAutenticado(2L, "Marketing Amarelo", "m@amarelo.example", Perfil.ADMIN_PATROCINADOR);

    @SuppressWarnings("unchecked")
    ProvedoresService servico(ConfiguracaoDeProvedores config, boolean diagnosticoLigado) {
        ObjectProvider<br.com.rbs.portal.atlas.provedor.ProvedorDeLlm> semModelo = mock(ObjectProvider.class);
        ObjectProvider<ObservationRegistry> semObservacao = mock(ObjectProvider.class);
        TracoRepositorio tracos = mock(TracoRepositorio.class);
        when(tracos.estatisticas(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(new EstatisticasDoProvedor(0L, 0L, null, null));
        AtlasRepositorio atlas = mock(AtlasRepositorio.class);
        when(atlas.mensagensDoModeloHojePorParceria()).thenReturn(List.of());
        TetoDiario teto = mock(TetoDiario.class);
        when(teto.teto()).thenReturn(200);
        DisjuntoresDosProvedores disjuntores = new DisjuntoresDosProvedores(false, 5, 50f, 10, java.time.Duration.ofSeconds(60), java.time.Clock.systemUTC());
        return new ProvedoresService(new ProvedorAtual(semModelo, disjuntores), new FabricaDeProvedor(new MockEnvironment(), semObservacao), config,
                new br.com.rbs.portal.atlas.jev.ConfiguracaoDoJev(new MockEnvironment()), tracos, atlas, teto, new ModoDoAtlas(false, false, diagnosticoLigado, false),
                new VerificadorDeAcesso(null), disjuntores);
    }

    ConfiguracaoDeProvedores configuracao(String chaveDoJev, String urlDoJev, String chaveDoGemini, String urlDoGemini) {
        return new ConfiguracaoDeProvedores(chaveDoJev, urlDoJev, "jev-1.14.0", "none", chaveDoGemini, urlDoGemini, "gemini-embedding-2");
    }

    @Test
    void semModeloNemChaveTudoDizNenhumEOTesteDoLlmNaoChamaNada() {
        ProvedoresService servico = servico(configuracao("", "http://127.0.0.1:1", "", "http://127.0.0.1:1"), true);

        ProvedoresDto lista = servico.listar(RBS);

        assertThat(lista.provedores()).extracting(p -> p.nome() + ":" + p.origem() + ":" + p.configurado())
                .containsExactly("llm:NENHUM:false", "jev:NENHUM:false", "gemini:NENHUM:false");
        assertThat(lista.provedores()).extracting(p -> p.finalDaChave()).containsOnlyNulls();
        TesteDeProvedorDto teste = servico.testar(RBS, "llm");
        assertThat(teste.ok()).isFalse();
        assertThat(teste.chamou()).isFalse();
        assertThat(teste.erro()).isEqualTo("Nenhuma chave configurada.");
        assertThat(teste.milissegundos()).isZero();
    }

    @Test
    void provedorForaDoArDizSoONomeDaFalhaENuncaAChave() {
        ServidorHttpFalso desligado = ServidorHttpFalso.iniciar();
        String url = desligado.urlBase();
        desligado.close();
        ProvedoresService servico = servico(configuracao(CHAVE, url, CHAVE, url), true);

        for (String nome : List.of("jev", "gemini")) {
            TesteDeProvedorDto teste = servico.testar(RBS, nome);

            assertThat(teste.ok()).as(nome).isFalse();
            assertThat(teste.chamou()).isTrue();
            assertThat(teste.erro()).startsWith("Não consegui falar com o provedor (").endsWith("Exception).").doesNotContain(CHAVE);
            assertThat(teste.resposta()).isNull();
        }
    }

    @Test
    void chaveComCaractereQueOHttpNaoAceitaViraErroDoTesteEMaiorNaoDa500() {
        ProvedoresService servico = servico(configuracao("chave\ncom quebra de linha", "http://127.0.0.1:1", "", "http://127.0.0.1:1"), true);

        TesteDeProvedorDto teste = servico.testar(RBS, "jev");

        assertThat(teste.ok()).isFalse();
        assertThat(teste.erro()).startsWith("Não consegui falar com o provedor (IllegalArgumentException)");
    }

    @Test
    void soARbsUsaEComODiagnosticoLigado() {
        ProvedoresService servico = servico(configuracao("", "http://127.0.0.1:1", "", "http://127.0.0.1:1"), true);
        ProvedoresService desligado = servico(configuracao("", "http://127.0.0.1:1", "", "http://127.0.0.1:1"), false);

        assertThatThrownBy(() -> servico.listar(MARCA)).isInstanceOf(SemPermissaoException.class);
        assertThatThrownBy(() -> servico.testar(MARCA, "llm")).isInstanceOf(SemPermissaoException.class);
        assertThatThrownBy(() -> servico.testar(RBS, "outro")).isInstanceOf(NaoEncontradoException.class).hasMessage("Não encontramos este provedor.");
        assertThatThrownBy(() -> desligado.listar(RBS)).isInstanceOf(NaoEncontradoException.class);
        assertThatThrownBy(() -> desligado.testar(RBS, "llm")).isInstanceOf(NaoEncontradoException.class);
    }

    @Test
    void oFinalDaChaveSoApareceDe8CaracteresEmDiante() {
        assertThat(ProvedoresService.finalDa(null)).isNull();
        assertThat(ProvedoresService.finalDa("1234567")).isNull();
        assertThat(ProvedoresService.finalDa("12345678")).isEqualTo("5678");
        assertThat(ProvedoresService.finalDa(CHAVE)).isEqualTo("1234");
    }

    @Test
    void oModoDoJevUsaOVocabularioDaApi() {
        assertThat(new ConfiguracaoDeProvedores("", "", "", "none", "", "", "").jevModo()).isEqualTo("DESLIGADO");
        assertThat(new ConfiguracaoDeProvedores("", "", "", "SOMBRA", "", "", "").jevModo()).isEqualTo("SOMBRA");
        assertThat(new ConfiguracaoDeProvedores("", "", "", " sombra ", "", "", "").jevModo()).isEqualTo("SOMBRA");
        assertThat(new ConfiguracaoDeProvedores("", "", "", "ativo", "", "", "").jevModo()).isEqualTo("ATIVO");
        assertThat(new ConfiguracaoDeProvedores("", "", "", "qualquer coisa", "", "", "").jevModo()).isEqualTo("DESLIGADO");
    }
}
