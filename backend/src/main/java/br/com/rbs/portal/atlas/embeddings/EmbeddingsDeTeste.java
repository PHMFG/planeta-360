package br.com.rbs.portal.atlas.embeddings;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Embeddings falsos e determinísticos para a suíte e para os bancos descartáveis ({@code ATLAS_EMBEDDINGS=teste}). [ATLAS] Sem rede
 * e sem chave: o vetor de 768 dimensões sai de um hash das palavras e dos pares de palavras vizinhas do texto, sem acento e sem
 * maiúsculas, com o plural "-s" tirado e sem as palavras que não dizem nada ("de", "que", "para"), e é normalizado (norma 1). Dois textos com palavras em comum têm cosseno positivo, e o mesmo
 * texto dá sempre o mesmo vetor. Não entende significado: serve para a busca híbrida, a RLS e o fluxo da ingestão serem testados sem
 * rede. O título, a seção e o texto do trecho entram juntos; a pergunta entra só com as palavras dela (os prefixos de tarefa do
 * Gemini não contam). <b>Só roda em banco descartável</b> ({@code portal_e2e}, os {@code portal_qaN}, o de teste), nunca no
 * {@code portal}: os vetores não têm o significado dos do Gemini.
 */
public class EmbeddingsDeTeste implements ProvedorDeEmbeddings {

    public static final String MODELO = "embeddings-de-teste";

    /** As palavras que não dizem nada sobre o assunto: sem elas, dois textos quaisquer pareceriam parentes. */
    private static final java.util.Set<String> SEM_ASSUNTO = java.util.Set.of("que", "com", "para", "uma", "por", "dos", "das", "nos", "nas", "ser",
            "seu", "sua", "mais", "como", "mas", "sem", "ate", "foi", "sao", "esta", "este", "essa", "esse", "quando", "onde");

    @Override
    public String modelo() {
        return MODELO;
    }

    @Override
    public boolean ligado() {
        return true;
    }

    @Override
    public String motivoDeDesligado() {
        return "";
    }

    @Override
    public List<float[]> paraTrechos(String tituloDoDocumento, List<TextoDoTrecho> trechos) {
        List<float[]> vetores = new ArrayList<>(trechos.size());
        for (TextoDoTrecho trecho : trechos) {
            vetores.add(vetorDe(tituloDoDocumento + " " + (trecho.secao() == null ? "" : trecho.secao()) + " " + trecho.texto()));
        }
        return vetores;
    }

    @Override
    public float[] paraConsulta(String consulta) {
        return vetorDe(consulta);
    }

    /** O vetor de um texto qualquer: cada palavra e cada par de palavras vizinhas soma 1 numa posição (e sinal) escolhidos pelo hash. */
    static float[] vetorDe(String texto) {
        float[] vetor = new float[DIMENSOES];
        List<String> palavras = palavras(texto);
        for (int i = 0; i < palavras.size(); i++) {
            somar(vetor, palavras.get(i), 1.0f);
            if (i + 1 < palavras.size()) {
                somar(vetor, palavras.get(i) + " " + palavras.get(i + 1), 0.5f);
            }
        }
        double norma = ProvedorDeEmbeddings.norma(vetor);
        if (norma == 0.0) {
            vetor[0] = 1.0f;   // texto sem nenhuma palavra: um vetor fixo, para o resultado continuar determinístico
            return vetor;
        }
        for (int i = 0; i < vetor.length; i++) {
            vetor[i] = (float) (vetor[i] / norma);
        }
        return vetor;
    }

    private static void somar(float[] vetor, String chave, float peso) {
        long hash = fnv1a(chave);
        int posicao = (int) Long.remainderUnsigned(hash, DIMENSOES);
        float sinal = ((hash >>> 40) & 1L) == 0 ? 1.0f : -1.0f;
        vetor[posicao] += sinal * peso;
    }

    /** As palavras do texto: minúsculas, sem acento, só letras e números, com o plural "-s" tirado das de mais de 3 letras. */
    static List<String> palavras(String texto) {
        String limpo = Normalizer.normalize(texto.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        List<String> palavras = new ArrayList<>();
        for (String palavra : limpo.split("[^a-z0-9]+")) {
            if (palavra.isEmpty() || (palavra.length() <= 2 && !Character.isDigit(palavra.charAt(0))) || SEM_ASSUNTO.contains(palavra)) {
                continue;
            }
            palavras.add(palavra.length() > 3 && palavra.endsWith("s") ? palavra.substring(0, palavra.length() - 1) : palavra);
        }
        return palavras;
    }

    /** FNV-1a de 64 bits sobre o UTF-8: estável entre JVMs, ao contrário de um hash que dependa da execução. */
    private static long fnv1a(String texto) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : texto.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xffL);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
