package br.com.rbs.portal.atlas.loop;

import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeRelatorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeSolicitacao;
import br.com.rbs.portal.atlas.AtlasRepositorio.Parado;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.AcaoDoCartao;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.RespostaDoCartao;
import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

/**
 * O descarte do relatório. [ATLAS] Dois caminhos, o mesmo miolo ({@link #descartar}): o relatório MONTANDO ou AGUARDANDO_RESPOSTA vira
 * DESCARTADO, o cartão pendente dele vira CANCELADA e a chamada de ferramenta que o cartão deixou aberta é fechada com
 * {@code cancel} na própria conversa, para o histórico não ficar com uma {@code tool_call} sem resposta. Essa resposta leva também
 * {@code "relatorioDescartado": true}: é o que o modelo lê para não tratá-la como um cancelamento comum e dizer que o relatório
 * segue aberto (a nota do prompt do turno, no {@link LoopDoAgente}, cobre o relatório descartado que não tinha cartão).
 * <ul>
 *   <li><b>O que ficou parado</b> ({@link #varrer}): sem nenhuma mensagem nova há mais de {@value #DIAS_PARADO} dias. Conferido
 *       quando a pessoa lista ou abre as conversas (não há tarefa agendada).</li>
 *   <li><b>A pessoa descartou pela tela</b> (rota 65, {@link #descartarDaTela}): um relatório só, por id, com o histórico
 *       {@code ALTERACAO_STATUS} em nome de quem descartou. Quem chama já pegou o turno da conversa (409 se havia outro).</li>
 * </ul>
 * Pronto não se descarta (o gatilho do banco recusaria, e a tela recebe 409). Descartado de novo não muda nada.
 */
@Component
public class DescarteDeRelatorios {

    public static final int DIAS_PARADO = 7;
    public static final String JA_ESTA_PRONTO = "Este relatório já está pronto e não pode ser descartado.";

    private final AtlasRepositorio repositorio;
    private final HistoricoDaConversa historico;
    private final HistoricoService trilha;

    public DescarteDeRelatorios(AtlasRepositorio repositorio, HistoricoDaConversa historico, HistoricoService trilha) {
        this.repositorio = repositorio;
        this.historico = historico;
        this.trilha = trilha;
    }

    /** Descarta os relatórios parados destas conversas. Devolve quantos descartou. Uma transação curta. */
    @Transactional
    public int varrer(List<Long> conversaIds) {
        List<Parado> parados = repositorio.relatoriosParados(conversaIds, DIAS_PARADO);
        for (Parado parado : parados) {
            descartar(parado.relatorioId(), parado.conversaId());
        }
        return parados.size();
    }

    /**
     * Rota 65: descarta o relatório que a pessoa escolheu. Trava a linha, confere o estado de novo (entre a leitura de quem chama e
     * aqui o relatório pode ter ficado pronto) e descarta. Devolve verdadeiro se descartou, e falso se já estava descartado (nada
     * muda e nenhuma linha vai ao histórico). Pronto dá 409. [HISTORICO] {@code ALTERACAO_STATUS} com o estado de antes, na mesma
     * transação. Uma transação curta.
     */
    @Transactional
    public boolean descartarDaTela(long relatorioId, UsuarioAutenticado usuario, HttpServletRequest requisicao) {
        LinhaDeRelatorio relatorio = repositorio.travarRelatorio(relatorioId)
                .orElseThrow(() -> new NaoEncontradoException(RelatorioAtlasService.NAO_ENCONTRADO));
        if ("DESCARTADO".equals(relatorio.status())) {
            return false;
        }
        if ("PRONTO".equals(relatorio.status())) {
            throw new ConflitoException(JA_ESTA_PRONTO);
        }
        descartar(relatorio.id(), relatorio.conversaId());
        trilha.registrar(AcaoHistorico.ALTERACAO_STATUS, usuario, relatorio.parceriaId(), "atlas_relatorio", relatorio.id(),
                relatorio.status(), "DESCARTADO", "Relatório \"" + titulo(relatorio) + "\" descartado pela tela", requisicao);
        return true;
    }

    /**
     * O miolo dos dois caminhos: fecha o cartão pendente (CANCELADA, e a chamada que ele deixou aberta recebe {@code cancel}) e muda o
     * relatório para DESCARTADO. Quem chama está numa transação e já tem a linha do relatório travada.
     */
    private void descartar(long relatorioId, long conversaId) {
        for (LinhaDeSolicitacao cartao : repositorio.solicitacoesDoRelatorio(relatorioId)) {
            if ("PENDENTE".equals(cartao.status())) {
                repositorio.responderSolicitacao(cartao.id(), "CANCELADA");
                String resultado = new RespostaDoCartao(cartao.id(), relatorioId, cartao.toolCallId(), AcaoDoCartao.CANCEL, List.of())
                        .resultadoDoDescarteJson();
                historico.resultado(conversaId, cartao.toolCallId(), resultado);
                repositorio.concluirChamada(conversaId, cartao.toolCallId(), "relatorio_solicitar_campos", resultado);
            }
        }
        repositorio.mudarStatus(relatorioId, "DESCARTADO");
    }

    /** O título do relatório, do modelo que ele guardou ao nascer (ou o nome do modelo, se faltar). */
    private static String titulo(LinhaDeRelatorio relatorio) {
        try {
            Object titulo = AtlasJson.ler(relatorio.especificacao(), new TypeReference<Map<String, Object>>() {
            }).get("titulo");
            return titulo instanceof String texto && !texto.isBlank() ? texto : relatorio.modelo();
        } catch (RuntimeException ilegivel) {
            return relatorio.modelo();
        }
    }
}
