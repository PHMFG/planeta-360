package br.com.rbs.portal.comum;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Grava no histórico o que cada pessoa fez no portal, com usuário, parceria, entidade, detalhe, IP e navegador.
 * Atende o item 5 da Entrega 2. [HISTORICO] Nas rotas de alteração, chame dentro da mesma transação da alteração:
 * ou grava os dois, ou nenhum. As rotas de login e logout não têm transação, e é de propósito (veja AuthService).
 */
@Service
public class HistoricoService {

    // Só caracteres de endereço IP. O getRemoteAddr() pode trazer o sufixo de zona do IPv6 ("%en0"), que o tipo inet recusa.
    private static final Pattern CARACTERES_DE_IP = Pattern.compile("[0-9a-fA-F:.]+");
    private static final int LIMITE_USER_AGENT = 500;
    private static final int LIMITE_EMAIL = 254;   // tamanho máximo de um e-mail (coluna usuario.email)

    private final HistoricoRepositorio repositorio;

    public HistoricoService(HistoricoRepositorio repositorio) {
        this.repositorio = repositorio;
    }

    /** Registro genérico, para as rotas das próximas etapas (entrega, arquivo, relatório...). */
    public void registrar(AcaoHistorico acao, UsuarioAutenticado usuario, Long parceriaId, String entidade, Long entidadeId,
                          String valorAnterior, String valorNovo, String detalhe, HttpServletRequest requisicao) {
        repositorio.inserir(parceriaId, entidade, entidadeId, acao, valorAnterior, valorNovo, detalhe,
                usuario != null ? usuario.id() : null, enderecoIp(requisicao), navegador(requisicao));
    }

    /** [HISTORICO] Login que deu certo. Não há parceria: quem entra pode ter várias. */
    public void registrarLogin(UsuarioAutenticado usuario, HttpServletRequest requisicao) {
        registrar(AcaoHistorico.LOGIN, usuario, null, "usuario", usuario.id(), null, null, null, requisicao);
    }

    /** [HISTORICO] Logout. Chamado antes de a sessão ser encerrada, enquanto ainda sabemos quem estava logado. */
    public void registrarLogout(UsuarioAutenticado usuario, HttpServletRequest requisicao) {
        registrar(AcaoHistorico.LOGOUT, usuario, null, "usuario", usuario.id(), null, null, null, requisicao);
    }

    /**
     * [HISTORICO] Tentativa de login que falhou. O e-mail tentado vai em "detalhe", porque quem erra o e-mail não é
     * nenhum usuário (o banco aceita usuario_id e entidade_id nulos só nesta ação). Se o e-mail existe, o id dele
     * também é gravado, para a RBS ver quantas tentativas erradas uma conta recebeu.
     * [PENDENTE] Limite de tentativas por IP e por e-mail (contra força bruta); hoje só fica registrado.
     */
    public void registrarLoginFalhou(String emailTentado, Long usuarioIdSeExistir, HttpServletRequest requisicao) {
        // Mesmo texto que a amostra do banco usa, para as linhas novas e as antigas aparecerem iguais nos filtros da RBS.
        repositorio.inserir(null, "usuario", usuarioIdSeExistir, AcaoHistorico.LOGIN_FALHOU, null, null,
                "e-mail tentado: " + limitar(emailTentado, LIMITE_EMAIL), usuarioIdSeExistir, enderecoIp(requisicao),
                navegador(requisicao));
    }

    /** Sem requisição (a ingestão de um documento roda numa tarefa de fundo), não há endereço nem navegador a gravar. */
    private static String enderecoIp(HttpServletRequest requisicao) {
        if (requisicao == null) {
            return null;
        }
        String ip = requisicao.getRemoteAddr();
        if (ip == null) {
            return null;
        }
        int zona = ip.indexOf('%');
        String semZona = zona >= 0 ? ip.substring(0, zona) : ip;
        return CARACTERES_DE_IP.matcher(semZona).matches() ? semZona : null;
    }

    private static String navegador(HttpServletRequest requisicao) {
        if (requisicao == null) {
            return null;
        }
        return limitar(requisicao.getHeader("User-Agent"), LIMITE_USER_AGENT);
    }

    private static String limitar(String texto, int limite) {
        if (texto == null) {
            return null;
        }
        return texto.length() <= limite ? texto : texto.substring(0, limite);
    }
}
