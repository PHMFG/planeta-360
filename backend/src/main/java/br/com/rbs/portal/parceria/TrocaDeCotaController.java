package br.com.rbs.portal.parceria;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.parceria.TrocaDeCotaDtos.ImpactoDaTrocaDto;
import br.com.rbs.portal.parceria.TrocaDeCotaDtos.TrocaDeCotaDto;
import br.com.rbs.portal.parceria.TrocaDeCotaDtos.TrocaDeCotaRequisicao;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas 102 e 104 (R6.d3): a prévia e a troca da cota da parceria. [PERMISSAO] Só a RBS: a marca recebe 403 antes de o corpo ser lido
 * (PermissaoAntesDoCorpo.ROTAS_SO_DA_RBS). A validação do corpo fica no service, para a ordem 403, 404, 409 e 422 valer.
 */
@RestController
@RequestMapping("/api/parcerias/{id}/cota")
public class TrocaDeCotaController {

    private final TrocaDeCotaService servico;

    public TrocaDeCotaController(TrocaDeCotaService servico) {
        this.servico = servico;
    }

    @GetMapping("/previa")
    public ImpactoDaTrocaDto previa(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                    @RequestParam(required = false) String para) {
        return servico.previa(usuario, id, para);
    }

    @PutMapping
    public TrocaDeCotaDto trocar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                 @RequestBody TrocaDeCotaRequisicao corpo, HttpServletRequest requisicao) {
        return servico.trocar(usuario, id, corpo, requisicao);
    }
}
