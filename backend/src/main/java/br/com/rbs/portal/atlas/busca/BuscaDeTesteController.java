package br.com.rbs.portal.atlas.busca;

import br.com.rbs.portal.atlas.busca.BuscaDeTesteDtos.BuscaDeTesteDto;
import br.com.rbs.portal.atlas.busca.BuscaDeTesteDtos.BuscaDeTesteRequisicao;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rota 61 do contrato (docs/api.md, "Busca nos documentos"): a busca de teste da RBS. [ATLAS] Sempre mapeada. [PERMISSAO] Só do
 * Admin RBS: o PermissaoAntesDoCorpo responde 403 antes de o corpo ser lido. As regras estão no BuscaDeTesteService.
 */
@RestController
public class BuscaDeTesteController {

    private final BuscaDeTesteService servico;

    public BuscaDeTesteController(BuscaDeTesteService servico) {
        this.servico = servico;
    }

    /** 61. A busca como o modelo a faria para uma parceria, com as duas listas, o RRF e o que a parceria enxerga. */
    @PostMapping("/api/atlas/busca-teste")
    public BuscaDeTesteDto buscar(@AuthenticationPrincipal UsuarioAutenticado usuario, @RequestBody BuscaDeTesteRequisicao corpo) {
        return servico.buscar(usuario, corpo);
    }
}
