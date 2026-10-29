package br.com.rbs.portal.atlas.loop;

import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeRelatorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeSolicitacao;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.ferramentas.RelatorioFerramentas;
import br.com.rbs.portal.atlas.ferramentas.RelatorioFerramentas.Execucao;
import br.com.rbs.portal.atlas.loop.HistoricoDaConversa.Chamada;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.ChamadaDeFerramenta;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.MensagemDeChat;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.PedidoAoLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm;
import br.com.rbs.portal.atlas.relatorio.ChecagemDeTexto;
import br.com.rbs.portal.atlas.relatorio.ChecagemDeTexto.Veredito;
import br.com.rbs.portal.atlas.relatorio.ModeloBase;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.AcaoDoCartao;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.dados.DadosDtos.ValorRequisicao;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;

/**
 * O loop do agente: a conversa da marca com o modelo de linguagem (plano, seções 6.1, 10 e 11). [ATLAS] A cada passo remonta o
 * histórico de {@code atlas_mensagem}, manda ao {@link ProvedorDeLlm} com o prompt de sistema e só as ferramentas do estado atual,
 * grava a resposta e executa os pedidos de ferramenta. Regras que o loop garante:
 * <ul>
 *   <li><b>Nenhuma transação aberta durante a chamada ao modelo.</b> Cada gravação e cada ferramenta rodam numa transação curta
 *       própria; o pool tem poucas conexões e uma presa à espera do modelo pararia o portal. O turno em si (um por vez em cada
 *       conversa, 409) é do {@code AtlasService}.</li>
 *   <li><b>As chamadas da rodada rodam UMA A UMA, na ordem</b>, e cada resposta é gravada. Ao chegar em
 *       {@code relatorio_solicitar_campos} o loop grava o cartão, deixa essa resposta PENDENTE e para o turno; as chamadas
 *       seguintes da mesma rodada (inclusive outra {@code relatorio_solicitar_campos}) respondem erro. Assim toda
 *       {@code tool_call} tem resposta antes da próxima mensagem, menos a do cartão, que a rota 46 fecha. Não se usa o
 *       {@code ToolCallingManager}: ele responderia todas as chamadas da rodada de uma vez.</li>
 *   <li><b>Até {@value #LIMITE_DE_CHAMADAS_POR_TURNO} chamadas de ferramenta por turno</b> (contador nosso). Erro de ferramenta
 *       volta ao modelo como mensagem de ferramenta, nunca como 500.</li>
 *   <li><b>O modelo não escreve número.</b> O texto da resposta passa pela {@link ChecagemDeTexto} (a mesma do parágrafo do
 *       relatório): com algarismo solto, o servidor pede uma nova redação uma vez; se falhar de novo, troca pela frase fixa
 *       {@link #FRASE_FIXA}. O histórico guarda o texto com os marcadores; quem exibe os resolve.</li>
 *   <li><b>Falha do modelo não quebra o histórico</b>: o turno termina com uma frase fixa gravada, e tudo o que já foi feito
 *       fica salvo.</li>
 *   <li>O id de cada pedido de ferramenta é nosso ({@code call_<uuid>}), único na conversa.</li>
 * </ul>
 */
@Component
public class LoopDoAgente {

    public static final int LIMITE_DE_CHAMADAS_POR_TURNO = 8;
    /**
     * O turno vale por 5 minutos (a trava de {@code turno_desde}). O loop não começa uma nova ida ao modelo depois de 3 minutos,
     * porque a última ainda pode levar até 2 (60 segundos de limite e uma nova tentativa) e o turno não pode vencer sob ele.
     */
    public static final Duration PRAZO_DO_TURNO = Duration.ofMinutes(3);

    /** A troca do texto do chat que levou número solto duas vezes (plano, seção 10.6). */
    public static final String FRASE_FIXA = "Não trago números fora do relatório. Posso montar o relatório de ativação.";
    public static final String FRASE_DO_LIMITE = "Parei por aqui: esta resposta pediu passos demais. Diga de novo o que você quer, "
            + "ou use o botão Montar relatório.";
    public static final String FRASE_DA_FALHA = "Não consegui falar com o modelo de linguagem agora. O que já foi feito está salvo; "
            + "tente de novo daqui a pouco ou use o botão Montar relatório.";
    public static final String FRASE_DO_PRAZO = "Esta resposta demorou mais do que o esperado e eu parei. O que já foi feito está "
            + "salvo; tente de novo ou use o botão Montar relatório.";
    public static final String FRASE_SEM_RESPOSTA = "Não consegui responder agora. Tente de novo ou use o botão Montar relatório.";
    public static final String ERRO_DO_LIMITE = "Limite de chamadas de ferramenta deste turno atingido. Não chame mais ferramentas.";

