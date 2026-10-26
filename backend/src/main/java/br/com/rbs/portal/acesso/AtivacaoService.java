package br.com.rbs.portal.acesso;

import br.com.rbs.portal.acesso.AcessoDtos.AtivarRequisicao;
import br.com.rbs.portal.acesso.AtivacaoRepositorio.Autorizacao;
import br.com.rbs.portal.acesso.AtivacaoRepositorio.CodigoVigente;
import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.Perfil;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O código de primeiro acesso: gera o código (para a RBS repassar) e o consome (quando o patrocinador define a senha).
 * Atende os itens 3 e 5 da Entrega 2. [PERMISSAO] [HISTORICO]
 * O código tem 8 letras e números num alfabeto sem 0/O/1/I, vale 7 dias e só serve uma vez. O banco guarda só o
 * hash SHA-256 (nunca o código), e 5 erros de digitação bloqueiam o código: a RBS precisa gerar outro.
 */
@Service
public class AtivacaoService {

    /** A mesma frase para e-mail desconhecido, código errado, vencido, usado, bloqueado ou de e-mail revogado. */
    static final String CODIGO_INVALIDO = "Código inválido ou vencido. Peça um novo à RBS.";

    static final int DIAS_DE_VALIDADE = 7;
    static final int MAXIMO_DE_ERROS = 5;
    private static final int TAMANHO_MAXIMO_DA_SENHA_EM_BYTES = 72;   // o BCrypt só lê os 72 primeiros bytes

    // 32 símbolos: letras sem I e O, números sem 0 e 1 (que se confundem ao ler ou ditar o código).
    private static final String ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int TAMANHO_DO_CODIGO = 8;
    private static final SecureRandom SORTEIO = new SecureRandom();

    /** O código como a RBS o vê (com hífen no meio) e até quando vale. Só existe na resposta que o cria. */
    public record CodigoEmitido(String codigo, OffsetDateTime expiraEm) {
    }

    private final AtivacaoRepositorio repositorio;
    private final UsuarioRepositorio usuarios;
    private final PasswordEncoder codificador;
    private final HistoricoService historico;

    public AtivacaoService(AtivacaoRepositorio repositorio, UsuarioRepositorio usuarios, PasswordEncoder codificador,
                           HistoricoService historico) {
        this.repositorio = repositorio;
        this.usuarios = usuarios;
        this.codificador = codificador;
        this.historico = historico;
    }

    /**
     * Gera um código novo para a autorização e invalida o anterior. Quem chama (a área parceria) já está numa transação
     * e entrega o texto à RBS uma vez só: depois disso não há como ler o código de novo, só gerar outro.
     */
    @Transactional
    public CodigoEmitido emitir(long emailAutorizadoId) {
        repositorio.invalidarVigentes(emailAutorizadoId);
        String codigo = sortear();
        OffsetDateTime expiraEm = repositorio.inserirCodigo(emailAutorizadoId, hash(normalizar(codigo)), DIAS_DE_VALIDADE);
        return new CodigoEmitido(codigo.substring(0, 4) + "-" + codigo.substring(4), Datas.emBrasilia(expiraEm));
    }

