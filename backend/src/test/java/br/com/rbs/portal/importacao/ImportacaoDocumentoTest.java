package br.com.rbs.portal.importacao;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.ApoioDeTeste;
import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.RoteiroDeLlmConfig;
import br.com.rbs.portal.atlas.busca.BuscaHibrida;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.Escopo;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.Pedido;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.ResultadoDaBusca;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * O documento que a importação cria no Atlas: o da RBS não chega a marca nenhuma, e o da própria marca fica só na parceria dela.
 * A busca roda como {@code atlas_ro}, o mesmo caminho do Atlas da marca; a RBS busca a edição inteira.
 */
@TesteDeIntegracao
@Import(RoteiroDeLlmConfig.class)
class ImportacaoDocumentoTest {

    private static final String RBS = "b2b@rbs.example";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String SEGREDO = "Orquídea Furtiva";
    private static final String TEXTO = "O código " + SEGREDO + " é interno da RBS e não sai da edição.";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    BuscaHibrida busca;

    @AfterEach
    void limpar() {
        jdbc.sql("DELETE FROM importacao").update();
        jdbc.sql("DELETE FROM documento_trecho WHERE documento_id IN "
                + "(SELECT id FROM documento WHERE chave LIKE 'importacao-%')").update();
        jdbc.sql("DELETE FROM documento WHERE chave LIKE 'importacao-%'").update();
        jdbc.sql("DELETE FROM arquivo WHERE categoria = 'IMPORTACAO'").update();
        jdbc.sql("DELETE FROM arquivo WHERE categoria = 'DOCUMENTO' AND nome_original = 'rbs-segredo.txt'").update();
    }

    private long evento2027() {
        return jdbc.sql("SELECT id FROM evento WHERE edicao = 2027").query(Long.class).single();
    }

    private long enviar(SessaoDeApi rbs) {
        var csrf = ApoioDeTeste.pedirCsrf(mvc);
        MvcTestResult criada = mvc.post().uri("/api/importacoes").multipart()
                .file(new MockMultipartFile("arquivo", "rbs-segredo.txt", "text/plain", TEXTO.getBytes(StandardCharsets.UTF_8)))
                .param("eventoId", String.valueOf(evento2027())).param("alcance", "EDICAO").param("destino", "SO_CONSULTA")
                .session(rbs.sessao()).cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token()).exchange();
        assertThat(criada).hasStatus(HttpStatus.ACCEPTED);
        long id = ((Number) lerJson(criada, "$.id")).longValue();
        assertThat(rbs.post("/api/importacoes/" + id + "/confirmar", "{}")).hasStatus(HttpStatus.OK);
        return id;
    }

    private Escopo doAmarelo() {
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        return jdbc.sql("SELECT evento_id, cota_tipo_id, id FROM parceria WHERE id = :p").param("p", parceria)
                .query((rs, n) -> new Escopo(rs.getLong(1), rs.getInt(2), rs.getLong(3))).single();
    }

    private ResultadoDaBusca buscar(Escopo escopo) {
        return busca.buscar(new Pedido(escopo, SEGREDO, null, BuscaHibrida.K_PADRAO, null, null));
    }

    @Test
    void oDocumentoDaImportacaoDaRbsNaoChegaAMarcaNemPelaBuscaNemPeloVisualizador() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);
        long importacao = enviar(rbs);
        long documento = ((Number) lerJson(rbs.get("/api/importacoes/" + importacao), "$.documentoId")).longValue();

        assertThat(buscar(doAmarelo()).textual()).as("a busca da marca não acha o documento da RBS")
                .noneMatch(c -> c.documentoId() == documento);
        assertThat(amarelo.get("/api/documentos/" + documento)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(amarelo.get("/api/documentos/" + documento + "/conteudo")).hasStatus(HttpStatus.NOT_FOUND);

        assertThat(buscar(Escopo.todaAEdicao(evento2027())).textual()).as("o Atlas da RBS continua achando o documento")
                .anyMatch(c -> c.documentoId() == documento);
        assertThat(rbs.get("/api/documentos/" + documento)).hasStatus(HttpStatus.OK);
        assertThat(rbs.get("/api/documentos/" + documento + "/conteudo")).hasStatus(HttpStatus.OK);
    }
}
