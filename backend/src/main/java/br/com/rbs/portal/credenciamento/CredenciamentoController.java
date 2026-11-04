package br.com.rbs.portal.credenciamento;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.credenciamento.CredenciamentoDtos.CredenciaisDto;
import br.com.rbs.portal.credenciamento.CredenciamentoDtos.CredenciaisRequisicao;
import br.com.rbs.portal.parceria.ParceriaDtos.EventoCompletoDto;
import tools.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas 77 a 79 (docs/api.md): ver e gravar as credenciais da parceria e informar o Open Staff e o período de cadastro da edição.
 * [CREDENCIAMENTO] A 78 e a 79 são só da RBS: [PERMISSAO] a marca recebe 403 antes do corpo (PermissaoAntesDoCorpo). As regras estão no
 * CredenciamentoService.
 */
@RestController
@RequestMapping("/api")
public class CredenciamentoController {

    private final CredenciamentoService servico;

    public CredenciamentoController(CredenciamentoService servico) {
        this.servico = servico;
    }

    /** 77. */
    @GetMapping("/parcerias/{id}/credenciais")
    public CredenciaisDto ver(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        return servico.ver(usuario, id);
    }

    /** 78. Upsert por setor e beneficiário; quantidade 0 retira a linha. */
    @PatchMapping("/parcerias/{id}/credenciais")
    public CredenciaisDto gravar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                 @RequestBody CredenciaisRequisicao corpo, HttpServletRequest requisicao) {
        return servico.gravar(usuario, id, corpo, requisicao);
    }

    /** 79. O endereço do Open Staff (só https) e o período de cadastro da edição; o que não vier no corpo não muda. */
    @PatchMapping("/eventos/{id}/credenciamento")
    public EventoCompletoDto informarEdicao(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                            @RequestBody JsonNode corpo, HttpServletRequest requisicao) {
        return servico.informarEdicao(usuario, id, corpo, requisicao);
    }
}
