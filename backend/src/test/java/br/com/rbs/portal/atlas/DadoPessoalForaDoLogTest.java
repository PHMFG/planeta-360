package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * [R5.4.c] [DADO PESSOAL] O nome do destinatário de uma cortesia (D8) mora numa coluna só: {@code cortesia_destinacao.destinatario_nome}.
 * O teste grava um nome canário pela rota 83 (e o edita pela 84, e provoca recusas com ele) e varre:
 * <ul>
 *   <li>todas as colunas de texto (e JSON) de todas as tabelas, por {@code information_schema}, menos a coluna onde o nome deve ficar;</li>
 *   <li>todos os eventos de log (com o nível em DEBUG), inclusive o de um corpo quebrado que traz o canário;</li>
 *   <li>o JSON e as fontes de {@code consultar_cota}, que usa as mesmas tabelas;</li>
 *   <li>as respostas de erro e o histórico da parceria.</li>
 * </ul>
 * Há controle positivo nas duas varreduras: sem ele, "não achou" não quereria dizer nada. Cada teste é desfeito no fim (as varreduras
 * enxergam o que a transação deles gravou).
 */
@TesteComLlmRoteirizado
@TestPropertySource(properties = {"atlas.relatorio.ligado=false"})
@Transactional
class DadoPessoalForaDoLogTest extends ApoioDasConsultas {

    private static final String TABELA_DO_NOME = "cortesia_destinacao";
    private static final String COLUNA_DO_NOME = "destinatario_nome";

    @Autowired
    MockMvcTester mvc;

    private final Logger raiz = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private ListAppender<ILoggingEvent> captura;
    private Level nivelAnterior;

    @BeforeEach
    void ligarCaptura() {
        captura = new ListAppender<>();
        captura.start();
        raiz.addAppender(captura);
        nivelAnterior = raiz.getLevel();
        raiz.setLevel(Level.DEBUG);
    }

    @AfterEach
    void desligarCaptura() {
        raiz.detachAppender(captura);
        raiz.setLevel(nivelAnterior);
    }

    private static String canario() {
        return "Canario-" + UUID.randomUUID();
    }

    /** Os eventos de log que citam o canário (o logger e o começo da mensagem, para a falha dizer quem vazou). */
    private List<String> eventosCom(String canario) {
        return captura.list.stream().filter(e -> citam(e, canario))
                .map(e -> e.getLoggerName() + ": " + e.getFormattedMessage().substring(0, Math.min(200, e.getFormattedMessage().length()))).toList();
    }

    private boolean logou(String canario) {
        return captura.list.stream().anyMatch(e -> citam(e, canario));
    }

    private static boolean citam(ILoggingEvent e, String canario) {
        return e.getFormattedMessage().contains(canario) || e.toString().contains(canario)
                || (e.getThrowableProxy() != null && e.getThrowableProxy().getMessage() != null
                        && e.getThrowableProxy().getMessage().contains(canario));
    }

