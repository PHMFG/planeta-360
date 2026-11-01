package br.com.rbs.portal.atlas.diagnostico;

import br.com.rbs.portal.atlas.ModoDoAtlas;
import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.UsoDoTeto;
import br.com.rbs.portal.atlas.diagnostico.DiagnosticoDtos.ProvedorDto;
import br.com.rbs.portal.atlas.diagnostico.DiagnosticoDtos.ProvedoresDto;
import br.com.rbs.portal.atlas.diagnostico.DiagnosticoDtos.TesteDeProvedorDto;
import br.com.rbs.portal.atlas.diagnostico.DiagnosticoDtos.TetoDiarioDto;
import br.com.rbs.portal.atlas.diagnostico.DiagnosticoDtos.UsoDoTetoDto;
import br.com.rbs.portal.atlas.diagnostico.TracoRepositorio.EstatisticasDoProvedor;
import br.com.rbs.portal.atlas.embeddings.EmbeddingsGemini;
import br.com.rbs.portal.atlas.embeddings.EmbeddingsIndisponivelException;
import br.com.rbs.portal.atlas.loop.TetoDiario;
import br.com.rbs.portal.atlas.provedor.FabricaDeProvedor;
import br.com.rbs.portal.atlas.provedor.ProvedorAtual;
import br.com.rbs.portal.atlas.provedor.ProvedorAtual.Escolha;
import br.com.rbs.portal.atlas.provedor.ProvedorAtual.Origem;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.MensagemDeChat;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.PedidoAoLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorSpringAi;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Os provedores externos do Atlas vistos pelo diagnóstico (rotas 52 e 53 do docs/api.md): o modelo de linguagem (OpenCode Go), o
 * JEV (TypeSafe) e os embeddings (Gemini). [ATLAS] [PERMISSAO] Só a RBS, com o diagnóstico ligado (desligado, 404). A rota 52 mostra
 * o estado de cada um (configurado, de onde vem a chave, modelo, base URL, os 4 últimos caracteres da chave, a latência e os erros
 * das últimas 24 horas, do traço) e o teto diário por parceria. A rota 53 faz uma chamada mínima a um deles, só para provar que a
 * conexão e a chave funcionam: o cliente de verdade do JEV vem no M7 e o do Gemini no M3.
 * <p>
 * [SEGURANCA] A chave nunca sai: nem inteira nem na mensagem de erro (que é nossa, ou o motivo do provedor já sem a chave). Sem
 * chave, a rota 53 responde sem chamar nada. Nenhuma transação fica aberta durante a chamada (este service não abre nenhuma).
 */
@Service
public class ProvedoresService {

    public static final String LLM = "llm";
    public static final String JEV = "jev";
    public static final String GEMINI = "gemini";
    public static final String PROVEDOR_NAO_ENCONTRADO = "Não encontramos este provedor.";
    public static final String SEM_CHAVE = "Nenhuma chave configurada.";

    /** A mensagem do teste do modelo de linguagem: mínima, sem ferramentas. */
    static final String MENSAGEM_DO_TESTE = "Responda só com a palavra ok.";
    static final String SESSAO_DO_TESTE = "debug-teste";
    static final int LIMITE_DA_RESPOSTA = 200;
    /** O pedido mínimo do JEV: uma pergunta {@code noul} em português sobre um estado curto. */
    static final String ESTADO_DO_TESTE_DO_JEV = "Até quando preciso informar os geradores na Arena?";
    static final String PERGUNTA_DO_TESTE_DO_JEV = "A mensagem pergunta sobre um prazo?";
    static final String TEXTO_DO_TESTE_DO_GEMINI = "task: search result | query: prazo dos geradores";
    static final int DIMENSOES = 768;
    private static final Duration PRAZO_DA_CONEXAO = Duration.ofSeconds(5);
    private static final Duration PRAZO_DA_CHAMADA = Duration.ofSeconds(20);

