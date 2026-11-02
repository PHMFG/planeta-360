package br.com.rbs.portal.atlas.relatorio;

import br.com.rbs.portal.atlas.relatorio.Especificacao.Bloco;
import br.com.rbs.portal.atlas.relatorio.Especificacao.Referencia;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.AvisoIndisponivelDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.BlocoDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.CampoInformadoDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.CapaDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.FonteDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.FontesDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.FotoDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.GaleriaComprovacoesDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.ItemKpiDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.KpiDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.KpiGradeDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.ParagrafoDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.SecaoDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.SnapshotDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.TabelaDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.TituloDto;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Lista;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Resolucao;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.TextoResolvido;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Valor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Monta o relatório do Atlas: pega a especificação (blocos com referências e o texto de cada parágrafo) e uma resolução, e devolve
 * os blocos já com os valores, no formato da rota 47 e do snapshot. [ATLAS] [RELATORIO] As regras que valem em todo bloco:
 * <ul>
 *   <li>Nunca zero no lugar de "sem dado": o número sem valor sai com {@code valor} nulo e o motivo em {@code indisponivel}.</li>
 *   <li>O item exibido de um par previsto &times; exibido da grade leva o {@code selo} do servidor ({@link SelosDoRelatorio}).</li>
 *   <li>Parágrafo cuja frase tem referência sem valor sai como {@code aviso_indisponivel}: frase com buraco não existe.</li>
 *   <li>Parágrafo redigível ainda sem texto mostra a frase-modelo do modelo-base (redigido por MODELO). No relatório pronto o texto
 *       sempre existe: a finalização o exige ou o plano B o preenche antes.</li>
 *   <li>"Fontes e cálculos" lista cada número usado no relatório (também as variáveis das contas), na ordem em que aparecem, com a
 *       origem de cada um: dado informado (autor e data), consolidação do portal ou a conta com os valores que entraram.</li>
 * </ul>
 */
@Component
public class MontadorDoRelatorio {

    private static final String SEM_FOTOS = "Ainda não há fotos de comprovação enviadas.";

    /** Monta as seções e as contas. Quem grava o snapshot guarda exatamente o que sai daqui. */
    public SnapshotDto montar(Especificacao especificacao, Resolucao resolucao) {
        Set<String> usadas = new LinkedHashSet<>();
        List<SecaoDto> secoes = new ArrayList<>();
        int[] posicaoDasFontes = {-1, -1};   // seção e bloco da seção "Fontes e cálculos", preenchida por último
        for (int s = 0; s < especificacao.secoes().size(); s++) {
            var secao = especificacao.secoes().get(s);
            List<BlocoDto> blocos = new ArrayList<>();
            for (int b = 0; b < secao.blocos().size(); b++) {
                Bloco bloco = secao.blocos().get(b);
                if (bloco instanceof Especificacao.Fontes fontes) {
                    posicaoDasFontes = new int[]{s, b};
                    blocos.add(new FontesDto(fontes.id(), List.of()));
                } else {
                    blocos.add(montarBloco(especificacao, bloco, resolucao, usadas));
                }
            }
            secoes.add(new SecaoDto(secao.id(), secao.titulo(), blocos));
        }
        if (posicaoDasFontes[0] >= 0) {
            SecaoDto secao = secoes.get(posicaoDasFontes[0]);
            List<BlocoDto> blocos = new ArrayList<>(secao.blocos());
            BlocoDto fontes = blocos.get(posicaoDasFontes[1]);
            blocos.set(posicaoDasFontes[1], new FontesDto(fontes.id(), fontes(usadas, resolucao)));
            secoes.set(posicaoDasFontes[0], new SecaoDto(secao.id(), secao.titulo(), blocos));
        }
        return new SnapshotDto(especificacao.titulo(), secoes, resolucao.calculos());
    }

    private BlocoDto montarBloco(Especificacao especificacao, Bloco bloco, Resolucao resolucao, Set<String> usadas) {
        return switch (bloco) {
            case Especificacao.Capa c -> new CapaDto(c.id(), c.titulo(), texto(c.subtitulo(), resolucao), texto(c.edicao(), resolucao));
            case Especificacao.Titulo t -> new TituloDto(t.id(), t.texto());
            case Especificacao.Paragrafo p -> paragrafo(p, resolucao, usadas);
            case Especificacao.Kpi k -> new KpiDto(k.id(), item(k.rotulo(), k.valor().ref(), resolucao, usadas));
            case Especificacao.KpiGrade g -> new KpiGradeDto(g.id(), SelosDoRelatorio.comSelos(g.itens(), g.itens().stream()
                    .map(i -> item(i.rotulo(), i.valor().ref(), resolucao, usadas)).toList(), resolucao));
            case Especificacao.Tabela t -> tabela(t, resolucao, usadas);
            case Especificacao.GaleriaComprovacoes g -> galeria(g, resolucao, usadas);
            case Especificacao.CampoInformado c -> campo(c, resolucao, usadas);
            case Especificacao.AvisoIndisponivel a -> new AvisoIndisponivelDto(a.id(), a.motivo());
            case Especificacao.Fontes f -> new FontesDto(f.id(), List.of());
        };
    }

