package br.com.rbs.portal.materiais;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
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
 * R5.2.a: os espaços do MIV e da logo (rota 72) e o campo {@code papel} da entrega (rotas 14, 15, 16 e 19). O primeiro envio cria a
 * entrega, e os seguintes são versões, também depois de aprovado. [UPLOAD] [PERMISSAO] [ISOLAMENTO] Cada teste é desfeito no fim.
 */
@TesteDeIntegracao
@Transactional
class EspacosDaMarcaTest extends ApoioDeEspacos {

    private static final String FRASE_DOS_FORMATOS =
            "Para o MIV e a logo, envie PNG, JPG, PDF ou ZIP. Arquivos .svg e .ai só dentro de um .zip.";

    private String envio(long parceria, String papel) {
        return "/api/parcerias/" + parceria + "/espacos/" + papel + "/arquivos";
    }

    /** O Verde não tem MIV nem logo na amostra: o primeiro envio dele cria o espaço. */
    @Test
    void primeiroEnvioCriaOEspacoEOsSeguintesSaoVersoes() {
        long verde = verde();
        SessaoDeApi marca = entrar(VERDE);
        assertThat(contar("SELECT count(*) FROM entrega WHERE parceria_id = ? AND papel = 'MIV'", verde)).isZero();

        MvcTestResult v1 = enviarAoEspaco(marca, verde, "MIV", "miv.pdf", pdf("miv um"));
        MvcTestResult v2 = enviarAoEspaco(marca, verde, "MIV", "miv.zip", zip());

        assertThat(entregaDoEspaco(v2)).as("a mesma entrega").isEqualTo(entregaDoEspaco(v1));
        assertThat(lerJson(v1, "$.versao")).isEqualTo(1);
        assertThat(lerJson(v2, "$.versao")).isEqualTo(2);
        assertThat(lerJson(v1, "$.statusDaEntrega")).isEqualTo("ENVIADO");
        assertThat(contar("SELECT count(*) FROM entrega e JOIN aba a ON a.id = e.aba_id WHERE e.parceria_id = ? AND e.papel = 'MIV' "
                + "AND a.codigo = 'APLICACOES' AND e.status = 'ENVIADO'", verde)).isEqualTo(1);
        assertThat(historico("CRIACAO", "entrega", entregaDoEspaco(v1))).hasSize(1);
        assertThat(historico("ENVIO_ARQUIVO", "entrega", entregaDoEspaco(v1))).hasSize(1);
        assertThat(historico("NOVA_VERSAO", "entrega", entregaDoEspaco(v1))).hasSize(1);
    }

    @Test
    void mivELogoSaoEspacosSeparadosEAGetMostraAsVersoes() {
        long verde = verde();
        SessaoDeApi marca = entrar(VERDE);
        MvcTestResult miv = enviarAoEspaco(marca, verde, "MIV", "miv.pdf", pdf("miv"));
        MvcTestResult logo = enviarAoEspaco(marca, verde, "LOGO", "logo.png", png());
        enviarAoEspaco(marca, verde, "LOGO", "logo2.png", png());

        assertThat(entregaDoEspaco(logo)).isNotEqualTo(entregaDoEspaco(miv));
        MvcTestResult espacos = marca.get("/api/parcerias/" + verde + "/espacos");
        assertThat(espacos).hasStatus(HttpStatus.OK);
        assertThat(lerJson(espacos, "$.miv.papel")).isEqualTo("MIV");
        assertThat(lerLista(espacos, "$.miv.arquivos")).hasSize(1);
        assertThat(lerJson(espacos, "$.logo.papel")).isEqualTo("LOGO");
        assertThat(lerLista(espacos, "$.logo.arquivos")).hasSize(2);
        assertThat(lerJson(espacos, "$.logo.arquivos[0].versao")).isEqualTo(2);
        assertThat(lerJson(espacos, "$.logo.status")).isEqualTo("ENVIADO");
    }

    @Test
    void semEnvioOMivEALogoVemNulosParaAMarcaEParaARbs() {
        long verde = verde();
        for (SessaoDeApi pessoa : new SessaoDeApi[] {entrar(VERDE), entrar(RBS)}) {
            MvcTestResult espacos = pessoa.get("/api/parcerias/" + verde + "/espacos");

            assertThat(espacos).hasStatus(HttpStatus.OK);
            assertThat(lerJson(espacos, "$.miv")).isNull();
            assertThat(lerJson(espacos, "$.logo")).isNull();
            assertThat(lerLista(espacos, "$.solicitacoesIngressos")).as("só as da amostra").hasSize(
                    (int) contar("SELECT count(*) FROM entrega WHERE parceria_id = ? AND papel = 'DOCUMENTO_INGRESSOS'", verde));
            assertThat(lerLista(espacos, "$.formularioIngressos.versoes")).isEmpty();
        }
    }

