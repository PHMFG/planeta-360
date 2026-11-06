package br.com.rbs.portal.consolidacao;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.consolidacao.ConsultaDeMidiaService.Arquivo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas 105 a 108 do contrato (docs/api.md): a consulta da mídia com filtros, da parceria (105 e 106) e da RBS (107 e 108).
 * [PERMISSAO] A marca precisa ser da parceria (404) e ter a aba MIDIA aberta (403); o recorte por mês é das Master e da RBS;
 * as rotas da RBS são só dela (PermissaoAntesDoCorpo). As regras ficam no ConsultaDeMidiaService.
 */
@RestController
@RequestMapping("/api")
public class ConsultaDeMidiaController {

    private final ConsultaDeMidiaService servico;

    public ConsultaDeMidiaController(ConsultaDeMidiaService servico) {
        this.servico = servico;
    }

    /** 105. A mídia da parceria, linha do plano × mês (ou uma linha por plano, sem o recorte). */
    @GetMapping("/parcerias/{id}/midia")
    public Object midia(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                        @RequestParam(required = false) String mes,
                        @RequestParam(required = false) String veiculo,
                        @RequestParam(required = false) String formato,
                        @RequestParam(required = false) String tipoMidia,
                        @RequestParam(required = false) String unidade) {
        return servico.consulta(usuario, id, mes, veiculo, formato, tipoMidia, unidade);
    }

    /** 106. A mesma consulta em .xlsx. */
    @GetMapping("/parcerias/{id}/midia.xlsx")
    public ResponseEntity<byte[]> xlsx(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                       @RequestParam(required = false) String mes,
                                       @RequestParam(required = false) String veiculo,
                                       @RequestParam(required = false) String formato,
                                       @RequestParam(required = false) String tipoMidia,
                                       @RequestParam(required = false) String unidade,
                                       HttpServletRequest requisicao) {
        return baixar(servico.xlsx(usuario, id, mes, veiculo, formato, tipoMidia, unidade, requisicao));
    }

    /** 106. A mesma consulta em CSV (UTF-8 com BOM, ponto e vírgula). */
    @GetMapping("/parcerias/{id}/midia.csv")
    public ResponseEntity<byte[]> csv(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                      @RequestParam(required = false) String mes,
                                      @RequestParam(required = false) String veiculo,
                                      @RequestParam(required = false) String formato,
                                      @RequestParam(required = false) String tipoMidia,
                                      @RequestParam(required = false) String unidade,
                                      HttpServletRequest requisicao) {
        return baixar(servico.csv(usuario, id, mes, veiculo, formato, tipoMidia, unidade, requisicao));
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
