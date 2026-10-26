package br.com.rbs.portal.materiais;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.materiais.MateriaisDtos.CriarEntregaRequisicao;
import br.com.rbs.portal.materiais.MateriaisDtos.EntregaDto;
import br.com.rbs.portal.materiais.MateriaisDtos.ItemDaFilaDto;
import br.com.rbs.portal.materiais.MateriaisDtos.MudarStatusRequisicao;
import br.com.rbs.portal.materiais.MateriaisDtos.PaginaDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
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
 * Rotas 14, 15, 16 e 19: entregas da parceria, criar entrega, fila da RBS e mudar o status. Atende o item 4 da Entrega 2.
 * [PERMISSAO] Criar, ver a fila e mudar o status é só da RBS (403 para o patrocinador); [ISOLAMENTO] entrega de outra
 * parceria dá 404. As regras estão no EntregaService; aqui só entram os parâmetros e o código HTTP.
 */
@RestController
@RequestMapping("/api")
public class EntregaController {

    private final EntregaService servico;

    public EntregaController(EntregaService servico) {
        this.servico = servico;
    }

    /** 14. [VALIDACAO] aba e status que não existem viram 422 com campos.aba / campos.status. */
    @GetMapping("/parcerias/{id}/entregas")
    public List<EntregaDto> listar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                   @RequestParam(required = false)
                                   @Pattern(regexp = MateriaisDtos.PADRAO_ABA, message = "Aba inválida. Use PERFIL, APLICACOES, MIDIA, CORTESIAS, CREDENCIAMENTO, PESQUISA ou CONTRATO.")
                                   String aba,
                                   @RequestParam(required = false)
                                   @Pattern(regexp = MateriaisDtos.PADRAO_STATUS, message = "Status inválido.")
                                   String status) {
        return servico.listar(usuario, id, aba, status);
    }

    /** 15. Cria a entrega (201). */
    @PostMapping("/parcerias/{id}/entregas")
    public ResponseEntity<EntregaDto> criar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                            @Valid @RequestBody CriarEntregaRequisicao corpo, HttpServletRequest requisicao) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servico.criar(usuario, id, corpo, requisicao));
    }

    /** 16. A fila da RBS, paginada. status aceita vários valores separados por vírgula. */
    @GetMapping("/entregas")
    public PaginaDto<ItemDaFilaDto> fila(@AuthenticationPrincipal UsuarioAutenticado usuario,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) Long parceriaId,
                                         @RequestParam(required = false) Long eventoId,
                                         @RequestParam(required = false)
                                         @Pattern(regexp = MateriaisDtos.PADRAO_ABA, message = "Aba inválida. Use PERFIL, APLICACOES, MIDIA, CORTESIAS, CREDENCIAMENTO, PESQUISA ou CONTRATO.")
                                         String abaCodigo,
                                         @RequestParam(required = false) Boolean atrasadas,
                                         @RequestParam(required = false)
                                         @Size(max = 160, message = "A busca tem no máximo 160 caracteres.") String q,
                                         @RequestParam(defaultValue = "0") @Min(value = 0, message = "A página começa em 0.") int pagina,
                                         @RequestParam(defaultValue = "50")
                                         @Min(value = 1, message = "O tamanho vai de 1 a 200.")
                                         @Max(value = 200, message = "O tamanho vai de 1 a 200.") int tamanho) {
        return servico.fila(usuario, status, parceriaId, eventoId, abaCodigo, atrasadas, q, pagina, tamanho);
    }

    /** 19. Muda o status da entrega (só a RBS). */
    @PatchMapping("/entregas/{id}/status")
    public EntregaDto mudarStatus(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                  @Valid @RequestBody MudarStatusRequisicao corpo, HttpServletRequest requisicao) {
        return servico.mudarStatus(usuario, id, corpo, requisicao);
    }
}
