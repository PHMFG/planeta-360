package br.com.rbs.portal.importacao;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.importacao.ImportacaoDtos.ImportacaoResumo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * A rota 117 do contrato: a marca manda a planilha da agência em Aplicações, e a importação nasce restrita à parceria dela e
 * ao destino DADOS_ATIVACAO. [PERMISSAO] A parceria e a aba APLICACOES são conferidas antes do corpo pelo
 * PermissaoAntesDoCorpo; o service repete e confere a situação da parceria. A RBS também pode chamar.
 */
@RestController
@RequestMapping("/api/parcerias/{id}/importacoes")
public class ImportacaoDaMarcaController {

    private final ImportacaoService servico;

    public ImportacaoDaMarcaController(ImportacaoService servico) {
        this.servico = servico;
    }

    /** 117. multipart com a planilha da agência (.xlsx, .xls ou .csv). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportacaoResumo> criar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                                  @RequestPart(name = "arquivo", required = false) MultipartFile arquivo,
                                                  HttpServletRequest requisicao) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(servico.criarDaMarca(usuario, id, arquivo, requisicao));
    }
}