    private static final Logger log = LoggerFactory.getLogger(ProvedoresService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ProvedorAtual provedorAtual;
    private final FabricaDeProvedor fabrica;
    private final ConfiguracaoDeProvedores config;
    private final TracoRepositorio tracos;
    private final AtlasRepositorio atlas;
    private final TetoDiario teto;
    private final ModoDoAtlas modo;
    private final VerificadorDeAcesso verificador;
    // Sem seguir redirecionamento: a chave vai no cabeçalho, e só para o endereço configurado.
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(PRAZO_DA_CONEXAO).followRedirects(HttpClient.Redirect.NEVER).build();

    public ProvedoresService(ProvedorAtual provedorAtual, FabricaDeProvedor fabrica, ConfiguracaoDeProvedores config,
                             TracoRepositorio tracos, AtlasRepositorio atlas, TetoDiario teto, ModoDoAtlas modo,
                             VerificadorDeAcesso verificador) {
        this.provedorAtual = provedorAtual;
        this.fabrica = fabrica;
        this.config = config;
        this.tracos = tracos;
        this.atlas = atlas;
        this.teto = teto;
        this.modo = modo;
        this.verificador = verificador;
    }

    // ---------------------------------------------------------------------
    // Rota 52: o estado de cada provedor
    // ---------------------------------------------------------------------

    public ProvedoresDto listar(UsuarioAutenticado usuario) {
        exigirRbsComDiagnostico(usuario);
        List<ProvedorDto> provedores = List.of(doLlm(), doJev(), doGemini());
        List<UsoDoTetoDto> uso = atlas.mensagensDoModeloHojePorParceria().stream()
                .map((UsoDoTeto u) -> new UsoDoTetoDto(u.parceriaId(), u.parceria(), u.usadas())).toList();
        return new ProvedoresDto(provedores, new TetoDiarioDto(teto.teto(), uso));
    }

    private ProvedorDto doLlm() {
        Escolha escolha = provedorAtual.escolha();
        EstatisticasDoProvedor estatisticas = tracos.estatisticas(List.of(EtapaDoTraco.LLM_IDA.name()));
        return switch (escolha.origem()) {
            case DEBUG -> new ProvedorDto(LLM, true, Origem.DEBUG.name(), escolha.debug().modelo(), escolha.debug().baseUrl(),
                    escolha.debug().finalDaChave(), null, chamadas(estatisticas), erros(estatisticas), p50(estatisticas),
                    p95(estatisticas), null);
            case AMBIENTE -> new ProvedorDto(LLM, true, Origem.AMBIENTE.name(), fabrica.modeloPadrao(), fabrica.baseUrlPadrao(),
                    finalDa(fabrica.chaveDoAmbiente()), null, chamadas(estatisticas), erros(estatisticas), p50(estatisticas),
                    p95(estatisticas), null);
            case NENHUM -> new ProvedorDto(LLM, false, Origem.NENHUM.name(), fabrica.modeloPadrao(), fabrica.baseUrlPadrao(), null, null,
                    chamadas(estatisticas), erros(estatisticas), p50(estatisticas), p95(estatisticas), null);
        };
    }

    private ProvedorDto doJev() {
        EstatisticasDoProvedor estatisticas = tracos.estatisticas(List.of(EtapaDoTraco.J1_INTENCAO.name(), EtapaDoTraco.J1_CARTAO.name()));
        boolean configurado = config.jevTemChave();
        return new ProvedorDto(JEV, configurado, configurado ? Origem.AMBIENTE.name() : Origem.NENHUM.name(), config.jevModelo(),
                config.jevBaseUrl(), finalDa(config.jevChave()), config.jevModo(), chamadas(estatisticas), erros(estatisticas),
                p50(estatisticas), p95(estatisticas), null);
    }

    private ProvedorDto doGemini() {
        EstatisticasDoProvedor estatisticas = tracos.estatisticas(List.of(EtapaDoTraco.BUSCA.name()));
        boolean configurado = config.geminiTemChave();
        return new ProvedorDto(GEMINI, configurado, configurado ? Origem.AMBIENTE.name() : Origem.NENHUM.name(), config.geminiModelo(),
                config.geminiBaseUrl(), finalDa(config.geminiChave()), null, chamadas(estatisticas), erros(estatisticas),
                p50(estatisticas), p95(estatisticas), null);
    }

    private static long chamadas(EstatisticasDoProvedor e) {
        return e.chamadas() == null ? 0 : e.chamadas();
    }

    private static long erros(EstatisticasDoProvedor e) {
        return e.erros() == null ? 0 : e.erros();
    }

    private static Long p50(EstatisticasDoProvedor e) {
        return e.p50() == null ? null : Math.round(e.p50());
    }

    private static Long p95(EstatisticasDoProvedor e) {
        return e.p95() == null ? null : Math.round(e.p95());
    }

    // ---------------------------------------------------------------------
    // Rota 53: uma chamada mínima a um provedor
    // ---------------------------------------------------------------------

    /**
     * Manda uma chamada mínima ao provedor e diz se respondeu. Não grava nada (não conta no teto diário). Provedor que não existe
     * dá 404. Sem chave, responde sem chamar nada, com o motivo. A resposta é 200 sempre que a rota existe: {@code ok} diz se deu
     * certo.
     */
    public TesteDeProvedorDto testar(UsuarioAutenticado usuario, String nome) {
        exigirRbsComDiagnostico(usuario);
        return switch (nome == null ? "" : nome.toLowerCase(Locale.ROOT)) {
            case LLM -> testarLlm();
            case JEV -> testarJev();
            case GEMINI -> testarGemini();
            default -> throw new NaoEncontradoException(PROVEDOR_NAO_ENCONTRADO);
        };
    }

    /** O teste do modelo de linguagem que vale agora: o mesmo da rota de debug {@code POST /api/debug/llm/teste}. */
    public TesteDeProvedorDto testarLlm() {
        Escolha escolha = provedorAtual.escolha();
        String modelo = switch (escolha.origem()) {
            case DEBUG -> escolha.debug().modelo();
            default -> fabrica.modeloPadrao();
        };
        if (escolha.provedor() == null) {
            return new TesteDeProvedorDto(LLM, false, false, 0, modelo, null, SEM_CHAVE);
        }
        long inicio = System.nanoTime();
        try {
            RespostaDoLlm resposta = escolha.provedor().responder(
                    new PedidoAoLlm(SESSAO_DO_TESTE, List.of(MensagemDeChat.usuario(MENSAGEM_DO_TESTE)), List.of()));
            String texto = resposta.texto() == null ? "" : resposta.texto();
            if (texto.length() > LIMITE_DA_RESPOSTA) {
                texto = texto.substring(0, LIMITE_DA_RESPOSTA);
            }
            return new TesteDeProvedorDto(LLM, true, true, desde(inicio), modelo, texto, null);
        } catch (RuntimeException erro) {
            return new TesteDeProvedorDto(LLM, false, true, desde(inicio), modelo, null, motivo(erro));
        }
    }

    private TesteDeProvedorDto testarJev() {
        if (!config.jevTemChave()) {
            return new TesteDeProvedorDto(JEV, false, false, 0, config.jevModelo(), null, SEM_CHAVE);
        }
        String corpo = JSON.writeValueAsString(Map.of("model", config.jevModelo(), "state", ESTADO_DO_TESTE_DO_JEV, "questions",
                Map.of("relevante", Map.of("type", "noul", "instructions", PERGUNTA_DO_TESTE_DO_JEV))));
        return chamar(JEV, config.jevModelo(), config.jevChave(),
                () -> HttpRequest.newBuilder(URI.create(base(config.jevBaseUrl()) + "/v1/systemone")).timeout(PRAZO_DA_CHAMADA)
                        .header("Authorization", "Bearer " + config.jevChave()).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(corpo)).build(),
                resposta -> {
            JsonNode noul = resposta.path("answers").path("relevante").path("noul");
            return noul.isNumber() ? "noul " + noul.asDouble() : null;
        });
    }

