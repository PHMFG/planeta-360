package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] [RELATORIO] A conferência do M6 no plano B, o botão "Montar relatório" sem o modelo de linguagem (o que sobra quando o
 * provedor cai): monta o relatório de ponta a ponta, rápido, e o A4 que sai cumpre as duas regras do marco, sem o modelo de verdade:
 * nenhum algarismo (nem número por extenso) fora de marcador no texto, e todo número do A4 está em "Fontes e cálculos". O mesmo com o
 * modelo roteirizado está em {@link ConferenciaDoRelatorioComModeloTest}. Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class ConferenciaDoRelatorioPlanoBTest {

    private static final String AMARELO = "marketing@amarelo.example";
    private static final String BOTAO = "{\"atalho\":\"MONTAR_RELATORIO\",\"modelo\":\"ATIVACAO_EVENTO\"}";
    private static final String SO_OBRIGATORIOS = "{\"acao\":\"accept\",\"valores\":[{\"chave\":\"ativacao.visitantes_total\",\"valor\":1200},"
            + "{\"chave\":\"ativacao.cadastros_consentidos\",\"valor\":1072}]}";
    private static final String COMPLETO = "{\"acao\":\"accept\",\"valores\":[{\"chave\":\"ativacao.visitantes_total\",\"valor\":1200},"
            + "{\"chave\":\"ativacao.cadastros_consentidos\",\"valor\":1072},{\"chave\":\"ativacao.brindes_disponiveis\",\"valor\":300},"
            + "{\"chave\":\"ativacao.brindes_distribuidos\",\"valor\":150}]}";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
    }

    long numero(MvcTestResult resposta, String caminho) {
        return ((Number) lerJson(resposta, caminho)).longValue();
    }

    /** O botão, o cartão e o accept: o relatório pronto (a resposta da rota 47), o id e quanto tudo levou. */
    record Montado(MvcTestResult relatorio, long id, Duration duracao) {
    }

    Montado montar(SessaoDeApi marca, String accept) {
        long inicio = System.nanoTime();
        MvcTestResult aberta = marca.post("/api/parcerias/" + amarelo() + "/atlas/conversas", BOTAO);
        assertThat(aberta).hasStatus(HttpStatus.CREATED);
        assertThat(lerJson(aberta, "$.llmDisponivel")).as("sem modelo de linguagem").isEqualTo(false);
        MvcTestResult pronta = marca.post("/api/atlas/solicitacoes/" + numero(aberta, "$.itens[2].solicitacaoId") + "/respostas", accept);
        assertThat(pronta).hasStatus(HttpStatus.OK);
        assertThat(lerLista(pronta, "$.itens[*].tipo")).containsExactly("texto", "relatorio_pronto");
        long id = numero(pronta, "$.itens[1].relatorioId");
        MvcTestResult relatorio = marca.get("/api/atlas/relatorios/" + id);
        Duration duracao = Duration.ofNanos(System.nanoTime() - inicio);
        assertThat(relatorio).hasStatus(HttpStatus.OK);
        return new Montado(relatorio, id, duracao);
    }

    @Test
    void obotaoMontaORelatorioCompletoEmMenosDeDezSegundosSemOModelo() {
        Montado montado = montar(SessaoDeApi.entrar(mvc, AMARELO), COMPLETO);

        assertThat(montado.duracao()).as("plano B de ponta a ponta").isLessThan(Duration.ofSeconds(10));
        assertThat(lerJson(montado.relatorio(), "$.status")).isEqualTo("PRONTO");
        assertThat(lerLista(montado.relatorio(), "$.secoes[*].id")).containsExactly("capa", "resumo", "aplicacoes", "publico", "comprovacoes", "fontes");
        // sem o modelo, todo parágrafo é a frase-modelo do modelo-base
        assertThat(lerLista(montado.relatorio(), "$.secoes[*].blocos[?(@.tipo=='paragrafo')].redigidoPor")).containsExactly("MODELO", "MODELO");
        assertThat(lerLista(montado.relatorio(), "$.secoes[*].blocos[?(@.tipo=='paragrafo')].texto")).containsExactly(
                "Aplicações de marca de Cliente Amarelo previstas no plano: 19; comprovadas no evento: 17.",
                "Visitantes no espaço da marca: 1.200. Taxa de cadastro com consentimento: 89,3%.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETO", "SO_OBRIGATORIOS"})
    void oTextoDoRelatorioDoBotaoNaoTemAlgarismoForaDeMarcadorETodoNumeroDoA4EstaEmFontesECalculos(String quais) {
        Montado montado = montar(SessaoDeApi.entrar(mvc, AMARELO), "COMPLETO".equals(quais) ? COMPLETO : SO_OBRIGATORIOS);

        int paragrafos = ApoioDoA4.exigirTextoSemAlgarismoForaDeMarcador(jdbc, montado.id());
        int numeros = ApoioDoA4.exigirTodoNumeroEmFontesECalculos(montado.relatorio());

        assertThat(paragrafos).as("parágrafos conferidos").isEqualTo(4);   // o texto guardado e a frase-modelo de cada um dos dois
        assertThat(numeros).as("números do A4 conferidos").isGreaterThanOrEqualTo(8);
    }

    @ParameterizedTest
    @ValueSource(strings = {"previstas em 2027 no plano", "previstas, dezessete, no plano", "previstas no plano, no primeiro de dezembro,"})
    void aConferenciaDoTextoPegaAlgarismoENumeroPorExtensoQueEntrarPorFora(String troca) {
        // a conferência não pode ser uma que sempre passa: o texto adulterado direto no banco (um relatório em montagem) a reprova
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);
        MvcTestResult aberta = marca.post("/api/parcerias/" + amarelo() + "/atlas/conversas", BOTAO);
        long relatorio = jdbc.sql("SELECT id FROM atlas_relatorio WHERE conversa_id = :c").param("c", numero(aberta, "$.conversaId"))
                .query(Long.class).single();
        assertThat(ApoioDoA4.exigirTextoSemAlgarismoForaDeMarcador(jdbc, relatorio)).isEqualTo(2);   // antes: limpo (a frase-modelo de cada bloco)

        jdbc.sql("UPDATE atlas_relatorio SET especificacao = CAST(replace(CAST(especificacao AS text), 'previstas no plano', :troca) AS jsonb) "
                + "WHERE id = :id").param("troca", troca).param("id", relatorio).update();

        assertThatThrownBy(() -> ApoioDoA4.exigirTextoSemAlgarismoForaDeMarcador(jdbc, relatorio)).isInstanceOf(AssertionError.class);
    }

    @Test
    void semOsBrindesOCampoVemIndisponivelComOMotivoENuncaZeroENadaDeNumeroSemFonte() {
        Montado montado = montar(SessaoDeApi.entrar(mvc, AMARELO), SO_OBRIGATORIOS);

        assertThat(lerLista(montado.relatorio(), "$.secoes[*].blocos[?(@.tipo=='kpi_grade')].itens[?(@.rotulo=='Brindes distribuídos')].valor"))
                .containsOnlyNulls();
        assertThat((String) lerLista(montado.relatorio(),
                "$.secoes[*].blocos[?(@.tipo=='kpi_grade')].itens[?(@.rotulo=='Brindes distribuídos')].indisponivel").get(0)).isNotBlank();
        ApoioDoA4.exigirTodoNumeroEmFontesECalculos(montado.relatorio());
    }
}
