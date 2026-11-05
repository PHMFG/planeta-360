package br.com.rbs.portal.materiais;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Testa o envio de arquivos (rota 17) como o front faz: multipart com sessão de verdade e CSRF. Atende o item 4 da Entrega 2.
 * [UPLOAD] [ISOLAMENTO] [PERMISSAO] [HISTORICO] Cada teste é desfeito no fim (@Transactional); os arquivos gravados em
 * target/armazenamento-teste são apagados quando a transação desfaz.
 */
@TesteDeIntegracao
@Transactional
class ArquivosTest extends ApoioDeMateriais {

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    long arquivosDaEntrega(long entregaId) {
        return contar("SELECT count(*) FROM arquivo WHERE entrega_id = ?", entregaId);
    }

    // ---------------------------------------------------------------------
    // Item 4: o patrocinador envia, e a resposta e o banco mostram autor, data, versão e histórico
    // ---------------------------------------------------------------------

    @Test
    void patrocinadorDoAmareloEnviaPdfEmEntregaDeMidiaERecebeAutorDataVersaoEHistorico() throws Exception {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "VT para os telões dos palcos");
        SessaoDeApi amarelo = entrar(AMARELO);
        byte[] bytes = pdf("primeira versão do VT");

        MvcTestResult resposta = enviar(amarelo, entrega, "VT final.pdf", bytes, null, "Primeira versão para análise.");

        // A resposta (rota 17): o objeto Arquivo mais o status em que a entrega ficou.
        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(resposta.getResponse().getHeader("Location")).isEqualTo("/api/arquivos/" + id(resposta));
        assertThat(lerJson(resposta, "$.entregaId")).isEqualTo((int) entrega);
        assertThat(lerJson(resposta, "$.nomeOriginal")).isEqualTo("VT final.pdf");
        assertThat(lerJson(resposta, "$.mimeType")).isEqualTo("application/pdf");
        assertThat(lerJson(resposta, "$.tamanhoBytes")).isEqualTo(bytes.length);
        assertThat(lerJson(resposta, "$.versao")).isEqualTo(1);
        assertThat(lerJson(resposta, "$.categoria")).as("padrão do patrocinador").isEqualTo("MATERIAL_MARCA");
        assertThat(lerJson(resposta, "$.enviadoPor.id")).isEqualTo((int) usuarioId(AMARELO));
        assertThat(lerJson(resposta, "$.enviadoPor.nome")).isEqualTo("Marketing Amarelo");
        assertThat(lerJson(resposta, "$.enviadoPor.perfil")).isEqualTo("ADMIN_PATROCINADOR");
        String enviadoEm = (String) lerJson(resposta, "$.enviadoEm");
        assertThat(enviadoEm).endsWith("-03:00");
        assertThat(OffsetDateTime.parse(enviadoEm)).isBetween(OffsetDateTime.now().minusMinutes(5), OffsetDateTime.now().plusMinutes(5));
        assertThat(lerJson(resposta, "$.statusDaEntrega")).isEqualTo("ENVIADO");

        // O banco: autor, data, versão, hash e a entrega em ENVIADO.
        Map<String, Object> linha = jdbc.sql("SELECT * FROM arquivo WHERE id = :id").param("id", id(resposta)).query().singleRow();
        assertThat(((Number) linha.get("enviado_por")).longValue()).isEqualTo(usuarioId(AMARELO));
        assertThat(linha.get("enviado_em")).isNotNull();
        assertThat(((Number) linha.get("versao")).intValue()).isEqualTo(1);
        assertThat(((Number) linha.get("parceria_id")).longValue()).isEqualTo(amarelo());
        assertThat(((Number) linha.get("entrega_id")).longValue()).isEqualTo(entrega);
        assertThat(linha.get("aba_id")).isEqualTo(jdbc.sql("SELECT id FROM aba WHERE codigo = 'MIDIA'").query().singleValue());
        assertThat(linha.get("hash_sha256")).isEqualTo(sha256(bytes));
        assertThat(((Number) linha.get("tamanho_bytes")).longValue()).isEqualTo(bytes.length);
        assertThat(linha.get("mime_type")).isEqualTo("application/pdf");
        assertThat(statusDaEntrega(entrega)).isEqualTo("ENVIADO");

