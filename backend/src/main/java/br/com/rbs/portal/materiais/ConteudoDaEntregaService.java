package br.com.rbs.portal.materiais;

import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.materiais.ConteudoRepositorio.Gravado;
import br.com.rbs.portal.materiais.EntregaRepositorio.EntregaBase;
import br.com.rbs.portal.materiais.MateriaisDtos.ConteudoEnviadoDto;
import br.com.rbs.portal.materiais.MateriaisDtos.ConteudoRequisicao;
import br.com.rbs.portal.materiais.MateriaisDtos.UsuarioResumoDto;
import jakarta.servlet.http.HttpServletRequest;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Texto ou link enviado numa entrega (rota 86, R5.3.a): cada envio é uma versão nova da entrega, como um arquivo, com autor, data e
 * histórico, e o status vai a ENVIADO como no envio de arquivo. Não há rota para editar nem apagar (R5.3.c): corrigir é enviar de novo,
 * e o gatilho rn_entrega_conteudo_imutavel do banco recusa UPDATE e DELETE. [ISOLAMENTO] Entrega de outra parceria dá 404.
 * [PERMISSAO] As mesmas regras da rota 17: a marca só com a aba aberta e enquanto a entrega espera material; a RBS em qualquer status.
 * [HISTORICO] O texto enviado não vai ao histórico (só o tipo e a versão); do link vai o domínio.
 */
@Service
public class ConteudoDaEntregaService {

    static final int LIMITE_TEXTO = 5000;
    static final int LIMITE_TITULO = 160;
    static final int LIMITE_OBSERVACAO = 500;
    static final String FRASE_DA_PARCERIA_ENCERRADA = "Esta parceria está encerrada e não aceita novos envios.";

    /** Status em que o patrocinador ainda pode enviar (os mesmos da rota 17). */
    private static final Set<String> ACEITAM_ENVIO = Set.of("NAO_INICIADO", "AGUARDANDO_ENVIO", "AJUSTE_SOLICITADO", "ENVIADO");

    /** Os controles que o CHECK ck_conteudo_sem_controle recusa: o NUL e os demais, menos o tab, a quebra de linha e o retorno de carro. */
    private static final Pattern CONTROLE = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    private final AcessoAEntregas acesso;
    private final EntregaRepositorio entregas;
    private final ConteudoRepositorio conteudos;
    private final HistoricoService historico;

    public ConteudoDaEntregaService(AcessoAEntregas acesso, EntregaRepositorio entregas, ConteudoRepositorio conteudos,
                                    HistoricoService historico) {
        this.acesso = acesso;
        this.entregas = entregas;
        this.conteudos = conteudos;
        this.historico = historico;
    }

    /**
     * Rota 86. Ordem das respostas: 404 (entrega de outra parceria ou que não existe), 403 (aba fechada), 409 (parceria encerrada), 422
     * (tipo, texto, endereço, título ou observação inválidos), 409 (status que não aceita envio da marca).
     */
    @Transactional
    public ConteudoEnviadoDto enviar(UsuarioAutenticado usuario, long entregaId, ConteudoRequisicao corpo, HttpServletRequest requisicao) {
        EntregaBase entrega = acesso.exigir(usuario, entregaId, true);
        acesso.exigirAbaLiberada(usuario, entrega);
        String situacao = conteudos.situacaoDaParceria(entrega.parceriaId())
                .orElseThrow(() -> new NaoEncontradoException("Não encontramos esta parceria."));
        if ("ENCERRADA".equals(situacao)) {
            throw new ConflitoException(FRASE_DA_PARCERIA_ENCERRADA);
        }

        // [VALIDACAO] Junta os erros de campo numa resposta só. O NUL e os controles dão 422 aqui, antes de chegar ao banco.
        Map<String, String> campos = new LinkedHashMap<>();
        String tipo = corpo.tipo() == null ? "" : corpo.tipo().trim();
        if (!"TEXTO".equals(tipo) && !"LINK".equals(tipo)) {
            campos.put("tipo", "Informe o tipo: TEXTO ou LINK.");
        }
        String texto = null;
        String url = null;
        if ("TEXTO".equals(tipo)) {
            texto = conferirTexto(corpo.texto(), campos);
            if (corpo.url() != null && !corpo.url().isBlank()) {
                campos.put("url", "Para enviar um endereço, use o tipo LINK.");
            }
        } else if ("LINK".equals(tipo)) {
            url = conferirLink(corpo.url(), campos);
            if (corpo.texto() != null && !corpo.texto().isBlank()) {
                campos.put("texto", "Para enviar um texto, use o tipo TEXTO.");
            }
        }
        String titulo = conferirOpcional("titulo", corpo.titulo(), LIMITE_TITULO, "O título", campos);
        String observacao = conferirOpcional("observacao", corpo.observacao(), LIMITE_OBSERVACAO, "A observação", campos);
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }

