package br.com.rbs.portal.importacao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.atlas.ProvedorRoteirizado;
import br.com.rbs.portal.atlas.RoteiroDeLlmConfig;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.ChamadaDeFerramenta;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm;
import br.com.rbs.portal.importacao.extracao.ConferenciaDaExtracao;
import br.com.rbs.portal.importacao.extracao.ExtratorLivre;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** O extrator de texto corrido (E8): o modelo propõe as linhas pela ferramenta e o código confere cada valor na fonte. */
@TesteDeIntegracao
@Import(RoteiroDeLlmConfig.class)
class ExtratorLivreTest {

    private static final String PARAGRAFO = "Segunda, 05/10 · Programação · 3 chamadas assinadas de 5\" para Cliente Amarelo · "
            + "2 comerciais de 30\" para Cliente Amarelo.";

    @Autowired
    ExtratorLivre extrator;

    @Autowired
    ConferenciaDaExtracao conferencia;

    @Autowired
    ProvedorRoteirizado roteiro;

    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void limpar() {
        roteiro.limpar();
    }

    private AlcanceDeUmaImportacao alcance() {
        long evento = jdbc.sql("SELECT id FROM evento WHERE edicao = 2027").query(Long.class).single();
        return new AlcanceDeUmaImportacao(evento, AlcanceDaImportacao.EDICAO, null, null);
    }

    private static ConteudoLido conteudo() {
        return new ConteudoLido("DOCX", List.of(new Pedaco("Rádio 92 · Relatório de veiculação", "parágrafo 1", 1),
                new Pedaco(PARAGRAFO, "parágrafo 3", 2)), Map.of());
    }

    private static ConteudoLido conteudoDaImagem() {
        return new ConteudoLido("PNG", List.of(new Pedaco(
                "CLIENTE ROXO\nRádio Atlântida\nConteúdo assinado\nPlaneta Atlântida\n21 de outubro de 2026", "imagem", 1)),
                Map.of());
    }

    private static String proposta(int quantidade) {
        return """
                {"destino":"VEICULACAO","linhas":[{"campos":{"patrocinador":"Cliente Amarelo","veiculo":"Rádio 92",
                  "formato":"Chamadas assinadas","exibido_em":"2026-10-05","quantidade":%d},
                  "origem":{"patrocinador":{"localizador":"parágrafo 3","trecho":"Cliente Amarelo"},
                            "veiculo":{"localizador":"parágrafo 3","trecho":"Rádio 92"},
                            "formato":{"localizador":"parágrafo 3","trecho":"chamadas assinadas"},
                            "exibido_em":{"localizador":"parágrafo 3","trecho":"05/10"},
                            "quantidade":{"localizador":"parágrafo 3","trecho":"3 chamadas"}}}]}
                """.formatted(quantidade);
    }

    private void roteirizar(String argumentos) {
        roteiro.limpar().passo(pedido -> {
            assertThat(pedido.mensagens().get(1).conteudo()).contains("<fonte>", "parágrafo 3");
            return new RespostaDoLlm(null, List.of(new ChamadaDeFerramenta("m1", "propor_linhas", argumentos)), null);
        });
    }

    private static RespostaDoLlm resposta(String destino, String linha) {
        return new RespostaDoLlm(null, List.of(new ChamadaDeFerramenta("m1", "propor_linhas",
                "{\"destino\":\"" + destino + "\",\"linhas\":[" + linha + "]}")), null);
    }

    @Test
    void oModeloPropõeALinhaEOCódigoConfereCadaValorNaFonte() {
        roteirizar(proposta(3));
        ResultadoDaExtracao resultado = extrator.extrairComDetalhe(conteudo(), DestinoDaImportacao.VEICULACAO, alcance());
        assertThat(resultado.linhas()).hasSize(1);
        LinhaProposta linha = resultado.linhas().getFirst();
        assertThat(linha.deTexto()).isTrue();
        assertThat(linha.dados().get("quantidade")).isEqualTo(3L);
        assertThat(linha.dados().get("exibido_em")).isEqualTo("2026-10-05");
        assertThat(linha.origem().get("quantidade").localizador()).isEqualTo("parágrafo 3");
        assertThat(conferencia.problemas(linha, alcance(), null)).isEmpty();
    }

