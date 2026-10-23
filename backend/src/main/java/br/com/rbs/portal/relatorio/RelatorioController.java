package br.com.rbs.portal.relatorio;

import br.com.rbs.portal.comum.Csv;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.relatorio.RelatorioDtos.MudarStatusRequisicao;
import br.com.rbs.portal.relatorio.RelatorioDtos.RelatorioDto;
import br.com.rbs.portal.relatorio.RelatorioDtos.SituacaoDto;
import br.com.rbs.portal.relatorio.RelatorioService.ArquivoCsv;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas 31 a 36 do contrato (docs/api.md): situação dos relatórios, geração, validação, leitura e CSV.
 * Atende o item 6 da Entrega 2. [PERMISSAO] As rotas 31 a 34 são da RBS (o patrocinador recebe 403); as 35 e 36 também
 * servem ao patrocinador da parceria, que só vê o relatório disponibilizado. As regras ficam no RelatorioService.
 */
@RestController
@RequestMapping("/api")
public class RelatorioController {

    private static final String STATUS_DO_FILTRO = "SEM_RELATORIO|RASCUNHO|VALIDADO|DISPONIBILIZADO";

    private final RelatorioService servico;

    public RelatorioController(RelatorioService servico) {
        this.servico = servico;
    }

    /** 31. A situação do relatório de cada parceria da edição (padrão: a mais nova). */
    @GetMapping("/relatorios")
    public List<SituacaoDto> listar(
            @AuthenticationPrincipal UsuarioAutenticado usuario,
            @RequestParam(required = false) Long eventoId,
            @RequestParam(required = false)
            @Pattern(regexp = STATUS_DO_FILTRO, message = "Use SEM_RELATORIO, RASCUNHO, VALIDADO ou DISPONIBILIZADO.")
            String status) {
        return servico.listar(usuario, eventoId, status);
    }

    /** 32. A mesma lista, em CSV. */
    @GetMapping("/relatorios.csv")
    public ResponseEntity<byte[]> listarEmCsv(
            @AuthenticationPrincipal UsuarioAutenticado usuario,
            @RequestParam(required = false) Long eventoId,
            @RequestParam(required = false)
            @Pattern(regexp = STATUS_DO_FILTRO, message = "Use SEM_RELATORIO, RASCUNHO, VALIDADO ou DISPONIBILIZADO.")
            String status) {
        return csv(servico.listarEmCsv(usuario, eventoId, status));
    }

    /** 33. Gera uma nova versão do relatório da parceria (201, com o endereço do relatório em Location). */
    @PostMapping("/parcerias/{id}/relatorios")
    public ResponseEntity<RelatorioDto> gerar(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id) {
        RelatorioDto relatorio = servico.gerar(usuario, id);
        return ResponseEntity.created(URI.create("/api/relatorios/" + relatorio.id())).body(relatorio);
    }

    /** 34. Valida ou disponibiliza o relatório; o banco impõe a ordem e o erro sai como 409. */
    @PatchMapping("/relatorios/{id}/status")
    public RelatorioDto mudarStatus(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                    @Valid @RequestBody MudarStatusRequisicao corpo, HttpServletRequest requisicao) {
        return servico.mudarStatus(usuario, id, corpo.status(), requisicao);
    }

    /** 35. O relatório da parceria: o patrocinador vê o disponibilizado; a RBS vê o mais novo ou a versão de ?versao=. */
    @GetMapping("/parcerias/{id}/relatorio")
    public RelatorioDto ver(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                            @RequestParam(required = false) @Min(value = 1, message = "A versão começa em 1.") Integer versao) {
        return servico.ver(usuario, id, versao);
    }

    /** 36. Um relatório em CSV; grava DOWNLOAD no histórico. */
    @GetMapping("/relatorios/{id}/csv")
    public ResponseEntity<byte[]> baixarCsv(@AuthenticationPrincipal UsuarioAutenticado usuario, @PathVariable long id,
                                            HttpServletRequest requisicao) {
        return csv(servico.baixarCsv(usuario, id, requisicao));
    }

    private static ResponseEntity<byte[]> csv(ArquivoCsv arquivo) {
        return Csv.resposta(arquivo.nome(), arquivo.cabecalho(), arquivo.linhas());
    }
}
