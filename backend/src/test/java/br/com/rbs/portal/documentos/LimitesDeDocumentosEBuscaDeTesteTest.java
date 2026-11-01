package br.com.rbs.portal.documentos;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.EmbeddingsControlavel;
import br.com.rbs.portal.atlas.EmbeddingsControlavelConfig;
import br.com.rbs.portal.atlas.RelogioDeTeste;
import br.com.rbs.portal.atlas.RelogioDeTesteConfig;
import br.com.rbs.portal.atlas.limites.LimitesPorUsuario;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] Os limites por usuário das rotas 59 e 61 (M8, problema 15 do quadro), com os valores padrão num contexto próprio (o
 * contexto normal da suíte os deixa altos) e um {@code Clock} que o teste adianta. Cada GET da rota 59 grava uma linha de DOWNLOAD
 * (120 por hora), e cada chamada da rota 61 gasta um embedding do Gemini (60 por hora). Confere o que o contrato da {@code api.md}
 * diz: o 429 em {@code ProblemDetail} com quanto falta; o limite por usuário, e não por documento nem por parceria; a conferência
 * depois das permissões (o 403, o 404 e o 422 não gastam ficha) e, na rota 61, antes do embedding.
 */
@TesteDeIntegracao
@Import({RelogioDeTesteConfig.class, EmbeddingsControlavelConfig.class})
@TestPropertySource(properties = {"atlas.limites.documentos-por-hora=120", "atlas.limites.buscas-de-teste-por-hora=60"})
@Transactional
class LimitesDeDocumentosEBuscaDeTesteTest extends ApoioDeDocumentos {

    private static final String OUTRA_RBS = "pesquisa@rbs.example";
    private static final String BUSCA = "/api/atlas/busca-teste";
    private static final String FRASE_DO_DOCUMENTO = "Você atingiu o limite de aberturas de documentos por hora. Tente de novo em ";
    private static final String FRASE_DA_BUSCA = "Você atingiu o limite de buscas de teste por hora. Tente de novo em ";

    @Autowired
    RelogioDeTeste relogio;

    @Autowired
    EmbeddingsControlavel embeddings;

    @Autowired
    LimitesPorUsuario limites;

    @BeforeEach
    void comOsBaldesCheios() {
        embeddings.limpar();
        // O balde fica na memória e o rollback não o zera: dois dias parados enchem todos de novo.
        relogio.avancar(Duration.ofDays(3));
    }

    private MvcTestResult abrir(SessaoDeApi pessoa, long documento) {
        return pessoa.get("/api/documentos/" + documento + "/conteudo");
    }

    /** {@code quantas} aberturas do arquivo, cada uma com 200. */
    private void abrirVezes(SessaoDeApi pessoa, long documento, int quantas) {
        for (int i = 0; i < quantas; i++) {
            assertThat(abrir(pessoa, documento)).as("abertura %d de %d", i + 1, quantas).hasStatus(HttpStatus.OK);
        }
    }

    private long aberturas() {
        return contar("SELECT count(*) FROM historico WHERE acao = 'DOWNLOAD' AND entidade = 'documento'");
    }

    private String corpoDaBusca(String consulta) {
        return "{\"parceriaId\":" + SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo") + ",\"consulta\":\"" + consulta + "\"}";
    }

    private MvcTestResult buscar(SessaoDeApi pessoa) {
        return pessoa.post(BUSCA, corpoDaBusca("prazo para informar os geradores"));
    }

    private void buscarVezes(SessaoDeApi pessoa, int quantas) {
        for (int i = 0; i < quantas; i++) {
            assertThat(buscar(pessoa)).as("busca %d de %d", i + 1, quantas).hasStatus(HttpStatus.OK);
        }
    }

    @Test
    void osPadroesSaoCentoEVinteAberturasESessentaBuscasDeTestePorHora() {
        assertThat(LimitesPorUsuario.DOCUMENTOS_POR_HORA).isEqualTo(120);
        assertThat(LimitesPorUsuario.BUSCAS_DE_TESTE_POR_HORA).isEqualTo(60);
        assertThat(limites.documentosPorHora()).isEqualTo(120);
        assertThat(limites.buscasDeTestePorHora()).isEqualTo(60);
    }

