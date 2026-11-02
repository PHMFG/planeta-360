package br.com.rbs.portal.atlas.loop;

import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.ModoDoAtlas;
import br.com.rbs.portal.atlas.loop.VagasDoTeto.Reserva;
import br.com.rbs.portal.comum.LimiteDeUsoException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * O teto diário do Atlas por parceria (plano, seção 6.7). [ATLAS] O Go limita o uso a cada 5 horas, e uma rodada de testes sem
 * teto pode gastá-lo. O contador não tem tabela: são as mensagens do assistente gravadas em {@code atlas_mensagem} no dia
 * (horário de Brasília), de todas as conversas da parceria. Não contam as que o servidor escreveu sem o modelo ({@code sem_modelo}, V15:
 * o botão "Montar relatório" do plano B, a recusa de fora de escopo e a resposta direta do JEV) nem as {@code fora_do_modelo} (a
 * resposta fixa de um cartão aberto), porque não gastam o modelo. O limite vem de {@code atlas.teto-diario-por-parceria}
 * (padrão {@value #PADRAO}); com o contador no limite ou acima, a mensagem de texto seguinte recebe 429. A conferência é no início
 * do turno: um turno que começou abaixo do teto termina, mesmo que passe dele.
 * <p>
 * [M8] Pedidos em paralelo: o contador é o das mensagens já gravadas, e o turno só grava a primeira depois de começar. Conferir e
 * começar eram dois passos, e N pedidos juntos, com uma vaga só, passavam todos. {@link #reservar} confere e marca a vaga no mesmo
 * passo (por parceria, na memória, em {@link VagasDoTeto}, que o teto da RBS também usa), e o turno a solta quando termina; enquanto
 * ela está marcada, conta junto das mensagens gravadas. A reserva pesa um por turno em andamento, então o teto fica certo para quem
 * chega junto e um pouco mais apertado só enquanto os turnos gravam a primeira mensagem (a do turno é contada duas vezes, nunca menos).
 */
@Component
public class TetoDiario {

    public static final int PADRAO = 200;
    public static final String MENSAGEM = "O Atlas atingiu o limite de uso de hoje desta parceria. Use o botão Montar relatório ou tente amanhã.";

    /** O mesmo 429 com o relatório desligado ({@code atlas.relatorio.ligado=false}): não há botão a oferecer. */
    public static final String MENSAGEM_NO_CHAT = "O Atlas atingiu o limite de uso de hoje desta parceria. Tente amanhã.";

    private final AtlasRepositorio repositorio;
    private final int teto;
    private final ModoDoAtlas modo;
    /** Os turnos que reservaram uma vaga e ainda não acabaram, por parceria. */
    private final VagasDoTeto vagas = new VagasDoTeto();

    public TetoDiario(AtlasRepositorio repositorio, @Value("${atlas.teto-diario-por-parceria:" + PADRAO + "}") int teto,
                      ModoDoAtlas modo) {
        this.repositorio = repositorio;
        this.teto = teto;
        this.modo = modo;
    }

    /** O limite em vigor. */
    public int teto() {
        return teto;
    }

    /** Quantas mensagens do modelo esta parceria já gastou hoje (dia de Brasília). */
    public long usadasHoje(long parceriaId) {
        return repositorio.mensagensDoModeloHoje(parceriaId);
    }

    /** O teto já foi atingido hoje por esta parceria? */
    public boolean atingido(long parceriaId) {
        return usadasHoje(parceriaId) >= teto;
    }

    /** Quantos turnos desta parceria têm uma vaga marcada agora. */
    public int reservadas(long parceriaId) {
        return vagas.reservadas(parceriaId);
    }

    /**
     * Confere o teto e, havendo folga, marca uma vaga para o turno, tudo no mesmo passo (por parceria): dois pedidos juntos não
     * passam os dois com uma vaga só. Conta o que já foi gravado mais os turnos que têm vaga marcada. Sem folga, vazio.
     */
    public Optional<Reserva> tentarReservar(long parceriaId) {
        return vagas.tentar(parceriaId, () -> usadasHoje(parceriaId), teto);
    }

    /** O mesmo, com a frase do 429 do contrato quando não há folga. Quem reserva solta a vaga quando o turno acaba. */
    public Reserva reservar(long parceriaId) {
        return tentarReservar(parceriaId).orElseThrow(() -> new LimiteDeUsoException(modo.relatorioLigado() ? MENSAGEM : MENSAGEM_NO_CHAT));
    }

    /**
     * 429 com a frase do contrato se o teto já foi atingido hoje; senão, quantas mensagens a parceria já usou hoje. Só confere (não
     * marca vaga): quem vai começar um turno usa {@link #reservar}.
     */
    public long exigirFolga(long parceriaId) {
        long usadas = usadasHoje(parceriaId);
        if (usadas >= teto) {
            throw new LimiteDeUsoException(modo.relatorioLigado() ? MENSAGEM : MENSAGEM_NO_CHAT);
        }
        return usadas;
    }
}
