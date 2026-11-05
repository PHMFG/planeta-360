package br.com.rbs.portal.materiais;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.materiais.EspacosDtos.EspacosDto;
import br.com.rbs.portal.materiais.EspacosDtos.PlanoComercialDto;
import br.com.rbs.portal.materiais.MateriaisDtos.ArquivoDto;
import br.com.rbs.portal.materiais.MateriaisDtos.ArquivoEnviadoDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
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
 * Rotas 69 a 73: o plano comercial do Perfil e os espaços de documento da marca (ingressos, MIV e logo). As regras estão no
 * EspacosService; aqui só entram as partes do formulário e os códigos HTTP. A parte "arquivo" é conferida no service (e não por
 * required=true) para a falta dela virar 422 com campos.arquivo, como na rota 17. Diferente da 17, estas rotas NÃO usam consumes=multipart:
 * assim a permissão e a parceria (403 e 404) vêm antes de qualquer conferência do corpo, e uma requisição que não é multipart chega ao
 * service como "sem arquivo" (422).
 */
@RestController
@RequestMapping("/api")
public class EspacosController {

    private final EspacosService servico;

    public EspacosController(EspacosService servico) {
        this.servico = servico;
    }

    /** 69. As versões do plano comercial, da mais nova para a mais antiga. */
    @GetMapping("/parcerias/{id}/plano-comercial")
    public PlanoComercialDto planoComercial(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        return servico.planoComercial(usuario, id);
    }

    /** 70. Só a RBS (conferido antes do corpo em PermissaoAntesDoCorpo). 201 com a versão criada. */
    @PostMapping(path = "/parcerias/{id}/plano-comercial")
    public ResponseEntity<ArquivoDto> enviarPlano(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                                  @RequestPart(name = "arquivo", required = false) MultipartFile arquivo,
                                                  @RequestParam(required = false) String observacao, HttpServletRequest requisicao) {
        ArquivoDto enviado = servico.enviarPlano(usuario, id, arquivo, observacao, requisicao);
        return ResponseEntity.created(URI.create("/api/arquivos/" + enviado.id())).body(enviado);
    }

    /** 71. O formulário de ingressos, as solicitações, o MIV e a logo. {@code aba} (opcional) pede só a parte do Perfil ou de Aplicações. */
    @GetMapping("/parcerias/{id}/espacos")
    public EspacosDto espacos(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                              @RequestParam(required = false)
                              @Pattern(regexp = "PERFIL|APLICACOES", message = "Aba inválida. Use PERFIL ou APLICACOES.") String aba) {
        return servico.espacos(usuario, id, aba);
    }

    /** 72. Envia um arquivo para o espaço {@code papel} (DOCUMENTO_INGRESSOS, MIV ou LOGO). A categoria do formulário é ignorada. */
    @PostMapping(path = "/parcerias/{id}/espacos/{papel}/arquivos")
    public ResponseEntity<ArquivoEnviadoDto> enviarParaEspaco(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                                              @PathVariable String papel,
                                                              @RequestPart(name = "arquivo", required = false) MultipartFile arquivo,
                                                              @RequestParam(required = false) String observacao,
                                                              HttpServletRequest requisicao) {
        ArquivoEnviadoDto enviado = servico.enviarParaEspaco(usuario, id, papel, arquivo, observacao, requisicao);
        return ResponseEntity.created(URI.create("/api/arquivos/" + enviado.id())).body(enviado);
    }

    /** 73. Só a RBS: o formulário em branco da compra de ingressos. */
    @PostMapping(path = "/parcerias/{id}/formulario-ingressos")
    public ResponseEntity<ArquivoDto> publicarFormulario(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                                         @RequestPart(name = "arquivo", required = false) MultipartFile arquivo,
                                                         @RequestParam(required = false) String observacao,
                                                         HttpServletRequest requisicao) {
        ArquivoDto enviado = servico.publicarFormulario(usuario, id, arquivo, observacao, requisicao);
        return ResponseEntity.created(URI.create("/api/arquivos/" + enviado.id())).body(enviado);
    }
}
