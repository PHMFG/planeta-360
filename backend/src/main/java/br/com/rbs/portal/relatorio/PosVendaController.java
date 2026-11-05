package br.com.rbs.portal.relatorio;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.relatorio.PosVendaDtos.InformarResultadoRequisicao;
import br.com.rbs.portal.relatorio.PosVendaDtos.PosVendaDaEdicaoDto;
import br.com.rbs.portal.relatorio.PosVendaDtos.PosVendaDaParceriaDto;
import br.com.rbs.portal.relatorio.PosVendaDtos.PublicarRequisicao;
import br.com.rbs.portal.relatorio.PosVendaDtos.ResultadoDto;
import br.com.rbs.portal.relatorio.PosVendaService.ResultadoGravado;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas 96 a 101 (docs/api.md): o anexo "Pós-venda da edição" do relatório (R8.1) e os números da edição (R8.2). [RELATORIO] A 96 serve à
 * marca da parceria (com o relatório disponibilizado) e à RBS; da 97 à 101 são só da RBS: [PERMISSAO] a marca recebe 403 antes de o corpo
 * ser lido (PermissaoAntesDoCorpo). As regras estão no PosVendaService.
 */
@RestController
@RequestMapping("/api")
public class PosVendaController {

    private final PosVendaService servico;

    public PosVendaController(PosVendaService servico) {
        this.servico = servico;
    }

    /** 96. O anexo em vigor da parceria, com os links dos posts. A marca só o vê com o relatório disponibilizado (senão, 404). */
    @GetMapping("/parcerias/{id}/posvenda")
    public PosVendaDaParceriaDto ver(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        return servico.ver(usuario, id);
    }

    /** 97. Publica uma versão nova do anexo da parceria (201, com o endereço da rota 96 em Location). */
    @PostMapping("/parcerias/{id}/posvenda")
    public ResponseEntity<PosVendaDaEdicaoDto> publicar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                                        @RequestBody(required = false) PublicarRequisicao corpo, HttpServletRequest requisicao) {
        PosVendaDaEdicaoDto publicado = servico.publicar(usuario, id, corpo, requisicao);
        return ResponseEntity.created(URI.create("/api/parcerias/" + id + "/posvenda")).body(publicado);
    }

    /** 98. As versões de todas as parcerias da edição, retiradas inclusive. */
    @GetMapping("/eventos/{id}/posvenda")
    public List<PosVendaDaEdicaoDto> listar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        return servico.listar(usuario, id);
    }

    /** 99. Retira a versão (não apaga) e responde 204; retirar de novo também. */
    @DeleteMapping("/posvenda/{id}")
    public ResponseEntity<Void> retirar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id, HttpServletRequest requisicao) {
        servico.retirar(usuario, id, requisicao);
        return ResponseEntity.noContent().build();
    }

    /** 100. Os números da edição. */
    @GetMapping("/eventos/{id}/resultados")
    public List<ResultadoDto> listarResultados(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        return servico.listarResultados(usuario, id);
    }

    /** 101. Informa um número da edição: 201 se o indicador é novo, 200 se trocou o valor de um que já existia. */
    @PutMapping("/eventos/{id}/resultados")
    public ResponseEntity<ResultadoDto> informarResultado(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                                          @RequestBody(required = false) InformarResultadoRequisicao corpo,
                                                          HttpServletRequest requisicao) {
        ResultadoGravado gravado = servico.informarResultado(usuario, id, corpo, requisicao);
        return gravado.criado()
                ? ResponseEntity.created(URI.create("/api/eventos/" + id + "/resultados")).body(gravado.resultado())
                : ResponseEntity.ok(gravado.resultado());
    }
}
