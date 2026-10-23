package br.com.rbs.portal.comum;

import java.io.Serializable;

/**
 * Quem está logado, guardado na sessão. Tem só o necessário para decidir permissões; a senha nunca vai para a sessão.
 * Os controllers recebem este objeto com @AuthenticationPrincipal. Atende o item 3 da Entrega 2.
 * [PERMISSAO] O perfil vem do banco no momento do login; as parcerias NÃO ficam aqui, para a revogação valer na hora.
 */
public record UsuarioAutenticado(Long id, String nome, String email, Perfil perfil) implements Serializable {

    public boolean ehRbs() {
        return perfil == Perfil.ADMIN_RBS;
    }
}
