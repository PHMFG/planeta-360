package br.com.rbs.portal.comum;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A regra central de isolamento: toda rota que recebe o id de uma parceria passa por aqui antes de ler ou gravar.
 * Atende o item 3 da Entrega 2. [ISOLAMENTO] [PERMISSAO] Dois testes, nesta ordem: o perfil pode fazer a operação?
 * O dado é de uma parceria a que o usuário tem acesso?
 */
@Component
public class VerificadorDeAcesso {

    private final JdbcClient jdbc;

    public VerificadorDeAcesso(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * [ISOLAMENTO] A equipe da RBS passa em qualquer parceria que exista. O patrocinador só passa se tiver vínculo
     * vigente em parceria_usuario. Quem não passa recebe 404, o mesmo erro de "não existe": assim um patrocinador não
     * descobre quais ids de parceria existem. A consulta roda A CADA PEDIDO e nada é guardado na sessão, porque a
     * revogação do e-mail (que apaga o vínculo no banco) tem de valer na hora, até para quem já estava logado.
     */
    public void exigirParceria(UsuarioAutenticado usuario, long parceriaId) {
        boolean permitido = usuario.ehRbs() ? parceriaExiste(parceriaId) : temVinculoVigente(usuario.id(), parceriaId);
        if (!permitido) {
            throw new NaoEncontradoException("Não encontramos esta parceria.");
        }
    }

    /** [PERMISSAO] Rotas que só a equipe da RBS pode usar: o patrocinador recebe 403. */
    public void exigirRbs(UsuarioAutenticado usuario) {
        if (!usuario.ehRbs()) {
            throw new SemPermissaoException("Esta operação é restrita à equipe da RBS.");
        }
    }

    // O usuário precisa estar ativo: desativar uma conta derruba o acesso mesmo com o vínculo ainda gravado.
    private boolean temVinculoVigente(long usuarioId, long parceriaId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM parceria_usuario pu
                            JOIN usuario u ON u.id = pu.usuario_id
                            WHERE pu.parceria_id = :parceriaId AND pu.usuario_id = :usuarioId AND u.ativo)
                        """)
                .param("parceriaId", parceriaId)
                .param("usuarioId", usuarioId)
                .query(Boolean.class)
                .single();
    }

    private boolean parceriaExiste(long parceriaId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM parceria WHERE id = :parceriaId)")
                .param("parceriaId", parceriaId)
                .query(Boolean.class)
                .single();
    }
}
