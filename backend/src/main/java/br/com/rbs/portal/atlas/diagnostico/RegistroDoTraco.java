package br.com.rbs.portal.atlas.diagnostico;

import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.ModoDoAtlas;
import br.com.rbs.portal.atlas.diagnostico.Traco.Etapa;
import br.com.rbs.portal.atlas.diagnostico.TracoRepositorio.EtapaNova;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.UsoDoLlm;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Abre o {@link Traco} de cada turno e o grava no fim (plano do Atlas completo, M2). [ATLAS] Duas regras que não mudam:
 * <ul>
 *   <li><b>Nenhuma conexão fica presa durante a chamada a um provedor.</b> O traço vive na memória do turno; aqui só há duas
 *       gravações, cada uma numa transação curta própria: tudo de uma vez no fim do turno (e no {@code finally}, se der erro) e,
 *       se o resumo do raciocínio terminar depois, a atualização da linha dele.</li>
 *   <li><b>Nada sensível chega ao banco.</b> Quem monta {@code dados} não passa o raciocínio cru, a chave nem o prompt de sistema;
 *       e, por garantia, cada texto é limpo antes de gravar: corta em {@value #LIMITE_DO_TEXTO} caracteres, tira o caractere nulo
 *       (que o PostgreSQL não guarda), apaga as chaves de provedor que a API conhece e o que parece uma chave ({@code sk-...},
 *       {@code Bearer ...}).</li>
 * </ul>
 * Com {@code atlas.diagnostico.ligado=false} (o padrão) entrega sempre o {@link Traco#NENHUM}.
 * <p>
 * <b>Custo.</b> O custo de uma ida é o {@code usage.opencode.list_cost} que o OpenCode Go devolve em cada resposta (somado pelo
 * diagnóstico). O Go é uma assinatura por limite de uso (US$ 80 por mês), não cobra por token, e o preço dele não consta em
 * {@code docs/atlas/referencias/}; por isso os dois preços de {@code application.yml} ({@code atlas.diagnostico.preco-por-milhao},
 * os de lista do mesmo modelo no OpenCode Zen, que o quadro do Atlas registra) só entram quando uma resposta não traz o
 * {@code list_cost}.
 */
@Component
public class RegistroDoTraco {

    /** O tamanho máximo de um texto em {@code dados} (plano, tabela da V10). */
    public static final int LIMITE_DO_TEXTO = 20_000;

    private static final Logger log = LoggerFactory.getLogger(RegistroDoTraco.class);
    /** O que parece chave de provedor ou cabeçalho de autorização, em qualquer texto do traço. */
    private static final Pattern PARECE_CHAVE = Pattern.compile("(?i)(sk-[A-Za-z0-9_-]{6,}|bearer\\s+[A-Za-z0-9._~+/=-]{6,})");
    private static final String OMITIDO = "[omitido]";
    private static final BigDecimal UM_MILHAO = BigDecimal.valueOf(1_000_000);
    /** Chaves de provedor que a API lê da configuração. */
    private static final List<String> PROPRIEDADES_DE_CHAVE = List.of("spring.ai.openai.api-key", "atlas.jev.chave",
            "atlas.embeddings.chave");

    private final TracoRepositorio repositorio;
    private final TransactionTemplate transacao;
    private final ModoDoAtlas modo;
    private final BigDecimal precoDeEntrada;
    private final BigDecimal precoDeSaida;
    private final List<String> segredos = new ArrayList<>();

    public RegistroDoTraco(TracoRepositorio repositorio, TransactionTemplate transacao, ModoDoAtlas modo, Environment ambiente,
                           @Value("${atlas.diagnostico.preco-por-milhao.entrada:0}") BigDecimal precoDeEntrada,
                           @Value("${atlas.diagnostico.preco-por-milhao.saida:0}") BigDecimal precoDeSaida) {
        this.repositorio = repositorio;
        this.transacao = transacao;
        this.modo = modo;
        this.precoDeEntrada = precoDeEntrada;
        this.precoDeSaida = precoDeSaida;
        for (String propriedade : PROPRIEDADES_DE_CHAVE) {
            String chave = ambiente.getProperty(propriedade);
            if (chave != null && chave.strip().length() >= 8) {
                segredos.add(chave.strip());
            }
        }
    }

    /**
     * O traço de um turno novo desta conversa; o {@link Traco#NENHUM} com o diagnóstico desligado. {@code parceriaId} é nulo na
     * conversa sem parceria (o Atlas da RBS).
     */
    public Traco abrir(Long parceriaId, long conversaId) {
        return modo.diagnosticoLigado() ? new Traco(this, parceriaId, conversaId) : Traco.NENHUM;
    }

    // ---------------------------------------------------------------------
    // O que uma ida ao modelo gastou
    // ---------------------------------------------------------------------

    /**
     * Os detalhes de uma ida ao modelo (ou de um resumo) no traço: modelo, tokens, motivo do fim, latência e custo em dólares.
     * O custo é o {@code list_cost} que o provedor devolveu; sem ele, o preço por milhão de tokens configurado, se houver; senão
     * nenhum ({@code custoUsd} fica de fora).
     */
    Map<String, Object> dadosDoUso(UsoDoLlm uso, String modeloDoProvedor) {
        Map<String, Object> dados = new LinkedHashMap<>();
        String modelo = uso != null && uso.modelo() != null ? uso.modelo() : modeloDoProvedor;
        if (modelo != null) {
            dados.put("modelo", modelo);
        }
        if (uso == null) {
            return dados;
        }
        putSeNaoNulo(dados, "tokensEntrada", uso.tokensEntrada());
        putSeNaoNulo(dados, "tokensSaida", uso.tokensSaida());
        putSeNaoNulo(dados, "motivoDoFim", uso.motivoDoFim());
        putSeNaoNulo(dados, "latenciaMs", uso.latenciaMs());
        if (uso.custoDeLista() != null) {
            dados.put("custoUsd", uso.custoDeLista());
            dados.put("custoOrigem", "RESPOSTA");
        } else if (uso.tokensEntrada() != null && uso.tokensSaida() != null
                && (precoDeEntrada.signum() > 0 || precoDeSaida.signum() > 0)) {
            BigDecimal custo = precoDeEntrada.multiply(BigDecimal.valueOf(uso.tokensEntrada()))
                    .add(precoDeSaida.multiply(BigDecimal.valueOf(uso.tokensSaida())))
                    .divide(UM_MILHAO, 10, RoundingMode.HALF_UP).stripTrailingZeros();
            dados.put("custoUsd", custo);
            dados.put("custoOrigem", "PRECO_CONFIGURADO");
        }
        return dados;
    }

    private static void putSeNaoNulo(Map<String, Object> dados, String chave, Object valor) {
        if (valor != null) {
            dados.put(chave, valor);
        }
    }

    // ---------------------------------------------------------------------
    // Gravar
    // ---------------------------------------------------------------------

    /**
     * Grava as etapas do turno, na ordem dada (a posição vira {@code ordem}), numa transação curta. Quem chama (o
     * {@link Traco#encerrar}) segura a trava do traço. Guarda o id de cada linha, para o resumo pendente ser atualizado.
     */
    void gravar(Traco traco, List<Etapa> etapas) {
        transacao.executeWithoutResult(estado -> {
            int ordem = 1;
            for (Etapa etapa : etapas) {
                long id = repositorio.inserir(new EtapaNova(traco.parceriaId(), traco.conversaId(), traco.turno(), etapa.mensagemId(),
                        ordem++, etapa.tipo().name(), etapa.resultadoAGravar().name(),
                        OffsetDateTime.ofInstant(etapa.inicio(), ZoneOffset.UTC), etapa.duracaoAGravar(), json(etapa.dadosAGravar())));
                etapa.gravada(id);
            }
        });
    }

    /**
     * O resumo do raciocínio que terminou depois do fim do turno: a linha dele passa de pendente ao resultado de verdade, numa
     * transação curta. Falha é só log.
     */
    void atualizar(long id, Etapa etapa) {
        try {
            transacao.executeWithoutResult(estado -> repositorio.atualizar(id, etapa.resultadoAGravar().name(), etapa.duracaoAGravar(),
                    json(etapa.dadosAGravar())));
        } catch (RuntimeException falha) {
            log.warn("Atlas: a etapa {} do traço não pôde ser atualizada ({})", etapa.tipo(), falha.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------------
    // Limpeza
    // ---------------------------------------------------------------------

    private String json(Map<String, Object> dados) {
        return AtlasJson.escrever(limpar(dados));
    }

    /** Os textos do valor, limpos (mapas e listas por dentro); número, booleano e nulo passam como estão. */
    Object limpar(Object valor) {
        if (valor instanceof String texto) {
            return limparTexto(texto);
        }
        if (valor instanceof Map<?, ?> mapa) {
            Map<String, Object> limpo = new LinkedHashMap<>();
            mapa.forEach((chave, filho) -> {
                if (filho != null) {
                    limpo.put(String.valueOf(chave), limpar(filho));
                }
            });
            return limpo;
        }
        if (valor instanceof Iterable<?> itens) {
            List<Object> limpa = new ArrayList<>();
            itens.forEach(item -> limpa.add(limpar(item)));
            return limpa;
        }
        return valor;
    }

    /** O texto sem caractere nulo, sem as chaves de provedor que a API conhece, sem o que parece chave e com no máximo 20.000 caracteres. */
    String limparTexto(String texto) {
        String limpo = texto.replace("\u0000", "");
        for (String segredo : segredos) {
            limpo = limpo.replace(segredo, OMITIDO);
        }
        limpo = PARECE_CHAVE.matcher(limpo).replaceAll(OMITIDO);
        if (limpo.length() > LIMITE_DO_TEXTO) {
            int fim = Character.isHighSurrogate(limpo.charAt(LIMITE_DO_TEXTO - 1)) ? LIMITE_DO_TEXTO - 1 : LIMITE_DO_TEXTO;
            limpo = limpo.substring(0, fim);
        }
        return limpo;
    }
}
