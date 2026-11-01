package br.com.rbs.portal.atlas.loop;

import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeRelatorio;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.loop.HistoricoDaConversa.Chamada;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.AcaoDoCartao;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.CartaoAberto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.Lacuna;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.RelatorioFinalizado;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.RespostaDoCartao;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.ResumoDoRelatorio;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.dados.DadosDtos.ValorRequisicao;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * O plano B do Atlas: o relatório montado SEM modelo de linguagem. [ATLAS] O botão "Montar relatório" dispara a sequência
 * botão &rarr; base &rarr; cartão de perguntas &rarr; frases-modelo &rarr; relatório pronto, com as mesmas operações do
 * {@link RelatorioAtlasService} que o modelo vai chamar como ferramentas: não há um segundo caminho para manter.
 * <ol>
 *   <li>{@link #montar}: se a conversa já tem um relatório em montagem, continua dele; senão executa {@code relatorio_iniciar}.
 *       Com lacuna obrigatória sem valor, abre o cartão ({@code relatorio_solicitar_campos}) e o turno termina esperando a
 *       resposta; sem lacuna, vai direto para o passo 3.</li>
 *   <li>{@link #responder}: grava a resposta do cartão (rota 46). Com {@code accept} ou {@code decline}, vai para o passo 3. Com
 *       {@code cancel}, o relatório fica montando e o botão continua dele.</li>
 *   <li>Preenche cada bloco redigível com a frase-modelo e executa {@code relatorio_finalizar}.</li>
 * </ol>
 * O histórico sai no formato de chat completions, como se o modelo tivesse chamado as ferramentas (ids {@code botao_<uuid>}):
 * veja {@link HistoricoDaConversa}. Cada passo é uma transação só, dentro do pedido: tudo ou nada.
 */
@Component
public class PlanoBSemLlm {

    /** A mensagem {@code user} que o botão grava no histórico (o modelo lê "(botão)" e sabe que não foi uma frase da pessoa). */
    public static final String TEXTO_DO_BOTAO = "Montar o relatório da ativação (botão)";
    /** Como o botão aparece no item {@code usuario} da conversa. */
    public static final String TEXTO_DO_BOTAO_NA_TELA = "Montar o relatório da ativação";
    public static final String TEXTO_NOVO = "Montei a base do relatório de ativação com os números do portal. Faltam números que só vocês sabem.";
    public static final String TEXTO_CONTINUACAO = "Continuo o relatório de ativação que você já começou. Ainda faltam números que só vocês sabem.";
    public static final String MENSAGEM_DO_CARTAO = "Para fechar a seção de público, preciso destes números.";
    public static final String TEXTO_PRONTO = "Pronto: o relatório de ativação está montado. A origem de cada número está em Fontes e cálculos.";
    public static final String AVISO_CARTAO_FECHADO = "Cartão fechado. Use Montar relatório para continuar.";
    /** O mesmo aviso quando o modelo de linguagem está ligado: a pessoa pode continuar pelo chat. */
    public static final String AVISO_CARTAO_FECHADO_COM_MODELO = "Cartão fechado. Quando tiver os números, peça para continuar.";
    public static final String CARTAO_ABERTO = "Este relatório está esperando a resposta do cartão. Responda ou feche o cartão antes de montar de novo.";
    static final String OBJETIVO = "Montar o relatório de ativação no evento, pelo botão";

    private final RelatorioAtlasService relatorios;
    private final HistoricoDaConversa historico;
    private final AtlasRepositorio repositorio;

    public PlanoBSemLlm(RelatorioAtlasService relatorios, HistoricoDaConversa historico, AtlasRepositorio repositorio) {
        this.relatorios = relatorios;
        this.historico = historico;
        this.repositorio = repositorio;
    }

    /**
     * O botão. Grava a mensagem da pessoa e roda o plano B até o cartão (turno que espera) ou até o relatório pronto. Com
     * cartão aberto na conversa: 409 ({@link #CARTAO_ABERTO}).
     */
    @Transactional
    public void montar(AtlasContexto contexto, String modelo) {
        var ativo = repositorio.relatorioAtivoDaConversa(contexto.conversaId());
        if (ativo.isPresent() && "AGUARDANDO_RESPOSTA".equals(ativo.get().status())) {
            throw new ConflitoException(CARTAO_ABERTO);   // antes de gravar qualquer coisa
        }
        historico.usuario(contexto.conversaId(), TEXTO_DO_BOTAO);
        long relatorioId;
        String introducao;
        if (ativo.isPresent()) {
            LinhaDeRelatorio relatorio = ativo.get();
            relatorioId = relatorio.id();
            introducao = TEXTO_CONTINUACAO;
        } else {
            relatorioId = executarIniciar(contexto, modelo);
            introducao = TEXTO_NOVO;
        }
        List<Lacuna> lacunas = relatorios.lacunas(contexto, relatorioId);
        if (lacunas.stream().anyMatch(Lacuna::obrigatoria)) {
            abrirCartao(contexto, relatorioId, introducao, lacunas);
        } else {
            concluir(contexto, relatorioId);
        }
    }

    /**
     * A resposta do cartão (rota 46). 409 se ele já foi respondido. Com {@code accept} ou {@code decline}, termina o relatório
     * no mesmo passo; com {@code cancel}, só fecha o cartão (o item {@code aviso} sai do histórico).
     */
    @Transactional
    public void responder(AtlasContexto contexto, long solicitacaoId, AcaoDoCartao acao, List<ValorRequisicao> valores) {
        RespostaDoCartao resposta = registrarResposta(contexto, solicitacaoId, acao, valores);
        if (acao != AcaoDoCartao.CANCEL) {
            concluir(contexto, resposta.relatorioId());
        }
    }

    /**
     * Só a metade do {@link #responder} que é de toda resposta de cartão, com ou sem modelo de linguagem: grava os valores (como
     * dado informado com origem ATLAS), fecha o cartão e põe no histórico a resposta da ferramenta pendente, com o mesmo
     * {@code tool_call_id}: {@code {acao, campos:[{chave, ref, valor}]}}. Quem continua é o plano B (frases-modelo e finalizar)
     * ou o loop do modelo. Uma transação só: com erro (cartão já respondido, valor inválido), nada é gravado.
     */
    @Transactional
    public RespostaDoCartao registrarResposta(AtlasContexto contexto, long solicitacaoId, AcaoDoCartao acao,
                                              List<ValorRequisicao> valores) {
        RespostaDoCartao resposta = relatorios.responder(contexto, solicitacaoId, acao, valores);
        historico.resultado(contexto.conversaId(), resposta.toolCallId(), resposta.resultadoJson());
        repositorio.concluirChamada(contexto.conversaId(), resposta.toolCallId(), "relatorio_solicitar_campos", resposta.resultadoJson());
        return resposta;
    }

    // ---------------------------------------------------------------------
    // Os passos, escritos no histórico como chamadas de ferramenta
    // ---------------------------------------------------------------------

    private long executarIniciar(AtlasContexto contexto, String modelo) {
        String id = HistoricoDaConversa.novoIdDeChamada();
        String argumentos = json("modelo", modelo, "objetivo", OBJETIVO);
        long mensagem = historico.assistente(contexto.conversaId(), null, List.of(new Chamada(id, "relatorio_iniciar", argumentos)));
        ResumoDoRelatorio resumo = medir(mensagem, "relatorio_iniciar", argumentos,
                () -> relatorios.iniciar(contexto, modelo, OBJETIVO));
        historico.resultado(contexto.conversaId(), id, AtlasJson.escrever(resumo));
        return resumo.relatorioId();
    }

    private void abrirCartao(AtlasContexto contexto, long relatorioId, String introducao, List<Lacuna> lacunas) {
        String id = HistoricoDaConversa.novoIdDeChamada();
        List<String> chaves = lacunas.stream().map(Lacuna::chave).limit(RelatorioAtlasService.MAXIMO_DE_CAMPOS_NO_CARTAO).toList();
        String argumentos = json("mensagem", MENSAGEM_DO_CARTAO, "campos", chaves);
        CartaoAberto cartao = relatorios.solicitarCamposDoBotao(contexto, relatorioId, MENSAGEM_DO_CARTAO, chaves, id);
        long mensagem = historico.assistente(contexto.conversaId(), introducao,
                List.of(new Chamada(cartao.toolCallId(), "relatorio_solicitar_campos", argumentos)));
        // A ferramenta fica sem resultado até a pessoa responder o cartão (rota 46).
        repositorio.registrarChamada(mensagem, "relatorio_solicitar_campos", argumentos, null, null, null);
    }

    /** Preenche as frases-modelo e finaliza: o fim do plano B. O texto "Pronto..." vai junto do pedido de finalização. */
    private void concluir(AtlasContexto contexto, long relatorioId) {
        relatorios.preencherFrasesModelo(contexto, relatorioId);
        String id = HistoricoDaConversa.novoIdDeChamada();
        long mensagem = historico.assistente(contexto.conversaId(), TEXTO_PRONTO,
                List.of(new Chamada(id, "relatorio_finalizar", "{}")));
        RelatorioFinalizado pronto = medir(mensagem, "relatorio_finalizar", "{}", () -> relatorios.finalizar(contexto, relatorioId));
        historico.resultado(contexto.conversaId(), id, pronto.resultadoJson());
    }

    /** Roda a ferramenta, registra argumentos, resultado e duração em {@code atlas_tool_call} e devolve o resultado. */
    private <T> T medir(long mensagemId, String ferramenta, String argumentos, Supplier<T> acao) {
        long inicio = System.nanoTime();
        T resultado = acao.get();
        int ms = (int) ((System.nanoTime() - inicio) / 1_000_000);
        repositorio.registrarChamada(mensagemId, ferramenta, argumentos, AtlasJson.escrever(resultado), null, ms);
        return resultado;
    }

    private static String json(Object... paresNomeValor) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        for (int i = 0; i < paresNomeValor.length; i += 2) {
            corpo.put((String) paresNomeValor[i], paresNomeValor[i + 1]);
        }
        return AtlasJson.escrever(corpo);
    }
}
