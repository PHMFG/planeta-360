package br.com.rbs.portal.atlas.loop;

import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeSolicitacao;
import br.com.rbs.portal.atlas.AtlasRepositorio.Parado;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.AcaoDoCartao;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.RespostaDoCartao;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * O descarte do relatório que ficou parado. [ATLAS] Um relatório MONTANDO ou AGUARDANDO_RESPOSTA sem nenhuma mensagem nova há mais
 * de {@value #DIAS_PARADO} dias vira DESCARTADO, e o cartão pendente dele vira CANCELADA. A chamada de ferramenta que o cartão
 * deixou aberta é fechada com {@code cancel} na própria conversa, para o histórico não ficar com uma {@code tool_call} sem
 * resposta. Conferido quando a pessoa lista ou abre as conversas (não há tarefa agendada). Descartar pela tela fica para depois.
 * Pronto não se descarta (o gatilho do banco recusaria).
 */
@Component
public class DescarteDeRelatorios {

    public static final int DIAS_PARADO = 7;

    private final AtlasRepositorio repositorio;
    private final HistoricoDaConversa historico;

    public DescarteDeRelatorios(AtlasRepositorio repositorio, HistoricoDaConversa historico) {
        this.repositorio = repositorio;
        this.historico = historico;
    }

    /** Descarta os relatórios parados destas conversas. Devolve quantos descartou. Uma transação curta. */
    @Transactional
    public int varrer(List<Long> conversaIds) {
        List<Parado> parados = repositorio.relatoriosParados(conversaIds, DIAS_PARADO);
        for (Parado parado : parados) {
            for (LinhaDeSolicitacao cartao : repositorio.solicitacoesDoRelatorio(parado.relatorioId())) {
                if ("PENDENTE".equals(cartao.status())) {
                    repositorio.responderSolicitacao(cartao.id(), "CANCELADA");
                    String resultado = new RespostaDoCartao(cartao.id(), parado.relatorioId(), cartao.toolCallId(),
                            AcaoDoCartao.CANCEL, List.of()).resultadoJson();
                    historico.resultado(parado.conversaId(), cartao.toolCallId(), resultado);
                    repositorio.concluirChamada(parado.conversaId(), cartao.toolCallId(), "relatorio_solicitar_campos", resultado);
                }
            }
            repositorio.mudarStatus(parado.relatorioId(), "DESCARTADO");
        }
        return parados.size();
    }
}
