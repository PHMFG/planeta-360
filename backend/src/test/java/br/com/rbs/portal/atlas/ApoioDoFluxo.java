package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * [ATLAS] O que os testes da resposta em fluxo (SSE) das rotas 44 e 46 têm em comum: ler os eventos do corpo, abrir a conversa,
 * falar em fluxo e apagar do banco o que o teste confirmou. Essas classes NÃO são {@code @Transactional}: o turno em fluxo roda
 * em outra thread, que não enxerga uma transação aberta do teste, e o resumo do raciocínio é gravado por outra thread também.
 * Não faz parte da API.
 */
final class ApoioDoFluxo {

    static final String FLUXO_E_JSON = "text/event-stream, application/json";
    static final String AMARELO = "marketing@amarelo.example";

    private ApoioDoFluxo() {
    }

    /** Um evento do fluxo: o nome e o JSON de {@code data}. */
    record Evento(String nome, String dados) {

        <T> T ler(String caminho) {
            return JsonPath.read(dados, caminho);
        }
    }

    /** Os eventos do corpo SSE, na ordem ({@code event:nome} e {@code data:json}, separados por linha em branco). */
    static List<Evento> eventos(MvcTestResult resposta) {
        List<Evento> eventos = new ArrayList<>();
        for (String bloco : corpo(resposta).split("\n\n")) {
            String nome = null;
            StringBuilder dados = new StringBuilder();
            for (String linha : bloco.split("\n")) {
                if (linha.startsWith("event:")) {
                    nome = linha.substring("event:".length()).strip();
                } else if (linha.startsWith("data:")) {
                    dados.append(linha.substring("data:".length()));
                }
            }
            if (nome != null) {
                eventos.add(new Evento(nome, dados.toString()));
            }
        }
        return eventos;
    }

    static List<String> nomes(List<Evento> eventos) {
        return eventos.stream().map(Evento::nome).toList();
    }

    /** Os eventos que não são {@code resumo}: o resumo chega quando fica pronto, e a posição dele varia de uma rodada para outra. */
    static List<Evento> semResumos(List<Evento> eventos) {
        return eventos.stream().filter(e -> !e.nome().equals("resumo")).toList();
    }

    static List<Evento> resumos(List<Evento> eventos) {
        return eventos.stream().filter(e -> e.nome().equals("resumo")).toList();
    }

    /** Os nomes sem os resumos e sem repetição seguida: [pensando, etapa, texto, texto, resumo] vira [pensando, etapa, texto]. */
    static List<String> sequencia(List<Evento> eventos) {
        List<String> sequencia = new ArrayList<>();
        for (Evento evento : semResumos(eventos)) {
            if (sequencia.isEmpty() || !sequencia.get(sequencia.size() - 1).equals(evento.nome())) {
                sequencia.add(evento.nome());
            }
        }
        return sequencia;
    }

    static String juntar(List<Evento> eventos, String nome, String campo) {
        return String.join("", eventos.stream().filter(e -> e.nome().equals(nome)).map(e -> e.<String>ler("$." + campo)).toList());
    }

    static Evento fim(List<Evento> eventos) {
        assertThat(eventos).isNotEmpty();
        Evento ultimo = eventos.get(eventos.size() - 1);
        assertThat(ultimo.nome()).isEqualTo("fim");
        return ultimo;
    }

    /** Nenhum evento {@code resumo} vem depois do {@code fim} (o fim é sempre o último, mas a conferência fica explícita). */
    static void exigirResumosAntesDoFim(List<Evento> eventos) {
        List<String> nomes = nomes(eventos);
        assertThat(nomes.lastIndexOf("resumo")).isLessThan(nomes.indexOf("fim"));
        assertThat(nomes.indexOf("fim")).isEqualTo(nomes.size() - 1);
    }

