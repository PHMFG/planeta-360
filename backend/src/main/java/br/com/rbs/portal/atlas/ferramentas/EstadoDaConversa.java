package br.com.rbs.portal.atlas.ferramentas;

import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeRelatorio;
import java.util.Optional;

/**
 * Em que pé está a conversa, para o {@link RegistroDeFerramentas} escolher o que oferecer ao modelo (plano, seção 3.2). [ATLAS]
 * {@code relatorio} é o relatório em andamento da conversa (em montagem ou esperando o cartão), se houver; {@code podePedirCartao}
 * é falso quando ele já usou os três cartões que o modelo pode pedir.
 */
public record EstadoDaConversa(boolean relatorioLigado, Optional<LinhaDeRelatorio> relatorio, boolean podePedirCartao) {

    /** O cartão está aberto: o turno para e nenhuma ferramenta é oferecida. */
    public boolean cartaoAberto() {
        return relatorioLigado && relatorio.isPresent() && !"MONTANDO".equals(relatorio.get().status());
    }

    /** Há um relatório em montagem (sem cartão aberto). */
    public boolean montando() {
        return relatorioLigado && relatorio.isPresent() && "MONTANDO".equals(relatorio.get().status());
    }

    /** O relatório está ligado e ainda não há nenhum em andamento. */
    public boolean semRelatorio() {
        return relatorioLigado && relatorio.isEmpty();
    }
}
