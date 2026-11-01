package br.com.rbs.portal.atlas.relatorio;

import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.BlocoDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.ItemKpiDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.KpiGradeDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.SecaoDto;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Resolucao;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Valor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Os selos do servidor num relatório do Atlas: "acima do previsto" e "abaixo do previsto" (plano do Atlas completo, M7). [ATLAS]
 * [RELATORIO] São calculados <b>em código</b>, comparando o número exibido com o previsto dos blocos que têm os dois (hoje a grade de
 * aplicações do modelo-base: comprovadas contra previstas), e saem em cada item {@code kpi_grade} como {@code selo}, para o front
 * desenhar. Também são o que o ponto {@code coerencia} do JEV lê para conferir se o parágrafo escrito pelo modelo diz o contrário
 * deles. Só há selo quando os dois números têm valor e são diferentes: igual, ou faltando um, não leva selo; sem nenhum selo no
 * relatório, o ponto {@code coerencia} não roda. O selo nunca é texto do modelo: o relatório pronto guarda o do momento em que
 * ficou pronto, junto do snapshot.
 */
public final class SelosDoRelatorio {

    public static final String ACIMA = "acima do previsto";
    public static final String ABAIXO = "abaixo do previsto";

    /** O número exibido e o previsto contra o qual ele se compara (referência do exibido &rarr; referência do previsto). */
    private static final Map<String, String> PREVISTO_DO_EXIBIDO = Map.of("cons:aplicacoes.comprovadas", "cons:aplicacoes.previstas");

    private SelosDoRelatorio() {
    }

    /**
     * Os itens de uma grade, com o selo nos que têm: cada item cuja referência é de um número exibido, se a grade tem o item do
     * previsto dele e os dois têm valor numérico.
     */
    public static List<ItemKpiDto> comSelos(List<Especificacao.ItemKpi> modelo, List<ItemKpiDto> itens, Resolucao resolucao) {
        List<ItemKpiDto> comSelo = new ArrayList<>();
        for (int i = 0; i < itens.size(); i++) {
            ItemKpiDto item = itens.get(i);
            String refDoPrevisto = item.ref() == null ? null : PREVISTO_DO_EXIBIDO.get(item.ref());
            boolean gradeTemOPrevisto = refDoPrevisto != null && modelo.stream().anyMatch(m -> refDoPrevisto.equals(m.valor().ref()));
            comSelo.add(gradeTemOPrevisto ? item.comSelo(selo(resolucao.valor(item.ref()), resolucao.valor(refDoPrevisto)).orElse(null)) : item);
        }
        return comSelo;
    }

    /** O selo do exibido contra o previsto: nenhum se faltar um dos dois números ou se forem iguais. */
    static Optional<String> selo(Valor exibido, Valor previsto) {
        BigDecimal a = exibido.numero();
        BigDecimal b = previsto.numero();
        if (a == null || b == null || !exibido.disponivel() || !previsto.disponivel()) {
            return Optional.empty();
        }
        int comparacao = a.compareTo(b);
        return comparacao == 0 ? Optional.empty() : Optional.of(comparacao > 0 ? ACIMA : ABAIXO);
    }

    /**
     * Os selos de um relatório já montado, em frases para o JEV: o rótulo do item exibido e o selo ("Comprovadas: abaixo do
     * previsto"), na ordem do relatório. Vazio quando nenhum item tem selo.
     */
    public static List<String> frases(List<SecaoDto> secoes) {
        List<String> frases = new ArrayList<>();
        for (SecaoDto secao : secoes) {
            for (BlocoDto bloco : secao.blocos()) {
                if (bloco instanceof KpiGradeDto grade) {
                    grade.itens().stream().filter(item -> item.selo() != null).forEach(item -> frases.add(item.rotulo() + ": " + item.selo()));
                }
            }
        }
        return frases;
    }
}
