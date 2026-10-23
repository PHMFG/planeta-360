package br.com.rbs.portal.parceria;

import br.com.rbs.portal.parceria.ParceriaDtos.EventoCompletoDto;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/eventos (rota 6): as edições do festival, da mais nova para a mais antiga. Atende o item 3 da Entrega 2.
 * Qualquer usuário logado pode ler (a exigência de sessão vem da SegurancaConfig); não há dado de parceria aqui.
 */
@RestController
@RequestMapping("/api/eventos")
public class EventoController {

    private final ParceriaService servico;

    public EventoController(ParceriaService servico) {
        this.servico = servico;
    }

    @GetMapping
    public List<EventoCompletoDto> listar() {
        return servico.eventos();
    }
}
