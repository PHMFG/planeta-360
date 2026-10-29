package br.com.rbs.portal.atlas;

import br.com.rbs.portal.comum.Perfil;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.dados.DadosDtos.ValorRequisicao;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Pequenas ajudas dos testes do Atlas: o usuário da amostra como o Spring Security o guarda na sessão, e os pedidos de gravação de
 * dados. Atende o item 5 do pedido da segunda parte do Atlas. Não faz parte da API.
 */
public final class ApoioDoAtlas {

    public static final String RBS = "b2b@rbs.example";
    public static final String AMARELO = "marketing@amarelo.example";
    public static final String AGENCIA_DO_AMARELO = "atendimento@agencia-x.example";
    public static final String ROXO = "marketing@roxo.example";
    public static final String VISITANTES = "ativacao.visitantes_total";
    public static final String CADASTROS = "ativacao.cadastros_consentidos";
    public static final String DISPONIVEIS = "ativacao.brindes_disponiveis";
    public static final String DISTRIBUIDOS = "ativacao.brindes_distribuidos";

    private ApoioDoAtlas() {
    }

    /** O usuário da amostra com este e-mail, no formato da sessão. */
    public static UsuarioAutenticado usuario(JdbcClient jdbc, String email) {
        var linha = jdbc.sql("SELECT id, nome, perfil FROM usuario WHERE email = :email").param("email", email)
                .query((rs, n) -> new Object[]{rs.getLong("id"), rs.getString("nome"), rs.getString("perfil")}).single();
        return new UsuarioAutenticado((Long) linha[0], (String) linha[1], email, Perfil.valueOf((String) linha[2]));
    }

    /** Um pedido de mentira para o histórico (IP e navegador). */
    public static MockHttpServletRequest requisicao() {
        MockHttpServletRequest requisicao = new MockHttpServletRequest();
        requisicao.addHeader("User-Agent", "teste-do-atlas");
        return requisicao;
    }

    public static ValorRequisicao valor(String chave, Object valor) {
        return new ValorRequisicao(chave, valor);
    }

    public static List<ValorRequisicao> valores(Object... paresChaveValor) {
        java.util.ArrayList<ValorRequisicao> lista = new java.util.ArrayList<>();
        for (int i = 0; i < paresChaveValor.length; i += 2) {
            lista.add(valor((String) paresChaveValor[i], paresChaveValor[i + 1]));
        }
        return lista;
    }
}
