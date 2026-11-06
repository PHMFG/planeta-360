package br.com.rbs.portal.pesquisa;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/**
 * Corpos da rota 119 (o perfil da pesquisa), todos em record. O contrato está em docs/api.md.
 */
public final class PerfilDaPesquisaDtos {

    private PerfilDaPesquisaDtos() {
    }

    /** A pesquisa da ativação, como a rota 119 a mostra: id, nome e base de respondentes. */
    public record PesquisaDoPerfilDto(Long id, String nome, Integer baseRespondentes) {
    }

    /** Uma categoria de uma dimensão do perfil, com o valor e a base de respondentes. */
    public record CategoriaDoPerfilDto(String dimensao, String categoria, BigDecimal valor, Integer baseRespondentes) {
    }

    /**
     * Uma resposta de texto livre, agregada. A marca nunca recebe a resposta com suspeita de dado pessoal; a RBS a vê com
     * {@code suspeita} preenchido (e o campo só aparece para ela).
     */
    public record RespostaTextoDto(Long perguntaId, Integer ordem, String texto, Integer contagem,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) Boolean suspeita) {
    }

    /** A resposta da rota 119: a pesquisa, o perfil da marca, o da pesquisa geral da edição e as respostas de texto. */
    public record PerfilDaPesquisaDto(PesquisaDoPerfilDto pesquisa, List<CategoriaDoPerfilDto> perfil,
                                      List<CategoriaDoPerfilDto> geral, List<RespostaTextoDto> respostasTexto) {
    }
}
