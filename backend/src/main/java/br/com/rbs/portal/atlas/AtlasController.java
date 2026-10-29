package br.com.rbs.portal.atlas;

import br.com.rbs.portal.atlas.AtlasDtos.AbrirConversaRequisicao;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ConversasDaParceriaDto;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.AtlasDtos.RespostaDoCartaoRequisicao;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.RelatorioAtlasDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.RelatorioAtlasResumoDto;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas 42 a 48 do contrato (docs/api.md): a conversa da marca com o Atlas, o cartão de perguntas e o relatório que ele monta.
 * [ATLAS] [PERMISSAO] As rotas 42 a 46 são só do Admin Patrocinador: o PermissaoAntesDoCorpo responde 403 à RBS antes de o corpo
 * ser lido (lista ROTAS_SO_DO_PATROCINADOR). As rotas 47 e 48 são de quem é da parceria e da RBS. [ISOLAMENTO] Recurso de outra
 * parceria dá 404, e conversa ou cartão de outro usuário da mesma parceria também. Todas as regras estão no AtlasService; aqui
 * só entram o corpo e o usuário da sessão. O corpo não leva Bean Validation de propósito: o service confere o formato depois do
 * 404 e do 403, para quem não pode nunca ver um erro de campo.
 */
@RestController
public class AtlasController {

    private final AtlasService servico;

    public AtlasController(AtlasService servico) {
        this.servico = servico;
    }

    /** 42. Corpo opcional: vazio abre uma conversa vazia; o atalho abre e já monta o relatório (plano B). Responde 201. */
    @PostMapping("/api/parcerias/{id}/atlas/conversas")
    public ResponseEntity<ConversaDto> abrirConversa(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                                     @RequestBody(required = false) AbrirConversaRequisicao corpo,
                                                     HttpServletRequest requisicao) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servico.abrirConversa(usuario, id, corpo, requisicao));
    }

    /** 43. */
    @GetMapping("/api/parcerias/{id}/atlas/conversas")
    public ConversasDaParceriaDto listarConversas(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        return servico.listarConversas(usuario, id);
    }

    /** 44. */
    @PostMapping("/api/atlas/conversas/{id}/mensagens")
    public ConversaDto enviarMensagem(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                      @RequestBody MensagemRequisicao corpo, HttpServletRequest requisicao) {
        return servico.enviarMensagem(usuario, id, corpo, requisicao);
    }

    /** 45. */
    @GetMapping("/api/atlas/conversas/{id}")
    public ConversaDto verConversa(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        return servico.verConversa(usuario, id);
    }

    /** 46. */
    @PostMapping("/api/atlas/solicitacoes/{id}/respostas")
    public ConversaDto responderCartao(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                       @RequestBody RespostaDoCartaoRequisicao corpo, HttpServletRequest requisicao) {
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
}
