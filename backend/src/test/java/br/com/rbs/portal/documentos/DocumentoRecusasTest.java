package br.com.rbs.portal.documentos;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * [ATLAS] As recusas que vêm do BANCO (os gatilhos da V11), pela rota 57. Esta classe NÃO é {@code @Transactional}: um erro do
 * PostgreSQL aborta a transação, e o teste ainda precisa ler o banco depois. Só usa os documentos do kit, que ficam confirmados no
 * banco de teste, e só tenta o que o banco recusa, então nada muda neles: o D11 (CPFs) e o D12 (só imagem) seguem como estavam.
 * Bloqueante: "D11 recusado pelo gatilho (409 com a frase); D12 em ERRO".
 */
@TesteDeIntegracao
class DocumentoRecusasTest extends ApoioDeDocumentos {

    static final String FRASE_DO_DADO_PESSOAL = "Este documento parece ter dados pessoais (CPF, RG ou uma lista de contatos) e não pode ser aprovado. "
            + "Tire os dados pessoais e envie de novo.";

    @Test
    void aprovarODocumentoComCpfsDa409ComAFraseDoGatilhoENaoMudaNada() {
        SessaoDeApi rbs = entrar(RBS);
        long d11 = doKit("D11");

        MvcTestResult resposta = acao(rbs, d11, "APROVAR");

        assertThat(resposta).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(resposta, "$.detail")).isEqualTo(FRASE_DO_DADO_PESSOAL);
        assertThat(lerJson(resposta, "$.title")).isEqualTo("Conflito");
        assertThat(statusDe(d11)).isEqualTo("AGUARDANDO_APROVACAO");
        assertThat(jdbc.sql("SELECT aprovado_por FROM documento WHERE id = :id").param("id", d11).query(Long.class).optional()).isEmpty();
        assertThat(contar("SELECT count(*) FROM documento_trecho WHERE documento_id = ?", d11)).isZero();
        assertThat(contar("SELECT count(*) FROM historico WHERE entidade = 'documento' AND entidade_id = ? AND acao = 'ALTERACAO_STATUS'", d11))
                .as("a recusa do banco desfaz o histórico junto").isZero();
    }

    @Test
    void oD11SeguePodendoSerRecusadoPelaRbsMasNaoAprovado() {
        SessaoDeApi rbs = entrar(RBS);
        long d11 = doKit("D11");

        assertThat(lerJson(rbs.get("/api/documentos/" + d11), "$.contemDadosPessoais")).isEqualTo(true);
        assertThat(acao(rbs, d11, "APROVAR")).hasStatus(HttpStatus.CONFLICT);
        assertThat(acao(rbs, d11, "REINDEXAR")).as("não está em erro").hasStatus(HttpStatus.CONFLICT);
        assertThat(acao(rbs, d11, "REVOGAR")).as("não está indexado").hasStatus(HttpStatus.CONFLICT);
        assertThat(statusDe(d11)).isEqualTo("AGUARDANDO_APROVACAO");
    }

    @Test
    void reindexarODocumentoQueNasceuEmErroDa409PorqueNuncaFoiAprovado() {
        SessaoDeApi rbs = entrar(RBS);
        long d12 = doKit("D12");

        MvcTestResult resposta = acao(rbs, d12, "REINDEXAR");

        assertThat(resposta).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(resposta, "$.detail"))
                .isEqualTo("Este documento nunca foi aprovado e não pode ser reindexado. Corrija o arquivo e envie de novo.");
        assertThat(statusDe(d12)).isEqualTo("ERRO");
        assertThat(jdbc.sql("SELECT erro FROM documento WHERE id = :id").param("id", d12).query(String.class).single())
                .isEqualTo("Sem texto extraível; OCR fora do escopo.");
    }

    @Test
    void aprovarOQueJaEstaIndexadoOuRevogadoDa409PelaApi() {
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult indexado = acao(rbs, doKit("D02"), "APROVAR");
        MvcTestResult revogado = acao(rbs, doKit("D13"), "REINDEXAR");

        assertThat(indexado).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(indexado, "$.detail")).isEqualTo("Só um documento aguardando aprovação pode ser aprovado. Este documento está indexado.");
        assertThat(revogado).hasStatus(HttpStatus.CONFLICT);
        assertThat(lerJson(revogado, "$.detail")).isEqualTo("Só um documento com erro pode ser reindexado. Este documento está revogado.");
    }
}
