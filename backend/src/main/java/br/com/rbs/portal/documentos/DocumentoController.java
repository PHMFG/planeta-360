package br.com.rbs.portal.documentos;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.documentos.DocumentoDtos.DocumentoDto;
import br.com.rbs.portal.documentos.DocumentoDtos.MudarStatusRequisicao;
import br.com.rbs.portal.documentos.DocumentoDtos.TrechoDoDocumentoDto;
import br.com.rbs.portal.documentos.DocumentoService.DadosDoEnvio;
import br.com.rbs.portal.materiais.MateriaisDtos.PaginaDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
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
 * Rotas 54 a 58 do contrato (docs/api.md): os documentos do Atlas. [ATLAS] Enviar (54), listar (55), ver (56), aprovar, recusar,
 * revogar e reindexar (57) e ver os trechos (58). [PERMISSAO] Todas são só da RBS, menos a 56, em que a marca vê o documento que o
 * escopo dela permite (e só o que a rota mostra). As regras estão no DocumentoService; aqui entram as partes do formulário, o corpo e
 * os cabeçalhos. As partes são conferidas no service (e não por required=true) para a falta delas virar 422 com "campos".
 */
@RestController
@RequestMapping("/api/documentos")
public class DocumentoController {

    private final DocumentoService servico;

    public DocumentoController(DocumentoService servico) {
        this.servico = servico;
    }

    /**
     * 54. multipart/form-data: arquivo (PDF ou TXT), titulo, chave, tipo, escopo e, conforme o escopo, eventoId (GLOBAL e COTA), cota
     * (COTA) ou parceriaId (PARCERIA). Responde 201 com o documento.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentoDto> enviar(@AuthenticationPrincipal UsuarioAutenticado usuario,
                                               @RequestPart(name = "arquivo", required = false) MultipartFile arquivo,
                                               @RequestParam(required = false) String titulo,
                                               @RequestParam(required = false) String chave,
                                               @RequestParam(required = false) String tipo,
                                               @RequestParam(required = false) String escopo,
                                               @RequestParam(required = false) String eventoId,
                                               @RequestParam(required = false) String cota,
                                               @RequestParam(required = false) String parceriaId,
                                               HttpServletRequest requisicao) {
        byte[] conteudo = null;
        try {
            conteudo = arquivo == null ? null : arquivo.getBytes();
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }
        DocumentoDto criado = servico.enviar(usuario, arquivo == null ? null : arquivo.getOriginalFilename(), conteudo,
                new DadosDoEnvio(titulo, chave, tipo, escopo, eventoId, cota, parceriaId), requisicao);
        return ResponseEntity.created(URI.create("/api/documentos/" + criado.id())).body(criado);
    }

    /** 55. */
    @GetMapping
    public PaginaDto<DocumentoDto> listar(@AuthenticationPrincipal UsuarioAutenticado usuario,
                                          @RequestParam(required = false) Long eventoId,
                                          @RequestParam(required = false) String escopo,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String tipo,
                                          @RequestParam(required = false) String chave,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "0") @Min(value = 0, message = "A página começa em 0.") int pagina,
                                          @RequestParam(defaultValue = "50")
                                          @Min(value = 1, message = "O tamanho vai de 1 a 200.")
                                          @Max(value = 200, message = "O tamanho vai de 1 a 200.") int tamanho) {
        return servico.listar(usuario, eventoId, escopo, status, tipo, chave, q, pagina, tamanho);
    }

    /** 56. A RBS recebe o documento inteiro; a marca, só o que a rota mostra a ela (título, tipo, versão, páginas e se foi substituído). */
    @GetMapping("/{id}")
    public Object ver(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        return servico.ver(usuario, id);
    }

    /** 57. */
    @PatchMapping("/{id}/status")
    public DocumentoDto mudarStatus(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                    @Valid @RequestBody MudarStatusRequisicao corpo, HttpServletRequest requisicao) {
        return servico.mudarStatus(usuario, id, corpo.acao(), corpo.observacao(), requisicao);
    }

    /** 58. */
    @GetMapping("/{id}/trechos")
    public PaginaDto<TrechoDoDocumentoDto> trechos(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                                   @RequestParam(defaultValue = "0") @Min(value = 0, message = "A página começa em 0.") int pagina,
                                                   @RequestParam(defaultValue = "50")
                                                   @Min(value = 1, message = "O tamanho vai de 1 a 200.")
                                                   @Max(value = 200, message = "O tamanho vai de 1 a 200.") int tamanho) {
        return servico.trechos(usuario, id, pagina, tamanho);
    }
}
