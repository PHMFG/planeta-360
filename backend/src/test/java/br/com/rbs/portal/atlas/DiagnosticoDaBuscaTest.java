package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.RBS;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.etapas;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.exigirCoerencia;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.primeira;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.todas;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.tracoDoTurno;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.ApoioDoDiagnostico.Linha;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * [ATLAS] A etapa {@code BUSCA} do diagnóstico (M4): a consulta, as duas listas com a posição e a distância ou a nota, os escolhidos
 * pelo RRF, o modo degradado (e por quê) e os tempos do embedding e do SQL. Ela acontece DENTRO da etapa {@code FERRAMENTA} da busca e
 * por isso fica fora da soma do caminho crítico, como o resumo do raciocínio. Nada do texto dos trechos entra no traço. A marca vê o
 * traço dela (rota 49) e a RBS vê o de todos (rota 51). Não é {@code @Transactional}: o traço vai ao banco no fim do turno e o teste
 * apaga o que confirmou.
 */
@TesteComLlmRoteirizado
@Import(EmbeddingsControlavelConfig.class)
@TestPropertySource(properties = {"atlas.diagnostico.ligado=true", "atlas.diagnostico.marca=true", "atlas.prazo-do-resumo=PT0.5S",
        "atlas.relatorio.ligado=false"})
class DiagnosticoDaBuscaTest {

