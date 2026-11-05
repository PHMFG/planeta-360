package br.com.rbs.portal.consolidacao;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.consolidacao.ComprovacaoMensalDtos.ComprovacaoMensalDto;
import br.com.rbs.portal.consolidacao.ComprovacaoMensalService.Arquivo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas 88 a 91 do contrato (docs/api.md): a comprovação mensal de mídia (R3.2 e R3.3). [PERMISSAO] As rotas de parceria servem
 * à cota Master (com a aba MIDIA aberta) e à RBS; a do lote (91) é só da RBS (PermissaoAntesDoCorpo). As regras ficam no
 * ComprovacaoMensalService.
 */
@RestController
@RequestMapping("/api")
public class ComprovacaoMensalController {

    private final ComprovacaoMensalService servico;

    public ComprovacaoMensalController(ComprovacaoMensalService servico) {
        this.servico = servico;
    }

    /** 88. Mês x tipo de mídia x unidade, com os totais por mês e unidade. */
    @GetMapping("/parcerias/{id}/comprovacao-mensal")
    public ComprovacaoMensalDto comprovacao(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        return servico.comprovacao(usuario, id);
    }

    /** 89. A mesma comprovação em planilha .xlsx. */
    @GetMapping("/parcerias/{id}/comprovacao-mensal.xlsx")
    public ResponseEntity<byte[]> xlsx(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                       HttpServletRequest requisicao) {
        return baixar(servico.xlsx(usuario, id, requisicao));
    }

    /** 90. A mesma comprovação em CSV (UTF-8 com BOM, separado por ponto e vírgula). */
    @GetMapping("/parcerias/{id}/comprovacao-mensal.csv")
    public ResponseEntity<byte[]> csv(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                      HttpServletRequest requisicao) {
        return baixar(servico.csv(usuario, id, requisicao));
    }

    /** 91. O lote da edição: um .xlsx com uma aba por parceria Master. Só a RBS. */
    @GetMapping("/eventos/{id}/comprovacao-mensal.xlsx")
    public ResponseEntity<byte[]> lote(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                       HttpServletRequest requisicao) {
        return baixar(servico.lote(usuario, id, requisicao));
    }

    /** [UPLOAD] "attachment" para o navegador baixar; o nome do arquivo só tem ASCII e hífen. */
    private static ResponseEntity<byte[]> baixar(Arquivo arquivo) {
        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.set(HttpHeaders.CONTENT_TYPE, arquivo.tipoMime());
        cabecalhos.setContentDisposition(ContentDisposition.attachment().filename(arquivo.nome()).build());
        cabecalhos.set("X-Content-Type-Options", "nosniff");
        return ResponseEntity.ok().headers(cabecalhos).body(arquivo.bytes());
    }
}
