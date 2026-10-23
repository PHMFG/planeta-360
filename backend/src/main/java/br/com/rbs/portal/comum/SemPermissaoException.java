package br.com.rbs.portal.comum;

/**
 * O usuário está logado, mas o perfil dele não pode fazer esta operação: o TratadorDeErros devolve 403.
 * [PERMISSAO] Lançada por VerificadorDeAcesso.exigirRbs, nas rotas que só a equipe da RBS pode usar.
 */
public class SemPermissaoException extends RuntimeException {

    public SemPermissaoException(String mensagem) {
        super(mensagem);
    }
}
