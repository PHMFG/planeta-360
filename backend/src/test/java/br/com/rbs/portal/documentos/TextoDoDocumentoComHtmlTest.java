package br.com.rbs.portal.documentos;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

import br.com.rbs.portal.TesteDeIntegracao;

/**
 * [ATLAS] Linha 12 da seção 12.2 do plano (M8), do lado do servidor: o texto de um documento que traz HTML ({@code <img onerror>},
 * {@code <script>}) vai à tela como TEXTO. O trecho sai como string de um JSON, sem reescrita e sem content type de página, e o
 * arquivo abre {@code inline} com {@code nosniff} e {@code CSP: sandbox}, como {@code text/plain}: nenhum caminho entrega o texto do
 * documento como HTML. O escape do que o visualizador desenha é do front ({@code frontend/e2e/unidade/destaque.spec.ts}, o
 * {@code customTextRenderer} escapado).
 */
@TesteDeIntegracao
@Transactional
class TextoDoDocumentoComHtmlTest extends ApoioDeDocumentos {

    @Autowired
    IngestaoService ingestao;

    private static final String ATAQUE = "<img src=x onerror=alert(1)> e <script>alert(2)</script> e [clique](javascript:alert(3))";

    @Test
    void htmlNoTextoDoDocumentoSaiComoTextoDeUmJsonEOArquivoAbreComoTextoPlanoSemPoderSobreAPagina() {
        SessaoDeApi rbs = entrar(RBS);
        byte[] conteudo = texto("DOCUMENTO FICTICIO\n\n1. Aviso de teste\nPara o destaque: " + ATAQUE + "\n");
        long id = enviarOk(rbs, "html.txt", conteudo, global("Teste de HTML no destaque", "teste-html-no-destaque"));
        assertThat(acao(rbs, id, "APROVAR")).hasStatus(HttpStatus.OK);
        ingestao.processar(id);   // dentro da transação do teste, o callback do commit não roda: a indexação é chamada à mão
        assertThat(statusDe(id)).isEqualTo("INDEXADO");
        long trecho = jdbc.sql("SELECT id FROM documento_trecho WHERE documento_id = :d AND texto LIKE '%onerror%' ORDER BY ordem LIMIT 1")
                .param("d", id).query(Long.class).single();

        for (SessaoDeApi pessoa : new SessaoDeApi[] {rbs, entrar(AMARELO)}) {
            MvcTestResult resposta = pessoa.get("/api/documentos/" + id + "/trechos/" + trecho);
            assertThat(resposta).hasStatus(HttpStatus.OK);
            assertThat(resposta.getResponse().getContentType()).startsWith("application/json");
            assertThat(resposta.getResponse().getHeaders("X-Content-Type-Options")).containsExactly("nosniff");
            assertThat((String) lerJson(resposta, "$.texto")).as("o texto vai cru, como dado: quem desenha escapa").contains(ATAQUE);

            MvcTestResult arquivo = pessoa.get("/api/documentos/" + id + "/conteudo");
            assertThat(arquivo).hasStatus(HttpStatus.OK);
            assertThat(arquivo.getResponse().getContentType()).startsWith("text/plain").doesNotContain("html");
            assertThat(arquivo.getResponse().getHeaders("X-Content-Type-Options")).containsExactly("nosniff");
            assertThat(arquivo.getResponse().getHeaders("Content-Security-Policy")).containsExactly("sandbox");
        }
    }
}
