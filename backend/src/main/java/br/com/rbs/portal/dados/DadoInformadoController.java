package br.com.rbs.portal.dados;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.dados.DadosDtos.DadosDaAbaDto;
import br.com.rbs.portal.dados.DadosDtos.GravarDadosRequisicao;
import br.com.rbs.portal.materiais.MateriaisDtos;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas 40 e 41 do contrato (docs/api.md): ver os valores vigentes de uma aba e gravar de 1 a 20 valores novos. [ISOLAMENTO]
 * [PERMISSAO] Da marca da parceria (com a aba aberta) e da RBS; parceria de outra marca dá 404. As regras estão no
 * DadoInformadoService, o mesmo que o cartão de perguntas do Atlas usa; aqui só entram o corpo e o filtro.
 */
@RestController
@RequestMapping("/api/parcerias/{id}/dados")
public class DadoInformadoController {

    private final DadoInformadoService servico;

    public DadoInformadoController(DadoInformadoService servico) {
        this.servico = servico;
    }

    /** 40. [VALIDACAO] A aba é obrigatória e precisa existir (422 com campos.aba). */
    @GetMapping
    public DadosDaAbaDto ver(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                             @RequestParam(required = false)
                             @NotBlank(message = "Informe a aba.")
                             @Pattern(regexp = MateriaisDtos.PADRAO_ABA,
                                     message = "Aba inválida. Use PERFIL, APLICACOES, MIDIA, CORTESIAS, CREDENCIAMENTO, PESQUISA ou CONTRATO.")
                             String aba) {
        return servico.ver(usuario, id, aba);
    }

    /** 41. Responde 200 com o mesmo corpo da rota 40; a origem do valor é PORTAL. */
    @PostMapping
    public DadosDaAbaDto gravar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                @Valid @RequestBody GravarDadosRequisicao corpo, HttpServletRequest requisicao) {
        return servico.gravar(usuario, id, corpo.valores(), OrigemDoDado.PORTAL, requisicao);
    }
}
