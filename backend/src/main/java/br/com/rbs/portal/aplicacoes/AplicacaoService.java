package br.com.rbs.portal.aplicacoes;

import br.com.rbs.portal.aplicacoes.AplicacaoDtos.AplicacoesDto;
import br.com.rbs.portal.aplicacoes.AplicacaoDtos.ComprovacaoDto;
import br.com.rbs.portal.aplicacoes.AplicacaoDtos.LinhaDto;
import br.com.rbs.portal.aplicacoes.AplicacaoDtos.TotaisDto;
import br.com.rbs.portal.aplicacoes.AplicacaoRepositorio.ComprovacaoGravada;
import br.com.rbs.portal.aplicacoes.AplicacaoRepositorio.LinhaBase;
import br.com.rbs.portal.aplicacoes.AplicacaoRepositorio.LinhaDeComprovacao;
import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.Armazenamento;
import br.com.rbs.portal.comum.ArquivoRecusadoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.TipoDeArquivo;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.materiais.MateriaisDtos.UsuarioResumoDto;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * A aba Aplicações: o plano de aplicações de marca com as fotos de comprovação (rota 37) e o envio de uma foto pela RBS
 * (rota 38). [ISOLAMENTO] Parceria de outro patrocinador dá 404, igual à que não existe. [PERMISSAO] A marca lê só com a
 * aba APLICACOES aberta (403 com as duas frases do projeto); enviar foto é só da RBS. [UPLOAD] A foto é gravada com a parceria
 * e a aba APLICACOES: sem a aba, o download (rota 18) não conferiria se ela está aberta. [HISTORICO] Cada foto registra
 * CRIACAO na entidade comprovacao_marca.
 */
@Service
public class AplicacaoService {

    static final String ABA = "APLICACOES";
    static final String LINHA_DE_MIDIA = "A comprovação vale só para aplicação de marca.";
    static final String SO_FOTO = "Envie uma foto em PNG ou JPG.";
    private static final int LIMITE_DESCRICAO = 200;
    private static final Set<TipoDeArquivo> FOTOS = Set.of(TipoDeArquivo.PNG, TipoDeArquivo.JPG, TipoDeArquivo.JPEG);

    private final AplicacaoRepositorio repositorio;
    private final Armazenamento armazenamento;
    private final VerificadorDeAcesso verificador;
    private final HistoricoService historico;

    public AplicacaoService(AplicacaoRepositorio repositorio, Armazenamento armazenamento, VerificadorDeAcesso verificador,
                            HistoricoService historico) {
        this.repositorio = repositorio;
        this.armazenamento = armazenamento;
        this.verificador = verificador;
        this.historico = historico;
    }

    // ---------------------------------------------------------------------
    // Rota 37: o plano de aplicações
    // ---------------------------------------------------------------------

