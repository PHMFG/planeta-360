package br.com.rbs.portal.contrato;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.contrato.ContratoDtos.ContratoDto;
import br.com.rbs.portal.contrato.ContratoDtos.SignatarioRequisicao;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Rotas 74 a 76 (docs/api.md): ver o contrato, informar o signatário e enviar o contrato assinado. [CONTRATO] A 76 é só da RBS:
 * [PERMISSAO] a marca recebe 403 antes de o multipart ser lido (PermissaoAntesDoCorpo). A 75 confere a parceria e a aba antes do
 * corpo. As regras estão no ContratoService.
 */
@RestController
@RequestMapping("/api/parcerias/{id}/contrato")
public class ContratoController {

    private final ContratoService servico;

    public ContratoController(ContratoService servico) {
        this.servico = servico;
    }

    /** 74. */
    @GetMapping
    public ContratoDto ver(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        return servico.ver(usuario, id);
    }

    /** 75. Cria ou troca o signatário. */
    @PutMapping("/signatario")
    public ContratoDto informarSignatario(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                          @RequestBody(required = false) SignatarioRequisicao corpo, HttpServletRequest requisicao) {
        return servico.informarSignatario(usuario, id, corpo, requisicao);
    }

    /** 76. Multipart: {@code arquivo} (PDF), {@code assinadoEm} (AAAA-MM-DD, opcional) e {@code observacao} (opcional). Responde 201. */
    @PostMapping(path = "/arquivo")
    public ResponseEntity<ContratoDto> enviarArquivo(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                                     @RequestPart(name = "arquivo", required = false) MultipartFile arquivo,
                                                     @RequestParam(required = false) String assinadoEm,
                                                     @RequestParam(required = false) String observacao, HttpServletRequest requisicao) {
        ContratoDto contrato = servico.enviarArquivo(usuario, id, arquivo, assinadoEm, observacao, requisicao);
        return ResponseEntity.created(URI.create("/api/parcerias/" + id + "/contrato")).body(contrato);
    }
}
