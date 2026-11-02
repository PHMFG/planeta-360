package br.com.rbs.portal.atlas.relatorio;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.CADASTROS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.VISITANTES;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.valores;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.contexto.AtlasContextoFabrica;
import br.com.rbs.portal.atlas.ferramentas.Argumentos;
import br.com.rbs.portal.atlas.ferramentas.Calcular;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.FonteDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.FontesDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.ParagrafoDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.RelatorioAtlasDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.OperacaoDeTexto;
import br.com.rbs.portal.dados.DadoInformadoService;
import br.com.rbs.portal.dados.OrigemDoDado;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] [RELATORIO] A conta do {@code calcular} com um relatório em montagem na conversa (M5): vai também para
 * {@code atlas_calculo}, o texto de um bloco a usa como {@code {{calc:nome}}}, o relatório a resolve (em montagem e depois de
 * pronto, pelo snapshot) e "Fontes e cálculos" a lista como lista as fórmulas fixas. Sem relatório em montagem, nada vai para
 * {@code atlas_calculo}. Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class ContaDoChatNoRelatorioTest {

    private static final String RESUMO = "b-resumo";
    private static final String PUBLICO = "b-publico";
    private static final String BOM_RESUMO = "A marca {{parc:patrocinador}} teve {{cons:aplicacoes.comprovadas}} de "
            + "{{cons:aplicacoes.previstas}} aplicações comprovadas.";
    private static final String COM_A_CONTA = "A energia custou R$ {{calc:tarifa}}, e {{calc:taxa_cadastro}} dos visitantes deixaram cadastro.";
    private static final String MENSAGEM = "O preço é R$ 312,00 por metro e a área tem 34 metros.";

    @Autowired
    RelatorioAtlasService relatorios;

    @Autowired
    Calcular calcular;

    @Autowired
    AtlasRepositorio repositorio;

    @Autowired
    AtlasContextoFabrica fabrica;

    @Autowired
    DadoInformadoService dados;

    @Autowired
    JdbcClient jdbc;

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    AtlasContexto novaConversa() {
        long conversa = repositorio.criarConversa(amarelo(), usuario(jdbc, AMARELO).id());
        return fabrica.para(usuario(jdbc, AMARELO), amarelo(), conversa, requisicao());
    }

    FontesDoTurno turno() {
        return new FontesDoTurno(MENSAGEM, 2027, LocalDate.of(2026, 11, 1));
    }

    void tarifa(AtlasContexto contexto, FontesDoTurno turno, String nome) {
        calcular.executar(contexto, turno, Argumentos.ler("{\"nome\":\"" + nome + "\",\"script\":\"preco * area\",\"formato\":\"moeda\","
                + "\"variaveis\":{\"preco\":{\"fonte\":\"usuario\",\"valor\":\"312\"},\"area\":{\"fonte\":\"usuario\",\"valor\":\"34\"}}}"),
                "call_x", null);
    }

    List<Map<String, Object>> linhasDeCalculo(long relatorio) {
        return jdbc.sql("SELECT nome, script, resultado, unidade, jsonb_typeof(variaveis) AS tipo FROM atlas_calculo WHERE relatorio_id = :r ORDER BY id")
                .param("r", relatorio).query().listOfRows();
    }

    @Test
    void comRelatorioEmMontagemAContaVaiParaAtlasCalculo() {
        AtlasContexto contexto = novaConversa();
        long relatorio = relatorios.iniciar(contexto, ModeloBase.ATIVACAO_EVENTO, "Teste").relatorioId();

        tarifa(contexto, turno(), "tarifa");

        List<Map<String, Object>> linhas = linhasDeCalculo(relatorio);
        assertThat(linhas).hasSize(1);
        assertThat(linhas.get(0)).containsEntry("nome", "tarifa").containsEntry("script", "preco * area").containsEntry("tipo", "object");
        assertThat(((java.math.BigDecimal) linhas.get(0).get("resultado"))).isEqualByComparingTo("10608");
    }

    @Test
    void semRelatorioEmMontagemNadaVaiParaAtlasCalculo() {
        AtlasContexto contexto = novaConversa();
        long antes = jdbc.sql("SELECT count(*) FROM atlas_calculo").query(Long.class).single();

        tarifa(contexto, turno(), "tarifa");

        assertThat(jdbc.sql("SELECT count(*) FROM atlas_calculo").query(Long.class).single()).isEqualTo(antes);
    }

    @Test
    void oMesmoNomeNoMesmoRelatorioEmOutroTurnoEErro() {
        AtlasContexto contexto = novaConversa();
        relatorios.iniciar(contexto, ModeloBase.ATIVACAO_EVENTO, "Teste");
        tarifa(contexto, turno(), "tarifa");

        // outro turno: o turno novo não conhece a conta, mas o relatório sim, e a linha de atlas_calculo é única por nome
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tarifa(contexto, turno(), "tarifa"))
                .isInstanceOf(br.com.rbs.portal.comum.DadosInvalidosException.class)
                .satisfies(erro -> assertThat(((br.com.rbs.portal.comum.DadosInvalidosException) erro).campos().values())
                        .anySatisfy(motivo -> assertThat(motivo).contains("tarifa")));
    }

    @Test
    void oBlocoUsaAContaOMontadorAResolveEFontesECalculosAListaComoListaAsFormulasFixas() {
        AtlasContexto contexto = novaConversa();
        long relatorio = relatorios.iniciar(contexto, ModeloBase.ATIVACAO_EVENTO, "Teste").relatorioId();
        dados.gravar(contexto.usuario(), amarelo(), valores(VISITANTES, 1200L, CADASTROS, 1072L), OrigemDoDado.ATLAS, requisicao());
        tarifa(contexto, turno(), "tarifa");

        // o bloco usa {{calc:tarifa}}: a checagem do parágrafo resolve a conta do chat
        var edicao = relatorios.definirTextos(contexto, relatorio,
                List.of(new OperacaoDeTexto(RESUMO, BOM_RESUMO), new OperacaoDeTexto(PUBLICO, COM_A_CONTA)));
        assertThat(edicao.operacoes()).extracting("resultado").containsExactly("aplicada", "aplicada");

        // ainda em montagem, o relatório já mostra o valor
        RelatorioAtlasDto emMontagem = relatorios.ver(contexto.usuario(), relatorio);
        assertThat(paragrafo(emMontagem, PUBLICO).texto()).isEqualTo("A energia custou R$ 10.608,00, e 89,3% dos visitantes deixaram cadastro.");

        relatorios.finalizar(contexto, relatorio);

        RelatorioAtlasDto pronto = relatorios.ver(contexto.usuario(), relatorio);
        assertThat(pronto.status()).isEqualTo("PRONTO");
        assertThat(paragrafo(pronto, PUBLICO).texto()).isEqualTo("A energia custou R$ 10.608,00, e 89,3% dos visitantes deixaram cadastro.");
        FonteDto daConta = fontes(pronto).stream().filter(f -> f.ref().equals("calc:tarifa")).findFirst().orElseThrow();
        assertThat(daConta.rotulo()).isEqualTo("tarifa");
        assertThat(daConta.valor()).isEqualTo("10.608,00");
        assertThat(daConta.origem()).contains("preco * area", "preco = 312", "area = 34");
        assertThat(fontes(pronto)).extracting(FonteDto::ref).contains("calc:taxa_cadastro", "calc:tarifa");
        // atlas_calculo: a conta do chat (já gravada) e as duas fórmulas fixas (gravadas ao finalizar), sem duplicar nenhuma
        List<Object> nomes = linhasDeCalculo(relatorio).stream().map(linha -> linha.get("nome")).toList();
        assertThat(nomes.get(0)).isEqualTo("tarifa");
        assertThat(nomes).containsExactlyInAnyOrder("tarifa", "taxa_cadastro", "brindes_pct");
    }

    @Test
    void contaQueNaoEDesteRelatorioNaoEhResolvidaPeloTextoDoBloco() {
        AtlasContexto contexto = novaConversa();
        tarifa(contexto, turno(), "tarifa");   // sem relatório em montagem: a conta só existe no chat
        long relatorio = relatorios.iniciar(contexto, ModeloBase.ATIVACAO_EVENTO, "Teste").relatorioId();

        var edicao = relatorios.definirTextos(contexto, relatorio, List.of(new OperacaoDeTexto(PUBLICO, "A energia custou R$ {{calc:tarifa}}.")));

        assertThat(edicao.operacoes()).singleElement().satisfies(op -> {
            assertThat(op.resultado()).isEqualTo("recusada");
            assertThat(op.erro()).contains("calc:tarifa");
        });
    }

    private static ParagrafoDto paragrafo(RelatorioAtlasDto relatorio, String bloco) {
        return relatorio.secoes().stream().flatMap(secao -> secao.blocos().stream()).filter(b -> b.id().equals(bloco))
                .map(ParagrafoDto.class::cast).findFirst().orElseThrow();
    }

    private static List<FonteDto> fontes(RelatorioAtlasDto relatorio) {
        return relatorio.secoes().stream().flatMap(secao -> secao.blocos().stream()).filter(FontesDto.class::isInstance)
                .map(FontesDto.class::cast).flatMap(bloco -> bloco.itens().stream()).toList();
    }
}
