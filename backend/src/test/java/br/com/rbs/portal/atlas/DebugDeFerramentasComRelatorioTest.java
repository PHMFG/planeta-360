package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] O debug das ferramentas ({@code atlas.debug.ferramentas=true}) com o relatório ligado: além do que vale nos dois modos
 * (DebugDeFerramentasBase), as ferramentas do relatório aparecem antes do {@code cartao}, a que abriu o cartão sai sem resultado
 * enquanto ele espera a resposta e ganha o resultado depois dela, e o botão (plano B) também aparece, porque as chamadas dele estão
 * no histórico.
 */
@TesteComLlmRoteirizado
@Transactional
@TestPropertySource(properties = {"atlas.relatorio.ligado=true", "atlas.debug.ferramentas=true"})
class DebugDeFerramentasComRelatorioTest extends DebugDeFerramentasBase {

    @Test
    void ferramentaQueAbriuOCartaoSaiSemResultadoAteAPessoaResponder() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);
        modelo.chamada(LoopDoAgenteTest.INICIAR, LoopDoAgenteTest.ARGS_INICIAR).chamada(LoopDoAgenteTest.SOLICITAR, LoopDoAgenteTest.ARGS_CARTAO);

        MvcTestResult aberta = falar(pessoa, conversa, "Quero o relatório da ativação.");

        assertThat(lerLista(aberta, "$.itens[*].tipo")).containsExactly("usuario", "ferramenta", "ferramenta", "cartao");
        assertThat(lerJson(aberta, "$.itens[1].nome")).isEqualTo(LoopDoAgenteTest.INICIAR);
        assertThat(lerJson(aberta, "$.itens[1].erro")).isEqualTo(false);
        assertThat((String) lerJson(aberta, "$.itens[1].resultado")).contains("\"relatorioId\"");
        assertThat(lerJson(aberta, "$.itens[2].nome")).isEqualTo(LoopDoAgenteTest.SOLICITAR);
        assertThat(lerJson(aberta, "$.itens[2].argumentos")).isEqualTo(LoopDoAgenteTest.ARGS_CARTAO);
        assertThat(lerJson(aberta, "$.itens[2].resultado")).isNull();
        assertThat(lerJson(aberta, "$.itens[2].erro")).isEqualTo(false);
        assertThat(lerJson(aberta, "$.itens[2].duracaoMs")).isNotNull();

        long cartao = ((Number) lerJson(aberta, "$.itens[3].solicitacaoId")).longValue();
        modelo.texto("Os números de {{parc:patrocinador}} estão no relatório.");
        MvcTestResult respondida = pessoa.post("/api/atlas/solicitacoes/" + cartao + "/respostas",
                "{\"acao\":\"accept\",\"valores\":[{\"chave\":\"ativacao.visitantes_total\",\"valor\":1200},"
                        + "{\"chave\":\"ativacao.cadastros_consentidos\",\"valor\":1072}]}");
        assertThat(respondida).hasStatus(HttpStatus.OK);
        assertThat(lerJson(respondida, "$.debugDeFerramentas")).isEqualTo(true);
        assertThat(lerLista(respondida, "$.itens[*].tipo")).containsExactly("texto");   // o turno novo: só o texto

        // depois da resposta, a conversa inteira mostra o resultado da ferramenta do cartão
        MvcTestResult inteira = pessoa.get("/api/atlas/conversas/" + conversa);
        assertThat(lerLista(inteira, "$.itens[*].tipo")).containsExactly("usuario", "ferramenta", "ferramenta", "cartao", "texto");
        assertThat((String) lerJson(inteira, "$.itens[2].resultado")).contains("\"accept\"");
        assertThat(lerJson(inteira, "$.itens[2].erro")).isEqualTo(false);
        assertThat(corpo(inteira)).doesNotContain(SENTINELA);
    }

    @Test
    void asChamadasDoBotaoTambemAparecemSemCartaoDuplicado() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);

        MvcTestResult botao = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens",
                "{\"atalho\":\"MONTAR_RELATORIO\",\"modelo\":\"ATIVACAO_EVENTO\"}");

        assertThat(botao).hasStatus(HttpStatus.OK);
        assertThat(lerLista(botao, "$.itens[*].tipo")).contains("usuario", "texto", "ferramenta", "cartao");
        assertThat(lerLista(botao, "$.itens[?(@.tipo=='ferramenta')].nome")).containsExactly(LoopDoAgenteTest.INICIAR,
                LoopDoAgenteTest.SOLICITAR);
        assertThat(lerLista(botao, "$.itens[?(@.tipo=='cartao')]")).hasSize(1);
    }
}
