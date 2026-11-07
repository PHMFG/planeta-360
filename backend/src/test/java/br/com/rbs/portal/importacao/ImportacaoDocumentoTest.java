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
        jdbc.sql("DELETE FROM veiculacao WHERE importacao_linha_id IS NOT NULL").update();
        jdbc.sql("DELETE FROM importacao").update();
        jdbc.sql("DELETE FROM documento_trecho WHERE documento_id IN "
                + "(SELECT id FROM documento WHERE chave LIKE 'importacao-%')").update();
        jdbc.sql("DELETE FROM documento WHERE chave LIKE 'importacao-%'").update();
        jdbc.sql("DELETE FROM stg_veiculacao WHERE carga_id IN (SELECT id FROM carga WHERE arquivo_id IN "
                + "(SELECT id FROM arquivo WHERE categoria = 'IMPORTACAO'))").update();
        jdbc.sql("DELETE FROM carga WHERE arquivo_id IN (SELECT id FROM arquivo WHERE categoria = 'IMPORTACAO')").update();
        jdbc.sql("DELETE FROM arquivo WHERE categoria = 'IMPORTACAO'").update();
        jdbc.sql("DELETE FROM arquivo WHERE categoria = 'DOCUMENTO' AND nome_original IN "
                + "('rbs-segredo.txt', 'rbs-veiculacao.csv', 'rbs-nota.txt', 'agencia.csv')").update();
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
        return ((Number) lerJson(criada, "$.id")).longValue();
    }

    private void confirmar(SessaoDeApi rbs, long importacao) {
        assertThat(rbs.post("/api/importacoes/" + importacao + "/confirmar", "{}")).hasStatus(HttpStatus.OK);
    }

    /** Uma importação de veiculação com o segredo numa coluna que a extração ignora, mas que fica no texto do documento. */
    private long enviarVeiculacao(SessaoDeApi rbs) {
        var plano = jdbc.sql("""
                        SELECT ap.veiculo, ap.formato, ap.espaco_programa FROM aproveitamento_previsto ap
                        JOIN parceria pa ON pa.id = ap.parceria_id JOIN patrocinador p ON p.id = pa.patrocinador_id
                        JOIN evento e ON e.id = pa.evento_id
                        WHERE p.nome_canonico = 'Cliente Amarelo' AND e.edicao = 2027 AND ap.categoria = 'MIDIA'
                          AND ap.espaco_programa IS NOT NULL AND ap.formato !~ '[",]' AND ap.espaco_programa !~ '[",]'
                          AND NOT EXISTS (SELECT 1 FROM veiculacao v WHERE v.aproveitamento_id = ap.id AND v.periodo = 'MES')
                        ORDER BY ap.id LIMIT 1""").query().singleRow();
        String csv = "patrocinador,veiculo,formato,espaco_programa,exibido_em,quantidade,link,observacao\n"
                + "Cliente Amarelo," + plano.get("veiculo") + "," + plano.get("formato") + "," + plano.get("espaco_programa")
                + ",2026-12-10T15:00:00-03:00,1,," + SEGREDO + "\n";
        var csrf = ApoioDeTeste.pedirCsrf(mvc);
        MvcTestResult criada = mvc.post().uri("/api/importacoes").multipart()
                .file(new MockMultipartFile("arquivo", "rbs-veiculacao.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                .param("eventoId", String.valueOf(evento2027())).param("alcance", "EDICAO").param("destino", "VEICULACAO")
                .session(rbs.sessao()).cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token()).exchange();
        assertThat(criada).hasStatus(HttpStatus.ACCEPTED);
        long id = ((Number) lerJson(criada, "$.id")).longValue();
        assertThat(rbs.patch("/api/importacoes/" + id + "/linhas", "{\"situacao\":\"ACEITA\",\"todas\":true}"))
                .hasStatus(HttpStatus.OK);
        return id;
    }

    private String statusDoDocumento(long documento) {
        return jdbc.sql("SELECT status FROM documento WHERE id = :id").param("id", documento).query(String.class).single();
    }

    private Escopo doAmarelo() {
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        return jdbc.sql("SELECT evento_id, cota_tipo_id, id FROM parceria WHERE id = :p").param("p", parceria)
                .query((rs, n) -> new Escopo(rs.getLong(1), rs.getInt(2), rs.getLong(3))).single();
    }

    private ResultadoDaBusca buscar(Escopo escopo) {
        return busca.buscar(new Pedido(escopo, SEGREDO, null, BuscaHibrida.K_PADRAO, null, null));
    }

    private long enviarRbsNaParceria(SessaoDeApi rbs, long parceria) {
        var csrf = ApoioDeTeste.pedirCsrf(mvc);
        MvcTestResult criada = mvc.post().uri("/api/importacoes").multipart()
                .file(new MockMultipartFile("arquivo", "rbs-nota.txt", "text/plain", TEXTO.getBytes(StandardCharsets.UTF_8)))
                .param("eventoId", String.valueOf(evento2027())).param("alcance", "PARCERIA")
                .param("parceriaId", String.valueOf(parceria)).param("destino", "SO_CONSULTA")
                .session(rbs.sessao()).cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token()).exchange();
        assertThat(criada).hasStatus(HttpStatus.ACCEPTED);
        return ((Number) lerJson(criada, "$.id")).longValue();
    }

    @Test
    void oArquivoDaImportacaoDaRbsNaParceriaNaoSaiParaAMarca() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        long importacao = enviarRbsNaParceria(rbs, parceria);
        long arquivo = ((Number) lerJson(rbs.get("/api/importacoes/" + importacao), "$.arquivo.id")).longValue();

        assertThat(amarelo.get("/api/arquivos/" + arquivo)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(rbs.get("/api/arquivos/" + arquivo)).hasStatus(HttpStatus.OK);
    }

    @Test
    void oArquivoDaPlanilhaDaAgenciaSaiParaAPropriaMarca() {
        SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        var csrf = ApoioDeTeste.pedirCsrf(mvc);
        byte[] planilha = "campo,valor\nativacao.visitantes_total,10\n".getBytes(StandardCharsets.UTF_8);
        MvcTestResult criada = mvc.post().uri("/api/parcerias/" + parceria + "/importacoes").multipart()
                .file(new MockMultipartFile("arquivo", "agencia.csv", "text/csv", planilha))
                .session(amarelo.sessao()).cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token()).exchange();
        assertThat(criada).hasStatus(HttpStatus.ACCEPTED);
        long importacao = ((Number) lerJson(criada, "$.id")).longValue();
        long arquivo = ((Number) lerJson(amarelo.get("/api/importacoes/" + importacao), "$.arquivo.id")).longValue();

        assertThat(amarelo.get("/api/arquivos/" + arquivo)).hasStatus(HttpStatus.OK);
        assertThat(SessaoDeApi.entrar(mvc, RBS).get("/api/arquivos/" + arquivo)).hasStatus(HttpStatus.OK);
    }

    @Test
    void oDocumentoDaImportacaoDaRbsNaoChegaAMarcaNemPelaBuscaNemPeloVisualizador() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        SessaoDeApi amarelo = SessaoDeApi.entrar(mvc, AMARELO);
        long importacao = enviar(rbs);
        confirmar(rbs, importacao);
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

    @Test
    void oDocumentoSoEntraNoAtlasDepoisDeConfirmarERetirarOTira() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long importacao = enviarVeiculacao(rbs);
        long documento = ((Number) lerJson(rbs.get("/api/importacoes/" + importacao), "$.documentoId")).longValue();

        assertThat(statusDoDocumento(documento)).as("a prévia não indexa o documento").isEqualTo("AGUARDANDO_APROVACAO");
        assertThat(buscar(Escopo.todaAEdicao(evento2027())).textual())
                .noneMatch(c -> c.documentoId() == documento);

        confirmar(rbs, importacao);
        assertThat(statusDoDocumento(documento)).isEqualTo("INDEXADO");
        assertThat(buscar(Escopo.todaAEdicao(evento2027())).textual())
                .anyMatch(c -> c.documentoId() == documento);

        assertThat(rbs.post("/api/importacoes/" + importacao + "/retirar", "{}")).hasStatus(HttpStatus.OK);
        assertThat(statusDoDocumento(documento)).isEqualTo("REVOGADO");
        assertThat(jdbc.sql("SELECT count(*) FROM documento_trecho WHERE documento_id = :d AND ativo").param("d", documento)
                .query(Long.class).single()).isZero();
        assertThat(buscar(Escopo.todaAEdicao(evento2027())).textual())
                .noneMatch(c -> c.documentoId() == documento);
    }

    @Test
    void descartarRecusaODocumentoDaPrevia() {
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        long importacao = enviar(rbs);
        long documento = ((Number) lerJson(rbs.get("/api/importacoes/" + importacao), "$.documentoId")).longValue();

        assertThat(rbs.post("/api/importacoes/" + importacao + "/descartar", "{}")).hasStatus(HttpStatus.OK);
        assertThat(statusDoDocumento(documento)).isEqualTo("RECUSADO");
        assertThat(buscar(Escopo.todaAEdicao(evento2027())).textual())
                .noneMatch(c -> c.documentoId() == documento);
    }
}
