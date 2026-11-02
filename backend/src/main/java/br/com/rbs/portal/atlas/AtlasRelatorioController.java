package br.com.rbs.portal.atlas;

import br.com.rbs.portal.atlas.AtlasDtos.RespostaDoCartaoRequisicao;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.RelatorioAtlasDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.RelatorioAtlasResumoDto;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas 46 a 48 e 65 do contrato (docs/api.md): o cartão de perguntas, o relatório que o Atlas monta e o descarte dele. [ATLAS] Só existem com
 * {@code atlas.relatorio.ligado=true}; sem a propriedade (o padrão: o Atlas é só o chat) o Spring não as mapeia e elas respondem
 * 404, como qualquer rota que não existe. As rotas 46 e 65 são só do Admin Patrocinador (o PermissaoAntesDoCorpo responde 403 à RBS
 * antes de o corpo ser lido); as rotas 47 e 48 são de quem é da parceria e da RBS. [ISOLAMENTO] Cartão de outra parceria ou de outro
 * usuário dá 404. Todas as regras estão no AtlasService.
 */
@RestController
@ConditionalOnProperty(name = "atlas.relatorio.ligado", havingValue = "true")
public class AtlasRelatorioController {

    private final AtlasService servico;

    public AtlasRelatorioController(AtlasService servico) {
        this.servico = servico;
    }

    /** 46. Em fluxo com {@code Accept: text/event-stream}, como a 44. */
    @PostMapping("/api/atlas/solicitacoes/{id}/respostas")
    public Object responderCartao(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                  @RequestBody RespostaDoCartaoRequisicao corpo, HttpServletRequest requisicao) {
        if (AtlasController.pedeFluxo(requisicao)) {
            return servico.responderCartaoEmFluxo(usuario, id, corpo, requisicao);
        }
        return servico.responderCartao(usuario, id, corpo, requisicao);
    }

    /** 47. */
    @GetMapping("/api/atlas/relatorios/{id}")
    public RelatorioAtlasDto verRelatorio(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        return servico.verRelatorio(usuario, id);
    }

    /** 48. */
    @GetMapping("/api/parcerias/{id}/atlas/relatorios")
    public List<RelatorioAtlasResumoDto> listarRelatorios(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        return servico.listarRelatorios(usuario, id);
    }

    /** 65. O mesmo corpo da 47, já com o status {@code DESCARTADO}. Só o Admin Patrocinador (a RBS recebe 403 antes de tudo). */
    @PostMapping("/api/atlas/relatorios/{id}/descartar")
    public RelatorioAtlasDto descartarRelatorio(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                                HttpServletRequest requisicao) {
        return servico.descartarRelatorio(usuario, id, requisicao);
    }
}
