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

    /**
     * É uma ferramenta de resposta direta ({@code returnDirect}): o servidor mostra o resultado à pessoa. Numa rodada que também teve
     * outra ferramenta, o turno segue com uma nova ida ao modelo, e as diretas ficam fora da oferta dessa ida e das seguintes do
     * turno, para não haver laço.
     */
    default boolean direta() {
        return false;
    }

    /**
     * É uma ferramenta do Atlas da RBS (M8)? Só estas rodam na conversa da RBS: {@code buscar_documentos} (só os documentos
     * {@code GLOBAL}), {@code gerar_modelo}, {@code calcular} e {@code contar_dias}. As outras (a consulta da parceria, as entregas, a
     * cota, os prazos, o relatório) nunca são oferecidas a ela, e um pedido delas volta como "não existe". O padrão é falso: uma
     * ferramenta nova nasce só da marca.
     */
    default boolean daRbs() {
        return false;
    }

    /** O que o modelo da RBS lê desta ferramenta (nome, descrição e esquema). O padrão é o mesmo da marca. */
    default DefinicaoDeFerramenta definicaoDaRbs() {
        return definicao();
    }

    /** Fase 1, sem transação. O que devolve é entregue a {@link #executar}. O padrão não faz nada. */
    default Object preparar(AtlasContexto contexto, FontesDoTurno turno, Map<String, Object> argumentos) {
        return null;
    }

    /** Fase 2, na transação do loop. {@code preparado} é o que {@link #preparar} devolveu. */
    Execucao executar(AtlasContexto contexto, FontesDoTurno turno, Map<String, Object> argumentos, String chamadaId, Object preparado);
}
