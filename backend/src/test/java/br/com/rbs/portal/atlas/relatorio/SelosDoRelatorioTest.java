package br.com.rbs.portal.atlas.relatorio;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.AvisoIndisponivelDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.ItemKpiDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.KpiGradeDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.SecaoDto;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Valor;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * [ATLAS] [RELATORIO] Os selos do servidor (M7): "acima do previsto" e "abaixo do previsto", em código, comparando o exibido com o
 * previsto. Sem contexto do Spring: a regra de comparação e as frases que o JEV lê. O selo no relatório de verdade (a rota 47, a
 * grade de aplicações do Amarelo) está em {@code SelosNoRelatorioTest}.
 */
class SelosDoRelatorioTest {

    private static Valor valor(String ref, String texto, Integer numero) {
        return new Valor(ref, "Rótulo", texto, numero == null ? null : BigDecimal.valueOf(numero), null, "origem", texto == null ? "sem dado" : null);
    }

    @Test
    void exibidoMenorQuePrevistoEAbaixo() {
        assertThat(SelosDoRelatorio.selo(valor("a", "17", 17), valor("b", "19", 19))).contains(SelosDoRelatorio.ABAIXO);
    }

    @Test
    void exibidoMaiorQuePrevistoEAcima() {
        assertThat(SelosDoRelatorio.selo(valor("a", "20", 20), valor("b", "19", 19))).contains(SelosDoRelatorio.ACIMA);
    }

    @Test
    void igualOuSemNumeroNaoTemSelo() {
        assertThat(SelosDoRelatorio.selo(valor("a", "19", 19), valor("b", "19", 19))).isEmpty();
        assertThat(SelosDoRelatorio.selo(valor("a", null, null), valor("b", "19", 19))).isEmpty();
        assertThat(SelosDoRelatorio.selo(valor("a", "17", 17), valor("b", null, null))).isEmpty();
        assertThat(SelosDoRelatorio.selo(valor("a", "texto", null), valor("b", "19", 19))).isEmpty();
    }

    @Test
    void osTextosDosSelosSaoOsDoPlano() {
        assertThat(SelosDoRelatorio.ACIMA).isEqualTo("acima do previsto");
        assertThat(SelosDoRelatorio.ABAIXO).isEqualTo("abaixo do previsto");
    }

    @Test
    void asFrasesParaOJevTrazemORotuloDoItemEOSeloNaOrdemDoRelatorio() {
        List<SecaoDto> secoes = List.of(
                new SecaoDto("aplicacoes", "Aplicações", List.of(new KpiGradeDto("b-kpi-aplic", List.of(
                        new ItemKpiDto("Previstas", "19", null, "cons:aplicacoes.previstas", null),
                        new ItemKpiDto("Comprovadas", "17", null, "cons:aplicacoes.comprovadas", SelosDoRelatorio.ABAIXO),
                        new ItemKpiDto("Fotos", "17", null, "cons:aplicacoes.fotos", null))))),
                new SecaoDto("outra", "Outra", List.of(new AvisoIndisponivelDto("b-aviso", "sem dado"))));

        assertThat(SelosDoRelatorio.frases(secoes)).containsExactly("Comprovadas: abaixo do previsto");
    }

    @Test
    void semNenhumSeloNaoHaFrase() {
        List<SecaoDto> secoes = List.of(new SecaoDto("aplicacoes", "Aplicações", List.of(new KpiGradeDto("b", List.of(
                new ItemKpiDto("Previstas", "19", null, "cons:aplicacoes.previstas"),
                new ItemKpiDto("Comprovadas", "19", null, "cons:aplicacoes.comprovadas"))))));

        assertThat(SelosDoRelatorio.frases(secoes)).isEmpty();
    }

    @Test
    void oItemSemOParDoPrevistoNaGradeNaoLevaSelo() {
        var modelo = List.of(new Especificacao.ItemKpi("Comprovadas", new Especificacao.Referencia("cons:aplicacoes.comprovadas")));
        var itens = List.of(new ItemKpiDto("Comprovadas", "17", null, "cons:aplicacoes.comprovadas"));

        // sem o item "previstas" na grade o selo nem chega a consultar a resolução (nula aqui): não há par
        assertThat(SelosDoRelatorio.comSelos(modelo, itens, null)).containsExactlyElementsOf(itens);
    }
}
