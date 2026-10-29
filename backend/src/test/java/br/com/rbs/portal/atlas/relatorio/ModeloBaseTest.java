package br.com.rbs.portal.atlas.relatorio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.relatorio.Especificacao.Capa;
import br.com.rbs.portal.atlas.relatorio.Especificacao.KpiGrade;
import br.com.rbs.portal.atlas.relatorio.Especificacao.Paragrafo;
import br.com.rbs.portal.atlas.relatorio.Especificacao.Referencia;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * [ATLAS] O modelo-base "Ativação no evento" lido do arquivo e a especificação em JSON: o catálogo de blocos é fechado (tipo,
 * propriedade ou referência fora do que o plano define é erro), a ordem se mantém e o texto de um bloco redigível volta do JSON
 * como foi gravado. Teste de unidade, sem Spring nem banco.
 */
class ModeloBaseTest {

    private final ModeloBase modelos = new ModeloBase();

    private Especificacao ativacao() {
        return modelos.obter(ModeloBase.ATIVACAO_EVENTO).orElseThrow();
    }

    @Test
    void leOModeloDeAtivacaoComSuasSecoesEBlocos() {
        Especificacao modelo = ativacao();

        assertThat(modelo.aba()).isEqualTo("APLICACOES");
        assertThat(modelo.titulo()).isEqualTo("Ativação no evento");
        assertThat(modelo.secoes()).extracting(Especificacao.Secao::id)
                .containsExactly("capa", "resumo", "aplicacoes", "publico", "comprovacoes", "fontes");
        assertThat(modelo.blocos()).extracting(Especificacao.Bloco::id)
                .containsExactly("b-capa", "b-resumo", "b-kpi-aplic", "b-tab-aplic", "b-kpi-publico", "b-publico", "b-galeria", "b-fontes");
        assertThat(modelo.redigiveis()).extracting(Paragrafo::id).containsExactly("b-resumo", "b-publico");
        assertThat(modelo.lacunas().obrigatorias()).containsExactly("ativacao.visitantes_total", "ativacao.cadastros_consentidos");
        assertThat(modelo.lacunas().opcionais()).containsExactly("ativacao.brindes_disponiveis", "ativacao.brindes_distribuidos");
        assertThat(modelo.calculos().keySet()).containsExactly("taxa_cadastro", "brindes_pct");
    }

    @Test
    void aCapaLevaAEdicaoEAFraseDoResumoComecaComAMarca() {
        Capa capa = (Capa) ativacao().bloco("b-capa").orElseThrow();
        Paragrafo resumo = (Paragrafo) ativacao().bloco("b-resumo").orElseThrow();

        assertThat(capa.edicao()).isEqualTo(new Referencia("evento:edicao"));
        assertThat(capa.subtitulo()).isEqualTo(new Referencia("parc:patrocinador"));
        assertThat(resumo.fraseModelo()).startsWith("A marca {{parc:patrocinador}} teve ");
    }

    @Test
    void osRotulosDePercentualNaoTemOParentesesPorqueOValorJaVemComPorcento() {
        KpiGrade aplicacoes = (KpiGrade) ativacao().bloco("b-kpi-aplic").orElseThrow();
        KpiGrade publico = (KpiGrade) ativacao().bloco("b-kpi-publico").orElseThrow();

        assertThat(aplicacoes.itens()).extracting(Especificacao.ItemKpi::rotulo).containsExactly("Previstas", "Comprovadas", "Comprovação", "Fotos");
        assertThat(publico.itens()).extracting(Especificacao.ItemKpi::rotulo)
                .containsExactly("Visitantes", "Cadastros", "Taxa de cadastro", "Brindes distribuídos");
        assertThat(ativacao().blocos().toString()).doesNotContain("(%)");
    }

    @Test
    void aEspecificacaoVoltaDoJsonIgualAoQueFoiGravada() {
        Especificacao original = ativacao().comTexto("b-resumo", "A marca {{parc:patrocinador}} foi ao evento.", "ATLAS");

        Especificacao lida = AtlasJson.ler(AtlasJson.escrever(original), Especificacao.class);

        assertThat(lida).isEqualTo(original);
        assertThat(((Paragrafo) lida.bloco("b-resumo").orElseThrow()).redigidoPor()).isEqualTo("ATLAS");
        assertThat(lida.calculos().keySet()).as("a ordem dos cálculos se mantém").containsExactly("taxa_cadastro", "brindes_pct");
    }

    @Test
    void soUmBlocoRedigivelRecebeTexto() {
        assertThatThrownBy(() -> ativacao().comTexto("b-capa", "texto", "ATLAS")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ativacao().comTexto("b-nao-existe", "texto", "ATLAS")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tipoDeBlocoForaDoCatalogoEErro() {
        String json = ativacao().titulo() == null ? "" : AtlasJson.escrever(ativacao()).replace("\"tipo\":\"capa\"", "\"tipo\":\"html\"");

        assertThatThrownBy(() -> AtlasJson.ler(json, Especificacao.class)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void propriedadeDesconhecidaNoBlocoEErro() {
        String json = AtlasJson.escrever(ativacao()).replace("\"tipo\":\"fontes\"", "\"tipo\":\"fontes\",\"html\":\"<b>x</b>\"");

        assertThat(json).contains("\"html\"");
        assertThatThrownBy(() -> AtlasJson.ler(json, Especificacao.class)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void referenciaForaDaGramaticaOuCalculoInexistenteNaoPassaNaValidacao() {
        String semCalculo = AtlasJson.escrever(ativacao()).replace("calc:taxa_cadastro", "calc:nao_existe");
        String refRuim = AtlasJson.escrever(ativacao()).replace("cons:aplicacoes.previstas", "cons:aplicacoes.tudo");
        String listaComoValor = AtlasJson.escrever(ativacao()).replace("cons:aplicacoes.fotos", "lista:aplicacoes");

        for (String json : List.of(semCalculo, refRuim, listaComoValor)) {
            Especificacao lida = AtlasJson.ler(json, Especificacao.class);
            assertThatThrownBy(lida::validar).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void oModeloBaseNaoTemNumeroEscritoNosTextos() {
        for (Paragrafo p : ativacao().redigiveis()) {
            assertThat(Referencias.semMarcadores(p.fraseModelo())).as("frase-modelo de " + p.id()).doesNotContainPattern("[0-9]");
        }
    }
}
