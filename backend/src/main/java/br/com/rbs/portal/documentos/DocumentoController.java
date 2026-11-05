package br.com.rbs.portal.documentos;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.documentos.DocumentoDtos.DocumentoDto;
import br.com.rbs.portal.documentos.DocumentoDtos.MudarStatusRequisicao;
import br.com.rbs.portal.documentos.DocumentoDtos.PaginasForaDoAtlasRequisicao;
import br.com.rbs.portal.documentos.DocumentoDtos.TrechoDoDocumentoDto;
import br.com.rbs.portal.documentos.DocumentoDtos.TrechoDoVisualizadorDto;
import br.com.rbs.portal.documentos.DocumentoService.ConteudoDoDocumento;
import br.com.rbs.portal.documentos.DocumentoService.DadosDoEnvio;
import br.com.rbs.portal.materiais.MateriaisDtos.PaginaDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
 * Rotas 54 a 60 e 103 do contrato (docs/api.md): os documentos do Atlas. [ATLAS] Enviar (54), listar (55), ver (56), aprovar, recusar,
 * revogar e reindexar (57), ver os trechos (58) e o visualizador: o arquivo (59) e um trecho inteiro (60). [PERMISSAO] São só da RBS,
 * menos a 56, a 59 e a 60, em que a marca vê o documento que o escopo dela permite (e só o que a rota mostra). As regras estão no DocumentoService; aqui entram as partes do formulário, o corpo e
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
     * (COTA) ou parceriaId (PARCERIA); opcional, paginasForaDoAtlas ("4" ou "4,7": páginas que não entram no índice do Atlas).
     * Responde 201 com o documento.
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
                                               @RequestParam(required = false) String paginasForaDoAtlas,
                                               HttpServletRequest requisicao) {
        byte[] conteudo = null;
        try {
            conteudo = arquivo == null ? null : arquivo.getBytes();
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }
        DocumentoDto criado = servico.enviar(usuario, arquivo == null ? null : arquivo.getOriginalFilename(), conteudo,
                new DadosDoEnvio(titulo, chave, tipo, escopo, eventoId, cota, parceriaId, paginasForaDoAtlas), requisicao);
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

    /** 103. A RBS marca (ou desmarca) as páginas que ficam fora do Atlas, enquanto o documento aguarda aprovação. */
    @PatchMapping("/{id}/paginas-fora-do-atlas")
    public DocumentoDto marcarPaginasForaDoAtlas(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                                 @RequestBody PaginasForaDoAtlasRequisicao corpo, HttpServletRequest requisicao) {
        return servico.marcarPaginasForaDoAtlas(usuario, id, corpo.paginasForaDoAtlas(), requisicao);
    }

    /** 57. */
    @PatchMapping("/{id}/status")
    public DocumentoDto mudarStatus(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                    @Valid @RequestBody MudarStatusRequisicao corpo, HttpServletRequest requisicao) {
        return servico.mudarStatus(usuario, id, corpo.acao(), corpo.observacao(), requisicao);
    }

    /**
     * 59. O arquivo do documento para o visualizador, na própria página (inline), nunca como anexo. Os cabeçalhos tiram do arquivo
     * qualquer poder sobre a página: {@code nosniff} (o navegador não reinterpreta o tipo), {@code Content-Security-Policy: sandbox}
     * (um PDF ou TXT aberto direto não roda script nem acessa a sessão) e {@code Cache-Control: private, no-store} (nada fica em cache
     * do navegador ou de intermediário). A rota ignora o {@code Range}: todo GET devolve 200 com o arquivo inteiro e grava o DOWNLOAD,
     * e o {@code Accept-Ranges: none} avisa ao leitor. Por isso o corpo é um {@link InputStreamResource}, que o Spring não fatia (um
     * {@link Resource} de arquivo seria fatiado em 206, e um {@code Range} inválido daria 416 com o arquivo inteiro no corpo, sem a
     * contagem). Só o HEAD não grava. Cada GET gasta uma ficha do limite de aberturas por hora do usuário (429 com quanto falta, no
     * service); o HEAD só olha.
     */
    @GetMapping("/{id}/conteudo")
    public ResponseEntity<Resource> conteudo(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                             HttpServletRequest requisicao) throws IOException {
        ConteudoDoDocumento arquivo = servico.conteudo(usuario, id, !"HEAD".equals(requisicao.getMethod()), requisicao);
        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setContentType(tipo(arquivo.mimeType()));
        cabecalhos.setContentLength(Files.size(arquivo.caminho()));
        cabecalhos.setContentDisposition(ContentDisposition.inline().filename(arquivo.nomeOriginal(), StandardCharsets.UTF_8).build());
        cabecalhos.set(HttpHeaders.ACCEPT_RANGES, "none");
        cabecalhos.set("X-Content-Type-Options", "nosniff");
        cabecalhos.set("Content-Security-Policy", "sandbox");
        cabecalhos.set("Cache-Control", "private, no-store");
        return ResponseEntity.ok().headers(cabecalhos).body(new InputStreamResource(Files.newInputStream(arquivo.caminho())));
    }

    /** 60. O trecho inteiro, com a página e o texto, para o destaque no visualizador. */
    @GetMapping("/{id}/trechos/{trechoId}")
    public TrechoDoVisualizadorDto trechoDoVisualizador(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                                        @PathVariable long trechoId) {
        return servico.trechoDoVisualizador(usuario, id, trechoId);
    }

    /** O tipo do arquivo; o TXT sai com o conjunto de caracteres dito (UTF-8), para a tela não adivinhar os acentos. */
    private static MediaType tipo(String mime) {
        try {
            MediaType tipo = MediaType.parseMediaType(mime);
            return "text".equals(tipo.getType()) && "plain".equals(tipo.getSubtype()) && tipo.getCharset() == null
                    ? new MediaType(tipo, StandardCharsets.UTF_8) : tipo;
        } catch (IllegalArgumentException invalido) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
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
