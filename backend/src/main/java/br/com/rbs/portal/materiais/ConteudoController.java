package br.com.rbs.portal.materiais;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.materiais.MateriaisDtos.ConteudoEnviadoDto;
import br.com.rbs.portal.materiais.MateriaisDtos.ConteudoRequisicao;
import br.com.rbs.portal.materiais.MateriaisDtos.LinksDeMidiaDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas 86 e 87: enviar um texto ou um link numa entrega e listar os links de mídia da parceria. R4.3, R5.3.a, R5.3.b e R5.3.c: não há
 * rota para editar nem apagar o que foi enviado (o banco também recusa). [ISOLAMENTO] Entrega ou parceria de outra marca dá 404. As
 * regras estão nos services; aqui só entram o corpo e o código HTTP.
 */
@RestController
@RequestMapping("/api")
public class ConteudoController {

    private final ConteudoDaEntregaService conteudo;
    private final LinksDeMidiaService links;

    public ConteudoController(ConteudoDaEntregaService conteudo, LinksDeMidiaService links) {
        this.conteudo = conteudo;
        this.links = links;
    }

    /** 86. Os campos são conferidos no service (422 com campos.*), não por anotação, para a ordem 404, 403 e só depois 422. */
    @PostMapping("/entregas/{id}/conteudos")
    public ResponseEntity<ConteudoEnviadoDto> enviar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                                     @RequestBody ConteudoRequisicao corpo, HttpServletRequest requisicao) {
        return ResponseEntity.status(HttpStatus.CREATED).body(conteudo.enviar(usuario, id, corpo, requisicao));
    }

    /** 87. */
    @GetMapping("/parcerias/{id}/links-de-midia")
    public LinksDeMidiaDto linksDeMidia(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        return links.listar(usuario, id);
    }
}
