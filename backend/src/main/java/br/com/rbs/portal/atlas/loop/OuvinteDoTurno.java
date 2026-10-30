package br.com.rbs.portal.atlas.loop;

/**
 * Quem acompanha um turno do loop do agente enquanto ele roda: a resposta em fluxo das rotas 44 e 46 (docs/api.md, "Resposta em
 * fluxo"). [ATLAS] O loop chama, na thread do turno e na ordem em que as coisas acontecem: o raciocínio cru, a frase de cada
 * ferramenta que começa, o texto da resposta já filtrado (os marcadores resolvidos; nada que a checagem do chat recusaria) e o
 * aviso de que o texto desta ida ao modelo foi recusado. Quem ouve nunca derruba o turno: se o cliente foi embora, ignora o que
 * chegar, e o turno termina e grava do mesmo jeito.
 * <p>
 * {@link #NENHUM} é o turno da resposta JSON de sempre: o loop não transmite e chama o modelo sem fluxo.
 */
public interface OuvinteDoTurno {

    /** Sem fluxo: a resposta JSON no fim do turno, como antes. */
    OuvinteDoTurno NENHUM = new OuvinteDoTurno() {
        @Override
        public boolean emFluxo() {
            return false;
        }

        @Override
        public void raciocinio(String pedaco) {
        }

        @Override
        public void etapa(String texto) {
        }

        @Override
        public void texto(String pedaco) {
        }

        @Override
        public void textoRecusado() {
        }
    };

    /** O loop pede ao modelo em fluxo e filtra o texto à medida que chega? */
    default boolean emFluxo() {
        return true;
    }

    /** Um pedaço do {@code reasoning_content}, cru (sem a checagem do texto). */
    void raciocinio(String pedaco);

    /** Uma ferramenta começou a rodar: a frase para a marca, nunca o nome da ferramenta. */
    void etapa(String texto);

    /** Um pedaço do texto para a marca, já filtrado e com os marcadores trocados pelos valores. */
    void texto(String pedaco);

    /** O texto desta ida ao modelo foi recusado: a tela descarta o que recebeu dela. */
    void textoRecusado();
}
