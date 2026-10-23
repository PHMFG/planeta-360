package br.com.rbs.portal.materiais;

import br.com.rbs.portal.comum.Csv;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.materiais.HistoricoConsultaService.ArquivoCsv;
import br.com.rbs.portal.materiais.MateriaisDtos.HistoricoDto;
import br.com.rbs.portal.materiais.MateriaisDtos.HistoricoGeralDto;
import br.com.rbs.portal.materiais.MateriaisDtos.PaginaDto;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas 20, 21 e 22: histórico da parceria, histórico geral da RBS e o geral em CSV. Atende os itens 4 e 5 da Entrega 2.
 * [HISTORICO] [PERMISSAO] A 20 serve ao patrocinador da parceria (sem IP e navegador); a 21 e a 22 são só da RBS.
 * As datas chegam como texto e são conferidas no service para o erro sair como 422 com o campo, e não como 400.
 */
@RestController
@RequestMapping("/api")
public class HistoricoController {

    private final HistoricoConsultaService servico;

    public HistoricoController(HistoricoConsultaService servico) {
        this.servico = servico;
    }

    /** 20. */
    @GetMapping("/parcerias/{id}/historico")
    public PaginaDto<HistoricoDto> daParceria(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                              @RequestParam(required = false) String acao,
                                              @RequestParam(required = false) @Size(max = 40, message = "A entidade tem no máximo 40 caracteres.") String entidade,
                                              @RequestParam(required = false) String de,
                                              @RequestParam(required = false) String ate,
                                              @RequestParam(defaultValue = "0") @Min(value = 0, message = "A página começa em 0.") int pagina,
                                              @RequestParam(defaultValue = "50")
                                              @Min(value = 1, message = "O tamanho vai de 1 a 200.")
                                              @Max(value = 200, message = "O tamanho vai de 1 a 200.") int tamanho) {
        return servico.daParceria(usuario, id, acao, entidade, de, ate, pagina, tamanho);
    }

    /** 21. */
    @GetMapping("/historico")
    public PaginaDto<HistoricoGeralDto> geral(@AuthenticationPrincipal UsuarioAutenticado usuario,
                                              @RequestParam(required = false) Long parceriaId,
                                              @RequestParam(required = false) Long usuarioId,
                                              @RequestParam(required = false) String acao,
                                              @RequestParam(required = false) @Size(max = 40, message = "A entidade tem no máximo 40 caracteres.") String entidade,
                                              @RequestParam(required = false) String de,
                                              @RequestParam(required = false) String ate,
                                              @RequestParam(required = false) @Size(max = 120, message = "A busca tem no máximo 120 caracteres.") String q,
                                              @RequestParam(defaultValue = "0") @Min(value = 0, message = "A página começa em 0.") int pagina,
                                              @RequestParam(defaultValue = "50")
                                              @Min(value = 1, message = "O tamanho vai de 1 a 200.")
                                              @Max(value = 200, message = "O tamanho vai de 1 a 200.") int tamanho) {
        return servico.geral(usuario, parceriaId, usuarioId, acao, entidade, de, ate, q, pagina, tamanho);
    }

    /** 22. Os mesmos filtros da 21, sem paginação; o Csv cuida do BOM, do ";" e das fórmulas. */
    @GetMapping("/historico.csv")
    public ResponseEntity<byte[]> csv(@AuthenticationPrincipal UsuarioAutenticado usuario,
                                      @RequestParam(required = false) Long parceriaId,
                                      @RequestParam(required = false) Long usuarioId,
                                      @RequestParam(required = false) String acao,
                                      @RequestParam(required = false) @Size(max = 40, message = "A entidade tem no máximo 40 caracteres.") String entidade,
                                      @RequestParam(required = false) String de,
                                      @RequestParam(required = false) String ate,
                                      @RequestParam(required = false) @Size(max = 120, message = "A busca tem no máximo 120 caracteres.") String q) {
        ArquivoCsv arquivo = servico.csv(usuario, parceriaId, usuarioId, acao, entidade, de, ate, q);
        return Csv.resposta(arquivo.nome(), arquivo.cabecalho(), arquivo.linhas());
    }
}
