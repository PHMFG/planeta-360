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
                .isEqualTo("Categoria inválida. Use uma destas: DOCUMENTO_INGRESSOS, MATERIAL_MARCA, MATERIAL_MIDIA, OUTRO.");
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
    void aCategoriaDocumentoEDosDocumentosDoAtlasERotaDasEntregasContinuaRecusandoComoInvalida() {
        SessaoDeApi rbs = entrar(RBS);
        long entrega = criarEntrega(rbs, amarelo(), "MIDIA", "VT");

        for (SessaoDeApi pessoa : new SessaoDeApi[] {rbs, entrar(AMARELO)}) {
            MvcTestResult resposta = enviar(pessoa, entrega, "doc.pdf", pdf("x"), "DOCUMENTO", null);

            assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat((String) lerJson(resposta, "$.campos.categoria")).startsWith("Categoria inválida. Use uma destas:").doesNotContain("DOCUMENTO,")
                    .doesNotContain("DOCUMENTO.");
        }
        assertThat(contar("SELECT count(*) FROM arquivo WHERE categoria = 'DOCUMENTO' AND entrega_id IS NOT NULL")).isZero();
    }

    @Test
    void txtContinuaRecusadoNaRota17ComAListaDosEnviosComuns() {
        SessaoDeApi rbs = entrar(RBS);
        long entrega = criarEntrega(rbs, amarelo(), "MIDIA", "VT");
        byte[] texto = "uma nota em texto\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        for (SessaoDeApi pessoa : new SessaoDeApi[] {rbs, entrar(AMARELO)}) {
            MvcTestResult resposta = enviar(pessoa, entrega, "nota.txt", texto);

            assertThat(resposta).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            assertThat(lerJson(resposta, "$.detail"))
                    .isEqualTo("Este tipo de arquivo não é aceito. Envie um destes: pdf, png, jpg, jpeg, doc, docx, xls, xlsx, ppt, pptx, csv, mp4, mp3, zip.");
        }
    }

    @Test
    void categoriaQueExisteMasEDaRbsContinuaDando403ParaAMarca() {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "VT");

        MvcTestResult resposta = enviar(entrar(AMARELO), entrega, "doc.pdf", pdf("x"), "COMPROVACAO", null);

        assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("A categoria COMPROVACAO é só da RBS. Use DOCUMENTO_INGRESSOS, "
                + "MATERIAL_MARCA, MATERIAL_MIDIA, OUTRO.");
    }

    /** R5.7.b: o contrato assinado é da RBS. A marca não o vê entre as categorias do envio e recebe 403 se o pedir. */
    @Test
    void categoriaContratoSaiuDaListaDaMarcaEContinuaNaDaRbs() {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "VT");

        MvcTestResult daMarca = enviar(entrar(AMARELO), entrega, "doc.pdf", pdf("x"), "CONTRATO", null);

        assertThat(daMarca).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(daMarca, "$.detail")).isEqualTo("A categoria CONTRATO é só da RBS. Use DOCUMENTO_INGRESSOS, "
                + "MATERIAL_MARCA, MATERIAL_MIDIA, OUTRO.");
        assertThat(lerJson(enviar(entrar(AMARELO), entrega, "doc.pdf", pdf("x"), "INVENTADA", null), "$.campos.categoria").toString())
                .doesNotContain("CONTRATO");
    }
}
