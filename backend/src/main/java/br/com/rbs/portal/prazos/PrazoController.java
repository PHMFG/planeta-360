package br.com.rbs.portal.prazos;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.prazos.PrazoDtos.CriarPrazoRequisicao;
import br.com.rbs.portal.prazos.PrazoDtos.PrazoDto;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas 62 a 64 do contrato (docs/api.md): os prazos que o Atlas consulta. [ATLAS] Listar (62), cadastrar (63) e desativar (64). Só da
 * RBS: [PERMISSAO] o patrocinador recebe 403 antes do corpo (PermissaoAntesDoCorpo). As regras estão no PrazoService; aqui entram o
 * corpo e os cabeçalhos.
 */
@RestController
@RequestMapping("/api")
public class PrazoController {

    private final PrazoService servico;

    public PrazoController(PrazoService servico) {
        this.servico = servico;
    }

    /** 62. Os prazos da edição, por data, hora e título. Só os ativos, a menos que {@code incluirInativos=true}. */
    @GetMapping("/eventos/{id}/prazos")
    public List<PrazoDto> listar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                 @RequestParam(defaultValue = "false") boolean incluirInativos) {
        return servico.listar(usuario, id, incluirInativos);
    }

    /** 63. Responde 201, com o {@code Location} do prazo e o prazo. */
    @PostMapping("/eventos/{id}/prazos")
    public ResponseEntity<PrazoDto> criar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                          @RequestBody CriarPrazoRequisicao corpo, HttpServletRequest requisicao) {
        PrazoDto criado = servico.criar(usuario, id, corpo, requisicao);
        return ResponseEntity.created(URI.create("/api/prazos/" + criado.id())).body(criado);
    }

    /** 64. Desativa o prazo (não apaga) e responde 204; desativar de novo também. */
    @DeleteMapping("/prazos/{id}")
    public ResponseEntity<Void> desativar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                          HttpServletRequest requisicao) {
        servico.desativar(usuario, id, requisicao);
        return ResponseEntity.noContent().build();
    }
}
