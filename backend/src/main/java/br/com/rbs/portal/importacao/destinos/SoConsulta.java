package br.com.rbs.portal.importacao.destinos;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.importacao.AlcanceDeUmaImportacao;
import br.com.rbs.portal.importacao.DestinoDeGravacao;
import br.com.rbs.portal.importacao.DestinoDaImportacao;
import br.com.rbs.portal.importacao.LinhaProposta;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * O destino SO_CONSULTA: o arquivo entra no Atlas (o documento com os localizadores) e nenhum número é gravado. Não há linha
 * proposta para este destino; a confirmação só fecha a importação.
 */
@Component
public class SoConsulta implements DestinoDeGravacao {

    @Override
    public DestinoDaImportacao destino() {
        return DestinoDaImportacao.SO_CONSULTA;
    }

    @Override
    public List<String> problemas(LinhaProposta linha, AlcanceDeUmaImportacao alcance) {
        return List.of();
    }

    @Override
    public ResultadoDaGravacao gravar(UsuarioAutenticado usuario, ContextoDaGravacao contexto, List<LinhaAceita> linhas) {
        return new ResultadoDaGravacao(null, linhas.size(), 0, 0, 0, List.of());
    }
}
