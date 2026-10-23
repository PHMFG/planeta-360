package br.com.rbs.portal.parceria;

import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.parceria.EmailAutorizadoService.Resultado;
import br.com.rbs.portal.parceria.ParceriaDtos.AbaDto;
import br.com.rbs.portal.parceria.ParceriaDtos.CotaDto;
import br.com.rbs.portal.parceria.ParceriaDtos.CriarParceriaRequisicao;
import br.com.rbs.portal.parceria.ParceriaDtos.EmailNovoRequisicao;
import br.com.rbs.portal.parceria.ParceriaDtos.EmailResumoComCodigoDto;
import br.com.rbs.portal.parceria.ParceriaDtos.EmailResumoDto;
import br.com.rbs.portal.parceria.ParceriaDtos.EventoCompletoDto;
import br.com.rbs.portal.parceria.ParceriaDtos.EventoDto;
import br.com.rbs.portal.parceria.ParceriaDtos.ParceriaDetalheDto;
import br.com.rbs.portal.parceria.ParceriaDtos.ParceriaResumoDto;
import br.com.rbs.portal.parceria.ParceriaDtos.PatrocinadorDto;
import br.com.rbs.portal.parceria.ParceriaDtos.PatrocinadorRequisicao;
import br.com.rbs.portal.parceria.ParceriaRepositorio.AbaDaParceria;
import br.com.rbs.portal.parceria.ParceriaRepositorio.Cota;
import br.com.rbs.portal.parceria.ParceriaRepositorio.FiltroDaLista;
import br.com.rbs.portal.parceria.ParceriaRepositorio.ParceriaBase;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Eventos e parcerias: listar, ver e criar (rotas 6 a 9; a edição da rota 10 está em ParceriaEdicaoService).
 * Atende os itens 3 e 5 da Entrega 2. [ISOLAMENTO] O patrocinador só lista e vê a parceria dele. [PERMISSAO] Criar é da RBS.
 * [HISTORICO] CRIACAO e AUTORIZACAO entram na mesma transação da criação.
 */
@Service
public class ParceriaService {

    private final ParceriaRepositorio parcerias;
    private final EmailAutorizadoRepositorio emails;
    private final EmailAutorizadoService emailsService;
    private final VerificadorDeAcesso verificador;
    private final HistoricoService historico;

    public ParceriaService(ParceriaRepositorio parcerias, EmailAutorizadoRepositorio emails,
                           EmailAutorizadoService emailsService, VerificadorDeAcesso verificador, HistoricoService historico) {
        this.parcerias = parcerias;
        this.emails = emails;
        this.emailsService = emailsService;
        this.verificador = verificador;
        this.historico = historico;
    }

    /** Rota 6. */
    @Transactional(readOnly = true)
    public List<EventoCompletoDto> eventos() {
        return parcerias.eventos();
    }

    /** Rota 7. [ISOLAMENTO] A RBS recebe todas; o patrocinador, só as parcerias em que tem vínculo. */
    @Transactional(readOnly = true)
    public List<ParceriaResumoDto> listar(UsuarioAutenticado usuario, Long eventoId, String situacao, String nomeContem) {
        String nome = nomeContem == null || nomeContem.isBlank() ? null : nomeContem.trim();
        return parcerias.listar(new FiltroDaLista(eventoId, situacao, nome, usuario.ehRbs() ? null : usuario.id()));
    }

    /** Rota 9. [ISOLAMENTO] Quem não é da parceria recebe 404, igual a um id que não existe. */
    @Transactional(readOnly = true)
    public ParceriaDetalheDto<EmailResumoDto> detalhe(UsuarioAutenticado usuario, long parceriaId) {
        verificador.exigirParceria(usuario, parceriaId);
        return montar(buscar(parceriaId, false), emails.vigentes(parceriaId));
    }

