package br.com.rbs.portal.atlas.embeddings;

import java.util.List;

/**
 * Os embeddings desligados ({@code ATLAS_EMBEDDINGS=none}, o padrão). [ATLAS] O portal e a suíte sobem sem chave: aprovar um
 * documento nesse estado deixa o documento em {@code ERRO} com a frase abaixo, e a amostra do dev avisa no log e não cria os documentos.
 */
public class EmbeddingsDesligado implements ProvedorDeEmbeddings {

    public static final String MOTIVO = "Os embeddings estão desligados (ATLAS_EMBEDDINGS=none). Escolha gemini, com a chave do Gemini, ou teste.";

    @Override
    public String modelo() {
        return "nenhum";
    }

    @Override
    public boolean ligado() {
        return false;
    }

    @Override
    public String motivoDeDesligado() {
        return MOTIVO;
    }

    @Override
    public List<float[]> paraTrechos(String tituloDoDocumento, List<TextoDoTrecho> trechos) {
        throw new EmbeddingsIndisponivelException(MOTIVO);
    }

    @Override
    public float[] paraConsulta(String consulta) {
        throw new EmbeddingsIndisponivelException(MOTIVO);
    }
}