    private static String texto(Referencia referencia, Resolucao resolucao) {
        return referencia == null ? null : resolucao.valor(referencia.ref()).texto();
    }

    private ItemKpiDto item(String rotulo, String ref, Resolucao resolucao, Set<String> usadas) {
        usar(ref, resolucao, usadas);
        Valor valor = resolucao.valor(ref);
        return valor.disponivel() ? new ItemKpiDto(rotulo, valor.texto(), null, ref) : new ItemKpiDto(rotulo, null, valor.motivo(), ref);
    }

    private BlocoDto paragrafo(Especificacao.Paragrafo p, Resolucao resolucao, Set<String> usadas) {
        String fonte = p.texto() != null ? p.texto() : p.fraseModelo();
        String por = p.texto() != null && "ATLAS".equals(p.redigidoPor()) ? "ATLAS" : "MODELO";
        Referencias.marcadores(fonte).forEach(ref -> usar(ref, resolucao, usadas));
        TextoResolvido resolvido = resolucao.texto(fonte);
        if (resolvido.texto() == null) {
            List<String> rotulos = resolvido.indisponiveis().stream().map(Valor::rotulo).toList();
            return new AvisoIndisponivelDto(p.id(), "Sem os dados para escrever este texto: " + ResolvedorDeReferencias.juntar(rotulos) + ".");
        }
        return new ParagrafoDto(p.id(), resolvido.texto(), por);
    }

    private BlocoDto tabela(Especificacao.Tabela t, Resolucao resolucao, Set<String> usadas) {
        usadas.add(t.fonte().ref());
        Lista lista = resolucao.tabela(t.fonte().ref());
        return lista.linhas().isEmpty()
                ? new AvisoIndisponivelDto(t.id(), ResolvedorDeReferencias.SEM_APLICACOES)
                : new TabelaDto(t.id(), lista.colunas(), lista.linhas(), t.fonte().ref());
    }

    private BlocoDto galeria(Especificacao.GaleriaComprovacoes g, Resolucao resolucao, Set<String> usadas) {
        usadas.add(g.fonte().ref());
        List<FotoDto> fotos = resolucao.fotos(g.fonte().ref());
        return fotos.isEmpty() ? new AvisoIndisponivelDto(g.id(), SEM_FOTOS) : new GaleriaComprovacoesDto(g.id(), fotos);
    }

    private BlocoDto campo(Especificacao.CampoInformado c, Resolucao resolucao, Set<String> usadas) {
        usar(c.valor().ref(), resolucao, usadas);
        Valor valor = resolucao.valor(c.valor().ref());
        return valor.disponivel() ? new CampoInformadoDto(c.id(), c.rotulo(), valor.texto(), valor.origem())
                : new AvisoIndisponivelDto(c.id(), valor.motivo());
    }

    /** Anota a referência (e, numa conta, as variáveis dela) como usada, para "Fontes e cálculos" listá-la. */
    private void usar(String ref, Resolucao resolucao, Set<String> usadas) {
        if ("parc".equals(Referencias.prefixo(ref)) || "evento".equals(Referencias.prefixo(ref))) {
            return;
        }
        usadas.add(ref);
        if ("calc".equals(Referencias.prefixo(ref))) {
            resolucao.calculos().stream().filter(c -> c.nome().equals(Referencias.nome(ref)))
                    .forEach(c -> c.variaveis().forEach(v -> usarDaConta(v.ref(), resolucao, usadas)));
        }
    }

    /**
     * Uma variável de conta. Na conta do chat a origem pode não ser uma referência ({@code usuario}, {@code T2}, {@code R1}) ou ser
     * o {@code calc:} de uma conta que não está neste relatório: essas ficam de fora da lista de fontes, que só tem o que resolve.
     */
    private void usarDaConta(String ref, Resolucao resolucao, Set<String> usadas) {
        if (!Referencias.valida(ref) || "parc".equals(Referencias.prefixo(ref)) || "evento".equals(Referencias.prefixo(ref))) {
            return;
        }
        try {
            resolucao.valor(ref);
        } catch (ResolvedorDeReferencias.ReferenciaDesconhecidaException semValor) {
            return;
        }
        usadas.add(ref);
    }

    private List<FonteDto> fontes(Set<String> usadas, Resolucao resolucao) {
        List<FonteDto> fontes = new ArrayList<>();
        for (String ref : usadas) {
            if ("lista:aplicacoes".equals(ref)) {
                fontes.add(new FonteDto(ref, "Plano de aplicações", null, ResolvedorDeReferencias.ORIGEM_LISTA));
            } else if ("lista:comprovacoes".equals(ref)) {
                fontes.add(new FonteDto(ref, "Fotos de comprovação", null, ResolvedorDeReferencias.ORIGEM_LISTA));
            } else {
                Valor valor = resolucao.valor(ref);
                String origem = valor.disponivel() ? valor.origem()
                        : (valor.origem() == null || valor.origem().isBlank() ? "" : valor.origem() + ": ") + valor.motivo();
                fontes.add(new FonteDto(ref, valor.rotulo(), valor.texto(), origem));
            }
        }
        return fontes;
    }
}
