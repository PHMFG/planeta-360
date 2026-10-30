package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.ferramentas.RelatorioFerramentas;
import br.com.rbs.portal.atlas.loop.LoopDoAgente;
import br.com.rbs.portal.atlas.provedor.ServidorDeLlmFalso;
import br.com.rbs.portal.atlas.provedor.ServidorDeLlmFalso.Fluxo;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * [ATLAS] A resposta em fluxo (SSE) das rotas 44 e 46 de ponta a ponta: a API sobe com o Spring AI de verdade apontando para um
 * servidor HTTP falso e local que transmite no formato de fluxo da OpenAI ({@code stream: true}, deltas com
 * {@code reasoning_content}, pedidos de ferramenta em pedaços, {@code [DONE]}) e, como o DeepSeek com o raciocínio ligado,
 * responde 400 se o {@code reasoning_content} de um assistente com ferramentas não voltar. Confere os eventos ({@code raciocinio},
 * {@code etapa}, {@code texto} já filtrado, {@code texto_recusado}, {@code fim}), o item {@code raciocinio} na conversa, a
 * resposta JSON de sempre sem o cabeçalho e o turno que termina mesmo com o cliente fora.
 * <p>
 * O turno em fluxo roda em outra thread, que não enxerga uma transação aberta do teste: esta classe NÃO é {@code @Transactional}.
 * O que ela confirma no banco é apagado no fim, como no LoopSemTransacaoTest.
 */
@TesteDeIntegracao
class AtlasFluxoTest {

    private static final String CHAVE = "chave-de-mentira-so-do-teste";
    private static final String AMARELO = "marketing@amarelo.example";
    private static final String FLUXO_E_JSON = "text/event-stream, application/json";
    private static final ServidorDeLlmFalso SERVIDOR = ServidorDeLlmFalso.iniciar();
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

    @DynamicPropertySource
    static void ligarOModeloNoServidorFalso(DynamicPropertyRegistry registro) {
        registro.add("spring.ai.model.chat", () -> "openai");
        registro.add("spring.ai.openai.api-key", () -> CHAVE);
        registro.add("spring.ai.openai.base-url", SERVIDOR::urlBase);
        registro.add("spring.ai.openai.max-retries", () -> "0");
    }

