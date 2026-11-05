package br.com.rbs.portal.pesquisa;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.materiais.MateriaisDtos.PaginaDto;
import br.com.rbs.portal.pesquisa.AberturaDtos.AbrirPesquisaRequisicao;
import br.com.rbs.portal.pesquisa.AberturaDtos.EditarPesquisaRequisicao;
import br.com.rbs.portal.pesquisa.AberturaDtos.PerguntaDaFilaDto;
import br.com.rbs.portal.pesquisa.AberturaDtos.PesquisaDaEdicaoDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas 92 a 95 do contrato (docs/api.md): a RBS lista, abre e edita as pesquisas da edição e vê a fila das perguntas a aprovar.
 * [PERMISSAO] Só a RBS: o patrocinador recebe 403 antes do corpo (PermissaoAntesDoCorpo). As regras estão no PesquisaAberturaService.
 */
@RestController
@RequestMapping("/api")
public class PesquisaAberturaController {

    private final PesquisaAberturaService servico;

    public PesquisaAberturaController(PesquisaAberturaService servico) {
        this.servico = servico;
    }

    /** 92. As pesquisas da edição, a da ativação primeiro. */
    @GetMapping("/eventos/{id}/pesquisas")
    public List<PesquisaDaEdicaoDto> listar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        return servico.listar(usuario, id);
    }

    /** 93. Responde 201, com o {@code Location} da pesquisa e a pesquisa. */
    @PostMapping("/eventos/{id}/pesquisas")
    public ResponseEntity<PesquisaDaEdicaoDto> abrir(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                                     @RequestBody AbrirPesquisaRequisicao corpo, HttpServletRequest requisicao) {
        PesquisaDaEdicaoDto aberta = servico.abrir(usuario, id, corpo, requisicao);
        return ResponseEntity.created(URI.create("/api/eventos/" + id + "/pesquisas")).body(aberta);
    }

    /** 94. Edição parcial: nome, metodologia, base e nível de confiança. */
    @PatchMapping("/pesquisas/{id}")
    public PesquisaDaEdicaoDto editar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                      @RequestBody EditarPesquisaRequisicao corpo, HttpServletRequest requisicao) {
        return servico.editar(usuario, id, corpo, requisicao);
    }

    /** 95. A fila das perguntas de todas as parcerias, paginada como a rota 16. A decisão continua na rota 25. */
    @GetMapping("/pesquisa/perguntas")
    public PaginaDto<PerguntaDaFilaDto> fila(@AuthenticationPrincipal UsuarioAutenticado usuario,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) Long eventoId,
                                             @RequestParam(defaultValue = "0") @Min(value = 0, message = "A página começa em 0.") int pagina,
                                             @RequestParam(defaultValue = "50")
                                             @Min(value = 1, message = "O tamanho vai de 1 a 200.")
                                             @Max(value = 200, message = "O tamanho vai de 1 a 200.") int tamanho) {
        return servico.fila(usuario, status, eventoId, pagina, tamanho);
    }
}
