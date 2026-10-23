package br.com.rbs.portal.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Corpos de requisição, com a validação de campos exigida na entrega. */
public final class Dtos {
    private Dtos() {}

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String senha) {}

    public record PrimeiroAcessoRequest(@NotBlank @Size(max = 120) String nome,
                                        @NotBlank @Email @Size(max = 254) String email,
                                        @NotBlank @Size(min = 8, max = 72) String senha) {}

    public record NovoPatrocinadorRequest(@NotBlank @Size(max = 120) String nome,
                                          @Size(max = 80) String segmento,
                                          @NotNull Long eventoId,
                                          @NotBlank String cota,
                                          @Size(max = 120) String nomeProduto) {}

    public record EmailAutorizadoRequest(@NotBlank @Email @Size(max = 254) String email,
                                         @NotBlank String vinculo) {}

    public record NovaEntregaRequest(@NotBlank String abaCodigo,
                                     @NotBlank @Size(max = 160) String titulo,
                                     String descricao,
                                     LocalDate prazo,
                                     String status) {}

    public record StatusRequest(@NotBlank String status) {}
}
