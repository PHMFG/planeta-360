package br.com.rbs.portal.atlas.busca;

import br.com.rbs.portal.atlas.busca.BuscaHibrida.Candidato;
import br.com.rbs.portal.documentos.KitDeDocumentos;
import br.com.rbs.portal.documentos.KitDeDocumentos.Documento;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * As perguntas do gabarito do kit (backend/src/main/resources/dev/documentos/gabarito.json) vistas como um conjunto de avaliação da
 * busca: para cada pergunta, o documento, a versão e as páginas onde está a resposta, e quem a pode fazer. Serve ao recall@5 e ao
 * MRR da busca sem o modelo de linguagem (M4), com os embeddings de teste (na suíte) ou com o Gemini ({@code @Tag("avaliacao")}).
 * Fica de fora o que o kit NÃO responde (as perguntas sem resposta e as respostas que a v2, a edição de 2026 e a circular revogada
 * tiraram do ar). Não faz parte da API.
 */
final class GabaritoDaBusca {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Uma pergunta: onde a resposta está ({@code paginas}, no documento do kit) e quem pergunta ({@code cliente}, o primeiro que a vê). */
    record Pergunta(String fato, String pergunta, Documento documento, List<Integer> paginas, List<String> tambem, String cliente) {

        /** O candidato é o trecho certo: o documento e a versão do gabarito, numa página onde a resposta está. */
        boolean acerta(Candidato candidato) {
            boolean doDocumento = candidato.documento().equals(documento.titulo()) && candidato.versao() == documento.versao();
            boolean naPagina = paginas.stream().anyMatch(p -> candidato.pagina() <= p && p <= candidato.paginaFim());
            if (doDocumento && naPagina) {
                return true;
            }
            // "também": a resposta vale a partir de outro documento (o total de energia do anexo vale o do manual)
            return tambem.stream().anyMatch(chave -> KitDeDocumentos.DOCUMENTOS.stream()
                    .anyMatch(d -> d.chave().equals(chave) && d.titulo().equals(candidato.documento()) && d.versao() == candidato.versao()
                            && "D02".equals(d.id())));
        }
    }

    /** O resultado de uma rodada: perguntas, acertos entre os 5 primeiros e a soma de 1/posição do primeiro acerto (até a 10ª). */
    record Medida(int perguntas, int acertosNos5, double somaDosInversos, List<String> erradas) {

        double recallAos5() {
            return perguntas == 0 ? 0 : (double) acertosNos5 / perguntas;
        }

        double mrr() {
            return perguntas == 0 ? 0 : somaDosInversos / perguntas;
        }

        @Override
        public String toString() {
            return String.format("%d perguntas, recall@5 %.3f, MRR %.3f", perguntas, recallAos5(), mrr());
        }
    }

    private GabaritoDaBusca() {
    }

    private static String cliente(String persona) {
        return switch (persona) {
            case "amarelo" -> "Cliente Amarelo";
            case "roxo" -> "Cliente Roxo";
            default -> "Cliente Verde";
        };
    }

    /** As perguntas que a busca tem de saber responder. */
    static List<Pergunta> perguntas() {
        List<Pergunta> perguntas = new ArrayList<>();
        try {
            JsonNode gabarito = JSON.readTree(new ClassPathResource(KitDeDocumentos.GABARITO).getInputStream());
            for (JsonNode fato : gabarito) {
                if (fato.path("semResposta").asBoolean(false) || fato.path("documento").isNull() || fato.path("visivelPara").isEmpty()) {
                    continue;
                }
                Documento documento = KitDeDocumentos.por(fato.path("id").asString());
                List<Integer> paginas = new ArrayList<>(List.of(fato.path("pagina").asInt()));
                fato.path("paginasTambem").forEach(p -> paginas.add(p.asInt()));
                List<String> tambem = new ArrayList<>();
                fato.path("tambem").forEach(t -> tambem.add(t.asString()));
                String cliente = cliente(fato.path("visivelPara").get(0).asString());
                fato.path("perguntas").forEach(p -> perguntas.add(new Pergunta(fato.path("fato").asString(), p.asString(), documento,
                        List.copyOf(paginas), List.copyOf(tambem), cliente)));
            }
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }
        return perguntas;
    }

    /** Roda todas as perguntas por {@code busca} (que devolve os candidatos na ordem do RRF) e mede. */
    static Medida medir(BiFunction<String, String, List<Candidato>> busca) {
        int perguntas = 0;
        int acertos = 0;
        double inversos = 0;
        List<String> erradas = new ArrayList<>();
        for (Pergunta p : perguntas()) {
            perguntas++;
            List<Candidato> candidatos = busca.apply(p.cliente(), p.pergunta());
            int posicao = 0;
            for (int i = 0; i < Math.min(10, candidatos.size()); i++) {
                if (p.acerta(candidatos.get(i))) {
                    posicao = i + 1;
                    break;
                }
            }
            if (posicao >= 1 && posicao <= 5) {
                acertos++;
            } else {
                erradas.add(p.fato() + " [" + p.cliente() + "]: " + p.pergunta());
            }
            if (posicao >= 1) {
                inversos += 1.0 / posicao;
            }
        }
        return new Medida(perguntas, acertos, inversos, erradas);
    }
}
