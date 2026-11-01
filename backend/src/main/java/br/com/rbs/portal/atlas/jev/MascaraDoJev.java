package br.com.rbs.portal.atlas.jev;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O que o JEV recebe no {@code state} (plano do Atlas completo, M7): o mínimo. [ATLAS] [PRIVACIDADE] Antes de sair do servidor, todo
 * texto que vai ao JEV passa por aqui: e-mails, telefones e CPFs (e o CNPJ, que tem a mesma cara) viram {@code [removido]}, e o nome
 * da marca da conversa também. Nome nem id de parceria entram no {@code state}: ele leva só a mensagem (ou o trecho, ou a frase) do
 * ponto de decisão. A doc do JEV diz que contexto irrelevante derruba o acerto, e o provedor é de fora; os dois motivos pedem o menos
 * possível.
 * <p>
 * Os formatos mascarados: CPF e CNPJ com pontuação, ou só algarismos (11 e 14); telefone com DDD entre parênteses, com DDD e espaço,
 * com {@code +55}, só com hífen ({@code 99999-9999}, {@code 3333-4444}) e a sequência de 10 a 13 algarismos sem separador. Data
 * ({@code 04/12/2026} e {@code 2026-12-04}), hora, valor em reais ({@code R$ 1.250,00}), tabela de números ({@code 100 200 400 40}) e
 * números pequenos não são mascarados: não são dado pessoal, e sem eles a pergunta perde o sentido (a frase "o trecho apoia R$ 312"
 * precisa do 312). O preço é um falso positivo raro, como um intervalo de anos {@code 2026-2027}, que só tira informação do que o
 * JEV lê.
 */
public final class MascaraDoJev {

    public static final String REMOVIDO = "[removido]";

    /**
     * Onde um número solto não pode começar nem acabar: grudado em outro algarismo, ou em outro algarismo por ponto, vírgula, barra,
     * dois-pontos ou hífen (o pedaço de uma data, de uma hora ou de um valor). O ponto final de uma frase, depois do número, não impede.
     */
    private static final String ANTES = "(?<!\\d)(?<!\\d[.,/:\\-])";
    private static final String DEPOIS = "(?!\\d)(?![.,/:\\-]\\d)";

    private static final List<Pattern> DADOS_PESSOAIS = List.of(
            // e-mail
            Pattern.compile("(?<![\\p{L}\\p{N}._%+-])[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)+"),
            // CNPJ e CPF com pontuação
            Pattern.compile(ANTES + "\\d{2}\\.\\d{3}\\.\\d{3}/\\d{4}-\\d{2}" + DEPOIS),
            Pattern.compile(ANTES + "\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}" + DEPOIS),
            // telefone: com o DDD entre parênteses, com +55, com o DDD e espaço, só com hífen
            Pattern.compile("(?<![\\d])(?:\\+\\d{1,3}\\s?)?\\(\\d{2}\\)\\s?9?\\d{4}[\\s-]?\\d{4}" + DEPOIS),
            Pattern.compile(ANTES + "\\+\\d{1,3}[\\s.-]?\\d{2}[\\s.-]?9?\\d{4}[\\s.-]?\\d{4}" + DEPOIS),
            Pattern.compile(ANTES + "\\d{2}\\s9?\\d{4}[\\s-]\\d{4}" + DEPOIS),
            Pattern.compile(ANTES + "9?\\d{4}-\\d{4}" + DEPOIS),
            // CPF, CNPJ e telefone só com algarismos
            Pattern.compile(ANTES + "\\d{10,14}" + DEPOIS));

    private MascaraDoJev() {
    }

    /** O texto sem e-mail, telefone, CPF nem os {@code termos} (o nome da marca, por exemplo). Nulo vira texto vazio. */
    public static String mascarar(String texto, List<String> termos) {
        if (texto == null || texto.isEmpty()) {
            return "";
        }
        String mascarado = texto;
        for (Pattern padrao : DADOS_PESSOAIS) {
            mascarado = padrao.matcher(mascarado).replaceAll(Matcher.quoteReplacement(REMOVIDO));
        }
        for (String termo : termos) {
            if (termo != null && !termo.isBlank()) {
                mascarado = Pattern.compile(Pattern.quote(termo.strip()), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                        .matcher(mascarado).replaceAll(Matcher.quoteReplacement(REMOVIDO));
            }
        }
        return mascarado;
    }
}
