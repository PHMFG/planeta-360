package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.CADASTROS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.VISITANTES;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.valores;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemCartao;
import br.com.rbs.portal.atlas.AtlasDtos.ItemRelatorioPronto;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.AtlasDtos.RespostaDoCartaoRequisicao;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] [RELATORIO] A conferência do M6 com o modelo de linguagem roteirizado (nenhum provedor de verdade): o modelo monta o
 * relatório de ponta a ponta (início, cartão, textos, finalização) e o A4 que sai cumpre as duas regras do marco. O modelo tenta
 * primeiro textos com algarismo, com número por extenso e com data por extenso em cada bloco, e o servidor os recusa: o que fica
 * guardado na especificação é só o que passou, sem nenhum algarismo fora de marcador. Todo número do A4 está em "Fontes e
 * cálculos". O botão sem modelo está em {@link ConferenciaDoRelatorioPlanoBTest}. Cada teste é desfeito no fim (@Transactional).
 */
@TesteComLlmRoteirizado
@Transactional
class ConferenciaDoRelatorioComModeloTest {

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void zerarORoteiro() {
        modelo.limpar();
    }

    UsuarioAutenticado marca() {
        return usuario(jdbc, AMARELO);
    }

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    @Test
    @SuppressWarnings("unchecked")
    void omodeloMontaORelatorioEOTextoSoSaiComMarcadorEOA4TemTodoNumeroNasFontes() {
        long conversa = atlas.abrirConversa(marca(), amarelo(), null, requisicao()).conversaId();
        modelo.chamada(LoopDoAgenteTest.INICIAR, LoopDoAgenteTest.ARGS_INICIAR).chamada(LoopDoAgenteTest.SOLICITAR, LoopDoAgenteTest.ARGS_CARTAO);
        ConversaDto aberta = atlas.enviarMensagem(marca(), conversa, new MensagemRequisicao("Monte o relatório da ativação.", null, null), requisicao());
        ItemCartao cartao = aberta.itens().stream().filter(ItemCartao.class::isInstance).map(ItemCartao.class::cast).findFirst().orElseThrow();
        // o modelo tenta o que não pode, em cada bloco (a primeira recusa só pede de novo), e depois escreve só com marcador
        modelo.chamada(LoopDoAgenteTest.EDITAR, LoopDoAgenteTest.edicao(
                        "b-resumo", "A marca teve 17 aplicações comprovadas em 2027.",
                        "b-publico", "Foram mil e duzentos visitantes no primeiro de dezembro."))
                .chamada(LoopDoAgenteTest.EDITAR, LoopDoAgenteTest.edicao("b-resumo", LoopDoAgenteTest.RESUMO, "b-publico", LoopDoAgenteTest.PUBLICO))
                .chamada(LoopDoAgenteTest.FINALIZAR, "{}");

        ConversaDto pronta = atlas.responderCartao(marca(), cartao.solicitacaoId(),
                new RespostaDoCartaoRequisicao("accept", valores(VISITANTES, 1200L, CADASTROS, 1072L)), requisicao());

        ItemRelatorioPronto item = (ItemRelatorioPronto) pronta.itens().get(0);
        assertThat(pronta.itens()).hasSize(1);
        assertThat(modelo.sobraDoRoteiro()).isZero();
        // as duas tentativas ruins foram recusadas pelo servidor, com o motivo
        List<String> respostasDeEdicao = jdbc.sql("""
                SELECT m.conteudo FROM atlas_mensagem m WHERE m.conversa_id = :c AND m.papel = 'tool' AND m.conteudo LIKE '%"operacoes"%' ORDER BY m.ordem
                """).param("c", conversa).query(String.class).list();
        assertThat(respostasDeEdicao).hasSize(2);
        assertThat(respostasDeEdicao.get(0)).contains("\"resultado\":\"recusada\"").doesNotContain("\"resultado\":\"aplicada\"");
        assertThat(respostasDeEdicao.get(1)).contains("\"resultado\":\"aplicada\"");

        SessaoDeApi sessao = SessaoDeApi.entrar(mvc, AMARELO);
        MvcTestResult relatorio = sessao.get("/api/atlas/relatorios/" + item.relatorioId());
        assertThat(relatorio).hasStatus(HttpStatus.OK);
        assertThat(lerJson(relatorio, "$.status")).isEqualTo("PRONTO");
        assertThat(lerLista(relatorio, "$.secoes[*].blocos[?(@.tipo=='paragrafo')].redigidoPor")).containsExactly("ATLAS", "ATLAS");
        // o texto guardado: nenhum algarismo nem número ou data por extenso fora de marcador (a especificação inteira, sem o resolvido)
        assertThat(ApoioDoA4.exigirTextoSemAlgarismoForaDeMarcador(jdbc, item.relatorioId())).isEqualTo(4);
        assertThat(ApoioDoA4.textosDeParagrafo(jdbc, item.relatorioId())).anyMatch(t -> t.contains("{{cons:aplicacoes.comprovadas}}"))
                .noneMatch(t -> t.contains("mil e duzentos") || t.contains("primeiro de dezembro") || t.contains("17 aplicações"));
        // e todo número do A4 está na seção Fontes e cálculos
        assertThat(ApoioDoA4.exigirTodoNumeroEmFontesECalculos(relatorio)).isGreaterThanOrEqualTo(8);
        assertThat(lerLista(relatorio, "$.secoes[*].blocos[?(@.tipo=='paragrafo')].texto")).containsExactly(
                "A marca Cliente Amarelo teve 17 de 19 aplicações comprovadas.",
                "O espaço recebeu 1.200 visitantes, e 89,3% deixaram cadastro.");
    }
}
