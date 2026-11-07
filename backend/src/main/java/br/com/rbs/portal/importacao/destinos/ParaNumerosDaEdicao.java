package br.com.rbs.portal.importacao.destinos;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.importacao.AlcanceDeUmaImportacao;
import br.com.rbs.portal.importacao.DestinoDeGravacao;
import br.com.rbs.portal.importacao.DestinoDaImportacao;
import br.com.rbs.portal.importacao.LinhaProposta;
import br.com.rbs.portal.importacao.extracao.MapeadorTabular;
import br.com.rbs.portal.relatorio.PosVendaDtos.InformarResultadoRequisicao;
import br.com.rbs.portal.relatorio.PosVendaService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O destino NUMEROS_EDICAO: os números do "festival em números" (evento_resultado), pelo mesmo serviço das rotas 100 e 101. A
 * importação só cria os números; o relatório gerado depois é que os lê. A prévia repete as regras do serviço (campos
 * obrigatórios, tamanho, qualificador, valor maior que zero, máximo e duas casas), e cada linha confirmada roda num savepoint:
 * uma linha recusada não derruba as outras.
 */
@Component
public class ParaNumerosDaEdicao implements DestinoDeGravacao {

    private static final int INDICADOR_MAXIMO = 80;
    private static final int UNIDADE_MAXIMA = 20;
    private static final int FONTE_MAXIMA = 200;
    private static final BigDecimal LIMITE = new BigDecimal("100000000000000");
    private static final List<String> QUALIFICADORES = List.of("EXATO", "MAIS_DE");

    private final PosVendaService posVenda;
    private final TransactionTemplate savepoint;

    public ParaNumerosDaEdicao(PosVendaService posVenda, PlatformTransactionManager gerenteDeTransacao) {
        this.posVenda = posVenda;
        this.savepoint = new TransactionTemplate(gerenteDeTransacao);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @Override
    public DestinoDaImportacao destino() {
        return DestinoDaImportacao.NUMEROS_EDICAO;
    }

    @Override
    public List<String> problemas(LinhaProposta linha, AlcanceDeUmaImportacao alcance) {
        List<String> problemas = new ArrayList<>();
        exigir(linha, "indicador", INDICADOR_MAXIMO, problemas);
        exigir(linha, "unidade", UNIDADE_MAXIMA, problemas);
        exigir(linha, "fonte", FONTE_MAXIMA, problemas);
        String qualificador = texto(linha.dados().get("qualificador"));
        if (qualificador != null && !qualificador.isBlank()
                && !QUALIFICADORES.contains(qualificador.strip().toUpperCase(Locale.ROOT))) {
            problemas.add("Use EXATO ou MAIS_DE.");
        }
        Object valor = linha.dados().get("valor");
        BigDecimal numero = valor == null ? null : MapeadorTabular.decimal(String.valueOf(valor));
        if (numero == null || numero.signum() <= 0) {
            problemas.add("O valor do número precisa ser maior que zero.");
        } else if (numero.compareTo(LIMITE) >= 0) {
            problemas.add("O valor passa do máximo: use menos de 100.000.000.000.000.");
        } else if (numero.stripTrailingZeros().scale() > 2) {
            problemas.add("O valor tem no máximo 2 casas decimais.");
        }
        return problemas;
    }

    private static void exigir(LinhaProposta linha, String campo, int maximo, List<String> problemas) {
        Object valor = linha.dados().get(campo);
        if (valor == null || String.valueOf(valor).isBlank()) {
            problemas.add("Falta " + campo + " do número.");
        } else if (String.valueOf(valor).strip().length() > maximo) {
            problemas.add("Use no máximo " + maximo + " caracteres em " + campo + ".");
        }
    }

    @Override
    public ResultadoDaGravacao gravar(UsuarioAutenticado usuario, ContextoDaGravacao contexto, List<LinhaAceita> linhas) {
        int inseridas = 0;
        List<Rejeicao> rejeicoes = new ArrayList<>();
        for (LinhaAceita linha : linhas) {
            List<String> problemas = problemas(linha.proposta(), contexto.alcance());
            if (!problemas.isEmpty()) {
                rejeicoes.add(new Rejeicao(linha.ordem(), problemas.getFirst()));
                continue;
            }
            var dados = linha.proposta().dados();
            try {
                BigDecimal valor = MapeadorTabular.decimal(String.valueOf(dados.get("valor")));
                savepoint.executeWithoutResult(estado -> posVenda.informarResultado(usuario, contexto.eventoId(),
                        new InformarResultadoRequisicao(texto(dados.get("indicador")), valor, texto(dados.get("qualificador")),
                                texto(dados.get("unidade")), texto(dados.get("fonte"))), null));
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
