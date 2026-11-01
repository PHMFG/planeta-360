package br.com.rbs.portal.atlas;

import br.com.rbs.portal.dados.DadosDtos.ValorRequisicao;
import br.com.rbs.portal.dados.DadosDtos.VigenteDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Corpos de entrada e saída das rotas 42 a 46 (a conversa com o Atlas e o cartão de perguntas), em record. [ATLAS] O formato é o
 * contrato com o front (docs/api.md, frontend/src/api/atlas.ts): camelCase, datas com o fuso de Brasília, cada item da conversa com
 * o seu {@code tipo}. Os itens são DERIVADOS do histórico gravado em {@code atlas_mensagem} (o formato de chat completions): nada
 * é guardado só para a tela. Os relatórios (rotas 47 e 48) estão em {@code relatorio.RelatorioAtlasDtos}.
 */
public final class AtlasDtos {

    /** O atalho do botão "Montar relatório" no corpo das rotas 42 e 44. */
    public static final String ATALHO_MONTAR_RELATORIO = "MONTAR_RELATORIO";

    private AtlasDtos() {
    }

    // ---------------------------------------------------------------------
    // Saída
    // ---------------------------------------------------------------------

    /**
     * A resposta das rotas 42, 44, 45 e 46. 42, 44 e 46 trazem só os itens do turno; a 45, a conversa inteira.
     * {@code relatorioDisponivel} é o {@code atlas.relatorio.ligado}: falso, o Atlas é só o chat. {@code debugDeFerramentas} é o
     * {@code atlas.debug.ferramentas}: verdadeiro, os itens trazem também cada ferramenta que rodou (temporário).
     * {@code diagnosticoDisponivel} é verdadeiro quando a marca vê o diagnóstico das próprias conversas
     * ({@code atlas.diagnostico.ligado} e {@code atlas.diagnostico.marca}): os itens do assistente trazem o {@code turno} e a rota
     * 49 responde.
     */
    public record ConversaDto(Long conversaId, boolean llmDisponivel, boolean relatorioDisponivel, boolean debugDeFerramentas,
                              boolean diagnosticoDisponivel, List<ItemDaConversa> itens) {
    }

    /** Um campo do cartão: o campo do catálogo (rota 39) mais se é obrigatório e o valor vigente (formato da rota 40). */
    public record CampoDoCartaoDto(String chave, String aba, String rotulo, String tipo, String unidade, Number minimo, Number maximo,
                                   String ajuda, String limiteCampo, boolean obrigatorio, VigenteDto vigente) {
    }

    /** Um item da conversa; o tipo vai na propriedade {@code tipo}. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "tipo")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ItemUsuario.class, name = "usuario"),
            @JsonSubTypes.Type(value = ItemRaciocinio.class, name = "raciocinio"),
            @JsonSubTypes.Type(value = ItemTexto.class, name = "texto"),
            @JsonSubTypes.Type(value = ItemFerramenta.class, name = "ferramenta"),
            @JsonSubTypes.Type(value = ItemAviso.class, name = "aviso"),
            @JsonSubTypes.Type(value = ItemCartao.class, name = "cartao"),
            @JsonSubTypes.Type(value = ItemRelatorioPronto.class, name = "relatorio_pronto"),
            @JsonSubTypes.Type(value = ItemCalculo.class, name = "calculo"),
            @JsonSubTypes.Type(value = ItemPedidoRelatorio.class, name = "pedido_relatorio"),
            @JsonSubTypes.Type(value = ItemModelo.class, name = "modelo")})
    public sealed interface ItemDaConversa permits ItemUsuario, ItemRaciocinio, ItemTexto, ItemFerramenta, ItemAviso, ItemCartao,
            ItemRelatorioPronto, ItemCalculo, ItemPedidoRelatorio, ItemModelo {
    }

    /** O que a pessoa disse (o botão aparece como "Montar o relatório da ativação"). */
    public record ItemUsuario(String texto) implements ItemDaConversa {
    }

    /**
     * O resumo do raciocínio do modelo ({@code reasoning_content}) de uma mensagem do assistente, gerado e conferido no servidor
     * (o raciocínio cru nunca sai dele). Vem antes dos itens da mesma mensagem e só existe quando a mensagem tem resumo gravado:
     * mensagem sem raciocínio, com resumo recusado, atrasado ou perdido, e a de antes do resumo, não geram item. {@code turno} é o
     * id do turno do diagnóstico a que a mensagem pertence (só com o diagnóstico à vista da marca; veja {@link ConversaDto}).
     */
    public record ItemRaciocinio(String resumo, @JsonInclude(JsonInclude.Include.NON_NULL) String turno) implements ItemDaConversa {
    }

