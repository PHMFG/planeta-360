package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.ApoioDeTeste.corpo;
import static br.com.rbs.portal.ApoioDeTeste.lerJson;
import static br.com.rbs.portal.ApoioDeTeste.lerLista;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.FLUXO_E_JSON;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.conversa;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.eventos;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.exigirResumosAntesDoFim;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.falarEmFluxo;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.fim;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.juntar;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.nomes;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.raciociniosGravados;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.resumos;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.resumosGravados;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.sequencia;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.turnoSolto;
import static br.com.rbs.portal.atlas.provedor.ServidorDeLlmFalso.RESUMO_PADRAO;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.ApoioDoFluxo.Evento;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.ferramentas.RelatorioFerramentas;
import br.com.rbs.portal.atlas.loop.LoopDoAgente;
import br.com.rbs.portal.atlas.provedor.ServidorDeLlmFalso;
import br.com.rbs.portal.atlas.provedor.ServidorDeLlmFalso.Fluxo;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
 * responde 400 se o {@code reasoning_content} de um assistente com ferramentas não voltar. Confere os eventos
 * ({@code pensando}, {@code resumo}, {@code etapa}, {@code texto} já filtrado, {@code texto_recusado}, {@code fim}), o item
 * {@code raciocinio} com o {@code resumo} na conversa e o que o servidor falso recebeu na chamada à parte do resumo.
 * <p>
 * O raciocínio cru nunca sai do servidor: uma frase-sentinela que o servidor falso põe no {@code reasoning_content} não pode
 * aparecer em corpo nenhum das rotas 42, 44 (JSON e fluxo), 45 e 46, nem no log. O resumo com algarismo, nome de ferramenta ou
 * {@code {{} é descartado, a falha da chamada do resumo não muda o turno, o {@code fim} espera o resumo pendente (por até
 * {@code atlas.prazo-do-resumo}, aqui 2 segundos) e o resumo que passa do prazo sai sem resumo no {@code fim} e é gravado depois.
 * <p>
 * O turno em fluxo roda em outra thread, que não enxerga uma transação aberta do teste, e o resumo é gravado por outra thread
 * também: esta classe NÃO é {@code @Transactional}. O que ela confirma no banco é apagado no fim, como no LoopSemTransacaoTest.
 */
@TesteDeIntegracao
@ExtendWith(OutputCaptureExtension.class)
class AtlasFluxoTest {

    private static final String CHAVE = "chave-de-mentira-so-do-teste";
    /** O que o servidor falso põe no reasoning_content e que não pode aparecer em nenhuma resposta da API nem no log. */
    private static final String SENTINELA = "SENTINELA-DO-RACIOCINIO-CRU";
    private static final ServidorDeLlmFalso SERVIDOR = ServidorDeLlmFalso.iniciar();

    @DynamicPropertySource
    static void ligarOModeloNoServidorFalso(DynamicPropertyRegistry registro) {
        registro.add("spring.ai.model.chat", () -> "openai");
        registro.add("spring.ai.openai.api-key", () -> CHAVE);
        registro.add("spring.ai.openai.base-url", SERVIDOR::urlBase);
        registro.add("spring.ai.openai.max-retries", () -> "0");
        registro.add("atlas.prazo-do-resumo", () -> "PT2S");   // o padrão são 15 s; aqui o teste do resumo atrasado não precisa esperar tanto
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

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void guardarOQueJaExiste() {
        SERVIDOR.limpar();
        SERVIDOR.exigirRaciocinio(true);
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagarOQueOTesteConfirmou() {
        limpeza.apagar();
    }

    /** As mensagens do usuário das chamadas de resumo que o servidor falso recebeu: o raciocínio, entre delimitadores. */
    private List<String> entradasDosResumos() {
        return SERVIDOR.resumosRecebidos().stream().map(r -> JsonPath.<String>read(r.corpo(), "$.messages[1].content")).toList();
    }

    // ---------------------------------------------------------------------
    // Rota 44
    // ---------------------------------------------------------------------

    @Test
    void rota44TransmitePensandoEtapaTextoFiltradoResumoEFim() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        SERVIDOR.responderEmFluxo(new Fluxo().raciocinio("Vou consultar ").raciocinio("os números.")
                .chamada("call_do_servidor", RelatorioFerramentas.CONSULTAR).argumentosEmPedacos("{}", 1).fim("tool_calls"));
        // o marcador chega partido em dois pedaços e sai resolvido, num pedaço só
        SERVIDOR.responderEmFluxo(new Fluxo().raciocinio("Agora ").raciocinio("respondo.")
                .texto("Os números de ").texto("{{parc:pat").texto("rocinador}}").texto(" estão no portal.").fim("stop"));

        MvcTestResult resposta = falarEmFluxo(pessoa, conversa, "Como estou?");
        List<Evento> eventos = eventos(resposta);

        // o raciocínio cru não sai: nenhum evento "raciocinio" e nenhuma frase dele no corpo; só o aviso "pensando", uma vez por ida
        // (cada ida mandou o raciocínio em dois pedaços)
        assertThat(nomes(eventos)).doesNotContain("raciocinio");
        assertThat(corpo(resposta)).doesNotContain("Vou consultar").doesNotContain("Agora respondo");
        assertThat(sequencia(eventos)).containsExactly("pensando", "etapa", "pensando", "texto", "fim");
        assertThat(eventos.stream().filter(e -> e.nome().equals("pensando")).map(Evento::dados)).containsExactly("{}", "{}");
        assertThat(eventos.stream().filter(e -> e.nome().equals("etapa")).map(e -> e.<String>ler("$.texto")))
                .containsExactly("Consultando os números da parceria…");
        assertThat(juntar(eventos, "texto", "delta")).isEqualTo("Os números de Cliente Amarelo estão no portal.");
        assertThat(eventos.stream().filter(e -> e.nome().equals("texto")).map(e -> e.<String>ler("$.delta")))
                .noneMatch(delta -> delta.contains("{") || delta.contains("}") || delta.contains("parc:"));

        // o resumo de cada ida chega quando fica pronto, depois do texto dela ou durante a ida seguinte, e sempre antes do fim
        assertThat(resumos(eventos)).extracting(e -> e.<String>ler("$.texto")).containsExactly(RESUMO_PADRAO, RESUMO_PADRAO);
        assertThat(resumos(eventos)).allSatisfy(e -> assertThat(JsonPath.<Map<String, Object>>read(e.dados(), "$")).containsOnlyKeys("texto"));
        exigirResumosAntesDoFim(eventos);

        Evento fim = fim(eventos);
        assertThat(fim.<Number>ler("$.conversaId").longValue()).isEqualTo(conversa);
        assertThat(fim.<Boolean>ler("$.llmDisponivel")).isTrue();
        assertThat(fim.<List<String>>ler("$.itens[*].tipo")).containsExactly("usuario", "raciocinio", "raciocinio", "texto");
        assertThat(fim.<String>ler("$.itens[1].resumo")).isEqualTo(RESUMO_PADRAO);
        assertThat(fim.<String>ler("$.itens[2].resumo")).isEqualTo(RESUMO_PADRAO);
        assertThat(fim.<Map<String, Object>>ler("$.itens[1]")).containsOnlyKeys("tipo", "resumo");   // o campo "texto" com o cru não existe mais
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
        assertThat(turnoSolto(jdbc, conversa)).isTrue();

        // o resumo é uma chamada à parte por mensagem: sem ferramentas, sem fluxo, com o thinking desligado, poucos tokens e o
        // raciocínio da mensagem (e só ele) como entrada
        assertThat(SERVIDOR.resumosRecebidos()).hasSize(2);
        for (var chamada : SERVIDOR.resumosRecebidos()) {
            assertThat(JsonPath.<Object>read(chamada.corpo(), "$.thinking")).isEqualTo(Map.of("type", "disabled"));
            assertThat(chamada.corpo()).doesNotContain("\"tools\"").doesNotContain("\"stream\":true");
            assertThat(JsonPath.<Integer>read(chamada.corpo(), "$.max_tokens")).isLessThanOrEqualTo(200);
            assertThat(JsonPath.<Double>read(chamada.corpo(), "$.temperature")).isLessThan(0.2);
            assertThat(JsonPath.<List<String>>read(chamada.corpo(), "$.messages[*].role")).containsExactly("system", "user");
            assertThat(chamada.cabecalho("x-opencode-session")).containsExactly(String.valueOf(conversa));
            assertThat(chamada.cabecalho("user-agent")).containsExactly("PlanetaAtlas/0.1");
        }
        assertThat(entradasDosResumos()).hasSize(2).allSatisfy(entrada -> assertThat(entrada).contains("<<<RACIOCINIO", "RACIOCINIO>>>"));
        assertThat(entradasDosResumos()).anySatisfy(e -> assertThat(e).contains("Vou consultar os números.").doesNotContain("Agora respondo"))
                .anySatisfy(e -> assertThat(e).contains("Agora respondo.").doesNotContain("Vou consultar"));

        // o cru continua gravado (volta ao modelo); o resumo, na coluna própria
        assertThat(raciociniosGravados(jdbc, conversa)).containsExactly("Vou consultar os números.", "Agora respondo.");
        assertThat(resumosGravados(jdbc, conversa)).containsExactly(RESUMO_PADRAO, RESUMO_PADRAO);

        // a rota 45 mostra os mesmos itens, com o resumo antes do texto e sem o cru
        MvcTestResult inteira = pessoa.get("/api/atlas/conversas/" + conversa);
        assertThat(lerLista(inteira, "$.itens[*].tipo")).containsExactly("usuario", "raciocinio", "raciocinio", "texto");
        assertThat(lerJson(inteira, "$.itens[2].resumo")).isEqualTo(RESUMO_PADRAO);
        assertThat(corpo(inteira)).doesNotContain("Vou consultar").doesNotContain("Agora respondo");
    }

    @Test
    void algarismoSoltoNoMeioDoTextoParaORepasseEVemANovaRedacao() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
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
        // sem raciocínio, sem pensando, sem resumo e sem chamada de resumo
        assertThat(SERVIDOR.resumosRecebidos()).isEmpty();
    }

    @Test
    void nomeDeFerramentaNoTextoTambemERecusadoEDuasVezesViraAFraseFixa() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
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
    void semOCabecalhoARespostaEOJsonDeSempreComOItemRaciocinioComResumo() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        SERVIDOR.responder(ServidorDeLlmFalso.chamada("call_x", RelatorioFerramentas.CONSULTAR, "{}", "Vou consultar."));
        SERVIDOR.responder(ServidorDeLlmFalso.texto("Os números de {{parc:patrocinador}} estão no portal."));

        MvcTestResult resposta = pessoa.post("/api/atlas/conversas/" + conversa + "/mensagens", "{\"texto\":\"Como estou?\"}");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        assertThat(resposta.getRequest().isAsyncStarted()).isFalse();
        assertThat(resposta.getResponse().getContentType()).startsWith("application/json");
        // sem fluxo, o turno também espera o resumo antes de responder o JSON
        assertThat(lerLista(resposta, "$.itens[*].tipo")).containsExactly("usuario", "raciocinio", "texto");
        assertThat(lerJson(resposta, "$.itens[1].resumo")).isEqualTo(RESUMO_PADRAO);
        assertThat(corpo(resposta)).doesNotContain("Vou consultar");
        assertThat(SERVIDOR.recebidas()).allSatisfy(r -> assertThat(r.corpo()).doesNotContain("\"stream\":true"));
        // a 2ª ida devolveu o raciocínio (senão o servidor falso teria respondido 400 e o turno, a frase da falha)
        assertThat(lerJson(resposta, "$.itens[2].texto")).isEqualTo("Os números de Cliente Amarelo estão no portal.");
        assertThat(SERVIDOR.resumosRecebidos()).hasSize(1);
    }

    @Test
    void oBotaoEmFluxoSoTrazOFim() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);

        MvcTestResult resposta = pessoa.postAceitando("/api/atlas/conversas/" + conversa + "/mensagens",
                "{\"atalho\":\"MONTAR_RELATORIO\",\"modelo\":\"ATIVACAO_EVENTO\"}", "text/event-stream");

        assertThat(resposta).hasStatus(HttpStatus.OK);
        List<Evento> eventos = eventos(resposta);
        assertThat(nomes(eventos)).containsExactly("fim");
        assertThat(fim(eventos).<List<String>>ler("$.itens[*].tipo")).containsExactly("usuario", "texto", "cartao");
        assertThat(SERVIDOR.recebidas()).isEmpty();
        assertThat(SERVIDOR.resumosRecebidos()).isEmpty();
        assertThat(turnoSolto(jdbc, conversa)).isTrue();
    }

    // ---------------------------------------------------------------------
    // Rota 46 e o cartão
    // ---------------------------------------------------------------------

    @Test
    void rota46EmFluxoDepoisDoCartaoDevolveORaciocinioNaRetomada() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        SERVIDOR.responderEmFluxo(new Fluxo().raciocinio("Preciso criar o relatório.")
                .chamada("call_do_servidor", LoopDoAgenteTest.INICIAR).argumentosEmPedacos(LoopDoAgenteTest.ARGS_INICIAR, 7).fim("tool_calls"));
        SERVIDOR.responderEmFluxo(new Fluxo().raciocinio("Faltam dois números.")
                .chamada("call_do_servidor", LoopDoAgenteTest.SOLICITAR).argumentosEmPedacos(LoopDoAgenteTest.ARGS_CARTAO, 9).fim("tool_calls"));

        MvcTestResult aberto = falarEmFluxo(pessoa, conversa, "Monte o relatório.");
        List<Evento> abertos = eventos(aberto);

        assertThat(sequencia(abertos)).containsExactly("pensando", "etapa", "pensando", "etapa", "fim");
        assertThat(abertos.stream().filter(e -> e.nome().equals("etapa")).map(e -> e.<String>ler("$.texto")))
                .containsExactly("Abrindo o relatório…", "Preparando as perguntas…");
        assertThat(resumos(abertos)).hasSize(2);
        exigirResumosAntesDoFim(abertos);
        assertThat(corpo(aberto)).doesNotContain("Preciso criar").doesNotContain("Faltam dois");
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
        assertThat(sequencia(eventos)).containsExactly("pensando", "etapa", "pensando", "etapa", "fim");
        assertThat(eventos.stream().filter(e -> e.nome().equals("etapa")).map(e -> e.<String>ler("$.texto")))
                .containsExactly("Redigindo o documento…", "Finalizando o documento…");
        assertThat(nomes(eventos)).doesNotContain("raciocinio");
        assertThat(corpo(resposta)).doesNotContain("Vou redigir").doesNotContain("Agora finalizo");
        assertThat(resumos(eventos)).hasSize(2);
        exigirResumosAntesDoFim(eventos);
        Evento fim = fim(eventos);
        assertThat(fim.<List<String>>ler("$.itens[*].tipo")).containsExactly("raciocinio", "raciocinio", "relatorio_pronto");
        assertThat(fim.<String>ler("$.itens[0].resumo")).isEqualTo(RESUMO_PADRAO);

        assertThat(SERVIDOR.recebidas()).hasSize(4);
        assertThat(SERVIDOR.resumosRecebidos()).hasSize(4);
        String retomada = SERVIDOR.recebidas().get(2).corpo();
        assertThat(JsonPath.<List<String>>read(retomada, "$.messages[*].role"))
                .containsExactly("system", "user", "assistant", "tool", "assistant", "tool");
        assertThat(JsonPath.<String>read(retomada, "$.messages[2].reasoning_content")).isEqualTo("Preciso criar o relatório.");
        assertThat(JsonPath.<String>read(retomada, "$.messages[4].reasoning_content")).isEqualTo("Faltam dois números.");
        assertThat(JsonPath.<String>read(retomada, "$.messages[2].tool_calls[0].function.arguments")).isEqualTo(LoopDoAgenteTest.ARGS_INICIAR);
        // o resumo nunca volta ao modelo: nenhuma ida o leva
        assertThat(SERVIDOR.recebidas()).allSatisfy(r -> assertThat(r.corpo()).doesNotContain(RESUMO_PADRAO));
        assertThat(jdbc.sql("SELECT status FROM atlas_relatorio WHERE conversa_id = :c").param("c", conversa).query(String.class).single())
                .isEqualTo("PRONTO");
        assertThat(turnoSolto(jdbc, conversa)).isTrue();
    }

    @Test
    void oServidorFalsoRecusaComo400ODeepSeekSemORaciocinioEOTurnoTerminaNaFraseDaFalha() {
        // prova de que a exigência do servidor falso funciona: um histórico com tool_calls sem raciocínio dá 400
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        SERVIDOR.responderEmFluxo(new Fluxo().chamada("call_do_servidor", RelatorioFerramentas.CONSULTAR).argumentos("{}").fim("tool_calls"));

        List<Evento> eventos = eventos(falarEmFluxo(pessoa, conversa, "Oi"));

        assertThat(SERVIDOR.recebidas()).hasSize(2);
        assertThat(juntar(eventos, "texto", "delta")).isEqualTo(LoopDoAgente.FRASE_DA_FALHA);
        assertThat(fim(eventos).<List<String>>ler("$.itens[*].tipo")).containsExactly("usuario", "texto");
    }

    // ---------------------------------------------------------------------
    // O raciocínio cru não sai do servidor
    // ---------------------------------------------------------------------

    @Test
    void oRaciocinioCruNaoApareceEmCorpoNenhumDasRotasNemNoLog(CapturedOutput saida) {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");

        // rota 42 (com o botão: o plano B não tem raciocínio, mas o corpo também não pode levá-lo)
        MvcTestResult rota42 = pessoa.post("/api/parcerias/" + parceria + "/atlas/conversas",
                "{\"atalho\":\"MONTAR_RELATORIO\",\"modelo\":\"ATIVACAO_EVENTO\"}");
        assertThat(rota42).hasStatus(HttpStatus.CREATED);
        assertThat(corpo(rota42)).doesNotContain(SENTINELA);

        // rota 44 em JSON: duas idas com o sentinela no raciocínio e o cartão aberto no fim
        long conversaJson = conversa(pessoa, jdbc);
        SERVIDOR.responder(ServidorDeLlmFalso.chamada("call_x", LoopDoAgenteTest.INICIAR, LoopDoAgenteTest.ARGS_INICIAR, SENTINELA + " iniciar"));
        SERVIDOR.responder(ServidorDeLlmFalso.chamada("call_x", LoopDoAgenteTest.SOLICITAR, LoopDoAgenteTest.ARGS_CARTAO, SENTINELA + " cartao"));
        MvcTestResult rota44Json = pessoa.post("/api/atlas/conversas/" + conversaJson + "/mensagens", "{\"texto\":\"Monte o relatório.\"}");
        assertThat(rota44Json).hasStatus(HttpStatus.OK);
        assertThat(corpo(rota44Json)).doesNotContain(SENTINELA);
        assertThat(lerLista(rota44Json, "$.itens[*].tipo")).containsExactly("usuario", "raciocinio", "raciocinio", "cartao");
        long cartao = ((Number) lerJson(rota44Json, "$.itens[3].solicitacaoId")).longValue();

        // rota 45: a conversa inteira
        assertThat(corpo(pessoa.get("/api/atlas/conversas/" + conversaJson))).doesNotContain(SENTINELA);

        // rota 46 em JSON: a retomada leva o raciocínio de volta ao modelo (senão o servidor falso responde 400) e não o devolve à tela
        SERVIDOR.responder(ServidorDeLlmFalso.chamada("call_x", LoopDoAgenteTest.EDITAR,
                LoopDoAgenteTest.edicao("b-resumo", LoopDoAgenteTest.RESUMO, "b-publico", LoopDoAgenteTest.PUBLICO), SENTINELA + " redigir"));
        SERVIDOR.responder(ServidorDeLlmFalso.chamada("call_x", LoopDoAgenteTest.FINALIZAR, "{}", SENTINELA + " finalizar"));
        MvcTestResult rota46Json = pessoa.post("/api/atlas/solicitacoes/" + cartao + "/respostas",
                "{\"acao\":\"accept\",\"valores\":[{\"chave\":\"ativacao.visitantes_total\",\"valor\":1200},"
                        + "{\"chave\":\"ativacao.cadastros_consentidos\",\"valor\":1072}]}");
        assertThat(rota46Json).hasStatus(HttpStatus.OK);
        assertThat(corpo(rota46Json)).doesNotContain(SENTINELA);
        assertThat(lerLista(rota46Json, "$.itens[*].tipo")).containsExactly("raciocinio", "raciocinio", "relatorio_pronto");
        assertThat(corpo(pessoa.get("/api/atlas/conversas/" + conversaJson))).doesNotContain(SENTINELA);
        String retomada = SERVIDOR.recebidas().get(2).corpo();
        assertThat(JsonPath.<String>read(retomada, "$.messages[2].reasoning_content")).isEqualTo(SENTINELA + " iniciar");
        assertThat(JsonPath.<String>read(retomada, "$.messages[4].reasoning_content")).isEqualTo(SENTINELA + " cartao");

        // rota 44 em fluxo, com o sentinela em vários pedaços
        long conversaFluxo = conversa(pessoa, jdbc);
        SERVIDOR.responderEmFluxo(new Fluxo().raciocinio(SENTINELA).raciocinio(" em ").raciocinio("pedacos")
                .texto("Os números de ").texto("{{parc:patrocinador}}").texto(" estão no portal.").fim("stop"));
        MvcTestResult rota44Fluxo = falarEmFluxo(pessoa, conversaFluxo, "Como estou?");
        assertThat(corpo(rota44Fluxo)).doesNotContain(SENTINELA).doesNotContain("pedacos");
        assertThat(sequencia(eventos(rota44Fluxo))).containsExactly("pensando", "texto", "fim");
        assertThat(corpo(pessoa.get("/api/atlas/conversas/" + conversaFluxo))).doesNotContain(SENTINELA);

        // o log também não: nem das idas, nem dos resumos
        assertThat(saida.getAll()).doesNotContain(SENTINELA);

        // o cru só existe no banco (para voltar ao modelo) e na entrada do resumo, que é a chamada ao provedor
        assertThat(raciociniosGravados(jdbc, conversaJson)).allSatisfy(r -> assertThat(r).startsWith(SENTINELA));
        assertThat(raciociniosGravados(jdbc, conversaFluxo)).containsExactly(SENTINELA + " em pedacos");
        assertThat(entradasDosResumos()).hasSize(5).allSatisfy(e -> assertThat(e).contains(SENTINELA));
    }

    @Test
    void mensagemAntigaComRaciocinioESemResumoNaoViraItemMasComResumoSim() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        // uma conversa de antes do resumo (V9): raciocínio gravado, resumo não; e uma mensagem nova, com os dois
        jdbc.sql("""
                INSERT INTO atlas_mensagem (conversa_id, ordem, papel, conteudo, reasoning_content, resumo_do_raciocinio) VALUES
                (:c, 1, 'user', 'Oi', NULL, NULL),
                (:c, 2, 'assistant', 'Olá.', :cru, NULL),
                (:c, 3, 'user', 'E agora?', NULL, NULL),
                (:c, 4, 'assistant', 'Agora sim.', :cru, 'Conferi o pedido e respondi.')
                """).param("c", conversa).param("cru", SENTINELA + " antigo").update();

        MvcTestResult inteira = pessoa.get("/api/atlas/conversas/" + conversa);

        assertThat(lerLista(inteira, "$.itens[*].tipo")).containsExactly("usuario", "texto", "usuario", "raciocinio", "texto");
        assertThat(lerJson(inteira, "$.itens[3].resumo")).isEqualTo("Conferi o pedido e respondi.");
        assertThat(corpo(inteira)).doesNotContain(SENTINELA);
    }

    // ---------------------------------------------------------------------
    // O resumo recusado, a falha, a espera
    // ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "Conferi 12 aplicações e citei as fotos.",
            "Conferi os números de {{parc:patrocinador}} e citei as aplicações.",
            "Usei a parceria_consultar para conferir as aplicações.",
            "Conferi o dado:ativacao.visitantes_total do evento.",
            "Montei o marcador do relatório de ativação.",
            "   "})
    void resumoRecusadoPelaChecagemOuComChavesDuplasFicaSemItemENemEntraNoLog(String ruim, CapturedOutput saida) {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        SERVIDOR.responderResumo(ruim);
        SERVIDOR.responderEmFluxo(new Fluxo().raciocinio("Vou responder com calma.")
                .texto("Os números de ").texto("{{parc:patrocinador}}").texto(" estão no portal.").fim("stop"));

        List<Evento> eventos = eventos(falarEmFluxo(pessoa, conversa, "Como estou?"));

        // o turno é o de sempre, só sem resumo
        assertThat(sequencia(eventos)).containsExactly("pensando", "texto", "fim");
        assertThat(resumos(eventos)).isEmpty();
        assertThat(fim(eventos).<List<String>>ler("$.itens[*].tipo")).containsExactly("usuario", "texto");
        assertThat(SERVIDOR.resumosRecebidos()).hasSize(1);   // sem segunda tentativa
        assertThat(resumosGravados(jdbc, conversa)).containsOnlyNulls().hasSize(1);
        assertThat(lerLista(pessoa.get("/api/atlas/conversas/" + conversa), "$.itens[*].tipo")).containsExactly("usuario", "texto");
        assertThat(turnoSolto(jdbc, conversa)).isTrue();
        // o log diz que foi recusado, sem o conteúdo (nem do resumo, nem do raciocínio)
        assertThat(saida.getAll()).contains("o resumo do raciocínio da mensagem").contains("foi recusado").doesNotContain("Vou responder com calma");
        if (!ruim.isBlank()) {
            assertThat(saida.getAll()).doesNotContain(ruim.strip());
        }
    }

    @Test
    void falhaDaChamadaDoResumoNaoMudaOTurno(CapturedOutput saida) {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        SERVIDOR.responderResumoComStatus(500, "{\"error\":{\"message\":\"ECO-DO-ERRO fora do ar\"}}");
        SERVIDOR.responderEmFluxo(new Fluxo().raciocinio("Vou responder com calma.")
                .texto("Os números de ").texto("{{parc:patrocinador}}").texto(" estão no portal.").fim("stop"));

        List<Evento> eventos = eventos(falarEmFluxo(pessoa, conversa, "Como estou?"));

        assertThat(sequencia(eventos)).containsExactly("pensando", "texto", "fim");
        assertThat(resumos(eventos)).isEmpty();
        assertThat(juntar(eventos, "texto", "delta")).isEqualTo("Os números de Cliente Amarelo estão no portal.");
        Evento fim = fim(eventos);
        assertThat(fim.<List<String>>ler("$.itens[*].tipo")).containsExactly("usuario", "texto");
        assertThat(fim.<Boolean>ler("$.itens[1].porModelo")).isTrue();   // a resposta é a do modelo, não a frase fixa de falha
        assertThat(SERVIDOR.resumosRecebidos()).hasSize(1);   // sem segunda tentativa
        assertThat(resumosGravados(jdbc, conversa)).containsOnlyNulls();
        assertThat(turnoSolto(jdbc, conversa)).isTrue();
        assertThat(saida.getAll()).contains("o resumo do raciocínio da mensagem").contains("falhou")
                .doesNotContain("ECO-DO-ERRO").doesNotContain("Vou responder com calma");
    }

    @Test
    void oFimEsperaOResumoPendenteEAProximaIdaNaoEspera() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        SERVIDOR.atrasarResumos(Duration.ofMillis(1000));
        SERVIDOR.responderEmFluxo(new Fluxo().raciocinio("Vou consultar os números.")
                .chamada("call_do_servidor", RelatorioFerramentas.CONSULTAR).argumentos("{}").fim("tool_calls"));
        SERVIDOR.responderEmFluxo(new Fluxo().raciocinio("Agora respondo.")
                .texto("Os números de ").texto("{{parc:patrocinador}}").texto(" estão no portal.").fim("stop"));

        long inicio = System.nanoTime();
        List<Evento> eventos = eventos(falarEmFluxo(pessoa, conversa, "Como estou?"));
        long ms = (System.nanoTime() - inicio) / 1_000_000;

        // o fim esperou os dois resumos (os dois juntos: cada um levou 1 s, dentro do prazo de 2 s) e os trouxe
        assertThat(ms).isGreaterThanOrEqualTo(900);
        assertThat(resumos(eventos)).hasSize(2);
        exigirResumosAntesDoFim(eventos);
        assertThat(fim(eventos).<List<String>>ler("$.itens[*].tipo")).containsExactly("usuario", "raciocinio", "raciocinio", "texto");
        // a ida seguinte não esperou o resumo da anterior: o texto da 2ª ida já tinha chegado quando o 1º resumo saiu
        assertThat(nomes(eventos).indexOf("resumo")).isGreaterThan(nomes(eventos).lastIndexOf("texto"));
        assertThat(sequencia(eventos)).containsExactly("pensando", "etapa", "pensando", "texto", "fim");
        assertThat(resumosGravados(jdbc, conversa)).containsExactly(RESUMO_PADRAO, RESUMO_PADRAO);
        assertThat(turnoSolto(jdbc, conversa)).isTrue();
    }

    @Test
    void resumoQueDemoraMaisQueOPrazoSaiSemResumoNoFimMasEGravadoDepois() throws Exception {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        SERVIDOR.atrasarResumos(Duration.ofMillis(4000));   // o prazo de espera do turno é de 2 s
        SERVIDOR.responderEmFluxo(new Fluxo().raciocinio("Vou responder com calma.")
                .texto("Os números de ").texto("{{parc:patrocinador}}").texto(" estão no portal.").fim("stop"));

        long inicio = System.nanoTime();
        List<Evento> eventos = eventos(falarEmFluxo(pessoa, conversa, "Como estou?"));
        long ms = (System.nanoTime() - inicio) / 1_000_000;

        // o fim esperou o prazo (2 s) e não o resumo inteiro (4 s); saiu sem o resumo e sem nenhum evento "resumo"
        assertThat(ms).isGreaterThanOrEqualTo(1900).isLessThan(3800);
        assertThat(sequencia(eventos)).containsExactly("pensando", "texto", "fim");
        assertThat(resumos(eventos)).isEmpty();
        assertThat(fim(eventos).<List<String>>ler("$.itens[*].tipo")).containsExactly("usuario", "texto");
        // o turno não ficou preso por causa do resumo: a trava já está solta, e o resumo ainda não foi gravado
        assertThat(turnoSolto(jdbc, conversa)).isTrue();
        assertThat(resumosGravados(jdbc, conversa)).containsOnlyNulls();
        assertThat(SERVIDOR.resumosRecebidos()).hasSize(1);

        // o resumo chega depois: é gravado e aparece na próxima leitura da conversa (rota 45)
        long limite = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (resumosGravados(jdbc, conversa).get(0) == null && System.nanoTime() < limite) {
            Thread.sleep(50);
        }
        assertThat(resumosGravados(jdbc, conversa)).containsExactly(RESUMO_PADRAO);
        MvcTestResult inteira = pessoa.get("/api/atlas/conversas/" + conversa);
        assertThat(lerLista(inteira, "$.itens[*].tipo")).containsExactly("usuario", "raciocinio", "texto");
        assertThat(lerJson(inteira, "$.itens[1].resumo")).isEqualTo(RESUMO_PADRAO);
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
        while (!turnoSolto(jdbc, conversa) && System.nanoTime() < limite) {
            Thread.sleep(50);
        }
        assertThat(turnoSolto(jdbc, conversa)).as("o turno terminou").isTrue();
        assertThat(jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'assistant'").param("c", conversa)
                .query(String.class).list()).containsExactly("Os números de {{parc:patrocinador}} estão no portal.");
        assertThat(raciociniosGravados(jdbc, conversa)).containsExactly("Respondo já.");
        // o resumo foi pedido e gravado mesmo com o fluxo fechado, antes de o turno soltar a trava
        assertThat(resumosGravados(jdbc, conversa)).containsExactly(RESUMO_PADRAO);
    }
}
