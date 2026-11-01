package br.com.rbs.portal.atlas.jev;

import java.util.Locale;

/**
 * O que o JEV faz num ponto de decisão (plano do Atlas completo, M7). [ATLAS] {@code DESLIGADO}: não é consultado. {@code SOMBRA}:
 * é consultado e a decisão dele só é gravada ("teria feito X"), sem mudar nada do que o servidor faz. {@code ATIVO}: age quando a
 * confiança chega ao limiar do ponto; abaixo dele, em erro ou no tempo limite, o turno segue o caminho do modelo de linguagem.
 * <p>
 * Um conversor só aceita as duas grafias que a configuração usa: {@code none}/{@code DESLIGADO}, {@code sombra}/{@code SOMBRA} e
 * {@code ativo}/{@code ATIVO} (a variável {@code ATLAS_JEV} e a propriedade {@code atlas.jev.<ponto>.modo}). Valor que não é nenhum
 * dos três fica {@code DESLIGADO}: o JEV nunca liga por engano.
 */
public enum ModoDoJev {
    DESLIGADO,
    SOMBRA,
    ATIVO;

    /** O modo pelo texto da configuração, em qualquer caixa e com espaços; nulo, vazio ou desconhecido é {@link #DESLIGADO}. */
    public static ModoDoJev de(String texto) {
        if (texto == null) {
            return DESLIGADO;
        }
        return switch (texto.strip().toLowerCase(Locale.ROOT)) {
            case "sombra" -> SOMBRA;
            case "ativo" -> ATIVO;
            default -> DESLIGADO;
        };
    }

    /** O texto é um dos valores que o conversor conhece (e não um engano de digitação que viraria {@code DESLIGADO} calado)? */
    public static boolean conhecido(String texto) {
        if (texto == null) {
            return false;
        }
        return switch (texto.strip().toLowerCase(Locale.ROOT)) {
            case "none", "desligado", "sombra", "ativo" -> true;
            default -> false;
        };
    }

    /** O JEV é consultado neste modo? */
    public boolean consulta() {
        return this != DESLIGADO;
    }

    /** O JEV age neste modo? */
    public boolean age() {
        return this == ATIVO;
    }
}
