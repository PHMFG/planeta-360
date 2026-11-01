package br.com.rbs.portal.atlas.jev;

import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.checagem.Frases;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.checagem.NormalizadorDeNumeros;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.diagnostico.ResultadoDaEtapa;
import br.com.rbs.portal.atlas.diagnostico.Traco.Etapa;
import br.com.rbs.portal.atlas.jev.JevDoTurno.Decisao;
import br.com.rbs.portal.atlas.jev.ProvedorDeJev.PedidoAoJev;
import br.com.rbs.portal.atlas.jev.ProvedorDeJev.PerguntaAoJev;
import br.com.rbs.portal.atlas.jev.ProvedorDeJev.RespostaDaPergunta;
import br.com.rbs.portal.atlas.jev.ProvedorDeJev.RespostaDoJev;
import br.com.rbs.portal.atlas.relatorio.Referencias;
import br.com.rbs.portal.dados.DadoInformadoService;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Os seis pontos de decisão do JEV (plano do Atlas completo, M7): pergunta, interpreta, registra e diz o que fazer; quem age é o
 * loop do agente, as ferramentas e a checagem. [ATLAS] Regras que valem em todos:
 * <ul>
 *   <li><b>Nunca durante uma transação.</b> Cada ponto roda na thread do turno (ou em requisições em paralelo, no {@code fontes} e no
 *       {@code coerencia}), sem conexão do pool na mão; as decisões só vão para {@code atlas_decisao} no fim, em {@link #gravar}.</li>
 *   <li><b>O JEV nunca é barreira.</b> Em {@code DESLIGADO} não é consultado; em {@code SOMBRA} é consultado e só se grava "teria
 *       feito X", sem mudar nada; em {@code ATIVO} age quando a confiança chega ao limiar do ponto. Abaixo do limiar, em erro, no tempo
 *       limite ou com o orçamento do turno gasto, o que ele decidiria não acontece e o turno segue o caminho do modelo de linguagem.</li>
 *   <li><b>O {@code state} é o mínimo</b>, mascarado ({@link MascaraDoJev}): a mensagem (ou o trecho, ou a frase) do ponto, nunca
 *       nome nem id de parceria.</li>
 *   <li><b>Orçamento:</b> até {@value JevDoTurno#MAXIMO_DE_REQUISICOES} requisições por turno. Depois disso o que falta segue sem JEV, e
 *       a etapa do traço diz {@code ORCAMENTO_ESGOTADO}.</li>
 *   <li><b>Registro:</b> cada pergunta respondida (ou que falhou) vira uma {@code atlas_decisao} com o ponto, o modo, o limiar, a ação
 *       ("fez: X" em {@code ATIVO}, "teria feito: X" em {@code SOMBRA}) e o alvo; cada rodada vira uma etapa do traço
 *       ({@code J1_INTENCAO}, {@code J1_CARTAO}, {@code R3_FONTES}, {@code R5_RESPOSTA}, {@code V2_COERENCIA}, {@code LACUNAS}).</li>
 * </ul>
 */
@Component
public class ServicoDoJev {

    private static final Logger log = LoggerFactory.getLogger(ServicoDoJev.class);

    /** Quanto o turno espera, no máximo, uma rodada em paralelo (cada requisição já tem os 800 ms dela). */
    private static final long ESPERA_MAXIMA_DA_RODADA_MS = 3_000;
    private static final String TIPO_NENHUM = "nenhum";

    /** O que a intenção pede que o servidor faça em ATIVO (e faria em SOMBRA). O texto é o que fica em {@code atlas_decisao.acao}. */
    public enum AcaoDeIntencao {
        NENHUMA(null, null),
        RECUSAR_FORA_DE_ESCOPO("recusou o fora de escopo sem o modelo", "recusar o fora de escopo sem o modelo"),
        PEDIDO_DE_RELATORIO_DIRETO("respondeu o pedido de relatório sem o modelo", "responder o pedido de relatório sem o modelo"),
        GERAR_MODELO_DIRETO("entregou o modelo em branco sem o modelo", "entregar o modelo em branco sem o modelo"),
        INICIAR_RELATORIO("abriu o relatório em código e seguiu para o modelo", "abrir o relatório em código e seguir para o modelo"),
        RAMO_DOCUMENTOS("deu ao modelo só as ferramentas dos documentos", "dar ao modelo só as ferramentas dos documentos"),
        RAMO_DADOS("deu ao modelo só as ferramentas dos dados da parceria", "dar ao modelo só as ferramentas dos dados da parceria");

        private final String feita;
        private final String teria;

        AcaoDeIntencao(String feita, String teria) {
            this.feita = feita;
            this.teria = teria;
        }
    }

    /**
     * O que o ponto de intenção decidiu. {@code acao} é o que o servidor faria com a escolha (no modo e com o limiar do ponto);
     * {@link #age} diz se faz de verdade (só em {@code ATIVO}). {@code tipoModelo} é o modelo em branco pedido, em maiúsculas
     * ({@code BRIEFING_CONTEUDO}), ou nulo quando não é um pedido de modelo.
     */
    public record DecisaoDeIntencao(String intencao, double confianca, String tipoModelo, Double sinalDeInjecao, ModoDoJev modo,
                                    AcaoDeIntencao acao) {

        public boolean age() {
            return modo == ModoDoJev.ATIVO && acao != AcaoDeIntencao.NENHUMA;
        }
    }

    /** O que o servidor sabe do estado da conversa, que a intenção usa para decidir se a ação cabe. */
    public record SituacaoDaIntencao(boolean relatorioLigado, boolean temRelatorioEmAndamento, Set<String> modelosDisponiveis) {
    }

    /** O que o ponto do cartão decidiu: {@link #responde} é verdadeiro só em {@code ATIVO}, com {@code responde_ao_cartao} acima do limiar. */
    public record DecisaoDoCartao(String opcao, double confianca, ModoDoJev modo, boolean responde) {
    }

    /** Um trecho que a busca devolveu, para o R3 julgar. {@code alvo} identifica o trecho no registro (o id dele). */
    public record TrechoParaJulgar(String alvo, String texto) {
    }

    /** O juízo do R3 sobre um trecho: o valor da {@code noul}, se o trecho sai do resultado ({@code ATIVO} abaixo do limiar) e o erro, se o JEV falhou. */
    public record JuizoDoTrecho(String alvo, Double noul, boolean tirar, String erro) {
    }

    /** O que o R5 achou da resposta: {@code recusa} só em {@code ATIVO}, e o {@code motivo} que a nova redação recebe. */
    public record ApoioDaResposta(boolean recusa, String motivo) {
    }

    /** Um parágrafo do relatório, para o V2 julgar contra os selos. */
    public record ParagrafoParaJulgar(String bloco, String texto) {
    }

    /** Uma lacuna opcional do relatório, para o passo 4 julgar se vale perguntar. */
    public record LacunaOpcional(String chave, String rotulo) {
    }

    /** A chamada ao JEV: a resposta, ou o motivo do erro; e quanto levou. */
    private record Chamada(RespostaDoJev resposta, String erro, long ms) {
    }

    private final ConfiguracaoDoJev config;
    private final ProvedorDeJev provedor;
    private final PerguntasDoJev perguntas;
    private final AtlasRepositorio repositorio;
    private final DadoInformadoService dados;
    private final TransactionTemplate transacao;
    private final ExecutorService emParalelo = Executors.newVirtualThreadPerTaskExecutor();

    public ServicoDoJev(ConfiguracaoDoJev config, ProvedorDeJev provedor, PerguntasDoJev perguntas, AtlasRepositorio repositorio,
                        DadoInformadoService dados, TransactionTemplate transacao) {
        this.config = config;
        this.provedor = provedor;
        this.perguntas = perguntas;
        this.repositorio = repositorio;
        this.dados = dados;
        this.transacao = transacao;
    }

    @PreDestroy
    void parar() {
        emParalelo.shutdown();
    }

    /** O modo do ponto neste turno: {@code DESLIGADO} num contexto sem turno de JEV. */
    public ModoDoJev modo(AtlasContexto contexto, PontoDoJev ponto) {
        return contexto.jev().ativo() ? config.modo(ponto) : ModoDoJev.DESLIGADO;
    }

    public double limiar(PontoDoJev ponto) {
        return config.limiar(ponto);
    }

    // ---------------------------------------------------------------------
    // J1: a intenção da mensagem
    // ---------------------------------------------------------------------

    /**
     * Pergunta ao JEV a intenção da mensagem (nove opções), o tipo do modelo em branco e o sinal de injeção, numa requisição só.
     * Vazio quando o ponto está desligado, o orçamento acabou ou o JEV falhou (a falha fica registrada). A ação só vale com a confiança
     * da intenção no limiar do ponto; o pedido de modelo precisa também da confiança do tipo no limiar.
     */
    public Optional<DecisaoDeIntencao> intencao(AtlasContexto contexto, String mensagem, SituacaoDaIntencao situacao) {
        ModoDoJev modo = modo(contexto, PontoDoJev.INTENCAO);
        if (!modo.consulta()) {
            return Optional.empty();
        }
        double limiar = config.limiar(PontoDoJev.INTENCAO);
        Etapa etapa = abrirEtapa(contexto, PontoDoJev.INTENCAO, modo);
        if (contexto.jev().reservar(1) == 0) {
            etapa.dado("motivo", "ORCAMENTO_ESGOTADO").fim(ResultadoDaEtapa.PULADO);
            return Optional.empty();
        }
        PedidoAoJev pedido = new PedidoAoJev(mascarar(contexto, perguntas.estado(PontoDoJev.INTENCAO, Map.of("mensagem", mensagem))),
                perguntas.perguntas(PontoDoJev.INTENCAO));
        Chamada chamada = chamar(pedido);
        RespostaDaPergunta intencao = valida(chamada, "intencao", PontoDoJev.INTENCAO, pedido);
        if (intencao == null) {
            String erro = chamada.erro() != null ? chamada.erro() : "RESPOSTA_INCOMPLETA";
            registrarFalha(contexto, PontoDoJev.INTENCAO, modo, limiar, "intencao", null, erro, chamada.ms());
            etapa.dado("latenciaMs", chamada.ms()).dado("erro", erro).dado("acao", "não agiu: o JEV não respondeu, o turno segue o modelo")
                    .fim(ResultadoDaEtapa.ERRO);
            return Optional.empty();
        }
        RespostaDaPergunta tipo = valida(chamada, "tipo_modelo", PontoDoJev.INTENCAO, pedido);
        RespostaDaPergunta sinal = chamada.resposta().respostas().get("sinal_injecao");
        String tipoDoModelo = tipo == null || TIPO_NENHUM.equals(tipo.escolha()) ? null : tipo.escolha().toUpperCase(java.util.Locale.ROOT);
        boolean passa = intencao.confianca() >= limiar;
        AcaoDeIntencao acao = !passa ? AcaoDeIntencao.NENHUMA : acaoDaIntencao(intencao.escolha(), tipo, tipoDoModelo, limiar, situacao);
        String texto = textoDaAcao(modo, acao.feita, acao.teria, passa ? "sem ação para " + intencao.escolha()
                : "confiança abaixo do limiar");
        String modelo = provedor.modelo();
        contexto.jev().adicionar(new Decisao(PontoDoJev.INTENCAO, modo, limiar, "intencao", intencao.escolha(), intencao.confianca(),
                intencao.probabilidades(), modelo, chamada.ms(), null, texto, null));
        if (tipo != null) {
            contexto.jev().adicionar(new Decisao(PontoDoJev.INTENCAO, modo, limiar, "tipo_modelo", tipo.escolha(), tipo.confianca(),
                    tipo.probabilidades(), modelo, chamada.ms(), null, texto, null));
        }
        if (sinal != null) {
            contexto.jev().adicionar(new Decisao(PontoDoJev.INTENCAO, modo, limiar, "sinal_injecao", null, sinal.noul(), null, modelo,
                    chamada.ms(), null, texto, null));
        }
        etapa.dado("latenciaMs", chamada.ms()).dado("escolha", intencao.escolha()).dado("confianca", intencao.confianca())
                .dado("probabilidades", intencao.probabilidades()).dado("tipoModelo", tipo == null ? null : tipo.escolha())
                .dado("confiancaDoTipo", tipo == null ? null : tipo.confianca()).dado("sinalDeInjecao", sinal == null ? null : sinal.noul())
                .dado("acao", texto).dado("agiu", modo == ModoDoJev.ATIVO && acao != AcaoDeIntencao.NENHUMA)
                .fim(modo == ModoDoJev.ATIVO ? ResultadoDaEtapa.OK : ResultadoDaEtapa.SOMBRA);
        return Optional.of(new DecisaoDeIntencao(intencao.escolha(), intencao.confianca(), tipoDoModelo,
                sinal == null ? null : sinal.noul(), modo, acao));
    }

    private static AcaoDeIntencao acaoDaIntencao(String escolha, RespostaDaPergunta tipo, String tipoDoModelo, double limiar,
                                                 SituacaoDaIntencao situacao) {
        return switch (escolha) {
            case "fora_de_escopo" -> AcaoDeIntencao.RECUSAR_FORA_DE_ESCOPO;
            case "outro_relatorio" -> AcaoDeIntencao.PEDIDO_DE_RELATORIO_DIRETO;
            case "modelo_em_branco" -> tipoDoModelo != null && tipo.confianca() >= limiar
                    && situacao.modelosDisponiveis().contains(tipoDoModelo) ? AcaoDeIntencao.GERAR_MODELO_DIRETO : AcaoDeIntencao.NENHUMA;
            case "montar_relatorio" -> situacao.relatorioLigado() && !situacao.temRelatorioEmAndamento()
                    ? AcaoDeIntencao.INICIAR_RELATORIO : AcaoDeIntencao.NENHUMA;
            case "pergunta_documentos" -> AcaoDeIntencao.RAMO_DOCUMENTOS;
            case "dados_parceria" -> AcaoDeIntencao.RAMO_DADOS;
            default -> AcaoDeIntencao.NENHUMA;
        };
    }

    // ---------------------------------------------------------------------
    // J1: a mensagem de quem tem um cartão aberto
    // ---------------------------------------------------------------------

    /**
     * Pergunta ao JEV o que a mensagem faz em relação ao cartão aberto (responde ao cartão, outro assunto ou cancelar). Vazio quando o
     * ponto está desligado, o orçamento acabou ou o JEV falhou. Só {@code responde_ao_cartao} acima do limiar, em {@code ATIVO}, muda
     * o turno (resposta fixa, sem modelo, e o cartão continua aberto); {@code outro_assunto} e {@code cancelar} seguem o caminho de
     * hoje: o cartão é fechado com {@code cancel} e a mensagem vai ao loop.
     */
    public Optional<DecisaoDoCartao> cartao(AtlasContexto contexto, String mensagem, List<String> chavesDoCartao) {
        ModoDoJev modo = modo(contexto, PontoDoJev.CARTAO);
        if (!modo.consulta()) {
            return Optional.empty();
        }
        double limiar = config.limiar(PontoDoJev.CARTAO);
        Etapa etapa = abrirEtapa(contexto, PontoDoJev.CARTAO, modo);
        if (contexto.jev().reservar(1) == 0) {
            etapa.dado("motivo", "ORCAMENTO_ESGOTADO").fim(ResultadoDaEtapa.PULADO);
            return Optional.empty();
        }
        Map<String, String> rotulos = new LinkedHashMap<>();
        dados.listarCampos(null).forEach(campo -> rotulos.put(campo.chave(), campo.rotulo()));
        List<String> nomesDosCampos = chavesDoCartao.stream().map(chave -> rotulos.getOrDefault(chave, chave)).toList();
        PedidoAoJev pedido = new PedidoAoJev(mascarar(contexto, perguntas.estado(PontoDoJev.CARTAO,
                Map.of("campos", String.join(", ", nomesDosCampos), "mensagem", mensagem))), perguntas.perguntas(PontoDoJev.CARTAO));
        Chamada chamada = chamar(pedido);
        RespostaDaPergunta cartao = valida(chamada, "cartao", PontoDoJev.CARTAO, pedido);
        if (cartao == null) {
            String erro = chamada.erro() != null ? chamada.erro() : "RESPOSTA_INCOMPLETA";
            registrarFalha(contexto, PontoDoJev.CARTAO, modo, limiar, "cartao", null, erro, chamada.ms());
            etapa.dado("latenciaMs", chamada.ms()).dado("erro", erro).dado("acao", "não agiu: o JEV não respondeu, segue o caminho de hoje")
                    .fim(ResultadoDaEtapa.ERRO);
            return Optional.empty();
        }
        boolean passa = cartao.confianca() >= limiar;
        boolean responde = "responde_ao_cartao".equals(cartao.escolha()) && passa;
        String texto = responde ? textoDaAcao(modo, "respondeu com a frase fixa e manteve o cartão aberto",
                "responder com a frase fixa e manter o cartão aberto", null)
                : "não agiu: " + (passa ? "segue o caminho de hoje (fecha o cartão e manda ao modelo)" : "confiança abaixo do limiar");
        contexto.jev().adicionar(new Decisao(PontoDoJev.CARTAO, modo, limiar, "cartao", cartao.escolha(), cartao.confianca(),
                cartao.probabilidades(), provedor.modelo(), chamada.ms(), null, texto, null));
        etapa.dado("latenciaMs", chamada.ms()).dado("escolha", cartao.escolha()).dado("confianca", cartao.confianca())
                .dado("probabilidades", cartao.probabilidades()).dado("acao", texto)
                .dado("agiu", modo == ModoDoJev.ATIVO && responde)
                .fim(modo == ModoDoJev.ATIVO ? ResultadoDaEtapa.OK : ResultadoDaEtapa.SOMBRA);
        return Optional.of(new DecisaoDoCartao(cartao.escolha(), cartao.confianca(), modo, modo == ModoDoJev.ATIVO && responde));
    }

    // ---------------------------------------------------------------------
    // R3: a relevância de cada trecho da busca
    // ---------------------------------------------------------------------

    /**
     * Pergunta ao JEV, para cada trecho, se ele ajuda a responder a pergunta ({@code noul}, só a pergunta e aquele trecho no
     * {@code state}). As requisições da busca saem em paralelo: uma rodada de espera. Devolve um juízo por trecho julgado, na ordem
     * (os que o orçamento não alcançou, e todos se o ponto está desligado, não aparecem e seguem). Em {@code ATIVO}, o trecho com
     * {@code noul} abaixo do limiar sai do resultado; o que falhou segue.
     */
    public List<JuizoDoTrecho> fontes(AtlasContexto contexto, String pergunta, List<TrechoParaJulgar> trechos) {
        ModoDoJev modo = modo(contexto, PontoDoJev.FONTES);
        if (!modo.consulta() || trechos.isEmpty()) {
            return List.of();
        }
        double limiar = config.limiar(PontoDoJev.FONTES);
        Etapa etapa = abrirEtapa(contexto, PontoDoJev.FONTES, modo);
        int concedidas = contexto.jev().reservar(trechos.size());
        if (concedidas == 0) {
            etapa.dado("motivo", "ORCAMENTO_ESGOTADO").fim(ResultadoDaEtapa.PULADO);
            return List.of();
        }
        List<TrechoParaJulgar> julgados = trechos.subList(0, concedidas);
        List<PedidoAoJev> pedidos = julgados.stream().map(trecho -> new PedidoAoJev(mascarar(contexto, perguntas.estado(PontoDoJev.FONTES,
                Map.of("pergunta", pergunta, "trecho", trecho.texto()))), perguntas.perguntas(PontoDoJev.FONTES))).toList();
        List<Chamada> chamadas = chamarEmParalelo(pedidos);
        List<JuizoDoTrecho> juizos = new ArrayList<>();
        List<Map<String, Object>> itens = new ArrayList<>();
        int comErro = 0;
        boolean tirouAlgum = false;
        for (int i = 0; i < julgados.size(); i++) {
            TrechoParaJulgar trecho = julgados.get(i);
            Chamada chamada = chamadas.get(i);
            RespostaDaPergunta relevante = chamada.erro() != null ? null : chamada.resposta().respostas().get("relevante");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("alvo", trecho.alvo());
            item.put("latenciaMs", chamada.ms());
            if (relevante == null || relevante.noul() == null) {
                String erro = chamada.erro() != null ? chamada.erro() : "RESPOSTA_INCOMPLETA";
                comErro++;
                juizos.add(new JuizoDoTrecho(trecho.alvo(), null, false, erro));
                item.put("erro", erro);
                item.put("acao", "não agiu: o JEV não respondeu, o trecho segue");
                registrarFalha(contexto, PontoDoJev.FONTES, modo, limiar, "relevante", trecho.alvo(), erro, chamada.ms());
            } else {
                double noul = relevante.noul();
                boolean tirar = modo == ModoDoJev.ATIVO && noul < limiar;
                tirouAlgum |= tirar;
                String texto = noul < limiar ? textoDaAcao(modo, "tirou o trecho do resultado", "tirar o trecho do resultado", null)
                        : textoDaAcao(modo, "manteve o trecho", "manter o trecho", null);
                juizos.add(new JuizoDoTrecho(trecho.alvo(), noul, tirar, null));
                item.put("noul", noul);
                item.put("acao", texto);
                contexto.jev().adicionar(new Decisao(PontoDoJev.FONTES, modo, limiar, "relevante", null, noul, null, provedor.modelo(),
                        chamada.ms(), null, texto, trecho.alvo()));
            }
            itens.add(item);
        }
        etapa.dado("requisicoes", julgados.size()).dado("semJev", trechos.size() - julgados.size() > 0 ? trechos.size() - julgados.size() : null)
                .dado("motivoDoCorte", trechos.size() > julgados.size() ? "ORCAMENTO_ESGOTADO" : null).dado("itens", itens);
        etapa.fim(comErro == julgados.size() ? ResultadoDaEtapa.ERRO
                : modo == ModoDoJev.ATIVO ? (tirouAlgum ? ResultadoDaEtapa.RECUSADO : ResultadoDaEtapa.OK) : ResultadoDaEtapa.SOMBRA);
        return juizos;
    }

    // ---------------------------------------------------------------------
    // R5: a fonte citada apoia cada frase da resposta?
    // ---------------------------------------------------------------------

    /**
     * Pergunta ao JEV, numa requisição só, se os trechos citados apoiam cada frase da resposta que cita um trecho (até 8 frases):
     * {@code apoia}, {@code contradiz} ou {@code nao_diz}. Em {@code ATIVO}, {@code contradiz} acima do limiar recusa a resposta, e
     * {@code nao_diz} acima do limiar também, se a frase tem número ou data. Vazio quando o ponto está desligado, a resposta não tem
     * frase com citação de trecho, o orçamento acabou ou o JEV falhou.
     */
    public Optional<ApoioDaResposta> resposta(AtlasContexto contexto, FontesDoTurno fontes, String texto) {
        ModoDoJev modo = modo(contexto, PontoDoJev.RESPOSTA);
        if (!modo.consulta()) {
            return Optional.empty();
        }
        double limiar = config.limiar(PontoDoJev.RESPOSTA);
        List<String> frases = new ArrayList<>();
        Set<String> citados = new LinkedHashSet<>();
        for (String frase : Frases.dividir(texto)) {
            List<String> trechosDaFrase = Frases.citacoes(frase).stream().filter(m -> m.startsWith("T")).toList();
            if (!trechosDaFrase.isEmpty() && frases.size() < PerguntasDoJev.MAXIMO_DE_FRASES) {
                frases.add(Referencias.substituir(frase, ref -> "[VALOR]"));
                citados.addAll(trechosDaFrase);
            }
        }
        Etapa etapa = abrirEtapa(contexto, PontoDoJev.RESPOSTA, modo);
        if (frases.isEmpty()) {
            etapa.dado("motivo", "SEM_FRASE_COM_CITACAO").fim(ResultadoDaEtapa.PULADO);
            return Optional.empty();
        }
        if (contexto.jev().reservar(1) == 0) {
            etapa.dado("motivo", "ORCAMENTO_ESGOTADO").fim(ResultadoDaEtapa.PULADO);
            return Optional.empty();
        }
        StringBuilder trechos = new StringBuilder();
        for (String marcador : citados) {
            fontes.fonte(marcador).ifPresent(fonte -> trechos.append('[').append(fonte.marcador()).append("] ").append(fonte.metadados().strip())
                    .append('\n').append(fonte.texto() == null ? "" : fonte.texto()).append("\n\n"));
        }
        String estado = mascarar(contexto, perguntas.estado(PontoDoJev.RESPOSTA, Map.of("resposta",
                Referencias.substituir(texto, ref -> "[VALOR]"), "trechos", trechos.toString().strip())));
        Map<String, PerguntaAoJev> pergunta = new LinkedHashMap<>();
        perguntas.perguntasDeFrases(frases.stream().map(f -> mascarar(contexto, f)).toList()).forEach(pergunta::put);
        PedidoAoJev pedido = new PedidoAoJev(estado, pergunta);
        Chamada chamada = chamar(pedido);
        if (chamada.erro() != null) {
            registrarFalha(contexto, PontoDoJev.RESPOSTA, modo, limiar, "frase_1", null, chamada.erro(), chamada.ms());
            etapa.dado("latenciaMs", chamada.ms()).dado("erro", chamada.erro()).dado("acao", "não agiu: o JEV não respondeu, a resposta segue")
                    .fim(ResultadoDaEtapa.ERRO);
            return Optional.empty();
        }
        List<Map<String, Object>> itens = new ArrayList<>();
        String motivo = null;
        for (int i = 0; i < frases.size(); i++) {
            String nome = "frase_" + (i + 1);
            RespostaDaPergunta r = chamada.resposta().respostas().get(nome);
            String frase = frases.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("alvo", nome);
            if (r == null || r.escolha() == null || !pergunta.get(nome).criterios().containsKey(r.escolha())) {
                item.put("erro", "RESPOSTA_INCOMPLETA");
                item.put("acao", "não agiu: o JEV não respondeu esta frase");
                registrarFalha(contexto, PontoDoJev.RESPOSTA, modo, limiar, nome, "frase:" + (i + 1), "RESPOSTA_INCOMPLETA", chamada.ms());
                itens.add(item);
                continue;
            }
            boolean passa = r.confianca() >= limiar;
            boolean comNumero = !NormalizadorDeNumeros.extrair(Frases.semCitacoes(Referencias.semMarcadores(frase)), false).isEmpty();
            boolean recusa = passa && ("contradiz".equals(r.escolha()) || ("nao_diz".equals(r.escolha()) && comNumero));
            String texto2 = recusa ? textoDaAcao(modo, "recusou a resposta e pediu nova redação", "recusar a resposta e pedir nova redação", null)
                    : "não agiu: " + (passa ? "a frase segue" : "confiança abaixo do limiar");
            if (recusa && modo == ModoDoJev.ATIVO && motivo == null) {
                motivo = "contradiz".equals(r.escolha())
                        ? "A frase \"" + frase + "\" contradiz o trecho que ela cita. Reescreva essa frase só com o que o trecho diz, ou retire a afirmação."
                        : "O trecho citado na frase \"" + frase + "\" não diz o que ela afirma sobre o número ou a data. Reescreva essa frase só com "
                                + "o que o trecho diz, ou retire a afirmação.";
            }
            item.put("escolha", r.escolha());
            item.put("confianca", r.confianca());
            item.put("probabilidades", r.probabilidades());
            item.put("comNumeroOuData", comNumero);
            item.put("acao", texto2);
            itens.add(item);
            contexto.jev().adicionar(new Decisao(PontoDoJev.RESPOSTA, modo, limiar, nome, r.escolha(), r.confianca(), r.probabilidades(),
                    provedor.modelo(), chamada.ms(), null, texto2, "frase:" + (i + 1)));
        }
        boolean recusou = motivo != null;
        etapa.dado("latenciaMs", chamada.ms()).dado("frases", frases.size()).dado("itens", itens)
                .fim(modo == ModoDoJev.ATIVO ? (recusou ? ResultadoDaEtapa.RECUSADO : ResultadoDaEtapa.OK) : ResultadoDaEtapa.SOMBRA);
        return Optional.of(new ApoioDaResposta(modo == ModoDoJev.ATIVO && recusou, motivo));
    }

    // ---------------------------------------------------------------------
    // V2: o parágrafo do relatório combina com os selos?
    // ---------------------------------------------------------------------

    /**
     * Pergunta ao JEV, para cada parágrafo, se ele é coerente com os selos do servidor ("acima do previsto", "abaixo do previsto"),
     * com os números trocados por {@code [VALOR]}. Sem selo, o ponto não roda. Devolve, por bloco, o motivo da recusa: só em
     * {@code ATIVO}, com {@code incoerente} acima do limiar; o que o JEV não respondeu segue.
     */
    public Map<String, String> coerencia(AtlasContexto contexto, List<String> selos, List<ParagrafoParaJulgar> paragrafos) {
        ModoDoJev modo = modo(contexto, PontoDoJev.COERENCIA);
        if (!modo.consulta() || paragrafos.isEmpty()) {
            return Map.of();
        }
        double limiar = config.limiar(PontoDoJev.COERENCIA);
        Etapa etapa = abrirEtapa(contexto, PontoDoJev.COERENCIA, modo);
        if (selos.isEmpty()) {
            etapa.dado("motivo", "SEM_SELO").fim(ResultadoDaEtapa.PULADO);
            return Map.of();
        }
        int concedidas = contexto.jev().reservar(paragrafos.size());
        if (concedidas == 0) {
            etapa.dado("motivo", "ORCAMENTO_ESGOTADO").fim(ResultadoDaEtapa.PULADO);
            return Map.of();
        }
        List<ParagrafoParaJulgar> julgados = paragrafos.subList(0, concedidas);
        String listaDeSelos = selos.stream().map(s -> "- " + s).reduce((a, b) -> a + "\n" + b).orElse("");
        List<PedidoAoJev> pedidos = julgados.stream().map(p -> new PedidoAoJev(mascarar(contexto, perguntas.estado(PontoDoJev.COERENCIA,
                Map.of("selos", listaDeSelos, "paragrafo", Referencias.substituir(p.texto(), ref -> "[VALOR]")))),
                perguntas.perguntas(PontoDoJev.COERENCIA))).toList();
        List<Chamada> chamadas = chamarEmParalelo(pedidos);
        Map<String, String> recusas = new LinkedHashMap<>();
        List<Map<String, Object>> itens = new ArrayList<>();
        int comErro = 0;
        for (int i = 0; i < julgados.size(); i++) {
            ParagrafoParaJulgar paragrafo = julgados.get(i);
            Chamada chamada = chamadas.get(i);
            RespostaDaPergunta r = chamada.erro() != null ? null : chamada.resposta().respostas().get("coerencia");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("alvo", paragrafo.bloco());
            item.put("latenciaMs", chamada.ms());
            if (r == null || !("coerente".equals(r.escolha()) || "incoerente".equals(r.escolha()))) {
                String erro = chamada.erro() != null ? chamada.erro() : "RESPOSTA_INCOMPLETA";
                comErro++;
                item.put("erro", erro);
                item.put("acao", "não agiu: o JEV não respondeu, o parágrafo segue");
                registrarFalha(contexto, PontoDoJev.COERENCIA, modo, limiar, "coerencia", paragrafo.bloco(), erro, chamada.ms());
            } else {
                boolean recusa = "incoerente".equals(r.escolha()) && r.confianca() >= limiar;
                String texto = recusa ? textoDaAcao(modo, "recusou o parágrafo e pediu nova redação", "recusar o parágrafo e pedir nova redação", null)
                        : "não agiu: " + ("incoerente".equals(r.escolha()) ? "confiança abaixo do limiar" : "o parágrafo segue");
                if (recusa && modo == ModoDoJev.ATIVO) {
                    recusas.put(paragrafo.bloco(), "O texto não combina com os selos do relatório (" + String.join("; ", selos)
                            + "). Não diga o contrário do que eles mostram.");
                }
                item.put("escolha", r.escolha());
                item.put("confianca", r.confianca());
                item.put("probabilidades", r.probabilidades());
                item.put("acao", texto);
                contexto.jev().adicionar(new Decisao(PontoDoJev.COERENCIA, modo, limiar, "coerencia", r.escolha(), r.confianca(),
                        r.probabilidades(), provedor.modelo(), chamada.ms(), null, texto, paragrafo.bloco()));
            }
            itens.add(item);
        }
        etapa.dado("selos", selos).dado("requisicoes", julgados.size()).dado("itens", itens)
                .fim(comErro == julgados.size() ? ResultadoDaEtapa.ERRO
                        : modo == ModoDoJev.ATIVO ? (recusas.isEmpty() ? ResultadoDaEtapa.OK : ResultadoDaEtapa.RECUSADO) : ResultadoDaEtapa.SOMBRA);
        return recusas;
    }

    // ---------------------------------------------------------------------
    // Passo 4: vale perguntar a lacuna opcional?
    // ---------------------------------------------------------------------

    /**
     * Pergunta ao JEV, para cada lacuna opcional, se vale perguntá-la à pessoa ({@code noul}). Devolve as chaves que não entram no
     * cartão: só em {@code ATIVO}, com a {@code noul} abaixo do limiar. Na dúvida, ou se o JEV falhou, a lacuna é perguntada.
     */
    public Set<String> lacunas(AtlasContexto contexto, String mensagem, List<LacunaOpcional> opcionais) {
        ModoDoJev modo = modo(contexto, PontoDoJev.LACUNAS);
        if (!modo.consulta() || opcionais.isEmpty()) {
            return Set.of();
        }
        double limiar = config.limiar(PontoDoJev.LACUNAS);
        Etapa etapa = abrirEtapa(contexto, PontoDoJev.LACUNAS, modo);
        int concedidas = contexto.jev().reservar(opcionais.size());
        if (concedidas == 0) {
            etapa.dado("motivo", "ORCAMENTO_ESGOTADO").fim(ResultadoDaEtapa.PULADO);
            return Set.of();
        }
        List<LacunaOpcional> julgadas = opcionais.subList(0, concedidas);
        List<PedidoAoJev> pedidos = julgadas.stream().map(l -> new PedidoAoJev(mascarar(contexto, perguntas.estado(PontoDoJev.LACUNAS,
                Map.of("mensagem", mensagem, "campo", l.rotulo()))), perguntas.perguntas(PontoDoJev.LACUNAS))).toList();
        List<Chamada> chamadas = chamarEmParalelo(pedidos);
        Set<String> dispensadas = new LinkedHashSet<>();
        List<Map<String, Object>> itens = new ArrayList<>();
        int comErro = 0;
        for (int i = 0; i < julgadas.size(); i++) {
            LacunaOpcional lacuna = julgadas.get(i);
            Chamada chamada = chamadas.get(i);
            RespostaDaPergunta r = chamada.erro() != null ? null : chamada.resposta().respostas().get("vale_perguntar");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("alvo", lacuna.chave());
            item.put("latenciaMs", chamada.ms());
            if (r == null || r.noul() == null) {
                String erro = chamada.erro() != null ? chamada.erro() : "RESPOSTA_INCOMPLETA";
                comErro++;
                item.put("erro", erro);
                item.put("acao", "não agiu: o JEV não respondeu, a lacuna é perguntada");
                registrarFalha(contexto, PontoDoJev.LACUNAS, modo, limiar, "vale_perguntar", lacuna.chave(), erro, chamada.ms());
            } else {
                boolean tirar = r.noul() < limiar;
                String texto = tirar ? textoDaAcao(modo, "tirou a lacuna do cartão", "tirar a lacuna do cartão", null)
                        : textoDaAcao(modo, "manteve a lacuna no cartão", "manter a lacuna no cartão", null);
                if (tirar && modo == ModoDoJev.ATIVO) {
                    dispensadas.add(lacuna.chave());
                }
                item.put("noul", r.noul());
                item.put("acao", texto);
                contexto.jev().adicionar(new Decisao(PontoDoJev.LACUNAS, modo, limiar, "vale_perguntar", null, r.noul(), null,
                        provedor.modelo(), chamada.ms(), null, texto, lacuna.chave()));
            }
            itens.add(item);
        }
        etapa.dado("requisicoes", julgadas.size()).dado("itens", itens)
                .fim(comErro == julgadas.size() ? ResultadoDaEtapa.ERRO
                        : modo == ModoDoJev.ATIVO ? (dispensadas.isEmpty() ? ResultadoDaEtapa.OK : ResultadoDaEtapa.RECUSADO) : ResultadoDaEtapa.SOMBRA);
        return dispensadas;
    }

    // ---------------------------------------------------------------------
    // Gravar as decisões
    // ---------------------------------------------------------------------

    /**
     * Grava em {@code atlas_decisao} as decisões do turno que ainda não foram gravadas, numa transação curta, ligadas à última mensagem
     * da pessoa na conversa. Falha de gravação é só log: o registro do JEV nunca derruba o turno. Seguro de chamar mais de uma vez.
     */
    public void gravar(AtlasContexto contexto) {
        List<Decisao> pendentes = contexto.jev().esvaziar();
        if (pendentes.isEmpty()) {
            return;
        }
        try {
            Optional<Long> mensagem = repositorio.idDaUltimaMensagemDaPessoa(contexto.conversaId());
            if (mensagem.isEmpty()) {
                return;
            }
            java.util.UUID turno = contexto.traco().turno();
            transacao.executeWithoutResult(estado -> {
                for (Decisao d : pendentes) {
                    repositorio.inserirDecisao(mensagem.get(), turno, d.ponto.nome(), d.modo.name(), d.limiar, d.pergunta, d.escolha,
                            d.confianca, d.probabilidades == null ? null : AtlasJson.escrever(d.probabilidades), d.modelo, d.decisaoDoLlm,
                            d.latenciaMs, d.erro, d.acao, d.alvo);
                }
            });
        } catch (RuntimeException falha) {
            log.warn("Atlas: as decisões do JEV da conversa {} não puderam ser gravadas ({})", contexto.conversaId(),
                    falha.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------------
    // Apoio
    // ---------------------------------------------------------------------

    private Etapa abrirEtapa(AtlasContexto contexto, PontoDoJev ponto, ModoDoJev modo) {
        return contexto.traco().abrir(ponto.etapa()).dado("ponto", ponto.nome()).dado("modo", modo.name())
                .dado("limiar", config.limiar(ponto)).dado("modelo", provedor.modelo());
    }

    /** O estado já mascarado: e-mail, telefone, CPF e o nome da marca da conversa viram {@code [removido]}. */
    private String mascarar(AtlasContexto contexto, String estado) {
        String marca = contexto.jev().marca();
        if (marca == null) {
            marca = repositorio.nomeDaMarca(contexto.parceriaId());
            contexto.jev().marca(marca);
        }
        return MascaraDoJev.mascarar(estado, List.of(marca));
    }

    private Chamada chamar(PedidoAoJev pedido) {
        long inicio = System.nanoTime();
        try {
            RespostaDoJev resposta = provedor.perguntar(pedido);
            return new Chamada(resposta, null, (System.nanoTime() - inicio) / 1_000_000);
        } catch (JevIndisponivelException falha) {
            return new Chamada(null, falha.motivo(), (System.nanoTime() - inicio) / 1_000_000);
        } catch (RuntimeException falha) {
            log.warn("Atlas: o JEV falhou por um motivo inesperado ({})", falha.getClass().getSimpleName());
            return new Chamada(null, "ERRO_" + falha.getClass().getSimpleName(), (System.nanoTime() - inicio) / 1_000_000);
        }
    }

    /** As requisições em paralelo, na ordem dos pedidos: uma rodada de espera. Uma que não volta no tempo é erro, e as outras valem. */
    private List<Chamada> chamarEmParalelo(List<PedidoAoJev> pedidos) {
        List<Future<Chamada>> futuros = new ArrayList<>();
        for (PedidoAoJev pedido : pedidos) {
            futuros.add(emParalelo.submit(() -> chamar(pedido)));
        }
        List<Chamada> chamadas = new ArrayList<>();
        long limite = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ESPERA_MAXIMA_DA_RODADA_MS);
        for (Future<Chamada> futuro : futuros) {
            try {
                chamadas.add(futuro.get(Math.max(1, limite - System.nanoTime()), TimeUnit.NANOSECONDS));
            } catch (TimeoutException esgotou) {
                futuro.cancel(true);
                chamadas.add(new Chamada(null, "TIMEOUT", ESPERA_MAXIMA_DA_RODADA_MS));
            } catch (InterruptedException interrompida) {
                futuro.cancel(true);
                Thread.currentThread().interrupt();
                chamadas.add(new Chamada(null, "INTERROMPIDO", 0));
            } catch (ExecutionException falhou) {
                chamadas.add(new Chamada(null, "ERRO_" + falhou.getCause().getClass().getSimpleName(), 0));
            }
        }
        return chamadas;
    }

    /** A resposta da pergunta, se veio e tem uma escolha que existe nas opções; nula se o JEV falhou ou respondeu fora do combinado. */
    private RespostaDaPergunta valida(Chamada chamada, String nome, PontoDoJev ponto, PedidoAoJev pedido) {
        if (chamada.erro() != null) {
            return null;
        }
        RespostaDaPergunta resposta = chamada.resposta().respostas().get(nome);
        PerguntaAoJev pergunta = pedido.perguntas().get(nome);
        if (resposta == null || resposta.escolha() == null || resposta.confianca() == null || pergunta == null
                || !pergunta.criterios().containsKey(resposta.escolha())) {
            return null;
        }
        return resposta;
    }

    private void registrarFalha(AtlasContexto contexto, PontoDoJev ponto, ModoDoJev modo, double limiar, String pergunta, String alvo,
                                String erro, long ms) {
        contexto.jev().adicionar(new Decisao(ponto, modo, limiar, pergunta, null, null, null, provedor.modelo(), ms, erro,
                "não agiu: o JEV não respondeu (" + erro + "), o turno segue o modelo", alvo));
    }

    /** "fez: X" em ATIVO, "teria feito: X" em SOMBRA; sem ação, "não agiu: motivo". */
    private static String textoDaAcao(ModoDoJev modo, String feita, String teria, String semAcao) {
        if (feita == null) {
            return "não agiu: " + semAcao;
        }
        return modo == ModoDoJev.ATIVO ? "fez: " + feita : "teria feito: " + teria;
    }
}
