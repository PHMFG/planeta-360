package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.contexto.AtlasContextoFabrica;
import br.com.rbs.portal.atlas.loop.PlanoBSemLlm;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] O debug das ferramentas ({@code atlas.debug.ferramentas=true}) no Atlas só chat ({@code atlas.relatorio.ligado=false}):
 * além do que vale nos dois modos (DebugDeFerramentasBase), uma ferramenta do relatório chamada à força aparece como erro, e uma
 * conversa antiga mostra as chamadas do relatório que estão no histórico, ainda sem {@code cartao} nem {@code relatorio_pronto}.
 */
@TesteComLlmRoteirizado
@Transactional
@TestPropertySource(properties = {"atlas.relatorio.ligado=false", "atlas.debug.ferramentas=true"})
class DebugDeFerramentasSoChatTest extends DebugDeFerramentasBase {

    @Autowired
    PlanoBSemLlm planoB;

    @Autowired
    AtlasContextoFabrica fabrica;

    @Test
    void ferramentaDoRelatorioChamadaAForcaAparecePorErroENadaECriado() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);
        modelo.chamada(LoopDoAgenteTest.INICIAR, LoopDoAgenteTest.ARGS_INICIAR)
                .texto("O relatório ainda não está disponível nesta versão. Posso responder perguntas sobre a parceria.");

        MvcTestResult resposta = falar(pessoa, conversa, "Monte o relatório.");

        assertThat(lerLista(resposta, "$.itens[*].tipo")).containsExactly("usuario", "ferramenta", "texto");
        assertThat(lerJson(resposta, "$.itens[1].nome")).isEqualTo(LoopDoAgenteTest.INICIAR);
        assertThat(lerJson(resposta, "$.itens[1].argumentos")).isEqualTo(LoopDoAgenteTest.ARGS_INICIAR);
        assertThat(lerJson(resposta, "$.itens[1].erro")).isEqualTo(true);
        assertThat((String) lerJson(resposta, "$.itens[1].resultado")).startsWith("{\"erro\":\"Não existe a ferramenta relatorio_iniciar");
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_relatorio").query(Long.class).single()).isZero();
    }

    @Test
    void conversaAntigaMostraAsChamadasDoHistoricoSemCartaoNemRelatorioPronto() {
        SessaoDeApi pessoa = entrar(AMARELO);
        long conversa = abrirConversa(pessoa);
        planoB.montar(fabrica.para(usuario(jdbc, AMARELO), amarelo(), conversa, requisicao()), "ATIVACAO_EVENTO");

        MvcTestResult inteira = pessoa.get("/api/atlas/conversas/" + conversa);

        assertThat(lerLista(inteira, "$.itens[?(@.tipo=='ferramenta')].nome")).containsExactly(LoopDoAgenteTest.INICIAR,
                LoopDoAgenteTest.SOLICITAR);
        assertThat(lerLista(inteira, "$.itens[*].tipo")).doesNotContain("cartao", "relatorio_pronto", "aviso");
        // a do cartão ainda não foi respondida: sem resultado
        assertThat(lerJson(inteira, "$.itens[?(@.nome=='" + LoopDoAgenteTest.SOLICITAR + "')].resultado")).asList().containsOnlyNulls();
        assertThat(corpo(inteira)).doesNotContain(SENTINELA);
    }
}
