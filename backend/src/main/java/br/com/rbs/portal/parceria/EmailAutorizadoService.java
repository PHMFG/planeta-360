package br.com.rbs.portal.parceria;

import br.com.rbs.portal.acesso.AtivacaoService;
import br.com.rbs.portal.acesso.AtivacaoService.CodigoEmitido;
import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.parceria.EmailAutorizadoRepositorio.Conta;
import br.com.rbs.portal.parceria.EmailAutorizadoRepositorio.Linha;
import br.com.rbs.portal.parceria.ParceriaDtos.AutorizarEmailRequisicao;
import br.com.rbs.portal.parceria.ParceriaDtos.EmailAutorizadoComCodigoDto;
import br.com.rbs.portal.parceria.ParceriaDtos.EmailAutorizadoDto;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Autoriza e revoga os e-mails que podem entrar numa parceria (rotas 11, 12 e 13). Atende os itens 3 e 5 da Entrega 2.
 * [PERMISSAO] Só e-mail autorizado cria acesso, e só a RBS autoriza. [HISTORICO] AUTORIZACAO e REVOGACAO entram na
 * mesma transação da mudança: ou grava as duas coisas, ou nenhuma.
 */
@Service
public class EmailAutorizadoService {

    /** O que a rota 12 devolve e se a linha era nova (201) ou já existia (200: código reemitido ou acesso devolvido). */
    public record Resultado(EmailAutorizadoComCodigoDto email, boolean criado) {
    }

    private final EmailAutorizadoRepositorio emails;
    private final AtivacaoService ativacao;
    private final VerificadorDeAcesso verificador;
    private final HistoricoService historico;

    public EmailAutorizadoService(EmailAutorizadoRepositorio emails, AtivacaoService ativacao,
                                  VerificadorDeAcesso verificador, HistoricoService historico) {
        this.emails = emails;
        this.ativacao = ativacao;
        this.verificador = verificador;
        this.historico = historico;
    }

    /** Rota 11. [PERMISSAO] Só a RBS vê quem autorizou e quem revogou. */
    @Transactional(readOnly = true)
    public List<EmailAutorizadoDto> listar(UsuarioAutenticado usuario, long parceriaId) {
        verificador.exigirRbs(usuario);
        verificador.exigirParceria(usuario, parceriaId);
        return emails.listar(parceriaId);
    }

    /** Rota 12. */
    @Transactional
    public Resultado autorizar(UsuarioAutenticado usuario, long parceriaId, AutorizarEmailRequisicao corpo,
                               HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        verificador.exigirParceria(usuario, parceriaId);
        if (ehDeUsuarioDaRbs(corpo.email())) {
            throw new DadosInvalidosException(Map.of("email", MENSAGEM_EMAIL_DA_RBS));
        }
        return autorizarEmail(usuario, parceriaId, corpo.email(), corpo.vinculo(), requisicao);
    }

    /** Rota 13. A linha não é apagada: o gatilho do banco tira o vínculo e invalida os códigos pendentes. */
    @Transactional
    public void revogar(UsuarioAutenticado usuario, long parceriaId, long emailId, HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        verificador.exigirParceria(usuario, parceriaId);
        // [ISOLAMENTO] O e-mail de outra parceria responde "não existe", como um id que nunca existiu.
        Linha linha = emails.buscarPorId(parceriaId, emailId)
                .orElseThrow(() -> new NaoEncontradoException("Não encontramos este e-mail nesta parceria."));
        if (linha.revogado()) {
            throw new ConflitoException("Este e-mail já estava revogado.");
        }
        emails.revogar(linha.id(), usuario.id());
        historico.registrar(AcaoHistorico.REVOGACAO, usuario, parceriaId, "email_autorizado", linha.id(),
                descricao(linha.email(), linha.vinculo()), null, "o acesso desta pessoa à parceria foi removido", requisicao);
    }

    static final String MENSAGEM_EMAIL_DA_RBS =
            "Este e-mail é de um usuário da RBS e não pode ser vinculado a uma parceria.";

    /** A RBS não entra como patrocinador: o banco recusa o vínculo (chave fk_parceria_usuario_so_admin_patrocinador). */
    boolean ehDeUsuarioDaRbs(String email) {
        return emails.contaDoEmail(normalizar(email)).filter(c -> !"ADMIN_PATROCINADOR".equals(c.perfil())).isPresent();
    }

    /**
     * O coração da rota 12, também usado na criação da parceria (que já conferiu a permissão). Quatro casos:
     * e-mail novo; e-mail pendente (reemite o código); e-mail revogado (reautoriza); e-mail já ativo (409).
     * Se a pessoa já tem conta com senha (entrou por outra parceria), o vínculo é feito na hora e não há código:
     * ela já provou que é dona do e-mail.
     */
    Resultado autorizarEmail(UsuarioAutenticado usuario, long parceriaId, String emailDigitado, String vinculo,
                             HttpServletRequest requisicao) {
        String email = normalizar(emailDigitado);
        Optional<Conta> conta = emails.contaDoEmail(email);
        Optional<Linha> existente = emails.buscarPorEmail(parceriaId, email);

        long emailId;
        boolean criado = existente.isEmpty();
        String detalhe;
        if (criado) {
            emailId = emails.inserir(parceriaId, email, vinculo, usuario.id());
            detalhe = "e-mail autorizado";
        } else {
            Linha linha = existente.get();
            if (!linha.revogado() && linha.ativo()) {
                throw new ConflitoException("Este e-mail já está autorizado nesta parceria.");
            }
            emailId = linha.id();
            if (linha.revogado()) {
                emails.reautorizar(emailId, vinculo, usuario.id());   // o gatilho devolve o vínculo a quem já tem conta
                detalhe = "e-mail reautorizado";
            } else {
                if (!linha.vinculo().equals(vinculo)) {
                    emails.atualizarVinculo(emailId, vinculo);
                }
                detalhe = "código de ativação reemitido";
            }
        }

        CodigoEmitido codigo = null;
        if (conta.isPresent() && conta.get().comSenha()) {
            emails.vincular(parceriaId, conta.get().id(), vinculo);
        } else {
            codigo = ativacao.emitir(emailId);
        }

        historico.registrar(AcaoHistorico.AUTORIZACAO, usuario, parceriaId, "email_autorizado", emailId, null,
                descricao(email, vinculo), detalhe, requisicao);
        EmailAutorizadoDto dto = emails.buscar(emailId);
        return new Resultado(EmailAutorizadoComCodigoDto.de(dto, codigo != null ? codigo.codigo() : null,
                codigo != null ? codigo.expiraEm() : null), criado);
    }

    /** E-mail sem espaços nas pontas e em minúsculas: o banco compara sem diferenciar maiúsculas, e assim fica uma grafia só. */
    static String normalizar(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    // Mesmo texto que a amostra do banco grava: "marketing@amarelo.example (marca)".
    private static String descricao(String email, String vinculo) {
        return email + " (" + vinculo.toLowerCase(Locale.ROOT) + ")";
    }
}
