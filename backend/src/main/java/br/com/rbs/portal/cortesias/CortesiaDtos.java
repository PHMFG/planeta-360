package br.com.rbs.portal.cortesias;

import br.com.rbs.portal.materiais.MateriaisDtos.UsuarioResumoDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Formatos das rotas 80 a 85 (aba Cortesias), em record. O formato é o contrato com o front (docs/api.md): camelCase, datas com o
 * fuso de Brasília. As quantidades de entrada são {@code Long} de propósito: um número acima de 32.767 (o limite da coluna) tem de
 * chegar ao service para virar 422 com a frase do campo, e não estourar na conversão do JSON ou no banco.
 */
public final class CortesiaDtos {

    private CortesiaDtos() {
    }

    // ---------------------------------------------------------------------
    // Entrada
    // ---------------------------------------------------------------------

    /** Rota 81. */
    public record CotasRequisicao(List<CotaEntrada> cotas) {
    }

    /** Uma célula da grade: o setor e o tipo/dia, a cota (0 retira a cota, se nada foi destinado), o acesso e o shuttle. */
    public record CotaEntrada(String setor, String dia, Long quantidade, Boolean comShuttle, String acesso) {
    }

    /**
     * Rota 83. O {@code toString} não traz o destinatário: o Spring registra em DEBUG o corpo lido ("Read application/json to [...]"),
     * e o nome de uma pessoa não pode ir para o log (D8).
     */
    public record DestinacaoRequisicao(String setor, String dia, Long quantidade, String destinatario) {
        @Override
        public String toString() {
            return "DestinacaoRequisicao[setor=" + setor + ", dia=" + dia + ", quantidade=" + quantidade + ", destinatario=(omitido)]";
        }
    }

    /** Rota 84: o que vem muda; o que não vem fica como está. */
    public record DestinacaoEdicao(String destinatario, Long quantidade) {
        @Override
        public String toString() {
            return "DestinacaoEdicao[destinatario=(omitido), quantidade=" + quantidade + "]";
        }
    }

    // ---------------------------------------------------------------------
    // Saída
    // ---------------------------------------------------------------------

    /** Uma destinação: quanto e para quem. O nome só sai na rota 80 e nas respostas que a devolvem inteira (D8). */
    public record DestinacaoDto(Long id, String destinatario, Integer quantidade, UsuarioResumoDto registradoPor,
                                OffsetDateTime registradoEm) {
        /** Sem o nome: o Spring registra em DEBUG a resposta ("Writing [...]") com o {@code toString} do objeto. */
        @Override
        public String toString() {
            return "DestinacaoDto[id=" + id + ", destinatario=(omitido), quantidade=" + quantidade + "]";
        }
    }

    /** Uma linha da tabela: a cota de um setor e tipo/dia, o que já foi destinado, o que resta e as destinações. */
    public record CotaDto(Long id, String setor, String dia, Integer cota, Long destinadas, Long disponiveis, String acesso,
                          boolean comShuttle, List<DestinacaoDto> destinacoes) {
    }

    /**
     * Os totais da parceria, da mesma visão da rota 29: {@code cota} é o {@code disponiveis} dela (a soma das cotas) e
     * {@code disponiveis} aqui é o que ainda se pode destinar. {@code percentual} é destinadas ÷ cota × 100, cortado na primeira casa;
     * nulo sem cota.
     */
    public record TotaisDto(Long cota, Long destinadas, Long disponiveis, BigDecimal percentual) {
    }

    /** A resposta das rotas 80, 81, 83 e 84. {@code prazoEncerrado}: o prazo da edição passou (a marca não destina mais; a RBS sim). */
    public record CortesiasDto(Long parceriaId, LocalDate cortesiasPrazo, boolean prazoEncerrado, TotaisDto totais,
                               List<CotaDto> cotas) {
    }

    /** A resposta da rota 82. */
    public record PrazoDto(Long eventoId, Integer edicao, LocalDate cortesiasPrazo) {
    }
}
