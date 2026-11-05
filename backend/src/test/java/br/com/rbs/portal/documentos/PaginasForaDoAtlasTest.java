package br.com.rbs.portal.documentos;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] [PERMISSAO] [VALIDACAO] [R2.1] As páginas fora do Atlas (rota 54, parte {@code paginasForaDoAtlas}, e a rota 103): a marca não
 * marca (403 antes do corpo); página fora do intervalo, repetida, zero, a mais que o limite, todas as páginas e texto que não é número
 * dão 422 e nada é gravado; só se marca enquanto o documento aguarda aprovação (409); desmarcar traz a suspeita de volta; e o
 * visualizador (rotas 59 e 60) entrega o PDF inteiro, com a página marcada, para a RBS e para a marca. Cada teste é desfeito no fim.
 */
@TesteDeIntegracao
@Transactional
class PaginasForaDoAtlasTest extends ApoioDeDocumentos {

    @Autowired
    IngestaoService ingestao;

    private long enviarManual(SessaoDeApi rbs, String chave) throws Exception {
        return enviarOk(rbs, "manual.pdf", DocumentoComPaginaDeContatosTest.manualDeTresPaginas(), global("Manual de teste", chave));
    }

    private static MvcTestResult marcar(SessaoDeApi pessoa, long id, String corpo) {
        return pessoa.patch("/api/documentos/" + id + "/paginas-fora-do-atlas", corpo);
    }

