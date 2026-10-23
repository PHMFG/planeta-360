package br.com.rbs.portal.consolidacao;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.consolidacao.ConsolidacaoDtos.ConsolidacaoDto;
import br.com.rbs.portal.consolidacao.ConsolidacaoDtos.PainelDto;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas 29 e 30 do contrato (docs/api.md): previsto x exibido de uma parceria e painel da RBS. Atende os itens 5 e 7 da
 * Entrega 2. [ISOLAMENTO] A 29 serve ao patrocinador da parceria e à RBS; [PERMISSAO] a 30 é só da RBS (403 para o
 * patrocinador). As regras ficam no ConsolidacaoService. As cargas de planilha (rotas 26 a 28) entram na parte B da etapa.
 */
@RestController
@RequestMapping("/api")
public class ConsolidacaoController {

    private final ConsolidacaoService servico;

    public ConsolidacaoController(ConsolidacaoService servico) {
        this.servico = servico;
    }

    /** 29. Previsto x exibido por unidade, veículo e linha; aplicações, entregas, cortesias e faltantes. */
    @GetMapping("/parcerias/{id}/consolidacao")
    public ConsolidacaoDto consolidar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        return servico.consolidar(usuario, id);
    }

    /** 30. Os números do painel da edição (padrão: a mais nova). */
    @GetMapping("/painel")
    public PainelDto painel(@AuthenticationPrincipal UsuarioAutenticado usuario,
                            @RequestParam(required = false) Long eventoId) {
        return servico.painel(usuario, eventoId);
    }
}
