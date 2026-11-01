package br.com.rbs.portal.atlas.contexto;

import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.AtlasRepositorio.EscopoDaParceria;
import br.com.rbs.portal.atlas.jev.JevDoTurno;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Monta o {@link AtlasContexto} de uma conversa. [ATLAS] [ISOLAMENTO] A conferência da parceria vai ao banco a cada pedido (vínculo
 * revogado vale na chamada seguinte), como no resto do portal: quem não é da parceria recebe 404, igual a parceria que não existe.
 */
@Component
public class AtlasContextoFabrica {

    private final VerificadorDeAcesso verificador;
    private final AtlasRepositorio repositorio;

    public AtlasContextoFabrica(VerificadorDeAcesso verificador, AtlasRepositorio repositorio) {
        this.verificador = verificador;
        this.repositorio = repositorio;
    }

    /**
     * O contexto da conversa, com o estado do JEV de um turno novo. {@code parceriaId} é o da linha da conversa, nunca um id que veio da URL
     * ou do corpo.
     */
    public AtlasContexto para(UsuarioAutenticado usuario, long parceriaId, long conversaId, HttpServletRequest requisicao) {
        verificador.exigirParceria(usuario, parceriaId);
        EscopoDaParceria escopo = repositorio.escopoDaParceria(parceriaId);
        return new AtlasContexto(usuario, parceriaId, escopo.eventoId(), escopo.cotaTipoId(), escopo.cotaCodigo(),
                verificador.abasAbertas(usuario, parceriaId), conversaId, requisicao).comJev(new JevDoTurno());
    }

    /**
     * O contexto da conversa da RBS (M8): sem parceria, cota nem abas, e <b>com o JEV desligado</b> ({@link JevDoTurno#NENHUM}: nenhum
     * ponto roda, e nada do estado da conversa vai a ele). {@code eventoId} é o da linha da conversa, nunca um id que veio da URL ou
     * do corpo. [PERMISSAO] Só a RBS: quem não é dela recebe 403.
     */
    public AtlasContexto paraARbs(UsuarioAutenticado usuario, long eventoId, long conversaId, HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        return new AtlasContexto(usuario, AtlasContexto.SEM_PARCERIA, eventoId, 0, null, Set.of(), conversaId, requisicao);
    }
}
