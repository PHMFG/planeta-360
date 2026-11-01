package br.com.rbs.portal.atlas.diagnostico;

/**
 * As etapas que um turno do Atlas pode deixar no traço ({@code atlas_traco.etapa}, o {@code CHECK} da V10 tem exatamente esta
 * lista, também as dos marcos seguintes). [ATLAS] Hoje a API grava {@link #G1_LIMITES}, {@link #LLM_IDA}, {@link #FERRAMENTA},
 * {@link #CHECAGEM}, {@link #RESUMO}, {@link #BUSCA}, {@link #SAIDA} e {@link #ERRO}; as outras (intenção e cartão do JEV, fontes,
 * resposta, coerência, lacunas e disjuntor) entram com os marcos seguintes.
 * <p>
 * {@link #RESUMO} corre em paralelo com o resto do turno (o resumo do raciocínio roda em outra thread e pode terminar depois do
 * fim do turno), e {@link #BUSCA} acontece DENTRO da etapa {@link #FERRAMENTA} da busca nos documentos (é o detalhe dela: as duas
 * listas, o RRF, os tempos). As duas ficam fora da soma do caminho crítico, cada uma em faixa separada; todas as outras são
 * sequenciais e nunca se sobrepõem: a soma das durações delas não passa do tempo do turno.
 */
public enum EtapaDoTraco {
    /** O teto diário da parceria e a trava de um turno por vez na conversa. */
    G1_LIMITES,
    J1_INTENCAO,
    J1_CARTAO,
    /** Uma ida ao modelo de linguagem. */
    LLM_IDA,
    /** Uma ferramenta que o modelo pediu. */
    FERRAMENTA,
    /** A busca nos documentos: a consulta, as duas listas, os escolhidos, o modo degradado e os tempos. Dentro da {@link #FERRAMENTA}. */
    BUSCA,
    R3_FONTES,
    /** A checagem do texto que o modelo escreveu (número solto, termo interno, marcador). */
    CHECAGEM,
    R5_RESPOSTA,
    V2_COERENCIA,
    LACUNAS,
    /** O resumo do raciocínio de uma mensagem do assistente (em paralelo). */
    RESUMO,
    /** O fim do turno: o desfecho, a espera pelos resumos pendentes e o que o fluxo transmitiu. */
    SAIDA,
    DISJUNTOR,
    /** Uma falha inesperada (a exceção, sem rastro de pilha). */
    ERRO;

    /** As etapas que ficam fora da soma do caminho crítico: o resumo, que corre em paralelo, e a busca, que está dentro da ferramenta. */
    public boolean emParalelo() {
        return this == RESUMO || this == BUSCA;
    }
}
