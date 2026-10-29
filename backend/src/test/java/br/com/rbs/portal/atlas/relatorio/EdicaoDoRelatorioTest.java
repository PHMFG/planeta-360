package br.com.rbs.portal.atlas.relatorio;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.CADASTROS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.VISITANTES;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.valores;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.contexto.AtlasContextoFabrica;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.ParagrafoDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.OperacaoDeTexto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.ResultadoDaEdicao;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.SemPermissaoException;
import br.com.rbs.portal.dados.DadoInformadoService;
import br.com.rbs.portal.dados.OrigemDoDado;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] [VALIDACAO] {@code relatorio_editar} / {@code definir_texto} no serviço do relatório: o parágrafo redigido passa pela
 * checagem do texto e sai com {@code redigidoPor} ATLAS; a recusa volta ao modelo no resultado da operação (e ele tem mais uma
 * tentativa); na segunda recusa do mesmo bloco ele fica com a frase-modelo (MODELO). Bloco que não existe ou não é redigível é
 * recusado sem contar. Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class EdicaoDoRelatorioTest {

    private static final String RESUMO = "b-resumo";
    private static final String PUBLICO = "b-publico";
    private static final String BOM_RESUMO = "A marca {{parc:patrocinador}} teve {{cons:aplicacoes.comprovadas}} de "
            + "{{cons:aplicacoes.previstas}} aplicações comprovadas.";
    private static final String BOM_PUBLICO = "O espaço recebeu {{dado:ativacao.visitantes_total}} visitantes, e "
            + "{{calc:taxa_cadastro}} deixaram cadastro.";

    @Autowired
    RelatorioAtlasService relatorios;

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

    long iniciar(AtlasContexto contexto) {
        return relatorios.iniciar(contexto, ModeloBase.ATIVACAO_EVENTO, "Teste").relatorioId();
    }

    ResultadoDaEdicao editar(AtlasContexto contexto, long relatorio, String bloco, String texto) {
        return relatorios.definirTextos(contexto, relatorio, List.of(new OperacaoDeTexto(bloco, texto)));
    }

    Especificacao.Paragrafo paragrafo(long relatorio, String bloco) {
        Especificacao especificacao = AtlasJson.ler(repositorio.relatorio(relatorio).orElseThrow().especificacao(), Especificacao.class);
        return (Especificacao.Paragrafo) especificacao.bloco(bloco).orElseThrow();
    }

    @Test
    void textoQuePassaNaChecagemSaiRedigidoPeloAtlas() {
        AtlasContexto contexto = novaConversa();
        long relatorio = iniciar(contexto);
        int versao = repositorio.relatorio(relatorio).orElseThrow().versaoEspecificacao();

        ResultadoDaEdicao resultado = editar(contexto, relatorio, RESUMO, "  " + BOM_RESUMO + "  ");

        assertThat(resultado.relatorioId()).isEqualTo(relatorio);
        assertThat(resultado.operacoes()).singleElement().satisfies(op -> {
            assertThat(op.indice()).isZero();
            assertThat(op.bloco()).isEqualTo(RESUMO);
            assertThat(op.resultado()).isEqualTo("aplicada");
            assertThat(op.erro()).isNull();
        });
        assertThat(resultado.blocosSemTexto()).containsExactly(PUBLICO);
        Especificacao.Paragrafo gravado = paragrafo(relatorio, RESUMO);
        assertThat(gravado.texto()).isEqualTo(BOM_RESUMO);
        assertThat(gravado.redigidoPor()).isEqualTo("ATLAS");
        assertThat(gravado.recusas()).isZero();
        assertThat(repositorio.relatorio(relatorio).orElseThrow().versaoEspecificacao()).isEqualTo(versao + 1);
    }

    @Test
    void relatorioFinalizadoMostraOParagrafoDoAtlasComOsNumerosPreenchidosPeloServidor() {
        AtlasContexto contexto = novaConversa();
        long relatorio = iniciar(contexto);
        dados.gravar(contexto.usuario(), amarelo(), valores(VISITANTES, 1200L, CADASTROS, 1072L), OrigemDoDado.ATLAS, requisicao());

        ResultadoDaEdicao resultado = relatorios.definirTextos(contexto, relatorio,
                List.of(new OperacaoDeTexto(RESUMO, BOM_RESUMO), new OperacaoDeTexto(PUBLICO, BOM_PUBLICO)));
        assertThat(resultado.operacoes()).extracting("resultado").containsExactly("aplicada", "aplicada");
        assertThat(resultado.blocosSemTexto()).isEmpty();
        relatorios.finalizar(contexto, relatorio);

        var pronto = relatorios.ver(contexto.usuario(), relatorio);
        var blocos = pronto.secoes().stream().flatMap(s -> s.blocos().stream()).toList();
        ParagrafoDto resumo = (ParagrafoDto) blocos.stream().filter(b -> b.id().equals(RESUMO)).findFirst().orElseThrow();
        assertThat(resumo.redigidoPor()).isEqualTo("ATLAS");
        assertThat(resumo.texto()).isEqualTo("A marca Cliente Amarelo teve 17 de 19 aplicações comprovadas.");
        ParagrafoDto publico = (ParagrafoDto) blocos.stream().filter(b -> b.id().equals(PUBLICO)).findFirst().orElseThrow();
        assertThat(publico.texto()).isEqualTo("O espaço recebeu 1.200 visitantes, e 89,3% deixaram cadastro.");
        assertThat(publico.redigidoPor()).isEqualTo("ATLAS");
    }

    @Test
    void algarismoSolteiroERecusadoESegundaFalhaDoMesmoBlocoDeixaAFraseModelo() {
        AtlasContexto contexto = novaConversa();
        long relatorio = iniciar(contexto);

        ResultadoDaEdicao primeira = editar(contexto, relatorio, RESUMO, "A marca teve 17 aplicações comprovadas.");

        assertThat(primeira.operacoes()).singleElement().satisfies(op -> {
            assertThat(op.resultado()).isEqualTo("recusada");
            assertThat(op.erro()).contains("algarismo \"1\"").contains("Escreva este bloco de novo");
        });
        assertThat(primeira.blocosSemTexto()).containsExactly(RESUMO, PUBLICO);
        assertThat(paragrafo(relatorio, RESUMO).texto()).isNull();
        assertThat(paragrafo(relatorio, RESUMO).recusas()).isEqualTo(1);

        ResultadoDaEdicao segunda = editar(contexto, relatorio, RESUMO, "Foram 17 de 19 aplicações.");

        assertThat(segunda.operacoes()).singleElement().satisfies(op -> {
            assertThat(op.resultado()).isEqualTo("frase_modelo");
            assertThat(op.erro()).contains("algarismo").contains("ficou com a frase-modelo");
        });
        Especificacao.Paragrafo recuo = paragrafo(relatorio, RESUMO);
        assertThat(recuo.texto()).isEqualTo(recuo.fraseModelo());
        assertThat(recuo.redigidoPor()).isEqualTo("MODELO");
        assertThat(recuo.recusas()).isZero();
        assertThat(segunda.blocosSemTexto()).containsExactly(PUBLICO);
    }

    @Test
    void aContagemDeRecusasEDeCadaBlocoEUmaRedacaoBoaZeraAContagem() {
        AtlasContexto contexto = novaConversa();
        long relatorio = iniciar(contexto);

        editar(contexto, relatorio, RESUMO, "Tem 3 coisas.");
        assertThat(paragrafo(relatorio, RESUMO).recusas()).isEqualTo(1);
        assertThat(paragrafo(relatorio, PUBLICO).recusas()).isZero();

        // o outro bloco falha pela primeira vez: continua só recusado, e o resumo não é afetado
        ResultadoDaEdicao outro = editar(contexto, relatorio, PUBLICO, "Tem 4 coisas.");
        assertThat(outro.operacoes().get(0).resultado()).isEqualTo("recusada");
        assertThat(paragrafo(relatorio, RESUMO).recusas()).isEqualTo(1);

        // a redação boa do resumo vale e zera a contagem; a falha seguinte volta a ser a primeira
        assertThat(editar(contexto, relatorio, RESUMO, BOM_RESUMO).operacoes().get(0).resultado()).isEqualTo("aplicada");
        assertThat(paragrafo(relatorio, RESUMO).recusas()).isZero();
        assertThat(editar(contexto, relatorio, RESUMO, "Outra com 5.").operacoes().get(0).resultado()).isEqualTo("recusada");
        assertThat(paragrafo(relatorio, RESUMO).texto()).isEqualTo(BOM_RESUMO);
    }

    @Test
    void duasOperacoesNoMesmoPedidoValemEmOrdemEUmaRecusaNaoDerrubaAOutra() {
        AtlasContexto contexto = novaConversa();
        long relatorio = iniciar(contexto);

        ResultadoDaEdicao resultado = relatorios.definirTextos(contexto, relatorio, List.of(
                new OperacaoDeTexto(RESUMO, "Tem 3."), new OperacaoDeTexto(PUBLICO, BOM_PUBLICO), new OperacaoDeTexto(RESUMO, "Tem 4.")));

        assertThat(resultado.operacoes()).extracting("indice", "bloco", "resultado")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0, RESUMO, "recusada"),
                        org.assertj.core.groups.Tuple.tuple(1, PUBLICO, "aplicada"),
                        org.assertj.core.groups.Tuple.tuple(2, RESUMO, "frase_modelo"));
        assertThat(paragrafo(relatorio, PUBLICO).redigidoPor()).isEqualTo("ATLAS");
        assertThat(paragrafo(relatorio, RESUMO).redigidoPor()).isEqualTo("MODELO");
        assertThat(resultado.blocosSemTexto()).isEmpty();
    }

    @Test
    void blocoQueNaoExisteOuNaoERedigivelERecusadoSemContar() {
        AtlasContexto contexto = novaConversa();
        long relatorio = iniciar(contexto);

        ResultadoDaEdicao resultado = relatorios.definirTextos(contexto, relatorio, List.of(
                new OperacaoDeTexto("b-capa", "Novo título."), new OperacaoDeTexto("b-kpi-aplic", "Texto."),
                new OperacaoDeTexto("b-inventado", "Texto."), new OperacaoDeTexto(null, "Texto.")));

        assertThat(resultado.operacoes()).extracting("resultado").containsOnly("recusada");
        assertThat(resultado.operacoes().get(0).erro()).contains("não existe ou não é redigível").contains("b-resumo, b-publico");
        assertThat(paragrafo(relatorio, RESUMO).recusas()).isZero();
        assertThat(resultado.blocosSemTexto()).containsExactly(RESUMO, PUBLICO);
        assertThat(repositorio.relatorio(relatorio).orElseThrow().versaoEspecificacao()).isEqualTo(1);   // nada mudou, nada gravado
    }

    @Test
    void referenciaInventadaEHtmlEUrlSaoRecusadosComoOAlgarismo() {
        AtlasContexto contexto = novaConversa();
        long relatorio = iniciar(contexto);

        for (String texto : List.of("Veja {{dado:ativacao.inventado}}.", "Veja {{cons:aplicacoes.inexistente}}.",
                "Veja <b>isto</b>.", "Veja https://exemplo.example.", "Veja [aqui](x).")) {
            ResultadoDaEdicao resultado = editar(contexto, relatorio, PUBLICO, texto);
            assertThat(resultado.operacoes().get(0).resultado()).as(texto).isIn("recusada", "frase_modelo");
            // a recusa nunca grava o texto do modelo: ou o bloco segue sem texto, ou recuou para a frase-modelo
            Especificacao.Paragrafo p = paragrafo(relatorio, PUBLICO);
            assertThat(p.texto() == null || p.texto().equals(p.fraseModelo())).as(texto).isTrue();
        }
    }

    @Test
    void relatorioDeOutraConversaOuForaDeMontagemOuComAbaFechadaNaoEdita() {
        AtlasContexto contexto = novaConversa();
        long relatorio = iniciar(contexto);

        assertThatThrownBy(() -> editar(novaConversa(), relatorio, RESUMO, BOM_RESUMO)).isInstanceOf(NaoEncontradoException.class);

        repositorio.mudarStatus(relatorio, "AGUARDANDO_RESPOSTA");
        assertThatThrownBy(() -> editar(contexto, relatorio, RESUMO, BOM_RESUMO)).isInstanceOf(ConflitoException.class)
                .hasMessageContaining("aguardando a resposta do cartão");
        repositorio.mudarStatus(relatorio, "MONTANDO");

        jdbc.sql("UPDATE parceria_aba SET liberada = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'APLICACOES')")
                .param("p", amarelo()).update();
        assertThatThrownBy(() -> editar(contexto, relatorio, RESUMO, BOM_RESUMO)).isInstanceOf(SemPermissaoException.class);
        assertThat(paragrafo(relatorio, RESUMO).texto()).isNull();
    }
}