    /**
     * Rota 37. Ordem das respostas: 404 (parceria que não existe ou é de outra), 403 (aba APLICACOES fechada para a marca; a
     * RBS passa). Uma só leitura (REPEATABLE READ): os totais, as linhas e as fotos são do mesmo instante, mesmo que a RBS
     * anexe uma foto no meio. Sem aplicação de marca no plano, {@code totais} vem com todos os campos nulos e {@code linhas}
     * vazia (nunca zero inventado), como a rota 29.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AplicacoesDto listar(UsuarioAutenticado usuario, long parceriaId) {
        verificador.exigirParceria(usuario, parceriaId);
        verificador.exigirAbaLiberada(usuario, parceriaId, ABA);

        TotaisDto totais = repositorio.totais(parceriaId)
                .map(l -> new TotaisDto(l.previstas(), l.comprovadas(), l.fotos(), l.percentual()))
                .orElse(new TotaisDto(null, null, null, null));

        Map<Long, List<ComprovacaoDto>> fotos = new LinkedHashMap<>();
        for (LinhaDeComprovacao c : repositorio.comprovacoes(parceriaId)) {
            fotos.computeIfAbsent(c.aproveitamentoId(), id -> new ArrayList<>()).add(comprovacao(c));
        }
        List<LinhaDto> linhas = repositorio.linhas(parceriaId).stream()
                .map(l -> new LinhaDto(l.id(), l.bloco(), l.veiculo(), l.formato(), l.espacoPrograma(), l.janela(), l.previsto(),
                        l.comprovadas(), l.fotos(), fotos.getOrDefault(l.id(), List.of())))
                .toList();
        return new AplicacoesDto(parceriaId, totais, linhas);
    }

    // ---------------------------------------------------------------------
    // Rota 38: anexar uma foto
    // ---------------------------------------------------------------------

    /**
     * Rota 38. Ordem das respostas: 403 (patrocinador, antes de qualquer outra conferência), 404 (parceria, ou linha que não
     * existe ou é de outra parceria), 422 (a linha é de mídia; parte "arquivo" ausente ou vazia; descrição com mais de 200
     * caracteres), 415 (não é PNG nem JPG, ou o conteúdo não bate com a extensão) e 413 (acima de 20 MB), estes dois do
     * Armazenamento. Tudo numa transação: se algo falhar, nem a foto no banco nem o arquivo em disco ficam.
     */
    @Transactional
    public ComprovacaoDto registrar(UsuarioAutenticado usuario, long parceriaId, long aproveitamentoId, MultipartFile arquivo,
                                    String descricaoInformada, HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        verificador.exigirParceria(usuario, parceriaId);
        LinhaBase linha = repositorio.linha(parceriaId, aproveitamentoId)
                .orElseThrow(() -> new NaoEncontradoException("Não encontramos esta aplicação."));
        // [VALIDACAO] A tabela guarda mídia e aplicação juntas; a foto só vale para a aplicação (o gatilho rn_comprovacao_so_aplicacao
        // também recusaria, mas com um 409 sem o campo).
        if (!"APLICACAO_MARCA".equals(linha.categoria())) {
            throw new DadosInvalidosException(LINHA_DE_MIDIA, Map.of("aproveitamentoId", LINHA_DE_MIDIA));
        }

        Map<String, String> campos = new LinkedHashMap<>();
        if (arquivo == null || arquivo.isEmpty()) {
            campos.put("arquivo", "Envie a foto na parte \"arquivo\" do formulário.");
        }
        String descricao = descricaoInformada == null || descricaoInformada.isBlank() ? null : descricaoInformada.trim();
        if (descricao != null && descricao.length() > LIMITE_DESCRICAO) {
            campos.put("descricao", "A descrição tem no máximo " + LIMITE_DESCRICAO + " caracteres.");
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }

        // [UPLOAD] Só foto. O Armazenamento aceita outras extensões, mas aqui elas não servem; o conteúdo (a assinatura de
        // PNG ou JPEG) ele confere: um executável renomeado para .png recebe 415.
        if (!TipoDeArquivo.daExtensao(arquivo.getOriginalFilename()).map(FOTOS::contains).orElse(false)) {
            throw new ArquivoRecusadoException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, SO_FOTO);
        }
        var gravado = armazenamento.gravar(bytesDe(arquivo), arquivo.getOriginalFilename(), "COMPROVACAO", usuario.id(), parceriaId,
                ABA);
        ComprovacaoGravada foto = repositorio.inserir(aproveitamentoId, gravado.id(), descricao, usuario.id());

        // [HISTORICO] A foto fica ligada à linha pelo formato dela, que é o que a RBS e a marca reconhecem.
        historico.registrar(AcaoHistorico.CRIACAO, usuario, parceriaId, "comprovacao_marca", foto.id(), null,
                gravado.nomeOriginal(), linha.formato() + (descricao == null ? "" : ": " + descricao), requisicao);
        return new ComprovacaoDto(foto.id(), gravado.id(), gravado.nomeOriginal(), descricao,
                new UsuarioResumoDto(usuario.id(), usuario.nome(), usuario.perfil().name()), Datas.emBrasilia(foto.registradoEm()));
    }

    // ---------------------------------------------------------------------
    // Ajudas
    // ---------------------------------------------------------------------

    private static ComprovacaoDto comprovacao(LinhaDeComprovacao c) {
        return new ComprovacaoDto(c.id(), c.arquivoId(), c.nomeOriginal(), c.descricao(),
                new UsuarioResumoDto(c.usuarioId(), c.usuarioNome(), c.usuarioPerfil()), Datas.emBrasilia(c.registradoEm()));
    }

    private static byte[] bytesDe(MultipartFile arquivo) {
        try {
            return arquivo.getBytes();
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }
    }
}