    private static final String BUSCAR = "buscar_documentos";

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    EmbeddingsControlavel embeddings;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void guardarOQueJaExiste() {
        modelo.limpar();
        embeddings.limpar();
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagarOQueOTesteConfirmou() {
        limpeza.apagar();
    }

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    long abrir() {
        return atlas.abrirConversa(usuario(jdbc, AMARELO), amarelo(), null, requisicao()).conversaId();
    }

    void falar(long conversa, String texto) {
        atlas.enviarMensagem(usuario(jdbc, AMARELO), conversa, new MensagemRequisicao(texto, null, null), requisicao());
    }

    static String busca(String consulta) {
        return AtlasJson.escrever(Map.of("consulta", consulta));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> lista(Linha etapa, String campo) {
        return (List<Map<String, Object>>) etapa.dados().get(campo);
    }

    @Test
    void aEtapaBuscaGuardaAConsultaAsDuasListasEOsEscolhidos() {
        long conversa = abrir();
        modelo.chamada(BUSCAR, busca("prazo para informar os geradores")).texto("Veja o manual.");

        falar(conversa, "Até quando informo os geradores?");

        List<Linha> turno = tracoDoTurno(jdbc, conversa);
        assertThat(etapas(turno)).containsSubsequence("G1_LIMITES", "LLM_IDA", "FERRAMENTA", "BUSCA", "LLM_IDA", "CHECAGEM", "SAIDA");
        Linha buscaDoTurno = primeira(turno, "BUSCA");
        assertThat(buscaDoTurno.resultado()).isEqualTo("OK");
        Map<String, Object> dados = buscaDoTurno.dados();
        assertThat(dados).containsEntry("consulta", "prazo para informar os geradores").containsEntry("k", 6).containsEntry("numeroDaBusca", 1)
                .doesNotContainKeys("degradado", "motivoDoDegradado", "semTermoUtil", "tipo");
        assertThat(((Number) dados.get("embeddingMs")).longValue()).isGreaterThanOrEqualTo(0);
        assertThat(((Number) dados.get("sqlMs")).longValue()).isGreaterThanOrEqualTo(0);

        List<Map<String, Object>> vetorial = lista(buscaDoTurno, "vetorial");
        List<Map<String, Object>> textual = lista(buscaDoTurno, "textual");
        List<Map<String, Object>> escolhidos = lista(buscaDoTurno, "escolhidos");
        assertThat(vetorial).isNotEmpty().hasSizeLessThanOrEqualTo(30);
        assertThat(textual).isNotEmpty().hasSizeLessThanOrEqualTo(30);
        assertThat(escolhidos).isNotEmpty().hasSizeLessThanOrEqualTo(6);
        assertThat(vetorial.get(0)).containsKeys("trechoId", "documentoId", "documento", "versao", "pagina", "posicao", "distancia")
                .containsEntry("posicao", 1);
        assertThat(textual.get(0)).containsKeys("trechoId", "documentoId", "documento", "versao", "pagina", "posicao", "nota")
                .containsEntry("posicao", 1);
        assertThat(escolhidos.get(0)).containsKeys("trechoId", "documento", "versao", "pagina", "rrf");
        assertThat(escolhidos).extracting(e -> ((Number) e.get("rrf")).doubleValue()).isSortedAccordingTo((a, b) -> Double.compare(b, a));
        assertThat(escolhidos.get(0).get("documento")).isEqualTo("Manual de Operações 2027");
        // a etapa BUSCA guarda onde estão os trechos, nunca o texto deles (o resultado inteiro que o modelo recebeu fica na FERRAMENTA)
        assertThat(buscaDoTurno.dados().toString()).doesNotContain("Todos os prazos terminam").doesNotContainPattern("texto=");
    }

    @Test
    void aBuscaEstaDentroDaFerramentaEFicaForaDaSomaDoCaminhoCritico() {
        long conversa = abrir();
        modelo.chamada(BUSCAR, busca("tarifa de energia por kVA")).texto("Veja o manual.");

        falar(conversa, "Quanto é a energia?");

        List<Linha> turno = tracoDoTurno(jdbc, conversa);
        Linha ferramenta = primeira(turno, "FERRAMENTA");
        Linha buscaDoTurno = primeira(turno, "BUSCA");
        assertThat(ferramenta.dados()).containsEntry("ferramenta", BUSCAR);
        assertThat(buscaDoTurno.iniciadaEm()).isAfterOrEqualTo(ferramenta.iniciadaEm().minusNanos(1_000_000));
        assertThat(buscaDoTurno.terminouEm()).isBeforeOrEqualTo(ferramenta.terminouEm().plusNanos(1_000_000));
        exigirCoerencia(turno);
        // o resultado do turno e a duração dele (rota 51) não contam a busca duas vezes
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        String turnoId = (String) lerJson(rbs.get("/api/atlas/diagnostico/turnos?parceriaId=" + amarelo()), "$.itens[0].turno");
        MvcTestResult detalhe = rbs.get("/api/atlas/diagnostico/turnos/" + turnoId);
        long duracao = ((Number) lerJson(detalhe, "$.duracaoMs")).longValue();
        long soma = turno.stream().filter(l -> !l.emParalelo()).mapToLong(Linha::duracaoMs).sum();
        assertThat(soma).isLessThanOrEqualTo(duracao + 1);
    }

    @Test
    void semEmbeddingsAEtapaDizQueFoiDegradadaEPorQue() {
        long conversa = abrir();
        embeddings.desligado(true);
        modelo.chamada(BUSCAR, busca("prazo para informar os geradores")).texto("Veja o manual.");

        falar(conversa, "Até quando informo os geradores?");

        Linha buscaDoTurno = primeira(tracoDoTurno(jdbc, conversa), "BUSCA");
        assertThat(buscaDoTurno.dados()).containsEntry("degradado", true).containsEntry("motivoDoDegradado", "EMBEDDINGS_DESLIGADOS");
        assertThat(lista(buscaDoTurno, "vetorial")).isEmpty();
        assertThat(lista(buscaDoTurno, "textual")).isNotEmpty();
        assertThat(lista(buscaDoTurno, "escolhidos")).isNotEmpty();
    }

    @Test
    void seOEmbeddingFalhaAEtapaGuardaOMotivoSemMensagemDoProvedor() {
        long conversa = abrir();
        embeddings.falharNaConsulta(true);
        modelo.chamada(BUSCAR, busca("prazo para informar os geradores")).texto("Veja o manual.");

        falar(conversa, "Até quando informo os geradores?");

        Linha buscaDoTurno = primeira(tracoDoTurno(jdbc, conversa), "BUSCA");
        assertThat(buscaDoTurno.resultado()).isEqualTo("OK");
        assertThat(buscaDoTurno.dados()).containsEntry("degradado", true).containsEntry("motivoDoDegradado", "EMBEDDING_FALHOU")
                .containsEntry("erroDoEmbedding", "EmbeddingsIndisponivelException");
        assertThat(ApoioDoDiagnostico.tudoOQueOTracoGuarda(jdbc, conversa)).doesNotContain("503");
    }

    @Test
    void aQuartaBuscaNaoDeixaEtapaDeBuscaEAFerramentaFicaComErro() {
        long conversa = abrir();
        modelo.chamada(BUSCAR, busca("geradores")).chamada(BUSCAR, busca("energia")).chamada(BUSCAR, busca("seguro"))
                .chamada(BUSCAR, busca("credenciais")).texto("Veja o manual.");

        falar(conversa, "Tudo sobre a operação.");

        List<Linha> turno = tracoDoTurno(jdbc, conversa);
        assertThat(todas(turno, "BUSCA")).hasSize(3).extracting(l -> l.dados().get("numeroDaBusca")).containsExactly(1, 2, 3);
        List<Linha> ferramentas = todas(turno, "FERRAMENTA");
        assertThat(ferramentas).hasSize(4);
        assertThat(ferramentas.get(3).resultado()).isEqualTo("ERRO");
        assertThat(ferramentas.subList(0, 3)).extracting(Linha::resultado).containsOnly("OK");
    }

    @Test
    void aMarcaVeAEtapaNoDiagnosticoDaPropriaConversaEOQueNaoPodeNaoAparece() {
        long conversa = abrir();
        modelo.chamada(BUSCAR, busca("prazo para informar os geradores")).texto("Veja o manual.");
        falar(conversa, "Até quando informo os geradores?");

        MvcTestResult resposta = SessaoDeApi.entrar(mvc, AMARELO).get("/api/atlas/conversas/" + conversa + "/diagnostico");

        assertThat(resposta).hasStatusOk();
        List<?> etapasDaMarca = (List<?>) lerJson(resposta, "$.turnos[0].etapas[?(@.etapa == 'BUSCA')]");
        assertThat(etapasDaMarca).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> dados = (Map<String, Object>) ((Map<String, Object>) etapasDaMarca.get(0)).get("dados");
        assertThat(dados).containsKeys("consulta", "vetorial", "textual", "escolhidos");
        // a etapa BUSCA mostra à marca o título e a página dos documentos que ela mesma enxerga, nunca o texto dos trechos
        assertThat(dados.toString()).doesNotContain("Todos os prazos terminam").doesNotContainPattern("texto=");
    }
}
