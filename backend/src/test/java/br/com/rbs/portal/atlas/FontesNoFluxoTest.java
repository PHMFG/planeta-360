package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoFluxo.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.conversa;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.eventos;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.falarEmFluxo;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.fim;
import static br.com.rbs.portal.atlas.ApoioDoFluxo.nomes;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.ApoioDoFluxo.Evento;
import br.com.rbs.portal.atlas.loop.LoopDoAgente;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.core.type.TypeReference;

/**
 * [ATLAS] [VALIDACAO] As fontes na resposta em fluxo (SSE) da rota 44, no Atlas só chat (M4, critério bloqueante): o filtro segura a
 * frase inteira e só a manda à tela com a citação dela; a citação que chega em pedaços não deixa a data escapar antes; a frase com
 * data sem citação nunca chega à tela ({@code texto_recusado}); o "não encontrei" recebe a indicação do atendimento como um
 * {@code texto} a mais; a resposta sem apoio nos documentos é descartada pela tela e trocada pelo texto fixo. Em todo caso, o que a
 * tela soma dos {@code texto} depois do último {@code texto_recusado} é o texto que a conversa guardou, e as {@code fontes} chegam no
 * item do {@code fim}. Não é {@code @Transactional}: o turno em fluxo roda em outra thread (veja o ApoioDoFluxo).
 */
@TesteComLlmRoteirizado
@Import(EmbeddingsControlavelConfig.class)
@TestPropertySource(properties = {"atlas.relatorio.ligado=false", "atlas.debug.ferramentas=false"})
class FontesNoFluxoTest {

    private static final String BUSCAR = "buscar_documentos";
    private static final String CONSULTA = "prazo para informar os geradores próprios";
    private static final String PRAZO = "04/12/2026";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    EmbeddingsControlavel embeddings;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void guardarOQueJaExiste() {
        modelo.limpar();
        embeddings.limpar();
        limpeza = new ApoioDoFluxo.Limpeza(jdbc);
        limpeza.guardar();
    }

    @AfterEach
    void apagarOQueOTesteConfirmou() {
        limpeza.apagar();
    }

    static String busca(String consulta) {
        return AtlasJson.escrever(Map.of("consulta", consulta));
    }

    /**
     * O marcador que a busca desta consulta dá ao trecho com a data (e o que dá a um trecho sem ela). A busca é a mesma do turno em
     * fluxo, com o mesmo kit e os mesmos embeddings de teste: o resultado é o mesmo, e o marcador vem de uma conversa de teste só
     * para descobri-lo (apagada no fim, como o resto).
     */
    @SuppressWarnings("unchecked")
    String[] marcadores(SessaoDeApi pessoa) {
        long descoberta = conversa(pessoa, jdbc);
        modelo.chamada(BUSCAR, busca(CONSULTA)).texto("Veja o manual.");
        falarEmFluxo(pessoa, descoberta, "Até quando informo os geradores?");
        modelo.limpar();
        String json = jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'tool'").param("c", descoberta)
                .query(String.class).single();
        List<Map<String, Object>> trechos = (List<Map<String, Object>>) AtlasJson.ler(json, new TypeReference<Map<String, Object>>() {
        }).get("trechos");
        String com = trechos.stream().filter(t -> ((String) t.get("texto")).contains(PRAZO)).map(t -> (String) t.get("marcador")).findFirst()
                .orElseThrow();
        String sem = trechos.stream().filter(t -> !((String) t.get("texto")).contains(PRAZO)).map(t -> (String) t.get("marcador")).findFirst()
                .orElseThrow();
        return new String[] {com, sem};
    }

    /** O que a tela mostra no fim: a soma dos {@code texto} que vieram depois do último {@code texto_recusado}. */
    static String queATelaMostra(List<Evento> eventos) {
        int ultimaRecusa = nomes(eventos).lastIndexOf("texto_recusado");
        StringBuilder tela = new StringBuilder();
        for (Evento evento : eventos.subList(ultimaRecusa + 1, eventos.size())) {
            if (evento.nome().equals("texto")) {
                tela.append(evento.<String>ler("$.delta"));
            }
        }
        return tela.toString();
    }

    /** O último item {@code texto} do {@code fim}: o que a conversa guardou como resposta do turno. */
    static String textoDoFim(Evento fim) {
        List<String> textos = fim.ler("$.itens[?(@.tipo=='texto')].texto");
        assertThat(textos).isNotEmpty();
        return textos.get(textos.size() - 1);
    }