    @Test
    void oNumeroQueNãoEstáNaFonteÉRecusadoPelaConferência() {
        roteirizar(proposta(999));
        LinhaProposta linha = extrator.extrairComDetalhe(conteudo(), DestinoDaImportacao.VEICULACAO, alcance()).linhas().getFirst();
        assertThat(conferencia.problemas(linha, alcance(), null))
                .anySatisfy(problema -> assertThat(problema).contains("quantidade", "não aparece na fonte"));
    }

    @Test
    void aOrigemComLocalizadorQueNãoExisteEhProcuradaNosPedacos() {
        String argumentos = proposta(3).replace("parágrafo 3\",\"trecho\":\"3 chamadas", "parágrafo 99\",\"trecho\":\"3 chamadas");
        roteirizar(argumentos);
        LinhaProposta linha = extrator.extrairComDetalhe(conteudo(), DestinoDaImportacao.VEICULACAO, alcance()).linhas().getFirst();
        assertThat(linha.origem()).containsKey("quantidade");
        assertThat(linha.origem().get("quantidade").localizador()).isEqualTo("parágrafo 3");
        assertThat(linha.origem().get("quantidade").trecho()).contains("3 chamadas");
        assertThat(conferencia.problemas(linha, alcance(), null)).isEmpty();
    }

    @Test
    void aOrigemVaziaEhPreenchidaComOSPedacoDoConteudoLido() {
        roteiro.limpar().passo(pedido -> resposta("COMPROVANTE", """
                {"campos":{"patrocinador":"Cliente Roxo","veiculo":"Rádio Atlântida","formato":"Conteúdo assinado",
                  "espaco_programa":"Planeta Atlântida","data":"2026-10-21"},"origem":{}}
                """));
        LinhaProposta linha = extrator.extrairComDetalhe(conteudoDaImagem(), DestinoDaImportacao.COMPROVANTE, alcance())
                .linhas().getFirst();

        assertThat(linha.origem()).containsKeys("patrocinador", "veiculo", "formato", "espaco_programa", "data");
        for (String campo : List.of("patrocinador", "veiculo", "formato", "espaco_programa", "data")) {
            assertThat(linha.origem().get(campo).localizador()).as(campo).isEqualTo("imagem");
        }
        assertThat(linha.origem().get("patrocinador").trecho()).contains("CLIENTE ROXO");
        assertThat(linha.origem().get("veiculo").trecho()).contains("Rádio Atlântida");
        assertThat(linha.origem().get("data").trecho()).contains("21 de outubro de 2026");
        assertThat(conferencia.problemas(linha, alcance(), null)).isEmpty();
    }

    @Test
    void oPromptDizQueUmMesSemAnoUsaOAnoDaEdicao() {
        String[] sistema = new String[1];
        roteiro.limpar().passo(pedido -> {
            sistema[0] = pedido.mensagens().get(0).conteudo();
            return resposta("VEICULACAO", "");
        });

        extrator.extrairComDetalhe(conteudo(), DestinoDaImportacao.VEICULACAO, alcance());

        assertThat(sistema[0]).contains("ano da edição", "em outubro", "2026");
    }

    @Test
    void oPromptDizQueAUnidadeEhUmCodigoEONumeroJaVemNaEscala() {
        String[] usuario = new String[1];
        roteiro.limpar().passo(pedido -> {
            usuario[0] = pedido.mensagens().get(1).conteudo();
            return resposta("NUMEROS_EDICAO", "");
        });

        extrator.extrairComDetalhe(conteudo(), DestinoDaImportacao.NUMEROS_EDICAO, alcance());

        assertThat(usuario[0]).contains("PESSOAS", "IMPACTOS", "MARCAS", "REAIS", "UNIDADE", "80000");
    }

    @Test
    void comSuspeitaDeDadoPessoalNadaVaiAoModelo() {
        assertThat(extrator.extrairSemModelo(conteudo(), DestinoDaImportacao.VEICULACAO, alcance())).isEmpty();
        assertThat(roteiro.pedidos()).isEmpty();
    }
}
