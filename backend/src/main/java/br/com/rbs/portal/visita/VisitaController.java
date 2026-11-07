package br.com.rbs.portal.visita;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.visita.VisitaDtos.NovidadesDto;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rota 120 do contrato (docs/api.md): a visita de cada pessoa a cada aba e o selo "novo" (S3). [PERMISSAO] A marca precisa
 * ser da parceria e a aba precisa estar aberta para ela; a RBS passa em qualquer aba. As regras ficam no VisitaService.
 */
@RestController
@RequestMapping("/api")
public class VisitaController {

    private final VisitaService servico;

    public VisitaController(VisitaService servico) {
        this.servico = servico;
    }

    /** 120a. Marca a visita da pessoa àquela aba (ou ao relatório). Responde 204, sem corpo. */
    @PostMapping("/parcerias/{id}/visitas/{aba}")
    public ResponseEntity<Void> visitar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                        @PathVariable String aba) {
        servico.registrar(usuario, id, aba);
        return ResponseEntity.noContent().build();
    }

    /** 120b. As oito abas, com a última visita da pessoa e o selo "novo". */
    @GetMapping("/parcerias/{id}/novidades")
    public NovidadesDto novidades(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        return servico.novidades(usuario, id);
    }
}