    /**
     * POST /api/auth/ativar. Confere o código, cria a conta (ou define a senha), vincula a pessoa às parcerias em que o
     * e-mail está autorizado e grava ATIVACAO no histórico. Não abre sessão: o front chama o login em seguida.
     *
     * noRollbackFor: o erro de digitação precisa ficar contado mesmo quando a resposta é 422; sem isso a transação
     * voltaria atrás e o limite de 5 erros nunca seria atingido.
     */
    @Transactional(noRollbackFor = DadosInvalidosException.class)
    public void ativar(AtivarRequisicao corpo, HttpServletRequest requisicao) {
        if (corpo.senha().getBytes(StandardCharsets.UTF_8).length > TAMANHO_MAXIMO_DA_SENHA_EM_BYTES) {
            // Caracteres com acento contam mais de um byte; o BCrypt recusaria a senha inteira.
            throw new DadosInvalidosException(Map.of("senha", "A senha é longa demais. Use até 72 bytes (acentos contam mais)."));
        }

        String email = corpo.email().toLowerCase(Locale.ROOT);
        String hashDigitado = hash(normalizar(corpo.codigo()));

        List<CodigoVigente> vigentes = repositorio.codigosVigentes(email);
        CodigoVigente certo = vigentes.stream()
                .filter(c -> !c.vencido() && c.tentativas() < MAXIMO_DE_ERROS && mesmoHash(c.codigoHash(), hashDigitado))
                .findFirst()
                .orElse(null);
        if (certo == null) {
            // [PERMISSAO] Conta o erro para travar força bruta. Sem código pendente (e-mail desconhecido) não há o que contar.
            repositorio.registrarErro(email, MAXIMO_DE_ERROS);
            throw codigoInvalido();
        }

        Usuario existente = usuarios.buscarPorEmail(certo.email()).orElse(null);
        if (existente != null && (existente.senhaHash() != null || existente.perfil() != Perfil.ADMIN_PATROCINADOR)) {
            // Conta já ativada (ou da RBS): o código não serve para trocar a senha de ninguém.
            throw codigoInvalido();
        }

        String senhaHash = codificador.encode(corpo.senha());
        long usuarioId;
        String nome = corpo.nome();
        if (existente == null) {
            usuarioId = repositorio.criarUsuario(nome != null ? nome : parteLocal(certo.email()), certo.email(), senhaHash);
        } else {
            usuarioId = existente.id();
            repositorio.definirSenha(usuarioId, senhaHash, nome);
        }

        // O e-mail pode ter sido autorizado em mais de uma parceria antes do primeiro acesso (uma agência, por exemplo).
        // A conta nasce com acesso a todas; os códigos das outras autorizações deixam de servir, porque já não há o que ativar.
        List<Autorizacao> autorizacoes = repositorio.autorizacoesVivas(certo.email());
        repositorio.marcarUsados(autorizacoes.stream().map(Autorizacao::id).toList());
        UsuarioAutenticado novo = new UsuarioAutenticado(usuarioId, nome != null ? nome : parteLocal(certo.email()),
                certo.email(), Perfil.ADMIN_PATROCINADOR);
        for (Autorizacao autorizacao : autorizacoes) {
            repositorio.vincular(autorizacao.parceriaId(), usuarioId, autorizacao.vinculo());
            // [HISTORICO] Uma linha por parceria, para a trilha de cada uma mostrar quando o acesso começou.
            historico.registrar(AcaoHistorico.ATIVACAO, novo, autorizacao.parceriaId(), "usuario", usuarioId, null, null,
                    "primeiro acesso com o código de ativação", requisicao);
        }
    }

    // ---------------------------------------------------------------------
    // Apoio
    // ---------------------------------------------------------------------

    private static DadosInvalidosException codigoInvalido() {
        return new DadosInvalidosException(CODIGO_INVALIDO, Map.of("codigo", CODIGO_INVALIDO));
    }

    /** Oito símbolos do alfabeto, sorteados com SecureRandom (imprevisível, ao contrário de java.util.Random). */
    private static String sortear() {
        StringBuilder codigo = new StringBuilder(TAMANHO_DO_CODIGO);
        for (int i = 0; i < TAMANHO_DO_CODIGO; i++) {
            codigo.append(ALFABETO.charAt(SORTEIO.nextInt(ALFABETO.length())));
        }
        return codigo.toString();
    }

    /** Maiúsculas e sem hífen, espaço ou qualquer sinal: "k7m2-9qxd" e "K7M2 9QXD" valem como "K7M29QXD". */
    static String normalizar(String codigo) {
        return codigo.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    /** SHA-256 em hexadecimal minúsculo, o formato que a coluna codigo_hash exige. */
    static String hash(String codigoNormalizado) {
        try {
            byte[] resumo = MessageDigest.getInstance("SHA-256").digest(codigoNormalizado.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(resumo);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 sempre existe na JVM", e);
        }
    }

    // Comparação em tempo constante: não deixa a duração da resposta contar quantos caracteres do hash batem.
    private static boolean mesmoHash(String guardado, String digitado) {
        return MessageDigest.isEqual(guardado.getBytes(StandardCharsets.UTF_8), digitado.getBytes(StandardCharsets.UTF_8));
    }

    private static String parteLocal(String email) {
        String local = email.substring(0, email.indexOf('@'));
        return local.length() <= 120 ? local : local.substring(0, 120);
    }
}
