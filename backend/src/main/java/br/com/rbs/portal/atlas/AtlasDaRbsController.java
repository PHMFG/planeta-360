package br.com.rbs.portal.atlas;

import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ConversasDaRbsDto;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas 67 e 68 do contrato (docs/api.md, "O Atlas da RBS"): a conversa da RBS por edição, sem parceria. [ATLAS] [PERMISSAO] Só do
 * Admin RBS: o PermissaoAntesDoCorpo responde 403 à marca antes de tudo (lista ROTAS_SO_DA_RBS). Sempre mapeadas, sem
 * {@code @ConditionalOnProperty}: assim o PermissaoAntesDoCorpoTest as enxerga, e o service responde 404 à RBS com
 * {@code atlas.rbs.ligado=false}. A mensagem, a leitura e o diagnóstico da conversa usam as rotas 44, 45 e 49 ({@link AtlasController}).
 */
@RestController
public class AtlasDaRbsController {

    private final AtlasDaRbsService servico;

    public AtlasDaRbsController(AtlasDaRbsService servico) {
        this.servico = servico;
    }

    /** 67. Não lê o corpo. Responde 201. */
    @PostMapping("/api/eventos/{eventoId}/atlas/conversas")
    public ResponseEntity<ConversaDto> abrir(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long eventoId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servico.abrir(usuario, eventoId));
    }

    /** 68. */
    @GetMapping("/api/eventos/{eventoId}/atlas/conversas")
    public ConversasDaRbsDto listar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long eventoId) {
        return servico.listar(usuario, eventoId);
    }
}
