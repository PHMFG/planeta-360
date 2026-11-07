package br.com.rbs.portal.visita;

import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.visita.VisitaDtos.NovidadeDaAbaDto;
import br.com.rbs.portal.visita.VisitaDtos.NovidadesDto;
import br.com.rbs.portal.visita.VisitaRepositorio.LinhaDeNovidade;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rota 120: a visita de cada pessoa a cada aba e o selo "novo" (S3). [PERMISSAO] A pessoa precisa ser da parceria (404); a
 * marca só marca visita a uma aba aberta (403), e a RBS passa em qualquer uma. O relatório não é uma aba do menu: entra como
 * "RELATORIO", sem a conferência de aba. [PESQUISA] Nada é escrito no histórico: a própria visita é o registro.
 */
@Service
public class VisitaService {

    /** As sete abas do menu mais o relatório, na ordem em que a resposta as devolve. */
    public static final List<String> ABAS = List.of("PERFIL", "APLICACOES", "MIDIA", "CORTESIAS", "CREDENCIAMENTO",
            "PESQUISA", "CONTRATO", "RELATORIO");
    private static final Set<String> CODIGOS = Set.copyOf(ABAS);
    public static final String ABA_INVALIDA = "Aba inválida. Use o código de uma aba ou RELATORIO.";

    private final VisitaRepositorio repositorio;
    private final VerificadorDeAcesso verificador;

    public VisitaService(VisitaRepositorio repositorio, VerificadorDeAcesso verificador) {
        this.repositorio = repositorio;
        this.verificador = verificador;
    }

    /** POST da rota 120. Grava a visita com o agora do banco; a marca só entra na aba que está aberta para ela. */
    @Transactional
    public void registrar(UsuarioAutenticado usuario, long parceriaId, String aba) {
        verificador.exigirParceria(usuario, parceriaId);
        String codigo = normalizar(aba);
        if (!usuario.ehRbs() && !"RELATORIO".equals(codigo)) {
            verificador.exigirAbaLiberada(usuario, parceriaId, codigo);
        }
        repositorio.registrar(usuario.id(), parceriaId, codigo);
    }

    /** GET da rota 120. As oito abas na ordem do menu, com a última visita da pessoa e o selo "novo". */
    @Transactional(readOnly = true)
    public NovidadesDto novidades(UsuarioAutenticado usuario, long parceriaId) {
        verificador.exigirParceria(usuario, parceriaId);
        Map<String, NovidadeDaAbaDto> abas = new LinkedHashMap<>();
        for (LinhaDeNovidade linha : repositorio.novidades(usuario.id(), parceriaId)) {
            abas.put(linha.aba(), new NovidadeDaAbaDto(linha.novo(), Datas.emBrasilia(linha.vistoEm())));
        }
        return new NovidadesDto(abas);
    }

    private static String normalizar(String aba) {
        String codigo = aba == null ? "" : aba.trim().toUpperCase(Locale.ROOT);
        if (!CODIGOS.contains(codigo)) {
            throw new DadosInvalidosException(Map.of("aba", ABA_INVALIDA));
        }
        return codigo;
    }
}
