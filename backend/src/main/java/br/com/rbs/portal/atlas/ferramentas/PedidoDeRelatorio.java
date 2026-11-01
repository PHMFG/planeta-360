package br.com.rbs.portal.atlas.ferramentas;

import static br.com.rbs.portal.atlas.ferramentas.Argumentos.desconhecidos;
import static br.com.rbs.portal.atlas.ferramentas.Argumentos.exigirSemErros;

import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.DefinicaoDeFerramenta;
import br.com.rbs.portal.relatorio.RelatorioService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@code pedido_de_relatorio}: a resposta ao pedido de "o relatório" (plano do Atlas completo, M5). [ATLAS] <b>Resposta direta</b>:
 * o relatório oficial é feito e validado pela RBS, não pelo Atlas, e o servidor responde com um texto fixo, sem nova ida ao modelo
 * de linguagem. Sem argumento (qualquer um, inclusive {@code parceria_id}, é argumento desconhecido e volta como erro).
 * <ul>
 *   <li>{@code relatorioOficial}: verdadeiro se a parceria da conversa tem uma versão que esta pessoa pode ver pela rota 35 (o
 *       {@link RelatorioService} decide, com o mesmo critério: o patrocinador só vê a disponibilizada). É só um booleano: o front leva
 *       à página certa, e nenhum número do relatório passa por aqui.</li>
 *   <li>{@code texto}: fixo, sem algarismo, em dois jeitos ({@link #texto}): o relatório mais recente está disponível, ou ele aparece
 *       no portal quando a RBS o disponibilizar. Os dois oferecem os modelos em branco.</li>
 *   <li>{@code modelos}: os modelos em branco que esta parceria pode pedir ({@link ModelosEmBranco#oferecidos}); o de perguntas da
 *       pesquisa só vai para quem tem a aba Pesquisa aberta.</li>
 * </ul>
 * A parceria vem do {@link AtlasContexto}. Devolve {@code {tipo: "pedido_relatorio", texto, relatorioOficial, modelos}}; a conversa o
 * relê como o item {@code pedido_relatorio}, com {@code relatorioOficial} e {@code modelos} de agora (o relatório pode ter sido
 * disponibilizado depois).
 */
@Component
public class PedidoDeRelatorio implements Ferramenta {

    public static final String NOME = "pedido_de_relatorio";
    /** O {@code tipo} do resultado (e do item da conversa). */
    public static final String TIPO_DO_RESULTADO = "pedido_relatorio";

    private static final String QUEM_FAZ = "O relatório da parceria é elaborado e validado pela equipe da RBS, não pelo Atlas.";
    private static final String COM_RELATORIO = QUEM_FAZ + " O mais recente já está disponível no portal. Se quiser, também tenho "
            + "modelos em branco para você preencher por conta própria.";
    private static final String SEM_RELATORIO = QUEM_FAZ + " Ele aparece no portal assim que a RBS o disponibilizar. Enquanto isso, "
            + "tenho modelos em branco para você preencher por conta própria.";

    private static final DefinicaoDeFerramenta DEFINICAO = new DefinicaoDeFerramenta(NOME,
            "Responde ao pedido de relatório da parceria. O relatório oficial é elaborado e validado pela equipe da RBS, não pelo "
                    + "Atlas: esta ferramenta mostra à pessoa um texto fixo que diz isso, o acesso ao relatório oficial mais recente "
                    + "quando a RBS já o disponibilizou e a oferta de modelos em branco. Use quando a pessoa pedir o relatório, o "
                    + "relatório oficial, o relatório final ou os resultados da parceria em documento. Não recebe argumentos. O "
                    + "servidor mostra a resposta pronta e o turno termina ali: você não recebe o resultado de volta para comentar.",
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");

    private final RelatorioService relatorios;
    private final ModelosEmBranco modelos;

    public PedidoDeRelatorio(RelatorioService relatorios, ModelosEmBranco modelos) {
        this.relatorios = relatorios;
        this.modelos = modelos;
    }

    /** O texto fixo da resposta, conforme haja ou não relatório oficial para esta pessoa ver. */
    public static String texto(boolean relatorioOficial) {
        return relatorioOficial ? COM_RELATORIO : SEM_RELATORIO;
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
        return 50;
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
        desconhecidos(argumentos, Set.of(), erros);
        exigirSemErros(erros);
        boolean relatorioOficial = relatorios.temVersaoParaVer(contexto.usuario(), contexto.parceriaId());
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("tipo", TIPO_DO_RESULTADO);
        json.put("texto", texto(relatorioOficial));
        json.put("relatorioOficial", relatorioOficial);
        json.put("modelos", modelos.oferecidos(contexto.usuario(), contexto.parceriaId()));
        return Execucao.direta(AtlasJson.escrever(json));
    }
}
