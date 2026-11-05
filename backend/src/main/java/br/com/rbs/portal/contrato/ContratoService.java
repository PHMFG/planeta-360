package br.com.rbs.portal.contrato;

import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.Armazenamento;
import br.com.rbs.portal.comum.ArquivoRecusadoException;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.contrato.ContratoDtos.ContratoDto;
import br.com.rbs.portal.contrato.ContratoDtos.SignatarioDto;
import br.com.rbs.portal.contrato.ContratoDtos.SignatarioRequisicao;
import br.com.rbs.portal.contrato.ContratoDtos.VersaoDoContratoDto;
import br.com.rbs.portal.contrato.ContratoRepositorio.Linha;
import br.com.rbs.portal.contrato.ContratoRepositorio.Versao;
import br.com.rbs.portal.materiais.MateriaisDtos.UsuarioResumoDto;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * O contrato da parceria (R5.7, rotas 74 a 76). [CONTRATO] A marca e a RBS veem o signatário e as versões do contrato assinado; as duas
 * informam o signatário (a RBS no lugar da marca); só a RBS envia o PDF assinado. [PERMISSAO] A aba Contrato fechada ou oculta dá 403 à
 * marca; parceria de outra marca, 404; parceria ENCERRADA recusa a escrita (409). [DADO-PESSOAL] O nome, o cargo e o e-mail do signatário
 * (D8) ficam só na tabela {@code contrato}: o histórico diz QUE mudou, nunca o quê, e nenhuma mensagem de erro repete o valor.
 */
@Service
public class ContratoService {

    static final String ABA = "CONTRATO";
    static final String PARCERIA_ENCERRADA = "Esta parceria está encerrada e não aceita alterações.";
    static final int NOME_MINIMO = 3;
    static final int NOME_MAXIMO = 120;
    static final int EMAIL_MAXIMO = 254;
    static final int CARGO_MAXIMO = 80;
    static final int OBSERVACAO_MAXIMA = 500;
    /** O mesmo formato do CHECK ck_contrato_signatario_email (V16). */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern DATA = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final ContratoRepositorio repositorio;
    private final VerificadorDeAcesso verificador;
    private final HistoricoService historico;
    private final Armazenamento armazenamento;

    public ContratoService(ContratoRepositorio repositorio, VerificadorDeAcesso verificador, HistoricoService historico,
                           Armazenamento armazenamento) {
        this.repositorio = repositorio;
        this.verificador = verificador;
        this.historico = historico;
        this.armazenamento = armazenamento;
    }

    /** Rota 74. 404 (parceria de outra marca), 403 (aba Contrato fechada ou oculta para a marca). */
    @Transactional(readOnly = true)
    public ContratoDto ver(UsuarioAutenticado usuario, long parceriaId) {
        entrar(usuario, parceriaId);
        return montar(parceriaId);
    }

