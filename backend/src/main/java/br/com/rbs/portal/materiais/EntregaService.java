package br.com.rbs.portal.materiais;

import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.materiais.EntregaRepositorio.EntregaBase;
import br.com.rbs.portal.materiais.EntregaRepositorio.FiltroDaFila;
import br.com.rbs.portal.materiais.MateriaisDtos.ArquivoDto;
import br.com.rbs.portal.materiais.MateriaisDtos.CriarEntregaRequisicao;
import br.com.rbs.portal.materiais.MateriaisDtos.EntregaDto;
import br.com.rbs.portal.materiais.MateriaisDtos.ItemDaFilaDto;
import br.com.rbs.portal.materiais.MateriaisDtos.MudarStatusRequisicao;
import br.com.rbs.portal.materiais.MateriaisDtos.ObservacaoDto;
import br.com.rbs.portal.materiais.MateriaisDtos.PaginaDto;
import br.com.rbs.portal.materiais.MateriaisDtos.VersaoDto;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entregas: lista por parceria (rota 14), criação (15), fila da RBS (16) e mudança de status (19). Atende o item 4 da Entrega 2.
 * [ISOLAMENTO] O patrocinador só vê as entregas da parceria dele e das abas liberadas. [PERMISSAO] Criar entrega, ver a fila e
 * mudar o status à mão é só da RBS. [HISTORICO] CRIACAO e ALTERACAO_STATUS entram na mesma transação da alteração.
 */
@Service
public class EntregaService {

    /**
     * [PERMISSAO] As mudanças de status que a RBS pode fazer à mão (o banco não limita). O patrocinador não aparece aqui: o
     * status dele só muda de forma indireta, ao enviar um arquivo (ArquivoService). Para voltar a andar depois de
     * AJUSTE_SOLICITADO, o patrocinador reenvia o arquivo.
     */
    static final Map<String, Set<String>> TRANSICOES = Map.of(
            "NAO_INICIADO", Set.of("AGUARDANDO_ENVIO", "NAO_APLICAVEL"),
            "AGUARDANDO_ENVIO", Set.of("NAO_APLICAVEL"),
            "ENVIADO", Set.of("EM_ANALISE", "AJUSTE_SOLICITADO", "APROVADO"),
            "EM_ANALISE", Set.of("AJUSTE_SOLICITADO", "APROVADO"),
            "AJUSTE_SOLICITADO", Set.of("NAO_APLICAVEL"),
            "APROVADO", Set.of("EM_ANALISE"),
            "NAO_APLICAVEL", Set.of("NAO_INICIADO"));

    private static final Set<String> TODOS_OS_STATUS = Set.of("NAO_INICIADO", "AGUARDANDO_ENVIO", "ENVIADO", "EM_ANALISE",
            "AJUSTE_SOLICITADO", "APROVADO", "NAO_APLICAVEL");

    private final EntregaRepositorio repositorio;
    private final AcessoAEntregas acesso;
    private final VerificadorDeAcesso verificador;
    private final HistoricoService historico;

    public EntregaService(EntregaRepositorio repositorio, AcessoAEntregas acesso, VerificadorDeAcesso verificador,
                          HistoricoService historico) {
        this.repositorio = repositorio;
        this.acesso = acesso;
        this.verificador = verificador;
        this.historico = historico;
    }

    /**
     * Rota 14. [ISOLAMENTO] Parceria de outro dá 404. [PERMISSAO] Pedir {@code aba} de uma aba fechada dá 403 para o
     * patrocinador (a frase diz se a cota não inclui a aba ou se a RBS ainda não a liberou); sem {@code aba}, ele recebe
     * as entregas de todas as abas liberadas. A RBS vê tudo.
     */
    @Transactional(readOnly = true)
    public List<EntregaDto> listar(UsuarioAutenticado usuario, long parceriaId, String abaCodigo, String status) {
        verificador.exigirParceria(usuario, parceriaId);
        if (abaCodigo != null) {
            verificador.exigirAbaLiberada(usuario, parceriaId, abaCodigo);
        }
        return montar(repositorio.daParceria(parceriaId, abaCodigo, status, !usuario.ehRbs()));
    }

    /** Rota 15. [PERMISSAO] O perfil é conferido antes de olhar a parceria: o patrocinador recebe 403 com qualquer id. */
    @Transactional
    public EntregaDto criar(UsuarioAutenticado usuario, long parceriaId, CriarEntregaRequisicao corpo,
                            HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        verificador.exigirParceria(usuario, parceriaId);
        int abaId = repositorio.abaId(corpo.abaCodigo()).orElseThrow(
                () -> new DadosInvalidosException(Map.of("abaCodigo", "Aba não encontrada.")));
        String status = corpo.status() == null ? "AGUARDANDO_ENVIO" : corpo.status();
        String descricao = textoOuNulo(corpo.descricao());

        String papel = corpo.papel() == null || corpo.papel().isBlank() ? null : corpo.papel();
        long entregaId;
        if (papel == null) {
            entregaId = repositorio.inserir(parceriaId, abaId, corpo.titulo().trim(), descricao, status, corpo.prazo());
        } else {
            // [VALIDACAO] O papel fixa a aba (seção 5.5 do plano): o espaço do documento de ingressos é do Perfil; o MIV e a logo, de Aplicações.
            String abaDoPapel = EspacosDaMarca.abaDoPapel(papel);
            if (!abaDoPapel.equals(corpo.abaCodigo())) {
                throw new DadosInvalidosException(Map.of("papel", "Este espaço é da aba " + EspacosDaMarca.nomeDaAba(abaDoPapel) + "."));
            }
            entregaId = repositorio.inserirComPapel(parceriaId, abaId, papel, corpo.titulo().trim(), descricao, status, corpo.prazo())
                    .orElseThrow(() -> new ConflitoException("Esta parceria já tem o espaço " + papel + "."));
        }
        historico.registrar(AcaoHistorico.CRIACAO, usuario, parceriaId, "entrega", entregaId, null, status,
                corpo.titulo().trim() + " (aba " + corpo.abaCodigo() + ")", requisicao);
        return montar(List.of(repositorio.buscar(entregaId, false).orElseThrow())).getFirst();
    }

