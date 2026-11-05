package br.com.rbs.portal.pesquisa;

import br.com.rbs.portal.pesquisa.PesquisaDtos.UsuarioResumoDto;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Corpos das rotas 92 a 95 (abrir e editar a pesquisa da edição, e a fila das perguntas a aprovar). Todos em record, camelCase.
 * O contrato está em docs/api.md. A validação dos campos fica no {@link PesquisaAberturaService}, para devolver todos os erros
 * de uma vez (422 com {@code campos}).
 */
public final class AberturaDtos {

    private AberturaDtos() {
    }

    /** Quantas perguntas da pesquisa estão em cada estado. */
    public record ContagemDePerguntasDto(int rascunho, int enviadas, int aprovadas) {
    }

    /**
     * Uma pesquisa da edição (itens das rotas 92 a 94). {@code resultados} conta as linhas de resultado carregadas.
     * {@code perguntasLigadas} só vem na abertura (rota 93): quantas perguntas já enviadas pelas marcas, e que estavam sem
     * pesquisa, passaram a valer para esta; nas outras rotas é {@code null}.
     */
    public record PesquisaDaEdicaoDto(Long id, Long eventoId, String nome, String escopo, String metodologia,
                                      Integer baseRespondentes, BigDecimal nivelConfianca, ContagemDePerguntasDto perguntas,
                                      int resultados, Integer perguntasLigadas) {
        PesquisaDaEdicaoDto comPerguntasLigadas(int ligadas) {
            return new PesquisaDaEdicaoDto(id, eventoId, nome, escopo, metodologia, baseRespondentes, nivelConfianca, perguntas,
                    resultados, ligadas);
        }
    }

    /** Corpo da rota 93. {@code escopo} omitido vale {@code ATIVACAO}. */
    public record AbrirPesquisaRequisicao(String nome, String escopo, String metodologia, Long baseRespondentes,
                                          BigDecimal nivelConfianca) {
    }

    /** Corpo da rota 94: o que vier nulo não muda; {@code metodologia} só com espaços limpa o campo. */
    public record EditarPesquisaRequisicao(String nome, String metodologia, Long baseRespondentes, BigDecimal nivelConfianca) {
    }

    /** Uma pergunta da fila da rota 95, com a marca e a edição a que ela pertence. */
    public record PerguntaDaFilaDto(Long id, Long parceriaId, String patrocinador, Long eventoId, Integer edicao, Long pesquisaId,
                                    Integer ordem, String texto, String status, UsuarioResumoDto enviadaPor,
                                    OffsetDateTime enviadaEm) {
    }
}
