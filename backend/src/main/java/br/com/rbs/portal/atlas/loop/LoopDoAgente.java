package br.com.rbs.portal.atlas.loop;

import br.com.rbs.portal.atlas.AtlasDtos.DadosDaFerramenta;
import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeRelatorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeSolicitacao;
import br.com.rbs.portal.atlas.ModoDoAtlas;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.diagnostico.EtapaDoTraco;
import br.com.rbs.portal.atlas.diagnostico.ResultadoDaEtapa;
import br.com.rbs.portal.atlas.diagnostico.Traco;
import br.com.rbs.portal.atlas.diagnostico.Traco.Etapa;
import br.com.rbs.portal.atlas.checagem.ChecagemDeFontes;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno.Fonte;
import br.com.rbs.portal.atlas.checagem.Frases;
import br.com.rbs.portal.atlas.ferramentas.BuscarDocumentos;
import br.com.rbs.portal.atlas.ferramentas.Execucao;
import br.com.rbs.portal.atlas.ferramentas.RegistroDeFerramentas;
import br.com.rbs.portal.atlas.ferramentas.RegistroDeFerramentas.Preparo;
import br.com.rbs.portal.atlas.ferramentas.RelatorioFerramentas;
import br.com.rbs.portal.atlas.loop.HistoricoDaConversa.Chamada;
import br.com.rbs.portal.atlas.loop.ResumoDoRaciocinio.ResumosDoTurno;
import br.com.rbs.portal.atlas.provedor.ProvedorAtual;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.ChamadaDeFerramenta;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.DefinicaoDeFerramenta;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.MensagemDeChat;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.OuvinteDoFluxo;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.PedidoAoLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm;
import br.com.rbs.portal.atlas.relatorio.ChecagemDeTexto.Veredito;
import br.com.rbs.portal.atlas.relatorio.ModeloBase;
import br.com.rbs.portal.atlas.relatorio.Referencias;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.AcaoDoCartao;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Resolucao;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.dados.DadosDtos.ValorRequisicao;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *   <li><b>O modelo não escreve número sem origem nem termo interno.</b> O texto da resposta passa pela {@link ChecagemDeFontes}
 *       (M4; no relatório e no resumo do raciocínio vale a regra antiga da {@link ChecagemDeTexto}, sem nenhum algarismo): com
 *       número ou data que nenhuma fonte citada na mesma frase traz, citação que não existe neste turno, chave crua
 *       ({@code dado:...}), nome de ferramenta, marcador que não resolve ou sem valor, o servidor pede uma nova redação uma
 *       vez; se falhar de novo, troca pelo texto fixo: {@link #TEXTO_NAO_ENCONTREI} num turno que buscou nos documentos,
 *       {@link #FRASE_FIXA} num que não buscou. O histórico guarda o texto com os marcadores e as citações; quem exibe os resolve.
 *       A frase {@code "Não encontrei isso nos documentos do portal."} escrita pelo modelo ganha a indicação do atendimento, e a
 *       resposta de um turno que buscou, nada achou e não cita nada vira o mesmo texto fixo.</li>
 *   <li><b>Falha do modelo não quebra o histórico</b>: o turno termina com uma frase fixa gravada, e tudo o que já foi feito
 *       fica salvo.</li>
 *   <li>O id de cada pedido de ferramenta é nosso ({@code call_<uuid>}), único na conversa.</li>
 *   <li><b>O raciocínio cru nunca sai do servidor.</b> O {@code reasoning_content} de cada resposta é gravado com a mensagem do
 *       assistente e volta ao modelo nas idas seguintes (o DeepSeek o exige com ferramentas), mas não vai à tela, nem no fluxo
 *       nem na conversa. Para a tela, o loop só avisa {@code pensando} (uma vez por ida, no primeiro pedaço de raciocínio) e,
 *       para cada mensagem gravada com raciocínio, pede ao {@link ResumoDoRaciocinio} um resumo, numa chamada à parte que não
 *       atrasa a ida seguinte. No fim do turno (qualquer saída normal de {@link #conduzir}) espera os resumos pendentes por até
 *       {@code atlas.prazo-do-resumo}; o que não ficou pronto sai sem resumo.</li>
 *   <li><b>O diagnóstico</b> (plano do Atlas completo, M2): cada ida ao modelo, ferramenta, checagem do texto e resumo do raciocínio,
 *       e o fim do turno, viram uma etapa do {@link Traco} do contexto. O traço vive na memória e só vai ao banco no fim do turno
 *       (quem chama o {@code conduzir} o encerra): o loop nunca grava traço durante a chamada ao modelo. Nada do raciocínio cru,
 *       da chave nem do prompt de sistema entra nele.</li>
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
    public static final String FRASE_FIXA = "Não consegui responder isso agora. Pergunte de outro jeito ou monte o relatório da ativação.";
    public static final String FRASE_DO_LIMITE = "Parei por aqui: esta resposta pediu passos demais. Diga de novo o que você quer, "
            + "ou use o botão Montar relatório.";
    public static final String FRASE_DA_FALHA = "Não consegui falar com o modelo de linguagem agora. O que já foi feito está salvo; "
            + "tente de novo daqui a pouco ou use o botão Montar relatório.";
    public static final String FRASE_DO_PRAZO = "Esta resposta demorou mais do que o esperado e eu parei. O que já foi feito está "
            + "salvo; tente de novo ou use o botão Montar relatório.";
    public static final String FRASE_SEM_RESPOSTA = "Não consegui responder agora. Tente de novo ou use o botão Montar relatório.";
    /** As mesmas frases com o relatório desligado ({@code atlas.relatorio.ligado=false}): nenhuma fala do botão. */
    public static final String FRASE_FIXA_NO_CHAT = "Não consegui responder isso agora. Pergunte de outro jeito.";
    public static final String FRASE_DO_LIMITE_NO_CHAT = "Parei por aqui: esta resposta pediu passos demais. Diga de novo o que você quer.";
    public static final String FRASE_DA_FALHA_NO_CHAT = "Não consegui falar com o modelo de linguagem agora. Tente de novo daqui a pouco.";
    public static final String FRASE_DO_PRAZO_NO_CHAT = "Esta resposta demorou mais do que o esperado e eu parei. Tente de novo.";
    public static final String FRASE_SEM_RESPOSTA_NO_CHAT = "Não consegui responder agora. Tente de novo.";
    /**
     * A frase que o prompt manda o modelo escrever, exatamente, quando os trechos não respondem, e o texto fixo do L2: o servidor
     * acrescenta a indicação do atendimento da RBS. O avaliador de conversas reconhece a primeira parte.
     */
    public static final String FRASE_NAO_ENCONTREI = "Não encontrei isso nos documentos do portal.";
    public static final String INDICACAO_DO_ATENDIMENTO = "Para essa dúvida, fale com o atendimento da RBS.";
    public static final String TEXTO_NAO_ENCONTREI = FRASE_NAO_ENCONTREI + " " + INDICACAO_DO_ATENDIMENTO;
    /**
     * As frases que o próprio loop grava como texto do assistente: no item {@code texto}, saem com {@code porModelo} falso. Entram
     * as dos dois modos: a conversa guarda a frase que valia quando foi escrita.
     */
    public static final Set<String> FRASES_DO_SERVIDOR = Set.of(FRASE_FIXA, FRASE_DO_LIMITE, FRASE_DA_FALHA, FRASE_DO_PRAZO,
            FRASE_SEM_RESPOSTA, FRASE_FIXA_NO_CHAT, FRASE_DO_LIMITE_NO_CHAT, FRASE_DA_FALHA_NO_CHAT, FRASE_DO_PRAZO_NO_CHAT,
            FRASE_SEM_RESPOSTA_NO_CHAT, TEXTO_NAO_ENCONTREI);
    public static final String ERRO_DO_LIMITE = "Limite de chamadas de ferramenta deste turno atingido. Não chame mais ferramentas.";
    /** A frase da etapa {@code etapa} da resposta em fluxo, por ferramenta: a marca lê o que está acontecendo, nunca o nome dela. */
    public static final Map<String, String> ETAPAS = Map.of(
            RelatorioFerramentas.CONSULTAR, "Consultando os números da parceria…",
            BuscarDocumentos.NOME, "Buscando nos documentos do portal…",
            RelatorioFerramentas.INICIAR, "Abrindo o relatório…",
            RelatorioFerramentas.SOLICITAR_CAMPOS, "Preparando as perguntas…",
            RelatorioFerramentas.EDITAR, "Redigindo o documento…",
            RelatorioFerramentas.FINALIZAR, "Finalizando o documento…");

    private static final Logger log = LoggerFactory.getLogger(LoopDoAgente.class);
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ProvedorAtual provedor;
    private final RegistroDeFerramentas ferramentas;
    private final HistoricoDaConversa historico;
    private final AtlasRepositorio repositorio;
    private final PlanoBSemLlm planoB;
    private final ChecagemDeFontes checagem;
    private final ResolvedorDeReferencias resolvedor;
    private final ModeloBase modelos;
    private final TransactionTemplate transacao;
    private final ResumoDoRaciocinio resumos;
    private final ModoDoAtlas modo;
    private final Duration prazoDoTurno;
    private final String prompt;

    /** {@code prazoDoTurno} vem de {@code atlas.prazo-do-turno} (padrão {@link #PRAZO_DO_TURNO}); os testes o encurtam. */
    public LoopDoAgente(ProvedorAtual provedor, RegistroDeFerramentas ferramentas, HistoricoDaConversa historico,
                        AtlasRepositorio repositorio, PlanoBSemLlm planoB, ChecagemDeFontes checagem, ResolvedorDeReferencias resolvedor,
                        ModeloBase modelos, TransactionTemplate transacao, ResumoDoRaciocinio resumos, ModoDoAtlas modo,
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
        this.resumos = resumos;
        this.modo = modo;
        this.prazoDoTurno = prazoDoTurno;
        this.prompt = lerPrompt(modo.relatorioLigado() ? "relatorio" : "chat");
    }

    /**
     * O estado de um turno: quantas ferramentas já rodaram, se a nova redação do chat já foi pedida, até quando vale e os resumos
     * do raciocínio que ele pediu (nulo até haver um provedor).
     */
    private static final class Turno {
        final long prazo;
        final Traco traco;
        final FontesDoTurno fontes;
        /** A última ordem do histórico quando o turno começou: o que tem ordem até aqui é de turno anterior. */
        final int ordemInicial;
        int chamadas;
        int idas;
        boolean limiteAtingido;
        boolean pediuNovaRedacao;
        ResumosDoTurno resumos;
        // O desfecho, para a etapa SAIDA do traço: o que terminou o turno e a última mensagem que ele gravou.
        String tipoDaSaida = "CARTAO_ABERTO";
        ResultadoDaEtapa resultadoDaSaida = ResultadoDaEtapa.OK;
        String motivoDaSaida;
        Long mensagemFinalId;
        int caracteresDaSaida;

        Turno(Duration prazoDoTurno, Traco traco, FontesDoTurno fontes, int ordemInicial) {
            this.prazo = System.nanoTime() + prazoDoTurno.toNanos();
            this.traco = traco;
            this.fontes = fontes;
            this.ordemInicial = ordemInicial;
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
        prepararConversa(contexto, texto);
        conduzir(contexto, OuvinteDoTurno.NENHUM);
    }

    /**
     * A parte da rota 44 com texto que vem antes do modelo: fecha o cartão aberto e grava a mensagem da pessoa. Na resposta em
     * fluxo roda antes de o fluxo abrir; depois vem {@link #conduzir}.
     */
    public void prepararConversa(AtlasContexto contexto, String texto) {
        for (LinhaDeSolicitacao cartao : repositorio.solicitacoesDaConversa(contexto.conversaId())) {
            if ("PENDENTE".equals(cartao.status())) {
                planoB.registrarResposta(contexto, cartao.id(), AcaoDoCartao.CANCEL, List.of());
            }
        }
        transacao.executeWithoutResult(estado -> historico.usuario(contexto.conversaId(), texto));
    }

    /**
     * Rota 46 com modelo: a pessoa respondeu o cartão. Grava os valores e a resposta da ferramenta pendente, com o mesmo
     * {@code tool_call_id} ({@code {acao, campos:[{chave, ref, valor}]}}), e o loop retoma. Erro de valor sobe antes de qualquer
     * gravação (nada é gravado).
     */
    public void retomar(AtlasContexto contexto, long solicitacaoId, AcaoDoCartao acao, List<ValorRequisicao> valores) {
        prepararRetomada(contexto, solicitacaoId, acao, valores);
        conduzir(contexto, OuvinteDoTurno.NENHUM);
    }

    /**
     * A parte da rota 46 que vem antes do modelo: grava os valores e a resposta da ferramenta pendente (o 422 dos valores sobe
     * daqui). Na resposta em fluxo roda antes de o fluxo abrir; depois vem {@link #conduzir}.
     */
    public void prepararRetomada(AtlasContexto contexto, long solicitacaoId, AcaoDoCartao acao, List<ValorRequisicao> valores) {
        planoB.registrarResposta(contexto, solicitacaoId, acao, valores);
    }

    // ---------------------------------------------------------------------
    // O laço
    // ---------------------------------------------------------------------

    /**
     * O turno com o modelo, depois de {@link #prepararConversa} ou {@link #prepararRetomada}. Com um {@code ouvinte} em fluxo,
     * cada ida ao modelo é transmitida (o raciocínio cru, o texto filtrado, a frase de cada ferramenta); com
     * {@link OuvinteDoTurno#NENHUM}, nada é transmitido e a resposta sai inteira no fim.
     */
    public void conduzir(AtlasContexto contexto, OuvinteDoTurno ouvinte) {
        Turno turno = new Turno(prazoDoTurno, contexto.traco(), new FontesDoTurno(
                repositorio.ultimaMensagemDaPessoa(contexto.conversaId()).orElse(""),
                repositorio.escopoDaParceria(contexto.parceriaId()).edicao(), Datas.hoje()),
                repositorio.ultimaOrdem(contexto.conversaId()));
        try {
            // O provedor é pego UMA vez: se a chave do debug mudar no meio, este turno termina com o que começou.
            ProvedorDeLlm llm = provedor.provedor().orElse(null);
            if (llm == null) {
                // A chave do debug foi apagada entre a conferência do service e aqui: termina como uma falha do modelo.
                gravarFrase(contexto, turno, frase(FRASE_DA_FALHA, FRASE_DA_FALHA_NO_CHAT), null, ouvinte, "SEM_MODELO",
                        ResultadoDaEtapa.ERRO);
                registrarSaida(turno, ouvinte, 0, 0);
                return;
            }
            turno.resumos = resumos.abrir(contexto, llm, ouvinte);
            laco(contexto, ouvinte, llm, turno);
        } catch (RuntimeException | Error erro) {
            // Em erro o laço sobe a exceção e não espera os resumos: os que já saíram terminam sozinhos. O traço guarda a falha e
            // quem chamou o encerra (a etapa SAIDA sai como ERRO).
            turno.traco.erro(erro);
            throw erro;
        }
        // Qualquer saída normal do laço passa por aqui: antes do fim (ou da resposta JSON), os resumos pendentes têm até o prazo.
        long inicioDaEspera = System.nanoTime();
        int pendentes = turno.resumos.esperar();
        registrarSaida(turno, ouvinte, (System.nanoTime() - inicioDaEspera) / 1_000_000, pendentes);
    }

    /**
     * A etapa SAIDA do traço: o desfecho do turno (o texto do modelo, uma frase fixa, o cartão aberto, o relatório pronto), quanto o
     * turno esperou os resumos do raciocínio e o que o fluxo transmitiu. Fica por último entre as etapas sequenciais.
     */
    private void registrarSaida(Turno turno, OuvinteDoTurno ouvinte, long esperaDosResumosMs, int resumosPendentes) {
        Etapa saida = turno.traco.abrir(EtapaDoTraco.SAIDA).mensagem(turno.mensagemFinalId)
                .dado("tipo", turno.tipoDaSaida).dado("motivo", turno.motivoDaSaida)
                .dado("idasAoModelo", turno.idas).dado("chamadasDeFerramenta", turno.chamadas)
                .dado("esperaDosResumosMs", esperaDosResumosMs).dado("resumosPendentes", resumosPendentes);
        if (turno.caracteresDaSaida > 0) {
            saida.dado("caracteres", turno.caracteresDaSaida);
        }
        if (turno.traco.ativo() && ouvinte.emFluxo()) {
            saida.dado("fluxo", ouvinte.medidas());
        }
        saida.fim(turno.resultadoDaSaida);
    }

    private void laco(AtlasContexto contexto, OuvinteDoTurno ouvinte, ProvedorDeLlm llm, Turno turno) {
        List<MensagemDeChat> transitorias = List.of();   // a nova redação pedida: vai ao modelo, não ao histórico
        while (true) {
            // Só chat: o relatório de uma conversa antiga fica onde está, e o modelo recebe só a consulta.
            Optional<LinhaDeRelatorio> ativo = modo.relatorioLigado() ? repositorio.relatorioAtivoDaConversa(contexto.conversaId())
                    : Optional.empty();
            if (ativo.isPresent() && "AGUARDANDO_RESPOSTA".equals(ativo.get().status())) {
                return;   // cartão aberto: o turno para e espera a rota 46
            }
            if (turno.venceu()) {
                gravarFrase(contexto, turno, frase(FRASE_DO_PRAZO, FRASE_DO_PRAZO_NO_CHAT), null, ouvinte, "PRAZO_DO_TURNO",
                        ResultadoDaEtapa.ERRO);
                return;
            }
            PedidoAoLlm pedido = pedido(contexto, turno, ativo, transitorias);
            FiltroDoTexto filtro = ouvinte.emFluxo() ? new FiltroDoTexto(resolucao(contexto), checagem, turno.fontes, ouvinte) : null;
            RespostaDoLlm resposta;
            AtomicBoolean pensou = new AtomicBoolean();   // o aviso "pensando" sai uma vez por ida
            // O traço da ida: só o que não é sensível (quantas mensagens e quais ferramentas, nunca o conteúdo nem o prompt).
            Etapa ida = turno.traco.abrir(EtapaDoTraco.LLM_IDA).dado("ida", ++turno.idas).dado("emFluxo", filtro != null)
                    .dado("mensagens", pedido.mensagens().size())
                    .dado("ferramentas", pedido.ferramentas().stream().map(DefinicaoDeFerramenta::nome).toList())
                    .dado("novaRedacao", transitorias.isEmpty() ? null : true);
            try {
                resposta = filtro == null ? llm.responder(pedido) : llm.responderEmFluxo(pedido, new OuvinteDoFluxo() {
                    @Override
                    public void raciocinio(String pedaco) {
                        // O raciocínio cru fica no servidor: da tela só se avisa que o modelo começou a pensar.
                        if (pensou.compareAndSet(false, true)) {
                            ouvinte.pensando();
                        }
                    }

                    @Override
                    public void texto(String pedaco) {
                        filtro.receber(pedaco);
                    }
                });
            } catch (RuntimeException falha) {
                log.warn("Atlas: o modelo não respondeu na conversa {} ({})", contexto.conversaId(), falha.getClass().getSimpleName());
                ida.dado("erro", falha.getClass().getSimpleName()).dado("detalhe", falha.getMessage()).fim(ResultadoDaEtapa.ERRO);
                if (filtro != null) {
                    registrarRecusaDoFluxo(turno, filtro);   // o filtro pode ter recusado um trecho antes de o modelo cair
                }
                if (filtro != null && filtro.saiuAlgo()) {
                    filtro.recusar();   // o texto pela metade sai da tela; a frase da falha vem a seguir
                }
                gravarFrase(contexto, turno, frase(FRASE_DA_FALHA, FRASE_DA_FALHA_NO_CHAT), null, ouvinte, "FALHA_DO_MODELO",
                        ResultadoDaEtapa.ERRO);
                return;
            }
            // O texto do chat sai sem os caracteres invisíveis: o que a checagem confere é o que a tela mostra e o histórico guarda.
            String texto = limpar(ChecagemDeFontes.semInvisiveis(resposta.texto()));
            String raciocinio = limpar(resposta.raciocinio());
            // A ida termina quando o modelo acaba de responder; o que vem a seguir (checagem, gravação) tem etapa própria.
            ida.dados(turno.traco.dadosDoUso(resposta.uso(), llm.modelo())).dado("chamadas", resposta.chamadas().size())
                    .dado("comTexto", texto != null).dado("comRaciocinio", raciocinio != null).fim(ResultadoDaEtapa.OK);
            if (filtro != null) {
                filtro.fechar();
                registrarRecusaDoFluxo(turno, filtro);
            }

            if (resposta.chamadas().isEmpty()) {
                if (texto == null) {
                    gravarFrase(contexto, turno, frase(FRASE_SEM_RESPOSTA, FRASE_SEM_RESPOSTA_NO_CHAT), raciocinio, ouvinte,
                            "SEM_RESPOSTA", ResultadoDaEtapa.ERRO);
                    return;
                }
                Veredito veredito = checarDoChat(contexto, turno, texto, "RESPOSTA");
                if (veredito.aprovado()) {
                    if (ehNaoEncontrei(texto)) {
                        // O modelo escreveu a frase certa: o servidor acrescenta a indicação do atendimento da RBS (o L2 do quadro).
                        ouvinte.texto(" " + INDICACAO_DO_ATENDIMENTO);
                        ida.mensagem(gravarTexto(contexto, turno, TEXTO_NAO_ENCONTREI, raciocinio));
                        turno.tipoDaSaida = "TEXTO";
                        turno.motivoDaSaida = "NAO_ENCONTREI";
                        return;
                    }
                    if (semApoioNosDocumentos(turno, texto)) {
                        // Buscou, nenhuma busca trouxe trecho, e a resposta não cita fonte nem usa dado da parceria: não há de onde ela
                        // tenha saído. Sai o texto fixo, e a tela descarta o que recebeu.
                        if (filtro != null) {
                            filtro.recusar();
                        }
                        gravarFrase(contexto, turno, TEXTO_NAO_ENCONTREI, raciocinio, ouvinte, "SEM_FONTE", ResultadoDaEtapa.RECUSADO);
                        return;
                    }
                    ida.mensagem(gravarTexto(contexto, turno, texto, raciocinio));   // em fluxo, o texto já foi à tela pelo filtro
                    turno.tipoDaSaida = "TEXTO";
                    return;
                }
                if (filtro != null) {
                    filtro.recusar();   // a checagem completa recusou o que o filtro deixou passar
                }
                if (turno.pediuNovaRedacao) {
                    // Segunda recusa: num turno que buscou, o texto fixo do L2; num que não buscou, a frase de sempre.
                    if (turno.fontes.buscas() > 0) {
                        gravarFrase(contexto, turno, TEXTO_NAO_ENCONTREI, null, ouvinte, "TEXTO_RECUSADO", ResultadoDaEtapa.RECUSADO);
                    } else {
                        gravarFrase(contexto, turno, frase(FRASE_FIXA, FRASE_FIXA_NO_CHAT), null, ouvinte, "TEXTO_RECUSADO",
                                ResultadoDaEtapa.RECUSADO);
                    }
                    return;
                }
                turno.pediuNovaRedacao = true;
                transitorias = List.of(MensagemDeChat.assistente(texto, List.of(), null), MensagemDeChat.usuario(pedidoDeNovaRedacao(
                        veredito.motivo())));
                continue;
            }

            transitorias = List.of();
            if (texto != null && !checarDoChat(contexto, turno, texto, "TEXTO_COM_FERRAMENTAS").aprovado()) {
                texto = null;   // texto solto de uma rodada de ferramentas não passa com número: sai, e a ação segue
                if (filtro != null) {
                    filtro.recusar();
                }
            }
            if (rodar(contexto, turno, texto, resposta.chamadas(), raciocinio, ouvinte, ida)) {
                return;
            }
        }
    }

    /**
     * Grava a mensagem do assistente com os pedidos de ferramenta e executa cada um, na ordem. Devolve verdadeiro se o turno
     * acabou: o cartão abriu, o relatório ficou pronto ou o limite de chamadas estourou.
     */
    private boolean rodar(AtlasContexto contexto, Turno turno, String texto, List<ChamadaDeFerramenta> pedidos, String raciocinio,
                          OuvinteDoTurno ouvinte, Etapa ida) {
        // O id e os argumentos são os que o servidor guarda: id nosso e argumentos sempre um texto (sem argumento, "{}").
        List<ChamadaDeFerramenta> chamadas = pedidos.stream().map(c -> {
            String nome = limpar(c.nome());
            String argumentos = limpar(c.argumentos());
            return new ChamadaDeFerramenta(HistoricoDaConversa.novoIdDoModelo(), nome == null ? "ferramenta_sem_nome" : nome,
                    argumentos == null ? "{}" : argumentos);
        }).toList();
        long mensagemId = transacao.execute(estado -> {
            long id = historico.assistente(contexto.conversaId(), texto,
                    chamadas.stream().map(c -> new Chamada(c.id(), c.nome(), c.argumentos())).toList(), raciocinio);
            gravarFontes(id, turno, texto);
            return id;
        });
        ida.mensagem(mensagemId);   // a mensagem do assistente que esta ida produziu
        turno.mensagemFinalId = mensagemId;
        turno.resumos.pedir(mensagemId, raciocinio);   // à parte: as ferramentas e a próxima ida não esperam por ele

        boolean pausa = false;
        boolean pronto = false;
        for (ChamadaDeFerramenta chamada : chamadas) {
            Etapa etapaDaFerramenta = turno.traco.abrir(EtapaDoTraco.FERRAMENTA).mensagem(mensagemId).dado("ferramenta", chamada.nome());
            if (pausa) {
                responderComErro(contexto, mensagemId, chamada, RelatorioFerramentas.AGUARDANDO_O_CARTAO, ouvinte);
                etapaDaFerramenta.dado("motivo", "AGUARDANDO_O_CARTAO").fim(ResultadoDaEtapa.PULADO);
            } else if (pronto) {
                responderComErro(contexto, mensagemId, chamada, "O relatório já está pronto. Não chame mais ferramentas.", ouvinte);
                etapaDaFerramenta.dado("motivo", "RELATORIO_PRONTO").fim(ResultadoDaEtapa.PULADO);
            } else if (++turno.chamadas > LIMITE_DE_CHAMADAS_POR_TURNO) {
                turno.limiteAtingido = true;
                responderComErro(contexto, mensagemId, chamada, ERRO_DO_LIMITE, ouvinte);
                etapaDaFerramenta.dado("motivo", "LIMITE_DE_CHAMADAS").fim(ResultadoDaEtapa.RECUSADO);
            } else {
                String etapa = ETAPAS.get(chamada.nome());
                if (etapa != null) {
                    ouvinte.etapa(etapa);
                }
                Rodada rodada = executar(contexto, turno, mensagemId, chamada);
                pausa = rodada.execucao().pausa();
                pronto = rodada.execucao().pronto();
                avisarFerramenta(ouvinte, chamada, rodada.execucao().resultadoJson(), rodada.erro(), rodada.ms());
                etapaDaFerramenta.dado("argumentos", chamada.argumentos()).dado("resultado", rodada.execucao().resultadoJson())
                        .dado("pausa", pausa ? true : null).dado("pronto", pronto ? true : null)
                        .fim(rodada.erro() ? ResultadoDaEtapa.ERRO : ResultadoDaEtapa.OK);
            }
        }
        if (pausa || pronto) {
            turno.tipoDaSaida = pausa ? "CARTAO" : "RELATORIO_PRONTO";
            return true;
        }
        if (turno.limiteAtingido) {
            gravarFrase(contexto, turno, frase(FRASE_DO_LIMITE, FRASE_DO_LIMITE_NO_CHAT), null, ouvinte, "LIMITE_DE_CHAMADAS",
                    ResultadoDaEtapa.RECUSADO);
            return true;
        }
        return false;
    }

    /**
     * Executa a ferramenta em duas fases: o preparo, sem transação, e depois a execução e a gravação da resposta (ou, no cartão, só
     * a trilha) na MESMA transação. Se a ferramenta falha, nada dela fica, e a falha é gravada como a resposta da ferramenta, que
     * volta ao modelo.
     */
    private Rodada executar(AtlasContexto contexto, Turno turno, long mensagemId, ChamadaDeFerramenta chamada) {
        long inicio = System.nanoTime();
        int[] medido = new int[1];   // o mesmo tempo que a trilha guarda, para o evento do debug dizer igual
        try {
            // Fase 1, SEM transação: a ferramenta que chama um provedor (os embeddings da busca) faz a chamada aqui, e a conexão do
            // pool só é pega na fase 2. Os argumentos errados e a ferramenta que não existe sobem daqui, como erro de ferramenta.
            Preparo preparo = ferramentas.preparar(contexto, turno.fontes, chamada.nome(), chamada.argumentos());
            Execucao feita = transacao.execute(estado -> {
                Execucao execucao = ferramentas.executar(contexto, turno.fontes, preparo, chamada.id());
                int ms = (int) ((System.nanoTime() - inicio) / 1_000_000);
                medido[0] = ms;
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
            return new Rodada(feita, false, medido[0]);
        } catch (RuntimeException erro) {
            String json = RelatorioFerramentas.erroComoJson(erro, modo.relatorioLigado());
            int ms = (int) ((System.nanoTime() - inicio) / 1_000_000);
            transacao.executeWithoutResult(estado -> gravarErro(contexto, mensagemId, chamada, json, erro.getMessage(), ms));
            return new Rodada(new Execucao(json, false, false), true, ms);
        }
    }

    /** Uma ferramenta que rodou: o que fez, se deu erro e quanto levou (o mesmo número da trilha). */
    private record Rodada(Execucao execucao, boolean erro, int ms) {
    }

    private void responderComErro(AtlasContexto contexto, long mensagemId, ChamadaDeFerramenta chamada, String frase,
                                  OuvinteDoTurno ouvinte) {
        String json = RelatorioFerramentas.erroComoJson(frase);
        transacao.executeWithoutResult(estado -> gravarErro(contexto, mensagemId, chamada, json, frase, 0));
        avisarFerramenta(ouvinte, chamada, json, true, 0);
    }

    /** DEBUG (atlas.debug.ferramentas): manda ao fluxo a ferramenta que acabou de terminar, com os argumentos e o resultado crus. */
    private void avisarFerramenta(OuvinteDoTurno ouvinte, ChamadaDeFerramenta chamada, String resultado, boolean erro, int ms) {
        if (modo.debugDeFerramentas()) {
            ouvinte.ferramenta(DadosDaFerramenta.cortando(chamada.nome(), chamada.argumentos(), resultado, erro, ms));
        }
    }

    private void gravarErro(AtlasContexto contexto, long mensagemId, ChamadaDeFerramenta chamada, String json, String motivo, int ms) {
        historico.resultado(contexto.conversaId(), chamada.id(), json);
        repositorio.registrarChamada(mensagemId, chamada.nome(), argumentosParaATrilha(chamada.argumentos()), null,
                motivo == null ? "erro" : motivo, ms);
    }

    // ---------------------------------------------------------------------
    // O que vai ao modelo
    // ---------------------------------------------------------------------

    private PedidoAoLlm pedido(AtlasContexto contexto, Turno turno, Optional<LinhaDeRelatorio> ativo, List<MensagemDeChat> transitorias) {
        List<MensagemDeChat> mensagens = new ArrayList<>();
        mensagens.add(MensagemDeChat.sistema(promptDe(contexto)));
        mensagens.addAll(historico.paraOProvedor(contexto.conversaId(), turno.ordemInicial));
        mensagens.addAll(transitorias);
        boolean podePedirCartao = ativo.isEmpty()
                || repositorio.solicitacoesDoRelatorio(ativo.get().id()).size() < RelatorioAtlasService.LIMITE_DE_CARTOES;
        return new PedidoAoLlm(String.valueOf(contexto.conversaId()), mensagens, ferramentas.oferecidas(ativo, podePedirCartao));
    }

    /**
     * O prompt de sistema com a data de hoje e o nome da marca; id de parceria, conversa ou usuário nunca entra. Se um documento que
     * a conversa já citou foi revogado ou substituído, o prompt do turno ganha a nota "<título> v<n> não vale mais: não use o que foi
     * dito dele" (a lista vem de {@code atlas_fonte} e do estado de agora dos documentos; a nota não é gravada em lugar nenhum).
     */
    private String promptDe(AtlasContexto contexto) {
        String base = prompt.replace("@DATA@", DIA.format(Datas.hoje())).replace("@MARCA@", repositorio.nomeDaMarca(contexto.parceriaId()));
        List<AtlasRepositorio.DocumentoRetirado> retirados = repositorio.documentosCitadosQueSairamDoAr(contexto.conversaId());
        if (retirados.isEmpty()) {
            return base;
        }
        StringBuilder nota = new StringBuilder(base).append("\n\nDocumentos que esta conversa já citou e que mudaram:");
        for (AtlasRepositorio.DocumentoRetirado documento : retirados) {
            nota.append("\n- ").append(documento.titulo()).append(" v").append(documento.versao())
                    .append(" não vale mais: não use o que foi dito dele.");
        }
        return nota.toString();
    }

    /**
     * O pedido de uma nova redação do chat (o texto foi recusado). Não é fixo: diz o motivo e o que a regra do chat pede, que é o
     * oposto de "sem nenhum algarismo": os números de documento vão copiados da fonte, com a citação na mesma frase.
     */
    private static String pedidoDeNovaRedacao(String motivo) {
        return "Sua resposta foi recusada pelo servidor: " + motivo + " Escreva a resposta de novo. Número e data de documento vão "
                + "copiados exatamente como estão no trecho, com a citação [T#] na mesma frase; dado da parceria só com marcador "
                + "{{ref}}; nada de termo interno, link ou endereço. Se os trechos não trazem a resposta, escreva exatamente \""
                + FRASE_NAO_ENCONTREI + "\".";
    }

    /** O texto é, só, a frase de "não encontrei" (sem importar a caixa, os espaços nem o ponto final). */
    private static boolean ehNaoEncontrei(String texto) {
        String limpo = texto.strip().replaceAll("\\s+", " ");
        return limpo.equalsIgnoreCase(FRASE_NAO_ENCONTREI)
                || limpo.equalsIgnoreCase(FRASE_NAO_ENCONTREI.substring(0, FRASE_NAO_ENCONTREI.length() - 1));
    }

    /**
     * O turno buscou, nenhuma busca trouxe trecho (voltaram vazias ou deram erro), e a resposta não cita fonte nem usa marcador de
     * dado da parceria: ela não tem de onde ter saído. (Com marcador {{ref}}, é uma resposta certa com dado da parceria, e não pode virar "não encontrei".)
     */
    private static boolean semApoioNosDocumentos(Turno turno, String texto) {
        return turno.fontes.nenhumaBuscaTrouxeTrecho() && Frases.citacoes(texto).isEmpty() && Referencias.marcadores(texto).isEmpty();
    }

    /** Grava, na transação da mensagem, as fontes de trecho que o texto citou ({@code atlas_fonte}); o texto já passou pela checagem. */
    private void gravarFontes(long mensagemId, Turno turno, String texto) {
        if (texto == null) {
            return;
        }
        for (String marcador : new LinkedHashSet<>(Frases.citacoes(texto))) {
            Fonte fonte = turno.fontes.fonte(marcador).orElse(null);
            if (fonte != null && fonte.tipo() == FontesDoTurno.Tipo.TRECHO) {
                repositorio.inserirFonteDeTrecho(mensagemId, fonte.marcador(), fonte.trechoId(), fonte.documentoId(), fonte.versao(),
                        fonte.pagina(), fonte.titulo(), fonte.secao());
            }
        }
    }

    /** O prompt do modo: {@code relatorio.txt} com o relatório ligado, {@code chat.txt} (sem o fluxo do relatório) desligado. */
    private static String lerPrompt(String nome) {
        String caminho = "atlas/prompts/" + nome + ".txt";
        try {
            return new String(new ClassPathResource(caminho).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException erro) {
            throw new UncheckedIOException("Não consegui ler o prompt do Atlas (resources/" + caminho + ").", erro);
        }
    }

    /** A frase do modo: a antiga (com o botão) com o relatório ligado, a do chat desligado. */
    private String frase(String comRelatorio, String noChat) {
        return modo.relatorioLigado() ? comRelatorio : noChat;
    }

    // ---------------------------------------------------------------------
    // O texto do chat passa pela mesma regra do relatório
    // ---------------------------------------------------------------------

    /** A checagem do texto do chat, com a etapa CHECAGEM no traço: se aprovou, e por que não (a frase de recusa, que é nossa). */
    private Veredito checarDoChat(AtlasContexto contexto, Turno turno, String texto, String origem) {
        Etapa etapa = turno.traco.abrir(EtapaDoTraco.CHECAGEM).dado("origem", origem).dado("caracteres", texto.length());
        Veredito veredito = checagem.chat(resolucao(contexto), turno.fontes, texto);
        etapa.dado("aprovado", veredito.aprovado()).dado("motivo", veredito.motivo())
                .fim(veredito.aprovado() ? ResultadoDaEtapa.OK : ResultadoDaEtapa.RECUSADO);
        return veredito;
    }

    /**
     * Se o filtro do fluxo recusou um trecho desta ida na checagem (e já avisou a tela com {@code texto_recusado}), deixa a etapa
     * {@code CHECAGEM} de origem {@code FLUXO}: a frase da recusa e quanto do texto o filtro tinha conferido. A checagem do texto
     * inteiro, de origem {@code RESPOSTA}, vem depois e também fica no traço. Sem recusa do filtro, não deixa nada.
     */
    private void registrarRecusaDoFluxo(Turno turno, FiltroDoTexto filtro) {
        if (filtro.motivoDaRecusa() == null) {
            return;
        }
        turno.traco.abrir(EtapaDoTraco.CHECAGEM).dado("origem", "FLUXO").dado("caracteres", filtro.caracteresNaRecusa())
                .dado("aprovado", false).dado("motivo", filtro.motivoDaRecusa()).fim(ResultadoDaEtapa.RECUSADO);
    }

    /** Uma resolução nova dos marcadores do chat, contra a parceria da conversa e com o perfil de quem pede. */
    private Resolucao resolucao(AtlasContexto contexto) {
        return resolvedor.abrir(contexto.usuario(), contexto.parceriaId(), modelos.obter(ModeloBase.ATIVACAO_EVENTO).orElseThrow());
    }

    /** Grava o texto do assistente como a resposta do turno e devolve o id da mensagem. */
    private long gravarTexto(AtlasContexto contexto, Turno turno, String texto, String raciocinio) {
        long mensagemId = transacao.execute(estado -> {
            long id = historico.assistente(contexto.conversaId(), texto, List.of(), raciocinio);
            gravarFontes(id, turno, texto);
            return id;
        });
        turno.mensagemFinalId = mensagemId;
        turno.caracteresDaSaida = texto.length();
        if (turno.resumos != null) {
            turno.resumos.pedir(mensagemId, raciocinio);   // sem raciocínio, não há o que resumir
        }
        return mensagemId;
    }

    /**
     * Grava uma frase fixa do servidor e, em fluxo, a manda inteira à tela (ela não passa pelo filtro: é nossa). {@code motivo} e
     * {@code resultado} dizem ao traço por que o turno terminou assim.
     */
    private void gravarFrase(AtlasContexto contexto, Turno turno, String frase, String raciocinio, OuvinteDoTurno ouvinte, String motivo,
                             ResultadoDaEtapa resultado) {
        gravarTexto(contexto, turno, frase, raciocinio);
        turno.tipoDaSaida = "FRASE_FIXA";
        turno.motivoDaSaida = motivo;
        turno.resultadoDaSaida = resultado;
        ouvinte.texto(frase);
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

    /**
     * Os argumentos como JSON para a trilha: o que o modelo mandou, se for JSON; senão o texto dele como string JSON. O PostgreSQL não
     * guarda o caractere nulo no jsonb, e o JSON pode trazê-lo como o escape de nulo de seis caracteres (o {@link #limpar} só tira o
     * nulo cru, não o escape): um
     * argumento com ele derrubaria a gravação da trilha, e com ela o turno. Cada nulo vira o caractere de substituição.
     */
    private static String argumentosParaATrilha(String argumentos) {
        if (argumentos == null || argumentos.isBlank()) {
            return "{}";
        }
        try {
            Object lido = AtlasJson.ler(argumentos, Object.class);
            return temNulo(lido) ? AtlasJson.escrever(semNulo(lido)) : argumentos;
        } catch (JacksonException naoEJson) {
            return AtlasJson.escrever(semNulo(argumentos));
        }
    }

    private static boolean temNulo(Object valor) {
        if (valor instanceof String texto) {
            return texto.indexOf('\u0000') >= 0;
        }
        if (valor instanceof Map<?, ?> mapa) {
            return mapa.entrySet().stream().anyMatch(e -> temNulo(e.getKey()) || temNulo(e.getValue()));
        }
        if (valor instanceof List<?> lista) {
            return lista.stream().anyMatch(LoopDoAgente::temNulo);
        }
        return false;
    }

    private static Object semNulo(Object valor) {
        if (valor instanceof String texto) {
            return texto.replace('\u0000', '\uFFFD');
        }
        if (valor instanceof Map<?, ?> mapa) {
            Map<Object, Object> limpo = new java.util.LinkedHashMap<>();
            mapa.forEach((chave, conteudo) -> limpo.put(semNulo(chave), semNulo(conteudo)));
            return limpo;
        }
        if (valor instanceof List<?> lista) {
            return lista.stream().map(LoopDoAgente::semNulo).toList();
        }
        return valor;
    }
}
