package br.com.rbs.portal.atlas.diagnostico;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Corpos de saída das rotas 49 a 53 do contrato (docs/api.md, "Diagnóstico do Atlas"), em record. [ATLAS] camelCase, datas com o
 * fuso de Brasília. O {@code dados} de cada etapa já vem sanitizado do banco ({@link RegistroDoTraco}) e sai como o objeto JSON que
 * está lá, sem passar por um mapa (os números ficam como foram gravados).
 */
public final class DiagnosticoDtos {

    private DiagnosticoDtos() {
    }

    /** Quem é o dono de uma conversa. */
    public record UsuarioResumoDto(Long id, String nome, String perfil) {
    }

    /** Uma etapa do turno. {@code dados} é um objeto JSON (cru, já sanitizado); {@code mensagemId} é nulo nas etapas sem mensagem. */
    public record EtapaDto(Integer ordem, String etapa, String resultado, OffsetDateTime iniciadaEm, Integer duracaoMs, Long mensagemId,
                           @JsonRawValue String dados) {
    }

    /**
     * Um turno de uma conversa (rota 49 e item da rota 51). {@code iniciadoEm} e {@code duracaoMs} vão do começo da primeira etapa
     * sequencial ao fim da última (o resumo do raciocínio, em paralelo, fica de fora); {@code resultado} é o da etapa {@code SAIDA};
     * {@code mensagemId} é a última mensagem do assistente que o turno gravou. Os tokens e o custo somam as idas ao modelo e os
     * resumos do raciocínio ({@code custoUsd} é {@code 0} quando nenhuma resposta trouxe custo).
     */
    public record TurnoDto(String turno, Long mensagemId, OffsetDateTime iniciadoEm, Integer duracaoMs, String resultado,
                           long tokensEntrada, long tokensSaida, BigDecimal custoUsd, List<EtapaDto> etapas) {
    }

    /** A rota 49: o diagnóstico de uma conversa, com os totais dela. */
    public record DiagnosticoDaConversaDto(Long conversaId, long tokensEntrada, long tokensSaida, long tokens, BigDecimal custoUsd,
                                           List<TurnoDto> turnos) {
    }

    /** Um turno da lista da rota 50, sem as etapas ({@code etapas} é quantas são). */
    public record TurnoDaListaDto(String turno, Long conversaId, Long parceriaId, String parceria, UsuarioResumoDto usuario,
                                  Long mensagemId, OffsetDateTime iniciadoEm, Integer duracaoMs, String resultado, int etapas,
                                  long tokensEntrada, long tokensSaida, BigDecimal custoUsd) {
    }

    /** A rota 51: um turno com a parceria, a conversa e o usuário, e as etapas. */
    public record TurnoDetalhadoDto(String turno, Long conversaId, Long parceriaId, String parceria, UsuarioResumoDto usuario,
                                    Long mensagemId, OffsetDateTime iniciadoEm, Integer duracaoMs, String resultado,
                                    long tokensEntrada, long tokensSaida, BigDecimal custoUsd, List<EtapaDto> etapas) {
    }

    /**
     * Um provedor na rota 52. {@code origem}: {@code AMBIENTE}, {@code DEBUG} (só o modelo de linguagem) ou {@code NENHUM}. Nunca a
     * chave inteira: só os 4 últimos caracteres. {@code modo} é o do JEV ({@code DESLIGADO}, {@code SOMBRA} ou {@code ATIVO}) e
     * nulo nos outros. {@code disjuntor} é nulo até o M8. A latência e os erros são das últimas 24 horas, do traço.
     */
    public record ProvedorDto(String nome, boolean configurado, String origem, String modelo, String baseUrl, String finalDaChave,
                              String modo, long chamadas24h, long erros24h, Long latenciaP50Ms, Long latenciaP95Ms, String disjuntor) {
    }

    /** Quantas mensagens do modelo uma parceria gastou hoje (dia de Brasília). */
    public record UsoDoTetoDto(Long parceriaId, String parceria, long usadas) {
    }

    /** O teto diário por parceria ({@code atlas.teto-diario-por-parceria}) e quem já usou hoje. */
    public record TetoDiarioDto(int limite, List<UsoDoTetoDto> parcerias) {
    }

    /** A rota 52. */
    public record ProvedoresDto(List<ProvedorDto> provedores, TetoDiarioDto tetoDiario) {
    }

    /**
     * A rota 53. {@code chamou} é falso quando não havia chave e nada foi chamado ({@code erro} diz o motivo). Com {@code ok}
     * verdadeiro vem {@code resposta}; com falso, {@code erro} (nunca com a chave).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TesteDeProvedorDto(String provedor, boolean ok, boolean chamou, long milissegundos, String modelo, String resposta,
                                     String erro) {
    }
}
