package br.com.rbs.portal.pesquisa;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.pesquisa.PerfilDaPesquisaDtos.PerfilDaPesquisaDto;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rota 119 do contrato (docs/api.md): o perfil demográfico da pesquisa. [PERMISSAO] A marca só entra se for Master e tiver a
 * aba PESQUISA aberta; a RBS vê qualquer parceria. As regras ficam no PerfilDaPesquisaService.
 */
@RestController
@RequestMapping("/api")
public class PerfilDaPesquisaController {

    private final PerfilDaPesquisaService servico;

    public PerfilDaPesquisaController(PerfilDaPesquisaService servico) {
        this.servico = servico;
    }

    /** 119. O perfil da marca, o comparativo da pesquisa geral da edição e as respostas de texto. */
    @GetMapping("/parcerias/{id}/pesquisa/perfil")
    public PerfilDaPesquisaDto perfil(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        return servico.perfil(usuario, id);
    }
}
