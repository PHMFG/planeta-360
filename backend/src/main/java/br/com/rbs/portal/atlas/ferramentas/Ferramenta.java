package br.com.rbs.portal.atlas.ferramentas;

import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.DefinicaoDeFerramenta;
import java.util.Map;

/**
 * Uma ferramenta que o modelo de linguagem pode chamar, em duas fases (plano do Atlas completo, M4). [ATLAS]
 * <ol>
 *   <li>{@link #preparar}: roda <b>fora de qualquer transação</b>. É onde a ferramenta que chama um provedor (os embeddings da
 *       busca nos documentos) faz a chamada, sem segurar uma conexão do pool de 10 durante a rede. Devolve o que a segunda fase vai
 *       gravar. Quem não chama provedor não precisa dela.</li>
 *   <li>{@link #executar}: roda na transação de hoje do loop, a mesma em que a resposta da ferramenta é gravada. Se ela falha,
 *       nada da ferramenta fica.</li>
 * </ol>
 * Nenhuma ferramenta recebe parceria: a parceria, o usuário e a conversa vêm do {@link AtlasContexto}. Argumento fora do esquema
 * volta ao modelo como erro.
 */
public interface Ferramenta {

    String nome();

    DefinicaoDeFerramenta definicao();

    /** A posição da ferramenta na lista que o modelo recebe (a menor primeiro): a consulta, a busca nos documentos, o resto. */
    int ordem();

    /** O modelo recebe esta ferramenta neste estado da conversa? */
    boolean oferecida(EstadoDaConversa estado);

    /** Fase 1, sem transação. O que devolve é entregue a {@link #executar}. O padrão não faz nada. */
    default Object preparar(AtlasContexto contexto, FontesDoTurno turno, Map<String, Object> argumentos) {
        return null;
    }

    /** Fase 2, na transação do loop. {@code preparado} é o que {@link #preparar} devolveu. */
    Execucao executar(AtlasContexto contexto, FontesDoTurno turno, Map<String, Object> argumentos, String chamadaId, Object preparado);
}
