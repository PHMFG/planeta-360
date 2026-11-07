package br.com.rbs.portal.importacao.destinos;

import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.SemPermissaoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.dados.DadoInformadoRepositorio.LinhaDeCampo;
import br.com.rbs.portal.dados.DadoInformadoService;
import br.com.rbs.portal.dados.DadosDtos.ValorRequisicao;
import br.com.rbs.portal.dados.OrigemDoDado;
import br.com.rbs.portal.importacao.AlcanceDeUmaImportacao;
import br.com.rbs.portal.importacao.CamposDaAtivacao;
import br.com.rbs.portal.importacao.DestinoDeGravacao;
import br.com.rbs.portal.importacao.DestinoDaImportacao;
import br.com.rbs.portal.importacao.LinhaProposta;
import br.com.rbs.portal.importacao.extracao.MapeadorTabular;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O destino DADOS_ATIVACAO: os números que a agência conta (visitantes, cadastros, brindes) viram {@code dado_informado}.
 * [CARGA] Grava pelo mesmo serviço das rotas 40 e 41, com quem confirmou como autor e a origem ATLAS; o valor anterior fica
 * no histórico. Só vale numa importação de uma parceria (o alcance PARCERIA). Campo fora do catálogo, campo de arquivo, valor
 * que não é número, tipo, mínimo, máximo, teto e relação entre campos (cadastros ≤ visitantes, brindes distribuídos ≤ disponíveis)
 * recusam a linha já na prévia, com a mesma frase do serviço. Na confirmação, cada linha roda num savepoint: uma linha que o
 * serviço ainda recuse não derruba as outras nem a importação.
 */
@Component
public class ParaDadosDaAtivacao implements DestinoDeGravacao {

    /** O mesmo teto de dado_informado.valor_numero, NUMERIC(14,2), do DadoInformadoService. */
    private static final BigDecimal TETO = new BigDecimal("999999999999.99");
    private static final DecimalFormatSymbols SIMBOLOS_BR = simbolosBr();

    private final DadoInformadoService dadoInformado;
    private final CamposDaAtivacao campos;
    private final TransactionTemplate savepoint;

