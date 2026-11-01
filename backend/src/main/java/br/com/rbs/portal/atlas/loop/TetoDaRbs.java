package br.com.rbs.portal.atlas.loop;

import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.comum.LimiteDeUsoException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * O teto diário do Atlas da RBS, por pessoa (marco M8). [ATLAS] A conversa da RBS não tem parceria, então o {@link TetoDiario} por
 * parceria não se aplica a ela: o limite é um só por pessoa da RBS, simples, e vem de {@code atlas.rbs.teto-diario} (padrão
 * {@value #PADRAO}). O contador é o mesmo dos outros: as mensagens do assistente que vieram do modelo, gravadas no dia (horário de
 * Brasília) nas conversas da pessoa que não têm parceria, sem as {@code sem_modelo} nem as {@code fora_do_modelo}. Com o contador no
 * limite ou acima, a mensagem de texto seguinte recebe 429, na mesma forma do teto da parceria. A conferência é no início do turno.
 */
@Component
public class TetoDaRbs {

    public static final int PADRAO = 200;
    public static final String MENSAGEM = "O Atlas atingiu o limite de uso de hoje da sua conta. Tente amanhã.";

    private final AtlasRepositorio repositorio;
    private final int teto;

    public TetoDaRbs(AtlasRepositorio repositorio, @Value("${atlas.rbs.teto-diario:" + PADRAO + "}") int teto) {
        this.repositorio = repositorio;
        this.teto = teto;
    }

    /** O limite em vigor. */
    public int teto() {
        return teto;
    }

    /** Quantas mensagens do modelo esta pessoa da RBS já gastou hoje. */
    public long usadasHoje(long usuarioId) {
        return repositorio.mensagensDoModeloHojeDaRbs(usuarioId);
    }

    /** 429 se o teto já foi atingido hoje por esta pessoa; senão, quantas mensagens ela já usou hoje. */
    public long exigirFolga(long usuarioId) {
        long usadas = usadasHoje(usuarioId);
        if (usadas >= teto) {
            throw new LimiteDeUsoException(MENSAGEM);
        }
        return usadas;
    }
}
