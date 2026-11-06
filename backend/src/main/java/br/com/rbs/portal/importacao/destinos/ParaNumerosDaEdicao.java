package br.com.rbs.portal.importacao.destinos;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.importacao.AlcanceDeUmaImportacao;
import br.com.rbs.portal.importacao.DestinoDeGravacao;
import br.com.rbs.portal.importacao.DestinoDaImportacao;
import br.com.rbs.portal.importacao.LinhaProposta;
import br.com.rbs.portal.relatorio.PosVendaDtos.InformarResultadoRequisicao;
import br.com.rbs.portal.relatorio.PosVendaService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * O destino NUMEROS_EDICAO: os números do "festival em números" (evento_resultado), pelo mesmo serviço das rotas 100 e 101. A
 * importação só cria os números; o relatório gerado depois é que os lê.
 */
@Component
public class ParaNumerosDaEdicao implements DestinoDeGravacao {

    private final PosVendaService posVenda;

    public ParaNumerosDaEdicao(PosVendaService posVenda) {
        this.posVenda = posVenda;
    }

    @Override
    public DestinoDaImportacao destino() {
        return DestinoDaImportacao.NUMEROS_EDICAO;
    }

    @Override
    public List<String> problemas(LinhaProposta linha, AlcanceDeUmaImportacao alcance) {
        List<String> problemas = new ArrayList<>();
        for (String campo : List.of("indicador", "unidade", "fonte")) {
            Object valor = linha.dados().get(campo);
            if (valor == null || String.valueOf(valor).isBlank()) {
                problemas.add("Falta " + campo + " do número.");
            }
        }
        Object valor = linha.dados().get("valor");
        BigDecimal numero = valor == null ? null : br.com.rbs.portal.importacao.extracao.MapeadorTabular.decimal(String.valueOf(valor));
        if (numero == null || numero.signum() <= 0) {
            problemas.add("O valor do número precisa ser maior que zero.");
        }
        return problemas;
    }

    @Override
    public ResultadoDaGravacao gravar(UsuarioAutenticado usuario, ContextoDaGravacao contexto, List<LinhaAceita> linhas) {
        int inseridas = 0;
        List<Rejeicao> rejeicoes = new ArrayList<>();
        for (LinhaAceita linha : linhas) {
            var dados = linha.proposta().dados();
            try {
                BigDecimal valor = br.com.rbs.portal.importacao.extracao.MapeadorTabular.decimal(String.valueOf(dados.get("valor")));
                posVenda.informarResultado(usuario, contexto.eventoId(), new InformarResultadoRequisicao(
                        texto(dados.get("indicador")), valor, texto(dados.get("qualificador")), texto(dados.get("unidade")),
                        texto(dados.get("fonte"))), null);
                inseridas++;
            } catch (RuntimeException recusado) {
                rejeicoes.add(new Rejeicao(linha.ordem(), recusado.getMessage()));
            }
        }
        return new ResultadoDaGravacao(null, linhas.size(), inseridas, 0, rejeicoes.size(), rejeicoes);
    }

    private static String texto(Object valor) {
        return valor == null ? null : String.valueOf(valor);
    }
}
