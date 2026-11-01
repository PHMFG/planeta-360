package br.com.rbs.portal.atlas;

import br.com.rbs.portal.atlas.AtlasDtos.ConversaDaRbsResumoDto;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ConversasDaRbsDto;
import br.com.rbs.portal.atlas.provedor.DisponibilidadeDoLlm;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * O Atlas da RBS: a conversa da equipe da RBS por edição, sem parceria, para tirar dúvidas operacionais sobre os documentos globais
 * da edição (marco M8). [ATLAS] Aqui moram as rotas 67 (abrir) e 68 (listar); a mensagem, a leitura e o diagnóstico da conversa
 * passam pelas rotas 44, 45 e 49, no {@link AtlasService}. [PERMISSAO] Só o Admin RBS (o {@code PermissaoAntesDoCorpo} responde 403 à
 * marca antes do corpo), e só com {@code atlas.rbs.ligado}: desligado, a RBS recebe 404 (a rota não existe para ela). A conversa é de
 * quem a abriu e de um evento: [ISOLAMENTO] a RBS não vê a conversa de outra pessoa, e a marca não vê nenhuma.
 */
@Service
public class AtlasDaRbsService {

    /** Quantas conversas a rota 68 devolve (as mesmas 20 da rota 43). */
    public static final int LIMITE_DE_CONVERSAS = AtlasService.LIMITE_DE_CONVERSAS;
    public static final String EDICAO_NAO_ENCONTRADA = "Não encontramos esta edição do festival.";

    private final AtlasRepositorio repositorio;
    private final ModoDoAtlas modo;
    private final VerificadorDeAcesso verificador;
    private final DisponibilidadeDoLlm llm;

    public AtlasDaRbsService(AtlasRepositorio repositorio, ModoDoAtlas modo, VerificadorDeAcesso verificador, DisponibilidadeDoLlm llm) {
        this.repositorio = repositorio;
        this.modo = modo;
        this.verificador = verificador;
        this.llm = llm;
    }

    /**
     * Rota 67. Abre uma conversa nova da pessoa da RBS neste evento, vazia. Ordem das respostas: 403 (marca), 404 (Atlas da RBS
     * desligado; edição que não existe).
     */
    public ConversaDto abrir(UsuarioAutenticado usuario, long eventoId) {
        exigirAcesso(usuario, eventoId);
        long conversaId = repositorio.criarConversaDaRbs(eventoId, usuario.id());
        return new ConversaDto(conversaId, llm.disponivel(), false, modo.debugDeFerramentas(), modo.diagnosticoLigado(), List.of());
    }

    /** Rota 68. As conversas DESTA pessoa neste evento, da mexida há menos tempo para a mais antiga, até 20. */
    public ConversasDaRbsDto listar(UsuarioAutenticado usuario, long eventoId) {
        exigirAcesso(usuario, eventoId);
        List<ConversaDaRbsResumoDto> conversas = repositorio.conversasDaRbs(eventoId, usuario.id(), LIMITE_DE_CONVERSAS).stream()
                .map(c -> new ConversaDaRbsResumoDto(c.id(), Datas.emBrasilia(c.criadaEm()), Datas.emBrasilia(c.atualizadaEm()), c.previa()))
                .toList();
        return new ConversasDaRbsDto(llm.disponivel(), conversas);
    }

    private void exigirAcesso(UsuarioAutenticado usuario, long eventoId) {
        verificador.exigirRbs(usuario);
        if (!modo.rbsLigado()) {
            throw new NaoEncontradoException();   // desligado, a rota não existe para a RBS
        }
        if (!repositorio.eventoExiste(eventoId)) {
            throw new NaoEncontradoException(EDICAO_NAO_ENCONTRADA);
        }
    }
}
