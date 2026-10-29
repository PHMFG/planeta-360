package br.com.rbs.portal.dados;

import br.com.rbs.portal.materiais.MateriaisDtos.UsuarioResumoDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Corpos de entrada e saída das rotas 39 a 41 (catálogo de campos e dados informados pela marca), em record. O formato é o
 * contrato com o front (docs/api.md): camelCase, datas com o fuso de Brasília, listas sempre como lista, campo sem valor como
 * null. [VALIDACAO] O valor de um campo INTEIRO sai como inteiro (1200, e não 1200.00).
 */
public final class DadosDtos {

    /** O máximo de valores por pedido da rota 41. */
    public static final int LIMITE_DE_VALORES = 20;

    private DadosDtos() {
    }

    /**
     * Um campo do catálogo (rota 39). {@code minimo} e {@code maximo} são nulos quando não há limite; {@code limiteCampo} é a
     * chave do campo cujo valor vigente este não pode passar (cadastros não passam de visitantes), ou nulo.
     */
    public record CampoDto(String chave, String aba, String rotulo, String tipo, String unidade, Number minimo, Number maximo,
                           String ajuda, String limiteCampo) {
    }

    /**
     * O valor vigente de um campo: o mais recente, com quem informou, quando e por onde (PORTAL ou ATLAS). {@code valor} é um
     * inteiro (campo INTEIRO), um decimal (DECIMAL), uma data AAAA-MM-DD (DATA) ou o id do arquivo (ARQUIVO).
     */
    public record VigenteDto(Object valor, String origem, UsuarioResumoDto informadoPor, OffsetDateTime informadoEm) {
    }

    /** Um campo do catálogo com o valor vigente da parceria ({@code vigente} é nulo enquanto ninguém informou). */
    public record CampoComValorDto(String chave, String aba, String rotulo, String tipo, String unidade, Number minimo,
                                   Number maximo, String ajuda, String limiteCampo, VigenteDto vigente) {
    }

    /** A resposta das rotas 40 e 41: os campos da aba, na ordem do catálogo, cada um com o vigente. */
    public record DadosDaAbaDto(Long parceriaId, String aba, List<CampoComValorDto> campos) {
    }

    // ---------------------------------------------------------------------
    // Corpos de entrada
    // ---------------------------------------------------------------------

    /**
     * [VALIDACAO] Um valor do pedido. {@code valor} chega como veio no JSON (número, texto, nulo...) porque quem decide se serve
     * é o tipo do campo, que o DadoInformadoService confere; assim a mensagem diz o que o campo espera.
     */
    public record ValorRequisicao(
            @NotBlank(message = "Informe a chave do campo.")
            @Size(max = 80, message = "A chave do campo tem no máximo 80 caracteres.")
            String chave,
            Object valor) {

        public ValorRequisicao {
            chave = chave == null ? null : chave.trim();
        }
    }

    /** [VALIDACAO] O corpo da rota 41: de 1 a 20 valores. Os demais erros (chave, tipo, limites) o Service confere. */
    public record GravarDadosRequisicao(
            @NotNull(message = "Envie a lista de valores.")
            @Size(min = 1, max = LIMITE_DE_VALORES, message = "Envie de 1 a 20 valores.")
            List<@NotNull(message = "Valor vazio.") @Valid ValorRequisicao> valores) {
    }
}
