package br.com.rbs.portal.acesso;

import br.com.rbs.portal.comum.Perfil;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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

    /** Resposta de GET /api/auth/csrf: o nome do cabeçalho e o valor do token. */
    public record CsrfResposta(String cabecalho, String token) {
    }

    public record CotaDto(String codigo, String nome) {
    }

    public record EventoDto(Long id, String nome, Integer edicao) {
    }

    /** Uma das 7 abas do menu. liberada = false quando a cota ainda não libera a aba (o front mostra o cadeado). */
    public record AbaDto(String codigo, String nome, int ordem, boolean liberada) {
    }

    public record ParceriaDoUsuarioDto(Long id, String patrocinador, CotaDto cota, EventoDto evento, List<AbaDto> abas) {
    }

    /** Quem está logado e a que parcerias tem acesso (lista vazia para o Admin RBS, que enxerga todas). */
    public record UsuarioLogadoDto(Long id, String nome, String email, Perfil perfil, List<ParceriaDoUsuarioDto> parcerias) {
    }
}
