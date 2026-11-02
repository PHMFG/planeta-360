package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.ApoioDeTeste;
import br.com.rbs.portal.ApoioDeTeste.Csrf;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

/**
 * [ATLAS] [DADOS] O campo de arquivo {@code ativacao.planilha_agencia} (V14, rota 66) nas ferramentas do modelo (M6), roteirizado: a
 * planilha enviada não aparece na consulta nem nas lacunas (obrigatórias e opcionais) de {@code relatorio_iniciar},
 * {@code relatorio_solicitar_campos} recusa o campo ("recebe um arquivo, que o cartão não aceita") e o texto do relatório não pode
 * usá-la como dado ({@code {{dado:ativacao.planilha_agencia}}} não existe). O relatório do botão está em
 * {@link PlanilhaDaAgenciaNoAtlasTest}. Cada teste é desfeito no fim (@Transactional).
 */
@TesteComLlmRoteirizado
@Transactional
class PlanilhaDaAgenciaNasFerramentasTest {

    private static final String AMARELO = "marketing@amarelo.example";
    private static final String CHAVE = "ativacao.planilha_agencia";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @BeforeEach
    void zerarORoteiro() {
        modelo.limpar();
    }

    long amarelo() {
        return SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
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
        assertThat(lerJson(resposta, "$.arquivo.nomeOriginal")).isEqualTo("contagem.csv");
    }

    @Test
    @SuppressWarnings("unchecked")
    void asFerramentasDoModeloNaoOferecemAPlanilhaNemAAceitamNoCartaoNemNoTexto() {
        enviarAPlanilha(SessaoDeApi.entrar(mvc, AMARELO));
        long conversa = atlas.abrirConversa(ApoioDoAtlas.usuario(jdbc, AMARELO), amarelo(), null, ApoioDoAtlas.requisicao()).conversaId();
        modelo.chamada(LoopDoAgenteTest.CONSULTAR, "{}")
                .chamada(LoopDoAgenteTest.INICIAR, LoopDoAgenteTest.ARGS_INICIAR)
                .chamada(LoopDoAgenteTest.SOLICITAR, "{\"mensagem\":\"Informe.\",\"campos\":[\"" + CHAVE + "\"]}")
                .chamada(LoopDoAgenteTest.EDITAR, LoopDoAgenteTest.edicao("b-resumo", "Veja a planilha: {{dado:" + CHAVE + "}}."))
                .texto("Não consegui usar a planilha.");

        ConversaDto turno = atlas.enviarMensagem(ApoioDoAtlas.usuario(jdbc, AMARELO), conversa,
                new MensagemRequisicao("Use a planilha da agência no relatório.", null, null), ApoioDoAtlas.requisicao());

        assertThat(turno.itens()).hasSize(2);   // a pergunta e o texto final: nenhum cartão abriu
        List<Map<String, Object>> respostas = jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'tool' ORDER BY ordem")
                .param("c", conversa).query(String.class).list().stream()
                .map(texto -> AtlasJson.ler(texto, new TypeReference<Map<String, Object>>() {
                })).toList();
        assertThat(respostas).hasSize(4);
        // 1. a consulta: o modelo não recebe a planilha como dado
        assertThat(respostas.get(0).toString()).doesNotContain("planilha");
        // 2. o início do relatório: a planilha não é lacuna, obrigatória nem opcional
        assertThat(respostas.get(1)).containsKeys("lacunasObrigatorias", "lacunasOpcionais");
        assertThat(((List<Map<String, Object>>) respostas.get(1).get("lacunasObrigatorias"))).extracting(l -> l.get("chave"))
                .containsExactly("ativacao.visitantes_total", "ativacao.cadastros_consentidos");
        assertThat(((List<Map<String, Object>>) respostas.get(1).get("lacunasOpcionais"))).extracting(l -> l.get("chave"))
                .containsExactly("ativacao.brindes_disponiveis", "ativacao.brindes_distribuidos");
        assertThat(respostas.get(1).toString()).doesNotContain("planilha");
        // 3. o cartão recusa o campo de arquivo
        assertThat(respostas.get(2).toString()).contains("recebe um arquivo, que o cartão não aceita");
        // 4. o texto do relatório não usa a planilha como dado
        assertThat(respostas.get(3).toString()).contains("recusada").contains("não existe nesta parceria");
        assertThat(contar("SELECT count(*) FROM atlas_solicitacao s JOIN atlas_relatorio r ON r.id = s.relatorio_id WHERE r.conversa_id = ?",
                conversa)).isZero();
        assertThat(contar("SELECT count(*) FROM atlas_relatorio WHERE conversa_id = ? AND especificacao::text LIKE '%planilha%'", conversa)).isZero();
        assertThat(lerLista(mvc.get().uri("/api/campos").session(SessaoDeApi.entrar(mvc, AMARELO).sessao()).exchange(), "$[*].chave"))
                .contains(CHAVE);   // o campo existe no catálogo, só não entra no Atlas
    }
}