    /**
     * Rota 75. Ordem: 404, 403 (aba), 409 (encerrada), 422 (campos, todos numa resposta). A marca e a RBS (no lugar da marca) gravam.
     * [HISTORICO] CRIACAO na primeira vez e EDICAO nas seguintes, na entidade {@code contrato}, SEM valor e SEM nome no detalhe.
     */
    @Transactional
    public ContratoDto informarSignatario(UsuarioAutenticado usuario, long parceriaId, SignatarioRequisicao corpo,
                                          HttpServletRequest requisicao) {
        entrar(usuario, parceriaId);
        exigirNaoEncerrada(parceriaId);
        SignatarioRequisicao c = corpo == null ? new SignatarioRequisicao(null, null, null) : corpo;
        Map<String, String> campos = new LinkedHashMap<>();
        String nome = limpo(c.nome());
        if (nome == null) {
            campos.put("nome", "Informe o nome de quem assina.");
        } else if (temControle(nome, false)) {
            campos.put("nome", "O nome tem caracteres inválidos.");
        } else if (nome.length() < NOME_MINIMO || nome.length() > NOME_MAXIMO) {
            campos.put("nome", "O nome tem de " + NOME_MINIMO + " a " + NOME_MAXIMO + " caracteres.");
        }
        String email = limpo(c.email());
        if (email == null) {
            campos.put("email", "Informe o e-mail de quem assina.");
        } else if (temControle(email, false) || email.length() > EMAIL_MAXIMO || !EMAIL.matcher(email).matches()) {
            campos.put("email", "Informe um e-mail válido, como nome@empresa.com.br.");
        }
        String cargo = limpo(c.cargo());
        if (cargo != null && temControle(cargo, false)) {
            campos.put("cargo", "O cargo tem caracteres inválidos.");
        } else if (cargo != null && cargo.length() > CARGO_MAXIMO) {
            campos.put("cargo", "O cargo tem no máximo " + CARGO_MAXIMO + " caracteres.");
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }

        boolean jaTinha = repositorio.buscar(parceriaId).map(l -> l.signatarioNome() != null).orElse(false);
        long contratoId = repositorio.gravarSignatario(parceriaId, nome, email, cargo, usuario.id());
        historico.registrar(jaTinha ? AcaoHistorico.EDICAO : AcaoHistorico.CRIACAO, usuario, parceriaId, "contrato", contratoId, null, null,
                jaTinha ? "Signatário alterado" : "Signatário informado", requisicao);
        return montar(parceriaId);
    }

    /**
     * Rota 76. Só a RBS. Ordem: 403 (marca), 404, 409 (encerrada), 422 (arquivo vazio, data inválida ou no futuro, observação), 415 (não é
     * PDF) e 413 (tamanho). A versão é a maior que o contrato já tem mais 1, sob o bloqueio da parceria, e o contrato passa a apontar
     * para ela na mesma transação. [HISTORICO] ENVIO_ARQUIVO na v1 e NOVA_VERSAO nas seguintes, com o nome do arquivo.
     */
    @Transactional
    public ContratoDto enviarArquivo(UsuarioAutenticado usuario, long parceriaId, MultipartFile arquivo, String assinadoEmInformado,
                                     String observacaoInformada, HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        entrar(usuario, parceriaId);
        exigirNaoEncerrada(parceriaId);

        Map<String, String> campos = new LinkedHashMap<>();
        if (arquivo == null || (arquivo.isEmpty() && (arquivo.getOriginalFilename() == null || arquivo.getOriginalFilename().isBlank()))) {
            campos.put("arquivo", "Envie o contrato na parte \"arquivo\" do formulário.");
        } else if (arquivo.isEmpty()) {
            campos.put("arquivo", "O arquivo está vazio.");
        }
        LocalDate assinadoEm = data(assinadoEmInformado, campos);
        String observacao = limpo(observacaoInformada);
        if (observacao != null && temControle(observacao, true)) {
            campos.put("observacao", "A observação tem caracteres inválidos.");
        } else if (observacao != null && observacao.length() > OBSERVACAO_MAXIMA) {
            campos.put("observacao", "A observação tem no máximo " + OBSERVACAO_MAXIMA + " caracteres.");
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }
        String nomeDoArquivo = arquivo.getOriginalFilename();
        if (nomeDoArquivo == null || !nomeDoArquivo.strip().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new ArquivoRecusadoException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "O contrato assinado precisa ser um PDF.");
        }

