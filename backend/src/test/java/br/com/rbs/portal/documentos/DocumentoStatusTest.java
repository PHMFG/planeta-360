package br.com.rbs.portal.documentos;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] A rota 57 (aprovar, recusar, revogar e reindexar) e o resultado da ingestão. Cada teste é desfeito no fim
 * (@Transactional): como o commit nunca acontece, o callback que enfileira a ingestão nunca dispara, e o teste chama
 * {@code IngestaoService.processar} no lugar dele (os passos entram na transação do teste). Os testes em que o banco recusa
 * (e a transação do PostgreSQL aborta) estão em DocumentoRecusasTest.
 */
@TesteDeIntegracao
@Transactional
class DocumentoStatusTest extends ApoioDeDocumentos {

    @Autowired
    IngestaoService ingestao;

    private long enviarComunicado(SessaoDeApi rbs, String chave) {
        return enviarOk(rbs, "comunicado.pdf", KitDeDocumentos.por("D09").conteudo(), global("Comunicado de teste", chave));
    }

    @Test
    void aprovarPoeEmProcessandoGravaQuemAprovouEOHistorico() {
        SessaoDeApi rbs = entrar(RBS);
        long id = enviarComunicado(rbs, "teste-aprovar");

        MvcTestResult resposta = acao(rbs, id, "APROVAR", "Conferido pela equipe.");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.status")).isEqualTo("PROCESSANDO");
        assertThat(lerJson(resposta, "$.aprovadoPor.perfil")).isEqualTo("ADMIN_RBS");
        assertThat(lerJson(resposta, "$.aprovadoEm")).isNotNull();
        assertThat(lista(resposta, "$.acoes")).isEmpty();
        var banco = jdbc.sql("SELECT aprovado_por, aprovado_por_perfil FROM documento WHERE id = :id").param("id", id).query().singleRow();
        assertThat(banco.get("aprovado_por_perfil")).isEqualTo("ADMIN_RBS");
        var linha = jdbc.sql("""
                        SELECT acao, valor_anterior, valor_novo, detalhe FROM historico
                        WHERE entidade = 'documento' AND entidade_id = :id AND acao = 'ALTERACAO_STATUS'
                        """).param("id", id).query().singleRow();
        assertThat(linha.get("valor_anterior")).isEqualTo("AGUARDANDO_APROVACAO");
        assertThat(linha.get("valor_novo")).isEqualTo("PROCESSANDO");
        assertThat(linha.get("detalhe")).isEqualTo("Conferido pela equipe.");
    }

    @Test
    void aIngestaoIndexaOsTrechosAtivosComModeloDimensoesETempos() {
        SessaoDeApi rbs = entrar(RBS);
        long id = enviarComunicado(rbs, "teste-indexar");
        acao(rbs, id, "APROVAR");

        ingestao.processar(id);

        MvcTestResult depois = rbs.get("/api/documentos/" + id);
        assertThat(lerJson(depois, "$.status")).isEqualTo("INDEXADO");
        assertThat(lerJson(depois, "$.paginas")).isEqualTo(2);
        assertThat((Integer) lerJson(depois, "$.trechos")).isGreaterThan(0);
        assertThat(lerJson(depois, "$.modelo")).isEqualTo("embeddings-de-teste");
        assertThat(lerJson(depois, "$.dimensoes")).isEqualTo(768);
        assertThat(lerJson(depois, "$.modeloDiferente")).isEqualTo(false);
        assertThat((Integer) lerJson(depois, "$.extracaoMs")).isGreaterThanOrEqualTo(0);
        assertThat((Integer) lerJson(depois, "$.embeddingsMs")).isGreaterThanOrEqualTo(0);
        assertThat(lerJson(depois, "$.erro")).isNull();
        assertThat(lista(depois, "$.acoes")).containsExactly("REVOGAR");
        long trechos = contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ?", id);
        assertThat(trechos).isEqualTo(((Number) lerJson(depois, "$.trechos")).longValue());
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ? AND ativo", id)).isEqualTo(trechos);
        // o trecho leva a edição e o escopo do documento (copiados pelo gatilho), e o vetor é de 768 dimensões
        var trecho = jdbc.sql("""
                        SELECT t.escopo, t.versao, t.evento_id, vector_dims(t.embedding) AS dims, t.tsv IS NOT NULL AS tem_tsv
                        FROM documento_trecho t WHERE t.documento_id = :id ORDER BY ordem LIMIT 1
                        """).param("id", id).query().singleRow();
        assertThat(trecho.get("escopo")).isEqualTo("GLOBAL");
        assertThat(((Number) trecho.get("versao")).intValue()).isEqualTo(1);
        assertThat(((Number) trecho.get("evento_id")).longValue()).isEqualTo(evento(2027));
        assertThat(((Number) trecho.get("dims")).intValue()).isEqualTo(768);
        assertThat(trecho.get("tem_tsv")).isEqualTo(true);
        var historico = jdbc.sql("""
                        SELECT valor_anterior, valor_novo, detalhe FROM historico
                        WHERE entidade = 'documento' AND entidade_id = :id AND valor_novo = 'INDEXADO'
                        """).param("id", id).query().singleRow();
        assertThat(historico.get("valor_anterior")).isEqualTo("PROCESSANDO");
        assertThat((String) historico.get("detalhe")).contains(trechos + " trechos").contains("embeddings-de-teste");
    }

