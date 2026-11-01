package br.com.rbs.portal.atlas.ferramentas;

import static br.com.rbs.portal.atlas.ferramentas.Argumentos.desconhecidos;
import static br.com.rbs.portal.atlas.ferramentas.Argumentos.exigirSemErros;
import static br.com.rbs.portal.atlas.ferramentas.Argumentos.lista;
import static br.com.rbs.portal.atlas.ferramentas.Argumentos.texto;

import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeRelatorio;
import br.com.rbs.portal.atlas.ModoDoAtlas;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.jev.PontoDoJev;
import br.com.rbs.portal.atlas.jev.ServicoDoJev;
import br.com.rbs.portal.atlas.jev.ServicoDoJev.LacunaOpcional;
import br.com.rbs.portal.atlas.jev.ServicoDoJev.ParagrafoParaJulgar;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.DefinicaoDeFerramenta;
import br.com.rbs.portal.atlas.relatorio.ConsultaDaParceria;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.Lacuna;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.CartaoAberto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.OperacaoDeTexto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.RelatorioFinalizado;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.ReferenciaDesconhecidaException;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.SemPermissaoException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * As ferramentas que o modelo de linguagem chama (plano, seção 10.2): {@code relatorio_iniciar},
 * {@code relatorio_solicitar_campos}, {@code relatorio_editar} (só {@code definir_texto}) e {@code relatorio_finalizar}, e a
 * consulta só de leitura {@code parceria_consultar}, que devolve os números da parceria sem abrir relatório.
 * [ATLAS] [ISOLAMENTO] São um embrulho fino do {@link RelatorioAtlasService} (o mesmo que o plano B usa) e da
 * {@link ConsultaDaParceria}. Três regras:
 * <ul>
 *   <li><b>Nenhuma recebe parceria.</b> A parceria, o usuário e a conversa vêm do {@link AtlasContexto} (sessão e
 *       VerificadorDeAcesso). O relatório sobre o qual a ferramenta age é o que está em montagem NESTA conversa. Argumento que
 *       não está no esquema (um {@code parceria_id}, por exemplo) é recusado, e o resultado não muda de parceria nunca.</li>
 *   <li><b>A validação dos argumentos é nossa</b>, em Java (o modo {@code strict} dos provedores é beta e não vale aqui): tipo,
 *       tamanho e argumento desconhecido. O JSON Schema que vai ao modelo só o orienta.</li>
 *   <li><b>O servidor oferece só as ferramentas do estado atual</b> (seção 10.1): quem escolhe é o
 *       {@link RegistroDeFerramentas}, que recebe daqui as cinco ({@link #ferramentas()}) e as junta às outras do Atlas (a busca
 *       nos documentos, desde o M4). Com o cartão aberto, nenhuma é oferecida (o loop nem chama o modelo).</li>
 * </ul>
 * <p>
 * <b>O JEV (M7)</b> entra na fase 1 (sem transação) de duas delas: em {@code relatorio_editar}, o ponto {@code coerencia} confere se o
 * texto de cada parágrafo diz o contrário dos selos do servidor ("acima do previsto", "abaixo do previsto"; sem selo, não roda) e, em
 * {@code ATIVO}, o incoerente é recusado como qualquer texto recusado (nova redação; na segunda, a frase-modelo); em
 * {@code relatorio_solicitar_campos}, o ponto {@code lacunas} pergunta, por lacuna <b>opcional</b>, se vale perguntar, e a que ficar
 * abaixo do limiar não entra no cartão (na dúvida, pergunta; as obrigatórias nunca saem). O botão (plano B, sem modelo) não consulta o JEV.
 * <p>
 * Erro de ferramenta não é exceção para quem chama o modelo: {@link #erroComoJson} o transforma na mensagem de ferramenta que
 * volta ao modelo, e o loop nunca devolve 500 por causa dele.
 */
@Component
public class RelatorioFerramentas {

    public static final String INICIAR = "relatorio_iniciar";
    public static final String SOLICITAR_CAMPOS = "relatorio_solicitar_campos";
    public static final String EDITAR = "relatorio_editar";
    public static final String FINALIZAR = "relatorio_finalizar";
    public static final String CONSULTAR = "parceria_consultar";

    private static final Logger log = LoggerFactory.getLogger(RelatorioFerramentas.class);

    private static final DefinicaoDeFerramenta DEFINICAO_INICIAR = new DefinicaoDeFerramenta(INICIAR,
            "Começa o relatório de Ativação no evento desta parceria a partir do modelo pronto. Devolve as seções, os dados que "
                    + "já existem (cada um com a referência ref, que você usa nos textos como marcador {{ref}}), os blocos que você "
                    + "escreve e as lacunas: números que só a marca sabe. Chame uma vez por relatório.",
            """
            {"type":"object","properties":{
              "modelo":{"type":"string","enum":["ATIVACAO_EVENTO"],"description":"O modelo do relatório."},
              "objetivo":{"type":"string","maxLength":300,"description":"O que a pessoa quer, em uma frase curta e sem números."}},
             "required":["modelo"],"additionalProperties":false}""");

    private static final DefinicaoDeFerramenta DEFINICAO_SOLICITAR = new DefinicaoDeFerramenta(SOLICITAR_CAMPOS,
            "Abre um cartão de formulário para a pessoa informar números que o portal não tem. Peça só campos da lista de lacunas, "
                    + "todos de uma vez, e no máximo três cartões por relatório. A conversa para até a pessoa responder; a resposta "
                    + "volta como { acao, campos }, com acao accept, decline ou cancel.",
            """
            {"type":"object","properties":{
              "mensagem":{"type":"string","maxLength":400,"description":"O que você diz à pessoa no cartão, em português, sem número (nem por extenso), sem link nem chave."},
              "campos":{"type":"array","minItems":1,"maxItems":8,"items":{"type":"string"},
                        "description":"As chaves das lacunas (campo chave), por exemplo ativacao.visitantes_total."}},
             "required":["mensagem","campos"],"additionalProperties":false}""");

    private static final DefinicaoDeFerramenta DEFINICAO_EDITAR = new DefinicaoDeFerramenta(EDITAR,
            "Escreve o texto dos parágrafos do relatório. Cada operação definir_texto leva o id de um bloco redigível e o texto: no "
                    + "máximo três frases e 600 caracteres, sem HTML, link ou URL, e sem nenhum algarismo nem número por extenso. "
                    + "Onde o texto precisar de um dado, escreva o marcador {{ref}} com uma referência que as ferramentas "
                    + "devolveram (por exemplo {{dado:ativacao.visitantes_total}}); o servidor o troca pelo valor. Devolve o "
                    + "resultado de cada operação e os blocos que ainda estão sem texto.",
            """
            {"type":"object","properties":{
              "operacoes":{"type":"array","minItems":1,"maxItems":10,"items":{"type":"object","properties":{
                "op":{"type":"string","enum":["definir_texto"]},
                "bloco":{"type":"string","description":"O id de um bloco redigível, como veio em relatorio_iniciar."},
                "texto":{"type":"string","maxLength":600,"description":"O texto, só com marcadores no lugar dos dados."}},
               "required":["op","bloco","texto"],"additionalProperties":false}}},
             "required":["operacoes"],"additionalProperties":false}""");

    private static final DefinicaoDeFerramenta DEFINICAO_FINALIZAR = new DefinicaoDeFerramenta(FINALIZAR,
            "Fecha o relatório quando todo bloco redigível tem texto e as lacunas obrigatórias foram respondidas ou recusadas no "
                    + "cartão. Depois dela o relatório está pronto e não muda mais.",
            """
            {"type":"object","properties":{},"additionalProperties":false}""");

    private static final DefinicaoDeFerramenta DEFINICAO_CONSULTAR = new DefinicaoDeFerramenta(CONSULTAR,
            "Só leitura: devolve os números que o portal já tem desta parceria, sem abrir relatório. Use para responder no chat "
                    + "perguntas como quantas aplicações de marca foram comprovadas. Cada dado vem com a referência ref, o rótulo, "
                    + "o valor, a unidade e a origem; os que ainda não têm valor vêm em indisponiveis, com o motivo. Na resposta, "
                    + "cite o dado só com o marcador {{ref}} (o servidor o troca pelo valor), nunca com o valor escrito. Não recebe "
                    + "argumento.",
            """
            {"type":"object","properties":{},"additionalProperties":false}""");

    private static final Set<String> ARGUMENTOS_DE_INICIAR = Set.of("modelo", "objetivo");
    private static final Set<String> ARGUMENTOS_DE_SOLICITAR = Set.of("mensagem", "campos");
    private static final Set<String> ARGUMENTOS_DE_EDITAR = Set.of("operacoes");
    private static final Set<String> ARGUMENTOS_DA_OPERACAO = Set.of("op", "bloco", "texto");
    private static final String OPERACAO_DEFINIR_TEXTO = "definir_texto";

    private final RelatorioAtlasService relatorios;
    private final ConsultaDaParceria consulta;
    private final AtlasRepositorio repositorio;
    private final ModoDoAtlas modo;
    private final ServicoDoJev jev;

    public RelatorioFerramentas(RelatorioAtlasService relatorios, ConsultaDaParceria consulta, AtlasRepositorio repositorio,
                                ModoDoAtlas modo, ServicoDoJev jev) {
        this.relatorios = relatorios;
        this.consulta = consulta;
        this.repositorio = repositorio;
        this.modo = modo;
        this.jev = jev;
    }

    // ---------------------------------------------------------------------
    // As ferramentas (o RegistroDeFerramentas escolhe quais oferecer em cada estado)
    // ---------------------------------------------------------------------

    /**
     * As cinco ferramentas desta classe, na ordem em que o registro as oferece: a consulta (sempre), e, com o relatório ligado,
     * {@code relatorio_iniciar} (sem relatório em andamento) ou as outras três (em montagem, {@code relatorio_solicitar_campos}
     * só enquanto couber outro cartão). Com o cartão aberto nenhuma é oferecida (o loop nem chama o modelo). Com o relatório
     * desligado ({@code atlas.relatorio.ligado=false}) só a consulta existe: histórico antigo pode induzir o modelo a chamar uma
     * {@code relatorio_*}, e isso volta como erro de ferramenta, sem criar nada.
     */
    List<Ferramenta> ferramentas() {
        return List.of(
                new FerramentaDoRelatorio(CONSULTAR, 10, DEFINICAO_CONSULTAR, e -> !e.cartaoAberto(), false, null,
                        (contexto, argumentos, ativo, chamadaId, preparado) -> consultar(contexto, argumentos)),
                new FerramentaDoRelatorio(INICIAR, 100, DEFINICAO_INICIAR, EstadoDaConversa::semRelatorio, true, null,
                        (contexto, argumentos, ativo, chamadaId, preparado) -> iniciar(contexto, argumentos)),
                new FerramentaDoRelatorio(SOLICITAR_CAMPOS, 110, DEFINICAO_SOLICITAR, e -> e.montando() && e.podePedirCartao(), true,
                        this::julgarLacunas,
                        (contexto, argumentos, ativo, chamadaId, preparado) -> solicitarCampos(contexto, argumentos, exigirMontando(ativo),
                                chamadaId, dispensadas(preparado))),
                new FerramentaDoRelatorio(EDITAR, 120, DEFINICAO_EDITAR, EstadoDaConversa::montando, true, this::julgarCoerencia,
                        (contexto, argumentos, ativo, chamadaId, preparado) -> editar(contexto, argumentos, exigirMontando(ativo),
                                recusasDoJev(preparado))),
                new FerramentaDoRelatorio(FINALIZAR, 130, DEFINICAO_FINALIZAR, EstadoDaConversa::montando, true, null,
                        (contexto, argumentos, ativo, chamadaId, preparado) -> finalizar(contexto, argumentos, exigirMontando(ativo))));
    }

    /** Como cada ferramenta do relatório roda: com o relatório em andamento da conversa, se houver, e o que a fase 1 preparou. */
    @FunctionalInterface
    private interface Corpo {

        Execucao rodar(AtlasContexto contexto, Map<String, Object> argumentos, Optional<LinhaDeRelatorio> ativo, String chamadaId,
                       Object preparado);
    }

    /** A fase 1 de uma ferramenta do relatório (sem transação): onde o JEV é consultado. */
    @FunctionalInterface
    private interface Preparador {

        Object preparar(AtlasContexto contexto, FontesDoTurno turno, Map<String, Object> argumentos);
    }

    /** Uma das cinco ferramentas; só a {@code relatorio_solicitar_campos} e a {@code relatorio_editar} têm fase de preparo (o JEV). */
    private final class FerramentaDoRelatorio implements Ferramenta {

        private final String nome;
        private final int ordem;
        private final DefinicaoDeFerramenta definicao;
        private final java.util.function.Predicate<EstadoDaConversa> oferecida;
        private final boolean doRelatorio;
        private final Preparador preparador;
        private final Corpo corpo;

        FerramentaDoRelatorio(String nome, int ordem, DefinicaoDeFerramenta definicao,
                              java.util.function.Predicate<EstadoDaConversa> oferecida, boolean doRelatorio, Preparador preparador,
                              Corpo corpo) {
            this.nome = nome;
            this.ordem = ordem;
            this.definicao = definicao;
            this.oferecida = oferecida;
            this.doRelatorio = doRelatorio;
            this.preparador = preparador;
            this.corpo = corpo;
        }

        @Override
        public String nome() {
            return nome;
        }

        @Override
        public DefinicaoDeFerramenta definicao() {
            return definicao;
        }

        @Override
        public int ordem() {
            return ordem;
        }

        @Override
        public boolean oferecida(EstadoDaConversa estado) {
            return (!doRelatorio || estado.relatorioLigado()) && oferecida.test(estado);
        }

        @Override
        public Object preparar(AtlasContexto contexto, FontesDoTurno turno, Map<String, Object> argumentos) {
            if (preparador == null || (doRelatorio && !modo.relatorioLigado())) {
                return null;
            }
            return preparador.preparar(contexto, turno, argumentos);
        }

        @Override
        public Execucao executar(AtlasContexto contexto, FontesDoTurno turno, Map<String, Object> argumentos, String chamadaId,
                                 Object preparado) {
            if (doRelatorio && !modo.relatorioLigado()) {
                // Histórico antigo pode induzir o modelo a chamar uma ferramenta do relatório: erro de ferramenta, nada é criado.
                throw new DadosInvalidosException(ferramentaInexistente(nome), Map.of());
            }
            return corpo.rodar(contexto, argumentos, doRelatorio ? repositorio.relatorioAtivoDaConversa(contexto.conversaId())
                    : Optional.empty(), chamadaId, preparado);
        }
    }

    static String ferramentaInexistente(String nome) {
        return "Não existe a ferramenta " + nome + ". Use só as ferramentas oferecidas.";
    }

    private Execucao consultar(AtlasContexto contexto, Map<String, Object> argumentos) {
        Map<String, String> erros = new LinkedHashMap<>();
        desconhecidos(argumentos, Set.of(), erros);
        exigirSemErros(erros);
        return Execucao.resultado(AtlasJson.escrever(consulta.consultar(contexto)));
    }

    private Execucao iniciar(AtlasContexto contexto, Map<String, Object> argumentos) {
        Map<String, String> erros = new LinkedHashMap<>();
        desconhecidos(argumentos, ARGUMENTOS_DE_INICIAR, erros);
        String modelo = texto(argumentos, "modelo", true, erros);
        String objetivo = texto(argumentos, "objetivo", false, erros);
        exigirSemErros(erros);
        return Execucao.resultado(AtlasJson.escrever(relatorios.iniciar(contexto, modelo, objetivo)));
    }

    private Execucao solicitarCampos(AtlasContexto contexto, Map<String, Object> argumentos, LinhaDeRelatorio relatorio, String chamadaId,
                                     Set<String> dispensadas) {
        Map<String, String> erros = new LinkedHashMap<>();
        desconhecidos(argumentos, ARGUMENTOS_DE_SOLICITAR, erros);
        String mensagem = texto(argumentos, "mensagem", true, erros);
        List<String> campos = lista(argumentos, "campos", erros);
        exigirSemErros(erros);
        if (!dispensadas.isEmpty()) {
            // O JEV (ativo) achou que não vale perguntar estas lacunas opcionais: elas não entram no cartão. Se eram só elas, não há cartão.
            List<String> restantes = campos.stream().filter(chave -> !dispensadas.contains(chave)).toList();
            if (restantes.isEmpty()) {
                Map<String, Object> dispensado = new LinkedHashMap<>();
                dispensado.put("cartao", "dispensado");
                dispensado.put("campos", new ArrayList<>(dispensadas));
                dispensado.put("aviso", "Estes campos são opcionais e não valem uma pergunta agora: siga sem abrir o cartão.");
                return Execucao.resultado(AtlasJson.escrever(dispensado));
            }
            campos = restantes;
        }
        CartaoAberto cartao = relatorios.solicitarCampos(contexto, relatorio.id(), mensagem, campos, chamadaId);
        log.debug("Atlas: cartão {} aberto na conversa {}", cartao.solicitacaoId(), contexto.conversaId());
        return new Execucao(null, true, false);
    }

    private Execucao editar(AtlasContexto contexto, Map<String, Object> argumentos, LinhaDeRelatorio relatorio,
                            Map<String, String> recusasDoJev) {
        Map<String, String> erros = new LinkedHashMap<>();
        desconhecidos(argumentos, ARGUMENTOS_DE_EDITAR, erros);
        List<OperacaoDeTexto> operacoes = new ArrayList<>();
        Object bruto = argumentos.get("operacoes");
        if (!(bruto instanceof List<?> itens) || itens.isEmpty() || itens.size() > RelatorioAtlasService.MAXIMO_DE_OPERACOES_POR_EDICAO) {
            erros.put("operacoes", "Envie de 1 a " + RelatorioAtlasService.MAXIMO_DE_OPERACOES_POR_EDICAO + " operações.");
        } else {
            for (int i = 0; i < itens.size(); i++) {
                if (!(itens.get(i) instanceof Map<?, ?> operacao)) {
                    erros.put("operacoes[" + i + "]", "A operação tem de ser um objeto com op, bloco e texto.");
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> campos = (Map<String, Object>) operacao;
                String prefixo = "operacoes[" + i + "].";
                Map<String, String> errosDaOperacao = new LinkedHashMap<>();
                desconhecidos(campos, ARGUMENTOS_DA_OPERACAO, errosDaOperacao);
                String op = texto(campos, "op", true, errosDaOperacao);
                if (op != null && !OPERACAO_DEFINIR_TEXTO.equals(op)) {
                    errosDaOperacao.put("op", "Só existe a operação " + OPERACAO_DEFINIR_TEXTO + ".");
                }
                String bloco = texto(campos, "bloco", true, errosDaOperacao);
                String texto = texto(campos, "texto", true, errosDaOperacao);
                errosDaOperacao.forEach((campo, motivo) -> erros.put(prefixo + campo, motivo));
                if (errosDaOperacao.isEmpty()) {
                    operacoes.add(new OperacaoDeTexto(bloco, texto));
                }
            }
        }
        exigirSemErros(erros);
        return Execucao.resultado(AtlasJson.escrever(relatorios.definirTextos(contexto, relatorio.id(), operacoes, recusasDoJev)));
    }

    private Execucao finalizar(AtlasContexto contexto, Map<String, Object> argumentos, LinhaDeRelatorio relatorio) {
        Map<String, String> erros = new LinkedHashMap<>();
        desconhecidos(argumentos, Set.of(), erros);
        exigirSemErros(erros);
        RelatorioFinalizado pronto = relatorios.finalizar(contexto, relatorio.id());
        return new Execucao(pronto.resultadoJson(), false, true);
    }

    // ---------------------------------------------------------------------
    // O JEV (M7): a fase 1 de relatorio_editar e de relatorio_solicitar_campos
    // ---------------------------------------------------------------------

    /**
     * O ponto {@code coerencia}, antes de {@code relatorio_editar} gravar: o texto de cada parágrafo contra os selos do servidor. Devolve
     * as recusas por bloco ({@code Map<String, String>}), ou nulo quando o ponto está desligado, o relatório não está em montagem ou
     * os argumentos não prestam (o {@code executar} os recusa em palavras). Sem selo no relatório, o JEV não é consultado.
     */
    private Object julgarCoerencia(AtlasContexto contexto, FontesDoTurno turno, Map<String, Object> argumentos) {
        if (!jev.modo(contexto, PontoDoJev.COERENCIA).consulta() || !(argumentos.get("operacoes") instanceof List<?> operacoes)) {
            return null;
        }
        Optional<LinhaDeRelatorio> ativo = repositorio.relatorioAtivoDaConversa(contexto.conversaId());
        if (ativo.isEmpty() || !"MONTANDO".equals(ativo.get().status())) {
            return null;
        }
        List<ParagrafoParaJulgar> paragrafos = new ArrayList<>();
        for (Object operacao : operacoes) {
            if (operacao instanceof Map<?, ?> campos && campos.get("bloco") instanceof String bloco
                    && campos.get("texto") instanceof String texto && !texto.isBlank()) {
                paragrafos.add(new ParagrafoParaJulgar(bloco, texto));
            }
        }
        if (paragrafos.isEmpty()) {
            return null;
        }
        return jev.coerencia(contexto, relatorios.selosDoRelatorio(contexto, ativo.get().id()), paragrafos);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> recusasDoJev(Object preparado) {
        return preparado instanceof Map<?, ?> mapa ? (Map<String, String>) mapa : Map.of();
    }

    /**
     * O ponto {@code lacunas}, antes de {@code relatorio_solicitar_campos} abrir o cartão: das lacunas opcionais que o modelo pediu e
     * que ainda não têm valor, as que o JEV acha que não valem a pergunta. Devolve as chaves a tirar do cartão, ou nulo.
     */
    private Object julgarLacunas(AtlasContexto contexto, FontesDoTurno turno, Map<String, Object> argumentos) {
        if (!jev.modo(contexto, PontoDoJev.LACUNAS).consulta() || !(argumentos.get("campos") instanceof List<?> campos)) {
            return null;
        }
        Optional<LinhaDeRelatorio> ativo = repositorio.relatorioAtivoDaConversa(contexto.conversaId());
        if (ativo.isEmpty() || !"MONTANDO".equals(ativo.get().status())) {
            return null;
        }
        Set<Object> pedidas = new LinkedHashSet<>(campos);
        List<LacunaOpcional> opcionais = relatorios.lacunas(contexto, ativo.get().id()).stream()
                .filter(lacuna -> !lacuna.obrigatoria() && pedidas.contains(lacuna.chave()))
                .map((Lacuna lacuna) -> new LacunaOpcional(lacuna.chave(), lacuna.rotulo())).toList();
        if (opcionais.isEmpty()) {
            return null;
        }
        return jev.lacunas(contexto, turno.mensagemDaPessoa(), opcionais);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> dispensadas(Object preparado) {
        return preparado instanceof Set<?> conjunto ? (Set<String>) conjunto : Set.of();
    }

    // ---------------------------------------------------------------------
    // Estado
    // ---------------------------------------------------------------------

    private static LinhaDeRelatorio exigirMontando(Optional<LinhaDeRelatorio> ativo) {
        LinhaDeRelatorio relatorio = ativo.orElseThrow(() -> new ConflitoException(
                "Ainda não há relatório sendo montado nesta conversa. Comece com " + INICIAR + "."));
        if (!"MONTANDO".equals(relatorio.status())) {
            throw new ConflitoException(AGUARDANDO_O_CARTAO);
        }
        return relatorio;
    }

    /** A frase de erro de toda ferramenta chamada com o cartão aberto. */
    public static final String AGUARDANDO_O_CARTAO = "Aguardando a resposta do cartão. Nenhuma ferramenta do relatório roda até ela voltar.";

    // ---------------------------------------------------------------------
    // Erro de ferramenta volta ao modelo
    // ---------------------------------------------------------------------

    /**
     * O erro como mensagem de ferramenta ({@code {"erro": "...", "campos": {...}}}), no lugar de uma resposta HTTP de erro. [ATLAS]
     * Só sai a frase que o portal já mostraria à pessoa; erro inesperado vira uma frase genérica (o rastro fica no log).
     */
    public static String erroComoJson(RuntimeException erro, boolean relatorioLigado) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        if (erro instanceof DadosInvalidosException invalidos) {
            corpo.put("erro", invalidos.getMessage());
            if (!invalidos.campos().isEmpty()) {
                corpo.put("campos", invalidos.campos());
            }
        } else if (erro instanceof ConflitoException || erro instanceof NaoEncontradoException || erro instanceof SemPermissaoException
                || erro instanceof ReferenciaDesconhecidaException) {
            corpo.put("erro", erro.getMessage());
        } else {
            log.error("Atlas: uma ferramenta falhou por um motivo inesperado", erro);
            corpo.put("erro", relatorioLigado
                    ? "A ferramenta falhou por um motivo interno. Tente de novo; se continuar, diga à pessoa para usar o botão "
                            + "Montar relatório."
                    : "A ferramenta falhou por um motivo interno. Tente de novo; se continuar, diga à pessoa que não conseguiu "
                            + "buscar esse dado agora.");
        }
        return AtlasJson.escrever(corpo);
    }

    /** O erro que o próprio loop devolve (pausa do cartão, limite de chamadas), no mesmo formato. */
    public static String erroComoJson(String frase) {
        return AtlasJson.escrever(Map.of("erro", frase));
    }
}
