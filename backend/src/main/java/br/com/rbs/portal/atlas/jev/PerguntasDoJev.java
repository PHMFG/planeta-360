package br.com.rbs.portal.atlas.jev;

import br.com.rbs.portal.atlas.jev.ProvedorDeJev.PedidoAoJev;
import br.com.rbs.portal.atlas.jev.ProvedorDeJev.PerguntaAoJev;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * As perguntas que o Atlas faz ao JEV em cada ponto de decisão (plano do Atlas completo, M7), lidas de
 * {@code atlas/jev/perguntas.json}. [ATLAS] O servidor e a calibração leem o MESMO arquivo, para medir exatamente o que roda. O
 * formato:
 * <pre>{@code
 * { "versao": 1,
 *   "pontos": { "<ponto>": { "estado": "<molde do state, com {chaves}>",
 *                            "perguntas": { "<nome>": { "type": "choice", "instructions": "...", "criteria": { "<opção>": "<descrição>" } },
 *                                           "<nome2>": { "type": "noul", "instructions": "..." } } } } }
 * }</pre>
 * A ordem das chaves importa e é a que vai ao JEV: a doc dele diz que tende a escolher a primeira opção, e {@code outro} fica por
 * último na {@code intencao}. Os {@code {marcadores}} do {@code estado} e das {@code instructions} são trocados na hora ({@link
 * #estado}, {@link #pergunta}): {@code mensagem}, {@code campos}, {@code pergunta}, {@code trecho}, {@code resposta},
 * {@code trechos}, {@code frase}, {@code selos}, {@code paragrafo} e {@code campo}. No ponto {@code resposta}, a pergunta
 * {@code frase} é um molde: {@link #perguntasDeFrases} a repete como {@code frase_1}, {@code frase_2}... (até
 * {@value #MAXIMO_DE_FRASES}), com a frase de cada uma no lugar de {@code {frase}}.
 */
@Component
public class PerguntasDoJev {

    public static final String ARQUIVO = "atlas/jev/perguntas.json";
    public static final int MAXIMO_DE_FRASES = 8;
    private static final Pattern MARCADOR = Pattern.compile("\\{([a-z_]+)}");

    private record Ponto(String estado, Map<String, PerguntaAoJev> perguntas) {
    }

    private final int versao;
    private final Map<PontoDoJev, Ponto> pontos = new EnumMap<>(PontoDoJev.class);

    public PerguntasDoJev() {
        JsonNode raiz = ler();
        this.versao = raiz.path("versao").asInt(0);
        JsonNode doArquivo = raiz.path("pontos");
        for (PontoDoJev ponto : PontoDoJev.values()) {
            JsonNode no = doArquivo.path(ponto.nome());
            if (!no.isObject()) {
                throw new IllegalStateException(ARQUIVO + " não traz o ponto " + ponto.nome() + ".");
            }
            Map<String, PerguntaAoJev> perguntas = new LinkedHashMap<>();
            no.path("perguntas").properties().forEach(entrada -> perguntas.put(entrada.getKey(), pergunta(ponto, entrada.getKey(),
                    entrada.getValue())));
            if (perguntas.isEmpty() || !no.path("estado").isString()) {
                throw new IllegalStateException(ARQUIVO + ": o ponto " + ponto.nome() + " precisa de estado e de perguntas.");
            }
            pontos.put(ponto, new Ponto(no.path("estado").asString(), Collections.unmodifiableMap(perguntas)));
        }
    }

    private static JsonNode ler() {
        try (InputStream entrada = new ClassPathResource(ARQUIVO).getInputStream()) {
            return JsonMapper.builder().build().readTree(new String(entrada.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException erro) {
            throw new UncheckedIOException("Não consegui ler " + ARQUIVO + ".", erro);
        }
    }

    private static PerguntaAoJev pergunta(PontoDoJev ponto, String nome, JsonNode no) {
        String onde = ARQUIVO + ", " + ponto.nome() + "." + nome;
        String tipo = no.path("type").asString("");
        String instrucoes = no.path("instructions").asString("");
        if (instrucoes.isBlank()) {
            throw new IllegalStateException(onde + " não tem instructions.");
        }
        if (PerguntaAoJev.NOUL.equals(tipo)) {
            return PerguntaAoJev.nota(instrucoes);
        }
        if (!PerguntaAoJev.CHOICE.equals(tipo)) {
            throw new IllegalStateException(onde + " tem um type que não é choice nem noul.");
        }
        Map<String, String> criterios = new LinkedHashMap<>();
        no.path("criteria").properties().forEach(opcao -> criterios.put(opcao.getKey(), opcao.getValue().asString("")));
        if (criterios.size() < 2 || criterios.containsValue("")) {
            throw new IllegalStateException(onde + " precisa de pelo menos duas opções, cada uma com a descrição.");
        }
        return PerguntaAoJev.escolha(instrucoes, criterios);
    }

    /** A versão do arquivo de perguntas (sobe quando o texto de uma pergunta muda). */
    public int versao() {
        return versao;
    }

    /** O molde do {@code state} do ponto, com os {@code {marcadores}} ainda no lugar. */
    public String moldeDoEstado(PontoDoJev ponto) {
        return pontos.get(ponto).estado();
    }

    /** O {@code state} do ponto com os valores no lugar dos marcadores. Marcador sem valor fica como está. */
    public String estado(PontoDoJev ponto, Map<String, String> valores) {
        return preencher(pontos.get(ponto).estado(), valores);
    }

    /** As perguntas do ponto, como estão no arquivo (com os marcadores). */
    public Map<String, PerguntaAoJev> perguntas(PontoDoJev ponto) {
        return pontos.get(ponto).perguntas();
    }

    /** Uma pergunta do ponto com os valores no lugar dos marcadores das instruções. */
    public PerguntaAoJev pergunta(PontoDoJev ponto, String nome, Map<String, String> valores) {
        PerguntaAoJev molde = pontos.get(ponto).perguntas().get(nome);
        if (molde == null) {
            throw new IllegalArgumentException("O ponto " + ponto.nome() + " não tem a pergunta " + nome + ".");
        }
        return new PerguntaAoJev(molde.tipo(), preencher(molde.instrucoes(), valores), molde.criterios());
    }

    /** O pedido do ponto com todas as suas perguntas e os valores no lugar dos marcadores. */
    public PedidoAoJev pedido(PontoDoJev ponto, Map<String, String> valores) {
        Map<String, PerguntaAoJev> perguntas = new LinkedHashMap<>();
        pontos.get(ponto).perguntas().keySet().forEach(nome -> perguntas.put(nome, pergunta(ponto, nome, valores)));
        return new PedidoAoJev(estado(ponto, valores), perguntas);
    }

    /**
     * As perguntas do ponto {@code resposta}: o molde {@code frase} repetido como {@code frase_1}, {@code frase_2}..., uma por frase
     * (no máximo {@value #MAXIMO_DE_FRASES}), cada uma com a sua frase no lugar de {@code {frase}}.
     */
    public Map<String, PerguntaAoJev> perguntasDeFrases(List<String> frases) {
        Map<String, PerguntaAoJev> perguntas = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(frases.size(), MAXIMO_DE_FRASES); i++) {
            perguntas.put("frase_" + (i + 1), pergunta(PontoDoJev.RESPOSTA, "frase", Map.of("frase", frases.get(i))));
        }
        return perguntas;
    }

    /** Troca cada {@code {chave}} pelo valor, de uma vez só: o que vem dentro de um valor nunca é trocado de novo. */
    static String preencher(String molde, Map<String, String> valores) {
        Matcher achado = MARCADOR.matcher(molde);
        StringBuilder saida = new StringBuilder();
        while (achado.find()) {
            String valor = valores.get(achado.group(1));
            achado.appendReplacement(saida, Matcher.quoteReplacement(valor == null ? achado.group() : valor));
        }
        achado.appendTail(saida);
        return saida.toString();
    }
}
