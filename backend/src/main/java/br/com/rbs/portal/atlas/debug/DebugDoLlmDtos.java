package br.com.rbs.portal.atlas.debug;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * O que entra e sai das rotas de debug do modelo de linguagem ({@code /api/debug/llm}). [ATLAS] Temporárias, só com
 * {@code atlas.debug.chaves=true}. A chave inteira só ENTRA (no PUT); nenhuma resposta a devolve, só os 4 últimos caracteres.
 */
public final class DebugDoLlmDtos {

    private DebugDoLlmDtos() {
    }

    /** Quem pôs a chave. */
    public record UsuarioResumoDto(Long id, String nome, String perfil) {
    }

    /**
     * GET e PUT. {@code origem}: DEBUG, AMBIENTE ou NENHUM. Em AMBIENTE e NENHUM, {@code configuradoPor} e {@code configuradoEm}
     * vêm nulos; em NENHUM, {@code finalDaChave} também, e {@code baseUrl} e {@code modelo} são os padrões do application.yml.
     * {@code raciocinio}: o {@code thinking} do modelo que vale agora (em AMBIENTE e NENHUM, o {@code atlas.raciocinio}).
     */
    public record EstadoDto(String origem, String baseUrl, String modelo, String finalDaChave, UsuarioResumoDto configuradoPor,
                            OffsetDateTime configuradoEm, boolean llmDisponivel, boolean raciocinio) {
    }

    /**
     * PUT. {@code baseUrl} e {@code modelo} são opcionais: vazios, valem os padrões do application.yml. {@code raciocinio}
     * também: ausente (nulo), fica o da chave do debug que já estava posta, ou o {@code atlas.raciocinio}.
     */
    public record ChaveRequisicao(String chave, String baseUrl, String modelo, Boolean raciocinio) {
    }

    /** POST /teste. Com {@code ok} verdadeiro vem {@code resposta}; com falso, {@code erro} (nunca com a chave). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TesteDto(boolean ok, long milissegundos, String modelo, String resposta, String erro) {
    }
}
