package br.com.rbs.portal.atlas.relatorio;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.CADASTROS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.DISPONIVEIS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.DISTRIBUIDOS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.ROXO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.VISITANTES;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.valores;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.AvisoIndisponivelDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.BlocoDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.CapaDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.FonteDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.FontesDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.GaleriaComprovacoesDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.ItemKpiDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.KpiGradeDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.ParagrafoDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.SnapshotDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.TabelaDto;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.dados.DadoInformadoService;
import br.com.rbs.portal.dados.OrigemDoDado;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] [RELATORIO] O relatório montado a partir do modelo "Ativação no evento": com os dados informados, sem eles e numa
 * parceria sem aplicação no plano. As regras: nunca zero no lugar de "sem dado"; parágrafo com referência sem valor sai como
 * aviso_indisponivel; "Fontes e cálculos" lista cada número com a origem. Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class MontadorDoRelatorioTest {

    @Autowired
    MontadorDoRelatorio montador;

    @Autowired
    ResolvedorDeReferencias resolvedor;

    @Autowired
    ModeloBase modelos;

    @Autowired
    DadoInformadoService dados;

    @Autowired
    JdbcClient jdbc;

    Especificacao modelo() {
        return modelos.obter(ModeloBase.ATIVACAO_EVENTO).orElseThrow();
    }

    SnapshotDto montar(String email, long parceria, Especificacao especificacao) {
        UsuarioAutenticado usuario = usuario(jdbc, email);
        return montador.montar(especificacao, resolvedor.abrir(usuario, parceria, especificacao));
    }

    SnapshotDto doAmarelo() {
        return montar(AMARELO, SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"), modelo());
    }

    void informar(Object... paresChaveValor) {
        dados.gravar(usuario(jdbc, AMARELO), SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"), valores(paresChaveValor),
                OrigemDoDado.ATLAS, requisicao());
    }

    @SuppressWarnings("unchecked")
    static <T extends BlocoDto> T bloco(SnapshotDto snapshot, String id, Class<T> tipo) {
        return snapshot.secoes().stream().flatMap(s -> s.blocos().stream()).filter(b -> b.id().equals(id)).findFirst()
                .map(b -> {
                    assertThat(b).as("o bloco " + id).isInstanceOf(tipo);
                    return (T) b;
                }).orElseThrow();
    }

    static ItemKpiDto item(KpiGradeDto grade, String rotulo) {
        return grade.itens().stream().filter(i -> i.rotulo().equals(rotulo)).findFirst().orElseThrow();
    }

    @Test
    void comOsDadosInformadosMontaOsBlocosComOsValores() {
        informar(VISITANTES, 1200L, CADASTROS, 1072L, DISPONIVEIS, 300L, DISTRIBUIDOS, 150L);

        SnapshotDto snapshot = doAmarelo();

        assertThat(snapshot.titulo()).isEqualTo("Ativação no evento");
        assertThat(snapshot.secoes()).extracting("id").containsExactly("capa", "resumo", "aplicacoes", "publico", "comprovacoes", "fontes");
        CapaDto capa = bloco(snapshot, "b-capa", CapaDto.class);
        assertThat(capa.titulo()).isEqualTo("Ativação no evento");
        assertThat(capa.subtitulo()).isEqualTo("Cliente Amarelo");
        assertThat(capa.edicao()).isEqualTo("Planeta Atlântida 2027");

        ParagrafoDto resumo = bloco(snapshot, "b-resumo", ParagrafoDto.class);
        assertThat(resumo.texto()).isEqualTo("A marca Cliente Amarelo teve 17 de 19 aplicações de marca comprovadas no evento.");
        assertThat(resumo.redigidoPor()).isEqualTo("MODELO");

        KpiGradeDto aplicacoes = bloco(snapshot, "b-kpi-aplic", KpiGradeDto.class);
        assertThat(aplicacoes.itens()).extracting(ItemKpiDto::rotulo, ItemKpiDto::valor, ItemKpiDto::indisponivel, ItemKpiDto::ref)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Previstas", "19", null, "cons:aplicacoes.previstas"),
                        org.assertj.core.groups.Tuple.tuple("Comprovadas", "17", null, "cons:aplicacoes.comprovadas"),
                        org.assertj.core.groups.Tuple.tuple("Comprovação", "89,4%", null, "cons:aplicacoes.percentual"),
                        org.assertj.core.groups.Tuple.tuple("Fotos", "17", null, "cons:aplicacoes.fotos"));
        TabelaDto tabela = bloco(snapshot, "b-tab-aplic", TabelaDto.class);
        assertThat(tabela.colunas()).containsExactly("Aplicação", "Previsto", "Comprovadas", "Fotos");
        assertThat(tabela.linhas()).hasSize(9);
        assertThat(tabela.ref()).isEqualTo("lista:aplicacoes");

        KpiGradeDto publico = bloco(snapshot, "b-kpi-publico", KpiGradeDto.class);
        assertThat(item(publico, "Visitantes").valor()).isEqualTo("1.200");
        assertThat(item(publico, "Cadastros").valor()).isEqualTo("1.072");
        assertThat(item(publico, "Taxa de cadastro").valor()).isEqualTo("89,3%");
        assertThat(item(publico, "Brindes distribuídos").valor()).isEqualTo("50%");
        assertThat(bloco(snapshot, "b-publico", ParagrafoDto.class).texto())
                .isEqualTo("O espaço recebeu 1.200 visitantes, e 89,3% deles deixaram cadastro com consentimento.");
        assertThat(bloco(snapshot, "b-galeria", GaleriaComprovacoesDto.class).itens()).hasSize(17);
    }

    @Test
    void semOsDadosOKpiSaiSemValorEOParagrafoSaiIndisponivelNuncaZero() {
        SnapshotDto snapshot = doAmarelo();

        KpiGradeDto publico = bloco(snapshot, "b-kpi-publico", KpiGradeDto.class);
        for (ItemKpiDto item : publico.itens()) {
            assertThat(item.valor()).as(item.rotulo()).isNull();
            assertThat(item.indisponivel()).as(item.rotulo()).isNotBlank();
        }
        assertThat(item(publico, "Visitantes").indisponivel()).isEqualTo("Não informado pela marca.");
        assertThat(item(publico, "Taxa de cadastro").indisponivel()).startsWith("Faltam dados para o cálculo");
        AvisoIndisponivelDto aviso = bloco(snapshot, "b-publico", AvisoIndisponivelDto.class);
        assertThat(aviso.motivo()).contains("Visitantes no espaço").contains("Taxa de cadastro");
        assertThat(AtlasJson.escrever(snapshot)).doesNotContain("\"valor\":\"0\"").doesNotContain("\"valor\":0");
    }

    @Test
    void dadoInformadoComOsDoisBrindesEmBrancoDeixaSoOsBrindesIndisponiveis() {
        informar(VISITANTES, 1200L, CADASTROS, 1072L);

        KpiGradeDto publico = bloco(doAmarelo(), "b-kpi-publico", KpiGradeDto.class);

        assertThat(item(publico, "Taxa de cadastro").valor()).isEqualTo("89,3%");
        ItemKpiDto brindes = item(publico, "Brindes distribuídos");
        assertThat(brindes.valor()).isNull();
        assertThat(brindes.indisponivel()).isEqualTo("Faltam dados para o cálculo: Brindes distribuídos e Brindes disponíveis.");
    }

    @Test
    void textoEscritoPeloAtlasSaiComoRedigidoPorAtlas() {
        informar(VISITANTES, 1200L);
        Especificacao escrita = modelo().comTexto("b-resumo", "{{parc:patrocinador}} levou a marca ao evento.", "ATLAS");

        ParagrafoDto resumo = bloco(montar(AMARELO, SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"), escrita), "b-resumo",
                ParagrafoDto.class);

        assertThat(resumo.texto()).isEqualTo("Cliente Amarelo levou a marca ao evento.");
        assertThat(resumo.redigidoPor()).isEqualTo("ATLAS");
    }

    @Test
    void parceriaSemAplicacaoNoPlanoNaoMostraTabelaNemGaleriaNemNumeroDeAplicacao() {
        SnapshotDto snapshot = montar(ROXO, SessaoDeApi.parceriaDe2027(jdbc, "Cliente Roxo"), modelo());

        assertThat(bloco(snapshot, "b-tab-aplic", AvisoIndisponivelDto.class).motivo()).isEqualTo(ResolvedorDeReferencias.SEM_APLICACOES);
        assertThat(bloco(snapshot, "b-galeria", AvisoIndisponivelDto.class)).isNotNull();
        assertThat(bloco(snapshot, "b-resumo", AvisoIndisponivelDto.class).motivo()).contains("Aplicações comprovadas");
        KpiGradeDto aplicacoes = bloco(snapshot, "b-kpi-aplic", KpiGradeDto.class);
        assertThat(aplicacoes.itens()).allSatisfy(i -> {
            assertThat(i.valor()).isNull();
            assertThat(i.indisponivel()).isEqualTo(ResolvedorDeReferencias.SEM_APLICACOES);
        });
    }

    @Test
    void fontesListaCadaNumeroUsadoComASuaOrigem() {
        informar(VISITANTES, 1200L, CADASTROS, 1072L);

        FontesDto fontes = bloco(doAmarelo(), "b-fontes", FontesDto.class);

        List<String> refs = fontes.itens().stream().map(FonteDto::ref).toList();
        assertThat(refs).doesNotHaveDuplicates().contains("cons:aplicacoes.previstas", "cons:aplicacoes.comprovadas",
                "cons:aplicacoes.percentual", "cons:aplicacoes.fotos", "lista:aplicacoes", "lista:comprovacoes",
                "dado:" + VISITANTES, "dado:" + CADASTROS, "calc:taxa_cadastro", "calc:brindes_pct",
                "dado:" + DISPONIVEIS, "dado:" + DISTRIBUIDOS);
        assertThat(refs).as("a marca, a cota e a edição não são números").noneMatch(r -> r.startsWith("parc:") || r.startsWith("evento:"));

        FonteDto previstas = fonte(fontes, "cons:aplicacoes.previstas");
        assertThat(previstas.rotulo()).isEqualTo("Aplicações previstas");
        assertThat(previstas.valor()).isEqualTo("19");
        assertThat(previstas.origem()).isEqualTo("Consolidação do portal: plano comercial e fotos de comprovação");

        FonteDto visitantes = fonte(fontes, "dado:" + VISITANTES);
        assertThat(visitantes.valor()).isEqualTo("1.200");
        assertThat(visitantes.origem()).startsWith("Informado por Marketing Amarelo em ").endsWith(", pelo Atlas");

        FonteDto taxa = fonte(fontes, "calc:taxa_cadastro");
        assertThat(taxa.valor()).isEqualTo("89,3%");
        assertThat(taxa.origem()).isEqualTo("pct(cadastros, visitantes) com cadastros = 1.072 e visitantes = 1.200");

        FonteDto naoInformado = fonte(fontes, "dado:" + DISPONIVEIS);
        assertThat(naoInformado.valor()).isNull();
        assertThat(naoInformado.origem()).isEqualTo("Não informado pela marca.");
        assertThat(fonte(fontes, "calc:brindes_pct").valor()).isNull();
    }

    @Test
    void oSnapshotVoltaDoJsonIgualAoQueFoiMontado() {
        informar(VISITANTES, 1200L, CADASTROS, 1072L);
        SnapshotDto snapshot = doAmarelo();

        SnapshotDto lido = AtlasJson.ler(AtlasJson.escrever(snapshot), SnapshotDto.class);

        assertThat(lido).isEqualTo(snapshot);
    }

    static FonteDto fonte(FontesDto fontes, String ref) {
        return fontes.itens().stream().filter(f -> f.ref().equals(ref)).findFirst().orElseThrow();
    }
}
