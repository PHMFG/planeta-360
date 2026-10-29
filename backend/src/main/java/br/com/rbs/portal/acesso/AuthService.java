package br.com.rbs.portal.acesso;

import br.com.rbs.portal.acesso.AcessoDtos.LoginRequisicao;
import br.com.rbs.portal.acesso.AcessoDtos.UsuarioLogadoDto;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.NaoAutenticadoException;
import br.com.rbs.portal.comum.Perfil;
import br.com.rbs.portal.comum.RespostasDeSeguranca;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

/**
 * Regras do login por sessão: confere e-mail e senha, abre a sessão, registra no histórico e monta o UsuarioLogado.
 * Atende os itens 3 e 5 da Entrega 2. [PERMISSAO] [HISTORICO]
 * Sem @Transactional de propósito: o LOGIN_FALHOU precisa ficar gravado mesmo quando a resposta é um erro 401.
 */
@Service
public class AuthService {

    /** A mesma frase para e-mail inexistente, senha errada e acesso revogado: não revela quais e-mails existem. */
    static final String CREDENCIAIS_INVALIDAS = "E-mail ou senha incorretos.";

    private final AuthenticationManager gerenteDeAutenticacao;
    private final SecurityContextRepository repositorioDoContexto;
    private final SecurityContextHolderStrategy contextoAtual = SecurityContextHolder.getContextHolderStrategy();
    private final UsuarioRepositorio usuarios;
    private final AcessoRepositorio acesso;
    private final HistoricoService historico;

    public AuthService(AuthenticationManager gerenteDeAutenticacao, SecurityContextRepository repositorioDoContexto,
                       UsuarioRepositorio usuarios, AcessoRepositorio acesso, HistoricoService historico) {
        this.gerenteDeAutenticacao = gerenteDeAutenticacao;
        this.repositorioDoContexto = repositorioDoContexto;
        this.usuarios = usuarios;
        this.acesso = acesso;
        this.historico = historico;
    }

    /** POST /api/auth/login. Devolve o UsuarioLogado e deixa a sessão aberta (cookie JSESSIONID). */
    public UsuarioLogadoDto login(LoginRequisicao corpo, HttpServletRequest requisicao, HttpServletResponse resposta) {
        String email = corpo.email().trim();

        Authentication conferida;
        try {
            conferida = gerenteDeAutenticacao.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, corpo.senha()));
        } catch (AuthenticationServiceException falhaInterna) {
            // Banco fora do ar e coisas assim: não é senha errada, então não vira 401 (e sai como 500 genérico).
            throw new IllegalStateException("Falha ao consultar o usuário durante o login", falhaInterna);
        } catch (AuthenticationException recusado) {
            // [HISTORICO] LOGIN_FALHOU com o e-mail tentado. Se o e-mail existe, grava também o id (para contar as tentativas).
            Long usuarioId = usuarios.buscarPorEmail(email).map(Usuario::id).orElse(null);
            historico.registrarLoginFalhou(email, usuarioId, requisicao);
            throw new NaoAutenticadoException(CREDENCIAIS_INVALIDAS);
        }

        Usuario usuario = ((UsuarioDetalhes) conferida.getPrincipal()).usuario();
        UsuarioAutenticado principal = new UsuarioAutenticado(usuario.id(), usuario.nome(), usuario.email(), usuario.perfil());

        // Contexto novo, só com o necessário (sem o hash da senha) para ir à sessão.
        SecurityContext contexto = contextoAtual.createEmptyContext();
        contexto.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, conferida.getAuthorities()));

        // Contra fixação de sessão: quem já tinha um JSESSIONID (por exemplo, posto por um atacante) ganha um id novo ao entrar.
        // Como o login é manual, o Spring Security não faz esta troca sozinho.
        if (requisicao.getSession(false) != null) {
            requisicao.changeSessionId();
        }
        contextoAtual.setContext(contexto);
        repositorioDoContexto.saveContext(contexto, requisicao, resposta);

        historico.registrarLogin(principal, requisicao);
        return montar(usuario);
    }

    /**
     * GET /api/auth/eu. Lê o usuário de novo no banco. [PERMISSAO] Que a conta desativada ou sem vínculos perde a sessão
     * (401 "Sua sessão terminou...") quem confere é o ConfereContaDaSessao, para toda rota, antes de chegar aqui; por isso
     * este método não repete a consulta de acesso (uma por requisição). Só o usuário que sumiu do banco ainda cai aqui.
     */
    public UsuarioLogadoDto eu(UsuarioAutenticado logado, HttpServletRequest requisicao, HttpServletResponse resposta) {
        Usuario usuario = usuarios.findById(logado.id()).orElse(null);
        if (usuario == null) {
            new SecurityContextLogoutHandler().logout(requisicao, resposta, contextoAtual.getContext().getAuthentication());
            throw new NaoAutenticadoException(RespostasDeSeguranca.SESSAO_ENCERRADA);
        }
        return montar(usuario);
    }

    private UsuarioLogadoDto montar(Usuario usuario) {
        // O Admin RBS enxerga todas as parcerias, então a lista dele é vazia (o contrato com o front).
        var parcerias = usuario.perfil() == Perfil.ADMIN_RBS ? List.<AcessoDtos.ParceriaDoUsuarioDto>of()
                : acesso.parceriasDoUsuario(usuario.id());
        return new UsuarioLogadoDto(usuario.id(), usuario.nome(), usuario.email(), usuario.perfil(), parcerias);
    }
}