    // ---------------------------------------------------------------------
    // Rota 59: o arquivo do documento
    // ---------------------------------------------------------------------

    @Test
    void aAberturaCentoEVinteEUmaRecebe429ComQuantoFaltaESemGravarNada() {
        SessaoDeApi rbs = entrar(RBS);
        long manual = doKit("D02");
        long antes = aberturas();
        abrirVezes(rbs, manual, 120);

        MvcTestResult resposta = abrir(rbs, manual);

        assertThat(resposta).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(resposta.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(resposta, "$.title")).isEqualTo("Limite de uso atingido");
        assertThat(lerJson(resposta, "$.detail")).isEqualTo(FRASE_DO_DOCUMENTO + "1 minuto.");
        assertThat(resposta.getResponse().getHeader("Content-Disposition")).isNull();
        assertThat(aberturas() - antes).as("só as 120 aberturas gravaram DOWNLOAD").isEqualTo(120);
    }

    @Test
    void oQuantoFaltaDiminuiComOTempoEAFichaVoltaAosPoucos() {
        SessaoDeApi rbs = entrar(RBS);
        long manual = doKit("D02");
        abrirVezes(rbs, manual, 120);

        assertThat(abrir(rbs, manual)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        relogio.avancar(Duration.ofSeconds(29));
        assertThat(lerJson(abrir(rbs, manual), "$.detail")).isEqualTo(FRASE_DO_DOCUMENTO + "1 minuto.");   // falta 1 s

        relogio.avancar(Duration.ofSeconds(1));
        abrirVezes(rbs, manual, 1);   // voltou uma ficha: uma abertura passa
        assertThat(abrir(rbs, manual)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void oLimiteEDoUsuarioEDivideOBaldeEntreTodosOsDocumentos() {
        SessaoDeApi rbs = entrar(RBS);
        abrirVezes(rbs, doKit("D02"), 60);
        abrirVezes(rbs, doKit("D08"), 60);   // outro documento, o mesmo balde

        assertThat(abrir(rbs, doKit("D09"))).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(abrir(rbs, doKit("D02"))).hasStatus(HttpStatus.TOO_MANY_REQUESTS);

        // outro usuário, da RBS ou de uma marca, tem o balde dele
        assertThat(abrir(entrar(OUTRA_RBS), doKit("D02"))).hasStatus(HttpStatus.OK);
        assertThat(abrir(entrar(AMARELO), doKit("D02"))).hasStatus(HttpStatus.OK);
    }

    @Test
    void oQueDa404NaoGastaFichaNemRevelaOLimite() {
        SessaoDeApi roxo = entrar(ROXO);
        // o D03 é só da Master (o Amarelo); o D13 está revogado; e o id que não existe: 404 sempre, muito acima do limite
        for (int i = 0; i < 130; i++) {
            assertThat(abrir(roxo, doKit("D03"))).as("tentativa %d no documento de outra parceria", i + 1).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(lerJson(abrir(roxo, doKit("D13")), "$.detail")).isEqualTo(NAO_ENCONTRADO);
        }
        assertThat(abrir(roxo, 999_999_999L)).hasStatus(HttpStatus.NOT_FOUND);

        abrirVezes(roxo, doKit("D02"), 120);   // as 120 fichas continuam todas lá
        assertThat(abrir(roxo, doKit("D02"))).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        // esgotado, o documento que a marca não vê segue dando 404, e não 429: a permissão vem antes
        assertThat(abrir(roxo, doKit("D03"))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(abrir(roxo, 999_999_999L)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void oHeadNaoGastaFichaMasSegueAsMesmasConferenciasDoGet() {
        SessaoDeApi rbs = entrar(RBS);
        long manual = doKit("D02");
        long antes = aberturas();

        for (int i = 0; i < 150; i++) {
            assertThat(rbs.head("/api/documentos/" + manual + "/conteudo")).hasStatus(HttpStatus.OK);
        }
        abrirVezes(rbs, manual, 120);   // o HEAD não gastou nenhuma das 120
        assertThat(aberturas() - antes).isEqualTo(120);

        // com o balde vazio o HEAD responde o que o GET responderia
        assertThat(rbs.head("/api/documentos/" + manual + "/conteudo")).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(abrir(rbs, manual)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void oVisualizadorDoTrechoEAsOutrasRotasDeDocumentoNaoEntramNaConta() {
        SessaoDeApi rbs = entrar(RBS);
        long manual = doKit("D02");
        abrirVezes(rbs, manual, 120);

        assertThat(abrir(rbs, manual)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rbs.get("/api/documentos/" + manual)).hasStatus(HttpStatus.OK);
        assertThat(rbs.get("/api/documentos/" + manual + "/trechos")).hasStatus(HttpStatus.OK);
        assertThat(rbs.get("/api/documentos")).hasStatus(HttpStatus.OK);
    }

    // ---------------------------------------------------------------------
    // Rota 61: a busca de teste
    // ---------------------------------------------------------------------

    @Test
    void aBuscaSessentaEUmaRecebe429ComQuantoFaltaESemGastarOEmbedding() {
        SessaoDeApi rbs = entrar(RBS);
        buscarVezes(rbs, 60);
        assertThat(embeddings.consultas()).hasSize(60);

        MvcTestResult resposta = buscar(rbs);

        assertThat(resposta).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(resposta.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(lerJson(resposta, "$.title")).isEqualTo("Limite de uso atingido");
        assertThat(lerJson(resposta, "$.detail")).isEqualTo(FRASE_DA_BUSCA + "1 minuto.");
        assertThat(embeddings.consultas()).as("a busca recusada não gastou embedding").hasSize(60);
    }

    @Test
    void oQuantoFaltaDaBuscaDiminuiComOTempoEAFichaVoltaAosPoucos() {
        SessaoDeApi rbs = entrar(RBS);
        buscarVezes(rbs, 60);

        assertThat(buscar(rbs)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        relogio.avancar(Duration.ofSeconds(59));
        assertThat(lerJson(buscar(rbs), "$.detail")).isEqualTo(FRASE_DA_BUSCA + "1 minuto.");   // falta 1 s

        relogio.avancar(Duration.ofSeconds(1));
        buscarVezes(rbs, 1);   // voltou uma ficha: uma busca passa
        assertThat(buscar(rbs)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void oLimiteDaBuscaEDoUsuarioEIndependenteDoDaRota59() {
        SessaoDeApi rbs = entrar(RBS);
        buscarVezes(rbs, 60);
        assertThat(buscar(rbs)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(abrir(rbs, doKit("D02"))).as("o balde dos documentos é outro").hasStatus(HttpStatus.OK);
        assertThat(buscar(entrar(OUTRA_RBS))).as("outro usuário tem o balde dele").hasStatus(HttpStatus.OK);
    }

    @Test
    void oQueNaoPassaDasPermissoesEDaValidacaoNaoGastaFichaNemEmbedding() {
        SessaoDeApi rbs = entrar(RBS);
        SessaoDeApi amarelo = entrar(AMARELO);

        for (int i = 0; i < 70; i++) {
            // 403: a marca, com qualquer corpo (antes de o corpo ser lido)
            assertThat(amarelo.post(BUSCA, corpoDaBusca("prazo"))).hasStatus(HttpStatus.FORBIDDEN);
            // 422: consulta em branco, parceria que não existe, tipo fora da lista
            assertThat(rbs.post(BUSCA, corpoDaBusca("   "))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(rbs.post(BUSCA, "{\"parceriaId\":999999999,\"consulta\":\"prazo\"}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(rbs.post(BUSCA, corpoDaBusca("prazo").replace("}", ",\"tipo\":\"INEXISTENTE\"}")))
                    .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        assertThat(embeddings.consultas()).isEmpty();

        buscarVezes(rbs, 60);   // as 60 fichas continuam todas lá
        assertThat(buscar(rbs)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        // esgotado, o 403 e o 422 seguem na frente do 429
        assertThat(amarelo.post(BUSCA, corpoDaBusca("prazo"))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(rbs.post(BUSCA, corpoDaBusca("   "))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
