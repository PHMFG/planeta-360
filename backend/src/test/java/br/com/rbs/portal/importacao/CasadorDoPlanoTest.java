package br.com.rbs.portal.importacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.rbs.portal.importacao.ImportacaoRepositorio.PlanilhaDoPlano;
import br.com.rbs.portal.importacao.extracao.CasadorDoPlano;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * O casamento da linha importada com o plano comercial (E11), com o plano da rodada A1: a grafia do Word, o plural, a abreviatura
 * de segundos, o "Indeterminado" no espaço e os números que nunca podem ser diferentes.
 */
class CasadorDoPlanoTest {

    private static final long PARCERIA = 3L;

    private static CasadorDoPlano casador() {
        ImportacaoRepositorio repositorio = mock(ImportacaoRepositorio.class);
        when(repositorio.planoDaParceria(PARCERIA)).thenReturn(List.of(
                new PlanilhaDoPlano(11L, "Rádio 92", "Comercial 30\"", "Indeterminado"),
                new PlanilhaDoPlano(12L, "Rádio 92", "Chamadas assinadas 5\"", "Indeterminado"),
                new PlanilhaDoPlano(13L, "Rádio 92", "Comercial 15\"", "Programação")));
        return new CasadorDoPlano(repositorio);
    }

    private static CasadorDoPlano.Resultado casar(String formato, String espaco) {
        return casador().casar(PARCERIA, "Rádio 92", formato, espaco);
    }

    @Test
    void aChamadaAssinadaDoWordCasaComOPlanoPelaRegraDePalavras() {
        CasadorDoPlano.Resultado resultado = casar("chamada assinada de 5\"", "Programação");

        assertThat(resultado.aproveitamentoId()).isEqualTo(12L);
        assertThat(resultado.semelhanca()).isEqualByComparingTo(new BigDecimal("0.9"));
    }

    @Test
    void oComercialComAGrafiaErradaDoWordCasaComOPlano() {
        CasadorDoPlano.Resultado resultado = casar("comercial de 30\"", "Programação");

        assertThat(resultado.aproveitamentoId()).isEqualTo(11L);
        assertThat(resultado.semelhanca()).isEqualByComparingTo(new BigDecimal("0.9"));
    }

    @Test
    void aChamadaDeCincoSegundosCasaComOPlanoQueDizCincoSegundosComAspas() {
        assertThat(casar("chamada de 5 segundos", null).aproveitamentoId()).isEqualTo(12L);
    }

    @Test
    void oNumeroDiferenteNuncaCasaComALinhaDoPlano() {
        assertThat(casar("comercial de 99\"", "Qualquer").aproveitamentoId()).isNull();
    }

    @Test
    void oEspacoIndeterminadoDoPlanoAceitaQualquerEspaco() {
        assertThat(casar("comercial de 30\"", "Programação").aproveitamentoId()).isEqualTo(11L);
    }
}