    private static final Logger log = LoggerFactory.getLogger(LoopDoAgente.class);
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ObjectProvider<ProvedorDeLlm> provedor;
    private final RelatorioFerramentas ferramentas;
    private final HistoricoDaConversa historico;
    private final AtlasRepositorio repositorio;
    private final PlanoBSemLlm planoB;
    private final ChecagemDeTexto checagem;
    private final ResolvedorDeReferencias resolvedor;
    private final ModeloBase modelos;
    private final TransactionTemplate transacao;
    private final Duration prazoDoTurno;
    private final String prompt;

    /** {@code prazoDoTurno} vem de {@code atlas.prazo-do-turno} (padrão {@link #PRAZO_DO_TURNO}); os testes o encurtam. */
    public LoopDoAgente(ObjectProvider<ProvedorDeLlm> provedor, RelatorioFerramentas ferramentas, HistoricoDaConversa historico,
                        AtlasRepositorio repositorio, PlanoBSemLlm planoB, ChecagemDeTexto checagem, ResolvedorDeReferencias resolvedor,
                        ModeloBase modelos, TransactionTemplate transacao,
                        @Value("${atlas.prazo-do-turno:PT3M}") Duration prazoDoTurno) {
        this.provedor = provedor;
        this.ferramentas = ferramentas;
        this.historico = historico;
        this.repositorio = repositorio;
        this.planoB = planoB;
        this.checagem = checagem;
        this.resolvedor = resolvedor;
        this.modelos = modelos;
        this.transacao = transacao;
        this.prazoDoTurno = prazoDoTurno;
        this.prompt = lerPrompt();
    }

    /** O estado de um turno: quantas ferramentas já rodaram, se a nova redação do chat já foi pedida e até quando vale. */
    private static final class Turno {
        final long prazo;
        int chamadas;
        boolean limiteAtingido;
        boolean pediuNovaRedacao;

        Turno(Duration prazoDoTurno) {
            this.prazo = System.nanoTime() + prazoDoTurno.toNanos();
        }

        boolean venceu() {
            return System.nanoTime() >= prazo;
        }
    }

    // ---------------------------------------------------------------------
    // As duas entradas
    // ---------------------------------------------------------------------

    /**
     * Rota 44 com texto: a pessoa falou. Com o cartão aberto, a mensagem nova primeiro o fecha com {@code {"acao":"cancel"}}, marca
     * a solicitação CANCELADA e só então entra (o histórico continua válido: toda tool_call tem resposta). Depois conduz o turno.
     * Quem chama já tem o turno da conversa (409) e a aba conferida.
     */
    public void conversar(AtlasContexto contexto, String texto) {
        for (LinhaDeSolicitacao cartao : repositorio.solicitacoesDaConversa(contexto.conversaId())) {
            if ("PENDENTE".equals(cartao.status())) {
                planoB.registrarResposta(contexto, cartao.id(), AcaoDoCartao.CANCEL, List.of());
            }
        }
        transacao.executeWithoutResult(estado -> historico.usuario(contexto.conversaId(), texto));
        conduzir(contexto);
    }

    /**
     * Rota 46 com modelo: a pessoa respondeu o cartão. Grava os valores e a resposta da ferramenta pendente, com o mesmo
     * {@code tool_call_id} ({@code {acao, campos:[{chave, ref, valor}]}}), e o loop retoma. Erro de valor sobe antes de qualquer
     * gravação (nada é gravado).
     */
    public void retomar(AtlasContexto contexto, long solicitacaoId, AcaoDoCartao acao, List<ValorRequisicao> valores) {
        planoB.registrarResposta(contexto, solicitacaoId, acao, valores);
        conduzir(contexto);
    }

    // ---------------------------------------------------------------------
    // O laço
    // ---------------------------------------------------------------------