        // O envio da marca só vale enquanto a entrega espera material; o retorno da RBS vale em qualquer status.
        if (!usuario.ehRbs() && !ACEITAM_ENVIO.contains(entrega.status())) {
            throw new ConflitoException(frasePorStatus(entrega.status()));
        }

        int versao = conteudos.proximaVersao(entregaId);
        Gravado gravado = conteudos.inserir(entregaId, entrega.parceriaId(), tipo, titulo, texto, url, observacao, usuario.id());
        String host = HostDoLink.de(url);
        String novo = ("TEXTO".equals(tipo) ? "Texto" : "Link: " + host) + " (versão " + versao + ")";
        historico.registrar(versao == 1 ? AcaoHistorico.ENVIO_ARQUIVO : AcaoHistorico.NOVA_VERSAO, usuario, entrega.parceriaId(),
                "entrega", entregaId, versao == 1 ? null : "versão " + (versao - 1), novo, observacao, requisicao);

        String status = entrega.status();
        if (!usuario.ehRbs() && !"ENVIADO".equals(status)) {
            entregas.atualizarStatus(entregaId, "ENVIADO");
            historico.registrar(AcaoHistorico.ALTERACAO_STATUS, usuario, entrega.parceriaId(), "entrega", entregaId, status,
                    "ENVIADO", "envio automático: " + novo, requisicao);
            status = "ENVIADO";
        } else {
            entregas.marcarComoAtualizada(entregaId);
        }
        return new ConteudoEnviadoDto(gravado.id(), entregaId, tipo, versao, titulo, texto, url, host, observacao,
                new UsuarioResumoDto(usuario.id(), usuario.nome(), usuario.perfil().name()), Datas.emBrasilia(gravado.enviadoEm()), status);
    }

    /** Texto de 1 a 5.000 caracteres (contados como o banco conta: por caractere, não por unidade UTF-16), sem controle além do tab e da quebra de linha. */
    private static String conferirTexto(String informado, Map<String, String> campos) {
        if (informado == null || informado.isBlank()) {
            campos.put("texto", "Escreva o texto.");
            return null;
        }
        if (CONTROLE.matcher(informado).find()) {
            campos.put("texto", "O texto tem caracteres inválidos (de controle). Copie de novo só o texto.");
            return null;
        }
        String texto = Normalizer.normalize(informado, Normalizer.Form.NFC).strip();
        if (texto.codePointCount(0, texto.length()) > LIMITE_TEXTO) {
            campos.put("texto", "O texto tem no máximo " + LIMITE_TEXTO + " caracteres.");
            return null;
        }
        return texto;
    }

    /** Endereço http ou https com host, sem usuario@, espaço, controle nem bidi, até 500 caracteres. */
    private static String conferirLink(String informado, Map<String, String> campos) {
        if (informado == null || informado.isBlank()) {
            campos.put("url", "Informe o endereço do link.");
            return null;
        }
        String url = informado.strip();
        if (!HostDoLink.valido(url)) {
            campos.put("url", "Endereço inválido. Use um link completo que comece com http:// ou https://, sem espaços e sem usuário e senha, com até "
                    + HostDoLink.LIMITE + " caracteres.");
            return null;
        }
        return url;
    }

    private static String conferirOpcional(String campo, String informado, int limite, String nome, Map<String, String> campos) {
        if (informado == null || informado.isBlank()) {
            return null;
        }
        if (CONTROLE.matcher(informado).find()) {
            campos.put(campo, nome + " tem caracteres inválidos (de controle).");
            return null;
        }
        String valor = Normalizer.normalize(informado, Normalizer.Form.NFC).strip();
        if (valor.codePointCount(0, valor.length()) > limite) {
            campos.put(campo, nome + " tem no máximo " + limite + " caracteres.");
            return null;
        }
        return valor;
    }

    private static String frasePorStatus(String status) {
        return switch (status) {
            case "EM_ANALISE" -> "Esta entrega está em análise pela RBS e não aceita novos envios agora.";
            case "APROVADO" -> "Esta entrega já foi aprovada e não aceita novos envios.";
            case "NAO_APLICAVEL" -> "Esta entrega foi marcada como não aplicável e não aceita envios.";
            default -> "Esta entrega não aceita novos envios no status " + status + ".";
        };
    }
}
