package br.com.rbs.portal.atlas.loop;

import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.ModoDoAtlas;
import br.com.rbs.portal.comum.LimiteDeUsoException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * O teto diário do Atlas por parceria (plano, seção 6.7). [ATLAS] O Go limita o uso a cada 5 horas, e uma rodada de testes sem
 * teto pode gastá-lo. O contador não tem tabela: são as mensagens do assistente gravadas em {@code atlas_mensagem} no dia
 * (horário de Brasília), de todas as conversas da parceria. O botão "Montar relatório" (plano B) escreve mensagens do assistente
 * com id {@code botao_...} e NÃO conta, porque não gasta o modelo. O limite vem de {@code atlas.teto-diario-por-parceria}
 * (padrão {@value #PADRAO}); com o contador no limite ou acima, a mensagem de texto seguinte recebe 429. A conferência é no início
 * do turno: um turno que começou abaixo do teto termina, mesmo que passe dele.
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

    /** O teto já foi atingido hoje por esta parceria? */
    public boolean atingido(long parceriaId) {
        return repositorio.mensagensDoModeloHoje(parceriaId) >= teto;
    }

    /** 429 com a frase do contrato se o teto já foi atingido hoje. */
    public void exigirFolga(long parceriaId) {
        if (atingido(parceriaId)) {
            throw new LimiteDeUsoException(modo.relatorioLigado() ? MENSAGEM : MENSAGEM_NO_CHAT);
        }
    }
}