    @AfterAll
    static void derrubarOServidor() {
        SERVIDOR.close();
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AtlasService atlas;

    private final Map<String, Long> ultimoIdAntes = new LinkedHashMap<>();

    @BeforeEach
    void guardarOQueJaExiste() {
        SERVIDOR.limpar();
        SERVIDOR.exigirRaciocinio(true);
        TABELAS_E_GATILHOS.keySet().forEach(tabela -> ultimoIdAntes.put(tabela,
                jdbc.sql("SELECT coalesce(max(id), 0) FROM " + tabela).query(Long.class).single()));
    }

    @AfterEach
    void apagarOQueOTesteConfirmou() {
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

    // ---------------------------------------------------------------------
    // Apoio
    // ---------------------------------------------------------------------

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

    /** Os nomes sem repetição seguida: [raciocinio, raciocinio, etapa] vira [raciocinio, etapa]. */
    static List<String> sequencia(List<Evento> eventos) {
        List<String> sequencia = new ArrayList<>();
        for (Evento evento : eventos) {
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

    long conversa(SessaoDeApi pessoa) {
        MvcTestResult aberta = pessoa.post("/api/parcerias/" + SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo") + "/atlas/conversas", "{}");
        assertThat(aberta).hasStatus(HttpStatus.CREATED);
        return ((Number) lerJson(aberta, "$.conversaId")).longValue();
    }

    MvcTestResult falarEmFluxo(SessaoDeApi pessoa, long conversa, String texto) {
        MvcTestResult resposta = pessoa.postAceitando("/api/atlas/conversas/" + conversa + "/mensagens",
                "{\"texto\":\"" + texto + "\"}", FLUXO_E_JSON);
        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(resposta.getResponse().getContentType()).startsWith("text/event-stream");
        return resposta;
    }

    boolean turnoSolto(long conversa) {
        return jdbc.sql("SELECT turno_desde IS NULL FROM atlas_conversa WHERE id = :id").param("id", conversa).query(Boolean.class).single();
    }

    // ---------------------------------------------------------------------
    // Rota 44
    // ---------------------------------------------------------------------

    @Test
    void rota44TransmiteRaciocinioEtapaTextoFiltradoEFim() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa);
        SERVIDOR.responderEmFluxo(new Fluxo().raciocinio("Vou consultar ").raciocinio("os números.")
                .chamada("call_do_servidor", RelatorioFerramentas.CONSULTAR).argumentosEmPedacos("{}", 1).fim("tool_calls"));
        // o marcador chega partido em dois pedaços e sai resolvido, num pedaço só
        SERVIDOR.responderEmFluxo(new Fluxo().raciocinio("Agora ").raciocinio("respondo.")
                .texto("Os números de ").texto("{{parc:pat").texto("rocinador}}").texto(" estão no portal.").fim("stop"));

        List<Evento> eventos = eventos(falarEmFluxo(pessoa, conversa, "Como estou?"));

        assertThat(sequencia(eventos)).containsExactly("raciocinio", "etapa", "raciocinio", "texto", "fim");
        assertThat(eventos.stream().filter(e -> e.nome().equals("etapa")).map(e -> e.<String>ler("$.texto")))
                .containsExactly("Consultando os números da parceria…");
        assertThat(juntar(eventos, "raciocinio", "delta")).isEqualTo("Vou consultar os números.Agora respondo.");
        assertThat(eventos.stream().filter(e -> e.nome().equals("raciocinio")).count()).as("em pedaços").isEqualTo(4);
        assertThat(juntar(eventos, "texto", "delta")).isEqualTo("Os números de Cliente Amarelo estão no portal.");
        assertThat(eventos.stream().filter(e -> e.nome().equals("texto")).map(e -> e.<String>ler("$.delta")))
                .noneMatch(delta -> delta.contains("{") || delta.contains("}") || delta.contains("parc:"));

        Evento fim = fim(eventos);
        assertThat(fim.<Number>ler("$.conversaId").longValue()).isEqualTo(conversa);
        assertThat(fim.<Boolean>ler("$.llmDisponivel")).isTrue();
        assertThat(fim.<List<String>>ler("$.itens[*].tipo")).containsExactly("usuario", "raciocinio", "raciocinio", "texto");
        assertThat(fim.<String>ler("$.itens[1].texto")).isEqualTo("Vou consultar os números.");
        assertThat(fim.<String>ler("$.itens[2].texto")).isEqualTo("Agora respondo.");
        assertThat(fim.<String>ler("$.itens[3].texto")).isEqualTo("Os números de Cliente Amarelo estão no portal.");
        assertThat(fim.<Boolean>ler("$.itens[3].porModelo")).isTrue();

        // o que foi ao provedor: em fluxo, com o raciocínio ligado e o reasoning_content da 1ª ida voltando na 2ª
        assertThat(SERVIDOR.recebidas()).hasSize(2);
        for (var recebida : SERVIDOR.recebidas()) {
            assertThat(JsonPath.<Boolean>read(recebida.corpo(), "$.stream")).isTrue();
            assertThat(JsonPath.<Object>read(recebida.corpo(), "$.thinking")).isEqualTo(Map.of("type", "enabled"));
            assertThat(recebida.cabecalho("user-agent")).containsExactly("PlanetaAtlas/0.1");
            assertThat(recebida.cabecalho("x-opencode-session")).containsExactly(String.valueOf(conversa));
        }
        assertThat(JsonPath.<String>read(SERVIDOR.ultima().corpo(), "$.messages[2].reasoning_content")).isEqualTo("Vou consultar os números.");
        assertThat(JsonPath.<String>read(SERVIDOR.ultima().corpo(), "$.messages[2].tool_calls[0].function.arguments")).isEqualTo("{}");
        assertThat(turnoSolto(conversa)).isTrue();

        // a rota 45 mostra os mesmos itens, com o raciocínio antes do texto
        MvcTestResult inteira = pessoa.get("/api/atlas/conversas/" + conversa);
        assertThat(lerLista(inteira, "$.itens[*].tipo")).containsExactly("usuario", "raciocinio", "raciocinio", "texto");
        assertThat(lerJson(inteira, "$.itens[2].texto")).isEqualTo("Agora respondo.");
    }

    @Test
    void algarismoSoltoNoMeioDoTextoParaORepasseEVemANovaRedacao() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa);
        SERVIDOR.responderEmFluxo(new Fluxo().texto("Vocês tiveram ").texto("muitas visitas, ").texto("19 ao todo").texto(", e foi bom.")
                .fim("stop"));
        SERVIDOR.responderEmFluxo(new Fluxo().texto("Para {{parc:patrocinador}}, ").texto("os números estão no portal.").fim("stop"));

        List<Evento> eventos = eventos(falarEmFluxo(pessoa, conversa, "Quantas visitas?"));

        assertThat(sequencia(eventos)).containsExactly("texto", "texto_recusado", "texto", "fim");
        int recusa = nomes(eventos).indexOf("texto_recusado");
        assertThat(juntar(eventos.subList(0, recusa), "texto", "delta")).isEqualTo("Vocês tiveram muitas visitas, ");
        assertThat(eventos.get(recusa).dados()).isEqualTo("{}");
        assertThat(juntar(eventos.subList(recusa, eventos.size()), "texto", "delta"))
                .isEqualTo("Para Cliente Amarelo, os números estão no portal.");
        assertThat(corpo(pessoa.get("/api/atlas/conversas/" + conversa))).doesNotContain("19 ao todo");
        Evento fim = fim(eventos);
        assertThat(fim.<List<String>>ler("$.itens[*].tipo")).containsExactly("usuario", "texto");
        assertThat(fim.<String>ler("$.itens[1].texto")).isEqualTo("Para Cliente Amarelo, os números estão no portal.");
        // a nova redação foi pedida ao modelo, como sem fluxo
        assertThat(SERVIDOR.recebidas()).hasSize(2);
        assertThat(JsonPath.<List<String>>read(SERVIDOR.ultima().corpo(), "$.messages[*].role"))
                .containsExactly("system", "user", "assistant", "user");
    }

    @Test
    void nomeDeFerramentaNoTextoTambemERecusadoEDuasVezesViraAFraseFixa() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa);
        SERVIDOR.responderEmFluxo(new Fluxo().texto("Vou usar a ").texto("parceria_consultar agora.").fim("stop"));
        SERVIDOR.responderEmFluxo(new Fluxo().texto("Um ").texto("marcador resolve.").fim("stop"));