    /**
     * Rota 16: a fila de trabalho da RBS. {@code status} aceita vários valores separados por vírgula. O patrocinador
     * recebe 403. Os filtros já vêm validados em formato pelo controller; aqui só se confere o conteúdo de status.
     */
    @Transactional(readOnly = true)
    public PaginaDto<ItemDaFilaDto> fila(UsuarioAutenticado usuario, String status, Long parceriaId, Long eventoId,
                                         String abaCodigo, Boolean atrasadas, String titulo, int pagina, int tamanho) {
        verificador.exigirRbs(usuario);
        List<String> statusPedidos = new ArrayList<>();
        if (status != null) {
            for (String valor : status.split(",")) {
                if (!valor.isBlank()) {
                    statusPedidos.add(valor.trim());
                }
            }
        }
        if (!TODOS_OS_STATUS.containsAll(statusPedidos)) {
            throw new DadosInvalidosException(Map.of("status", "Status inválido. Use um ou mais de: NAO_INICIADO, AGUARDANDO_ENVIO, "
                    + "ENVIADO, EM_ANALISE, AJUSTE_SOLICITADO, APROVADO, NAO_APLICAVEL, separados por vírgula."));
        }
        FiltroDaFila filtro = new FiltroDaFila(statusPedidos, parceriaId, eventoId, abaCodigo, Boolean.TRUE.equals(atrasadas),
                textoOuNulo(titulo));
        return repositorio.fila(filtro, Datas.hoje(), pagina, tamanho);
    }

    /**
     * Rota 19. [PERMISSAO] Só a RBS (403 para o patrocinador, com qualquer id). A tabela TRANSICOES decide o que é permitido
     * (409 com a frase do contrato); a observação é obrigatória ao pedir ajuste e ao reabrir uma entrega aprovada (422).
     * [HISTORICO] ALTERACAO_STATUS com o status anterior, o novo e a observação em detalhe, na mesma transação.
     */
    @Transactional
    public EntregaDto mudarStatus(UsuarioAutenticado usuario, long entregaId, MudarStatusRequisicao corpo,
                                  HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        EntregaBase entrega = acesso.exigir(usuario, entregaId, true);
        String de = entrega.status();
        String para = corpo.status();
        if (!TRANSICOES.getOrDefault(de, Set.of()).contains(para)) {
            throw new ConflitoException("Esta entrega não pode ir de " + de + " para " + para + ".");
        }
        String observacao = textoOuNulo(corpo.observacao());
        if (observacao == null && ("AJUSTE_SOLICITADO".equals(para) || "APROVADO".equals(de))) {
            throw new DadosInvalidosException(Map.of("observacao", "AJUSTE_SOLICITADO".equals(para)
                    ? "Explique o que o patrocinador precisa ajustar."
                    : "Explique por que a entrega aprovada está sendo reaberta."));
        }

        repositorio.atualizarStatus(entregaId, para);
        historico.registrar(AcaoHistorico.ALTERACAO_STATUS, usuario, entrega.parceriaId(), "entrega", entregaId, de, para,
                observacao, requisicao);
        return montar(List.of(repositorio.buscar(entregaId, false).orElseThrow())).getFirst();
    }

    // ---------------------------------------------------------------------
    // Montagem da Entrega do contrato
    // ---------------------------------------------------------------------

    /** Junta as entregas com os arquivos, as versões (arquivo, texto e link; a mais nova primeiro) e a última observação, em 3 consultas para todas. */
    List<EntregaDto> montar(List<EntregaBase> bases) {
        List<Long> ids = bases.stream().map(EntregaBase::id).toList();
        Map<Long, List<ArquivoDto>> arquivos = repositorio.arquivosDas(ids);
        Map<Long, List<VersaoDto>> versoes = repositorio.versoesDas(ids, arquivos);
        Map<Long, ObservacaoDto> observacoes = repositorio.ultimasObservacoes(ids);
        return bases.stream().map(b -> new EntregaDto(b.id(), b.parceriaId(), b.abaCodigo(), b.papel(), b.titulo(), b.descricao(),
                b.status(), b.prazo(), Datas.emBrasilia(b.criadoEm()), Datas.emBrasilia(b.atualizadoEm()),
                arquivos.getOrDefault(b.id(), List.of()), versoes.getOrDefault(b.id(), List.of()), observacoes.get(b.id()))).toList();
    }

    private static String textoOuNulo(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }
}
