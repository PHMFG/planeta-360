package br.com.rbs.portal.atlas.debug;

import br.com.rbs.portal.atlas.debug.DebugDoLlmDtos.ChaveRequisicao;
import br.com.rbs.portal.atlas.debug.DebugDoLlmDtos.EstadoDto;
import br.com.rbs.portal.atlas.debug.DebugDoLlmDtos.TesteDto;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas de debug (temporárias) do modelo de linguagem do Atlas: ver, pôr, apagar e testar a chave pela tela (docs/api.md, "Rotas
 * de debug"). [ATLAS] Só existem com {@code atlas.debug.chaves=true} (ligado no perfil dev; sem a propriedade, 404). Qualquer
 * usuário logado; POST, PUT e DELETE exigem o token CSRF, como toda rota. Não entram na conta das 48 rotas do contrato.
 */
@RestController
@ConditionalOnProperty(name = "atlas.debug.chaves", havingValue = "true")
public class DebugDoLlmController {

    private final DebugDoLlmService servico;

    public DebugDoLlmController(DebugDoLlmService servico) {
        this.servico = servico;
    }

    @GetMapping("/api/debug/llm")
    public EstadoDto estado() {
        return servico.estado();
    }

    @PutMapping("/api/debug/llm")
    public EstadoDto configurar(@AuthenticationPrincipal UsuarioAutenticado usuario, @RequestBody ChaveRequisicao corpo) {
        return servico.configurar(usuario, corpo);
    }

    @DeleteMapping("/api/debug/llm")
    public ResponseEntity<Void> esquecer(@AuthenticationPrincipal UsuarioAutenticado usuario) {
        servico.esquecer(usuario);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/debug/llm/teste")
    public TesteDto testar() {
        return servico.testar();
    }
}
