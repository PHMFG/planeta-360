package br.com.rbs.portal.atlas.busca;

import java.util.List;

/**
 * Os objetos da busca de teste da RBS (rota 61 do docs/api.md). [ATLAS] Não faz parte da API do portal da marca: só a RBS os vê.
 */
public final class BuscaDeTesteDtos {

    private BuscaDeTesteDtos() {
    }

    /** O corpo do pedido: a parceria que a busca vai "ser", a consulta e, se quiser, o tipo de documento. */
    public record BuscaDeTesteRequisicao(Long parceriaId, String consulta, String tipo) {
    }

    /** A parceria com o escopo que a busca usou: a edição e a cota decidem o que ela enxerga. */
    public record ParceriaDaBuscaDto(Long id, String patrocinador, Integer edicao, String cota) {
    }

    /**
     * Um trecho achado e o que as duas listas disseram dele. Na lista vetorial só a posição e a distância vêm preenchidas pela própria
     * lista, mas o trecho leva as quatro colunas e o {@code rrf} nas três listas, para a RBS ver de uma vez onde ele ficou em cada
     * uma (nulo = não entrou naquela lista). {@code inicioDoTexto} são os primeiros 240 caracteres; o texto inteiro é a rota 60.
     */
    public record CandidatoDto(Long trechoId, Long documentoId, String documento, Integer versao, String tipo, Integer pagina,
                               String secao, Integer posicaoVetorial, Double distancia, Integer posicaoTextual, Double nota,
                               Double rrf, String inicioDoTexto) {
    }

    /** Um documento que a parceria enxerga e quantos trechos ativos ele tem. */
    public record DocumentoVisivelDto(Long documentoId, String titulo, Integer versao, String tipo, String escopo, Long trechos) {
    }

    /**
     * A resposta da rota 61: a busca como o modelo a faria, sem o modelo. {@code degradado} é verdadeiro quando a busca seguiu só com
     * a lista textual, e {@code motivoDoDegradado} diz por quê. {@code vetorial} e {@code textual} são as duas listas de até 30,
     * {@code escolhidos} os 6 do RRF e {@code visiveis} os documentos que a parceria enxerga (o que o {@code atlas_ro} lê com a RLS).
     */
    public record BuscaDeTesteDto(ParceriaDaBuscaDto parceria, String consulta, String tipo, int k, boolean degradado,
                                  String motivoDoDegradado, String erroDoEmbedding, boolean semTermoUtil, long embeddingMs,
                                  long sqlMs, long trechosDeOutroModelo, List<CandidatoDto> vetorial, List<CandidatoDto> textual,
                                  List<CandidatoDto> escolhidos, List<DocumentoVisivelDto> visiveis) {
    }
}
