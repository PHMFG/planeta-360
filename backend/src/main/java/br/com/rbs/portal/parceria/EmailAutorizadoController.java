package br.com.rbs.portal.parceria;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.parceria.EmailAutorizadoService.Resultado;
import br.com.rbs.portal.parceria.ParceriaDtos.AutorizarEmailRequisicao;
import br.com.rbs.portal.parceria.ParceriaDtos.EmailAutorizadoComCodigoDto;
import br.com.rbs.portal.parceria.ParceriaDtos.EmailAutorizadoDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas 11 a 13: listar, autorizar e revogar os e-mails de uma parceria. Atende o item 3 da Entrega 2.
 * [PERMISSAO] Só a RBS: "patrocinador não cadastra nada" (403). O código de ativação sai só na resposta do POST.
 */
@RestController
@RequestMapping("/api/parcerias/{id}/emails")
public class EmailAutorizadoController {

    private final EmailAutorizadoService servico;

    public EmailAutorizadoController(EmailAutorizadoService servico) {
        this.servico = servico;
    }

    @GetMapping
    public List<EmailAutorizadoDto> listar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        return servico.listar(usuario, id);
    }

    /** 201 para e-mail novo; 200 quando a linha já existia (código reemitido ou acesso devolvido a quem estava revogado). */
    @PostMapping
    public ResponseEntity<EmailAutorizadoComCodigoDto> autorizar(@AuthenticationPrincipal UsuarioAutenticado usuario,
                                                                 @PathVariable long id,
                                                                 @Valid @RequestBody AutorizarEmailRequisicao corpo,
                                                                 HttpServletRequest requisicao) {
        Resultado resultado = servico.autorizar(usuario, id, corpo, requisicao);
        return ResponseEntity.status(resultado.criado() ? HttpStatus.CREATED : HttpStatus.OK).body(resultado.email());
    }

    @DeleteMapping("/{emailId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revogar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id, @PathVariable long emailId,
                        HttpServletRequest requisicao) {
        servico.revogar(usuario, id, emailId, requisicao);
    }
}
