package br.com.rbs.portal.documentos;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] As rotas de leitura (55, 56 e 58), o isolamento da marca (rota 56 pelo escopo; rota 18 fechada para os arquivos de
 * documento) e o histórico (a rota 20 não mostra documento à marca; a RBS vê tudo na 21). Usam o kit confirmado no banco de teste.
 */
@TesteDeIntegracao
@Transactional
class DocumentoLeituraTest extends ApoioDeDocumentos {

    private static final Set<String> CAMPOS_DA_MARCA = Set.of("id", "titulo", "tipo", "versao", "paginas", "substituido");

    private MvcTestResult ver(SessaoDeApi pessoa, String idDoKit) {
        return pessoa.get("/api/documentos/" + doKit(idDoKit));
    }

    // ---------------------------------------------------------------------
    // Rota 55: a lista
    // ---------------------------------------------------------------------

    @Test
    void aRbsListaOsDocumentosDoMaisNovoParaOMaisAntigoComPagina() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult lista = rbs.get("/api/documentos?tamanho=200");

        assertThat(lista).hasStatus(HttpStatus.OK);
        assertThat(lerJson(lista, "$.pagina")).isEqualTo(0);
        assertThat(lerJson(lista, "$.tamanho")).isEqualTo(200);
        assertThat(((Number) lerJson(lista, "$.total")).intValue()).isGreaterThanOrEqualTo(13);
        List<String> titulos = lista(lista, "$.itens[*].titulo").stream().map(Object::toString).toList();
        assertThat(titulos).contains("Manual de Operações 2027", "Plano comercial · Cota Master 2027", "Anexo da parceria · Cliente Amarelo",
                "Lista de credenciados · Cliente Verde");
        assertThat(lista(lista, "$.itens[0].acoes")).isNotNull();
    }

    @Test
    void osTitulosDoKitSaoOsQueOAvaliadorReconhece() {
        List<String> titulos = jdbc.sql("""
                        SELECT d.titulo || ' | ' || d.versao || ' | ' || e.edicao FROM documento d JOIN evento e ON e.id = d.evento_id
                        WHERE d.arquivo_id IN (SELECT id FROM arquivo WHERE nome_original LIKE 'd__-%') ORDER BY d.id
                        """).query(String.class).list();

        assertThat(titulos).containsExactly(
                "Manual de Operações 2027 | 1 | 2027", "Manual de Operações 2027 | 2 | 2027",
                "Plano comercial · Cota Master 2027 | 1 | 2027", "Plano comercial · Subcota G 2027 | 1 | 2027",
                "Plano comercial · Subcota 2027 | 1 | 2027", "Anexo da parceria · Cliente Amarelo | 1 | 2027",
                "Anexo da parceria · Cliente Roxo | 1 | 2027", "FAQ do patrocinador 2027 | 1 | 2027",
                "Comunicado 12/2026 · Janela extra de abastecimento | 1 | 2027", "Manual de Operações 2026 | 1 | 2026",
                "Lista de credenciados · Cliente Verde | 1 | 2027", "Aviso digitalizado · pontos de apoio | 1 | 2027",
                "Circular 07/2026 · Pulseiras prata | 1 | 2027");
    }

    @Test
    void osFiltrosDaListaCombinam() {
        SessaoDeApi rbs = entrar(RBS);

        assertThat(((Number) lerJson(rbs.get("/api/documentos?chave=manual-operacoes"), "$.total")).intValue()).as("D01, D02 e D10").isEqualTo(3);
        assertThat(((Number) lerJson(rbs.get("/api/documentos?chave=manual-operacoes&eventoId=" + evento(2026)), "$.total")).intValue()).as("D10").isEqualTo(1);
        assertThat(((Number) lerJson(rbs.get("/api/documentos?escopo=PARCERIA&status=INDEXADO"), "$.total")).intValue()).as("D06 e D07").isEqualTo(2);
        assertThat(((Number) lerJson(rbs.get("/api/documentos?status=ERRO&chave=documento-imagem"), "$.total")).intValue()).isEqualTo(1);
        assertThat(((Number) lerJson(rbs.get("/api/documentos?q=circular"), "$.total")).intValue()).isEqualTo(1);
        assertThat(((Number) lerJson(rbs.get("/api/documentos?q=CIRCULAR&status=REVOGADO"), "$.total")).intValue()).isEqualTo(1);
        assertThat(((Number) lerJson(rbs.get("/api/documentos?tipo=PLANO_COMERCIAL&escopo=COTA"), "$.total")).intValue()).isEqualTo(3);
        assertThat(((Number) lerJson(rbs.get("/api/documentos?status=SUBSTITUIDO&chave=manual-operacoes"), "$.total")).intValue()).as("D01").isEqualTo(1);
        assertThat(((Number) lerJson(rbs.get("/api/documentos?q=nao-existe-este-titulo"), "$.total")).intValue()).isZero();
    }

    @Test
    void aListaPaginaSemRepetirNemPerderDocumento() {
        SessaoDeApi rbs = entrar(RBS);
        int total = ((Number) lerJson(rbs.get("/api/documentos"), "$.total")).intValue();
        Set<Integer> vistos = new TreeSet<>();

        for (int pagina = 0; pagina * 5 < total; pagina++) {
            MvcTestResult resposta = rbs.get("/api/documentos?pagina=" + pagina + "&tamanho=5");
            for (Object id : lista(resposta, "$.itens[*].id")) {
                assertThat(vistos.add(((Number) id).intValue())).as("id repetido").isTrue();
            }
        }
        assertThat(vistos).hasSize(total);
    }

    @Test
    void filtroInvalidoDa422ComOCampoETamanhoForaDoLimiteTambem() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult ruim = rbs.get("/api/documentos?status=TALVEZ&escopo=TUDO&tipo=PLANILHA");
        assertThat(ruim).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(mapa(ruim, "$.campos").keySet()).containsExactlyInAnyOrder("status", "escopo", "tipo");
        assertThat(rbs.get("/api/documentos?tamanho=999")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(rbs.get("/api/documentos?pagina=-1")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(rbs.get("/api/documentos?eventoId=abc")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void caractereNuloNoFiltroDeChaveOuDeTituloDa422EmCamposEmVezDe500() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult naChave = rbs.get("/api/documentos?chave={c}", "manual\u0000operacoes");
        MvcTestResult noTitulo = rbs.get("/api/documentos?q={q}", "circ\u0000ular");
        MvcTestResult nosDois = rbs.get("/api/documentos?q={q}&chave={c}", "\u0000", "\u0000");

        assertThat(naChave).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(naChave, "$.campos.chave")).isEqualTo("A chave tem caracteres inválidos.");
        assertThat(noTitulo).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(noTitulo, "$.campos.q")).isEqualTo("A busca pelo título tem caracteres inválidos.");
        assertThat(mapa(nosDois, "$.campos").keySet()).containsExactlyInAnyOrder("q", "chave");
    }

    @Test
    void marcaRecebe403NaListaAntesDeValidarOFiltro() {
        SessaoDeApi amarelo = entrar(AMARELO);

        for (String uri : List.of("/api/documentos", "/api/documentos?status=TALVEZ", "/api/documentos?tamanho=999", "/api/documentos?eventoId=abc")) {
            MvcTestResult resposta = amarelo.get(uri);
            assertThat(resposta).as(uri).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo(SO_RBS);
        }
    }

    // ---------------------------------------------------------------------
    // Rota 56: ver um documento
    // ---------------------------------------------------------------------

    @Test
    void aRbsVeODocumentoInteiroEmQualquerStatus() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult d11 = ver(rbs, "D11");
        assertThat(lerJson(d11, "$.status")).isEqualTo("AGUARDANDO_APROVACAO");
        assertThat(lerJson(d11, "$.contemDadosPessoais")).isEqualTo(true);
        assertThat(lerJson(d11, "$.parceria.patrocinador")).isEqualTo("Cliente Verde");
        assertThat(lerJson(d11, "$.escopo")).isEqualTo("PARCERIA");
        MvcTestResult d03 = ver(rbs, "D03");
        assertThat(lerJson(d03, "$.cota.codigo")).isEqualTo("MASTER");
        assertThat(lerJson(d03, "$.tipo")).isEqualTo("PLANO_COMERCIAL");
        assertThat(lerJson(d03, "$.aprovadoPor.nome")).isNotNull();
        MvcTestResult d13 = ver(rbs, "D13");
        assertThat(lerJson(d13, "$.status")).isEqualTo("REVOGADO");
        assertThat(lerJson(d13, "$.revogadoPor.perfil")).isEqualTo("ADMIN_RBS");
        assertThat(lerJson(ver(rbs, "D12"), "$.erro")).isEqualTo("Sem texto extraível; OCR fora do escopo.");
    }

    @Test
    void aMarcaVeSoOQueOEscopoDelaPermiteESoOsSeisCampos() {
        // [documento do kit, esperado] para cada marca: GLOBAL da edição, COTA da cota dela, PARCERIA da própria parceria; só INDEXADO ou SUBSTITUIDO
        Map<String, Map<String, Boolean>> esperado = Map.of(
                AMARELO, Map.ofEntries(Map.entry("D01", true), Map.entry("D02", true), Map.entry("D03", true), Map.entry("D04", false),
                        Map.entry("D05", false), Map.entry("D06", true), Map.entry("D07", false), Map.entry("D08", true),
                        Map.entry("D09", true), Map.entry("D10", false), Map.entry("D11", false), Map.entry("D12", false),
                        Map.entry("D13", false)),
                ROXO, Map.ofEntries(Map.entry("D01", true), Map.entry("D02", true), Map.entry("D03", false), Map.entry("D04", true),
                        Map.entry("D05", false), Map.entry("D06", false), Map.entry("D07", true), Map.entry("D08", true),
                        Map.entry("D09", true), Map.entry("D10", false), Map.entry("D11", false), Map.entry("D12", false),
                        Map.entry("D13", false)),
                VERDE, Map.ofEntries(Map.entry("D01", true), Map.entry("D02", true), Map.entry("D03", false), Map.entry("D04", false),
                        Map.entry("D05", true), Map.entry("D06", false), Map.entry("D07", false), Map.entry("D08", true),
                        Map.entry("D09", true), Map.entry("D10", false), Map.entry("D11", false), Map.entry("D12", false),
                        Map.entry("D13", false)));

        esperado.forEach((email, documentos) -> {
            SessaoDeApi marca = entrar(email);
            documentos.forEach((idDoKit, pode) -> {
                MvcTestResult resposta = ver(marca, idDoKit);
                if (pode) {
                    assertThat(resposta).as(email + " " + idDoKit).hasStatus(HttpStatus.OK);
                    assertThat(mapa(resposta, "$").keySet()).as(email + " " + idDoKit).isEqualTo(CAMPOS_DA_MARCA);
                } else {
                    assertThat(resposta).as(email + " " + idDoKit).hasStatus(HttpStatus.NOT_FOUND);
                    assertThat(lerJson(resposta, "$.detail")).isEqualTo(NAO_ENCONTRADO);
                }
            });
        });
    }

    @Test
    void aMarcaSoVeATituloTipoVersaoPaginasEOAvisoDeSubstituido() {
        SessaoDeApi amarelo = entrar(AMARELO);

        MvcTestResult v1 = ver(amarelo, "D01");
        MvcTestResult v2 = ver(amarelo, "D02");

        assertThat(lerJson(v1, "$.titulo")).isEqualTo("Manual de Operações 2027");
        assertThat(lerJson(v1, "$.tipo")).isEqualTo("MANUAL_OPERACOES");
        assertThat(lerJson(v1, "$.versao")).isEqualTo(1);
        assertThat(lerJson(v1, "$.paginas")).isEqualTo(11);
        assertThat(lerJson(v1, "$.substituido")).as("a v1 foi substituída: aviso").isEqualTo(true);
        assertThat(lerJson(v2, "$.versao")).isEqualTo(2);
        assertThat(lerJson(v2, "$.substituido")).isEqualTo(false);
        // nunca: erro, dado pessoal, autoria, escopo, chave, arquivo ou caminho
        String corpo = br.com.rbs.portal.ApoioDeTeste.corpo(v2);
        assertThat(corpo).doesNotContain("erro", "contemDadosPessoais", "enviadoPor", "aprovadoPor", "revogadoPor", "escopo", "chave", "arquivo",
                "caminho", "modelo", "trechos");
    }

    @Test
    void documentoInexistenteOuIdQueNaoENumeroDa404ParaAsDuasPontas() {
        for (String email : List.of(RBS, AMARELO)) {
            SessaoDeApi pessoa = entrar(email);
            MvcTestResult inexistente = pessoa.get("/api/documentos/999999");
            MvcTestResult naoNumero = pessoa.get("/api/documentos/abc");
            assertThat(inexistente).as(email).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(lerJson(inexistente, "$.detail")).isEqualTo(NAO_ENCONTRADO);
            assertThat(naoNumero).as(email).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(lerJson(naoNumero, "$.detail")).isEqualTo(NAO_ENCONTRADO);
        }
    }

    @Test
    void semSessaoDa401NasRotasDeLeituraEDeStatus() {
        SessaoDeApi anonima = SessaoDeApi.anonima(mvc);

        assertThat(anonima.get("/api/documentos")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(anonima.get("/api/documentos/1")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(anonima.get("/api/documentos/1/trechos")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(anonima.patch("/api/documentos/1/status", "{\"acao\":\"APROVAR\"}")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    // ---------------------------------------------------------------------
    // Rota 58: os trechos
    // ---------------------------------------------------------------------

    @Test
    void aRbsVeOsTrechosDoDocumentoEmOrdemComPaginaSecaoTokensEOComecoDoTexto() {
        SessaoDeApi rbs = entrar(RBS);
        long d02 = doKit("D02");

        MvcTestResult resposta = rbs.get("/api/documentos/" + d02 + "/trechos?tamanho=5");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(((Number) lerJson(resposta, "$.total")).longValue()).isEqualTo(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ?", d02));
        assertThat(lerJson(resposta, "$.itens[0].ordem")).isEqualTo(1);
        assertThat(lerJson(resposta, "$.itens[0].paginaInicio")).isEqualTo(1);
        assertThat(lerJson(resposta, "$.itens[0].secao")).isEqualTo("PLANETA 360 · OPERAÇÕES DO FESTIVAL");
        // a capa (página 1) e o sumário (página 2) são dois trechos: um trecho nunca atravessa página
        assertThat(lerJson(resposta, "$.itens[1].paginaInicio")).isEqualTo(2);
        assertThat(lerJson(resposta, "$.itens[1].secao")).isEqualTo("PLANETA 360 · OPERAÇÕES DO FESTIVAL");
        assertThat(lerJson(resposta, "$.itens[2].secao")).isEqualTo("1 Apresentação");
        for (int i = 0; i < 5; i++) {
            assertThat(lerJson(resposta, "$.itens[" + i + "].paginaFim")).as("trecho " + (i + 1)).isEqualTo(lerJson(resposta, "$.itens[" + i + "].paginaInicio"));
        }
        assertThat(lerJson(resposta, "$.itens[0].ativo")).isEqualTo(true);
        assertThat((Integer) lerJson(resposta, "$.itens[0].tokens")).isGreaterThan(0);
        assertThat(((String) lerJson(resposta, "$.itens[2].inicioDoTexto"))).startsWith("1.1 Objetivo deste manual").hasSizeLessThanOrEqualTo(240);
        assertThat(lista(resposta, "$.itens").size()).isEqualTo(5);
        assertThat(((Number) lerJson(rbs.get("/api/documentos/" + d02 + "/trechos?pagina=1&tamanho=5"), "$.itens[0].ordem")).intValue()).isEqualTo(6);
    }

    @Test
    void osTrechosDeUmaVersaoSubstituidaApareceminativosEOsDeUmDocumentoSemIndexacaoNaoExistem() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult v1 = rbs.get("/api/documentos/" + doKit("D01") + "/trechos?tamanho=200");
        MvcTestResult d12 = rbs.get("/api/documentos/" + doKit("D12") + "/trechos");

        assertThat(lista(v1, "$.itens[*].ativo")).isNotEmpty().containsOnly(false);
        assertThat(lerJson(d12, "$.total")).isEqualTo(0);
        assertThat(lista(d12, "$.itens")).isEmpty();
    }

    @Test
    void trechosDeDocumentoInexistenteDa404EAMarcaRecebe403AntesDeTudo() {
        SessaoDeApi rbs = entrar(RBS);
        SessaoDeApi amarelo = entrar(AMARELO);

        MvcTestResult inexistente = rbs.get("/api/documentos/999999/trechos");
        assertThat(inexistente).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(inexistente, "$.detail")).isEqualTo(NAO_ENCONTRADO);
        assertThat(rbs.get("/api/documentos/abc/trechos")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(rbs.get("/api/documentos/" + doKit("D02") + "/trechos?tamanho=999")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        for (String uri : List.of("/api/documentos/" + doKit("D06") + "/trechos", "/api/documentos/999999/trechos", "/api/documentos/1/trechos?tamanho=999")) {
            MvcTestResult resposta = amarelo.get(uri);
            assertThat(resposta).as(uri).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo(SO_RBS);
        }
    }

    // ---------------------------------------------------------------------
    // Rota 18 fechada para os arquivos de documento (bloqueante)
    // ---------------------------------------------------------------------

    @Test
    void aMarcaRecebe404NaRota18DoArquivoDeQualquerDocumentoMesmoODaPropriaParceria() {
        // D06 é o anexo do próprio Amarelo, já indexado; D11 é da Verde, com CPFs; D13 está revogado; D02 é global e indexado
        for (String email : List.of(AMARELO, VERDE)) {
            SessaoDeApi marca = entrar(email);
            for (String idDoKit : List.of("D01", "D02", "D03", "D06", "D07", "D08", "D10", "D11", "D12", "D13")) {
                MvcTestResult resposta = marca.get("/api/arquivos/" + arquivoDoKit(idDoKit));
                assertThat(resposta).as(email + " baixando o arquivo do " + idDoKit).hasStatus(HttpStatus.NOT_FOUND);
                assertThat(lerJson(resposta, "$.detail")).isEqualTo("Não encontramos este arquivo.");
            }
        }
    }

    @Test
    void aRbsBaixaOArquivoDoDocumentoEODownloadFicaNaEntidadeDocumento() {
        SessaoDeApi rbs = entrar(RBS);
        long d11 = doKit("D11");
        long antes = contar("SELECT count(*) FROM historico WHERE acao = 'DOWNLOAD' AND entidade = 'arquivo'");

        MvcTestResult resposta = rbs.get("/api/arquivos/" + arquivoDoKit("D11"));

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(resposta.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(resposta.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(contar("SELECT count(*) FROM historico WHERE acao = 'DOWNLOAD' AND entidade = 'arquivo'")).isEqualTo(antes);
        assertThat(contar("SELECT count(*) FROM historico WHERE acao = 'DOWNLOAD' AND entidade = 'documento' AND entidade_id = ?", d11)).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // Rota 20: a marca não vê linha de documento (bloqueante)
    // ---------------------------------------------------------------------

    @Test
    void aMarcaNaoVeLinhaDeDocumentoNaRota20NemNaPropriaParceriaMasARbsVeTudo() {
        SessaoDeApi rbs = entrar(RBS);
        SessaoDeApi amarelo = entrar(AMARELO);
        long parceria = parceria("Cliente Amarelo");
        // o D06 é o anexo do Amarelo: o envio (CRIACAO) e a aprovação ficam na parceria dele; baixar o arquivo grava DOWNLOAD
        rbs.get("/api/arquivos/" + arquivoDoKit("D06"));
        long linhasDeDocumento = contar("SELECT count(*) FROM historico WHERE parceria_id = ? AND entidade = 'documento'", parceria);
        assertThat(linhasDeDocumento).as("CRIACAO, as mudanças de status e o DOWNLOAD").isGreaterThanOrEqualTo(4);

        MvcTestResult daMarca = amarelo.get("/api/parcerias/" + parceria + "/historico?tamanho=200");
        MvcTestResult daRbs = rbs.get("/api/parcerias/" + parceria + "/historico?tamanho=200");
        MvcTestResult filtrado = amarelo.get("/api/parcerias/" + parceria + "/historico?entidade=documento");
        MvcTestResult porAcao = amarelo.get("/api/parcerias/" + parceria + "/historico?acao=CRIACAO,ALTERACAO_STATUS,DOWNLOAD&tamanho=200");

        assertThat(lista(daMarca, "$.itens[?(@.entidade == 'documento')]")).isEmpty();
        assertThat(lerJson(filtrado, "$.total")).isEqualTo(0);
        assertThat(lista(porAcao, "$.itens[?(@.entidade == 'documento')]")).isEmpty();
        // a RBS vê o que a marca vê, mais as linhas de documento e as etapas internas do relatório (geração e validação)
        long internas = contar("SELECT count(*) FROM historico WHERE parceria_id = ? AND entidade <> 'documento' AND acao IN ('GERACAO_RELATORIO', 'VALIDACAO')",
                parceria);
        assertThat(((Number) lerJson(daRbs, "$.total")).longValue()).isEqualTo(((Number) lerJson(daMarca, "$.total")).longValue() + linhasDeDocumento + internas);
        assertThat(lista(daRbs, "$.itens[?(@.entidade == 'documento')]")).isNotEmpty();
    }

    @Test
    void aRbsVeAsLinhasDeDocumentoNoHistoricoGeralEnaDaParceria() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult geral = rbs.get("/api/historico?entidade=documento&tamanho=200");

        assertThat(((Number) lerJson(geral, "$.total")).intValue()).as("pelo menos o envio de cada um dos 13").isGreaterThanOrEqualTo(13);
        assertThat(lista(geral, "$.itens[?(@.acao == 'CRIACAO')]")).hasSizeGreaterThanOrEqualTo(13);
        assertThat(lista(geral, "$.itens[?(@.valorNovo == 'INDEXADO')]")).isNotEmpty();
        assertThat(lista(geral, "$.itens[?(@.valorNovo == 'REVOGADO')]")).isNotEmpty();
    }
}
