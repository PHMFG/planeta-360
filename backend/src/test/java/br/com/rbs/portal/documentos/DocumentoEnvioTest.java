package br.com.rbs.portal.documentos;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.comum.Armazenamento;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] A rota 54 (enviar um documento): quem pode, o que aceita (PDF e TXT, conferidos pela assinatura), o que grava (arquivo
 * DOCUMENTO, documento, histórico CRIACAO), a versão dentro do grupo e os 422. Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class DocumentoEnvioTest extends ApoioDeDocumentos {

    @Autowired
    Armazenamento armazenamento;

    private static byte[] exe() {
        return new byte[] {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 0, 0};
    }

    @Test
    void rbsEnviaUmPdfEGanha201ComODocumentoAguardandoAprovacao() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult resposta = enviar(rbs, "comunicado.pdf", KitDeDocumentos.por("D09").conteudo(), global("Comunicado de teste", "teste-envio"));

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        long id = id(resposta);
        assertThat(resposta.getResponse().getHeader("Location")).isEqualTo("/api/documentos/" + id);
        assertThat(lerJson(resposta, "$.titulo")).isEqualTo("Comunicado de teste");
        assertThat(lerJson(resposta, "$.chave")).isEqualTo("teste-envio");
        assertThat(lerJson(resposta, "$.versao")).isEqualTo(1);
        assertThat(lerJson(resposta, "$.tipo")).isEqualTo("OUTRO");
        assertThat(lerJson(resposta, "$.escopo")).isEqualTo("GLOBAL");
        assertThat(lerJson(resposta, "$.status")).isEqualTo("AGUARDANDO_APROVACAO");
        assertThat(lerJson(resposta, "$.evento.edicao")).isEqualTo(2027);
        assertThat(lerJson(resposta, "$.cota")).isNull();
        assertThat(lerJson(resposta, "$.parceria")).isNull();
        assertThat(lerJson(resposta, "$.paginas")).isEqualTo(2);
        assertThat(lerJson(resposta, "$.trechos")).isEqualTo(0);
        assertThat(lerJson(resposta, "$.modelo")).isNull();
        assertThat(lerJson(resposta, "$.contemDadosPessoais")).isEqualTo(false);
        assertThat(lerJson(resposta, "$.erro")).isNull();
        assertThat(lerJson(resposta, "$.arquivo.nomeOriginal")).isEqualTo("comunicado.pdf");
        assertThat(lerJson(resposta, "$.arquivo.mimeType")).isEqualTo("application/pdf");
        assertThat(lerJson(resposta, "$.enviadoPor.perfil")).isEqualTo("ADMIN_RBS");
        assertThat(lerJson(resposta, "$.aprovadoPor")).isNull();
        assertThat(lista(resposta, "$.acoes")).containsExactly("APROVAR", "RECUSAR");
        assertThat((Integer) lerJson(resposta, "$.extracaoMs")).isGreaterThanOrEqualTo(0);
    }

    @Test
    void oArquivoVaiParaOArmazenamentoComACategoriaDocumentoGeralESemAba() {
        long id = enviarOk(entrar(RBS), "comunicado.pdf", KitDeDocumentos.por("D09").conteudo(), global("Comunicado de teste", "teste-arquivo"));

        var arquivo = jdbc.sql("""
                        SELECT a.categoria, a.parceria_id, a.aba_id, a.entrega_id, a.caminho, a.mime_type, a.nome_original, a.tamanho_bytes, a.hash_sha256
                        FROM arquivo a JOIN documento d ON d.arquivo_id = a.id WHERE d.id = :id
                        """).param("id", id).query().singleRow();
        assertThat(arquivo.get("categoria")).isEqualTo("DOCUMENTO");
        assertThat(arquivo.get("parceria_id")).isNull();
        assertThat(arquivo.get("aba_id")).isNull();
        assertThat(arquivo.get("entrega_id")).isNull();
        assertThat((String) arquivo.get("caminho")).startsWith("geral/").endsWith(".pdf");
        assertThat(arquivo.get("mime_type")).isEqualTo("application/pdf");
        assertThat(((Number) arquivo.get("tamanho_bytes")).longValue()).isEqualTo(KitDeDocumentos.por("D09").conteudo().length);
        assertThat(armazenamento.localizar((String) arquivo.get("caminho"))).isPresent();
    }

    @Test
    void oEnvioGravaOHistoricoCriacaoNaEntidadeDocumento() {
        long id = enviarOk(entrar(RBS), "comunicado.pdf", KitDeDocumentos.por("D09").conteudo(), global("Comunicado de teste", "teste-historico"));

        var linha = jdbc.sql("""
                        SELECT acao, entidade, parceria_id, valor_anterior, valor_novo, detalhe, usuario_id, user_agent FROM historico
                        WHERE entidade = 'documento' AND entidade_id = :id
                        """).param("id", id).query().singleRow();
        assertThat(linha.get("acao")).isEqualTo("CRIACAO");
        assertThat(linha.get("parceria_id")).as("documento global não tem parceria").isNull();
        assertThat(linha.get("valor_anterior")).isNull();
        assertThat(linha.get("valor_novo")).isEqualTo("Comunicado de teste (versão 1)");
        assertThat((String) linha.get("detalhe")).startsWith("GLOBAL · teste-historico");
        assertThat(linha.get("user_agent")).isEqualTo(AGENTE);
    }

    @Test
    void aVersaoIncrementaDentroDoGrupoECadaGrupoComecaNa1() {
        SessaoDeApi rbs = entrar(RBS);
        byte[] pdf = KitDeDocumentos.por("D09").conteudo();

        MvcTestResult v1 = enviar(rbs, "a.pdf", pdf, global("Manual", "teste-versao"));
        MvcTestResult v2 = enviar(rbs, "b.pdf", pdf, global("Manual", "teste-versao"));
        MvcTestResult v3 = enviar(rbs, "c.pdf", pdf, global("Manual", "teste-versao"));
        MvcTestResult outraChave = enviar(rbs, "d.pdf", pdf, global("Manual", "teste-versao-outra"));
        Map<String, String> de2026 = global("Manual", "teste-versao");
        de2026.put("eventoId", String.valueOf(evento(2026)));
        MvcTestResult outraEdicao = enviar(rbs, "e.pdf", pdf, de2026);

        assertThat(lerJson(v1, "$.versao")).isEqualTo(1);
        assertThat(lerJson(v2, "$.versao")).isEqualTo(2);
        assertThat(lerJson(v3, "$.versao")).isEqualTo(3);
        assertThat(lerJson(outraChave, "$.versao")).as("outra chave: outro grupo").isEqualTo(1);
        assertThat(lerJson(outraEdicao, "$.versao")).as("outra edição: outro grupo (o D10 nasce na versão 1)").isEqualTo(1);
    }

    @Test
    void oMesmoPlanoEmCotasDiferentesEOMesmoAnexoEmParceriasDiferentesSaoGruposDiferentes() {
        SessaoDeApi rbs = entrar(RBS);
        byte[] pdf = KitDeDocumentos.por("D03").conteudo();
        Map<String, String> master = cota("MASTER", "teste-plano");
        Map<String, String> subcota = cota("SUBCOTA", "teste-plano");

        MvcTestResult m1 = enviar(rbs, "m1.pdf", pdf, master);
        MvcTestResult s1 = enviar(rbs, "s1.pdf", pdf, subcota);
        MvcTestResult m2 = enviar(rbs, "m2.pdf", pdf, master);
        MvcTestResult amarelo = enviar(rbs, "a.pdf", pdf, daParceria("Cliente Amarelo", "teste-anexo"));
        MvcTestResult roxo = enviar(rbs, "r.pdf", pdf, daParceria("Cliente Roxo", "teste-anexo"));

        assertThat(lerJson(m1, "$.versao")).isEqualTo(1);
        assertThat(lerJson(s1, "$.versao")).isEqualTo(1);
        assertThat(lerJson(m2, "$.versao")).isEqualTo(2);
        assertThat(lerJson(m1, "$.cota.codigo")).isEqualTo("MASTER");
        assertThat(lerJson(m1, "$.cota.nome")).isEqualTo("Master");
        assertThat(lerJson(amarelo, "$.versao")).isEqualTo(1);
        assertThat(lerJson(roxo, "$.versao")).isEqualTo(1);
        assertThat(lerJson(amarelo, "$.parceria.patrocinador")).isEqualTo("Cliente Amarelo");
        assertThat(lerJson(amarelo, "$.evento.edicao")).as("a edição vem da parceria").isEqualTo(2027);
    }

    @Test
    void oArquivoDeUmAnexoDeParceriaFicaNaPastaDaParceriaESemAba() {
        long id = enviarOk(entrar(RBS), "anexo.pdf", KitDeDocumentos.por("D06").conteudo(), daParceria("Cliente Amarelo", "teste-anexo-pasta"));

        var arquivo = jdbc.sql("SELECT a.parceria_id, a.aba_id, a.caminho FROM arquivo a JOIN documento d ON d.arquivo_id = a.id WHERE d.id = :id")
                .param("id", id).query().singleRow();
        assertThat(((Number) arquivo.get("parceria_id")).longValue()).isEqualTo(parceria("Cliente Amarelo"));
        assertThat(arquivo.get("aba_id")).isNull();
        assertThat((String) arquivo.get("caminho")).startsWith("parcerias/" + parceria("Cliente Amarelo") + "/");
        assertThat(contar("SELECT parceria_id FROM documento WHERE id = ?", id)).isEqualTo(parceria("Cliente Amarelo"));
    }

    @Test
    void txtEAceitoEContaComoUmaPagina() {
        MvcTestResult resposta = enviar(entrar(RBS), "faq.txt", KitDeDocumentos.por("D08").conteudo(), global("FAQ de teste", "teste-faq"));

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(resposta, "$.paginas")).isEqualTo(1);
        assertThat(lerJson(resposta, "$.arquivo.mimeType")).isEqualTo("text/plain");
        assertThat(lerJson(resposta, "$.status")).isEqualTo("AGUARDANDO_APROVACAO");
    }

    @Test
    void pdfSoDeImagemNasceEmErroSemAprovadoPorENaoSeReindexa() {
        MvcTestResult resposta = enviar(entrar(RBS), "aviso.pdf", KitDeDocumentos.por("D12").conteudo(), global("Aviso digitalizado", "teste-imagem"));

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(resposta, "$.status")).isEqualTo("ERRO");
        assertThat(lerJson(resposta, "$.erro")).isEqualTo("Sem texto extraível; OCR fora do escopo.");
        assertThat(lerJson(resposta, "$.paginas")).isEqualTo(1);
        assertThat(lerJson(resposta, "$.aprovadoPor")).isNull();
        assertThat(lista(resposta, "$.acoes")).as("só se corrige com um novo envio: não há reindexar").containsExactly("RECUSAR");
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ?", id(resposta))).isZero();
    }

    @Test
    void pdfCorrompidoComAAssinaturaCertaNasceEmErroComOMotivo() {
        MvcTestResult resposta = enviar(entrar(RBS), "quebrado.pdf", texto("%PDF-1.4\nisto não é um pdf de verdade"), global("Quebrado", "teste-quebrado"));

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(resposta, "$.status")).isEqualTo("ERRO");
        assertThat(lerJson(resposta, "$.erro")).isEqualTo("O PDF está corrompido ou não pôde ser lido.");
        assertThat(lerJson(resposta, "$.paginas")).isNull();
    }

    @Test
    void documentoComCpfEMarcadoENaoPodeSerAprovado() {
        MvcTestResult resposta = enviar(entrar(RBS), "lista.pdf", KitDeDocumentos.por("D11").conteudo(), daParceria("Cliente Verde", "teste-lista"));

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(resposta, "$.contemDadosPessoais")).isEqualTo(true);
        assertThat(lerJson(resposta, "$.status")).isEqualTo("AGUARDANDO_APROVACAO");
        assertThat(lista(resposta, "$.acoes")).as("aprovar some: o gatilho recusaria").containsExactly("RECUSAR");
    }

    // ---------------------------------------------------------------------
    // 415 e 413
    // ---------------------------------------------------------------------

    @Test
    void executavelRenomeadoParaPdfEOutrosTiposDao415ComAFraseDosDois() {
        SessaoDeApi rbs = entrar(RBS);
        long antes = contar("SELECT count(*) FROM documento");
        byte[] zip = {'P', 'K', 3, 4, 0, 0, 0, 0};
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

        for (Object[] caso : new Object[][] {{"programa.pdf", exe()}, {"programa.exe", exe()}, {"manual.docx", zip}, {"slides.pptx", zip},
                {"pacote.zip", zip}, {"foto.png", png}, {"planilha.csv", texto("a,b\n1,2\n")}, {"sem-extensao", texto("texto")}}) {
            MvcTestResult resposta = enviar(rbs, (String) caso[0], (byte[]) caso[1], global("Teste", "teste-415"));
            assertThat(resposta).as((String) caso[0]).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            assertThat(lerJson(resposta, "$.detail")).as((String) caso[0]).isIn(
                    "Este tipo de arquivo não é aceito. Envie um destes: pdf, txt.", "O conteúdo do arquivo não é um PDF de verdade. Confira se o arquivo não foi renomeado.");
        }
        assertThat(contar("SELECT count(*) FROM documento")).as("nada ficou gravado").isEqualTo(antes);
        assertThat(contar("SELECT count(*) FROM arquivo WHERE categoria = 'DOCUMENTO'")).isEqualTo(antes);
    }

    @Test
    void txtComByteNuloOuForaDeUtf8Da415() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult nulo = enviar(rbs, "a.txt", new byte[] {'a', 0, 'b'}, global("Teste", "teste-txt"));
        MvcTestResult latin1 = enviar(rbs, "b.txt", new byte[] {'a', ';', (byte) 0xE7, (byte) 0xE3, 'o'}, global("Teste", "teste-txt"));

        assertThat(nulo).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(latin1).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat((String) lerJson(nulo, "$.detail")).startsWith("O conteúdo do arquivo não é um TXT de verdade");
    }

    @Test
    void arquivoAcimaDe20MbDa413ENoLimiteExatoNaoDa413() {
        SessaoDeApi rbs = entrar(RBS);
        int limite = 20 * 1024 * 1024;
        byte[] cabecalho = texto("%PDF-1.4\n");
        byte[] noLimite = new byte[limite];
        byte[] passou = new byte[limite + 1];
        System.arraycopy(cabecalho, 0, noLimite, 0, cabecalho.length);
        System.arraycopy(cabecalho, 0, passou, 0, cabecalho.length);

        MvcTestResult grande = enviar(rbs, "grande.pdf", passou, global("Grande", "teste-grande"));
        MvcTestResult exato = enviar(rbs, "exato.pdf", noLimite, global("Exato", "teste-exato"));

        assertThat(grande).hasStatus(HttpStatus.CONTENT_TOO_LARGE);
        assertThat((String) lerJson(grande, "$.detail")).contains("20 MB");
        // No limite exato o arquivo passa pelo limite; como o conteúdo não é um PDF de verdade, o documento nasce em ERRO.
        assertThat(exato).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(exato, "$.status")).isEqualTo("ERRO");
    }

    // ---------------------------------------------------------------------
    // 403 e 422
    // ---------------------------------------------------------------------

    @Test
    void marcaRecebe403AntesDoCorpoQualquerQueSejaOCorpo() {
        SessaoDeApi amarelo = entrar(AMARELO);
        long antes = contar("SELECT count(*) FROM documento");

        for (Map<String, String> campos : List.<Map<String, String>>of(Map.of(), global("Teste", "teste-403"), Map.of("escopo", "INVENTADO"))) {
            MvcTestResult com = enviar(amarelo, "a.pdf", KitDeDocumentos.por("D09").conteudo(), campos);
            assertThat(com).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(com, "$.detail")).isEqualTo(SO_RBS);
        }
        MvcTestResult semArquivo = enviar(amarelo, null, null, Map.of());
        assertThat(semArquivo).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(contar("SELECT count(*) FROM documento")).isEqualTo(antes);
    }

    @Test
    void semOTokenCsrfDa403() {
        MvcTestResult resposta = enviarSemCsrf(entrar(RBS), "a.pdf", KitDeDocumentos.por("D09").conteudo(), global("Teste", "teste-csrf"));

        assertThat(resposta).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void semSessaoDa401() {
        MvcTestResult resposta = enviar(SessaoDeApi.anonima(mvc), "a.pdf", KitDeDocumentos.por("D09").conteudo(), global("Teste", "teste-401"));

        assertThat(resposta).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void camposFaltandoDao422ComTodosOsCamposErrados() {
        MvcTestResult resposta = enviar(entrar(RBS), null, null, Map.of());

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(resposta, "$.campos.arquivo")).isEqualTo("Envie o documento (PDF ou TXT) na parte \"arquivo\" do formulário.");
        assertThat(lerJson(resposta, "$.campos.titulo")).isEqualTo("Informe o título do documento.");
        assertThat(lerJson(resposta, "$.campos.chave")).isEqualTo("Informe a chave do documento, como manual-operacoes.");
        assertThat(lerJson(resposta, "$.campos.tipo")).isEqualTo("Tipo inválido. Use MANUAL_OPERACOES, PLANO_COMERCIAL, ANEXO_PARCERIA, FAQ, COMUNICADO ou OUTRO.");
        assertThat(lerJson(resposta, "$.campos.escopo")).isEqualTo("Escopo inválido. Use GLOBAL, COTA ou PARCERIA.");
    }

    @Test
    void valoresInvalidosDao422CampoACampo() {
        SessaoDeApi rbs = entrar(RBS);
        byte[] pdf = KitDeDocumentos.por("D09").conteudo();
        Map<String, String> ruins = global("x".repeat(201), "Chave Com Espaço");
        ruins.put("tipo", "PLANILHA");

        MvcTestResult resposta = enviar(rbs, "a.pdf", pdf, ruins);

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(resposta, "$.campos.titulo")).isEqualTo("O título tem no máximo 200 caracteres.");
        assertThat((String) lerJson(resposta, "$.campos.chave")).startsWith("A chave usa só letras minúsculas sem acento, números e hífen");
        assertThat((String) lerJson(resposta, "$.campos.tipo")).startsWith("Tipo inválido.");
        assertThat(mapa(resposta, "$.campos").keySet()).containsExactlyInAnyOrder("titulo", "chave", "tipo");
    }

    @Test
    void oEscopoDizOQueOFormularioPrecisaTer() {
        SessaoDeApi rbs = entrar(RBS);
        byte[] pdf = KitDeDocumentos.por("D09").conteudo();
        Map<String, String> semEdicao = global("T", "teste-escopo");
        semEdicao.remove("eventoId");
        Map<String, String> edicaoInexistente = global("T", "teste-escopo");
        edicaoInexistente.put("eventoId", "999999");
        Map<String, String> globalComCota = global("T", "teste-escopo");
        globalComCota.put("cota", "MASTER");
        Map<String, String> globalComParceria = global("T", "teste-escopo");
        globalComParceria.put("parceriaId", String.valueOf(parceria("Cliente Amarelo")));
        Map<String, String> cotaSemCota = cota("MASTER", "teste-escopo");
        cotaSemCota.remove("cota");
        Map<String, String> cotaInvalida = cota("PLATINA", "teste-escopo");
        Map<String, String> parceriaSemParceria = daParceria("Cliente Amarelo", "teste-escopo");
        parceriaSemParceria.remove("parceriaId");
        Map<String, String> parceriaInexistente = daParceria("Cliente Amarelo", "teste-escopo");
        parceriaInexistente.put("parceriaId", "999999");
        Map<String, String> parceriaComCota = daParceria("Cliente Amarelo", "teste-escopo");
        parceriaComCota.put("cota", "MASTER");
        Map<String, String> parceriaDeOutraEdicao = daParceria("Cliente Amarelo", "teste-escopo");
        parceriaDeOutraEdicao.put("eventoId", String.valueOf(evento(2026)));
        Map<String, String> eventoNaoNumerico = global("T", "teste-escopo");
        eventoNaoNumerico.put("eventoId", "abc");

        assertThat(enviar(rbs, "a.pdf", pdf, semEdicao)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(enviar(rbs, "a.pdf", pdf, semEdicao), "$.campos.eventoId")).isEqualTo("Informe a edição do festival (eventoId).");
        assertThat(lerJson(enviar(rbs, "a.pdf", pdf, edicaoInexistente), "$.campos.eventoId")).isEqualTo("Esta edição do festival não existe.");
        assertThat(lerJson(enviar(rbs, "a.pdf", pdf, eventoNaoNumerico), "$.campos.eventoId")).isEqualTo("A edição (eventoId) precisa ser um número.");
        assertThat(lerJson(enviar(rbs, "a.pdf", pdf, globalComCota), "$.campos.cota")).isEqualTo("Só o documento de escopo COTA leva cota.");
        assertThat(lerJson(enviar(rbs, "a.pdf", pdf, globalComParceria), "$.campos.parceriaId")).isEqualTo("Só o documento de escopo PARCERIA leva parceria.");
        assertThat(lerJson(enviar(rbs, "a.pdf", pdf, cotaSemCota), "$.campos.cota")).isEqualTo("Informe a cota: MASTER, SUBCOTA_G ou SUBCOTA.");
        assertThat(lerJson(enviar(rbs, "a.pdf", pdf, cotaInvalida), "$.campos.cota")).isEqualTo("Informe a cota: MASTER, SUBCOTA_G ou SUBCOTA.");
        assertThat(lerJson(enviar(rbs, "a.pdf", pdf, parceriaSemParceria), "$.campos.parceriaId")).isEqualTo("Informe a parceria (parceriaId) do documento.");
        assertThat(lerJson(enviar(rbs, "a.pdf", pdf, parceriaInexistente), "$.campos.parceriaId")).isEqualTo("Esta parceria não existe.");
        assertThat((String) lerJson(enviar(rbs, "a.pdf", pdf, parceriaComCota), "$.campos.cota")).startsWith("Documento de parceria não leva cota");
        assertThat((String) lerJson(enviar(rbs, "a.pdf", pdf, parceriaDeOutraEdicao), "$.campos.eventoId")).startsWith("A edição do documento é a da parceria");
    }

    // ---------------------------------------------------------------------
    // Montagem dos campos
    // ---------------------------------------------------------------------

    private Map<String, String> cota(String codigo, String chave) {
        Map<String, String> campos = global("Plano de teste", chave);
        campos.put("escopo", "COTA");
        campos.put("cota", codigo);
        return campos;
    }

    private Map<String, String> daParceria(String cliente, String chave) {
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("titulo", "Anexo de teste");
        campos.put("chave", chave);
        campos.put("tipo", "ANEXO_PARCERIA");
        campos.put("escopo", "PARCERIA");
        campos.put("parceriaId", String.valueOf(parceria(cliente)));
        return campos;
    }
}
