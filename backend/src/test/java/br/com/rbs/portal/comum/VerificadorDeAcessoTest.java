package br.com.rbs.portal.comum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.TesteDeIntegracao;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prova o isolamento entre parcerias: o Roxo pede a parceria do Amarelo e recebe "não encontrado", e a revogação vale na hora.
 * Atende o item 3 da Entrega 2. [ISOLAMENTO] [PERMISSAO] Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class VerificadorDeAcessoTest {

    @Autowired
    VerificadorDeAcesso verificador;

    @Autowired
    JdbcClient jdbc;

    UsuarioAutenticado usuario(String email) {
        var u = jdbc.sql("SELECT id, nome, perfil FROM usuario WHERE email = :email").param("email", email).query().singleRow();
        return new UsuarioAutenticado(((Number) u.get("id")).longValue(), (String) u.get("nome"), email,
                Perfil.valueOf((String) u.get("perfil")));
    }

    long parceria(String cliente) {
        return jdbc.sql("""
                        SELECT pa.id FROM parceria pa JOIN patrocinador p ON p.id = pa.patrocinador_id
                        JOIN evento e ON e.id = pa.evento_id WHERE p.nome_canonico = :cliente AND e.edicao = 2027
                        """).param("cliente", cliente).query(Long.class).single();
    }

    @Test
    void patrocinadorAcessaASuaParceria() {
        assertThatCode(() -> verificador.exigirParceria(usuario("marketing@roxo.example"), parceria("Cliente Roxo")))
                .doesNotThrowAnyException();
    }

    @Test
    void agenciaVinculadaAcessaAParceriaDoCliente() {
        assertThatCode(() -> verificador.exigirParceria(usuario("atendimento@agencia-x.example"), parceria("Cliente Amarelo")))
                .doesNotThrowAnyException();
    }

    @Test
    void roxoPedindoAParceriaDoAmareloRecebeNaoEncontrado() {
        UsuarioAutenticado roxo = usuario("marketing@roxo.example");
        long amarelo = parceria("Cliente Amarelo");

        assertThatThrownBy(() -> verificador.exigirParceria(roxo, amarelo)).isInstanceOf(NaoEncontradoException.class);
    }

    @Test
    void parceriaInexistentePareceIgualAParceriaDeOutro() {
        UsuarioAutenticado roxo = usuario("marketing@roxo.example");
        long amarelo = parceria("Cliente Amarelo");

        Throwable deOutro = org.assertj.core.api.Assertions.catchThrowable(() -> verificador.exigirParceria(roxo, amarelo));
        Throwable inexistente = org.assertj.core.api.Assertions.catchThrowable(() -> verificador.exigirParceria(roxo, 999_999L));

        assertThat(inexistente).isInstanceOf(NaoEncontradoException.class);
        assertThat(inexistente.getMessage()).as("a mensagem não diferencia os dois casos").isEqualTo(deOutro.getMessage());
    }

    @Test
    void rbsPassaEmQualquerParceriaQueExista() {
        UsuarioAutenticado rbs = usuario("b2b@rbs.example");

        assertThatCode(() -> verificador.exigirParceria(rbs, parceria("Cliente Roxo"))).doesNotThrowAnyException();
        assertThatCode(() -> verificador.exigirParceria(rbs, parceria("Cliente Verde"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> verificador.exigirParceria(rbs, 999_999L)).isInstanceOf(NaoEncontradoException.class);
    }

    @Test
    void revogarOEmailTiraOAcessoNaHora() {
        UsuarioAutenticado verde = usuario("marketing@verde.example");
        long parceriaVerde = parceria("Cliente Verde");
        assertThatCode(() -> verificador.exigirParceria(verde, parceriaVerde)).doesNotThrowAnyException();

        jdbc.sql("""
                        UPDATE email_autorizado SET revogado_em = now(), revogado_por = :rbs
                        WHERE email = 'marketing@verde.example'
                        """).param("rbs", usuario("b2b@rbs.example").id()).update();

        // O mesmo objeto UsuarioAutenticado (a "sessão") de antes: nada foi guardado, a consulta é feita de novo.
        assertThatThrownBy(() -> verificador.exigirParceria(verde, parceriaVerde)).isInstanceOf(NaoEncontradoException.class);
    }

    @Test
    void usuarioDesativadoPerdeOAcessoNaHora() {
        UsuarioAutenticado roxo = usuario("marketing@roxo.example");
        long parceriaRoxo = parceria("Cliente Roxo");

        jdbc.sql("UPDATE usuario SET ativo = FALSE WHERE id = :id").param("id", roxo.id()).update();

        assertThatThrownBy(() -> verificador.exigirParceria(roxo, parceriaRoxo)).isInstanceOf(NaoEncontradoException.class);
    }

    @Test
    void exigirRbsBarraOPatrocinador() {
        assertThatThrownBy(() -> verificador.exigirRbs(usuario("marketing@amarelo.example")))
                .isInstanceOf(SemPermissaoException.class);
        assertThatCode(() -> verificador.exigirRbs(usuario("pesquisa@rbs.example"))).doesNotThrowAnyException();
    }
}
