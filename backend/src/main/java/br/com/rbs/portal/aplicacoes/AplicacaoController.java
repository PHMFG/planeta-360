package br.com.rbs.portal.aplicacoes;

import br.com.rbs.portal.aplicacoes.AplicacaoDtos.AplicacoesDto;
import br.com.rbs.portal.aplicacoes.AplicacaoDtos.ComprovacaoDto;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import jakarta.servlet.http.HttpServletRequest;
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
 * Rotas 37 e 38 do contrato (docs/api.md): o plano de aplicações de marca da parceria, linha a linha, com as fotos de
 * comprovação, e o envio de uma foto. [ISOLAMENTO] [PERMISSAO] A 37 é da marca da parceria (com a aba APLICACOES aberta) e da
 * RBS; a 38 é só da RBS (o PermissaoAntesDoCorpo responde 403 ao patrocinador antes de ler as partes). As regras estão no
 * AplicacaoService; aqui só entram as partes do formulário. A parte "arquivo" é conferida no service (e não por required=true)
 * para a falta dela virar 422 com campos.arquivo, e não 400.
 */
@RestController
@RequestMapping("/api/parcerias/{id}/aplicacoes")
public class AplicacaoController {

    private final AplicacaoService servico;

    public AplicacaoController(AplicacaoService servico) {
        this.servico = servico;
    }

    /** 37. */
    @GetMapping
    public AplicacoesDto listar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        return servico.listar(usuario, id);
    }

    /** 38. multipart/form-data: arquivo (png, jpg ou jpeg) e descricao (opcional). Responde 201 com a comprovação. */
    @PostMapping(path = "/{aproveitamentoId}/comprovacoes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ComprovacaoDto> registrar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                                    @PathVariable long aproveitamentoId,
                                                    @RequestPart(name = "arquivo", required = false) MultipartFile arquivo,
                                                    @RequestParam(required = false) String descricao,
                                                    HttpServletRequest requisicao) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(servico.registrar(usuario, id, aproveitamentoId, arquivo, descricao, requisicao));
    }
}