    @Test
    void aVersao2SubstituiAVersao1NaMesmaTransacaoEOHistoricoDizIsso() {
        SessaoDeApi rbs = entrar(RBS);
        long v1 = enviarComunicado(rbs, "teste-substituir");
        acao(rbs, v1, "APROVAR");
        ingestao.processar(v1);
        long v2 = enviarComunicado(rbs, "teste-substituir");
        acao(rbs, v2, "APROVAR");
        assertThat(statusDe(v1)).as("enquanto a v2 processa, a v1 segue no ar").isEqualTo("INDEXADO");

        ingestao.processar(v2);

        assertThat(statusDe(v1)).isEqualTo("SUBSTITUIDO");
        assertThat(statusDe(v2)).isEqualTo("INDEXADO");
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ? AND ativo", v1)).isZero();
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ?", v1)).as("os trechos da v1 ficam, inativos").isGreaterThan(0);
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ? AND ativo", v2)).isGreaterThan(0);
        var historico = jdbc.sql("SELECT detalhe FROM historico WHERE entidade = 'documento' AND entidade_id = :id AND valor_novo = 'SUBSTITUIDO'")
                .param("id", v1).query().singleRow();
        assertThat(historico.get("detalhe")).isEqualTo("substituído pela versão 2");
        assertThat(lerJson(rbs.get("/api/documentos/" + v1), "$.status")).isEqualTo("SUBSTITUIDO");
    }

    @Test
    void recusarSaiDeAguardandoEDeErroENaoVoltaAtras() {
        SessaoDeApi rbs = entrar(RBS);
        long id = enviarComunicado(rbs, "teste-recusar");

        MvcTestResult recusado = acao(rbs, id, "RECUSAR", "Não é este documento.");

        assertThat(recusado).hasStatus(HttpStatus.OK);
        assertThat(lerJson(recusado, "$.status")).isEqualTo("RECUSADO");
        assertThat(lista(recusado, "$.acoes")).isEmpty();
        assertThat(lerJson(acao(rbs, id, "APROVAR"), "$.detail"))
                .isEqualTo("Só um documento aguardando aprovação pode ser aprovado. Este documento está recusado.");
        assertThat(acao(rbs, id, "APROVAR")).hasStatus(HttpStatus.CONFLICT);
        assertThat(acao(rbs, id, "RECUSAR")).hasStatus(HttpStatus.CONFLICT);
        assertThat(acao(rbs, id, "REVOGAR")).hasStatus(HttpStatus.CONFLICT);
        assertThat(acao(rbs, id, "REINDEXAR")).hasStatus(HttpStatus.CONFLICT);
        // o PDF só de imagem nasceu em ERRO: dá para recusar
        long imagem = enviarOk(rbs, "aviso.pdf", KitDeDocumentos.por("D12").conteudo(), global("Aviso", "teste-recusar-erro"));
        assertThat(lerJson(acao(rbs, imagem, "RECUSAR"), "$.status")).isEqualTo("RECUSADO");
    }

