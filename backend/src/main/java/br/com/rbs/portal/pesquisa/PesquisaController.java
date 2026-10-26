package br.com.rbs.portal.pesquisa;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.pesquisa.PesquisaDtos.DecisaoRequisicao;
import br.com.rbs.portal.pesquisa.PesquisaDtos.EnviarPerguntasRequisicao;
import br.com.rbs.portal.pesquisa.PesquisaDtos.PerguntaDto;
import br.com.rbs.portal.pesquisa.PesquisaDtos.PesquisaDaParceriaDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas 23 a 25 do contrato (docs/api.md): ver a pesquisa da ativação, enviar as perguntas e a RBS decidir sobre cada uma.
 * Atende o item 5 da Entrega 2. [ISOLAMENTO] [PERMISSAO] A rota 24 é do Admin Patrocinador da parceria e a 25 só da RBS
 * (o outro perfil recebe 403). As regras ficam no PesquisaService.
 */
@RestController
@RequestMapping("/api")
public class PesquisaController {

    private final PesquisaService servico;

    public PesquisaController(PesquisaService servico) {
        this.servico = servico;
    }

    /** 23. As perguntas, o estado de cada uma e os resultados das aprovadas. */
    @GetMapping("/parcerias/{id}/pesquisa")
    public PesquisaDaParceriaDto ver(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        return servico.ver(usuario, id);
    }

    /** 24. O PUT substitui o conjunto de perguntas da marca (de 1 a 3). */
    @PutMapping("/parcerias/{id}/pesquisa/perguntas")
    public PesquisaDaParceriaDto enviarPerguntas(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                                 @Valid @RequestBody EnviarPerguntasRequisicao corpo,
                                                 HttpServletRequest requisicao) {
        return servico.enviarPerguntas(usuario, id, corpo, requisicao);
    }

    /** 25. A RBS aprova a pergunta ou pede ajuste. */
    @PatchMapping("/pesquisa/perguntas/{id}/status")
    public PerguntaDto decidir(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                               @Valid @RequestBody DecisaoRequisicao corpo, HttpServletRequest requisicao) {
        return servico.decidir(usuario, id, corpo, requisicao);
    }
}