    @Test
    void marcaEnviaNovaVersaoEmQualquerStatusEAEntregaVoltaAEnviado() {
        long verde = verde();
        SessaoDeApi marca = entrar(VERDE);
        SessaoDeApi rbs = entrar(RBS);
        MvcTestResult primeiro = enviarAoEspaco(marca, verde, "LOGO", "logo.png", png());
        long entrega = entregaDoEspaco(primeiro);
        int versao = 1;

        // Aprovada pela RBS pelo caminho normal.
        assertThat(rbs.patch("/api/entregas/" + entrega + "/status", "{\"status\":\"APROVADO\"}")).hasStatus(HttpStatus.OK);
        MvcTestResult depoisDeAprovado = enviarAoEspaco(marca, verde, "LOGO", "logo_nova.png", png());
        versao++;
        assertThat(lerJson(depoisDeAprovado, "$.versao")).isEqualTo(versao);
        assertThat(lerJson(depoisDeAprovado, "$.statusDaEntrega")).isEqualTo("ENVIADO");
        assertThat(statusDaEntrega(entrega)).isEqualTo("ENVIADO");
        assertThat(historico("ALTERACAO_STATUS", "entrega", entrega)).as("o primeiro envio, APROVADO e ENVIADO de novo").hasSize(3);

        // Os demais status, postos direto no banco.
        for (String status : new String[] {"EM_ANALISE", "AJUSTE_SOLICITADO", "NAO_APLICAVEL", "NAO_INICIADO", "AGUARDANDO_ENVIO"}) {
            definirStatus(entrega, status);
            MvcTestResult resposta = enviarAoEspaco(marca, verde, "LOGO", "logo_" + status + ".png", png());
            versao++;
            assertThat(lerJson(resposta, "$.versao")).as(status).isEqualTo(versao);
            assertThat(lerJson(resposta, "$.statusDaEntrega")).as(status).isEqualTo("ENVIADO");
            assertThat(statusDaEntrega(entrega)).as(status).isEqualTo("ENVIADO");
        }
    }

    @Test
    void retornoDaRbsNoEspacoNaoMudaOStatus() {
        long verde = verde();
        SessaoDeApi rbs = entrar(RBS);
        MvcTestResult primeiro = enviarAoEspaco(rbs, verde, "MIV", "miv.pdf", pdf("rbs"));

        assertThat(lerJson(primeiro, "$.statusDaEntrega")).as("o primeiro envio da RBS não finge ser da marca")
                .isEqualTo("AGUARDANDO_ENVIO");
        MvcTestResult segundo = enviarAoEspaco(rbs, verde, "MIV", "miv2.pdf", pdf("rbs 2"));
        assertThat(lerJson(segundo, "$.versao")).isEqualTo(2);
        assertThat(statusDaEntrega(entregaDoEspaco(primeiro))).isEqualTo("AGUARDANDO_ENVIO");
    }