    private TesteDeProvedorDto testarGemini() {
        if (!config.geminiTemChave()) {
            return new TesteDeProvedorDto(GEMINI, false, false, 0, config.geminiModelo(), null, SEM_CHAVE);
        }
        String corpo = JSON.writeValueAsString(Map.of("content", Map.of("parts", List.of(Map.of("text", TEXTO_DO_TESTE_DO_GEMINI))),
                "embedContentConfig", Map.of("outputDimensionality", DIMENSOES)));
        return chamar(GEMINI, config.geminiModelo(), config.geminiChave(),
                () -> HttpRequest.newBuilder(URI.create(base(config.geminiBaseUrl()) + "/v1beta/models/" + config.geminiModelo()
                                + ":embedContent")).timeout(PRAZO_DA_CHAMADA)
                        .header("x-goog-api-key", config.geminiChave()).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(corpo)).build(),
                resposta -> {
            JsonNode valores = resposta.path("embedding").path("values");
            if (!valores.isArray() || valores.size() != DIMENSOES) {
                return null;   // não é o formato esperado
            }
            // A mesma conferência da indexação: 768 zeros ou um valor que não é número finito são erro, e não "768 dimensões".
            EmbeddingsGemini.vetorDe(valores, DIMENSOES);
            return DIMENSOES + " dimensões";
        });
    }

    /**
     * Faz a chamada e monta a resposta. {@code lerResposta} devolve o texto do resultado, ou nulo se o corpo não é o esperado
     * (aí é erro). O motivo do erro é o status e, se o provedor explicou, a frase dele já sem a chave. Falha de rede diz só o nome
     * da exceção.
     */
    private TesteDeProvedorDto chamar(String nome, String modelo, String chave, Supplier<HttpRequest> pedido,
                                      Function<JsonNode, String> lerResposta) {
        long inicio = System.nanoTime();
        try {
            HttpResponse<String> resposta = http.send(pedido.get(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode corpo = lerJson(resposta.body());
            if (resposta.statusCode() / 100 != 2) {
                return new TesteDeProvedorDto(nome, false, true, desde(inicio), modelo, null,
                        "O provedor respondeu " + resposta.statusCode() + explicacao(corpo, chave) + ".");
            }
            String lido = corpo == null ? null : lerResposta.apply(corpo);
            if (lido == null) {
                return new TesteDeProvedorDto(nome, false, true, desde(inicio), modelo, null,
                        "O provedor respondeu " + resposta.statusCode() + ", mas não no formato esperado.");
            }
            return new TesteDeProvedorDto(nome, true, true, desde(inicio), modelo, lido, null);
        } catch (InterruptedException interrompida) {
            Thread.currentThread().interrupt();
            return new TesteDeProvedorDto(nome, false, true, desde(inicio), modelo, null, "A chamada foi interrompida.");
        } catch (EmbeddingsIndisponivelException vetorInvalido) {
            return new TesteDeProvedorDto(nome, false, true, desde(inicio), modelo, null, vetorInvalido.getMessage());
        } catch (IOException | RuntimeException falha) {
            log.warn("Atlas: o teste do provedor {} falhou ({})", nome, falha.getClass().getSimpleName());
            return new TesteDeProvedorDto(nome, false, true, desde(inicio), modelo, null,
                    "Não consegui falar com o provedor (" + falha.getClass().getSimpleName() + ").");
        }
    }

    private static JsonNode lerJson(String texto) {
        try {
            return texto == null || texto.isBlank() ? null : JSON.readTree(texto);
        } catch (RuntimeException naoEJson) {
            return null;
        }
    }

    /** A frase de erro do provedor ({@code error.message}, {@code detail} ou {@code message}), sem a chave, curta; vazia se não houver. */
    private static String explicacao(JsonNode corpo, String chave) {
        if (corpo == null) {
            return "";
        }
        String frase = corpo.path("error").path("message").asString("");
        if (frase.isBlank()) {
            frase = corpo.path("detail").asString("");
        }
        if (frase.isBlank()) {
            frase = corpo.path("message").asString("");
        }
        return frase.isBlank() ? "" : ": " + ProvedorSpringAi.limpar(frase, chave);
    }

    private static String base(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // ---------------------------------------------------------------------
    // Apoio
    // ---------------------------------------------------------------------

    private void exigirRbsComDiagnostico(UsuarioAutenticado usuario) {
        verificador.exigirRbs(usuario);
        if (!modo.diagnosticoLigado()) {
            throw new NaoEncontradoException();
        }
    }

    /** Os 4 últimos caracteres da chave; nulo sem chave ou com chave curta demais (menos de 8), para não mostrá-la inteira. */
    public static String finalDa(String chave) {
        if (chave == null || chave.length() < 8) {
            return null;
        }
        return chave.substring(chave.length() - 4);
    }

    /**
     * O motivo da falha do modelo de linguagem, sem a chave: a mensagem do {@link ProvedorSpringAi} (que já risca a chave) e,
     * quando a causa mais funda é de rede ({@link IOException}: servidor fora do ar, nome que não resolve, tempo esgotado), o nome
     * e a frase dela, que nunca levam a chave. As outras causas (o erro HTTP do provedor, cujo corpo pode repetir a chave) não
     * entram.
     */
    public static String motivo(RuntimeException erro) {
        String motivo = erro.getMessage() == null || erro.getMessage().isBlank() ? erro.getClass().getSimpleName() : erro.getMessage();
        Throwable raiz = erro;
        while (raiz.getCause() != null && raiz.getCause() != raiz) {
            raiz = raiz.getCause();
        }
        if (raiz instanceof IOException rede) {
            motivo = motivo + " Causa: " + rede.getClass().getSimpleName() + (rede.getMessage() == null ? "" : ": " + rede.getMessage());
        }
        return ProvedorSpringAi.limpar(motivo, null);
    }

    private static long desde(long inicio) {
        return (System.nanoTime() - inicio) / 1_000_000;
    }
}