    /**
     * O que o Atlas disse, com os marcadores {@code {{ref}}} já trocados pelos valores. {@code porModelo} é verdadeiro só quando o
     * texto foi escrito pelo modelo de linguagem; falso nas frases fixas do servidor (o plano B do botão, a troca de um texto
     * recusado, a falha do modelo, o limite e o prazo do turno, e o "não encontrei" com a indicação do atendimento da RBS).
     * {@code fontes} são as fontes que o texto cita (as citações {@code [T1]} continuam no texto, para a tela virar link): só vem
     * quando o texto cita alguma, na ordem em que a primeira citação aparece.
     */
    public record ItemTexto(String texto, boolean porModelo, @JsonInclude(JsonInclude.Include.NON_NULL) String turno,
                            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FonteDoTexto> fontes) implements ItemDaConversa {

        public ItemTexto(String texto, boolean porModelo, String turno) {
            this(texto, porModelo, turno, List.of());
        }
    }

    /**
     * Uma fonte citada num texto do Atlas, derivada de {@code atlas_fonte}. {@code tipo} é {@code TRECHO}, {@code REGISTRO} ou
     * {@code CALCULO}; os campos do documento só vêm no trecho. {@code versao} e {@code pagina} são os da citação;
     * {@code substituido} e {@code revogado} são o estado de agora do documento: substituído abre com aviso, e revogado não abre mais
     * (a rota 59 responde 404).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FonteDoTexto(String marcador, String tipo, Long documentoId, String titulo, Integer versao, Integer pagina,
                               String secao, Long trechoId, boolean substituido, boolean revogado) {
    }

    /**
     * DEBUG (temporário, {@code atlas.debug.ferramentas}): uma ferramenta que o agente chamou. Vem depois do {@code raciocinio} e
     * do {@code texto} da mesma mensagem do assistente, na ordem em que rodou. {@code argumentos} e {@code resultado} são o JSON cru
     * como texto (o que o modelo enviou e o que recebeu), cortados em {@value DadosDaFerramenta#LIMITE_DO_TEXTO} caracteres;
     * {@code resultado} é nulo se a ferramenta ainda não respondeu e {@code duracaoMs} é nulo se não há registro dela.
     */
    public record ItemFerramenta(String nome, String argumentos, String resultado, boolean erro, Integer duracaoMs,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) String turno) implements ItemDaConversa {

        public static ItemFerramenta de(DadosDaFerramenta dados, String turno) {
            return new ItemFerramenta(dados.nome(), dados.argumentos(), dados.resultado(), dados.erro(), dados.duracaoMs(), turno);
        }
    }

    /** O corpo do evento {@code ferramenta} da resposta em fluxo: o mesmo do {@link ItemFerramenta}, sem o {@code tipo}. */
    public record DadosDaFerramenta(String nome, String argumentos, String resultado, boolean erro, Integer duracaoMs) {

        public static final int LIMITE_DO_TEXTO = 20_000;

        /** Os dados com {@code argumentos} e {@code resultado} cortados no limite. */
        public static DadosDaFerramenta cortando(String nome, String argumentos, String resultado, boolean erro, Integer duracaoMs) {
            return new DadosDaFerramenta(nome, cortar(argumentos), cortar(resultado), erro, duracaoMs);
        }

        static String cortar(String texto) {
            if (texto == null || texto.length() <= LIMITE_DO_TEXTO) {
                return texto;
            }
            int fim = Character.isHighSurrogate(texto.charAt(LIMITE_DO_TEXTO - 1)) ? LIMITE_DO_TEXTO - 1 : LIMITE_DO_TEXTO;
            return texto.substring(0, fim);
        }
    }

    /** Um aviso do sistema (por exemplo, o cartão que foi fechado). */
    public record ItemAviso(String texto) implements ItemDaConversa {
    }

    /** O cartão de perguntas, com o estado de agora (PENDENTE, RESPONDIDA, RECUSADA ou CANCELADA). */
    public record ItemCartao(Long solicitacaoId, Long relatorioId, String mensagem, String status, List<CampoDoCartaoDto> campos,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String turno) implements ItemDaConversa {
    }

    /** O relatório que ficou pronto; o front abre o A4 pela rota 47. */
    public record ItemRelatorioPronto(Long relatorioId, String titulo, @JsonInclude(JsonInclude.Include.NON_NULL) String turno)
            implements ItemDaConversa {
    }

    /** Uma variável de uma conta do {@code calcular}: o nome, de onde veio (a referência, o marcador da fonte ou {@code usuario}) e o valor que entrou. */
    public record VariavelDoCalculo(String nome, String origem, String valor) {
    }

    /** Um feriado que a contagem de dias pulou: a data como {@code dd/MM/aaaa} e o nome. */
    public record FeriadoConsiderado(String data, String nome) {
    }

    /**
     * O "Como calculei" (M5): a conta que o {@code calcular} ou o {@code contar_dias} fez no turno, derivada da resposta da ferramenta
     * (sem erro e sem {@code indisponivel}), com o relatório ligado ou desligado. Vem depois do que a mensagem do assistente que a
     * pediu tem, na ordem em que rodou. {@code ferramenta} é {@code calcular} ou {@code contar_dias}; {@code marcador} é o {@code C#} do
     * turno em que a conta foi feita. {@code resultado} é o valor formatado da conta ou o resultado da contagem (a data
     * {@code dd/MM/aaaa} ou a quantidade de dias). No {@code calcular}: {@code nome}, {@code script} e {@code variaveis} (a origem de
     * cada valor); {@code regra} é nula e {@code feriadosConsiderados} vazio. No {@code contar_dias}: {@code regra} e
     * {@code feriadosConsiderados}; {@code nome} e {@code script} nulos e {@code variaveis} vazio.
     */
    public record ItemCalculo(String marcador, String ferramenta, String nome, String script, List<VariavelDoCalculo> variaveis,
                              String resultado, String regra, List<FeriadoConsiderado> feriadosConsiderados,
                              @JsonInclude(JsonInclude.Include.NON_NULL) String turno) implements ItemDaConversa {
    }

    /** Um modelo em branco que o pedido de relatório oferece: o tipo do contrato ({@code BRIEFING_CONTEUDO}...) e o título. */
    public record ModeloOferecido(String tipo, String titulo) {
    }

    /**
     * A resposta do {@code pedido_de_relatorio} (M5), com o relatório ligado ou desligado: o texto fixo do servidor (sem algarismo),
     * se há relatório oficial para esta pessoa ver e os modelos em branco que a parceria pode pedir. Derivada da resposta da
     * ferramenta (resposta com erro não gera item), mas {@code relatorioOficial}, {@code modelos} e o {@code texto} que depende
     * deles são os <b>de agora</b>, relidos a cada leitura: a RBS pode ter disponibilizado o relatório (ou aberto ou fechado a
     * Pesquisa) depois. Vem depois do que a mensagem do assistente que o pediu tem.
     */
    public record ItemPedidoRelatorio(String texto, boolean relatorioOficial, List<ModeloOferecido> modelos,
                                      @JsonInclude(JsonInclude.Include.NON_NULL) String turno) implements ItemDaConversa {
    }

    /**
     * O modelo em branco que o {@code gerar_modelo} entregou (M5), com o relatório ligado ou desligado, como a ferramenta o devolveu
     * (resposta com erro não gera item). {@code conteudo} é Markdown; {@code nomeArquivo} termina em {@code .md}, e o navegador gera
     * o arquivo. O tipo do modelo ({@code BRIEFING_CONTEUDO}...) não vai no item: o {@code tipo} da propriedade é o do item.
     */
    public record ItemModelo(String titulo, String conteudo, String nomeArquivo,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String turno) implements ItemDaConversa {
    }

    /** Uma conversa da lista da rota 43. */
    public record ConversaResumoDto(Long id, OffsetDateTime criadaEm, OffsetDateTime atualizadaEm, RelatorioDaConversaDto relatorio) {
    }

    /** O relatório mais novo de uma conversa da lista (ou nulo, se ela ainda não tem). */
    public record RelatorioDaConversaDto(Long id, String status) {
    }

    /** A resposta da rota 43. */
    public record ConversasDaParceriaDto(boolean llmDisponivel, boolean relatorioDisponivel, List<ConversaResumoDto> conversas) {
    }

    /**
     * Uma conversa da RBS na lista da rota 68. {@code previa} é o começo (até 80 caracteres) da primeira mensagem que a pessoa escreveu
     * nela, ou {@code null} se ainda não escreveu.
     */
    public record ConversaDaRbsResumoDto(Long id, OffsetDateTime criadaEm, OffsetDateTime atualizadaEm, String previa) {
    }

    /** A resposta da rota 68: as conversas de quem pede neste evento. O Atlas da RBS não tem relatório. */
    public record ConversasDaRbsDto(boolean llmDisponivel, List<ConversaDaRbsResumoDto> conversas) {
    }

    // ---------------------------------------------------------------------
    // Entrada (a conferência é do AtlasService, para vir depois do 404 e do 403)
    // ---------------------------------------------------------------------

    /** Rota 42. Corpo opcional: vazio abre uma conversa vazia; {@code atalho} e {@code modelo} abrem e já montam o relatório. */
    public record AbrirConversaRequisicao(String atalho, String modelo) {
    }

    /** Rota 44. Ou {@code texto}, ou {@code atalho} com {@code modelo}. */
    public record MensagemRequisicao(String texto, String atalho, String modelo) {
    }

    /** Rota 46. {@code acao} é accept, decline ou cancel; {@code valores} só vale no accept. */
    public record RespostaDoCartaoRequisicao(String acao, List<ValorRequisicao> valores) {
    }
}
