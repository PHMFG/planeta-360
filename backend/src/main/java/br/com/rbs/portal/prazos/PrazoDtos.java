package br.com.rbs.portal.prazos;

import br.com.rbs.portal.materiais.MateriaisDtos.UsuarioResumoDto;
import br.com.rbs.portal.parceria.ParceriaDtos.CotaDto;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Corpos de entrada e saída das rotas 62 a 64 (prazos do Atlas), todos em record. [ATLAS] O formato é o contrato com o front
 * (docs/api.md e {@code frontend/src/api/prazos.ts}): camelCase, a data como AAAA-MM-DD, a hora como HH:mm, o resto do tempo com o
 * fuso de Brasília.
 */
public final class PrazoDtos {

    private PrazoDtos() {
    }

    /** A parceria de um prazo de escopo PARCERIA. */
    public record ParceriaDoPrazoDto(Long id, String patrocinador) {
    }

    /** O documento de onde o prazo saiu, como está agora: o prazo cujo documento deixou de valer (status) não vai para o Atlas. */
    public record DocumentoDoPrazoDto(Long id, String titulo, Integer versao, String status) {
    }

    /**
     * O prazo como a RBS vê (rotas 62 e 63). {@code cota} só no escopo COTA, {@code parceria} só no PARCERIA, {@code documento} e
     * {@code pagina} só quando o prazo tem origem. {@code hora} é nula quando o prazo vale o dia inteiro.
     */
    public record PrazoDto(Long id, Long eventoId, String escopo, CotaDto cota, ParceriaDoPrazoDto parceria, String titulo, LocalDate data,
                           String hora, String descricao, DocumentoDoPrazoDto documento, Integer pagina, boolean ativo,
                           UsuarioResumoDto criadoPor, OffsetDateTime criadoEm) {
    }

    /**
     * O corpo da rota 63, como chega: tudo opcional na leitura, porque a validação (e a frase de cada campo) é do
     * {@code PrazoService}, que junta os erros numa resposta só. {@code data} e {@code hora} chegam como texto para o 422 dizer o que
     * está errado, em vez de um 400 genérico do JSON.
     */
    public record CriarPrazoRequisicao(String escopo, String cota, Long parceriaId, String titulo, String data, String hora, String descricao,
                                       Long documentoId, Integer pagina) {
    }
}
