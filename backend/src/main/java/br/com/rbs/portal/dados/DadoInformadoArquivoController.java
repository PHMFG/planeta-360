package br.com.rbs.portal.dados;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.dados.DadosDtos.VigenteDto;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Rota 66 do contrato (docs/api.md): enviar o arquivo de um campo de arquivo do catálogo, hoje {@code ativacao.planilha_agencia}.
 * [UPLOAD] Multipart com a parte {@code arquivo}, que o service confere (422 com {@code campos.arquivo}, e não 400, se faltar).
 * [ISOLAMENTO] [PERMISSAO] Da marca da parceria (com a aba do campo aberta) e da RBS; parceria de outra marca dá 404. As regras estão no
 * DadoInformadoService; aqui só entram as partes do formulário e o cabeçalho {@code Location}, que aponta para o download (rota 18).
 */
@RestController
public class DadoInformadoArquivoController {

    private final DadoInformadoService servico;

    public DadoInformadoArquivoController(DadoInformadoService servico) {
        this.servico = servico;
    }

    /** 66. Responde 201 com o valor vigente (o que acabou de entrar), com o arquivo dentro. */
    @PostMapping(path = "/api/parcerias/{id}/dados-informados/{chave}/arquivo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VigenteDto> enviar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                             @PathVariable String chave,
                                             @RequestPart(name = "arquivo", required = false) MultipartFile arquivo,
                                             HttpServletRequest requisicao) {
        VigenteDto vigente = servico.enviarArquivo(usuario, id, chave, arquivo, requisicao);
        return ResponseEntity.created(URI.create("/api/arquivos/" + vigente.arquivo().id())).body(vigente);
    }
}
