package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.contexto.AtlasContextoFabrica;
import br.com.rbs.portal.atlas.ferramentas.GerarModelo;
import br.com.rbs.portal.atlas.ferramentas.ModelosEmBranco;
import br.com.rbs.portal.atlas.ferramentas.ModelosEmBranco.Tipo;
import br.com.rbs.portal.atlas.ferramentas.RegistroDeFerramentas;
import br.com.rbs.portal.comum.DadosInvalidosException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] O modelo em branco {@code ESQUELETO_RELATORIO_COTA} (M8, Atlas da RBS): o esqueleto do relatório da cota, sem nenhum número,
 * com as seis seções do relatório oficial e os dezessete indicadores do catálogo, cada um na sua seção. [PERMISSAO] O tipo é só da RBS:
 * a marca nunca o recebe, nem o vê na lista de modelos, nem o nome dele aparece no erro de tipo desconhecido. Cada teste é desfeito no
 * fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class EsqueletoDoRelatorioDaCotaTest {

    private static final String ESQUELETO = "{\"tipo\":\"ESQUELETO_RELATORIO_COTA\"}";
    private static final List<String> SECOES_DO_RELATORIO = List.of("Cumprimento das entregas", "Mídia: previsto x exibido",
            "Ativação no evento", "Pesquisa da ativação", "Cortesias e credenciamento", "O festival em números");

    @Autowired
    ModelosEmBranco modelos;

    @Autowired
    RegistroDeFerramentas registro;

    @Autowired
    AtlasContextoFabrica contextos;

    @Autowired
    AtlasService atlas;

    @Autowired
    JdbcClient jdbc;

    String conteudo() {
        return modelos.modelo(Tipo.ESQUELETO_RELATORIO_COTA).conteudo();
    }

    /** Os trechos do arquivo por seção ({@code ## ...}), na ordem em que aparecem. */
    Map<String, String> secoes() {
        Map<String, String> secoes = new LinkedHashMap<>();
        String atual = null;
        StringBuilder corpo = new StringBuilder();
        for (String linha : conteudo().split("\n")) {
            if (linha.startsWith("## ")) {
                if (atual != null) {
                    secoes.put(atual, corpo.toString());
                }
                atual = linha.substring(3).strip();
                corpo = new StringBuilder();
            } else {
                corpo.append(linha).append('\n');
            }
        }
        if (atual != null) {
            secoes.put(atual, corpo.toString());
        }
        return secoes;
    }

    AtlasContexto contextoDoAmarelo() {
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        var pessoa = usuario(jdbc, AMARELO);
        long conversa = atlas.abrirConversa(pessoa, parceria, null, requisicao()).conversaId();
        return contextos.para(pessoa, parceria, conversa, requisicao());
    }

    // ---------------------------------------------------------------------
    // O modelo
    // ---------------------------------------------------------------------

    @Test
    void oTipoTemTituloNomeDeArquivoMdEConteudo() {
        var modelo = modelos.modelo(Tipo.ESQUELETO_RELATORIO_COTA);

        assertThat(modelo.titulo()).isEqualTo("Esqueleto do relatório da cota");
        assertThat(modelo.nomeArquivo()).isEqualTo("esqueleto-do-relatorio-da-cota.md");
        assertThat(modelo.conteudo()).startsWith("# Esqueleto do relatório da cota").endsWith("\n");
        assertThat(Tipo.deNome("ESQUELETO_RELATORIO_COTA")).contains(Tipo.ESQUELETO_RELATORIO_COTA);
    }

    @Test
    void oEsqueletoNaoTemNumeroMoedaNemInstrucaoAoModelo() {
        String texto = conteudo();

        assertThat(texto).as("sem algarismo").doesNotContainPattern("[0-9]");
        assertThat(texto.toLowerCase()).as("sem moeda nem valor").doesNotContain("r$").doesNotContain("valor").doesNotContain("{{")
                .doesNotContain("brl");
        assertThat(texto.toLowerCase()).as("sem marca nem instrução ao modelo de linguagem").doesNotContain("atlas").doesNotContain("rbs")
                .doesNotContain("planeta").doesNotContain("atlântida").doesNotContain("edição").doesNotContain("você é");
    }

    @Test
    void trazAsSeisSecoesDoRelatorioOficialNaOrdemDoModelo() {
        List<String> titulos = new ArrayList<>(secoes().keySet());

        assertThat(titulos).contains("Identificação", "Observações");
        assertThat(titulos.stream().filter(SECOES_DO_RELATORIO::contains).toList()).containsExactlyElementsOf(SECOES_DO_RELATORIO);
        // as seis seções vêm seguidas, entre a identificação e as observações
        assertThat(titulos.subList(1, 7)).containsExactlyElementsOf(SECOES_DO_RELATORIO);
    }

    @Test
    void todoIndicadorDoCatalogoEstaNaSecaoDele() {
        List<String[]> catalogo = jdbc.sql("SELECT secao, nome FROM indicador").query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)})
                .list();
        Map<String, String> secoes = secoes();

        assertThat(catalogo).hasSize(17);
        for (String[] indicador : catalogo) {
            assertThat(secoes).as("a seção " + indicador[0]).containsKey(indicador[0]);
            assertThat(secoes.get(indicador[0])).as("o indicador " + indicador[1] + " na seção " + indicador[0]).contains(indicador[1]);
        }
    }

    // ---------------------------------------------------------------------
    // Só da RBS
    // ---------------------------------------------------------------------

    @Test
    void aRbsTemOsQuatroTiposEAMarcaSoOsTresDeSempre() {
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        var marca = usuario(jdbc, AMARELO);

        assertThat(modelos.disponiveisDaRbs()).containsExactly(Tipo.BRIEFING_CONTEUDO, Tipo.PERGUNTAS_PESQUISA,
                Tipo.PLANO_OPERACIONAL_ATIVACAO, Tipo.ESQUELETO_RELATORIO_COTA);
        assertThat(modelos.disponiveis(marca, parceria)).containsExactly(Tipo.BRIEFING_CONTEUDO, Tipo.PERGUNTAS_PESQUISA,
                Tipo.PLANO_OPERACIONAL_ATIVACAO);
        assertThat(modelos.oferecidos(marca, parceria)).extracting(AtlasDtos.ModeloOferecido::tipo)
                .containsExactly("BRIEFING_CONTEUDO", "PERGUNTAS_PESQUISA", "PLANO_OPERACIONAL_ATIVACAO");
    }

    @Test
    void aMarcaPedirOEsqueletoRecebeOErroDeTipoDesconhecidoSemOsNomeDele() {
        AtlasContexto amarelo = contextoDoAmarelo();
        FontesDoTurno turno = new FontesDoTurno("Oi", 2027, LocalDate.of(2026, 11, 1));

        var preparo = registro.preparar(amarelo, turno, GerarModelo.NOME, ESQUELETO);
        assertThatThrownBy(() -> registro.executar(amarelo, turno, preparo, "call_x")).isInstanceOf(DadosInvalidosException.class)
                .satisfies(erro -> {
                    String tipo = ((DadosInvalidosException) erro).campos().get("tipo");
                    assertThat(tipo).startsWith("Tipo desconhecido.").contains("BRIEFING_CONTEUDO", "PERGUNTAS_PESQUISA",
                            "PLANO_OPERACIONAL_ATIVACAO");
                    assertThat(tipo).doesNotContain("ESQUELETO");
                });
        assertThat(registro.oferecidas(java.util.Optional.empty(), true).toString()).doesNotContain("ESQUELETO_RELATORIO_COTA");
    }
}
