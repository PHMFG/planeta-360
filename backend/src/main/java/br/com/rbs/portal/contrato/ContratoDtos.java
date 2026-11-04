package br.com.rbs.portal.contrato;

import br.com.rbs.portal.materiais.MateriaisDtos.UsuarioResumoDto;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Objetos das rotas 74 a 76. [CONTRATO] */
public final class ContratoDtos {

    private ContratoDtos() {
    }

    /**
     * Quem assina pelo patrocinador. Dado pessoal de terceiro: só estas rotas o devolvem. {@code informadoEm} é nulo nos contratos
     * que vieram da amostra antes da V16.
     */
    public record SignatarioDto(String nome, String cargo, String email, UsuarioResumoDto informadoPor, OffsetDateTime informadoEm) {

        /** O Spring escreve o objeto no log (DEBUG) ao ler ou devolver o corpo: o signatário nunca sai no toString. */
        @Override
        public String toString() {
            return "SignatarioDto[oculto]";
        }
    }

    /** Uma versão do contrato assinado (um arquivo da categoria CONTRATO na aba Contrato). A mais nova vem primeiro. */
    public record VersaoDoContratoDto(Long id, Integer versao, String nomeOriginal, String mimeType, Long tamanhoBytes,
                                      UsuarioResumoDto enviadoPor, OffsetDateTime enviadoEm, String observacao) {
    }

    /**
     * A resposta das rotas 74 a 76. {@code signatario} é nulo enquanto ninguém informou. {@code arquivo} é a versão em vigor (a que o
     * contrato aponta) e {@code versoes} são todas, da mais nova para a mais antiga; sem contrato enviado, o primeiro é nulo e a
     * lista, vazia.
     */
    public record ContratoDto(Long parceriaId, SignatarioDto signatario, LocalDate assinadoEm, VersaoDoContratoDto arquivo,
                              List<VersaoDoContratoDto> versoes) {

        /** O signatário não sai no log (veja SignatarioDto): só a parceria e o que não é dado pessoal. */
        @Override
        public String toString() {
            return "ContratoDto[parceriaId=" + parceriaId + ", versoes=" + versoes.size() + "]";
        }
    }

    /** Corpo da rota 75. A validação é do service, depois da permissão e da parceria. */
    public record SignatarioRequisicao(String nome, String email, String cargo) {

        /** O corpo da rota 75 é dado pessoal: o Spring o escreve no log (DEBUG) ao ler, então o toString não mostra nada. */
        @Override
        public String toString() {
            return "SignatarioRequisicao[oculto]";
        }
    }
}