    private void conduzir(AtlasContexto contexto) {
        ProvedorDeLlm llm = provedor.getObject();
        Turno turno = new Turno(prazoDoTurno);
        List<MensagemDeChat> transitorias = List.of();   // a nova redação pedida: vai ao modelo, não ao histórico
        while (true) {
            Optional<LinhaDeRelatorio> ativo = repositorio.relatorioAtivoDaConversa(contexto.conversaId());
            if (ativo.isPresent() && "AGUARDANDO_RESPOSTA".equals(ativo.get().status())) {
                return;   // cartão aberto: o turno para e espera a rota 46
            }
            if (turno.venceu()) {
                gravarTexto(contexto, FRASE_DO_PRAZO, null);
                return;
            }
            RespostaDoLlm resposta;
            try {
                resposta = llm.responder(pedido(contexto, ativo, transitorias));
            } catch (RuntimeException falha) {
                log.warn("Atlas: o modelo não respondeu na conversa {} ({})", contexto.conversaId(), falha.getClass().getSimpleName());
                gravarTexto(contexto, FRASE_DA_FALHA, null);
                return;
            }
            String texto = limpar(resposta.texto());
            String raciocinio = limpar(resposta.raciocinio());

            if (resposta.chamadas().isEmpty()) {
                if (texto == null) {
                    gravarTexto(contexto, FRASE_SEM_RESPOSTA, raciocinio);
                    return;
                }
                Veredito veredito = checarDoChat(contexto, texto);
                if (veredito.aprovado()) {
                    gravarTexto(contexto, texto, raciocinio);
                    return;
                }
                if (turno.pediuNovaRedacao) {
                    gravarTexto(contexto, FRASE_FIXA, null);
                    return;
                }
                turno.pediuNovaRedacao = true;
                transitorias = List.of(MensagemDeChat.assistente(texto, List.of(), null), MensagemDeChat.usuario(
                        "Sua resposta foi recusada pelo servidor: " + veredito.motivo() + " Escreva a resposta de novo, sem "
                                + "nenhum algarismo nem número por extenso, citando os dados só com marcadores {{ref}}."));
                continue;
            }

            transitorias = List.of();
            if (texto != null && !checarDoChat(contexto, texto).aprovado()) {
                texto = null;   // texto solto de uma rodada de ferramentas não passa com número: sai, e a ação segue
            }
            if (rodar(contexto, turno, texto, resposta.chamadas(), raciocinio)) {
                return;
            }
        }
    }

    /**
     * Grava a mensagem do assistente com os pedidos de ferramenta e executa cada um, na ordem. Devolve verdadeiro se o turno
     * acabou: o cartão abriu, o relatório ficou pronto ou o limite de chamadas estourou.
     */
    private boolean rodar(AtlasContexto contexto, Turno turno, String texto, List<ChamadaDeFerramenta> pedidos, String raciocinio) {
        // O id e os argumentos são os que o servidor guarda: id nosso e argumentos sempre um texto (sem argumento, "{}").
        List<ChamadaDeFerramenta> chamadas = pedidos.stream().map(c -> {
            String nome = limpar(c.nome());
            String argumentos = limpar(c.argumentos());
            return new ChamadaDeFerramenta(HistoricoDaConversa.novoIdDoModelo(), nome == null ? "ferramenta_sem_nome" : nome,
                    argumentos == null ? "{}" : argumentos);
        }).toList();
        long mensagemId = transacao.execute(estado -> historico.assistente(contexto.conversaId(), texto,
                chamadas.stream().map(c -> new Chamada(c.id(), c.nome(), c.argumentos())).toList(), raciocinio));

        boolean pausa = false;
        boolean pronto = false;
        for (ChamadaDeFerramenta chamada : chamadas) {
            if (pausa) {
                responderComErro(contexto, mensagemId, chamada, RelatorioFerramentas.AGUARDANDO_O_CARTAO);
            } else if (pronto) {
                responderComErro(contexto, mensagemId, chamada, "O relatório já está pronto. Não chame mais ferramentas.");
            } else if (++turno.chamadas > LIMITE_DE_CHAMADAS_POR_TURNO) {
                turno.limiteAtingido = true;
                responderComErro(contexto, mensagemId, chamada, ERRO_DO_LIMITE);
            } else {
                Execucao execucao = executar(contexto, mensagemId, chamada);
                pausa = execucao.pausa();
                pronto = execucao.pronto();
            }
        }
        if (pausa || pronto) {
            return true;
        }
        if (turno.limiteAtingido) {
            gravarTexto(contexto, FRASE_DO_LIMITE, null);
            return true;
        }
        return false;
    }

