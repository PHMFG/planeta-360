package br.com.rbs.portal.materiais;

import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.SemPermissaoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.materiais.EntregaRepositorio.EntregaBase;
import org.springframework.stereotype.Component;

/**
 * Confere se o usuário pode mexer numa entrega (envio de arquivo, mudança de status). Atende o item 4 da Entrega 2.
 * [ISOLAMENTO] A entrega leva à parceria dela, e a parceria passa pelo VerificadorDeAcesso. [PERMISSAO] O patrocinador só
 * mexe em entregas de abas liberadas.
 */
@Component
class AcessoAEntregas {

    static final String ABA_FECHADA = "Esta aba ainda não foi liberada para a sua parceria.";

    private final EntregaRepositorio entregas;
    private final VerificadorDeAcesso verificador;

    AcessoAEntregas(EntregaRepositorio entregas, VerificadorDeAcesso verificador) {
        this.entregas = entregas;
        this.verificador = verificador;
    }

    /**
     * [ISOLAMENTO] A entrega que não existe e a de outra parceria dão o MESMO 404, com a mesma frase: se a frase mudasse, o
     * patrocinador descobriria quais ids de entrega existem. {@code travar} = FOR UPDATE, para alterações em série.
     */
    EntregaBase exigir(UsuarioAutenticado usuario, long entregaId, boolean travar) {
        EntregaBase entrega = entregas.buscar(entregaId, travar).orElseThrow(AcessoAEntregas::naoEncontrada);
        try {
            verificador.exigirParceria(usuario, entrega.parceriaId());
        } catch (NaoEncontradoException deOutraParceria) {
            throw naoEncontrada();
        }
        return entrega;
    }

    /** [PERMISSAO] 403 para o patrocinador numa aba que a RBS ainda não liberou; a RBS passa em qualquer aba. */
    void exigirAbaLiberada(UsuarioAutenticado usuario, EntregaBase entrega) {
        if (!usuario.ehRbs() && !entregas.abaLiberada(entrega.parceriaId(), entrega.abaId())) {
            throw new SemPermissaoException(ABA_FECHADA);
        }
    }

    static NaoEncontradoException naoEncontrada() {
        return new NaoEncontradoException("Não encontramos esta entrega.");
    }
}