    @Test
    void aCitacaoQueChegaEmPedacosFicaNaMesmaFraseDaDataEAsFontesVemNoFim() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        String certo = marcadores(pessoa)[0];
        long conversa = conversa(pessoa, jdbc);
        modelo.chamada(BUSCAR, busca(CONSULTA)).textoEmPedacos(null, "Os geradores próprios devem ser informados até 04/12/", "2026 ", "[",
                certo.substring(0, 1), certo.substring(1), "]", ". Fora isso, ", "nada muda.");

        List<Evento> eventos = eventos(falarEmFluxo(pessoa, conversa, "Até quando informo os geradores?"));

        List<Evento> textos = eventos.stream().filter(e -> e.nome().equals("texto")).toList();
        assertThat(nomes(eventos)).doesNotContain("texto_recusado");
        // a data só sai com a citação dela, na mesma frase; o resto vem depois, em outro pedaço
        assertThat(textos.get(0).<String>ler("$.delta")).isEqualTo("Os geradores próprios devem ser informados até 04/12/2026 [" + certo + "].");
        assertThat(queATelaMostra(eventos)).isEqualTo("Os geradores próprios devem ser informados até 04/12/2026 [" + certo + "]. Fora isso, nada muda.");
        Evento fim = fim(eventos);
        assertThat(fim.<List<String>>ler("$.itens[*].tipo")).containsSubsequence("usuario", "texto");
        assertThat(textoDoFim(fim)).as("o que a conversa guarda é o que a tela mostrou").isEqualTo(queATelaMostra(eventos));
        List<Map<String, Object>> fontes = fim.ler("$.itens[?(@.tipo=='texto')].fontes[*]");
        assertThat(fontes).hasSize(1);
        assertThat(fontes.get(0)).containsEntry("marcador", certo).containsEntry("tipo", "TRECHO")
                .containsEntry("titulo", "Manual de Operações 2027").containsEntry("versao", 2)
                .containsEntry("substituido", false).containsEntry("revogado", false)
                .containsKeys("documentoId", "trechoId", "pagina", "secao");
    }

    @Test
    void aDataSemCitacaoNuncaChegaATelaEANovaRedacaoSegue() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        String certo = marcadores(pessoa)[0];
        long conversa = conversa(pessoa, jdbc);
        modelo.chamada(BUSCAR, busca(CONSULTA))
                .textoEmPedacos(null, "Os geradores próprios devem ser informados até ", "04/12/", "2026.")
                .texto("Os geradores próprios devem ser informados até 04/12/2026 [" + certo + "].");

        List<Evento> eventos = eventos(falarEmFluxo(pessoa, conversa, "Até quando informo os geradores?"));

        int recusa = nomes(eventos).indexOf("texto_recusado");
        assertThat(recusa).isGreaterThanOrEqualTo(0);
        // nada com a data saiu antes da recusa; depois dela vem a frase refeita, com a citação
        for (Evento antes : eventos.subList(0, recusa)) {
            assertThat(antes.dados()).doesNotContain("04/12");
        }
        assertThat(queATelaMostra(eventos)).isEqualTo("Os geradores próprios devem ser informados até 04/12/2026 [" + certo + "].");
        assertThat(fim(eventos).<List<Map<String, Object>>>ler("$.itens[?(@.tipo=='texto')].fontes[*]")).hasSize(1);
    }

    @Test
    void citacaoDeUmTrechoSemOValorTambemNaoChegaATela() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        String[] marcadores = marcadores(pessoa);
        long conversa = conversa(pessoa, jdbc);
        modelo.chamada(BUSCAR, busca(CONSULTA))
                .textoEmPedacos(null, "O prazo é 04/12/2026 [", marcadores[1], "].")
                .texto("O prazo é 04/12/2026 [" + marcadores[0] + "].");

        List<Evento> eventos = eventos(falarEmFluxo(pessoa, conversa, "Prazo?"));

        int recusa = nomes(eventos).indexOf("texto_recusado");
        assertThat(recusa).isGreaterThanOrEqualTo(0);
        eventos.subList(0, recusa).forEach(e -> assertThat(e.dados()).doesNotContain("04/12"));
        assertThat(queATelaMostra(eventos)).isEqualTo("O prazo é 04/12/2026 [" + marcadores[0] + "].");
    }

    @Test
    void oNaoEncontreiGanhaAIndicacaoDoAtendimentoComoUmTextoAMais() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        modelo.chamada(BUSCAR, busca("estacionar helicóptero")).textoEmPedacos(null, "Não encontrei isso ", "nos documentos ", "do portal.");

        List<Evento> eventos = eventos(falarEmFluxo(pessoa, conversa, "Posso pousar um helicóptero?"));

        List<Evento> textos = eventos.stream().filter(e -> e.nome().equals("texto")).toList();
        assertThat(nomes(eventos)).doesNotContain("texto_recusado");
        assertThat(textos).hasSize(2);
        assertThat(textos.get(0).<String>ler("$.delta")).isEqualTo("Não encontrei isso nos documentos do portal.");
        assertThat(textos.get(1).<String>ler("$.delta")).isEqualTo(" Para essa dúvida, fale com o atendimento da RBS.");
        assertThat(queATelaMostra(eventos)).isEqualTo(LoopDoAgente.TEXTO_NAO_ENCONTREI);
        assertThat(textoDoFim(fim(eventos))).isEqualTo(LoopDoAgente.TEXTO_NAO_ENCONTREI);
    }

    @Test
    void respostaSemApoioNosDocumentosEDescartadaPelaTelaETrocadaPeloTextoFixo() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        // só palavras vazias: a busca volta sem trecho; o modelo responde por conta própria, sem número e sem citação
        modelo.chamada(BUSCAR, busca("de a o que para")).textoEmPedacos(null, "O prazo costuma ser ", "no fim do ano.");

        List<Evento> eventos = eventos(falarEmFluxo(pessoa, conversa, "Qual o prazo?"));

        // a frase saiu na tela (não tinha número), o servidor viu que ela não tem de onde sair e mandou descartá-la
        assertThat(nomes(eventos)).containsSubsequence("texto", "texto_recusado", "texto", "fim");
        assertThat(queATelaMostra(eventos)).isEqualTo(LoopDoAgente.TEXTO_NAO_ENCONTREI);
        assertThat(textoDoFim(fim(eventos))).isEqualTo(LoopDoAgente.TEXTO_NAO_ENCONTREI);
        List<Boolean> porModelo = fim(eventos).ler("$.itens[?(@.tipo=='texto')].porModelo");
        assertThat(porModelo.get(porModelo.size() - 1)).isFalse();
    }

    @Test
    void todaBuscaComErroTrocaARespostaPeloTextoFixoDeQueNaoDeuParaConsultar() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        embeddings.falharAsProximasBuscas(1);
        // a busca cai; o modelo diz que não consegue buscar, sem número e sem citação
        modelo.chamada(BUSCAR, busca(CONSULTA)).textoEmPedacos(null, "Não consegui buscar ", "esse dado agora.");

        List<Evento> eventos = eventos(falarEmFluxo(pessoa, conversa, "Qual o prazo?"));

        // o texto do modelo foi à tela (não tinha número); o servidor mandou descartá-lo e trocou pelo texto fixo da busca que falhou
        assertThat(nomes(eventos)).containsSubsequence("texto", "texto_recusado", "texto", "fim");
        assertThat(queATelaMostra(eventos)).isEqualTo(LoopDoAgente.FRASE_SEM_CONSULTA)
                .isEqualTo("Não consegui consultar os documentos agora. Tente de novo em instantes.");
        assertThat(textoDoFim(fim(eventos))).isEqualTo(LoopDoAgente.FRASE_SEM_CONSULTA);
        List<Boolean> porModelo = fim(eventos).ler("$.itens[?(@.tipo=='texto')].porModelo");
        assertThat(porModelo.get(porModelo.size() - 1)).isFalse();
    }

    @Test
    void semBuscarONumeroDeDocumentoNaoTemFonteEAFraseFixaDeSempreSai() {
        SessaoDeApi pessoa = SessaoDeApi.entrar(mvc, AMARELO);
        long conversa = conversa(pessoa, jdbc);
        modelo.texto("O prazo é 04/12/2026 [T1].").texto("O prazo é 04/12/2026 [T1].");

        List<Evento> eventos = eventos(falarEmFluxo(pessoa, conversa, "Prazo?"));

        // o turno não buscou: não há [T1] neste turno, e duas recusas seguidas viram a frase fixa de sempre, não a de "não encontrei"
        assertThat(queATelaMostra(eventos)).isEqualTo(LoopDoAgente.FRASE_FIXA_NO_CHAT).doesNotContain(PRAZO);
        assertThat(nomes(eventos).stream().filter("texto_recusado"::equals).count()).isGreaterThanOrEqualTo(1);
    }
}
