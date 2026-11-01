package br.com.rbs.portal.atlas.busca;

import br.com.rbs.portal.atlas.busca.BuscaHibrida.Escopo;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.Pedido;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.ResultadoDaBusca;
import br.com.rbs.portal.atlas.disjuntor.DisjuntorAbertoException;
import br.com.rbs.portal.atlas.disjuntor.DisjuntoresDosProvedores;
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
 *       do provedor, que pode carregar a chave). [M8] O embedding da pergunta passa pelo disjuntor do Gemini: aberto, a chamada nem
 *       sai, a busca segue na lista textual na hora, e o motivo é {@code EMBEDDING_FALHOU} com {@code DisjuntorAbertoException}.</li>
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
    public record Consultada(ResultadoDaBusca resultado, String motivoDoDegradado, String erroDoEmbedding, long embeddingMs,
                             DisjuntorAbertoException recusadoPeloDisjuntor) {

        public Consultada(ResultadoDaBusca resultado, String motivoDoDegradado, String erroDoEmbedding, long embeddingMs) {
            this(resultado, motivoDoDegradado, erroDoEmbedding, embeddingMs, null);
        }
    }

    private final BuscaHibrida busca;
    private final ProvedorDeEmbeddings embeddings;
    private final DisjuntoresDosProvedores disjuntores;

    public ConsultaAosDocumentos(BuscaHibrida busca, ProvedorDeEmbeddings embeddings, DisjuntoresDosProvedores disjuntores) {
        this.busca = busca;
        this.embeddings = embeddings;
        this.disjuntores = disjuntores;
    }

    /** Busca {@code consulta} (já conferida e sem espaços nas pontas) no que o {@code escopo} enxerga, e filtra por {@code tipo} se vier. */
    public Consultada consultar(Escopo escopo, String consulta, String tipo) {
        float[] vetor = null;
        String motivoDoDegradado = null;
        String erroDoEmbedding = null;
        long embeddingMs = 0;
        DisjuntorAbertoException recusadoPeloDisjuntor = null;
        if (embeddings.ligado()) {
            long inicio = System.nanoTime();
            try {
                vetor = disjuntores.gemini().executar(() -> embeddings.paraConsulta(consulta));
            } catch (DisjuntorAbertoException aberto) {
                motivoDoDegradado = "EMBEDDING_FALHOU";
                erroDoEmbedding = aberto.getClass().getSimpleName();
                recusadoPeloDisjuntor = aberto;
                log.debug("Atlas: o disjuntor do Gemini está aberto; a busca segue só com a lista textual");
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
        return new Consultada(resultado, motivoDoDegradado, erroDoEmbedding, embeddingMs, recusadoPeloDisjuntor);
    }
}