        int versao = repositorio.proximaVersao(parceriaId);
        var gravado = armazenamento.gravar(bytesDe(arquivo), nomeDoArquivo, "CONTRATO", usuario.id(), parceriaId, ABA);
        repositorio.numerarVersao(gravado.id(), versao, observacao);
        long contratoId = repositorio.apontarArquivo(parceriaId, gravado.id(), assinadoEm);
        historico.registrar(versao == 1 ? AcaoHistorico.ENVIO_ARQUIVO : AcaoHistorico.NOVA_VERSAO, usuario, parceriaId, "contrato", contratoId,
                versao == 1 ? null : "versão " + (versao - 1), gravado.nomeOriginal() + " (versão " + versao + ")", observacao, requisicao);
        return montar(parceriaId);
    }

    // ---------------------------------------------------------------------
    // Apoio
    // ---------------------------------------------------------------------

    /** A parceria é do usuário (404) e a aba está aberta para ele (403). */
    private void entrar(UsuarioAutenticado usuario, long parceriaId) {
        verificador.exigirParceria(usuario, parceriaId);
        verificador.exigirAbaLiberada(usuario, parceriaId, ABA);
    }

    /** Trava a parceria e recusa a escrita se ela está encerrada. */
    private void exigirNaoEncerrada(long parceriaId) {
        String situacao = repositorio.travarParceria(parceriaId).orElseThrow(() -> new NaoEncontradoException("Não encontramos esta parceria."));
        if ("ENCERRADA".equals(situacao)) {
            throw new ConflitoException(PARCERIA_ENCERRADA);
        }
    }

    private ContratoDto montar(long parceriaId) {
        Linha linha = repositorio.buscar(parceriaId).orElse(null);
        List<VersaoDoContratoDto> versoes = repositorio.versoes(parceriaId).stream().map(ContratoService::paraDto).toList();
        SignatarioDto signatario = linha == null || linha.signatarioNome() == null ? null
                : new SignatarioDto(linha.signatarioNome(), linha.signatarioCargo(), linha.signatarioEmail(),
                        linha.informadoPor() == null ? null : new UsuarioResumoDto(linha.informadoPor(), linha.informadoPorNome(), linha.informadoPorPerfil()),
                        Datas.emBrasilia(linha.informadoEm()));
        VersaoDoContratoDto emVigor = linha == null || linha.arquivoId() == null ? null
                : versoes.stream().filter(v -> v.id().equals(linha.arquivoId())).findFirst().orElse(null);
        return new ContratoDto(parceriaId, signatario, linha == null ? null : linha.assinadoEm(), emVigor, versoes);
    }

    private static VersaoDoContratoDto paraDto(Versao v) {
        return new VersaoDoContratoDto(v.id(), v.versao(), v.nomeOriginal(), v.mimeType(), v.tamanhoBytes(),
                new UsuarioResumoDto(v.enviadoPor(), v.enviadoPorNome(), v.enviadoPorPerfil()), Datas.emBrasilia(v.enviadoEm()), v.observacao());
    }

    /** A data da assinatura: AAAA-MM-DD, que exista, de 2000 até hoje (em Brasília). Vazia = sem data. */
    private static LocalDate data(String texto, Map<String, String> campos) {
        String limpo = limpo(texto);
        if (limpo == null) {
            return null;
        }
        LocalDate data;
        try {
            if (!DATA.matcher(limpo).matches()) {
                throw new DateTimeException("formato");
            }
            data = LocalDate.parse(limpo);
        } catch (DateTimeException invalida) {
            campos.put("assinadoEm", "Data inválida. Use o formato AAAA-MM-DD, por exemplo 2026-11-04.");
            return null;
        }
        if (data.getYear() < 2000) {
            campos.put("assinadoEm", "Data inválida. O ano tem de ser 2000 ou mais.");
            return null;
        }
        if (data.isAfter(Datas.hoje())) {
            campos.put("assinadoEm", "A data da assinatura não pode estar no futuro.");
            return null;
        }
        return data;
    }

    private static String limpo(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }

    /** NUL (o PostgreSQL não o guarda) e os demais caracteres de controle; a quebra de linha e o tab só valem onde {@code multilinha}. */
    static boolean temControle(String texto, boolean multilinha) {
        return texto.codePoints().anyMatch(c -> Character.isISOControl(c) && !(multilinha && (c == '\n' || c == '\r' || c == '\t')));
    }

    private static byte[] bytesDe(MultipartFile arquivo) {
        try {
            return arquivo.getBytes();
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }
    }
}
