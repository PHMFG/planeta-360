package br.com.rbs.portal.cortesias;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.cortesias.CortesiaDtos.CortesiasDto;
import br.com.rbs.portal.cortesias.CortesiaDtos.CotasRequisicao;
import br.com.rbs.portal.cortesias.CortesiaDtos.DestinacaoEdicao;
import br.com.rbs.portal.cortesias.CortesiaDtos.DestinacaoRequisicao;
import br.com.rbs.portal.cortesias.CortesiaDtos.PrazoDto;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas 80 a 85 do contrato (docs/api.md): a aba Cortesias. [PERMISSAO] As rotas 81 e 82 são só da RBS (o PermissaoAntesDoCorpo
 * responde 403 à marca antes de ler o corpo); as outras são da marca da parceria (com a aba CORTESIAS aberta) e da RBS. As regras
 * estão no CortesiaService; os corpos são records sem {@code @Valid} de propósito: o service os confere depois do 404 e do 403, e as
 * frases dos campos saem dele.
 */
@RestController
public class CortesiaController {

    private final CortesiaService servico;

    public CortesiaController(CortesiaService servico) {
        this.servico = servico;
    }

    /** 80. */
    @GetMapping("/api/parcerias/{id}/cortesias")
    public CortesiasDto listar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        return servico.listar(usuario, id);
    }

    /** 81. */
    @PatchMapping("/api/parcerias/{id}/cortesias/cotas")
    public CortesiasDto gravarCotas(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                    @RequestBody CotasRequisicao corpo, HttpServletRequest requisicao) {
        return servico.gravarCotas(usuario, id, corpo, requisicao);
    }

    /** 82. */
    @PatchMapping("/api/eventos/{id}/cortesias")
    public PrazoDto gravarPrazo(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                @RequestBody Map<String, Object> corpo, HttpServletRequest requisicao) {
        return servico.gravarPrazo(usuario, id, corpo, requisicao);
    }

    /** 83. */
    @PostMapping("/api/parcerias/{id}/cortesias/destinacoes")
    public ResponseEntity<CortesiasDto> destinar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                                 @RequestBody DestinacaoRequisicao corpo, HttpServletRequest requisicao) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servico.destinar(usuario, id, corpo, requisicao));
    }

    /** 84. */
    @PatchMapping("/api/parcerias/{id}/cortesias/destinacoes/{destId}")
    public CortesiasDto editar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id, @PathVariable long destId,
                               @RequestBody DestinacaoEdicao corpo, HttpServletRequest requisicao) {
        return servico.editar(usuario, id, destId, corpo, requisicao);
    }

    /** 85. */
    @DeleteMapping("/api/parcerias/{id}/cortesias/destinacoes/{destId}")
    public ResponseEntity<Void> retirar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                        @PathVariable long destId, HttpServletRequest requisicao) {
        servico.retirar(usuario, id, destId, requisicao);
        return ResponseEntity.noContent().build();
    }
}