    /**
     * Executa a ferramenta e grava a resposta (ou, no cartão, só a trilha) na MESMA transação: se a ferramenta falha, nada dela
     * fica, e a falha é gravada como a resposta da ferramenta, que volta ao modelo.
     */
    private Execucao executar(AtlasContexto contexto, long mensagemId, ChamadaDeFerramenta chamada) {
        long inicio = System.nanoTime();
        try {
            return transacao.execute(estado -> {
                Execucao execucao = ferramentas.executar(contexto, chamada.nome(), chamada.argumentos(), chamada.id());
                int ms = (int) ((System.nanoTime() - inicio) / 1_000_000);
                if (execucao.pausa()) {
                    // A resposta do cartão só existe quando a pessoa a mandar (rota 46): aqui fica só a trilha, sem resultado.
                    repositorio.registrarChamada(mensagemId, chamada.nome(), argumentosParaATrilha(chamada.argumentos()), null, null, ms);
                } else {
                    historico.resultado(contexto.conversaId(), chamada.id(), execucao.resultadoJson());
                    repositorio.registrarChamada(mensagemId, chamada.nome(), argumentosParaATrilha(chamada.argumentos()),
                            execucao.resultadoJson(), null, ms);
                }
                return execucao;
            });
        } catch (RuntimeException erro) {
            String json = RelatorioFerramentas.erroComoJson(erro);
            transacao.executeWithoutResult(estado -> gravarErro(contexto, mensagemId, chamada, json, erro.getMessage(),
                    (int) ((System.nanoTime() - inicio) / 1_000_000)));
            return new Execucao(json, false, false);
        }
    }

    private void responderComErro(AtlasContexto contexto, long mensagemId, ChamadaDeFerramenta chamada, String frase) {
        transacao.executeWithoutResult(estado -> gravarErro(contexto, mensagemId, chamada, RelatorioFerramentas.erroComoJson(frase),
                frase, 0));
    }

    private void gravarErro(AtlasContexto contexto, long mensagemId, ChamadaDeFerramenta chamada, String json, String motivo, int ms) {
        historico.resultado(contexto.conversaId(), chamada.id(), json);
        repositorio.registrarChamada(mensagemId, chamada.nome(), argumentosParaATrilha(chamada.argumentos()), null,
                motivo == null ? "erro" : motivo, ms);
    }

    // ---------------------------------------------------------------------
    // O que vai ao modelo
    // ---------------------------------------------------------------------

    private PedidoAoLlm pedido(AtlasContexto contexto, Optional<LinhaDeRelatorio> ativo, List<MensagemDeChat> transitorias) {
        List<MensagemDeChat> mensagens = new ArrayList<>();
        mensagens.add(MensagemDeChat.sistema(promptDe(contexto)));
        mensagens.addAll(historico.paraOProvedor(contexto.conversaId()));
        mensagens.addAll(transitorias);
        boolean podePedirCartao = ativo.isEmpty()
                || repositorio.solicitacoesDoRelatorio(ativo.get().id()).size() < RelatorioAtlasService.LIMITE_DE_CARTOES;
        return new PedidoAoLlm(String.valueOf(contexto.conversaId()), mensagens, ferramentas.oferecidas(ativo, podePedirCartao));
    }

    /** O prompt de sistema com a data de hoje e o nome da marca; id de parceria, conversa ou usuário nunca entra. */
    private String promptDe(AtlasContexto contexto) {
        return prompt.replace("@DATA@", DIA.format(Datas.hoje())).replace("@MARCA@", repositorio.nomeDaMarca(contexto.parceriaId()));
    }

    private static String lerPrompt() {
        try {
            return new String(new ClassPathResource("atlas/prompts/relatorio.txt").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException erro) {
            throw new UncheckedIOException("Não consegui ler o prompt do Atlas (resources/atlas/prompts/relatorio.txt).", erro);
        }
    }

    // ---------------------------------------------------------------------
    // O texto do chat passa pela mesma regra do relatório
    // ---------------------------------------------------------------------

    private Veredito checarDoChat(AtlasContexto contexto, String texto) {
        var resolucao = resolvedor.abrir(contexto.usuario(), contexto.parceriaId(), modelos.obter(ModeloBase.ATIVACAO_EVENTO).orElseThrow());
        return checagem.chat(resolucao, texto);
    }

    private void gravarTexto(AtlasContexto contexto, String texto, String raciocinio) {
        transacao.executeWithoutResult(estado -> historico.assistente(contexto.conversaId(), texto, List.of(), raciocinio));
    }

    // ---------------------------------------------------------------------
    // Apoio
    // ---------------------------------------------------------------------

    /** O texto sem o caractere nulo (que o PostgreSQL não guarda), ou nulo se ficou em branco. */
    private static String limpar(String texto) {
        if (texto == null) {
            return null;
        }
        String limpo = texto.replace("\u0000", "");
        return limpo.isBlank() ? null : limpo;
    }

    /** Os argumentos como JSON para a trilha: o que o modelo mandou, se for JSON; senão o texto dele como string JSON. */
    private static String argumentosParaATrilha(String argumentos) {
        if (argumentos == null || argumentos.isBlank()) {
            return "{}";
        }
        try {
            AtlasJson.ler(argumentos, Object.class);
            return argumentos;
        } catch (JacksonException naoEJson) {
            return AtlasJson.escrever(argumentos);
        }
    }
}