    /** Todas as colunas de texto e JSON de todas as tabelas do banco que têm o canário, como "tabela.coluna", sem as de {@code exceto}. */
    private List<String> ondeEstaOCanario(String canario, List<String> exceto) {
        List<String[]> colunas = jdbc.sql("""
                SELECT c.table_name, c.column_name FROM information_schema.columns c
                JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                WHERE c.table_schema = 'public' AND t.table_type = 'BASE TABLE'
                  AND c.data_type IN ('text', 'character varying', 'character', 'json', 'jsonb', 'ARRAY')
                ORDER BY c.table_name, c.ordinal_position
                """).query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).list();
        assertThat(colunas.size()).as("a varredura enxerga as colunas de texto do banco").isGreaterThan(50);
        List<String> achadas = new ArrayList<>();
        for (String[] coluna : colunas) {
            String nome = coluna[0] + "." + coluna[1];
            if (exceto.contains(nome)) {
                continue;
            }
            Long quantas = jdbc.sql("SELECT count(*) FROM \"" + coluna[0] + "\" WHERE CAST(\"" + coluna[1] + "\" AS text) LIKE :padrao")
                    .param("padrao", "%" + canario + "%").query(Long.class).single();
            if (quantas > 0) {
                achadas.add(nome);
            }
        }
        return achadas;
    }

    @Test
    void controlePositivoAVarreduraAchaOCanarioNaColunaCertaEOAppenderTambem() {
        long verde = parceria("Cliente Verde");
        String canario = canario();
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, VERDE);

        assertThat(marca.post("/api/parcerias/" + verde + "/cortesias/destinacoes",
                "{\"setor\":\"ARENA\",\"dia\":\"SABADO\",\"quantidade\":1,\"destinatario\":\"" + canario + "\"}")).hasStatus(HttpStatus.CREATED);
        org.slf4j.LoggerFactory.getLogger(DadoPessoalForaDoLogTest.class).info("registro de propósito: {}", canario);

        assertThat(ondeEstaOCanario(canario, List.of())).as("a varredura acha o nome exatamente onde ele deve ficar")
                .containsExactly(TABELA_DO_NOME + "." + COLUNA_DO_NOME);
        assertThat(logou(canario)).as("sem esta prova, 'não achou no log' não quereria dizer nada").isTrue();

        // e acha em qualquer outra coluna que o guarde: o canário num detalhe do histórico
        jdbc.sql("INSERT INTO historico (entidade, entidade_id, acao, detalhe, usuario_id) VALUES ('teste', 1, 'EDICAO', :d, (SELECT id FROM usuario WHERE email = 'b2b@rbs.example'))")
                .param("d", "vazou " + canario).update();
        assertThat(ondeEstaOCanario(canario, List.of())).contains("historico.detalhe", TABELA_DO_NOME + "." + COLUNA_DO_NOME);
    }

    @Test
    void oNomeSoFicaNaColunaDoDestinatarioNemEmLogNemNoAtlasNemNasRespostas() throws Exception {
        long verde = parceria("Cliente Verde");
        String canario = canario();
        String novoNome = canario();
        String uri = "/api/parcerias/" + verde + "/cortesias/destinacoes";
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, VERDE);
        SessaoDeApi rbs = SessaoDeApi.entrar(mvc, RBS);
        List<MvcTestResult> respostas = new ArrayList<>();

        // grava (marca), recusa por passar do disponível (com o nome no corpo), edita o nome (RBS) e lê
        MvcTestResult criada = marca.post(uri, "{\"setor\":\"ARENA\",\"dia\":\"SABADO\",\"quantidade\":1,\"destinatario\":\"" + canario + "\"}");
        assertThat(criada).hasStatus(HttpStatus.CREATED);
        respostas.add(marca.post(uri, "{\"setor\":\"ARENA\",\"dia\":\"SABADO\",\"quantidade\":5,\"destinatario\":\"" + canario + "\"}"));
        respostas.add(marca.post(uri, "{\"setor\":\"ARENA\",\"dia\":\"SABADO\",\"quantidade\":1,\"destinatario\":\"" + canario + "\\u0000\"}"));
        respostas.add(marca.post(uri, "{\"setor\":\"PISTA\",\"dia\":\"SABADO\",\"quantidade\":1,\"destinatario\":\"" + canario + "\"}"));
        respostas.add(marca.post(uri, "{\"setor\":\"ARENA\",\"dia\":\"SABADO\",\"quantidade\":1,\"destinatario\":\"" + canario));   // JSON quebrado
        for (MvcTestResult recusada : respostas) {
            assertThat(recusada.getResponse().getStatus()).isBetween(400, 422);
        }
        long id = jdbc.sql("SELECT d.id FROM cortesia_destinacao d WHERE d.destinatario_nome = :n").param("n", canario).query(Long.class).single();
        MvcTestResult editada = rbs.patch(uri + "/" + id, "{\"destinatario\":\"" + novoNome + "\"}");
        assertThat(editada).hasStatus(HttpStatus.OK);
        respostas.add(marca.get("/api/parcerias/" + verde + "/historico"));
        respostas.add(rbs.get("/api/historico?q=Canario"));
        respostas.add(rbs.get("/api/historico.csv"));
        respostas.add(rbs.get("/api/parcerias/" + verde + "/consolidacao"));
        respostas.add(rbs.get("/api/parcerias/" + verde + "/relatorio"));
        respostas.add(marca.get("/api/parcerias/" + verde));
        respostas.add(rbs.get("/api/parcerias/" + verde));

        // (1) as respostas de erro, o histórico e o detalhe da parceria não trazem nenhum dos dois nomes
        for (MvcTestResult resposta : respostas) {
            assertThat(corpo(resposta)).doesNotContain(canario).doesNotContain(novoNome);
        }
        // (2) a coluna certa tem o nome novo e nenhuma outra tabela tem nenhum dos dois
        for (String nome : List.of(canario, novoNome)) {
            assertThat(ondeEstaOCanario(nome, List.of(TABELA_DO_NOME + "." + COLUNA_DO_NOME))).as("fora de cortesia_destinacao: " + nome).isEmpty();
        }
        assertThat(ondeEstaOCanario(novoNome, List.of())).containsExactly(TABELA_DO_NOME + "." + COLUNA_DO_NOME);
        // (3) nenhum evento de log, em DEBUG, cita um dos nomes (inclusive o do corpo quebrado e o da recusa)
        assertThat(eventosCom(canario)).as("log com o nome antigo").isEmpty();
        assertThat(eventosCom(novoNome)).as("log com o nome novo").isEmpty();
        // (4) o Atlas: consultar_cota lê as mesmas tabelas e só soma
        AtlasContexto contexto = contexto(VERDE, "Cliente Verde");
        FontesDoTurno turno = turno();
        String json = chamar(contexto, turno, "consultar_cota", "{}").resultadoJson();
        assertThat(json).contains("\"destinadas\"").doesNotContain(canario).doesNotContain(novoNome);
        assertThat(turno.fontes().toString()).doesNotContain(canario).doesNotContain(novoNome);
        // (5) e nenhum evento de log do próprio Atlas
        assertThat(logou(canario)).isFalse();
    }

    @Test
    void retirarADestinacaoApagaONomeDoBanco() {
        long verde = parceria("Cliente Verde");
        String canario = canario();
        SessaoDeApi marca = SessaoDeApi.entrar(mvc, VERDE);
        marca.post("/api/parcerias/" + verde + "/cortesias/destinacoes",
                "{\"setor\":\"ARENA\",\"dia\":\"SABADO\",\"quantidade\":1,\"destinatario\":\"" + canario + "\"}");
        long id = jdbc.sql("SELECT d.id FROM cortesia_destinacao d WHERE d.destinatario_nome = :n").param("n", canario).query(Long.class).single();

        assertThat(marca.delete("/api/parcerias/" + verde + "/cortesias/destinacoes/" + id)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(ondeEstaOCanario(canario, List.of())).as("apagar é apagar de verdade: o nome não está em coluna nenhuma").isEmpty();
    }

    private static final String RBS = ApoioDoAtlas.RBS;
}