    public ParaDadosDaAtivacao(DadoInformadoService dadoInformado, CamposDaAtivacao campos,
                               PlatformTransactionManager gerenteDeTransacao) {
        this.dadoInformado = dadoInformado;
        this.campos = campos;
        this.savepoint = new TransactionTemplate(gerenteDeTransacao);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @Override
    public DestinoDaImportacao destino() {
        return DestinoDaImportacao.DADOS_ATIVACAO;
    }

    @Override
    public List<String> problemas(LinhaProposta linha, AlcanceDeUmaImportacao alcance) {
        List<String> problemas = new ArrayList<>();
        if (alcance.parceriaId() == null) {
            problemas.add("Os dados da ativação são de uma importação de parceria.");
            return problemas;
        }
        LinhaDeCampo campo = campo(linha, problemas);
        if (campo == null) {
            return problemas;
        }
        if ("ARQUIVO".equals(campo.tipo())) {
            problemas.add("O campo " + campo.rotulo() + " recebe um arquivo e não um número.");
            return problemas;
        }
        BigDecimal numero = numero(linha);
        if (numero == null) {
            if (texto(linha.dados().get("valor")) == null || texto(linha.dados().get("valor")).isBlank()) {
                problemas.add("Falta o valor da linha.");
            } else {
                problemas.add("O valor da linha precisa ser um número.");
            }
            return problemas;
        }
        Map<String, LinhaDeCampo> catalogo = new LinkedHashMap<>();
        for (LinhaDeCampo cada : campos.campos()) {
            catalogo.put(cada.chave(), cada);
        }
        validarValor(numero, campo, catalogo, campos.valoresVigentes(alcance.parceriaId()), problemas);
        return problemas;
    }

    /**
     * A relação entre campos quando os dois vêm na mesma importação (cadastros ≤ visitantes): o serviço confere o conjunto
     * inteiro numa chamada só, e a prévia precisa fazer o mesmo para não deixar passar uma linha que a confirmação recusaria.
     */
    @Override
    public Map<Integer, List<String>> problemasDoConjunto(List<LinhaProposta> linhas, AlcanceDeUmaImportacao alcance) {
        if (alcance.parceriaId() == null) {
            return Map.of();
        }
        Map<String, LinhaDeCampo> catalogo = new LinkedHashMap<>();
        for (LinhaDeCampo campo : campos.campos()) {
            catalogo.put(campo.chave(), campo);
        }
        Map<String, BigDecimal> propostos = new LinkedHashMap<>();
        List<LinhaDeCampo> resolvidos = new ArrayList<>();
        for (LinhaProposta linha : linhas) {
            LinhaDeCampo campo = campos.resolver(texto(linha.dados().get("campo_chave"))).orElse(null);
            resolvidos.add(campo);
            if (campo != null && !"ARQUIVO".equals(campo.tipo())) {
                BigDecimal numero = numero(linha);
                if (numero != null) {
                    propostos.put(campo.chave(), numero);
                }
            }
        }
        Map<String, BigDecimal> vigentes = campos.valoresVigentes(alcance.parceriaId());
        Map<Integer, List<String>> problemas = new LinkedHashMap<>();
        for (int i = 0; i < linhas.size(); i++) {
            LinhaDeCampo campo = resolvidos.get(i);
            BigDecimal numero = campo == null ? null : numero(linhas.get(i));
            if (campo == null || numero == null) {
                continue;
            }
            List<String> daLinha = problemas.computeIfAbsent(i, k -> new ArrayList<>());
            if (campo.limiteCampo() != null) {
                BigDecimal teto = propostos.containsKey(campo.limiteCampo()) ? propostos.get(campo.limiteCampo())
                        : vigentes.get(campo.limiteCampo());
                LinhaDeCampo limite = catalogo.get(campo.limiteCampo());
                if (teto != null && numero.compareTo(teto) > 0) {
                    daLinha.add("Não pode passar de " + limite.rotulo() + " (" + formatar(teto) + ").");
                }
            }
            for (LinhaDeCampo dependente : catalogo.values()) {
                if (!campo.chave().equals(dependente.limiteCampo()) || propostos.containsKey(dependente.chave())) {
                    continue;   // o dependente que veio no mesmo pedido é conferido na linha dele
                }
                BigDecimal atual = vigentes.get(dependente.chave());
                if (atual != null && atual.compareTo(numero) > 0) {
                    daLinha.add("Não pode ficar abaixo de " + dependente.rotulo() + " (" + formatar(atual) + ").");
                }
            }
        }
        return problemas;
    }

    @Override
    public ResultadoDaGravacao gravar(UsuarioAutenticado usuario, ContextoDaGravacao contexto, List<LinhaAceita> linhas) {
        Long parceriaId = contexto.alcance().parceriaId();
        if (parceriaId == null) {
            throw new DadosInvalidosException(Map.of("parceria",
                    "Os dados da ativação são de uma importação de parceria."));
        }
        int inseridas = 0;
        List<Rejeicao> rejeicoes = new ArrayList<>();
        for (LinhaAceita linha : linhas) {
            List<String> problemas = problemas(linha.proposta(), contexto.alcance());
            if (!problemas.isEmpty()) {
                rejeicoes.add(new Rejeicao(linha.ordem(), problemas.getFirst()));
                continue;
            }
            String referencia = texto(linha.proposta().dados().get("campo_chave"));
            Optional<LinhaDeCampo> campo = campos.resolver(referencia);
            if (campo.isEmpty()) {
                rejeicoes.add(new Rejeicao(linha.ordem(), "O campo " + referencia + " não está no catálogo da ativação."));
                continue;
            }
            BigDecimal valor = numero(linha.proposta());
            try {
                savepoint.executeWithoutResult(estado -> dadoInformado.gravar(usuario, parceriaId,
                        List.of(new ValorRequisicao(campo.get().chave(), valor)), OrigemDoDado.ATLAS, null));
                inseridas++;
            } catch (SemPermissaoException | NaoEncontradoException bloqueio) {
                // Aba fechada ou parceria que sumiu não é uma linha recusada: a resposta inteira é 403 ou 404.
                throw bloqueio;
            } catch (RuntimeException recusado) {
                rejeicoes.add(new Rejeicao(linha.ordem(), motivo(recusado)));
            }
        }
        return new ResultadoDaGravacao(null, linhas.size(), inseridas, 0, rejeicoes.size(), rejeicoes);
    }

    private LinhaDeCampo campo(LinhaProposta linha, List<String> problemas) {
        String referencia = texto(linha.dados().get("campo_chave"));
        if (referencia == null || referencia.isBlank()) {
            problemas.add("Falta o campo da linha.");
            return null;
        }
        Optional<LinhaDeCampo> campo = campos.resolver(referencia);
        if (campo.isEmpty()) {
            problemas.add("O campo " + referencia + " não está no catálogo da ativação.");
            return null;
        }
        return campo.get();
    }

    private static BigDecimal numero(LinhaProposta linha) {
        Object valor = linha.dados().get("valor");
        return valor == null ? null : MapeadorTabular.decimal(texto(valor));
    }

    private static void validarValor(BigDecimal numero, LinhaDeCampo campo, Map<String, LinhaDeCampo> catalogo,
                                     Map<String, BigDecimal> vigentes, List<String> problemas) {
        boolean inteiro = "INTEIRO".equals(campo.tipo());
        if (inteiro && numero.stripTrailingZeros().scale() > 0) {
            problemas.add("Informe um número inteiro.");
        } else if (!inteiro && numero.stripTrailingZeros().scale() > 2) {
            problemas.add("Use no máximo 2 casas decimais.");
        } else if (numero.abs().compareTo(TETO) > 0) {
            problemas.add("O valor é grande demais.");
        } else if (campo.minimo() != null && numero.compareTo(campo.minimo()) < 0) {
            problemas.add("O valor mínimo é " + formatar(campo.minimo()) + ".");
        } else if (campo.maximo() != null && numero.compareTo(campo.maximo()) > 0) {
            problemas.add("O valor máximo é " + formatar(campo.maximo()) + ".");
        } else {
            if (campo.limiteCampo() != null) {
                BigDecimal teto = vigentes.get(campo.limiteCampo());
                LinhaDeCampo limite = catalogo.get(campo.limiteCampo());
                if (teto != null && numero.compareTo(teto) > 0) {
                    problemas.add("Não pode passar de " + (limite == null ? campo.limiteCampo() : limite.rotulo())
                            + " (" + formatar(teto) + ").");
                }
            }
            for (LinhaDeCampo dependente : catalogo.values()) {
                if (!campo.chave().equals(dependente.limiteCampo())) {
                    continue;
                }
                BigDecimal atual = vigentes.get(dependente.chave());
                if (atual != null && atual.compareTo(numero) > 0) {
                    problemas.add("Não pode ficar abaixo de " + dependente.rotulo() + " (" + formatar(atual) + ").");
                }
            }
        }
    }

    private static String motivo(RuntimeException recusado) {
        if (recusado instanceof DadosInvalidosException invalido && !invalido.campos().isEmpty()) {
            return invalido.campos().values().iterator().next();
        }
        String mensagem = recusado.getMessage();
        return mensagem == null || mensagem.isBlank() ? "Não consegui gravar este valor." : mensagem;
    }

    private static String formatar(BigDecimal valor) {
        return new DecimalFormat("#,##0.##", SIMBOLOS_BR).format(valor);
    }

    private static DecimalFormatSymbols simbolosBr() {
        DecimalFormatSymbols simbolos = DecimalFormatSymbols.getInstance(Locale.ROOT);
        simbolos.setGroupingSeparator('.');
        simbolos.setDecimalSeparator(',');
        return simbolos;
    }

    private static String texto(Object valor) {
        return valor == null ? null : String.valueOf(valor);
    }
}
