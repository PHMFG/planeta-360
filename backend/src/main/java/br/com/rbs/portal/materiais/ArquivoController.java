package br.com.rbs.portal.materiais;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.materiais.ArquivoService.ArquivoBaixado;
import br.com.rbs.portal.materiais.MateriaisDtos.ArquivoEnviadoDto;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Rotas 17 e 18: enviar um arquivo para uma entrega e baixá-lo. Atende o item 4 da Entrega 2. [UPLOAD] O envio é
 * multipart/form-data; o download é sempre anexo, com nosniff. [ISOLAMENTO] Entrega ou arquivo de outra parceria dá 404.
 * As regras estão no ArquivoService; aqui só entram as partes do formulário e os cabeçalhos HTTP.
 */
@RestController
@RequestMapping("/api")
public class ArquivoController {

    private final ArquivoService servico;

    public ArquivoController(ArquivoService servico) {
        this.servico = servico;
    }

    /**
     * 17. As partes "categoria" e "observacao" são opcionais. A parte "arquivo" é conferida no service (e não por
     * required=true) para a falta dela virar 422 com campos.arquivo, como o contrato diz, e não 400.
     */
    @PostMapping(path = "/entregas/{id}/arquivos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ArquivoEnviadoDto> enviar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                                    @RequestPart(name = "arquivo", required = false) MultipartFile arquivo,
                                                    @RequestParam(required = false) String categoria,
                                                    @RequestParam(required = false) String observacao,
                                                    HttpServletRequest requisicao) {
        ArquivoEnviadoDto enviado = servico.enviar(usuario, id, arquivo, categoria, observacao, requisicao);
        return ResponseEntity.created(URI.create("/api/arquivos/" + enviado.id())).body(enviado);
    }

    /**
     * 18. [UPLOAD] Content-Disposition: attachment com o nome original em UTF-8 (filename*) faz o navegador baixar o
     * arquivo, nunca abri-lo na página; X-Content-Type-Options: nosniff impede que ele reinterprete o tipo.
     */
    @GetMapping("/arquivos/{id}")
    public ResponseEntity<Resource> baixar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                           HttpServletRequest requisicao) {
        ArquivoBaixado arquivo = servico.baixar(usuario, id, requisicao);
        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setContentType(tipo(arquivo.mimeType()));
        cabecalhos.setContentLength(arquivo.tamanhoBytes());
        cabecalhos.setContentDisposition(ContentDisposition.attachment().filename(arquivo.nomeOriginal(), StandardCharsets.UTF_8).build());
        cabecalhos.set("X-Content-Type-Options", "nosniff");
        return ResponseEntity.ok().headers(cabecalhos).body(new FileSystemResource(arquivo.caminho()));
    }

    private static MediaType tipo(String mime) {
        try {
            return MediaType.parseMediaType(mime);
        } catch (IllegalArgumentException invalido) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