    /**
     * Rota 8: cria a parceria (e o patrocinador, se for novo) e autoriza os e-mails iniciais. As abas nascem do gatilho
     * do banco, conforme a cota. Tudo numa transação: se um e-mail der erro, nada fica gravado.
     */
    @Transactional
    public ParceriaDetalheDto<EmailResumoComCodigoDto> criar(UsuarioAutenticado usuario, CriarParceriaRequisicao corpo,
                                                             HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        List<EmailNovoRequisicao> pedidos = corpo.emails() == null ? List.of() : corpo.emails();

        // [VALIDACAO] Junta todos os erros de campo numa resposta só, para o front destacar tudo de uma vez.
        Map<String, String> campos = new LinkedHashMap<>();
        PatrocinadorRequisicao patrocinador = corpo.patrocinador();
        String nomeNovo = patrocinador.nome() == null || patrocinador.nome().isBlank() ? null : patrocinador.nome().trim();
        if ((patrocinador.id() != null) == (nomeNovo != null)) {
            campos.put("patrocinador", "Informe o patrocinador existente (id) ou o nome de um novo, um dos dois.");
        } else if (patrocinador.id() != null && !parcerias.patrocinadorExiste(patrocinador.id())) {
            campos.put("patrocinador.id", "Patrocinador não encontrado.");
        }
        if (!parcerias.eventoExiste(corpo.eventoId())) {
            campos.put("eventoId", "Evento não encontrado.");
        }
        Cota cota = parcerias.cotaPorCodigo(corpo.cotaCodigo().trim()).orElse(null);
        if (cota == null) {
            campos.put("cotaCodigo", "Cota não encontrada. Use MASTER, SUBCOTA_G ou SUBCOTA.");
        }
        if ((corpo.valorInvestimento() == null) != (corpo.baseValor() == null)) {
            campos.put(corpo.valorInvestimento() == null ? "valorInvestimento" : "baseValor",
                    "O valor e a base do valor vão juntos: informe os dois ou nenhum.");
        }
        Set<String> vistos = new HashSet<>();
        for (int i = 0; i < pedidos.size(); i++) {
            String email = EmailAutorizadoService.normalizar(pedidos.get(i).email());
            if (!vistos.add(email)) {
                campos.put("emails[" + i + "].email", "E-mail repetido na lista.");
            } else if (emailsService.ehDeUsuarioDaRbs(email)) {
                campos.put("emails[" + i + "].email", EmailAutorizadoService.MENSAGEM_EMAIL_DA_RBS);
            }
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }

        // Conflitos (409): a API confere antes de gravar para devolver a frase clara; o banco é a segunda trava.
        if (nomeNovo != null && parcerias.nomeDePatrocinadorEmUso(nomeNovo, null)) {
            throw new ConflitoException("Já existe um patrocinador com este nome. Use o id dele.");
        }
        if (patrocinador.id() != null && parcerias.parceriaDaEdicaoExiste(corpo.eventoId(), patrocinador.id())) {
            throw new ConflitoException("Este patrocinador já tem parceria nesta edição do evento.");
        }

        long patrocinadorId = nomeNovo != null
                ? parcerias.criarPatrocinador(nomeNovo, textoOuNulo(patrocinador.segmento())) : patrocinador.id();
        long parceriaId = parcerias.criarParceria(corpo.eventoId(), patrocinadorId, cota.id(), textoOuNulo(corpo.nomeProduto()),
                corpo.valorInvestimento(), corpo.baseValor());

        ParceriaBase criada = buscar(parceriaId, false);
        if (nomeNovo != null) {
            historico.registrar(AcaoHistorico.CRIACAO, usuario, parceriaId, "patrocinador", patrocinadorId, null, nomeNovo,
                    "patrocinador novo, cadastrado junto com a parceria", requisicao);
        }
        historico.registrar(AcaoHistorico.CRIACAO, usuario, parceriaId, "parceria", parceriaId, null,
                criada.patrocinadorNome() + " · " + criada.eventoNome() + " " + criada.edicao() + " · " + criada.cotaNome(),
                criada.nomeProduto(), requisicao);

        List<EmailResumoComCodigoDto> autorizados = new ArrayList<>();
        for (EmailNovoRequisicao pedido : pedidos) {
            Resultado resultado = emailsService.autorizarEmail(usuario, parceriaId, pedido.email(), pedido.vinculo(), requisicao);
            var e = resultado.email();
            autorizados.add(new EmailResumoComCodigoDto(e.id(), e.email(), e.vinculo(), e.situacao(), e.codigoAtivacao(),
                    e.codigoExpiraEm()));
        }
        return montar(criada, autorizados);
    }

    // ---------------------------------------------------------------------
    // Apoio, usado também por ParceriaEdicaoService (a rota 10)
    // ---------------------------------------------------------------------

    ParceriaBase buscar(long parceriaId, boolean paraEditar) {
        return parcerias.buscarBase(parceriaId, paraEditar)
                .orElseThrow(() -> new NaoEncontradoException("Não encontramos esta parceria."));
    }

    <E> ParceriaDetalheDto<E> montar(ParceriaBase base, List<E> emailsAutorizados) {
        List<AbaDto> abas = parcerias.abasDaParceria(base.id()).stream().map(ParceriaService::paraAbaDto).toList();
        return new ParceriaDetalheDto<>(base.id(), new PatrocinadorDto(base.patrocinadorId(), base.patrocinadorNome(), base.segmento()),
                new EventoDto(base.eventoId(), base.eventoNome(), base.edicao()), new CotaDto(base.cotaCodigo(), base.cotaNome()),
                base.nomeProduto(), base.valorInvestimento(), base.baseValor(), base.situacao(), base.criadoEm(), abas,
                emailsAutorizados);
    }

    /** Como o contrato descreve: uma aba só conta como liberada se estiver liberada E visível. */
    static AbaDto paraAbaDto(AbaDaParceria aba) {
        return new AbaDto(aba.codigo(), aba.nome(), aba.ordem(), aba.liberada() && aba.visivel());
    }

    /** Texto em branco vale como "sem valor" (nulo no banco). */
    static String textoOuNulo(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }

    static boolean mudou(BigDecimal antes, BigDecimal depois) {
        return antes == null ? depois != null : depois == null || antes.compareTo(depois) != 0;
    }
}
