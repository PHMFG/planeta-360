package br.com.rbs.portal.atlas.diagnostico;

import br.com.rbs.portal.atlas.diagnostico.DiagnosticoDtos.DiagnosticoDaConversaDto;
import br.com.rbs.portal.atlas.diagnostico.DiagnosticoDtos.ProvedoresDto;
import br.com.rbs.portal.atlas.diagnostico.DiagnosticoDtos.TesteDeProvedorDto;
import br.com.rbs.portal.atlas.diagnostico.DiagnosticoDtos.TurnoDaListaDto;
import br.com.rbs.portal.atlas.diagnostico.DiagnosticoDtos.TurnoDetalhadoDto;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.materiais.MateriaisDtos.PaginaDto;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas 49 a 53 do contrato (docs/api.md, "Diagnóstico do Atlas"). [ATLAS] Sempre mapeadas, sem {@code @ConditionalOnProperty}:
 * assim o PermissaoAntesDoCorpoTest as enxerga, e o service responde 404 quando o diagnóstico está desligado. [PERMISSAO] A 49 é só
 * do Admin Patrocinador (dono da conversa); as 50 a 53 são só do Admin RBS: o PermissaoAntesDoCorpo responde 403 antes de tudo. Todas
 * as regras estão nos services; aqui só entram os parâmetros e o usuário da sessão.
 */
@RestController
public class DiagnosticoController {

    private final DiagnosticoService diagnostico;
    private final ProvedoresService provedores;

    public DiagnosticoController(DiagnosticoService diagnostico, ProvedoresService provedores) {
        this.diagnostico = diagnostico;
        this.provedores = provedores;
    }

    /** 49. O diagnóstico de uma conversa, para o dono dela. */
    @GetMapping("/api/atlas/conversas/{id}/diagnostico")
    public DiagnosticoDaConversaDto daConversa(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        return diagnostico.daConversa(usuario, id);
    }

    /** 50. Os turnos de todas as conversas, paginados, com filtros. */
    @GetMapping("/api/atlas/diagnostico/turnos")
    public PaginaDto<TurnoDaListaDto> turnos(@AuthenticationPrincipal UsuarioAutenticado usuario,
                                             @RequestParam(required = false) Long parceriaId,
                                             @RequestParam(required = false) String de,
                                             @RequestParam(required = false) String ate,
                                             @RequestParam(required = false) String resultado,
                                             @RequestParam(defaultValue = "0") @Min(value = 0, message = "A página começa em 0.") int pagina,
                                             @RequestParam(defaultValue = "50")
                                             @Min(value = 1, message = "O tamanho vai de 1 a 200.")
                                             @Max(value = 200, message = "O tamanho vai de 1 a 200.") int tamanho) {
        return diagnostico.turnos(usuario, parceriaId, de, ate, resultado, pagina, tamanho);
    }

    /** 51. Um turno com as etapas. */
    @GetMapping("/api/atlas/diagnostico/turnos/{turno}")
    public TurnoDetalhadoDto turno(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable String turno) {
        return diagnostico.turno(usuario, turno);
    }

    /** 52. O estado dos provedores e o teto diário por parceria. */
    @GetMapping("/api/atlas/provedores")
    public ProvedoresDto provedores(@AuthenticationPrincipal UsuarioAutenticado usuario) {
        return provedores.listar(usuario);
    }

    /** 53. Uma chamada mínima a um provedor ({@code llm}, {@code jev} ou {@code gemini}). */
    @PostMapping("/api/atlas/provedores/{nome}/teste")
    public TesteDeProvedorDto testar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable String nome) {
        return provedores.testar(usuario, nome);
    }
}
