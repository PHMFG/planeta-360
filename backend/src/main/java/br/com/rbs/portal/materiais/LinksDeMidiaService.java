package br.com.rbs.portal.materiais;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.materiais.MateriaisDtos.LinkDeMidiaDto;
import br.com.rbs.portal.materiais.MateriaisDtos.LinksDeMidiaDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rota 87 (R4.3, R5.3.b): os links de posts da parceria, juntando os da veiculação carregada pela RBS e os enviados como conteúdo nas
 * entregas de Mídia, do mais recente para o mais antigo. [ISOLAMENTO] Parceria de outra marca dá 404. [PERMISSAO] A marca precisa da
 * aba Mídia aberta (403). Só sai endereço http(s) válido (HostDoLink): um link inválido gravado no banco não é devolvido.
 */
@Service
public class LinksDeMidiaService {

    static final String ABA_MIDIA = "MIDIA";

    private final VerificadorDeAcesso verificador;
    private final ConteudoRepositorio repositorio;

    public LinksDeMidiaService(VerificadorDeAcesso verificador, ConteudoRepositorio repositorio) {
        this.verificador = verificador;
        this.repositorio = repositorio;
    }

    @Transactional(readOnly = true)
    public LinksDeMidiaDto listar(UsuarioAutenticado usuario, long parceriaId) {
        verificador.exigirParceria(usuario, parceriaId);
        verificador.exigirAbaLiberada(usuario, parceriaId, ABA_MIDIA);
        return new LinksDeMidiaDto(parceriaId, daParceria(parceriaId));
    }

    /**
     * Os mesmos links da rota 87, sem conferir quem pede nem a aba: para o anexo de pós-venda (rota 96), que já confere a parceria e o
     * relatório disponibilizado. Quem chama é que decide o que mostrar de cada item.
     */
    @Transactional(readOnly = true)
    public List<LinkDeMidiaDto> daParceria(long parceriaId) {
        List<LinkDeMidiaDto> itens = new ArrayList<>(repositorio.linksDaCarga(parceriaId));
        itens.addAll(repositorio.linksEnviados(parceriaId));
        itens.sort(Comparator.comparing(LinkDeMidiaDto::data).reversed());
        return itens;
    }
}
