package br.com.rbs.portal.dados;

import br.com.rbs.portal.dados.DadosDtos.CampoDto;
import br.com.rbs.portal.materiais.MateriaisDtos;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rota 39 do contrato (docs/api.md): o catálogo de campos que a marca pode informar (rótulo, tipo, unidade, limites e o campo
 * que limita o valor). [PERMISSAO] Qualquer pessoa logada: o catálogo é fixo e não carrega dado de parceria nenhuma. Os valores
 * de cada parceria estão no DadoInformadoController (rotas 40 e 41).
 */
@RestController
@RequestMapping("/api/campos")
public class CampoCatalogoController {

    private final DadoInformadoService servico;

    public CampoCatalogoController(DadoInformadoService servico) {
        this.servico = servico;
    }

    /** 39. [VALIDACAO] Uma aba que não existe vira 422 com campos.aba; uma aba que existe e não tem campos devolve lista vazia. */
    @GetMapping
    public List<CampoDto> listar(@RequestParam(required = false)
                                 @Pattern(regexp = MateriaisDtos.PADRAO_ABA,
                                         message = "Aba inválida. Use PERFIL, APLICACOES, MIDIA, CORTESIAS, CREDENCIAMENTO, PESQUISA ou CONTRATO.")
                                 String aba) {
        return servico.listarCampos(aba);
    }
}