    @Test
    void categoriaDoCorpoEIgnoradaEPapelEAbaSaoFixos() {
        long verde = verde();

        MvcTestResult miv = enviarPara(entrar(VERDE), envio(verde, "MIV"), "miv.pdf", pdf("x"), "categoria", "PLANO_COMERCIAL");
        MvcTestResult logo = enviarPara(entrar(VERDE), envio(verde, "LOGO"), "logo.png", png(), "categoria", "CONTRATO", "abaCodigo",
                "MIDIA");

        assertThat(miv).hasStatus(HttpStatus.CREATED);
        assertThat(logo).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(miv, "$.categoria")).isEqualTo("MATERIAL_MARCA");
        assertThat(lerJson(logo, "$.categoria")).isEqualTo("MATERIAL_MARCA");
        assertThat(contar("SELECT count(*) FROM arquivo WHERE categoria = 'PLANO_COMERCIAL' AND nome_original = 'miv.pdf'")).isZero();
        assertThat(contar("SELECT count(*) FROM entrega e JOIN aba a ON a.id = e.aba_id WHERE e.id = ? AND a.codigo = 'APLICACOES'",
                entregaDoEspaco(logo))).isEqualTo(1);
    }

    @Test
    void svgEAiSoltosSaoRecusadosComAFraseEDentroDeZipPassa() {
        long verde = verde();
        SessaoDeApi marca = entrar(VERDE);

        for (String nome : new String[] {"logo.svg", "logo.ai", "LOGO.SVG", "logo.docx", "logo.exe"}) {
            MvcTestResult resposta = enviarPara(marca, envio(verde, "LOGO"), nome, pdf("<svg/>"));
            assertThat(resposta).as(nome).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            assertThat(lerJson(resposta, "$.detail")).as(nome).isEqualTo(FRASE_DOS_FORMATOS);
        }
        assertThat(contar("SELECT count(*) FROM arquivo WHERE parceria_id = ? AND nome_original ILIKE 'logo.%'", verde)).isZero();

        MvcTestResult dentroDoZip = enviarAoEspaco(marca, verde, "LOGO", "logo_vetor.zip", zip());
        assertThat(lerJson(dentroDoZip, "$.mimeType")).isEqualTo("application/zip");
        assertThat(enviarPara(marca, envio(verde, "MIV"), "miv.jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).hasStatus(
                HttpStatus.CREATED);
    }

    @Test
    void bytesQueNaoBatemComAExtensaoDao415EVazioOuAusenteDa422() {
        long verde = verde();
        SessaoDeApi marca = entrar(VERDE);

        assertThat(enviarPara(marca, envio(verde, "LOGO"), "logo.png", pdf("nao sou png"))).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(enviarPara(marca, envio(verde, "LOGO"), "logo.png", new byte[0])).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        MvcTestResult ausente = enviarSemArquivo(marca, envio(verde, "LOGO"));
        assertThat(ausente).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(ausente, "$.campos.arquivo")).isNotNull();
        assertThat(enviarPara(marca, envio(verde, "LOGO"), "logo.png", png(), "observacao", "x".repeat(501)))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void papelInexistenteDa404EOutraMarcaDa404SemCriarNada() {
        long doVerde = verde();
        long entregasAntes = contar("SELECT count(*) FROM entrega WHERE parceria_id = ?", doVerde);

        assertThat(enviarPara(entrar(VERDE), envio(doVerde, "FOTO"), "x.png", png())).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(enviarPara(entrar(VERDE), envio(doVerde, "miv"), "x.png", png())).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(enviarPara(entrar(ROXO), envio(doVerde, "MIV"), "x.png", png())).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(entrar(ROXO).get("/api/parcerias/" + doVerde + "/espacos")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(contar("SELECT count(*) FROM entrega WHERE parceria_id = ?", doVerde)).isEqualTo(entregasAntes);
    }

    @Test
    void aplicacoesFechadaOuOcultaDa403SemCriarAEntrega() {
        long verde = verde();
        long entregasAntes = contar("SELECT count(*) FROM entrega WHERE parceria_id = ?", verde);
        fecharAba(verde, "APLICACOES", false, true);

        MvcTestResult fechada = enviarPara(entrar(VERDE), envio(verde, "MIV"), "miv.pdf", pdf("x"));
        assertThat(fechada).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(lerJson(fechada, "$.detail")).isEqualTo("Esta aba ainda não foi liberada para a sua parceria.");
        fecharAba(verde, "APLICACOES", true, false);
        assertThat(enviarPara(entrar(VERDE), envio(verde, "LOGO"), "logo.png", png())).as("oculta").hasStatus(HttpStatus.FORBIDDEN);

        assertThat(contar("SELECT count(*) FROM entrega WHERE parceria_id = ?", verde)).isEqualTo(entregasAntes);
        assertThat(entrar(VERDE).get("/api/parcerias/" + verde + "/espacos")).as("sem aba, a marca precisa das duas").hasStatus(
                HttpStatus.FORBIDDEN);
        MvcTestResult soPerfil = entrar(VERDE).get("/api/parcerias/" + verde + "/espacos?aba=PERFIL");
        assertThat(soPerfil).hasStatus(HttpStatus.OK);
        assertThat(lerJson(soPerfil, "$.miv")).isNull();
        assertThat(entrar(VERDE).get("/api/parcerias/" + verde + "/espacos?aba=APLICACOES")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(entrar(VERDE).get("/api/parcerias/" + verde + "/espacos?aba=MIDIA")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(entrar(RBS).get("/api/parcerias/" + verde + "/espacos")).as("a RBS sempre vê").hasStatus(HttpStatus.OK);
    }

    @Test
    void parceriaEncerradaRecusaOEnvioParaAMarcaEParaARbs() {
        long verde = verde();
        long comPapelAntes = contar("SELECT count(*) FROM entrega WHERE parceria_id = ? AND papel IS NOT NULL", verde);
        encerrar(verde);

        for (SessaoDeApi pessoa : new SessaoDeApi[] {entrar(VERDE), entrar(RBS)}) {
            MvcTestResult resposta = enviarPara(pessoa, envio(verde, "LOGO"), "logo.png", png());
            assertThat(resposta).hasStatus(HttpStatus.CONFLICT);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo("Esta parceria está encerrada e não aceita novos envios.");
        }
        assertThat(contar("SELECT count(*) FROM entrega WHERE parceria_id = ? AND papel IS NOT NULL", verde)).isEqualTo(comPapelAntes);
    }

    @Test
    void semSessao401SemCsrf403() {
        long verde = verde();

        assertThat(enviarSemSessao(envio(verde, "MIV"), "miv.pdf", pdf("x"))).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(SessaoDeApi.anonima(mvc).get("/api/parcerias/" + verde + "/espacos")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(enviarSemCsrf(entrar(VERDE), envio(verde, "MIV"), "miv.pdf", pdf("x"))).hasStatus(HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------------
    // Rota 15 e o campo papel
    // ---------------------------------------------------------------------

    @Test
    void rota15AceitaPapelComAAbaFixaUmaSoVezParaMivELogoEDevolvePapelNasRotas14E16E19() {
        long verde = verde();
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult criada = rbs.post("/api/parcerias/" + verde + "/entregas",
                "{\"abaCodigo\":\"APLICACOES\",\"titulo\":\"MIV da marca\",\"papel\":\"MIV\"}");
        assertThat(criada).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(criada, "$.papel")).isEqualTo("MIV");
        assertThat(lerJson(criada, "$.status")).isEqualTo("AGUARDANDO_ENVIO");

        MvcTestResult repetida = rbs.post("/api/parcerias/" + verde + "/entregas",
                "{\"abaCodigo\":\"APLICACOES\",\"titulo\":\"MIV de novo\",\"papel\":\"MIV\"}");
        assertThat(repetida).hasStatus(HttpStatus.CONFLICT);
        MvcTestResult abaErrada = rbs.post("/api/parcerias/" + verde + "/entregas",
                "{\"abaCodigo\":\"PERFIL\",\"titulo\":\"Logo\",\"papel\":\"LOGO\"}");
        assertThat(abaErrada).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(abaErrada, "$.campos.papel")).isEqualTo("Este espaço é da aba Aplicações.");
        MvcTestResult papelInvalido = rbs.post("/api/parcerias/" + verde + "/entregas",
                "{\"abaCodigo\":\"PERFIL\",\"titulo\":\"X\",\"papel\":\"FOTO\"}");
        assertThat(papelInvalido).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(entrar(VERDE).post("/api/parcerias/" + verde + "/entregas",
                "{\"abaCodigo\":\"APLICACOES\",\"titulo\":\"x\",\"papel\":\"LOGO\"}")).hasStatus(HttpStatus.FORBIDDEN);

        // Duas entregas de ingressos na mesma parceria são válidas (cada uma é uma solicitação).
        for (int i = 0; i < 2; i++) {
            assertThat(rbs.post("/api/parcerias/" + verde + "/entregas",
                    "{\"abaCodigo\":\"PERFIL\",\"titulo\":\"Pedido " + i + "\",\"papel\":\"DOCUMENTO_INGRESSOS\"}"))
                    .hasStatus(HttpStatus.CREATED);
        }

        // Rota 14: o papel aparece; a entrega comum vem com papel nulo.
        List<Map<String, Object>> entregas = lerLista(entrar(VERDE).get("/api/parcerias/" + verde + "/entregas?aba=APLICACOES"), "$");
        assertThat(entregas).anySatisfy(e -> assertThat(e.get("papel")).isEqualTo("MIV"));
        assertThat(entregas).anySatisfy(e -> assertThat(e).containsKey("papel").containsEntry("papel", null));

        // Rota 16: o item da fila traz o papel.
        MvcTestResult fila = rbs.get("/api/entregas?parceriaId=" + verde + "&abaCodigo=APLICACOES&tamanho=200");
        List<Map<String, Object>> itens = lerLista(fila, "$.itens");
        assertThat(itens).anySatisfy(e -> assertThat(e.get("papel")).isEqualTo("MIV"));

        // Rota 19: a entrega devolvida ao mudar o status traz o papel.
        long id = id(criada);
        assertThat(lerJson(rbs.patch("/api/entregas/" + id + "/status", "{\"status\":\"NAO_APLICAVEL\"}"), "$.papel")).isEqualTo("MIV");
    }

    @Test
    void oPrimeiroEnvioDepoisDeUmaEntregaCriadaPelaRota15AproveitaAMesmaEntrega() {
        long verde = verde();
        SessaoDeApi rbs = entrar(RBS);
        long criada = id(rbs.post("/api/parcerias/" + verde + "/entregas",
                "{\"abaCodigo\":\"APLICACOES\",\"titulo\":\"Logo da Verde\",\"papel\":\"LOGO\",\"status\":\"AGUARDANDO_ENVIO\"}"));

        MvcTestResult envio = enviarAoEspaco(entrar(VERDE), verde, "LOGO", "logo.png", png());

        assertThat(entregaDoEspaco(envio)).isEqualTo(criada);
        assertThat(lerJson(envio, "$.versao")).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM entrega WHERE parceria_id = ? AND papel = 'LOGO'", verde)).isEqualTo(1);
    }
}
