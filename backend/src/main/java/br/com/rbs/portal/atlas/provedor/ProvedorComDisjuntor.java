package br.com.rbs.portal.atlas.provedor;

import br.com.rbs.portal.atlas.disjuntor.DisjuntorDoProvedor;

/**
 * O {@link ProvedorDeLlm} com o disjuntor do modelo de linguagem em volta (M8). [ATLAS] Toda ida, todo fluxo e todo resumo do
 * raciocínio passam por {@link DisjuntorDoProvedor#executar}: cinco falhas seguidas (ou metade das dez últimas) abrem o disjuntor, e
 * aberto a chamada nem sai. O que sobe é uma {@code DisjuntorAbertoException}, que é uma {@link ProvedorIndisponivelException}: o
 * loop a trata como a queda do modelo e dá a frase fixa de falha na hora, sem esperar o tempo limite (60 s). Fechado, não muda nada.
 * O {@link ProvedorAtual} embrulha o provedor que vale agora (o das variáveis de ambiente ou o da chave do debug), então o
 * disjuntor é um só para os dois.
 */
public final class ProvedorComDisjuntor implements ProvedorDeLlm {

    private final ProvedorDeLlm provedor;
    private final DisjuntorDoProvedor disjuntor;

    public ProvedorComDisjuntor(ProvedorDeLlm provedor, DisjuntorDoProvedor disjuntor) {
        this.provedor = provedor;
        this.disjuntor = disjuntor;
    }

    /** O provedor de verdade, sem o disjuntor. */
    public ProvedorDeLlm interno() {
        return provedor;
    }

    @Override
    public RespostaDoLlm responder(PedidoAoLlm pedido) {
        return disjuntor.executar(() -> provedor.responder(pedido));
    }

    @Override
    public RespostaDoLlm responderEmFluxo(PedidoAoLlm pedido, OuvinteDoFluxo ouvinte) {
        return disjuntor.executar(() -> provedor.responderEmFluxo(pedido, ouvinte));
    }

    @Override
    public String resumir(PedidoDeResumo pedido) {
        return disjuntor.executar(() -> provedor.resumir(pedido));
    }

    @Override
    public ResumoDoLlm resumirMedindo(PedidoDeResumo pedido) {
        return disjuntor.executar(() -> provedor.resumirMedindo(pedido));
    }

    @Override
    public String modelo() {
        return provedor.modelo();
    }

    @Override
    public boolean leImagem() {
        return provedor.leImagem();
    }
}