        List<Evento> eventos = eventos(falarEmFluxo(pessoa, conversa, "Oi"));

        assertThat(sequencia(eventos)).containsExactly("texto", "texto_recusado", "texto", "texto_recusado", "texto", "fim");
        List<String> textos = eventos.stream().filter(e -> e.nome().equals("texto")).map(e -> e.<String>ler("$.delta")).toList();
        assertThat(textos).containsExactly("Vou usar a ", "Um ", LoopDoAgente.FRASE_FIXA);
        assertThat(fim(eventos).<List<String>>ler("$.itens[*].tipo")).containsExactly("usuario", "texto");
        assertThat(fim(eventos).<Boolean>ler("$.itens[1].porModelo")).isFalse();
    }

    @Test
    void semOCabecalhoARespostaEOJsonDeSempreComOItemRaciocinio() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa);
        SERVIDOR.responder(ServidorDeLlmFalso.chamada("call_x", RelatorioFerramentas.CONSULTAR, "{}", "Vou consultar."));
        SERVIDOR.responder(ServidorDeLlmFalso.texto("Os números de {{parc:patrocinador}} estão no portal."));

        MvcTestResult resposta = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Como estou?\"}");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(resposta.getRequest().isAsyncStarted()).isFalse();
        assertThat(resposta.getResponse().getContentType()).startsWith("application/json");
        assertThat(lerLista(resposta, "$.itens[*].tipo")).containsExactly("usuario", "raciocinio", "texto");
        assertThat(lerJson(resposta, "$.itens[1].texto")).isEqualTo("Vou consultar.");
        assertThat(SERVIDOR.recebidas()).allSatisfy(r -> assertThat(r.corpo()).doesNotContain("\"stream\":true"));
        // a 2ª ida devolveu o raciocínio (senão o servidor falso teria respondido 400 e o turno, a frase da falha)
        assertThat(lerJson(resposta, "$.itens[2].texto")).isEqualTo("Os números de Cliente Amarelo estão no portal.");
    }

    @Test
    void oBotaoEmFluxoSoTrazOFim() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa);

        MvcTestResult resposta = pessoa.postAceitando("/api/atlas/conversas/" + conversa + "/mensagens",
                "{\"atalho\":\"MONTAR_RELATORIO\",\"modelo\":\"ATIVACAO_EVENTO\"}", "text/event-stream");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        List<Evento> eventos = eventos(resposta);
        assertThat(nomes(eventos)).containsExactly("fim");
        assertThat(fim(eventos).<List<String>>ler("$.itens[*].tipo")).containsExactly("usuario", "texto", "cartao");
        assertThat(SERVIDOR.recebidas()).isEmpty();
        assertThat(turnoSolto(conversa)).isTrue();
    }

    // ---------------------------------------------------------------------
    // Rota 46 e o cartão
    // ---------------------------------------------------------------------

    @Test
    void rota46EmFluxoDepoisDoCartaoDevolveORaciocinioNaRetomada() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa);
        SERVIDOR.responderEmFluxo(new Fluxo().raciocinio("Preciso criar o relatório.")
                .chamada("call_do_servidor", LoopDoAgenteTest.INICIAR).argumentosEmPedacos(LoopDoAgenteTest.ARGS_INICIAR, 7).fim("tool_calls"));
        SERVIDOR.responderEmFluxo(new Fluxo().raciocinio("Faltam dois números.")
                .chamada("call_do_servidor", LoopDoAgenteTest.SOLICITAR).argumentosEmPedacos(LoopDoAgenteTest.ARGS_CARTAO, 9).fim("tool_calls"));

        List<Evento> abertos = eventos(falarEmFluxo(pessoa, conversa, "Monte o relatório."));

        assertThat(sequencia(abertos)).containsExactly("raciocinio", "etapa", "raciocinio", "etapa", "fim");
        assertThat(abertos.stream().filter(e -> e.nome().equals("etapa")).map(e -> e.<String>ler("$.texto")))
                .containsExactly("Abrindo o relatório…", "Preparando as perguntas…");
        Evento fimDoCartao = fim(abertos);
        assertThat(fimDoCartao.<List<String>>ler("$.itens[*].tipo")).containsExactly("usuario", "raciocinio", "raciocinio", "cartao");
        long cartao = fimDoCartao.<Number>ler("$.itens[3].solicitacaoId").longValue();
        assertThat(fimDoCartao.<String>ler("$.itens[3].status")).isEqualTo("PENDENTE");

        // a retomada remonta o histórico do banco: sem o reasoning_content das duas, o servidor falso responderia 400
        SERVIDOR.responderEmFluxo(new Fluxo().raciocinio("Vou redigir.").chamada("call_do_servidor", LoopDoAgenteTest.EDITAR)
                .argumentosEmPedacos(LoopDoAgenteTest.edicao("b-resumo", LoopDoAgenteTest.RESUMO, "b-publico", LoopDoAgenteTest.PUBLICO), 40)
                .fim("tool_calls"));
        SERVIDOR.responderEmFluxo(new Fluxo().raciocinio("Agora finalizo.").chamada("call_do_servidor", LoopDoAgenteTest.FINALIZAR)
                .argumentos("{}").fim("tool_calls"));
        MvcTestResult resposta = pessoa.postAceitando("/api/atlas/solicitacoes/" + cartao + "/respostas",
                "{\"acao\":\"accept\",\"valores\":[{\"chave\":\"ativacao.visitantes_total\",\"valor\":1200},"
                        + "{\"chave\":\"ativacao.cadastros_consentidos\",\"valor\":1072}]}", "text/event-stream");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(resposta.getResponse().getContentType()).startsWith("text/event-stream");
        List<Evento> eventos = eventos(resposta);
        assertThat(sequencia(eventos)).containsExactly("raciocinio", "etapa", "raciocinio", "etapa", "fim");
        assertThat(eventos.stream().filter(e -> e.nome().equals("etapa")).map(e -> e.<String>ler("$.texto")))
                .containsExactly("Redigindo o documento…", "Finalizando o documento…");
        assertThat(fim(eventos).<List<String>>ler("$.itens[*].tipo")).containsExactly("raciocinio", "raciocinio", "relatorio_pronto");

        assertThat(SERVIDOR.recebidas()).hasSize(4);
        String retomada = SERVIDOR.recebidas().get(2).corpo();
        assertThat(JsonPath.<List<String>>read(retomada, "$.messages[*].role"))
                .containsExactly("system", "user", "assistant", "tool", "assistant", "tool");
        assertThat(JsonPath.<String>read(retomada, "$.messages[2].reasoning_content")).isEqualTo("Preciso criar o relatório.");
        assertThat(JsonPath.<String>read(retomada, "$.messages[4].reasoning_content")).isEqualTo("Faltam dois números.");
        assertThat(JsonPath.<String>read(retomada, "$.messages[2].tool_calls[0].function.arguments")).isEqualTo(LoopDoAgenteTest.ARGS_INICIAR);
        assertThat(jdbc.sql("SELECT status FROM atlas_relatorio WHERE conversa_id = :c").param("c", conversa).query(String.class).single())
                .isEqualTo("PRONTO");
        assertThat(turnoSolto(conversa)).isTrue();
    }

    @Test
    void oServidorFalsoRecusaComo400ODeepSeekSemORaciocinioEOTurnoTerminaNaFraseDaFalha() {
        // prova de que a exigência do servidor falso funciona: um histórico com tool_calls sem raciocínio dá 400
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa);
        SERVIDOR.responderEmFluxo(new Fluxo().chamada("call_do_servidor", RelatorioFerramentas.CONSULTAR).argumentos("{}").fim("tool_calls"));

        List<Evento> eventos = eventos(falarEmFluxo(pessoa, conversa, "Oi"));

        assertThat(SERVIDOR.recebidas()).hasSize(2);
        assertThat(juntar(eventos, "texto", "delta")).isEqualTo(LoopDoAgente.FRASE_DA_FALHA);
        assertThat(fim(eventos).<List<String>>ler("$.itens[*].tipo")).containsExactly("usuario", "texto");
    }

    // ---------------------------------------------------------------------
    // O cliente vai embora
    // ---------------------------------------------------------------------

    @Test
    void oTurnoTerminaEGravaMesmoSeOClienteDesconectar() throws Exception {
        UsuarioAutenticado marca = ApoioDoAtlas.usuario(jdbc, AMARELO);
        long conversa = atlas.abrirConversa(marca, SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"), null, ApoioDoAtlas.requisicao())
                .conversaId();
        AtomicReference<SseEmitter> emissor = new AtomicReference<>();
        CountDownLatch devolvido = new CountDownLatch(1);
        // na primeira ida ao modelo, o cliente já foi embora: a conexão caiu
        SERVIDOR.aoReceber(recebida -> {
            try {
                if (devolvido.await(10, TimeUnit.SECONDS) && emissor.get() != null) {
                    emissor.get().completeWithError(new IOException("o cliente fechou a aba"));
                }
            } catch (InterruptedException interrompido) {
                Thread.currentThread().interrupt();
            }
        });
        SERVIDOR.responderEmFluxo(new Fluxo().raciocinio("Respondo já.").texto("Os números de ").texto("{{parc:patrocinador}} ")
                .texto("estão no portal.").fim("stop"));

        emissor.set(atlas.enviarMensagemEmFluxo(marca, conversa, new MensagemRequisicao("Oi", null, null), ApoioDoAtlas.requisicao()));
        devolvido.countDown();

        long limite = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!turnoSolto(conversa) && System.nanoTime() < limite) {
            Thread.sleep(50);
        }
        assertThat(turnoSolto(conversa)).as("o turno terminou").isTrue();
        assertThat(jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'assistant'").param("c", conversa)
                .query(String.class).list()).containsExactly("Os números de {{parc:patrocinador}} estão no portal.");
        assertThat(jdbc.sql("SELECT reasoning_content FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'assistant'")
                .param("c", conversa).query(String.class).single()).isEqualTo("Respondo já.");
    }
}
