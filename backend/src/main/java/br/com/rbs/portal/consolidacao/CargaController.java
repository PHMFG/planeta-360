package br.com.rbs.portal.consolidacao;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.consolidacao.CargaDtos.CargaDto;
import br.com.rbs.portal.consolidacao.CargaDtos.RejeicaoDto;
import br.com.rbs.portal.materiais.MateriaisDtos.PaginaDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Rotas 26, 27 e 28 do contrato (docs/api.md): enviar uma planilha CSV de veiculação, de pesquisa ou do plano comercial,
 * listar as cargas e ver as linhas rejeitadas. Atende o item 5 da Entrega 2. [PERMISSAO] As três são só da RBS (403 para o patrocinador). As regras
 * estão no CargaService; aqui só entram as partes do formulário. As partes são conferidas no service (e não por required=true)
 * para a falta delas virar 422 com "campos", como o contrato diz, e não 400.
 */
@RestController
@RequestMapping("/api/cargas")
public class CargaController {

    private final CargaService servico;

    public CargaController(CargaService servico) {
        this.servico = servico;
    }

    /** 26. multipart/form-data: arquivo, tipo (VEICULACAO, PESQUISA ou PLANO), eventoId e (só na pesquisa) escopo. Responde 201 com a carga. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CargaDto> enviar(@AuthenticationPrincipal UsuarioAutenticado usuario,
                                           @RequestPart(name = "arquivo", required = false) MultipartFile arquivo,
                                           @RequestParam(required = false) String tipo,
                                           @RequestParam(required = false) String eventoId,
                                           @RequestParam(required = false) String escopo,
                                           HttpServletRequest requisicao) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servico.enviar(usuario, arquivo, tipo, eventoId, escopo, requisicao));
    }

    /** 27. */
    @GetMapping
    public PaginaDto<CargaDto> listar(@AuthenticationPrincipal UsuarioAutenticado usuario,
                                      @RequestParam(required = false) String tipo,
                                      @RequestParam(required = false) Long eventoId,
                                      @RequestParam(defaultValue = "0") @Min(value = 0, message = "A página começa em 0.") int pagina,
                                      @RequestParam(defaultValue = "50")
                                      @Min(value = 1, message = "O tamanho vai de 1 a 200.")
                                      @Max(value = 200, message = "O tamanho vai de 1 a 200.") int tamanho) {
        return servico.listar(usuario, tipo, eventoId, pagina, tamanho);
    }

    /** 28. */
    @GetMapping("/{id}/rejeicoes")
    public PaginaDto<RejeicaoDto> rejeicoes(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                            @RequestParam(defaultValue = "0") @Min(value = 0, message = "A página começa em 0.") int pagina,
                                            @RequestParam(defaultValue = "50")
                                            @Min(value = 1, message = "O tamanho vai de 1 a 200.")
                                            @Max(value = 200, message = "O tamanho vai de 1 a 200.") int tamanho) {
        return servico.rejeicoes(usuario, id, pagina, tamanho);
    }
}
