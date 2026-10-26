package br.com.rbs.portal.consolidacao;

import java.time.OffsetDateTime;

/**
 * Formatos de saída das rotas 26 a 28 (cargas de planilha), em record. Atende o item 5 da Entrega 2.
 * O formato é o contrato com o front (docs/api.md): camelCase e datas com o fuso de Brasília.
 */
public final class CargaDtos {

    private CargaDtos() {
    }

    /** Quem fez a carga, como no resto da API: id, nome e perfil. */
    public record UsuarioResumoDto(Long id, String nome, String perfil) {
    }

    /**
     * Uma carga (resposta da rota 26 e item da rota 27). {@code tipo} é VEICULACAO ou PESQUISA (no banco, a veiculação
     * é gravada como MIDIA). Linhas lidas = inseridas + duplicadas + rejeitadas.
     */
    public record CargaDto(Long id, String tipo, Long eventoId, Long arquivoId, UsuarioResumoDto executadaPor,
                           OffsetDateTime executadaEm, OffsetDateTime processadaEm, int linhasLidas, int linhasInseridas,
                           int linhasDuplicadas, int linhasRejeitadas) {
    }

    /** Uma linha que não entrou na carga (rota 28). {@code linha} é o número da linha no arquivo enviado. */
    public record RejeicaoDto(int linha, String campo, String motivo, String conteudo) {
    }
}
