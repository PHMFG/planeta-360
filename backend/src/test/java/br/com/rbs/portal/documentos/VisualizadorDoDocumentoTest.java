package br.com.rbs.portal.documentos;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] [ISOLAMENTO] [VALIDACAO] O visualizador dos documentos (rotas 59 e 60, M4; critério bloqueante): o arquivo do documento e um
 * trecho inteiro, para a tela abrir a fonte de uma resposta do Atlas na página certa e destacar o trecho. A marca só chega ao que a
 * rota 56 mostra a ela: o escopo (GLOBAL da edição, COTA da cota dela, PARCERIA da própria parceria) e o status (indexado ou
 * substituído). O resto dá 404 com a frase de sempre, inclusive o documento revogado, o recusado, o que ainda espera aprovação, o de
 * outra edição, cota ou parceria, e o trecho que não é do documento da URL. A RBS abre tudo. Cada abertura grava DOWNLOAD na entidade
 * documento (a marca não vê essas linhas na rota 20). Usam o kit confirmado no banco de teste; cada teste é desfeito no fim.
 */
@TesteDeIntegracao
@Transactional
class VisualizadorDoDocumentoTest extends ApoioDeDocumentos {

    /** O que cada marca abre do kit (a mesma matriz da rota 56): GLOBAL da edição 2027, COTA da cota dela, PARCERIA da dela. */
    private static final Map<String, Set<String>> ABRE = Map.of(
            AMARELO, Set.of("D01", "D02", "D03", "D06", "D08", "D09"),
            ROXO, Set.of("D01", "D02", "D04", "D07", "D08", "D09"),
            VERDE, Set.of("D01", "D02", "D05", "D08", "D09"));

    private static final List<String> TODOS = List.of("D01", "D02", "D03", "D04", "D05", "D06", "D07", "D08", "D09", "D10", "D11", "D12", "D13");

    private MvcTestResult conteudo(SessaoDeApi pessoa, long documento) {
        return pessoa.get("/api/documentos/" + documento + "/conteudo");
    }

    private MvcTestResult trecho(SessaoDeApi pessoa, long documento, long trecho) {
        return pessoa.get("/api/documentos/" + documento + "/trechos/" + trecho);
    }

    /** O primeiro trecho do documento no banco (a RBS o enxerga em qualquer status). */
    private long primeiroTrecho(long documento) {
        return jdbc.sql("SELECT id FROM documento_trecho WHERE documento_id = :d ORDER BY ordem LIMIT 1").param("d", documento)
                .query(Long.class).single();
    }

    private long aberturas(long documento) {
        return contar("SELECT count(*) FROM historico WHERE acao = 'DOWNLOAD' AND entidade = 'documento' AND entidade_id = ?", documento);
    }

    // ---------------------------------------------------------------------
    // Rota 59: o arquivo
    // ---------------------------------------------------------------------

    @Test
    void aMarcaAbreSoOArquivoQueARota56MostraEOsOutrosDao404ComAMesmaFrase() {
        ABRE.forEach((email, abre) -> {
            SessaoDeApi marca = entrar(email);
            for (String idDoKit : TODOS) {
                long documento = doKit(idDoKit);
                MvcTestResult arquivo = conteudo(marca, documento);
                MvcTestResult ficha = marca.get("/api/documentos/" + documento);
                if (abre.contains(idDoKit)) {
                    assertThat(arquivo).as(email + " abrindo o " + idDoKit).hasStatus(HttpStatus.OK);
                } else {
                    assertThat(arquivo).as(email + " abrindo o " + idDoKit).hasStatus(HttpStatus.NOT_FOUND);
                    assertThat(lerJson(arquivo, "$.detail")).isEqualTo(NAO_ENCONTRADO);
                }
                // a regra é a da rota 56: o arquivo abre quando, e só quando, a ficha abre
                assertThat(arquivo.getResponse().getStatus()).as(email + " " + idDoKit + ": rota 59 igual à 56")
                        .isEqualTo(ficha.getResponse().getStatus());
            }
        });
    }

