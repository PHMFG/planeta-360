package br.com.rbs.portal.importacao;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.importacao.ImportacaoDtos.ConfirmacaoDaImportacao;
import br.com.rbs.portal.importacao.ImportacaoDtos.EdicaoDeLinha;
import br.com.rbs.portal.importacao.ImportacaoDtos.EdicaoEmLote;
import br.com.rbs.portal.importacao.ImportacaoDtos.ImportacaoDetalhe;
import br.com.rbs.portal.importacao.ImportacaoDtos.ImportacaoResumo;
import br.com.rbs.portal.importacao.ImportacaoDtos.ItemDaLinha;
import br.com.rbs.portal.importacao.ImportacaoDtos.LinhasAlteradasDto;
import br.com.rbs.portal.importacao.ImportacaoDtos.OrigemDaLinha;
import br.com.rbs.portal.importacao.ImportacaoDtos.PaginaDeImportacoes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * As rotas 109 a 116 e a 112b do contrato (docs/api.md): o Atlas de entrada. [PERMISSAO] A criação, a lista e a retirada são só da
 * RBS; as outras são da RBS e da marca nas importações dela. A conferência do perfil vem antes do corpo no
 * PermissaoAntesDoCorpo; a parceria e a situação são conferidas no ImportacaoService.
 */
@RestController
@RequestMapping("/api/importacoes")
public class ImportacaoController {

    private final ImportacaoService servico;

    public ImportacaoController(ImportacaoService servico) {
        this.servico = servico;
    }

    /** 109. multipart: arquivo ou texto, eventoId, alcance, cota/parceriaId e destino (opcional). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportacaoResumo> criar(@AuthenticationPrincipal UsuarioAutenticado usuario,
                                                  @RequestPart(name = "arquivo", required = false) MultipartFile arquivo,
                                                  @RequestParam(required = false) String eventoId,
                                                  @RequestParam(required = false) String alcance,
                                                  @RequestParam(required = false) String cota,
                                                  @RequestParam(required = false) Long parceriaId,
                                                  @RequestParam(required = false) String destino,
                                                  @RequestParam(required = false) String texto,
                                                  HttpServletRequest requisicao) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(servico.criar(usuario, arquivo, eventoId, alcance, cota, parceriaId,
                destino, texto, requisicao));
    }

    /** 110. A lista da RBS, a mais nova primeiro. */
    @GetMapping
    public PaginaDeImportacoes listar(@AuthenticationPrincipal UsuarioAutenticado usuario,
                                      @RequestParam(required = false) Long eventoId,
                                      @RequestParam(required = false) String situacao,
                                      @RequestParam(defaultValue = "0") @Min(value = 0, message = "A página começa em 0.") int pagina,
                                      @RequestParam(defaultValue = "50")
                                      @Min(value = 1, message = "O tamanho vai de 1 a 200.")
                                      @Max(value = 200, message = "O tamanho vai de 1 a 200.") int tamanho) {
        return servico.listar(usuario, eventoId, situacao, pagina, tamanho);
    }

    /** 111. O detalhe com as linhas propostas. */
    @GetMapping("/{id}")
    public ImportacaoDetalhe detalhar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        return servico.detalhar(usuario, id);
    }

    /** 112. A edição de uma linha. */
    @PatchMapping("/{id}/linhas/{linhaId}")
    public ItemDaLinha editarLinha(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                   @PathVariable long linhaId, @RequestBody(required = false) EdicaoDeLinha corpo,
                                   HttpServletRequest requisicao) {
        return servico.editarLinha(usuario, id, linhaId, corpo, requisicao);
    }

    /** 112b. A decisão em lote. */
    @PatchMapping("/{id}/linhas")
    public LinhasAlteradasDto editarEmLote(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                           @RequestBody(required = false) EdicaoEmLote corpo) {
        return servico.editarEmLote(usuario, id, corpo);
    }

    /** 113. A confirmação. */
    @PostMapping("/{id}/confirmar")
    public ImportacaoDetalhe confirmar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                       @RequestBody(required = false) ConfirmacaoDaImportacao corpo,
                                       HttpServletRequest requisicao) {
        return servico.confirmar(usuario, id, corpo == null ? null : corpo.linhas(), requisicao);
    }

    /** 114. Descarta a prévia. */
    @PostMapping("/{id}/descartar")
    public ImportacaoResumo descartar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                      HttpServletRequest requisicao) {
        return servico.descartar(usuario, id, requisicao);
    }

    /** 115. Retira a importação confirmada (só a RBS). */
    @PostMapping("/{id}/retirar")
    public ImportacaoResumo retirar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                    HttpServletRequest requisicao) {
        return servico.retirar(usuario, id, requisicao);
    }

    /** 116. A origem de um campo da linha para o visualizador. */
    @GetMapping("/{id}/origem/{linhaId}")
    public OrigemDaLinha origem(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                @PathVariable long linhaId, @RequestParam(required = false) String campo) {
        return servico.origem(usuario, id, linhaId, campo);
    }
}