    @Test
    void revogarDesligaOsTrechosNaHoraSemApagarENaoTemVolta() {
        SessaoDeApi rbs = entrar(RBS);
        long id = enviarComunicado(rbs, "teste-revogar");
        acao(rbs, id, "APROVAR");
        ingestao.processar(id);
        long trechos = contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ?", id);

        MvcTestResult revogado = acao(rbs, id, "REVOGAR", "Saiu uma circular nova.");

        assertThat(revogado).hasStatus(HttpStatus.OK);
        assertThat(lerJson(revogado, "$.status")).isEqualTo("REVOGADO");
        assertThat(lerJson(revogado, "$.revogadoPor.perfil")).isEqualTo("ADMIN_RBS");
        assertThat(lerJson(revogado, "$.revogadoEm")).isNotNull();
        assertThat(lista(revogado, "$.acoes")).isEmpty();
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ? AND ativo", id)).isZero();
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ?", id)).isEqualTo(trechos);
        assertThat(acao(rbs, id, "REVOGAR")).hasStatus(HttpStatus.CONFLICT);
        assertThat(acao(rbs, id, "APROVAR")).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void umDocumentoSubstituidoAindaPodeSerRevogado() {
        SessaoDeApi rbs = entrar(RBS);
        long v1 = enviarComunicado(rbs, "teste-revogar-substituido");
        acao(rbs, v1, "APROVAR");
        ingestao.processar(v1);
        long v2 = enviarComunicado(rbs, "teste-revogar-substituido");
        acao(rbs, v2, "APROVAR");
        ingestao.processar(v2);

        MvcTestResult resposta = acao(rbs, v1, "REVOGAR");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(lerJson(resposta, "$.status")).isEqualTo("REVOGADO");
        assertThat(statusDe(v2)).isEqualTo("INDEXADO");
    }

    @Test
    void reindexarSaiDeErroDeUmDocumentoQueJaFoiAprovado() {
        SessaoDeApi rbs = entrar(RBS);
        long id = enviarComunicado(rbs, "teste-reindexar");
        acao(rbs, id, "APROVAR");
        // a ingestão falhou (a rede do provedor caiu, por exemplo): o documento fica em ERRO com o motivo
        jdbc.sql("UPDATE documento SET status = 'ERRO', erro = 'O Gemini respondeu 503.' WHERE id = :id").param("id", id).update();
        MvcTestResult emErro = rbs.get("/api/documentos/" + id);
        assertThat(lerJson(emErro, "$.erro")).isEqualTo("O Gemini respondeu 503.");
        assertThat(lista(emErro, "$.acoes")).containsExactly("REINDEXAR", "RECUSAR");

        MvcTestResult reindexado = acao(rbs, id, "REINDEXAR");

        assertThat(reindexado).hasStatus(HttpStatus.OK);
        assertThat(lerJson(reindexado, "$.status")).isEqualTo("PROCESSANDO");
        assertThat(lerJson(reindexado, "$.erro")).isNull();
        assertThat(jdbc.sql("SELECT detalhe FROM historico WHERE entidade = 'documento' AND entidade_id = :id AND valor_anterior = 'ERRO'")
                .param("id", id).query(String.class).single()).isEqualTo("reindexação");
        ingestao.processar(id);
        assertThat(statusDe(id)).isEqualTo("INDEXADO");
    }

    @Test
    void aAcaoTemQueSerUmaDasQuatroEAObservacaoTemLimite() {
        SessaoDeApi rbs = entrar(RBS);
        long id = enviarComunicado(rbs, "teste-acao");

        MvcTestResult invalida = rbs.patch("/api/documentos/" + id + "/status", "{\"acao\":\"APAGAR\"}");
        MvcTestResult vazia = rbs.patch("/api/documentos/" + id + "/status", "{}");
        MvcTestResult longa = acao(rbs, id, "RECUSAR", "x".repeat(501));
        MvcTestResult semCorpo = rbs.patch("/api/documentos/" + id + "/status", "");

        assertThat(invalida).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(invalida, "$.campos.acao")).isEqualTo("Ação inválida. Use APROVAR, RECUSAR, REVOGAR ou REINDEXAR.");
        assertThat(vazia).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(vazia, "$.campos.acao")).isEqualTo("Informe a ação.");
        assertThat(longa).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(longa, "$.campos.observacao")).isEqualTo("A observação tem no máximo 500 caracteres.");
        assertThat(semCorpo).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(statusDe(id)).isEqualTo("AGUARDANDO_APROVACAO");
    }

    @Test
    void caractereNuloNaObservacaoDa422EmCamposEmVezDe500() {
        SessaoDeApi rbs = entrar(RBS);
        long id = enviarComunicado(rbs, "teste-observacao-nula");

        MvcTestResult resposta = rbs.patch("/api/documentos/" + id + "/status", "{\"acao\":\"RECUSAR\",\"observacao\":\"fora do \\u0000padrão\"}");

        assertThat(resposta).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lerJson(resposta, "$.campos.observacao")).isEqualTo("A observação tem caracteres inválidos.");
        assertThat(statusDe(id)).isEqualTo("AGUARDANDO_APROVACAO");
    }

    @Test
    void documentoInexistenteOuIdQueNaoENumeroDa404ComAFraseDoDocumento() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult inexistente = acao(rbs, 999_999, "APROVAR");
        MvcTestResult naoNumero = rbs.patch("/api/documentos/abc/status", "{\"acao\":\"APROVAR\"}");

        assertThat(inexistente).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(inexistente, "$.detail")).isEqualTo(NAO_ENCONTRADO);
        assertThat(naoNumero).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(lerJson(naoNumero, "$.detail")).isEqualTo(NAO_ENCONTRADO);
    }

    @Test
    void marcaRecebe403AntesDoCorpoNaRota57() {
        SessaoDeApi amarelo = entrar(AMARELO);
        long id = doKit("D02");

        for (String corpo : List.of("", "{}", "isto nao e json", "{\"acao\":\"APAGAR\"}", "{\"acao\":\"REVOGAR\"}")) {
            MvcTestResult resposta = amarelo.patch("/api/documentos/" + id + "/status", corpo);
            assertThat(resposta).as("corpo [" + corpo + "]").hasStatus(HttpStatus.FORBIDDEN);
            assertThat(lerJson(resposta, "$.detail")).isEqualTo(SO_RBS);
        }
        assertThat(statusDe(id)).isEqualTo("INDEXADO");
    }

    @Test
    void asAcoesDisponiveisDependemDoStatusEDoDadoPessoal() {
        SessaoDeApi rbs = entrar(RBS);

        assertThat(lista(rbs.get("/api/documentos/" + doKit("D02")), "$.acoes")).containsExactly("REVOGAR");
        assertThat(lista(rbs.get("/api/documentos/" + doKit("D01")), "$.acoes")).as("substituído").containsExactly("REVOGAR");
        assertThat(lista(rbs.get("/api/documentos/" + doKit("D11")), "$.acoes")).as("com dado pessoal").containsExactly("RECUSAR");
        assertThat(lista(rbs.get("/api/documentos/" + doKit("D12")), "$.acoes")).as("nasceu em erro").containsExactly("RECUSAR");
        assertThat(lista(rbs.get("/api/documentos/" + doKit("D13")), "$.acoes")).as("revogado").isEmpty();
    }

    @Test
    void oKitFicouNoEstadoQueAAmostraDescreve() {
        Map<String, String> esperado = Map.ofEntries(Map.entry("D01", "SUBSTITUIDO"), Map.entry("D02", "INDEXADO"), Map.entry("D03", "INDEXADO"),
                Map.entry("D04", "INDEXADO"), Map.entry("D05", "INDEXADO"), Map.entry("D06", "INDEXADO"), Map.entry("D07", "INDEXADO"),
                Map.entry("D08", "INDEXADO"), Map.entry("D09", "INDEXADO"), Map.entry("D10", "INDEXADO"),
                Map.entry("D11", "AGUARDANDO_APROVACAO"), Map.entry("D12", "ERRO"), Map.entry("D13", "REVOGADO"));

        esperado.forEach((idDoKit, status) -> assertThat(statusDe(doKit(idDoKit))).as(idDoKit).isEqualTo(status));
        assertThat(jdbc.sql("SELECT erro FROM documento WHERE id = :id").param("id", doKit("D12")).query(String.class).single())
                .isEqualTo("Sem texto extraível; OCR fora do escopo.");
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ? AND ativo", doKit("D01"))).as("v1 substituída: trechos inativos").isZero();
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ?", doKit("D01"))).isGreaterThan(0);
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ? AND ativo", doKit("D13"))).as("revogada: trechos desligados").isZero();
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ?", doKit("D11"))).isZero();
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ?", doKit("D12"))).isZero();
    }
}
