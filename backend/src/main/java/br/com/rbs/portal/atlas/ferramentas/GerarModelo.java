package br.com.rbs.portal.atlas.ferramentas;

import static br.com.rbs.portal.atlas.ferramentas.Argumentos.desconhecidos;
import static br.com.rbs.portal.atlas.ferramentas.Argumentos.exigirSemErros;
import static br.com.rbs.portal.atlas.ferramentas.Argumentos.texto;

import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.ferramentas.ModelosEmBranco.Modelo;
import br.com.rbs.portal.atlas.ferramentas.ModelosEmBranco.Tipo;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.DefinicaoDeFerramenta;
import br.com.rbs.portal.comum.SemPermissaoException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@code gerar_modelo(tipo)}: entrega à pessoa um modelo em branco para ela preencher (plano do Atlas completo, M5). [ATLAS]
 * <b>Resposta direta</b>: o servidor mostra o modelo (título, conteúdo e o nome do arquivo {@code .md} que o navegador gera) e o
 * turno termina ali, sem nova ida ao modelo de linguagem. O conteúdo vem de {@code resources/atlas/modelos-em-branco/*.txt}
 * ({@link ModelosEmBranco}), sem algarismo nem campo de valor, e o modelo não o reescreve.
 * <ul>
 *   <li>{@code tipo} é obrigatório: {@code BRIEFING_CONTEUDO}, {@code PERGUNTAS_PESQUISA} ou {@code PLANO_OPERACIONAL_ATIVACAO}.
 *       Outro valor volta ao modelo como erro, com os tipos válidos.</li>
 *   <li>[PERMISSAO] A Pesquisa é só da Master (decisão do projeto): {@code PERGUNTAS_PESQUISA} para quem não tem a aba Pesquisa aberta
 *       volta como erro, em palavras, para o modelo explicar à pessoa.</li>
 *   <li>[ISOLAMENTO] A parceria vem do {@link AtlasContexto}: {@code parceria_id} e qualquer outro argumento é argumento
 *       desconhecido e volta como erro.</li>
 * </ul>
 * Devolve {@code {tipo: "modelo", modelo, titulo, conteudo, nomeArquivo}}; a conversa o relê como o item {@code modelo}.
 */
@Component
public class GerarModelo implements Ferramenta {

    public static final String NOME = "gerar_modelo";
    /** O {@code tipo} do resultado (e do item da conversa). */
    public static final String TIPO_DO_RESULTADO = "modelo";
    static final String ERRO_PESQUISA_FECHADA = "O modelo de perguntas da pesquisa não está disponível para esta parceria: a aba Pesquisa "
            + "não está aberta para ela. Explique isso à pessoa e ofereça os outros modelos.";
    private static final Set<String> ARGUMENTOS = Set.of("tipo");

    private static final DefinicaoDeFerramenta DEFINICAO = new DefinicaoDeFerramenta(NOME,
            "Entrega à pessoa um modelo em branco de documento para ela preencher por conta própria: BRIEFING_CONTEUDO (briefing de "
                    + "conteúdo para a produção), PERGUNTAS_PESQUISA (perguntas personalizadas da pesquisa com o público; só a cota "
                    + "que tem a aba Pesquisa) ou PLANO_OPERACIONAL_ATIVACAO (plano operacional da ativação no festival). Use quando "
                    + "a pessoa pedir um modelo, um template ou um documento em branco. O servidor mostra o modelo pronto, com os "
                    + "botões de baixar e de copiar, e o turno termina ali: você não recebe o conteúdo de volta para comentar nem "
                    + "reescrever, então não prometa preencher o modelo por ela.",
            """
            {"type":"object","properties":{
              "tipo":{"type":"string","enum":["BRIEFING_CONTEUDO","PERGUNTAS_PESQUISA","PLANO_OPERACIONAL_ATIVACAO"],
                      "description":"O modelo pedido."}},
             "required":["tipo"],"additionalProperties":false}""");

    private final ModelosEmBranco modelos;

    public GerarModelo(ModelosEmBranco modelos) {
        this.modelos = modelos;
    }

    @Override
    public String nome() {
        return NOME;
    }

    @Override
    public DefinicaoDeFerramenta definicao() {
        return DEFINICAO;
    }

    @Override
    public int ordem() {
        return 60;
    }

    @Override
    public boolean oferecida(EstadoDaConversa estado) {
        return !estado.cartaoAberto();
    }

    @Override
    public boolean direta() {
        return true;
    }

    @Override
    public Execucao executar(AtlasContexto contexto, FontesDoTurno turno, Map<String, Object> argumentos, String chamadaId,
                             Object preparado) {
        Map<String, String> erros = new LinkedHashMap<>();
        desconhecidos(argumentos, ARGUMENTOS, erros);
        String pedido = texto(argumentos, "tipo", true, erros);
        List<Tipo> disponiveis = modelos.disponiveis(contexto.usuario(), contexto.parceriaId());
        // O tipo que é só da RBS não existe para a marca: o erro dela não o nomeia (nem pede a aba de uma Pesquisa que não é o caso).
        Tipo tipo = pedido == null ? null : Tipo.deNome(pedido).filter(t -> !t.soDaRbs()).orElse(null);
        if (pedido != null && tipo == null) {
            erros.put("tipo", "Tipo desconhecido.");
        }
        if (erros.containsKey("tipo")) {
            // todo erro de tipo diz quais valem para esta parceria: o modelo acerta na tentativa seguinte
            erros.put("tipo", erros.get("tipo") + " Use um destes: " + String.join(", ", disponiveis.stream().map(Tipo::name).toList()) + ".");
        }
        exigirSemErros(erros);
        if (!disponiveis.contains(tipo)) {
            throw new SemPermissaoException(ERRO_PESQUISA_FECHADA);
        }
        Modelo modelo = modelos.modelo(tipo);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("tipo", TIPO_DO_RESULTADO);
        json.put("modelo", tipo.name());
        json.put("titulo", modelo.titulo());
        json.put("conteudo", modelo.conteudo());
        json.put("nomeArquivo", modelo.nomeArquivo());
        return Execucao.direta(AtlasJson.escrever(json));
    }
}