    @Test
    void osDocumentosDoPlanoSaemSoParaQuemOEscopoDeixa() {
        // D01 substituída abre (com o aviso da rota 56); D03 só a Master; D04 só a Subcota G; D06 e D07 só do dono; D10 (2026) e D13 (revogado) nunca
        SessaoDeApi amarelo = entrar(AMARELO);
        SessaoDeApi roxo = entrar(ROXO);
        SessaoDeApi verde = entrar(VERDE);

        assertThat(conteudo(amarelo, doKit("D01"))).hasStatus(HttpStatus.OK);
        assertThat(lerJson(amarelo.get("/api/documentos/" + doKit("D01")), "$.substituido")).isEqualTo(true);
        assertThat(conteudo(amarelo, doKit("D03"))).hasStatus(HttpStatus.OK);
        assertThat(conteudo(roxo, doKit("D03"))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(conteudo(verde, doKit("D03"))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(conteudo(roxo, doKit("D04"))).hasStatus(HttpStatus.OK);
        assertThat(conteudo(amarelo, doKit("D04"))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(conteudo(amarelo, doKit("D06"))).hasStatus(HttpStatus.OK);
        assertThat(conteudo(roxo, doKit("D06"))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(conteudo(verde, doKit("D06"))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(conteudo(roxo, doKit("D07"))).hasStatus(HttpStatus.OK);
        assertThat(conteudo(amarelo, doKit("D07"))).hasStatus(HttpStatus.NOT_FOUND);
        for (SessaoDeApi marca : List.of(amarelo, roxo, verde)) {
            assertThat(conteudo(marca, doKit("D10"))).as("o manual de 2026 nunca sai para a edição 2027").hasStatus(HttpStatus.NOT_FOUND);
            assertThat(conteudo(marca, doKit("D13"))).as("revogado nunca sai").hasStatus(HttpStatus.NOT_FOUND);
            assertThat(conteudo(marca, doKit("D11"))).as("a lista com CPFs, ainda sem aprovação").hasStatus(HttpStatus.NOT_FOUND);
            assertThat(conteudo(marca, doKit("D12"))).as("o que deu erro").hasStatus(HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void aRbsAbreOArquivoDeQualquerDocumentoEmQualquerStatus() {
        SessaoDeApi rbs = entrar(RBS);

        for (String idDoKit : TODOS) {
            MvcTestResult arquivo = conteudo(rbs, doKit(idDoKit));
            assertThat(arquivo).as("RBS abrindo o " + idDoKit).hasStatus(HttpStatus.OK);
            assertThat(arquivo.getResponse().getContentAsByteArray()).isNotEmpty();
        }
    }

    @Test
    void osCabecalhosTiramDoArquivoQualquerPoderSobreAPagina() {
        SessaoDeApi rbs = entrar(RBS);
        SessaoDeApi amarelo = entrar(AMARELO);

        for (SessaoDeApi pessoa : List.of(rbs, amarelo)) {
            MvcTestResult pdf = conteudo(pessoa, doKit("D02"));
            assertThat(pdf).hasStatus(HttpStatus.OK);
            assertThat(pdf.getResponse().getContentType()).isEqualTo("application/pdf");
            assertThat(pdf.getResponse().getHeader("Content-Disposition")).startsWith("inline").doesNotContain("attachment")
                    .contains("d02-manual-operacoes-2027-v2.pdf");
            assertThat(pdf.getResponse().getHeaders("X-Content-Type-Options")).containsExactly("nosniff");
            assertThat(pdf.getResponse().getHeaders("Content-Security-Policy")).containsExactly("sandbox");
            assertThat(pdf.getResponse().getHeaders("Cache-Control")).containsExactly("private, no-store");
            assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
            assertThat((long) pdf.getResponse().getContentAsByteArray().length)
                    .isEqualTo(contar("SELECT tamanho_bytes FROM arquivo WHERE id = ?", arquivoDoKit("D02")));

            MvcTestResult txt = conteudo(pessoa, doKit("D08"));
            assertThat(txt).hasStatus(HttpStatus.OK);
            assertThat(txt.getResponse().getContentType()).startsWith("text/plain");
            assertThat(txt.getResponse().getHeader("Content-Disposition")).startsWith("inline");
            assertThat(txt.getResponse().getHeaders("X-Content-Type-Options")).containsExactly("nosniff");
            assertThat(txt.getResponse().getHeaders("Content-Security-Policy")).containsExactly("sandbox");
            assertThat(txt.getResponse().getHeaders("Cache-Control")).containsExactly("private, no-store");
        }
    }

    @Test
    void cadaAberturaGravaDownloadNaEntidadeDocumentoComAParceriaSoNoEscopoParceria() {
        SessaoDeApi amarelo = entrar(AMARELO);
        long parceriaAmarelo = parceria("Cliente Amarelo");
        long usuarioAmarelo = contar("SELECT id FROM usuario WHERE email = ?", AMARELO);
        long d06 = doKit("D06");
        long d02 = doKit("D02");
        long antesD06 = aberturas(d06);
        long antesD02 = aberturas(d02);

        conteudo(amarelo, d06);
        conteudo(amarelo, d02);
        conteudo(amarelo, d02);

        assertThat(aberturas(d06) - antesD06).isEqualTo(1);
        assertThat(aberturas(d02) - antesD02).as("cada abertura é uma linha").isEqualTo(2);
        Map<String, Object> doAnexo = jdbc.sql("SELECT parceria_id, usuario_id, valor_novo FROM historico WHERE acao = 'DOWNLOAD' "
                + "AND entidade = 'documento' AND entidade_id = :d ORDER BY id DESC LIMIT 1").param("d", d06).query().singleRow();
        assertThat(((Number) doAnexo.get("parceria_id")).longValue()).as("documento de PARCERIA leva a parceria").isEqualTo(parceriaAmarelo);
        assertThat(((Number) doAnexo.get("usuario_id")).longValue()).isEqualTo(usuarioAmarelo);
        assertThat(doAnexo.get("valor_novo")).isEqualTo("d06-anexo-parceria-amarelo-2027.pdf");
        Map<String, Object> doManual = jdbc.sql("SELECT parceria_id, usuario_id FROM historico WHERE acao = 'DOWNLOAD' "
                + "AND entidade = 'documento' AND entidade_id = :d ORDER BY id DESC LIMIT 1").param("d", d02).query().singleRow();
        assertThat(doManual.get("parceria_id")).as("documento GLOBAL: sem parceria, com o usuário").isNull();
        assertThat(((Number) doManual.get("usuario_id")).longValue()).isEqualTo(usuarioAmarelo);
        // a marca não vê essas linhas na rota 20, nem no filtro por ação
        MvcTestResult historico = amarelo.get("/api/parcerias/" + parceriaAmarelo + "/historico?acao=DOWNLOAD&tamanho=200");
        assertThat(lista(historico, "$.itens[?(@.entidade == 'documento')]")).isEmpty();
        // quem não pôde abrir não deixa linha
        long antesD04 = aberturas(doKit("D04"));
        assertThat(conteudo(amarelo, doKit("D04"))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(aberturas(doKit("D04"))).isEqualTo(antesD04);
    }

    @Test
    void aFaixaQueNaoComecaEmZeroEOHeadNaoGravamDownloadMasAPrimeiraFaixaGrava() {
        SessaoDeApi amarelo = entrar(AMARELO);
        long d02 = doKit("D02");
        long antes = aberturas(d02);

        MvcTestResult primeira = amarelo.getComCabecalho("/api/documentos/" + d02 + "/conteudo", "Range", "bytes=0-99");
        MvcTestResult continuacao = amarelo.getComCabecalho("/api/documentos/" + d02 + "/conteudo", "Range", "bytes=100-199");
        MvcTestResult resto = amarelo.getComCabecalho("/api/documentos/" + d02 + "/conteudo", "Range", "bytes=5000-");
        MvcTestResult cabeca = amarelo.head("/api/documentos/" + d02 + "/conteudo");

        assertThat(primeira).hasStatus(HttpStatus.PARTIAL_CONTENT);
        assertThat(primeira.getResponse().getContentAsByteArray()).hasSize(100);
        assertThat(primeira.getResponse().getHeader("Content-Range")).startsWith("bytes 0-99/");
        assertThat(continuacao).hasStatus(HttpStatus.PARTIAL_CONTENT);
        assertThat(resto).hasStatus(HttpStatus.PARTIAL_CONTENT);
        assertThat(cabeca).hasStatus(HttpStatus.OK);
        assertThat(aberturas(d02) - antes).as("só a primeira faixa foi uma abertura").isEqualTo(1);
        for (MvcTestResult resposta : List.of(primeira, continuacao, resto)) {
            assertThat(resposta.getResponse().getHeaders("Content-Security-Policy")).containsExactly("sandbox");
            assertThat(resposta.getResponse().getHeaders("Cache-Control")).containsExactly("private, no-store");
        }
    }

    @Test
    void documentoRevogadoDepoisDeAbertoDa404ParaAMarcaEARbsContinuaAbrindo() {
        SessaoDeApi rbs = entrar(RBS);
        SessaoDeApi amarelo = entrar(AMARELO);
        long d06 = doKit("D06");
        long trecho = primeiroTrecho(d06);
        assertThat(conteudo(amarelo, d06)).hasStatus(HttpStatus.OK);
        assertThat(trecho(amarelo, d06, trecho)).hasStatus(HttpStatus.OK);

        assertThat(acao(rbs, d06, "REVOGAR", "Saiu um anexo novo.")).hasStatus(HttpStatus.OK);

        assertThat(conteudo(amarelo, d06)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(trecho(amarelo, d06, trecho)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(conteudo(rbs, d06)).hasStatus(HttpStatus.OK);
        assertThat(trecho(rbs, d06, trecho)).hasStatus(HttpStatus.OK);
    }

    @Test
    void arquivoQueSumiuDoArmazenamentoDa404ComAFraseDeSempreESemGravarDownload() {
        SessaoDeApi rbs = entrar(RBS);
        long d02 = doKit("D02");
        long antes = aberturas(d02);
        String caminho = jdbc.sql("SELECT caminho FROM arquivo WHERE id = :a").param("a", arquivoDoKit("D02")).query(String.class).single();
        jdbc.sql("UPDATE arquivo SET caminho = 'documentos/nao-existe/sumiu.pdf' WHERE id = :a").param("a", arquivoDoKit("D02")).update();

        MvcTestResult resposta = conteudo(rbs, d02);

        assertThat(caminho).isNotBlank();
        assertThat(resposta).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo("Este documento não está mais disponível. Avise a equipe da RBS.");
        assertThat(aberturas(d02)).isEqualTo(antes);
    }

    @Test
    void semSessaoDa401EIdQueNaoEhNumeroOuNaoExisteDa404NasDuasPontas() {
        SessaoDeApi anonima = SessaoDeApi.anonima(mvc);
        assertThat(anonima.get("/api/documentos/1/conteudo")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(anonima.get("/api/documentos/1/trechos/1")).hasStatus(HttpStatus.UNAUTHORIZED);

        for (String email : List.of(RBS, AMARELO)) {
            SessaoDeApi pessoa = entrar(email);
            for (String uri : List.of("/api/documentos/999999/conteudo", "/api/documentos/abc/conteudo", "/api/documentos/999999/trechos/1",
                    "/api/documentos/abc/trechos/1", "/api/documentos/" + doKit("D02") + "/trechos/999999999",
                    "/api/documentos/" + doKit("D02") + "/trechos/abc")) {
                MvcTestResult resposta = pessoa.get(uri);
                assertThat(resposta).as(email + " " + uri).hasStatus(HttpStatus.NOT_FOUND);
                assertThat(lerJson(resposta, "$.detail")).as(email + " " + uri).isEqualTo(NAO_ENCONTRADO);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Rota 60: o trecho inteiro
    // ---------------------------------------------------------------------

    @Test
    void aRbsVeOTrechoInteiroComAPaginaASecaoEOTextoComoFoiIndexado() {
        SessaoDeApi rbs = entrar(RBS);
        long d02 = doKit("D02");
        Map<String, Object> banco = jdbc.sql("""
                        SELECT id, ordem, pagina_inicio, pagina_fim, secao_caminho, texto FROM documento_trecho
                        WHERE documento_id = :d AND length(texto) > 600 ORDER BY ordem LIMIT 1
                        """).param("d", d02).query().singleRow();
        long trechoId = ((Number) banco.get("id")).longValue();

        MvcTestResult resposta = trecho(rbs, d02, trechoId);

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(mapa(resposta, "$").keySet()).containsExactlyInAnyOrder("id", "documentoId", "ordem", "paginaInicio", "paginaFim", "secao", "texto");
        assertThat(((Number) lerJson(resposta, "$.id")).longValue()).isEqualTo(trechoId);
        assertThat(((Number) lerJson(resposta, "$.documentoId")).longValue()).isEqualTo(d02);
        assertThat(lerJson(resposta, "$.ordem")).isEqualTo(banco.get("ordem"));
        assertThat(lerJson(resposta, "$.paginaInicio")).isEqualTo(banco.get("pagina_inicio"));
        assertThat(lerJson(resposta, "$.paginaFim")).isEqualTo(banco.get("pagina_fim"));
        assertThat(lerJson(resposta, "$.secao")).isEqualTo(banco.get("secao_caminho"));
        assertThat((String) lerJson(resposta, "$.texto")).isEqualTo(banco.get("texto")).hasSizeGreaterThan(240);
    }

    @Test
    void aMarcaVeOTrechoSoDosDocumentosQueAbreEOsOutrosDao404() {
        ABRE.forEach((email, abre) -> {
            SessaoDeApi marca = entrar(email);
            for (String idDoKit : TODOS) {
                long documento = doKit(idDoKit);
                List<Long> trechos = jdbc.sql("SELECT id FROM documento_trecho WHERE documento_id = :d ORDER BY ordem LIMIT 2").param("d", documento)
                        .query(Long.class).list();
                for (long trecho : trechos) {
                    MvcTestResult resposta = trecho(marca, documento, trecho);
                    if (abre.contains(idDoKit)) {
                        assertThat(resposta).as(email + " no trecho " + trecho + " do " + idDoKit).hasStatus(HttpStatus.OK);
                        assertThat(((Number) lerJson(resposta, "$.documentoId")).longValue()).isEqualTo(documento);
                    } else {
                        assertThat(resposta).as(email + " no trecho " + trecho + " do " + idDoKit).hasStatus(HttpStatus.NOT_FOUND);
                        assertThat(lerJson(resposta, "$.detail")).isEqualTo(NAO_ENCONTRADO);
                    }
                }
            }
        });
    }

    @Test
    void aVersaoSubstituidaAbreOsTrechosInativosParaOAviso() {
        SessaoDeApi amarelo = entrar(AMARELO);
        long d01 = doKit("D01");
        long trecho = primeiroTrecho(d01);

        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE id = ? AND ativo", trecho)).as("a v1 está desligada na busca").isZero();
        assertThat(trecho(amarelo, d01, trecho)).hasStatus(HttpStatus.OK);
    }

    @Test
    void trocaDeIdNaoDeixaLerTrechoDeOutroDocumentoNemParaAMarcaQueVeOsDois() {
        // o Amarelo abre o D02 e o D06; o trecho do D06 com o id do D02 na URL (e o contrário) tem de dar 404, e o D04 (do Roxo) também
        SessaoDeApi amarelo = entrar(AMARELO);
        SessaoDeApi rbs = entrar(RBS);
        long d02 = doKit("D02");
        long d06 = doKit("D06");
        long d04 = doKit("D04");

        for (SessaoDeApi pessoa : List.of(amarelo, rbs)) {
            assertThat(trecho(pessoa, d02, primeiroTrecho(d06))).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(trecho(pessoa, d06, primeiroTrecho(d02))).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(trecho(pessoa, d02, primeiroTrecho(d04))).hasStatus(HttpStatus.NOT_FOUND);
        }
        assertThat(trecho(amarelo, d04, primeiroTrecho(d04))).as("o Amarelo não abre o documento do Roxo").hasStatus(HttpStatus.NOT_FOUND);
        assertThat(trecho(rbs, d04, primeiroTrecho(d04))).hasStatus(HttpStatus.OK);
        assertThat(trecho(amarelo, d02, primeiroTrecho(d02))).hasStatus(HttpStatus.OK);
    }
}
