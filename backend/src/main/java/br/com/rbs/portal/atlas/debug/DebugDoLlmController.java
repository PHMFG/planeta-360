package br.com.rbs.portal.atlas.debug;

import br.com.rbs.portal.atlas.debug.DebugDoLlmDtos.ChaveRequisicao;
import br.com.rbs.portal.atlas.debug.DebugDoLlmDtos.EstadoDto;
import br.com.rbs.portal.atlas.debug.DebugDoLlmDtos.TesteDto;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
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
 * de debug"). [ATLAS] Só existem com {@code atlas.debug.chaves=true} (ligado no perfil dev; sem a propriedade, 404). [PERMISSAO] Só
 * o Admin RBS: o PermissaoAntesDoCorpo responde 403 ao patrocinador antes de tudo, e cada rota confere o perfil de novo. POST, PUT e
 * DELETE exigem o token CSRF, como toda rota. Não entram na conta das 53 rotas do contrato.
 */
@RestController
@ConditionalOnProperty(name = "atlas.debug.chaves", havingValue = "true")
public class DebugDoLlmController {

    private final DebugDoLlmService servico;
    private final VerificadorDeAcesso verificador;

    public DebugDoLlmController(DebugDoLlmService servico, VerificadorDeAcesso verificador) {
        this.servico = servico;
        this.verificador = verificador;
    }

    @GetMapping("/api/debug/llm")
    public EstadoDto estado(@AuthenticationPrincipal UsuarioAutenticado usuario) {
        verificador.exigirRbs(usuario);
        return servico.estado();
    }

    @PutMapping("/api/debug/llm")
    public EstadoDto configurar(@AuthenticationPrincipal UsuarioAutenticado usuario, @RequestBody ChaveRequisicao corpo) {
        verificador.exigirRbs(usuario);
        return servico.configurar(usuario, corpo);
    }

    @DeleteMapping("/api/debug/llm")
    public ResponseEntity<Void> esquecer(@AuthenticationPrincipal UsuarioAutenticado usuario) {
        verificador.exigirRbs(usuario);
        servico.esquecer(usuario);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/debug/llm/teste")
    public TesteDto testar(@AuthenticationPrincipal UsuarioAutenticado usuario) {
        verificador.exigirRbs(usuario);
        return servico.testar();
    }
}
