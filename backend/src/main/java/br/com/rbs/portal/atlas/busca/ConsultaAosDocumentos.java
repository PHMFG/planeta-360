package br.com.rbs.portal.atlas.busca;

import br.com.rbs.portal.atlas.busca.BuscaHibrida.Escopo;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.Pedido;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.ResultadoDaBusca;
import br.com.rbs.portal.atlas.embeddings.ProvedorDeEmbeddings;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Uma consulta aos documentos de ponta a ponta: o embedding da consulta e a {@link BuscaHibrida} (plano do Atlas completo, M4).
 * [ATLAS] É o caminho único de quem busca: a ferramenta {@code buscar_documentos} do modelo e a busca de teste da RBS (rota 61)
 * passam por aqui, e por isso a RBS vê o que o modelo veria.
 * <ul>
 *   <li><b>O embedding é calculado fora de qualquer transação</b>; a busca abre a própria, só de leitura, como {@code atlas_ro}.
 *       Quem chama não pode estar dentro de uma transação por causa da rede do provedor (o loop chama na fase 1 da ferramenta).</li>
 *   <li><b>Sem embeddings</b> (desligados, sem chave, provedor fora do ar, disjuntor aberto), a busca segue só com a lista textual e
 *       o resultado diz por quê: {@code EMBEDDINGS_DESLIGADOS} ou {@code EMBEDDING_FALHOU} (com o nome da exceção, nunca a frase
 *       do provedor, que pode carregar a chave).</li>
 * </ul>
 */
@Component
public class ConsultaAosDocumentos {

    /** O tamanho máximo da consulta, em caracteres (a ferramenta do modelo e a busca de teste da RBS têm o mesmo limite). */
    public static final int LIMITE_DA_CONSULTA = 300;
    /** Os tipos de documento pelos quais a busca filtra. */
    public static final Set<String> TIPOS = Set.of("MANUAL_OPERACOES", "PLANO_COMERCIAL", "ANEXO_PARCERIA", "FAQ", "COMUNICADO", "OUTRO");

    private static final Logger log = LoggerFactory.getLogger(ConsultaAosDocumentos.class);

    /**
     * O que a consulta devolveu e como foi: {@code motivoDoDegradado} é nulo quando houve embedding; {@code erroDoEmbedding} é o nome da
     * exceção quando ele falhou; {@code embeddingMs} é o tempo do embedding (0 sem embeddings).
     */
    public record Consultada(ResultadoDaBusca resultado, String motivoDoDegradado, String erroDoEmbedding, long embeddingMs) {
    }

    private final BuscaHibrida busca;
    private final ProvedorDeEmbeddings embeddings;

    public ConsultaAosDocumentos(BuscaHibrida busca, ProvedorDeEmbeddings embeddings) {
        this.busca = busca;
        this.embeddings = embeddings;
    }

    /** Busca {@code consulta} (já conferida e sem espaços nas pontas) no que o {@code escopo} enxerga, e filtra por {@code tipo} se vier. */
    public Consultada consultar(Escopo escopo, String consulta, String tipo) {
        float[] vetor = null;
        String motivoDoDegradado = null;
        String erroDoEmbedding = null;
        long embeddingMs = 0;
        if (embeddings.ligado()) {
            long inicio = System.nanoTime();
            try {
                vetor = embeddings.paraConsulta(consulta);
            } catch (RuntimeException indisponivel) {
                motivoDoDegradado = "EMBEDDING_FALHOU";
                erroDoEmbedding = indisponivel.getClass().getSimpleName();
                log.warn("Atlas: o embedding da consulta falhou ({}); a busca segue só com a lista textual", erroDoEmbedding);
            }
            embeddingMs = (System.nanoTime() - inicio) / 1_000_000;
        } else {
            motivoDoDegradado = "EMBEDDINGS_DESLIGADOS";
        }
        ResultadoDaBusca resultado = busca.buscar(new Pedido(escopo, consulta, tipo, BuscaHibrida.K_PADRAO, vetor,
                vetor == null ? null : embeddings.modelo()));
        return new Consultada(resultado, motivoDoDegradado, erroDoEmbedding, embeddingMs);
    }
}
