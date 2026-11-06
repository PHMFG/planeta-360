package br.com.rbs.portal.consolidacao;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.consolidacao.ConsultaDeMidiaDtos.ConsultaDaRbsDto;
import br.com.rbs.portal.consolidacao.ConsultaDeMidiaService.Arquivo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas 107 e 108 do contrato (docs/api.md): a consulta da mídia de todas as parcerias da edição, com a marca em cada linha.
 * [PERMISSAO] Só o Admin RBS (PermissaoAntesDoCorpo confere antes de tudo); a edição padrão é a mais nova. As regras ficam no
 * ConsultaDeMidiaService.
 */
@RestController
@RequestMapping("/api")
public class ConsultaDaRbsController {

    private final ConsultaDeMidiaService servico;

    public ConsultaDaRbsController(ConsultaDeMidiaService servico) {
        this.servico = servico;
    }

    /** 107. A mídia de todas as parcerias da edição (ou das filtradas por parceria e cota). */
    @GetMapping("/midia")
    public ConsultaDaRbsDto midia(@AuthenticationPrincipal UsuarioAutenticado usuario,
                                  @RequestParam(required = false) Long eventoId,
                                  @RequestParam(required = false) Long parceriaId,
                                  @RequestParam(required = false) String cota,
                                  @RequestParam(required = false) String mes,
                                  @RequestParam(required = false) String veiculo,
                                  @RequestParam(required = false) String formato,
                                  @RequestParam(required = false) String tipoMidia,
                                  @RequestParam(required = false) String unidade) {
        return servico.consultaDaRbs(usuario, eventoId, parceriaId, cota, mes, veiculo, formato, tipoMidia, unidade);
    }

    /** 108. A mesma consulta em .xlsx, com a coluna Marca primeiro. */
    @GetMapping("/midia.xlsx")
    public ResponseEntity<byte[]> xlsx(@AuthenticationPrincipal UsuarioAutenticado usuario,
                                       @RequestParam(required = false) Long eventoId,
                                       @RequestParam(required = false) Long parceriaId,
                                       @RequestParam(required = false) String cota,
                                       @RequestParam(required = false) String mes,
                                       @RequestParam(required = false) String veiculo,
                                       @RequestParam(required = false) String formato,
                                       @RequestParam(required = false) String tipoMidia,
                                       @RequestParam(required = false) String unidade,
                                       HttpServletRequest requisicao) {
        return baixar(servico.xlsxDaRbs(usuario, eventoId, parceriaId, cota, mes, veiculo, formato, tipoMidia, unidade,
                requisicao));
    }

    /** 108. A mesma consulta em CSV, com a coluna Marca primeiro. */
    @GetMapping("/midia.csv")
    public ResponseEntity<byte[]> csv(@AuthenticationPrincipal UsuarioAutenticado usuario,
                                      @RequestParam(required = false) Long eventoId,
                                      @RequestParam(required = false) Long parceriaId,
                                      @RequestParam(required = false) String cota,
                                      @RequestParam(required = false) String mes,
                                      @RequestParam(required = false) String veiculo,
                                      @RequestParam(required = false) String formato,
                                      @RequestParam(required = false) String tipoMidia,
                                      @RequestParam(required = false) String unidade,
                                      HttpServletRequest requisicao) {
        return baixar(servico.csvDaRbs(usuario, eventoId, parceriaId, cota, mes, veiculo, formato, tipoMidia, unidade,
                requisicao));
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
