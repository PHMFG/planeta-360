package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.conversa;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.eventos;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.falarEmFluxo;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.fim;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.nomes;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.pedido;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.ApoioDoFluxo.Evento;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * [ATLAS] Com o debug das ferramentas desligado (o padrão fora do perfil dev), a conversa diz {@code debugDeFerramentas: false} e
 * nenhuma resposta tem item nem evento {@code ferramenta}, mesmo quando o agente chama ferramentas, e o que o modelo recebe é o
 * mesmo. Vale com o relatório ligado e desligado (os dois contextos são o da propriedade abaixo e o do AtlasSoChatTest). Não é
 * {@code @Transactional}, por causa do fluxo (veja o ApoioDoFluxo).
 */
@TesteComLlmRoteirizado
@TestPropertySource(properties = {"atlas.relatorio.ligado=true", "atlas.debug.ferramentas=false"})
class DebugDeFerramentasDesligadoTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProvedorRoteirizado modelo;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void guardarOQueJaExiste() {
        modelo.limpar();
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagarOQueOTesteConfirmou() {
        limpeza.apagar();
    }

    @Test
    void semDebugNaoHaItemNemEventoFerramenta() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long emJson = conversa(pessoa, jdbc);
        modelo.chamada(LoopDoAgenteTest.CONSULTAR, "{}").texto("Os números de {{parc:patrocinador}} estão no portal.");

        MvcTestResult json = pessoa.post("/api/atlas/conversas/" + emJson + "/mensagens", "{\"texto\":\"Como estou?\"}");

        assertThat(json).hasStatus(HttpStatus.OK);
        assertThat(lerJson(json, "$.debugDeFerramentas")).isEqualTo(false);
        assertThat(lerLista(json, "$.itens[*].tipo")).containsExactly("usuario", "texto");
        assertThat(corpo(json)).doesNotContain("ferramenta", "argumentos", "duracaoMs");
        List<String> vistoPeloModelo = modelo.ultimoPedido().mensagens().stream().filter(m -> "tool".equals(m.papel())).map(m -> m.conteudo())
                .toList();
        assertThat(vistoPeloModelo).hasSize(1).allSatisfy(c -> assertThat(c).contains("\"dados\""));
        assertThat(lerJson(pessoa.get("/api/atlas/conversas/" + emJson), "$.debugDeFerramentas")).isEqualTo(false);

        long emFluxo = conversa(pessoa, jdbc);
        modelo.chamadas(null, null, pedido(LoopDoAgenteTest.CONSULTAR, "{}")).texto("Os números de {{parc:patrocinador}} estão no portal.");
        MvcTestResult fluxo = falarEmFluxo(pessoa, emFluxo, "Como estou?");

        List<Evento> eventos = eventos(fluxo);
        assertThat(nomes(eventos)).contains("etapa").doesNotContain("ferramenta");
        assertThat(fim(eventos).<Boolean>ler("$.debugDeFerramentas")).isFalse();
        assertThat(fim(eventos).<List<String>>ler("$.itens[*].tipo")).containsExactly("usuario", "texto");
    }
}
