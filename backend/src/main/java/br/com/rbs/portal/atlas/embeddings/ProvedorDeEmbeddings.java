package br.com.rbs.portal.atlas.embeddings;

import java.util.List;
import java.util.Locale;

/**
 * Quem transforma texto em vetor para a busca do Atlas (M3 do plano do Atlas completo). [ATLAS] Três implementações, escolhidas por
 * {@code ATLAS_EMBEDDINGS}: {@code gemini} ({@link EmbeddingsGemini}, a de verdade), {@code teste} ({@link EmbeddingsDeTeste}, a
 * determinística da suíte e dos bancos descartáveis) e {@code none} ({@link EmbeddingsDesligado}). O vetor é de 768 dimensões e vem
 * normalizado (norma 1): a busca usa cosseno.
 *
 * <p>Os prefixos de tarefa vão no texto, e não num parâmetro (o {@code gemini-embedding-2} não aceita {@code task_type}): o trecho
 * leva {@code title: <título> | text: <seção>\n<texto>} e a pergunta leva {@code task: search result | query: <consulta>}. Quem
 * chama passa o título, a seção e o texto separados; cada implementação decide o que fazer com eles.
 *
 * <p>Nenhum método abre transação nem toca o banco, e nenhum deles deixa a chave do provedor sair em mensagem de erro. Quem chama
 * <b>não</b> pode segurar uma transação durante a chamada: a rede do provedor é lenta e o pool tem 10 conexões.
 */
public interface ProvedorDeEmbeddings {

    int DIMENSOES = 768;

    /** O nome do modelo, como o documento o guarda em {@code modelo_embedding}. */
    String modelo();

    /** Se dá para chamar: o desligado e o Gemini sem chave respondem falso, e a ingestão não começa. */
    boolean ligado();

    /** Por que não está ligado, em português (para o log e para a tela). Vazio quando está ligado. */
    String motivoDeDesligado();

    /** O trecho de um documento, com a seção que o contém (pode ser nula). */
    record TextoDoTrecho(String secao, String texto) {
    }

    /** Os vetores dos trechos de um documento, na ordem dada. Parte em lotes por conta própria. */
    List<float[]> paraTrechos(String tituloDoDocumento, List<TextoDoTrecho> trechos);

    /** O vetor de uma pergunta. */
    float[] paraConsulta(String consulta);

    /** O texto que vai ao Gemini para um trecho: {@code title: <título> | text: <seção>\n<texto>}. */
    static String textoDoTrecho(String tituloDoDocumento, TextoDoTrecho trecho) {
        String secao = trecho.secao() == null || trecho.secao().isBlank() ? "" : trecho.secao() + "\n";
        return "title: " + tituloDoDocumento + " | text: " + secao + trecho.texto();
    }

    /** O texto que vai ao Gemini para uma pergunta: {@code task: search result | query: <consulta>}. */
    static String textoDaConsulta(String consulta) {
        return "task: search result | query: " + consulta;
    }

    /** O vetor como o PostgreSQL o lê: {@code [0.1,0.2,...]}, para {@code CAST(:vetor AS vector)}. */
    static String comoTextoDoBanco(float[] vetor) {
        StringBuilder texto = new StringBuilder(vetor.length * 10).append('[');
        for (int i = 0; i < vetor.length; i++) {
            texto.append(i == 0 ? "" : ",").append(Float.toString(vetor[i]).toLowerCase(Locale.ROOT));
        }
        return texto.append(']').toString();
    }

    /** Divide o vetor pela norma quando ela foge de 1 por mais de 0,01; a norma do vetor sai por {@link #norma(float[])}. */
    static float[] normalizarSeFugir(float[] vetor) {
        double norma = norma(vetor);
        if (Math.abs(norma - 1.0) <= 0.01 || norma == 0.0) {
            return vetor;
        }
        float[] normalizado = new float[vetor.length];
        for (int i = 0; i < vetor.length; i++) {
            normalizado[i] = (float) (vetor[i] / norma);
        }
        return normalizado;
    }

    static double norma(float[] vetor) {
        double soma = 0;
        for (float valor : vetor) {
            soma += (double) valor * valor;
        }
        return Math.sqrt(soma);
    }
}
