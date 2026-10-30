package br.com.rbs.portal.atlas;

import br.com.rbs.portal.dados.DadosDtos.ValorRequisicao;
import br.com.rbs.portal.dados.DadosDtos.VigenteDto;
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

    /** A resposta das rotas 42, 44, 45 e 46. 42, 44 e 46 trazem só os itens do turno; a 45, a conversa inteira. */
    public record ConversaDto(Long conversaId, boolean llmDisponivel, List<ItemDaConversa> itens) {
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
            @JsonSubTypes.Type(value = ItemAviso.class, name = "aviso"),
            @JsonSubTypes.Type(value = ItemCartao.class, name = "cartao"),
            @JsonSubTypes.Type(value = ItemRelatorioPronto.class, name = "relatorio_pronto")})
    public sealed interface ItemDaConversa permits ItemUsuario, ItemRaciocinio, ItemTexto, ItemAviso, ItemCartao, ItemRelatorioPronto {
    }

    /** O que a pessoa disse (o botão aparece como "Montar o relatório da ativação"). */
    public record ItemUsuario(String texto) implements ItemDaConversa {
    }

    /**
     * O raciocínio do modelo ({@code reasoning_content}) de uma mensagem do assistente, cru: NÃO passa pela checagem do texto (a
     * tela o mostra rotulado como não verificado). Vem antes dos itens da mesma mensagem; raciocínio vazio não gera item.
     */
    public record ItemRaciocinio(String texto) implements ItemDaConversa {
    }

    /**
     * O que o Atlas disse, com os marcadores {@code {{ref}}} já trocados pelos valores. {@code porModelo} é verdadeiro só quando o
     * texto foi escrito pelo modelo de linguagem; falso nas frases fixas do servidor (o plano B do botão, a troca de um texto
     * recusado, a falha do modelo, o limite e o prazo do turno).
     */
    public record ItemTexto(String texto, boolean porModelo) implements ItemDaConversa {
    }

    /** Um aviso do sistema (por exemplo, o cartão que foi fechado). */
    public record ItemAviso(String texto) implements ItemDaConversa {
    }

    /** O cartão de perguntas, com o estado de agora (PENDENTE, RESPONDIDA, RECUSADA ou CANCELADA). */
    public record ItemCartao(Long solicitacaoId, Long relatorioId, String mensagem, String status, List<CampoDoCartaoDto> campos)
            implements ItemDaConversa {
    }

    /** O relatório que ficou pronto; o front abre o A4 pela rota 47. */
    public record ItemRelatorioPronto(Long relatorioId, String titulo) implements ItemDaConversa {
    }

    /** Uma conversa da lista da rota 43. */
    public record ConversaResumoDto(Long id, OffsetDateTime criadaEm, OffsetDateTime atualizadaEm, RelatorioDaConversaDto relatorio) {
    }

    /** O relatório mais novo de uma conversa da lista (ou nulo, se ela ainda não tem). */
    public record RelatorioDaConversaDto(Long id, String status) {
    }

    /** A resposta da rota 43. */
    public record ConversasDaParceriaDto(boolean llmDisponivel, List<ConversaResumoDto> conversas) {
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
