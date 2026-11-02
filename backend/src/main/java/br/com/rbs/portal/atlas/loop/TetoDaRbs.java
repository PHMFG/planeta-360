package br.com.rbs.portal.atlas.loop;

import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.loop.VagasDoTeto.Reserva;
import br.com.rbs.portal.comum.LimiteDeUsoException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * O teto diário do Atlas da RBS, por pessoa (marco M8). [ATLAS] A conversa da RBS não tem parceria, então o {@link TetoDiario} por
 * parceria não se aplica a ela: o limite é um só por pessoa da RBS, simples, e vem de {@code atlas.rbs.teto-diario} (padrão
 * {@value #PADRAO}). O contador é o mesmo dos outros: as mensagens do assistente que vieram do modelo, gravadas no dia (horário de
 * Brasília) nas conversas da pessoa que não têm parceria, sem as {@code sem_modelo} nem as {@code fora_do_modelo}. Com o contador no
 * limite ou acima, a mensagem de texto seguinte recebe 429, na mesma forma do teto da parceria. A conferência é no início do turno.
 * <p>
 * Pedidos em paralelo: como no {@link TetoDiario}, {@link #reservar} confere e marca a vaga no mesmo passo (por pessoa, em
 * {@link VagasDoTeto}), e o turno a solta quando termina. Sem isso, N conversas da mesma pessoa ao mesmo tempo, com uma vaga só,
 * passavam todas, porque a mensagem do assistente só é gravada depois de o modelo responder.
 */
@Component
public class TetoDaRbs {

    public static final int PADRAO = 200;
    public static final String MENSAGEM = "O Atlas atingiu o limite de uso de hoje da sua conta. Tente amanhã.";

    private final AtlasRepositorio repositorio;
    private final int teto;
    /** Os turnos que reservaram uma vaga e ainda não acabaram, por pessoa da RBS. */
    private final VagasDoTeto vagas = new VagasDoTeto();

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

    /** Quantos turnos desta pessoa têm uma vaga marcada agora. */
    public int reservadas(long usuarioId) {
        return vagas.reservadas(usuarioId);
    }

    /**
     * Confere o teto e, havendo folga, marca uma vaga para o turno, tudo no mesmo passo (por pessoa): dois pedidos juntos não passam
     * os dois com uma vaga só. Conta o que já foi gravado mais os turnos que têm vaga marcada. Sem folga, vazio.
     */
    public Optional<Reserva> tentarReservar(long usuarioId) {
        return vagas.tentar(usuarioId, () -> usadasHoje(usuarioId), teto);
    }

    /** O mesmo, com a frase do 429 do contrato quando não há folga. Quem reserva solta a vaga quando o turno acaba. */
    public Reserva reservar(long usuarioId) {
        return tentarReservar(usuarioId).orElseThrow(() -> new LimiteDeUsoException(MENSAGEM));
    }

    /**
     * 429 se o teto já foi atingido hoje por esta pessoa; senão, quantas mensagens ela já usou hoje. Só confere (não marca vaga):
     * quem vai começar um turno usa {@link #reservar}.
     */
    public long exigirFolga(long usuarioId) {
        long usadas = usadasHoje(usuarioId);
        if (usadas >= teto) {
            throw new LimiteDeUsoException(MENSAGEM);
        }
        return usadas;
    }
}
