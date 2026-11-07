package br.com.rbs.portal.atlas.jev;

import br.com.rbs.portal.atlas.diagnostico.EtapaDoTraco;

/**
 * Os pontos de decisão em que o JEV entra (plano do Atlas completo, M7, e o destino do arquivo, AC3). [ATLAS] O {@link #nome} é o que a configuração
 * ({@code atlas.jev.<ponto>.modo} e {@code atlas.jev.<ponto>.limiar}), o arquivo de perguntas e {@code atlas_decisao.ponto} usam. A
 * {@link #etapa} é a do traço do diagnóstico. O {@link #limiarPadrao} vale até a calibração fixar o do ponto: nos pontos de
 * {@code choice} é sobre a confiança da escolha; em {@code fontes} e {@code lacunas}, sobre o valor da {@code noul} (de 0 a 1).
 */
public enum PontoDoJev {
    /** A intenção da mensagem (J1, E2 e E4). */
    INTENCAO("intencao", EtapaDoTraco.J1_INTENCAO, 0.90),
    /** O que fazer com a mensagem de quem tem um cartão aberto (passo 6). */
    CARTAO("cartao", EtapaDoTraco.J1_CARTAO, 0.90),
    /** A relevância de cada trecho que a busca devolveu (R3). */
    FONTES("fontes", EtapaDoTraco.R3_FONTES, 0.30),
    /** Se a fonte citada apoia cada frase da resposta (R5). */
    RESPOSTA("resposta", EtapaDoTraco.R5_RESPOSTA, 0.90),
    /** Se o parágrafo do relatório combina com os selos do servidor (V2 e passo 9). */
    COERENCIA("coerencia", EtapaDoTraco.V2_COERENCIA, 0.90),
    /** Se vale perguntar uma lacuna opcional no cartão (passo 4). */
    LACUNAS("lacunas", EtapaDoTraco.LACUNAS, 0.30),
    /** Para qual destino vai o arquivo da importação (E5 e AC3); roda fora de um turno de conversa, só grava a decisão. */
    ARQUIVO_DESTINO("arquivo_destino", EtapaDoTraco.ARQUIVO_DESTINO, 0.90);

    private final String nome;
    private final EtapaDoTraco etapa;
    private final double limiarPadrao;

    PontoDoJev(String nome, EtapaDoTraco etapa, double limiarPadrao) {
        this.nome = nome;
        this.etapa = etapa;
        this.limiarPadrao = limiarPadrao;
    }

    public String nome() {
        return nome;
    }

    public EtapaDoTraco etapa() {
        return etapa;
    }

    public double limiarPadrao() {
        return limiarPadrao;
    }
}
