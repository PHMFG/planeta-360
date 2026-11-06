package br.com.rbs.portal.pesquisa;

import br.com.rbs.portal.comum.SemPermissaoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.pesquisa.PerfilDaPesquisaDtos.CategoriaDoPerfilDto;
import br.com.rbs.portal.pesquisa.PerfilDaPesquisaDtos.PerfilDaPesquisaDto;
import br.com.rbs.portal.pesquisa.PerfilDaPesquisaDtos.PesquisaDoPerfilDto;
import br.com.rbs.portal.pesquisa.PerfilDaPesquisaDtos.RespostaTextoDto;
import br.com.rbs.portal.pesquisa.PerfilDaPesquisaRepositorio.LinhaDePerfil;
import br.com.rbs.portal.pesquisa.PesquisaRepositorio.LinhaDePesquisa;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rota 119: o perfil demográfico da pesquisa. [PERMISSAO] A marca precisa ser da parceria (404), ter a aba PESQUISA aberta
 * (403) e ser da cota Master (403, como na comprovação mensal); a RBS vê qualquer parceria. [PESQUISA] O perfil da marca vem
 * da pesquisa da ativação; o comparativo, da pesquisa GERAL da mesma edição. A resposta de texto com suspeita de dado
 * pessoal não sai para a marca; a RBS a recebe com a marca da suspeita.
 */
@Service
public class PerfilDaPesquisaService {

    private static final String ABA_DA_PESQUISA = "PESQUISA";
    private static final String COTA_DA_PESQUISA = "MASTER";

    private final PerfilDaPesquisaRepositorio repositorio;
    private final PesquisaRepositorio pesquisaRepositorio;
    private final VerificadorDeAcesso verificador;

    public PerfilDaPesquisaService(PerfilDaPesquisaRepositorio repositorio, PesquisaRepositorio pesquisaRepositorio,
                                   VerificadorDeAcesso verificador) {
        this.repositorio = repositorio;
        this.pesquisaRepositorio = pesquisaRepositorio;
        this.verificador = verificador;
    }

    @Transactional(readOnly = true)
    public PerfilDaPesquisaDto perfil(UsuarioAutenticado usuario, long parceriaId) {
        verificador.exigirParceria(usuario, parceriaId);
        verificador.exigirAbaLiberada(usuario, parceriaId, ABA_DA_PESQUISA);
        if (!usuario.ehRbs() && !COTA_DA_PESQUISA.equals(repositorio.cota(parceriaId).orElse(null))) {
            throw new SemPermissaoException(PesquisaService.SO_AS_MASTER);
        }

        LinhaDePesquisa ativacao = pesquisaRepositorio.pesquisaDaAtivacao(parceriaId).orElse(null);
        LinhaDePesquisa geral = repositorio.pesquisaGeral(parceriaId).orElse(null);
        PesquisaDoPerfilDto pesquisa = ativacao == null ? null
                : new PesquisaDoPerfilDto(ativacao.id(), ativacao.nome(), ativacao.baseRespondentes());
        List<CategoriaDoPerfilDto> doPerfil = ativacao == null ? List.of()
                : repositorio.perfil(ativacao.id(), parceriaId).stream().map(PerfilDaPesquisaService::paraCategoria).toList();
        List<CategoriaDoPerfilDto> daGeral = geral == null ? List.of()
                : repositorio.perfil(geral.id(), null).stream().map(PerfilDaPesquisaService::paraCategoria).toList();
        List<RespostaTextoDto> respostas = ativacao == null ? List.of()
                : repositorio.respostasTexto(ativacao.id(), parceriaId, usuario.ehRbs()).stream()
                        .map(r -> new RespostaTextoDto(r.perguntaId(), r.ordem(), r.texto(), r.contagem(),
                                usuario.ehRbs() ? r.suspeita() : null))
                        .toList();
        return new PerfilDaPesquisaDto(pesquisa, doPerfil, daGeral, respostas);
    }

    private static CategoriaDoPerfilDto paraCategoria(LinhaDePerfil l) {
        return new CategoriaDoPerfilDto(l.dimensao(), l.categoria(), l.valor(), l.baseRespondentes());
    }
}
