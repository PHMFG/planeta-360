package br.com.rbs.portal.materiais;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Testa o download de arquivos (rota 18): os mesmos bytes, sempre como anexo, isolamento entre parcerias e o histórico DOWNLOAD.
 * Atende o item 4 da Entrega 2. [UPLOAD] [ISOLAMENTO] [HISTORICO] Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class DownloadTest extends ApoioDeMateriais {

    /** Sobe um PDF do Amarelo numa entrega de mídia e devolve o id do arquivo. */
    long arquivoDoAmarelo(String nome, byte[] bytes) {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "VT para download");
        MvcTestResult envio = enviar(entrar(AMARELO), entrega, nome, bytes);
        assertThat(envio).hasStatus(HttpStatus.CREATED);
        return id(envio);
    }

    @Test
    void downloadDevolveOsMesmosBytesComoAnexoComNosniffEGravaDownload() {
        byte[] bytes = pdf("conteúdo exato do arquivo");
        long arquivo = arquivoDoAmarelo("logo_final.pdf", bytes);

        MvcTestResult resposta = entrar(AMARELO).get("/api/arquivos/" + arquivo);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(resposta.getResponse().getContentAsByteArray()).isEqualTo(bytes);
        assertThat(resposta.getResponse().getHeader("Content-Type")).isEqualTo("application/pdf");
        assertThat(resposta.getResponse().getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"logo_final.pdf\"; filename*=UTF-8''logo_final.pdf");
        assertThat(resposta.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(resposta.getResponse().getHeader("Content-Length")).isEqualTo(String.valueOf(bytes.length));

        // [HISTORICO] DOWNLOAD na entidade arquivo, com quem baixou, a parceria, o IP e o navegador.
        List<Map<String, Object>> download = historico("DOWNLOAD", "arquivo", arquivo);
        assertThat(download).hasSize(1);
        assertThat(((Number) download.getFirst().get("usuario_id")).longValue()).isEqualTo(usuarioId(AMARELO));
        assertThat(((Number) download.getFirst().get("parceria_id")).longValue()).isEqualTo(amarelo());
        assertThat(download.getFirst().get("valor_novo")).isEqualTo("logo_final.pdf (versão 1)");
        assertThat(download.getFirst().get("ip")).isEqualTo("127.0.0.1");
    }

    @Test
    void nomeComAcentoEEspacoVaiCodificadoEmUtf8EACadaDownloadHaUmaLinhaNova() {
        long arquivo = arquivoDoAmarelo("Relatório final — v2.pdf", pdf("x"));
        SessaoDeApi amarelo = entrar(AMARELO);

        MvcTestResult primeiro = amarelo.get("/api/arquivos/" + arquivo);
        MvcTestResult segundo = entrar(RBS).get("/api/arquivos/" + arquivo);

        // O nome sem acento (filename=) serve a navegadores antigos; o filename* leva o nome certo, em UTF-8.
        assertThat(primeiro.getResponse().getHeader("Content-Disposition")).startsWith("attachment; filename=\"Relatorio final _ v2.pdf\"; ")
                .endsWith("filename*=UTF-8''Relat%C3%B3rio%20final%20%E2%80%94%20v2.pdf");
        assertThat(segundo).hasStatus(HttpStatus.OK);
        assertThat(historico("DOWNLOAD", "arquivo", arquivo)).extracting(l -> ((Number) l.get("usuario_id")).longValue())
                .containsExactly(usuarioId(AMARELO), usuarioId(RBS));
    }

    @Test
    void csvVaiComTipoTextCsvEMesmoSendoTextoNuncaAbreNaPagina() {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "Planilha");
        MvcTestResult envio = enviar(entrar(AMARELO), entrega, "dados.csv", "<script>alert(1)</script>;1\n".getBytes());

        MvcTestResult resposta = entrar(AMARELO).get("/api/arquivos/" + id(envio));

        assertThat(resposta.getResponse().getHeader("Content-Type")).isEqualTo("text/csv");
        assertThat(resposta.getResponse().getHeader("Content-Disposition")).startsWith("attachment;");
        assertThat(resposta.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void roxoBaixandoArquivoDoAmareloRecebe404IgualAArquivoInexistenteESemGravarDownload() {
        long arquivo = arquivoDoAmarelo("logo_final.pdf", pdf("secreto"));
        SessaoDeApi roxo = entrar(ROXO);

        MvcTestResult doAmarelo = roxo.get("/api/arquivos/" + arquivo);
        MvcTestResult inexistente = roxo.get("/api/arquivos/999999");

        assertThat(doAmarelo).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(inexistente).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(doAmarelo, "$.detail")).isEqualTo(lerJson(inexistente, "$.detail"));
        assertThat(corpo(doAmarelo)).doesNotContain("secreto").doesNotContain("Amarelo");
        assertThat(historico("DOWNLOAD", "arquivo", arquivo)).isEmpty();
    }

    @Test
    void arquivoGeralDaRbsSemParceriaDa404ParaOPatrocinadorEBaixaParaARbs() {
        long idDoGeral = armazenamento.gravar(pdf("fonte do festival"), "PV_PA_26_GERAL.pdf", "PESQUISA", usuarioId(RBS), null).id();

        MvcTestResult doPatrocinador = entrar(AMARELO).get("/api/arquivos/" + idDoGeral);
        MvcTestResult daRbs = entrar(RBS).get("/api/arquivos/" + idDoGeral);

        assertThat(doPatrocinador).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(daRbs).hasStatus(HttpStatus.OK);
        assertThat(daRbs.getResponse().getContentAsByteArray()).isEqualTo(pdf("fonte do festival"));
        List<Map<String, Object>> download = historico("DOWNLOAD", "arquivo", idDoGeral);
        assertThat(download).hasSize(1);
        assertThat(download.getFirst().get("parceria_id")).as("arquivo geral não tem parceria").isNull();
    }

    @Test
    void arquivoQueSumiuDoArmazenamentoDa404ESemDownloadNoHistorico() {
        // Os arquivos da amostra (logo_final.pdf) estão só no banco, não no disco.
        long logoV2 = jdbc.sql("SELECT id FROM arquivo WHERE nome_original = 'logo_final.pdf' AND versao = 2").query(Long.class).single();
        long downloadsAntes = contar("SELECT count(*) FROM historico WHERE acao = 'DOWNLOAD' AND entidade_id = ?", logoV2);

        MvcTestResult resposta = entrar(AMARELO).get("/api/arquivos/" + logoV2);

        assertThat(resposta).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(contar("SELECT count(*) FROM historico WHERE acao = 'DOWNLOAD' AND entidade_id = ?", logoV2)).isEqualTo(downloadsAntes);
    }

    @Test
    void caminhoGravadoQueTentaSairDaPastaNuncaEntregaArquivoDeFora() {
        long arquivo = arquivoDoAmarelo("logo_final.pdf", pdf("x"));
        jdbc.sql("UPDATE arquivo SET caminho = '../../pom.xml' WHERE id = :id").param("id", arquivo).update();

        MvcTestResult resposta = entrar(AMARELO).get("/api/arquivos/" + arquivo);

        assertThat(resposta).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(corpo(resposta)).doesNotContain("<project");
    }

    @Test
    void pdfDeRelatorioQueAindaNaoFoiDisponibilizadoNaoChegaAoPatrocinador() {
        long relatorio = jdbc.sql("SELECT id FROM relatorio WHERE parceria_id = :p AND versao = 1").param("p", amarelo()).query(Long.class).single();
        long pdfDoRelatorio = armazenamento.gravar(pdf("relatório"), "relatorio.pdf", "RELATORIO_PARCERIA", usuarioId(RBS), amarelo()).id();
        jdbc.sql("UPDATE relatorio SET arquivo_id = :a WHERE id = :r").param("a", pdfDoRelatorio).param("r", relatorio).update();

        MvcTestResult doPatrocinador = entrar(AMARELO).get("/api/arquivos/" + pdfDoRelatorio);
        MvcTestResult daRbs = entrar(RBS).get("/api/arquivos/" + pdfDoRelatorio);

        assertThat(doPatrocinador).as("o relatório é um rascunho").hasStatus(HttpStatus.NOT_FOUND);
        assertThat(daRbs).hasStatus(HttpStatus.OK);
    }

    @Test
    void downloadExigeSessao() {
        assertThat(SessaoDeApi.anonima(mvc).get("/api/arquivos/1")).hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
