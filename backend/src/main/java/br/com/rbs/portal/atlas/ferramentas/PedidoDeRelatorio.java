package br.com.rbs.portal.atlas.ferramentas;

import static br.com.rbs.portal.atlas.ferramentas.Argumentos.desconhecidos;
import static br.com.rbs.portal.atlas.ferramentas.Argumentos.exigirSemErros;

import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.ModoDoAtlas;
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
 *       no portal quando a RBS o disponibilizar. Os dois oferecem os modelos em branco. <b>Com o relatório ligado</b>
 *       ({@code atlas.relatorio.ligado}), os dois dizem também que o relatório da ativação no evento o Atlas monta ali mesmo: sem isso,
 *       a pessoa que pediu "o relatório" ouvia "não pelo Atlas" e nunca sabia que o da ativação existe. E a descrição da ferramenta,
 *       que o modelo lê, passa a valer só para "qualquer relatório que não seja o da ativação"; com o relatório desligado, nada muda.</li>
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
    /** A frase a mais com o relatório ligado: sem algarismo, e o único lugar em que o texto fixo oferece o relatório da ativação. */
    static final String OFERTA_DA_ATIVACAO = " O relatório da ativação no evento eu monto aqui: é só pedir.";

    private static final DefinicaoDeFerramenta DEFINICAO_COM_RELATORIO = new DefinicaoDeFerramenta(NOME,
            "Responde ao pedido de relatório da parceria que não é o da ativação no evento: o relatório oficial é elaborado e "
                    + "validado pela equipe da RBS, não pelo Atlas. Esta ferramenta mostra à pessoa um texto fixo que diz isso, o "
                    + "acesso ao relatório oficial mais recente quando a RBS já o disponibilizou, a oferta de modelos em branco e a "
                    + "de montar aqui o relatório da ativação. Use quando a pessoa pedir qualquer relatório que não seja o da "
                    + "ativação (o relatório oficial, o final, o pós-evento, o de mídia, o consolidado ou os resultados da parceria "
                    + "em documento). O relatório da ativação no evento não passa por aqui: se não der para saber qual é, pergunte "
                    + "em uma frase antes de chamar. Não recebe argumentos. O servidor mostra a resposta pronta e o turno termina "
                    + "ali: você não recebe o resultado de volta para comentar.",
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");

    private static final DefinicaoDeFerramenta DEFINICAO = new DefinicaoDeFerramenta(NOME,
            "Responde ao pedido de relatório da parceria. O relatório oficial é elaborado e validado pela equipe da RBS, não pelo "
                    + "Atlas: esta ferramenta mostra à pessoa um texto fixo que diz isso, o acesso ao relatório oficial mais recente "
                    + "quando a RBS já o disponibilizou e a oferta de modelos em branco. Use quando a pessoa pedir o relatório, o "
                    + "relatório oficial, o relatório final ou os resultados da parceria em documento. Não recebe argumentos. O "
                    + "servidor mostra a resposta pronta e o turno termina ali: você não recebe o resultado de volta para comentar.",
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");

    private final RelatorioService relatorios;
    private final ModelosEmBranco modelos;
    private final ModoDoAtlas modo;

    public PedidoDeRelatorio(RelatorioService relatorios, ModelosEmBranco modelos, ModoDoAtlas modo) {
        this.relatorios = relatorios;
        this.modelos = modelos;
        this.modo = modo;
    }

    /**
     * O texto fixo da resposta, conforme haja ou não relatório oficial para esta pessoa ver e conforme o relatório do Atlas esteja
     * ligado: ligado, ele acrescenta a oferta de montar o relatório da ativação ({@link #OFERTA_DA_ATIVACAO}).
     */
    public static String texto(boolean relatorioOficial, boolean relatorioLigado) {
        return (relatorioOficial ? COM_RELATORIO : SEM_RELATORIO) + (relatorioLigado ? OFERTA_DA_ATIVACAO : "");
    }

    @Override
    public String nome() {
        return NOME;
    }

    @Override
    public DefinicaoDeFerramenta definicao() {
        return modo.relatorioLigado() ? DEFINICAO_COM_RELATORIO : DEFINICAO;
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
        json.put("texto", texto(relatorioOficial, modo.relatorioLigado()));
        json.put("relatorioOficial", relatorioOficial);
        json.put("modelos", modelos.oferecidos(contexto.usuario(), contexto.parceriaId()));
        return Execucao.direta(AtlasJson.escrever(json));
    }
}