        // [HISTORICO] ENVIO_ARQUIVO com autor, parceria, IP e navegador; e ALTERACAO_STATUS porque o status mudou.
        List<Map<String, Object>> envio = historico("ENVIO_ARQUIVO", "entrega", entrega);
        assertThat(envio).hasSize(1);
        assertThat(((Number) envio.getFirst().get("usuario_id")).longValue()).isEqualTo(usuarioId(AMARELO));
        assertThat(((Number) envio.getFirst().get("parceria_id")).longValue()).isEqualTo(amarelo());
        assertThat(envio.getFirst().get("valor_novo")).isEqualTo("VT final.pdf (versão 1)");
        assertThat(envio.getFirst().get("detalhe")).isEqualTo("Primeira versão para análise.");
        assertThat(envio.getFirst().get("ip")).isEqualTo("127.0.0.1");
        assertThat(envio.getFirst().get("user_agent")).isEqualTo(AGENTE);
        List<Map<String, Object>> status = historico("ALTERACAO_STATUS", "entrega", entrega);
        assertThat(status).hasSize(1);
        assertThat(status.getFirst().get("valor_anterior")).isEqualTo("AGUARDANDO_ENVIO");
        assertThat(status.getFirst().get("valor_novo")).isEqualTo("ENVIADO");
        assertThat(historico("NOVA_VERSAO", "entrega", entrega)).isEmpty();
    }

    @Test
    void aObservacaoDoEnvioFicaNaVersaoESaiNaRespostaENaListaDeVersoesDaEntrega() {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "Peça nova");
        SessaoDeApi amarelo = entrar(AMARELO);

        MvcTestResult v1 = enviar(amarelo, entrega, "peca.pdf", pdf("v1"), null, "  Primeira versão, sem trilha.  ");
        MvcTestResult v2 = enviar(amarelo, entrega, "peca.pdf", pdf("v2"), null, null);
        MvcTestResult v3 = enviar(amarelo, entrega, "peca.pdf", pdf("v3"), null, "Agora com a trilha liberada.");

        // A resposta da rota 17 já traz a observação (sem os espaços das pontas) ou null.
        assertThat(lerJson(v1, "$.observacao")).isEqualTo("Primeira versão, sem trilha.");
        assertThat(lerJson(v2, "$.observacao")).isNull();
        assertThat(lerJson(v3, "$.observacao")).isEqualTo("Agora com a trilha liberada.");
        // E a lista de versões da entrega (rota 14), da mais nova para a mais antiga, traz cada uma com a sua.
        MvcTestResult lista = amarelo.get("/api/parcerias/" + amarelo() + "/entregas?aba=MIDIA");
        List<Object> versoes = lerLista(lista, "$[?(@.id == " + entrega + ")].arquivos[*].versao");
        List<Object> observacoes = lerLista(lista, "$[?(@.id == " + entrega + ")].arquivos[*].observacao");
        assertThat(versoes).containsExactly(3, 2, 1);
        assertThat(observacoes).containsExactly("Agora com a trilha liberada.", null, "Primeira versão, sem trilha.");
        // O banco guarda na linha do arquivo, e o histórico continua com o mesmo texto.
        assertThat(jdbc.sql("SELECT observacao FROM arquivo WHERE id = :id").param("id", id(v1)).query(String.class).single())
                .isEqualTo("Primeira versão, sem trilha.");
        assertThat(historico("ENVIO_ARQUIVO", "entrega", entrega).getFirst().get("detalhe")).isEqualTo("Primeira versão, sem trilha.");
        // A RBS também vê a observação de cada versão, e o retorno que ela mesma envia pode ter a sua.
        MvcTestResult retorno = enviar(entrar(RBS), entrega, "retorno.pdf", pdf("retorno"), "OUTRO", "Segue a versão com a marcação.");
        assertThat(lerJson(retorno, "$.observacao")).isEqualTo("Segue a versão com a marcação.");
        MvcTestResult daRbs = entrar(RBS).get("/api/parcerias/" + amarelo() + "/entregas?aba=MIDIA");
        assertThat(lerLista(daRbs, "$[?(@.id == " + entrega + ")].arquivos[0].observacao")).containsExactly("Segue a versão com a marcação.");
    }

    @Test
    void observacaoLongaDemaisContinuaRecusadaEOArquivoNaoFicaComTextoPela() {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "Peça nova");

        MvcTestResult resposta = enviar(entrar(AMARELO), entrega, "peca.pdf", pdf("v1"), null, "x".repeat(501));

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(resposta, "$.campos.observacao")).isNotNull();
        assertThat(arquivosDaEntrega(entrega)).isZero();
    }

    @Test
    void csvEmLatin1RecebeAInstrucaoDeSalvarEmUtf8ENaoAFraseDeArquivoRenomeado() {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "Planilha");
        SessaoDeApi amarelo = entrar(AMARELO);
        byte[] latin1 = "nome;cargo\nJoão;Diretor de Marketing".getBytes(StandardCharsets.ISO_8859_1);
        byte[] umPng = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};

        MvcTestResult emLatin1 = enviar(amarelo, entrega, "lista.csv", latin1);
        MvcTestResult pngRenomeado = enviar(amarelo, entrega, "lista.csv", umPng);

        assertThat(emLatin1).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat((String) lerJson(emLatin1, "$.detail")).contains("não está em UTF-8").contains("CSV UTF-8");
        assertThat(pngRenomeado).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat((String) lerJson(pngRenomeado, "$.detail")).contains("não é um CSV de verdade").contains("renomeado");
        assertThat(arquivosDaEntrega(entrega)).isZero();
    }

    @Test
    void segundoEnvioViraVersao2ComNovaVersaoNoHistoricoESemOutraMudancaDeStatus() {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "VT para os telões dos palcos");
        SessaoDeApi amarelo = entrar(AMARELO);
        enviar(amarelo, entrega, "VT final.pdf", pdf("v1"));

        MvcTestResult segundo = enviar(amarelo, entrega, "VT final.pdf", pdf("v2 com ajustes"), "MATERIAL_MIDIA", "Ajustei o áudio.");

        assertThat(segundo).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(segundo, "$.versao")).isEqualTo(2);
        assertThat(lerJson(segundo, "$.categoria")).isEqualTo("MATERIAL_MIDIA");
        assertThat(lerJson(segundo, "$.statusDaEntrega")).isEqualTo("ENVIADO");
        List<Map<String, Object>> novaVersao = historico("NOVA_VERSAO", "entrega", entrega);
        assertThat(novaVersao).hasSize(1);
        assertThat(novaVersao.getFirst().get("valor_anterior")).isEqualTo("versão 1");
        assertThat(novaVersao.getFirst().get("valor_novo")).isEqualTo("VT final.pdf (versão 2)");
        assertThat(novaVersao.getFirst().get("detalhe")).isEqualTo("Ajustei o áudio.");
        assertThat(historico("ENVIO_ARQUIVO", "entrega", entrega)).as("o ENVIO_ARQUIVO é só do primeiro").hasSize(1);
        assertThat(historico("ALTERACAO_STATUS", "entrega", entrega)).as("já estava ENVIADO: nada mudou").hasSize(1);
        // A rota 14 devolve as duas versões, da mais nova para a mais antiga.
        MvcTestResult lista = amarelo.get("/api/parcerias/" + amarelo() + "/entregas?aba=MIDIA");
        List<Integer> versoes = lerLista(lista, "$[?(@.id == " + entrega + ")].arquivos[*].versao");
        assertThat(versoes).containsExactly(2, 1);
    }

    @Test
    void oArquivoVaiParaODiscoComNomeDoServidorEOsBytesSaoOsMesmos() throws IOException {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "VT");
        byte[] bytes = pdf("conteúdo que precisa chegar intacto");

        MvcTestResult resposta = enviar(entrar(AMARELO), entrega, "Meu VT (final).pdf", bytes);

        String caminho = jdbc.sql("SELECT caminho FROM arquivo WHERE id = :id").param("id", id(resposta)).query(String.class).single();
        assertThat(caminho).matches("parcerias/" + amarelo() + "/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pdf");
        assertThat(caminho).as("o nome original nunca vai para o disco").doesNotContain("VT");
        Path emDisco = armazenamento.localizar(caminho).orElseThrow();
        assertThat(Files.readAllBytes(emDisco)).isEqualTo(bytes);
    }

    @Test
    void retornoDaRbsNaoMudaOStatusEVaiParaOPatrocinadorVer() {
        SessaoDeApi rbs = entrar(RBS);
        long entrega = criarEntrega(rbs, amarelo(), "MIDIA", "VT");
        definirStatus(entrega, "EM_ANALISE");

        MvcTestResult resposta = enviar(rbs, entrega, "parecer.pdf", pdf("parecer"), null, "Segue o parecer.");

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(resposta, "$.categoria")).as("padrão da RBS").isEqualTo("OUTRO");
        assertThat(lerJson(resposta, "$.enviadoPor.perfil")).isEqualTo("ADMIN_RBS");
        assertThat(lerJson(resposta, "$.statusDaEntrega")).isEqualTo("EM_ANALISE");
        assertThat(statusDaEntrega(entrega)).isEqualTo("EM_ANALISE");
        assertThat(historico("ENVIO_ARQUIVO", "entrega", entrega)).hasSize(1);
        assertThat(historico("ALTERACAO_STATUS", "entrega", entrega)).isEmpty();
        MvcTestResult lista = entrar(AMARELO).get("/api/parcerias/" + amarelo() + "/entregas?aba=MIDIA");
        assertThat(lerLista(lista, "$[?(@.id == " + entrega + ")].arquivos[*].nomeOriginal")).containsExactly("parecer.pdf");
    }

    @Test
    void rbsPodeMandarRetornoComCategoriaDaRbsEmEntregaJaAprovada() {
        long aprovada = entregaDaAmostra(amarelo(), "Logo final para aplicações no evento");

        MvcTestResult resposta = enviar(entrar(RBS), aprovada, "plano.pdf", pdf("plano"), "PLANO_COMERCIAL", null);

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(resposta, "$.versao")).as("depois das versões 1 e 2 da amostra").isEqualTo(3);
        assertThat(lerJson(resposta, "$.categoria")).isEqualTo("PLANO_COMERCIAL");
        assertThat(statusDaEntrega(aprovada)).isEqualTo("APROVADO");
    }

    @Test
    void patrocinadorEnviaEmQualquerStatusQueAindaEsperaMaterialEIssoPoeEmEnviado() {
        SessaoDeApi rbs = entrar(RBS);
        SessaoDeApi amarelo = entrar(AMARELO);
        for (String statusAntes : List.of("NAO_INICIADO", "AGUARDANDO_ENVIO", "AJUSTE_SOLICITADO", "ENVIADO")) {
            long entrega = criarEntrega(rbs, amarelo(), "PERFIL", "Entrega em " + statusAntes);
            definirStatus(entrega, statusAntes);

            MvcTestResult resposta = enviar(amarelo, entrega, "doc.pdf", pdf(statusAntes));

            assertThat(resposta).as("envio em " + statusAntes).hasStatus(HttpStatus.CREATED);
            assertThat(lerJson(resposta, "$.statusDaEntrega")).isEqualTo("ENVIADO");
            assertThat(statusDaEntrega(entrega)).isEqualTo("ENVIADO");
            assertThat(historico("ALTERACAO_STATUS", "entrega", entrega)).hasSize("ENVIADO".equals(statusAntes) ? 0 : 1);
        }
    }

    @Test
    void patrocinadorNaoEnviaDepoisDeEmAnaliseAprovadoOuNaoAplicavel409() {
        SessaoDeApi rbs = entrar(RBS);
        SessaoDeApi amarelo = entrar(AMARELO);
        for (String status : List.of("EM_ANALISE", "APROVADO", "NAO_APLICAVEL")) {
            long entrega = criarEntrega(rbs, amarelo(), "PERFIL", "Entrega em " + status);
            definirStatus(entrega, status);

            MvcTestResult resposta = enviar(amarelo, entrega, "doc.pdf", pdf(status));

            assertThat(resposta).as("envio em " + status).hasStatus(HttpStatus.CONFLICT);
            assertThat(lerJson(resposta, "$.detail")).isNotNull();
            assertThat(arquivosDaEntrega(entrega)).isZero();
            assertThat(statusDaEntrega(entrega)).isEqualTo(status);
        }
    }

    @Test
    void depoisDeAjusteSolicitadoOReenvioVoltaParaEnviadoEFicaNaVersao2() {
        SessaoDeApi rbs = entrar(RBS);
        SessaoDeApi roxo = entrar(ROXO);
        long entrega = entregaDaAmostra(roxo(), "VT para os telões dos palcos");   // AJUSTE_SOLICITADO na amostra, com a versão 1 da marca

        MvcTestResult resposta = enviar(roxo, entrega, "vt_ajustado.mp4", mp4(), "MATERIAL_MIDIA", null);

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(resposta, "$.mimeType")).isEqualTo("video/mp4");
        assertThat(lerJson(resposta, "$.versao")).as("a amostra já tem a versão 1 enviada pela marca").isEqualTo(2);
        assertThat(lerJson(resposta, "$.statusDaEntrega")).isEqualTo("ENVIADO");
        assertThat(statusDaEntrega(entrega)).isEqualTo("ENVIADO");
        assertThat(rbs.get("/api/entregas?status=ENVIADO&parceriaId=" + roxo())).hasStatus(HttpStatus.OK);
    }

    static byte[] mp4() {
        return new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2', 0, 0, 0, 0, 'm', 'p', '4', '2', 'i', 's', 'o', 'm'};
    }

    // ---------------------------------------------------------------------
    // [ISOLAMENTO] e [PERMISSAO]
    // ---------------------------------------------------------------------

    @Test
    void roxoEnviandoNaEntregaDoAmareloRecebe404IgualAEntregaInexistenteESemGravarNada() {
        long entregaDoAmarelo = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "VT do Amarelo");
        SessaoDeApi roxo = entrar(ROXO);
        long arquivosAntes = contar("SELECT count(*) FROM arquivo");
        long historicoAntes = contar("SELECT count(*) FROM historico");

        MvcTestResult doAmarelo = enviar(roxo, entregaDoAmarelo, "invasor.pdf", pdf("invasor"));
        MvcTestResult inexistente = enviar(roxo, 999999, "invasor.pdf", pdf("invasor"));

        assertThat(doAmarelo).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(inexistente).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(doAmarelo, "$.detail")).isEqualTo(lerJson(inexistente, "$.detail"));
        assertThat(corpo(doAmarelo)).doesNotContain("Amarelo");
        assertThat(contar("SELECT count(*) FROM arquivo")).isEqualTo(arquivosAntes);
        assertThat(contar("SELECT count(*) FROM historico")).isEqualTo(historicoAntes);
        assertThat(statusDaEntrega(entregaDoAmarelo)).isEqualTo("AGUARDANDO_ENVIO");
    }

    @Test
    void roxoEnviandoEmAbaFechadaRecebe403() {
        long pesquisaDoRoxo = entregaDaAmostra(roxo(), "Três perguntas personalizadas");

        MvcTestResult resposta = enviar(entrar(ROXO), pesquisaDoRoxo, "perguntas.pdf", pdf("perguntas"));

        assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("Esta aba não faz parte da cota da sua parceria.");
        assertThat(arquivosDaEntrega(pesquisaDoRoxo)).isZero();
    }

    @Test
    void patrocinadorNaoUsaCategoriaDaRbs() {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "VT");
        SessaoDeApi amarelo = entrar(AMARELO);

        for (String categoria : List.of("PLANO_COMERCIAL", "RELATORIO_VEICULACAO", "COMPROVACAO", "PESQUISA", "RELATORIO_PARCERIA", "CONTRATO")) {
            MvcTestResult resposta = enviar(amarelo, entrega, "doc.pdf", pdf(categoria), categoria, null);
            assertThat(resposta).as(categoria).hasStatus(HttpStatus.FORBIDDEN);
        }
        for (String categoria : List.of("MATERIAL_MARCA", "MATERIAL_MIDIA", "DOCUMENTO_INGRESSOS", "OUTRO")) {
            MvcTestResult resposta = enviar(amarelo, entrega, "doc.pdf", pdf(categoria), categoria, null);
            assertThat(resposta).as(categoria).hasStatus(HttpStatus.CREATED);
        }
    }

    @Test
    void envioExigeSessaoECsrfEMultipart() {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "VT");

        assertThat(enviarSemCsrf(entrar(AMARELO), entrega, "doc.pdf", pdf("x"))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(enviar(SessaoDeApi.anonima(mvc), entrega, "doc.pdf", pdf("x"))).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(entrar(AMARELO).post("/api/entregas/" + entrega + "/arquivos", "{}")).as("JSON no lugar de multipart")
                .hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    // ---------------------------------------------------------------------
    // [VALIDACAO] e [UPLOAD]: o que o servidor recusa
    // ---------------------------------------------------------------------

    @Test
    void camposInvalidosDevolvem422ComOCampoCerto() {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "VT");
        SessaoDeApi amarelo = entrar(AMARELO);

        MvcTestResult semArquivo = enviarSemArquivo(amarelo, entrega);
        MvcTestResult vazio = enviar(amarelo, entrega, "vazio.pdf", new byte[0]);
        MvcTestResult categoria = enviar(amarelo, entrega, "doc.pdf", pdf("x"), "INVENTADA", null);
        MvcTestResult observacao = enviar(amarelo, entrega, "doc.pdf", pdf("x"), null, "o".repeat(501));
        MvcTestResult observacaoNoLimite = enviar(amarelo, entrega, "doc.pdf", pdf("x"), null, "o".repeat(500));

        assertThat(semArquivo).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(semArquivo, "$.campos.arquivo")).isNotNull();
        assertThat(vazio).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(vazio, "$.campos.arquivo")).isEqualTo("O arquivo está vazio.");
        assertThat(lerJson(semArquivo, "$.campos.arquivo")).isEqualTo("Envie o arquivo na parte \"arquivo\" do formulário.");
        assertThat(categoria).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(categoria, "$.campos.categoria")).isNotNull();
        assertThat(observacao).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(observacao, "$.campos.observacao")).isNotNull();
        assertThat(observacaoNoLimite).hasStatus(HttpStatus.CREATED);
        assertThat(arquivosDaEntrega(entrega)).as("só o último foi aceito").isEqualTo(1);
    }

    @Test
    void executavelRenomeadoParaPdfDevolve415() {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "VT");
        byte[] executavel = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 0, 0};

        MvcTestResult resposta = enviar(entrar(AMARELO), entrega, "instalador.pdf", executavel);

        assertThat(resposta).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(lerJson(resposta, "$.title")).isEqualTo("Tipo de conteúdo não aceito");
        assertThat((String) lerJson(resposta, "$.detail")).contains("PDF");
        assertThat(arquivosDaEntrega(entrega)).isZero();
        assertThat(statusDaEntrega(entrega)).as("recusado: o status não mudou").isEqualTo("AGUARDANDO_ENVIO");
        assertThat(contar("SELECT count(*) FROM historico WHERE entidade = 'entrega' AND entidade_id = ? AND acao <> 'CRIACAO'", entrega)).isZero();
    }

    @Test
    void extensaoForaDaListaDevolve415MesmoComConteudoInocente() {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "VT");
        SessaoDeApi amarelo = entrar(AMARELO);
        byte[] texto = "conteudo".getBytes(StandardCharsets.UTF_8);

        for (String nome : List.of("programa.exe", "script.js", "pagina.html", "logo.svg", "macro.docm", "lib.dll", "semextensao",
                "termina-com-ponto.", ".pdf.exe", "arquivo.ai", "../../etc/passwd")) {
            assertThat(enviar(amarelo, entrega, nome, texto)).as(nome).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
        assertThat(arquivosDaEntrega(entrega)).isZero();
    }

    @Test
    void cadaTipoAceitoPassaComASuaAssinaturaECadaUmReprovaComAAssinaturaDeOutro() {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "VT");
        SessaoDeApi amarelo = entrar(AMARELO);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};
        byte[] zip = {'P', 'K', 3, 4, 20, 0, 0, 0, 8, 0};
        byte[] mp3Id3 = {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0x21};
        byte[] mp3Quadro = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x64, 0, 0};
        byte[] csv = "nome;valor\nAmarelo;1,5\nação;2\n".getBytes(StandardCharsets.UTF_8);

        Map<String, byte[]> aceitos = new java.util.LinkedHashMap<>();
        aceitos.put("a.pdf", pdf("x"));
        aceitos.put("A.PNG", png);
        aceitos.put("b.jpg", jpg);
        aceitos.put("c.jpeg", jpg);
        aceitos.put("d.docx", zip);
        aceitos.put("e.xlsx", zip);
        aceitos.put("f.pptx", zip);
        aceitos.put("g.csv", csv);
        aceitos.put("h.mp4", mp4());
        aceitos.put("i.mp3", mp3Id3);
        aceitos.put("j.mp3", mp3Quadro);
        aceitos.put("k.zip", zip);
        aceitos.forEach((nome, bytes) -> assertThat(enviar(amarelo, entrega, nome, bytes)).as(nome).hasStatus(HttpStatus.CREATED));
        assertThat(arquivosDaEntrega(entrega)).isEqualTo(aceitos.size());

        Map<String, byte[]> trocados = new java.util.LinkedHashMap<>();
        trocados.put("png-com-jpg.png", jpg);
        trocados.put("jpg-com-png.jpg", png);
        trocados.put("pdf-com-zip.pdf", zip);
        trocados.put("docx-com-pdf.docx", pdf("x"));
        trocados.put("mp4-com-mp3.mp4", mp3Id3);
        trocados.put("mp3-com-mp4.mp3", mp4());
        trocados.put("zip-com-png.zip", png);
        trocados.put("csv-com-nulo.csv", new byte[] {'a', ';', 'b', 0, '\n'});
        trocados.put("csv-latin1.csv", new byte[] {'a', ';', (byte) 0xE7, (byte) 0xE3, 'o', '\n'});
        trocados.put("csv-binario.csv", png);
        trocados.forEach((nome, bytes) -> assertThat(enviar(amarelo, entrega, nome, bytes)).as(nome).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
        assertThat(arquivosDaEntrega(entrega)).as("nenhum dos recusados foi gravado").isEqualTo(aceitos.size());
    }

    @Test
    void arquivoAcimaDe20MbDevolve413EOLimiteExatoPassa() {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "VT");
        SessaoDeApi amarelo = entrar(AMARELO);
        int limite = 20 * 1024 * 1024;
        byte[] noLimite = new byte[limite];
        byte[] cabecalho = pdf("");
        System.arraycopy(cabecalho, 0, noLimite, 0, cabecalho.length);
        byte[] passou = new byte[limite + 1];
        System.arraycopy(cabecalho, 0, passou, 0, cabecalho.length);

        MvcTestResult grande = enviar(amarelo, entrega, "grande.pdf", passou);
        MvcTestResult exato = enviar(amarelo, entrega, "exato.pdf", noLimite);

        assertThat(grande).hasStatus(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(lerJson(grande, "$.title")).isEqualTo("Arquivo grande demais");
        assertThat((String) lerJson(grande, "$.detail")).contains("20 MB");
        assertThat(exato).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(exato, "$.tamanhoBytes")).isEqualTo(limite);
        assertThat(arquivosDaEntrega(entrega)).isEqualTo(1);
    }

    @Test
    void oNomeOriginalSoGuardaOFimDoCaminhoELimpaCaracteresDeControle() {
        long entrega = criarEntrega(entrar(RBS), amarelo(), "MIDIA", "VT");

        MvcTestResult resposta = enviar(entrar(AMARELO), entrega, "C:\\fotos\\Logo\u0007 final.pdf", pdf("x"));

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(resposta, "$.nomeOriginal")).isEqualTo("Logo final.pdf");
    }
}