    @Test
    void aMarcaNaoMarcaPaginas() throws Exception {
        SessaoDeApi rbs = entrar(RBS);
        SessaoDeApi marca = entrar(AMARELO);
        long id = enviarManual(rbs, "teste-marca");

        for (String corpo : new String[] {"{\"paginasForaDoAtlas\":[2]}", "{}", "isto nao e json", ""}) {
            MvcTestResult resposta = marcar(marca, id, corpo);
            assertThat(resposta).as("corpo [" + corpo + "]").hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo(SO_RBS);
        }
        Map<String, String> campos = global("Manual da marca", "teste-marca-envio");
        campos.put("paginasForaDoAtlas", "2");
        assertThat(enviar(marca, "manual.pdf", DocumentoComPaginaDeContatosTest.manualDeTresPaginas(), campos)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(contar("SELECT count(*) FROM documento WHERE chave = 'teste-marca-envio'")).isZero();
        assertThat(contar("SELECT cardinality(paginas_ignoradas) FROM documento WHERE id = ?", id)).isZero();
        assertThat(marcar(SessaoDeApi.anonima(mvc), id, "{\"paginasForaDoAtlas\":[2]}")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void paginaForaDoIntervaloOuListaInvalidaDa422ENadaMuda() throws Exception {
        SessaoDeApi rbs = entrar(RBS);
        long id = enviarManual(rbs, "teste-422");

        for (String corpo : new String[] {"{\"paginasForaDoAtlas\":[9]}", "{\"paginasForaDoAtlas\":[4]}", "{\"paginasForaDoAtlas\":[0]}",
                "{\"paginasForaDoAtlas\":[-1]}", "{\"paginasForaDoAtlas\":[2,2]}", "{\"paginasForaDoAtlas\":[1,2,3]}",
                "{\"paginasForaDoAtlas\":[1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21]}", "{\"paginasForaDoAtlas\":null}", "{}"}) {
            MvcTestResult resposta = marcar(rbs, id, corpo);
            assertThat(resposta).as(corpo).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(mapa(resposta, "$.campos")).as(corpo).containsKey("paginasForaDoAtlas");
        }
        assertThat(contar("SELECT cardinality(paginas_ignoradas) FROM documento WHERE id = ?", id)).isZero();
        assertThat(contar("SELECT count(*) FROM historico WHERE entidade = 'documento' AND entidade_id = ? AND acao = 'EDICAO'", id)).isZero();
        assertThat(marcar(rbs, 987654321L, "{\"paginasForaDoAtlas\":[2]}")).hasStatus(HttpStatus.NOT_FOUND);

        // no envio: o mesmo, e o documento nem chega a ser gravado
        for (String texto : new String[] {"9", "0", "2,2", "1,2,3", "dois", "2;3", "2,,3", "-1"}) {
            Map<String, String> campos = global("Manual com páginas ruins", "teste-422-envio");
            campos.put("paginasForaDoAtlas", texto);
            MvcTestResult resposta = enviar(rbs, "manual.pdf", DocumentoComPaginaDeContatosTest.manualDeTresPaginas(), campos);
            assertThat(resposta).as("paginasForaDoAtlas=" + texto).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(mapa(resposta, "$.campos")).as(texto).containsKey("paginasForaDoAtlas");
        }
        assertThat(contar("SELECT count(*) FROM documento WHERE chave = 'teste-422-envio'")).isZero();
    }

    @Test
    void soSeMarcaEnquantoAguardaAprovacaoEDesmarcarTrazASuspeitaDeVolta() throws Exception {
        SessaoDeApi rbs = entrar(RBS);
        long id = enviarManual(rbs, "teste-409");

        MvcTestResult marcado = marcar(rbs, id, "{\"paginasForaDoAtlas\":[2]}");
        assertThat(marcado).hasStatus(HttpStatus.OK);
        assertThat(lerJson(marcado, "$.contemDadosPessoais")).isEqualTo(false);
        // marcar de novo a mesma lista é permitido (a lista inteira é trocada)
        assertThat(marcar(rbs, id, "{\"paginasForaDoAtlas\":[2]}")).hasStatus(HttpStatus.OK);

        MvcTestResult limpo = marcar(rbs, id, "{\"paginasForaDoAtlas\":[]}");
        assertThat(limpo).hasStatus(HttpStatus.OK);
        assertThat(lerJson(limpo, "$.contemDadosPessoais")).isEqualTo(true);
        assertThat(lista(limpo, "$.paginasSuspeitas")).containsExactly(2);
        assertThat(lista(limpo, "$.paginasForaDoAtlas")).isEmpty();
        assertThat(lista(limpo, "$.acoes")).containsExactly("RECUSAR");

        // outra página que não é a dos contatos: continua travado, e a suspeita segue citando a página 2
        MvcTestResult outra = marcar(rbs, id, "{\"paginasForaDoAtlas\":[3]}");
        assertThat(outra).hasStatus(HttpStatus.OK);
        assertThat(lerJson(outra, "$.contemDadosPessoais")).isEqualTo(true);
        assertThat(lista(outra, "$.paginasSuspeitas")).containsExactly(2);
        assertThat(lista(outra, "$.paginasForaDoAtlas")).containsExactly(3);

        marcar(rbs, id, "{\"paginasForaDoAtlas\":[2]}");
        assertThat(acao(rbs, id, "APROVAR")).hasStatus(HttpStatus.OK);
        MvcTestResult depoisDeAprovar = marcar(rbs, id, "{\"paginasForaDoAtlas\":[]}");
        assertThat(depoisDeAprovar).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(depoisDeAprovar, "$.detail").toString()).startsWith("As páginas fora do Atlas só mudam enquanto o documento aguarda aprovação.");
        assertThat(contar("SELECT paginas_ignoradas[1] FROM documento WHERE id = ?", id)).isEqualTo(2);

        ingestao.processar(id);
        assertThat(marcar(rbs, id, "{\"paginasForaDoAtlas\":[1]}")).hasStatus(HttpStatus.CONFLICT);
        assertThat(lista(rbs.get("/api/documentos/" + id), "$.paginasForaDoAtlas")).containsExactly(2);
    }

    @Test
    void oVisualizadorEntregaOPdfInteiroMesmoComAPaginaMarcada() throws Exception {
        SessaoDeApi rbs = entrar(RBS);
        SessaoDeApi marca = entrar(AMARELO);
        byte[] original = DocumentoComPaginaDeContatosTest.manualDeTresPaginas();
        long id = enviarOk(rbs, "manual.pdf", original, global("Manual no visualizador", "teste-visualizador"));
        assertThat(marcar(rbs, id, "{\"paginasForaDoAtlas\":[2]}")).hasStatus(HttpStatus.OK);

        MvcTestResult daRbs = rbs.get("/api/documentos/" + id + "/conteudo");
        assertThat(daRbs).hasStatus(HttpStatus.OK);
        assertThat(daRbs.getResponse().getContentAsByteArray()).isEqualTo(original);
        try (PDDocument pdf = Loader.loadPDF(daRbs.getResponse().getContentAsByteArray())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(3);
        }

        // depois de indexado, a marca abre o mesmo PDF inteiro; a rota 56 dela continua sem as páginas marcadas nem a suspeita
        assertThat(acao(rbs, id, "APROVAR")).hasStatus(HttpStatus.OK);
        ingestao.processar(id);
        MvcTestResult daMarca = marca.get("/api/documentos/" + id + "/conteudo");
        assertThat(daMarca).hasStatus(HttpStatus.OK);
        assertThat(daMarca.getResponse().getContentAsByteArray()).isEqualTo(original);
        MvcTestResult ver = marca.get("/api/documentos/" + id);
        assertThat(ver).hasStatus(HttpStatus.OK);
        assertThat(lerJson(ver, "$.paginas")).isEqualTo(3);
        String corpo = ver.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(corpo).doesNotContain("paginasForaDoAtlas").doesNotContain("paginasSuspeitas").doesNotContain("suspeitas");
    }
}
