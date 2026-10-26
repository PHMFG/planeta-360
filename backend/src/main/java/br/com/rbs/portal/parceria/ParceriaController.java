package br.com.rbs.portal.parceria;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.parceria.ParceriaDtos.CriarParceriaRequisicao;
import br.com.rbs.portal.parceria.ParceriaDtos.EditarParceriaRequisicao;
import br.com.rbs.portal.parceria.ParceriaDtos.EmailResumoComCodigoDto;
import br.com.rbs.portal.parceria.ParceriaDtos.EmailResumoDto;
import br.com.rbs.portal.parceria.ParceriaDtos.ParceriaDetalheDto;
import br.com.rbs.portal.parceria.ParceriaDtos.ParceriaResumoDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
 * Rotas 7 a 10: listar, criar, ver e editar parcerias. Atende o item 3 da Entrega 2.
 * [PERMISSAO] Criar e editar é só da RBS (403 para o patrocinador); [ISOLAMENTO] ver uma parceria de outro dá 404.
 * As regras estão em ParceriaService e ParceriaEdicaoService; aqui só entram os parâmetros e o código HTTP.
 */
@RestController
@RequestMapping("/api/parcerias")
public class ParceriaController {

    private final ParceriaService servico;
    private final ParceriaEdicaoService edicao;

    public ParceriaController(ParceriaService servico, ParceriaEdicaoService edicao) {
        this.servico = servico;
        this.edicao = edicao;
    }

    /** [VALIDACAO] situacao fora da lista vira 422 com campos.situacao. */
    @GetMapping
    public List<ParceriaResumoDto> listar(@AuthenticationPrincipal UsuarioAutenticado usuario,
                                          @RequestParam(required = false) Long eventoId,
                                          @RequestParam(required = false)
                                          @Pattern(regexp = ParceriaDtos.PADRAO_SITUACAO,
                                                  message = "A situação deve ser EM_NEGOCIACAO, ATIVA, ENCERRADA ou CANCELADA.")
                                          String situacao,
                                          @RequestParam(required = false)
                                          @Size(max = 120, message = "A busca tem no máximo 120 caracteres.") String q) {
        return servico.listar(usuario, eventoId, situacao, q);
    }

    @PostMapping
    public ResponseEntity<ParceriaDetalheDto<EmailResumoComCodigoDto>> criar(@AuthenticationPrincipal UsuarioAutenticado usuario,
                                                                             @Valid @RequestBody CriarParceriaRequisicao corpo,
                                                                             HttpServletRequest requisicao) {
        ParceriaDetalheDto<EmailResumoComCodigoDto> criada = servico.criar(usuario, corpo, requisicao);
        return ResponseEntity.created(URI.create("/api/parcerias/" + criada.id())).body(criada);
    }

    @GetMapping("/{id}")
    public ParceriaDetalheDto<EmailResumoDto> detalhe(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        return servico.detalhe(usuario, id);
    }

    @PatchMapping("/{id}")
    public ParceriaDetalheDto<EmailResumoDto> editar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                                     @Valid @RequestBody EditarParceriaRequisicao corpo,
                                                     HttpServletRequest requisicao) {
        return edicao.editar(usuario, id, corpo, requisicao);
    }
}
