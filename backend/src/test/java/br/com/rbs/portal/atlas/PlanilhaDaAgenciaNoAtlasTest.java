package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.ApoioDeTeste;
import br.com.rbs.portal.ApoioDeTeste.Csrf;
import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] [DADOS] O campo de arquivo {@code ativacao.planilha_agencia} (V14, rota 66) fica fora do Atlas (M6): não é lacuna
 * obrigatória nem opcional, não entra no cartão de perguntas do botão, não é dado do relatório e o relatório fica pronto sem ele,
 * com a planilha enviada ou não. Aqui, o relatório do botão (o plano B, sem modelo); as ferramentas do modelo estão em
 * {@link PlanilhaDaAgenciaNasFerramentasTest}. Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class PlanilhaDaAgenciaNoAtlasTest {

    private static final String AMARELO = "marketing@amarelo.example";
    private static final String CHAVE = "ativacao.planilha_agencia";
    private static final String BOTAO = "{\"atalho\":\"MONTAR_RELATORIO\",\"modelo\":\"ATIVACAO_EVENTO\"}";
    private static final String ACCEPT_OBRIGATORIOS = "{\"acao\":\"accept\",\"valores\":[{\"chave\":\"ativacao.visitantes_total\",\"valor\":1200},"
            + "{\"chave\":\"ativacao.cadastros_consentidos\",\"valor\":1072}]}";
    private static final List<String> CAMPOS_DE_NUMERO = List.of("ativacao.visitantes_total", "ativacao.cadastros_consentidos",
            "ativacao.brindes_disponiveis", "ativacao.brindes_distribuidos");

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

    long contar(String sql, Object... parametros) {
        var consulta = jdbc.sql(sql);
        for (Object parametro : parametros) {
            consulta = consulta.param(parametro);
        }
        return consulta.query(Long.class).single();
    }

    /** A rota 66 como o front a usa: multipart com sessão e token CSRF. */
    void enviarAPlanilha(SessaoDeApi marca) {
        Csrf csrf = ApoioDeTeste.pedirCsrf(mvc);
        MvcTestResult resposta = mvc.post().uri("/api/parcerias/" + amarelo() + "/dados-informados/" + CHAVE + "/arquivo").multipart()
                .cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token()).session(marca.sessao())
                .file(new MockMultipartFile("arquivo", "contagem.csv", "text/csv", "hora;visitantes\n10h;120\n".getBytes(StandardCharsets.UTF_8)))
                .exchange();
        assertThat(resposta).hasStatus(HttpStatus.CREATED);
    }

    /** O botão, o cartão (confere as chaves dele) e o accept só dos obrigatórios: devolve o relatório pronto pela rota 47. */
    MvcTestResult montarPeloBotaoEResponder(SessaoDeApi marca) {
        MvcTestResult aberta = marca.post("/api/parcerias/" + amarelo() + "/atlas/conversas", BOTAO);
        assertThat(aberta).hasStatus(HttpStatus.CREATED);
        assertThat(lerLista(aberta, "$.itens[2].campos[*].chave")).containsExactlyElementsOf(CAMPOS_DE_NUMERO);
        assertThat(lerLista(aberta, "$.itens[2].campos[?(@.obrigatorio==true)].chave"))
                .containsExactly("ativacao.visitantes_total", "ativacao.cadastros_consentidos");
        MvcTestResult pronta = marca.post("/api/atlas/solicitacoes/" + numero(aberta, "$.itens[2].solicitacaoId") + "/respostas",
                ACCEPT_OBRIGATORIOS);
        assertThat(pronta).hasStatus(HttpStatus.OK);
        MvcTestResult relatorio = marca.get("/api/atlas/relatorios/" + numero(pronta, "$.itens[1].relatorioId"));
        assertThat(relatorio).hasStatus(HttpStatus.OK);
        assertThat(lerJson(relatorio, "$.status")).isEqualTo("PRONTO");
        return relatorio;
    }

    @Test
    void semAPlanilhaOCartaoDoBotaoNaoAPedeEORelatorioFicaPronto() {
        MvcTestResult relatorio = montarPeloBotaoEResponder(SessaoDeApi.entrar(mvc, AMARELO));

        assertThat(corpo(relatorio)).doesNotContain("planilha").doesNotContain("Planilha");
        assertThat(contar("SELECT count(*) FROM dado_informado WHERE campo_chave = ?", CHAVE)).isZero();
    }

    @Test
    void comAPlanilhaEnviadaOCartaoContinuaIgualEORelatorioNaoAUsa() {
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, AMARELO);
        enviarAPlanilha(marca);

        MvcTestResult relatorio = montarPeloBotaoEResponder(marca);

        assertThat(corpo(relatorio)).doesNotContain("planilha").doesNotContain("Planilha").doesNotContain("contagem.csv");
        assertThat(lerLista(relatorio, "$.secoes[*].blocos[?(@.tipo=='fontes')].itens[*].ref")).noneMatch(ref -> String.valueOf(ref).contains("planilha"));
        // a planilha segue vigente na aba, e o relatório pronto não a tocou
        assertThat(contar("SELECT count(*) FROM dado_informado WHERE campo_chave = ?", CHAVE)).isEqualTo(1);
    }
}
