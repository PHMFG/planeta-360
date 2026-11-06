package br.com.rbs.portal.acesso;

import br.com.rbs.portal.comum.Perfil;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Corpos de entrada e saída das rotas /api/auth, todos em record. Atende o item 3 da Entrega 2.
 * O formato é o contrato com o front (veja docs/api.md): camelCase, abas e parcerias sempre como lista.
 */
public final class AcessoDtos {

    private AcessoDtos() {
    }

    /** [VALIDACAO] Corpo do login. As mensagens vão para "campos" do erro 422. */
    public record LoginRequisicao(
            @NotBlank(message = "Informe o e-mail.")
            @Email(message = "E-mail inválido.")
            @Size(max = 254, message = "O e-mail tem no máximo 254 caracteres.")
            String email,
            // O BCrypt só considera os 72 primeiros caracteres da senha; mais que isso nunca foi aceito no cadastro.
            @NotBlank(message = "Informe a senha.")
            @Size(max = 72, message = "A senha tem no máximo 72 caracteres.")
            String senha) {

        /** Tira os espaços das pontas do e-mail antes da validação: quem copia e cola costuma levar um espaço junto. */
        public LoginRequisicao {
            email = email == null ? null : email.trim();
        }
    }

    /** O @Email do Bean Validation aceita "a@b"; o banco (CHECK em usuario e email_autorizado) exige um ponto no domínio. */
    static final String PADRAO_EMAIL = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    /**
     * [VALIDACAO] Corpo de POST /api/auth/ativar (primeiro acesso). O nome é opcional: sem ele, a conta usa a parte do
     * e-mail antes do @. A senha tem de 8 a 72 caracteres (o BCrypt só lê os 72 primeiros).
     */
    public record AtivarRequisicao(
            @NotBlank(message = "Informe o e-mail.")
            @Email(message = "E-mail inválido.")
            @Pattern(regexp = PADRAO_EMAIL, message = "E-mail inválido.")
            @Size(max = 254, message = "O e-mail tem no máximo 254 caracteres.")
            String email,
            @NotBlank(message = "Informe o código de ativação.")
            @Size(max = 40, message = "O código de ativação é menor que isso. Confira o que a RBS enviou.")
            String codigo,
            @NotBlank(message = "Informe a senha.")
            @Size(min = 8, max = 72, message = "A senha deve ter de 8 a 72 caracteres.")
            String senha,
            @Size(max = 120, message = "O nome tem no máximo 120 caracteres.")
            String nome) {

        /** Espaços nas pontas do e-mail e do nome saem antes da validação; nome em branco vale como "não informado". */
        public AtivarRequisicao {
            email = email == null ? null : email.trim();
            nome = nome == null || nome.isBlank() ? null : nome.trim();
        }
    }

    /** Resposta de GET /api/auth/csrf: o nome do cabeçalho e o valor do token. */
    public record CsrfResposta(String cabecalho, String token) {
    }

    public record CotaDto(String codigo, String nome) {
    }

    public record EventoDto(Long id, String nome, Integer edicao) {
    }

    /**
     * Uma das 7 abas do menu. liberada = false quando a aba está fechada para a parceria (o front mostra o cadeado).
     * incluidaNaCota = a cota da parceria inclui a aba (cota_tipo_aba): false quer dizer "fora da sua cota" (a RBS nem
     * consegue liberar), diferente de uma aba da cota que a RBS ainda não abriu.
     */
    public record AbaDto(String codigo, String nome, int ordem, boolean liberada, boolean incluidaNaCota, boolean visivel) {
    }

    /** [U7] O atendimento da marca vem junto: o FAQ da marca mostra o nome e o e-mail. Nulos até a RBS preencher. */
    public record ParceriaDoUsuarioDto(Long id, String patrocinador, CotaDto cota, EventoDto evento, List<AbaDto> abas,
                                       String vinculo, String atendimentoNome, String atendimentoEmail) {
    }

    /** Quem está logado e a que parcerias tem acesso (lista vazia para o Admin RBS, que enxerga todas). */
    public record UsuarioLogadoDto(Long id, String nome, String email, Perfil perfil, List<ParceriaDoUsuarioDto> parcerias) {
    }
}