    /** Abre uma conversa da marca Amarelo (rota 42 vazia) e devolve o id. */
    static long conversa(SessaoDeApi pessoa, JdbcClient jdbc) {
        MvcTestResult aberta = pessoa.post("/api/parcerias/" + SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo") + "/atlas/conversas", "{}");
        assertThat(aberta).hasStatus(HttpStatus.CREATED);
        return ((Number) lerJson(aberta, "$.conversaId")).longValue();
    }

    /** A rota 44 com texto, pedindo o fluxo (como o front: {@code text/event-stream, application/json}). */
    static MvcTestResult falarEmFluxo(SessaoDeApi pessoa, long conversa, String texto) {
        MvcTestResult resposta = pessoa.postAceitando("/api/atlas/conversas/" + conversa + "/mensagens",
                "{\"texto\":\"" + texto + "\"}", FLUXO_E_JSON);
        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(resposta.getResponse().getContentType()).startsWith("text/event-stream");
        return resposta;
    }

    static boolean turnoSolto(JdbcClient jdbc, long conversa) {
        return jdbc.sql("SELECT turno_desde IS NULL FROM atlas_conversa WHERE id = :id").param("id", conversa).query(Boolean.class).single();
    }

    /** Os resumos gravados nas mensagens do assistente da conversa, na ordem (nulo = a mensagem ficou sem resumo). */
    static List<String> resumosGravados(JdbcClient jdbc, long conversa) {
        return jdbc.sql("SELECT resumo_do_raciocinio FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'assistant' ORDER BY ordem")
                .param("c", conversa).query((rs, n) -> rs.getString(1)).list();
    }

    /** Os {@code reasoning_content} gravados nas mensagens do assistente da conversa, na ordem. */
    static List<String> raciociniosGravados(JdbcClient jdbc, long conversa) {
        return jdbc.sql("SELECT reasoning_content FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'assistant' ORDER BY ordem")
                .param("c", conversa).query((rs, n) -> rs.getString(1)).list();
    }

    /**
     * Guarda o último id de cada tabela do Atlas (e das que ele grava) antes do teste e apaga, depois, o que o teste confirmou.
     * As tabelas com gatilho de imutabilidade têm o gatilho desligado só durante a limpeza (em {@code try/finally}).
     */
    static final class Limpeza {

        private static final Map<String, String> TABELAS_E_GATILHOS = new LinkedHashMap<>();

        static {
            TABELAS_E_GATILHOS.put("atlas_tool_call", null);
            TABELAS_E_GATILHOS.put("atlas_calculo", null);
            TABELAS_E_GATILHOS.put("atlas_solicitacao", null);
            TABELAS_E_GATILHOS.put("atlas_relatorio", "rn_atlas_relatorio_congelado");
            TABELAS_E_GATILHOS.put("atlas_mensagem", null);
            TABELAS_E_GATILHOS.put("atlas_conversa", null);
            TABELAS_E_GATILHOS.put("dado_informado", "rn_dado_informado_imutavel");
            TABELAS_E_GATILHOS.put("historico", "rn_historico_imutavel");
        }

        private final JdbcClient jdbc;
        private final Map<String, Long> ultimoIdAntes = new LinkedHashMap<>();

        Limpeza(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        void guardar() {
            TABELAS_E_GATILHOS.keySet().forEach(tabela -> ultimoIdAntes.put(tabela,
                    jdbc.sql("SELECT coalesce(max(id), 0) FROM " + tabela).query(Long.class).single()));
        }

        void apagar() {
            TABELAS_E_GATILHOS.forEach((tabela, gatilho) -> {
                if (gatilho != null) {
                    jdbc.sql("ALTER TABLE " + tabela + " DISABLE TRIGGER " + gatilho).update();
                }
                try {
                    jdbc.sql("DELETE FROM " + tabela + " WHERE id > :ultimo").param("ultimo", ultimoIdAntes.get(tabela)).update();
                } finally {
                    if (gatilho != null) {
                        jdbc.sql("ALTER TABLE " + tabela + " ENABLE TRIGGER " + gatilho).update();
                    }
                }
            });
        }
    }
}
