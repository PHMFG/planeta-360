package br.com.rbs.portal.materiais;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [VALIDACAO] [PERMISSAO] A mensagem de categoria inexistente (rota 17) lista só as categorias que o perfil de quem enviou
 * pode usar: a marca não fica sabendo que existem categorias só da RBS. Atende o item 4 da Entrega 2.
 * Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class CategoriaDoEnvioTest extends ApoioDeMateriais {

    @Test
    void marcaVeSoAsCategoriasQuePodeUsar() {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "VT");

        MvcTestResult resposta = enviar(entrar(AMARELO), entrega, "doc.pdf", pdf("x"), "INVENTADA", null);

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(resposta, "$.campos.categoria"))
                .isEqualTo("Categoria inválida. Use uma destas: CONTRATO, DOCUMENTO_INGRESSOS, MATERIAL_MARCA, MATERIAL_MIDIA, OUTRO.");
        for (String soDaRbs : new String[] {"COMPROVACAO", "PESQUISA", "PLANO_COMERCIAL", "RELATORIO_PARCERIA", "RELATORIO_VEICULACAO"}) {
            assertThat((String) lerJson(resposta, "$.campos.categoria")).as(soDaRbs).doesNotContain(soDaRbs);
        }
    }

    @Test
    void rbsVeTodasAsCategorias() {
        SessaoDeApi rbs = entrar(RBS);
        long entrega = criarEntrega(rbs, amarelo(), "MIDIA", "VT");

        MvcTestResult resposta = enviar(rbs, entrega, "doc.pdf", pdf("x"), "INVENTADA", null);

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(resposta, "$.campos.categoria")).isEqualTo("Categoria inválida. Use uma destas: COMPROVACAO, CONTRATO, "
                + "DOCUMENTO_INGRESSOS, MATERIAL_MARCA, MATERIAL_MIDIA, OUTRO, PESQUISA, PLANO_COMERCIAL, RELATORIO_PARCERIA, "
                + "RELATORIO_VEICULACAO.");
    }

    @Test
    void categoriaQueExisteMasEDaRbsContinuaDando403ParaAMarca() {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "VT");

        MvcTestResult resposta = enviar(entrar(AMARELO), entrega, "doc.pdf", pdf("x"), "COMPROVACAO", null);

        assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("A categoria COMPROVACAO é só da RBS. Use CONTRATO, "
                + "DOCUMENTO_INGRESSOS, MATERIAL_MARCA, MATERIAL_MIDIA, OUTRO.");
    }
}
