package br.com.rbs.portal.comum;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas que existem só nos testes, para provocar cada tipo de erro e ver o TratadorDeErros responder.
 * Atende o item 3 da Entrega 2. Ficam em src/test e não existem na API de verdade.
 */
@RestController
@RequestMapping("/api/teste")
class RotasSoDeTeste {

    record Corpo(@NotBlank(message = "Informe o nome.") String nome) {
    }

    private final JdbcClient jdbc;
    private final VerificadorDeAcesso verificador;

    RotasSoDeTeste(JdbcClient jdbc, VerificadorDeAcesso verificador) {
        this.jdbc = jdbc;
        this.verificador = verificador;
    }

    /** Dispara o gatilho rn_vinculo_exige_autorizacao: vincular quem não foi autorizado naquela parceria. */
    @GetMapping("/gatilho")
    String gatilho() {
        jdbc.sql("""
                INSERT INTO parceria_usuario (parceria_id, usuario_id, vinculo)
                SELECT (SELECT min(id) FROM parceria), (SELECT id FROM usuario WHERE email = 'marketing@roxo.example'), 'MARCA'
                WHERE NOT EXISTS (SELECT 1 FROM parceria_usuario WHERE parceria_id = (SELECT min(id) FROM parceria)
                                    AND usuario_id = (SELECT id FROM usuario WHERE email = 'marketing@roxo.example'))
                """).update();
        return "não deveria chegar aqui";
    }

    /** Dispara a regra única uq_patrocinador_nome (erro nativo do PostgreSQL, em inglês). */
    @GetMapping("/unico")
    String unico() {
        jdbc.sql("INSERT INTO patrocinador (nome_canonico) VALUES ('Cliente Amarelo')").update();
        return "não deveria chegar aqui";
    }

    /** Dispara uma chave estrangeira (patrocinador que não existe). */
    @GetMapping("/chave")
    String chave() {
        jdbc.sql("INSERT INTO parceria (evento_id, patrocinador_id, cota_tipo_id) VALUES (1, 999999, 1)").update();
        return "não deveria chegar aqui";
    }

    @GetMapping("/inesperado")
    String inesperado() {
        throw new IllegalStateException("conexão recusada em jdbc:postgresql://localhost:5432/portal com a senha portal");
    }

    @GetMapping("/inexistente")
    String inexistente() {
        throw new NaoEncontradoException();
    }

    @GetMapping("/so-rbs")
    String soRbs(@AuthenticationPrincipal UsuarioAutenticado usuario) {
        verificador.exigirRbs(usuario);
        return "ok";
    }

    @GetMapping("/parceria/{id}")
    String parceria(@AuthenticationPrincipal UsuarioAutenticado usuario, @org.springframework.web.bind.annotation.PathVariable long id) {
        verificador.exigirParceria(usuario, id);
        return "ok";
    }

    @GetMapping("/negado")
    String negado() {
        throw new AccessDeniedException("lançada dentro do controller");
    }

    @PostMapping("/validacao")
    String validacao(@Valid @RequestBody Corpo corpo) {
        return corpo.nome();
    }

    @GetMapping("/parametro")
    String parametro(@RequestParam @Pattern(regexp = "[A-Z]+", message = "Use só letras maiúsculas.") String aba) {
        return aba;
    }
}
