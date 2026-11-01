package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.primeira;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.todas;
import static br.com.rbs.portal.atlas.ApoioDoDiagnostico.tracoDaConversa;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.ApoioDoDiagnostico.Linha;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.FonteDoTexto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemTexto;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.loop.LoopDoAgente;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.MensagemDeChat;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.PedidoAoLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.core.type.TypeReference;

/**
 * [ATLAS] [VALIDACAO] O texto do chat e as fontes que ele cita, pelo loop inteiro (M4, critério bloqueante), com o modelo roteirizado
 * e o kit de documentos no banco de teste. A resposta que o modelo escreve é montada em cima do resultado da busca que ele acabou de
 * receber (o marcador do trecho certo e o de um trecho que não tem o número): é o que garante que o teste não depende da ordem do
 * ranking. Confere o que a pessoa lê (o texto com a citação e as {@code fontes} do item), o que o servidor guarda
 * ({@code atlas_fonte}), o que vai ao modelo na nova redação e o que o traço diz. Não é {@code @Transactional}: o traço vai ao banco
 * no fim do turno e o teste apaga o que confirmou.
 */
@TesteComLlmRoteirizado
@Import(EmbeddingsControlavelConfig.class)
@TestPropertySource(properties = {"atlas.diagnostico.ligado=true", "atlas.diagnostico.marca=true", "atlas.prazo-do-resumo=PT0.5S",
        "atlas.relatorio.ligado=false"})
class FontesNoChatTest {

    private static final String BUSCAR = "buscar_documentos";
    private static final String CONSULTA = "prazo para informar os geradores próprios";
    private static final String PRAZO = "04/12/2026";

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    EmbeddingsControlavel embeddings;

    @Autowired
    JdbcClient jdbc;

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

    long abrir() {
        return atlas.abrirConversa(usuario(jdbc, AMARELO), SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"), null, requisicao())
                .conversaId();
    }

    ConversaDto falar(long conversa, String texto) {
        return atlas.enviarMensagem(usuario(jdbc, AMARELO), conversa, new MensagemRequisicao(texto, null, null), requisicao());
    }

    static String busca(String consulta) {
        return AtlasJson.escrever(Map.of("consulta", consulta));
    }

    // ---------------------------------------------------------------------
    // O roteiro do modelo, em cima do que a busca devolveu
    // ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> trechosDaUltimaBusca(PedidoAoLlm pedido) {
        List<MensagemDeChat> mensagens = pedido.mensagens();
        for (int i = mensagens.size() - 1; i >= 0; i--) {
            if (mensagens.get(i).papel().equals("tool")) {
                Map<String, Object> resultado = AtlasJson.ler(mensagens.get(i).conteudo(), new TypeReference<Map<String, Object>>() {
                });
                return (List<Map<String, Object>>) resultado.get("trechos");
            }
        }
        throw new AssertionError("o pedido ao modelo não tem resultado de ferramenta");
    }

    /** O marcador do primeiro trecho da última busca que tem (ou, com {@code tem} falso, que não tem) este texto. */
    static String marcador(PedidoAoLlm pedido, String texto, boolean tem) {
        return trechosDaUltimaBusca(pedido).stream().filter(t -> ((String) t.get("texto")).contains(texto) == tem)
                .map(t -> (String) t.get("marcador")).findFirst()
                .orElseThrow(() -> new AssertionError("a busca não trouxe trecho " + (tem ? "com " : "sem ") + texto));
    }

    /** O modelo escreve o que o {@code redator} montar com o pedido na mão (para pôr o marcador que a busca de fato devolveu). */
    ProvedorRoteirizado escrever(Function<PedidoAoLlm, String> redator) {
        return modelo.passo(pedido -> new RespostaDoLlm(redator.apply(pedido), List.of(), null));
    }

    static String frasePrazo(String marcador) {
        return "Os geradores próprios devem ser informados à produção até " + PRAZO + (marcador == null ? "." : " [" + marcador + "].");
    }

    // ---------------------------------------------------------------------
    // O que o turno deixou
    // ---------------------------------------------------------------------

    static ItemTexto textoFinal(ConversaDto conversa) {
        List<ItemTexto> textos = conversa.itens().stream().filter(ItemTexto.class::isInstance).map(ItemTexto.class::cast).toList();
        assertThat(textos).as("o turno produziu um texto").isNotEmpty();
        return textos.get(textos.size() - 1);
    }

    List<Map<String, Object>> fontesGravadas(long conversa) {
        return jdbc.sql("""
                        SELECT f.marcador, f.tipo, f.trecho_id, f.documento_id, f.versao, f.pagina, f.mensagem_id
                        FROM atlas_fonte f JOIN atlas_mensagem m ON m.id = f.mensagem_id WHERE m.conversa_id = :c ORDER BY f.id
                        """).param("c", conversa).query().listOfRows();
    }

    /** O traço do último turno da conversa, na ordem gravada. */
    List<Linha> ultimoTurno(long conversa) {
        List<Linha> todas = tracoDaConversa(jdbc, conversa);
        java.util.UUID turno = todas.get(todas.size() - 1).turno();
        return new ArrayList<>(todas.stream().filter(l -> l.turno().equals(turno)).sorted(java.util.Comparator.comparingInt(Linha::ordem))
                .toList());
    }

    String textosDoAssistente(long conversa) {
        return String.join("\n", jdbc.sql("SELECT coalesce(conteudo, '') FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'assistant' ORDER BY ordem")
                .param("c", conversa).query(String.class).list());
    }

    // ---------------------------------------------------------------------
    // A citação certa
    // ---------------------------------------------------------------------

    @Test
    void numeroDeDocumentoComACitacaoDoTrechoQueOTemPassaEViraFonteDoItem() {
        long conversa = abrir();
        modelo.chamada(BUSCAR, busca(CONSULTA));
        escrever(pedido -> frasePrazo(marcador(pedido, PRAZO, true)));

        ItemTexto texto = textoFinal(falar(conversa, "Até quando informo os geradores?"));

        assertThat(texto.texto()).matches("Os geradores próprios devem ser informados à produção até 04/12/2026 \\[T[1-9]\\]\\.");
        assertThat(texto.porModelo()).isTrue();
        assertThat(texto.fontes()).hasSize(1);
        FonteDoTexto fonte = texto.fontes().get(0);
        assertThat(texto.texto()).contains("[" + fonte.marcador() + "]");
        assertThat(fonte.tipo()).isEqualTo("TRECHO");
        assertThat(fonte.titulo()).isEqualTo("Manual de Operações 2027");
        assertThat(fonte.versao()).isEqualTo(2);
        assertThat(fonte.pagina()).isGreaterThanOrEqualTo(1);
        assertThat(fonte.documentoId()).isNotNull();
        assertThat(fonte.trechoId()).isNotNull();
        assertThat(fonte.substituido()).isFalse();
        assertThat(fonte.revogado()).isFalse();
        // o servidor guarda a fonte ligada à mensagem do texto; o trecho guardado é o que de fato tem a data
        List<Map<String, Object>> gravadas = fontesGravadas(conversa);
        assertThat(gravadas).hasSize(1);
        assertThat(gravadas.get(0)).containsEntry("tipo", "TRECHO").containsEntry("marcador", fonte.marcador());
        assertThat(jdbc.sql("SELECT texto FROM documento_trecho WHERE id = :t").param("t", fonte.trechoId()).query(String.class).single())
                .contains(PRAZO);
        // reabrir a conversa traz as mesmas fontes
        assertThat(textoFinal(atlas.verConversa(usuario(jdbc, AMARELO), conversa)).fontes()).isEqualTo(texto.fontes());
        assertThat(primeira(ultimoTurno(conversa), "SAIDA").dados()).containsEntry("tipo", "TEXTO");
    }

    @Test
    void caractereInvisivelNaRespostaDoModeloNaoVaiParaATelaNemParaOHistorico() {
        long conversa = abrir();
        modelo.chamada(BUSCAR, busca(CONSULTA));
        escrever(pedido -> "Os geradores próprios devem ser informados à produção até 04/12/20\u200B26\u202E [" + marcador(pedido, PRAZO, true)
                + "].");

        ItemTexto texto = textoFinal(falar(conversa, "Até quando informo os geradores?"));

        // o que se conferiu é o que a pessoa lê e o que fica: sem o espaço de largura zero nem o override da escrita
        assertThat(texto.texto()).isEqualTo(frasePrazo(texto.fontes().get(0).marcador()));
        assertThat(texto.texto()).doesNotContain("\u200B").doesNotContain("\u202E");
        assertThat(textosDoAssistente(conversa)).doesNotContain("\u200B").doesNotContain("\u202E");
        assertThat(fontesGravadas(conversa)).hasSize(1);
    }

    @Test
    void fraseSemNumeroEmCitacaoNenhumaPassaSemFontes() {
        long conversa = abrir();
        modelo.chamada(BUSCAR, busca(CONSULTA)).texto("Os geradores próprios devem ser informados à produção antes do evento.");

        ItemTexto texto = textoFinal(falar(conversa, "Até quando informo os geradores?"));

        assertThat(texto.texto()).isEqualTo("Os geradores próprios devem ser informados à produção antes do evento.");
        assertThat(texto.fontes()).isEmpty();
        assertThat(fontesGravadas(conversa)).isEmpty();
    }

    @Test
    void duasCitacoesNaMesmaFraseViramDuasFontesNaOrdemDoTexto() {
        long conversa = abrir();
        modelo.chamada(BUSCAR, busca("tarifa de energia por kVA e prazo dos geradores"));
        escrever(pedido -> "A tarifa é de R$ 312,00 [" + marcador(pedido, "R$ 312,00", true) + "] e o prazo dos geradores é " + PRAZO + " ["
                + marcador(pedido, PRAZO, true) + "].");

        ItemTexto texto = textoFinal(falar(conversa, "Quanto é a energia e até quando informo os geradores?"));

        assertThat(texto.fontes()).isNotEmpty().hasSizeLessThanOrEqualTo(2);
        assertThat(texto.fontes()).extracting(FonteDoTexto::marcador).doesNotHaveDuplicates()
                .allSatisfy(m -> assertThat(texto.texto()).contains("[" + m + "]"));
        assertThat(fontesGravadas(conversa)).hasSameSizeAs(texto.fontes());
    }

    // ---------------------------------------------------------------------
    // A citação que não vale
    // ---------------------------------------------------------------------

    @Test
    void numeroSemCitacaoPedeNovaRedacaoComOMotivoEAProximaComCitacaoPassa() {
        long conversa = abrir();
        modelo.chamada(BUSCAR, busca(CONSULTA));
        escrever(pedido -> frasePrazo(null));
        escrever(pedido -> frasePrazo(marcador(pedido, PRAZO, true)));

        ItemTexto texto = textoFinal(falar(conversa, "Até quando informo os geradores?"));

        assertThat(texto.texto()).contains(PRAZO).matches(".*\\[T[1-9]\\]\\.");
        assertThat(texto.fontes()).hasSize(1);
        // o pedido de nova redação diz o motivo, manda copiar o número com a citação e dá a saída do "não encontrei"
        PedidoAoLlm segundaRedacao = modelo.ultimoPedido();
        MensagemDeChat pedidoDeRedacao = segundaRedacao.mensagens().get(segundaRedacao.mensagens().size() - 1);
        assertThat(pedidoDeRedacao.papel()).isEqualTo("user");
        assertThat(pedidoDeRedacao.conteudo()).contains("recusada").contains(PRAZO).contains("[T#]").contains("Não encontrei isso nos documentos do portal.")
                .doesNotContain("sem nenhum algarismo");
        // e diz o que fazer: corrigir só o apontado, e a frase de "não encontrei" é só para quando nenhum trecho responde
        assertThat(pedidoDeRedacao.conteudo()).contains("Corrija só o que foi apontado").contains("mantenha o que tem fonte")
                .contains("só se nenhum trecho responde").contains("não troque a resposta por essa frase");
        List<Linha> checagens = todas(ultimoTurno(conversa), "CHECAGEM");
        assertThat(checagens).extracting(Linha::resultado).containsExactly("RECUSADO", "OK");
        assertThat((String) checagens.get(0).dados().get("motivo")).contains(PRAZO).contains("não cita nenhuma fonte");
        // o texto recusado não fica na conversa
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'assistant' AND conteudo = :t")
                .param("c", conversa).param("t", frasePrazo(null)).query(Long.class).single()).isZero();
    }

    @Test
    void contaRecusadaPedeParaCorrigirSoOApontadoESemTrocarARespostaPorNaoEncontrei() {
        long conversa = abrir();
        modelo.chamada(BUSCAR, busca("tarifa de energia por kVA"));
        // a conta do modelo (3 x 312) é número sem origem; o trecho traz o valor unitário
        escrever(pedido -> "Para 3 geradores, o total é R$ 936,00 [" + marcador(pedido, "R$ 312,00", true) + "].");
        escrever(pedido -> "A tarifa é de R$ 312,00 [" + marcador(pedido, "R$ 312,00", true) + "]. O Atlas ainda não faz essa conta.");

        ItemTexto texto = textoFinal(falar(conversa, "Vou levar 3 geradores, quanto pago?"));

        PedidoAoLlm segundaRedacao = modelo.ultimoPedido();
        MensagemDeChat pedidoDeRedacao = segundaRedacao.mensagens().get(segundaRedacao.mensagens().size() - 1);
        assertThat(pedidoDeRedacao.papel()).isEqualTo("user");
        assertThat(pedidoDeRedacao.conteudo()).contains("936").contains("Corrija só o que foi apontado")
                .contains("mantenha o que tem fonte").contains("não troque a resposta por essa frase");
        // a frase de "não encontrei" é só a saída para quando nenhum trecho responde, tanto no motivo quanto no pedido
        assertThat(pedidoDeRedacao.conteudo()).contains("Só se nenhum trecho traz a resposta").contains("só se nenhum trecho responde");
        assertThat(texto.texto()).startsWith("A tarifa é de R$ 312,00 [T").contains("ainda não faz essa conta");
        assertThat(texto.fontes()).hasSize(1);
        assertThat(texto.texto()).doesNotContain("Não encontrei");
    }

    @Test
    void duasRecusasSeguidasDepoisDeBuscarViramOTextoFixoDeNaoEncontrei() {
        long conversa = abrir();
        modelo.chamada(BUSCAR, busca(CONSULTA));
        escrever(pedido -> frasePrazo(null));
        escrever(pedido -> "O prazo é " + PRAZO + " e vale para todas as marcas.");

        ItemTexto texto = textoFinal(falar(conversa, "Até quando informo os geradores?"));

        assertThat(texto.texto()).isEqualTo(LoopDoAgente.TEXTO_NAO_ENCONTREI)
                .startsWith("Não encontrei isso nos documentos do portal.").contains("atendimento da RBS");
        assertThat(texto.porModelo()).isFalse();
        assertThat(texto.fontes()).isEmpty();
        assertThat(textosDoAssistente(conversa)).doesNotContain(PRAZO);
        Linha saida = primeira(ultimoTurno(conversa), "SAIDA");
        assertThat(saida.resultado()).isEqualTo("RECUSADO");
        assertThat(saida.dados()).containsEntry("motivo", "TEXTO_RECUSADO");
    }

    @Test
    void duasRecusasComUmaContaProntaNaTelaViramAFraseGenericaENaoOnaoEncontrei() {
        long conversa = abrir();
        modelo.chamada(BUSCAR, busca(CONSULTA)).chamada("contar_dias", "{\"inicio\":\"2026-11-03\",\"dias\":18,\"uteis\":true}")
                .texto("Fica pronto em 01/12/2026 [C1].").texto("Fica pronto em 02/12/2026 [C1].");

        ConversaDto turno = falar(conversa, "Se o briefing sair em 03/11/2026, quando vencem 18 dias úteis?");

        // a contagem de verdade está na tela (item "calculo"); dizer "não encontrei" sobre ela seria o contrário do que ela mostra
        assertThat(textoFinal(turno).texto()).isEqualTo(LoopDoAgente.FRASE_FIXA_NO_CHAT);
        assertThat(turno.itens().stream().anyMatch(AtlasDtos.ItemCalculo.class::isInstance)).isTrue();
        Linha saida = primeira(ultimoTurno(conversa), "SAIDA");
        assertThat(saida.dados()).containsEntry("tipo", "FRASE_FIXA").containsEntry("motivo", "TEXTO_RECUSADO");
    }

    @Test
    void semContaNaTelaAsDuasRecusasDepoisDeBuscarContinuamNaoEncontrei() {
        long conversa = abrir();
        modelo.chamada(BUSCAR, busca(CONSULTA)).texto("O prazo é 05/12/2026.").texto("O prazo é 06/12/2026.");

        assertThat(textoFinal(falar(conversa, "Até quando informo os geradores?")).texto()).isEqualTo(LoopDoAgente.TEXTO_NAO_ENCONTREI);
    }

    @Test
    void buscaSemTrechoComUmaContaProntaNaoTrocaARespostaDaContaPeloNaoEncontrei() {
        long conversa = abrir();
        // só palavras vazias: a busca volta sem trecho; a contagem dá 30/11/2026 e a resposta o diz, sem citar a fonte
        modelo.chamada(BUSCAR, busca("de a o que para")).chamada("contar_dias", "{\"inicio\":\"2026-11-03\",\"dias\":18,\"uteis\":true}")
                .texto("O conteúdo fica pronto em 30/11/2026.");

        ItemTexto texto = textoFinal(falar(conversa, "Se o briefing sair em 03/11/2026, quando vencem 18 dias úteis?"));

        assertThat(texto.texto()).isEqualTo("O conteúdo fica pronto em 30/11/2026.");
        assertThat(texto.porModelo()).isTrue();
    }

    @Test
    void citacaoDeUmTrechoQueNaoTemONumeroNaoApoiaAFrase() {
        long conversa = abrir();
        modelo.chamada(BUSCAR, busca(CONSULTA));
        escrever(pedido -> frasePrazo(marcador(pedido, PRAZO, false)));
        escrever(pedido -> frasePrazo(marcador(pedido, PRAZO, true)));

        ItemTexto texto = textoFinal(falar(conversa, "Até quando informo os geradores?"));

        // a primeira redação cita um trecho que não traz a data: recusada; a segunda cita o trecho certo e passa
        List<Linha> checagens = todas(ultimoTurno(conversa), "CHECAGEM");
        assertThat(checagens).extracting(Linha::resultado).containsExactly("RECUSADO", "OK");
        assertThat((String) checagens.get(0).dados().get("motivo")).contains(PRAZO).contains("não tem origem")
                .contains("A fonte que a frase cita").contains("não traz esse valor");
        assertThat(texto.fontes()).hasSize(1);
        assertThat(jdbc.sql("SELECT texto FROM documento_trecho WHERE id = :t").param("t", texto.fontes().get(0).trechoId())
                .query(String.class).single()).contains(PRAZO);
    }

    @Test
    void citacaoInventadaQueABuscaNuncaDevolveEhRecusadaMesmoSemNumero() {
        long conversa = abrir();
        modelo.chamada(BUSCAR, busca(CONSULTA)).texto("Os geradores devem ser informados à produção [T99].")
                .texto("Os geradores devem ser informados à produção [T98].");

        ItemTexto texto = textoFinal(falar(conversa, "Até quando informo os geradores?"));

        assertThat(texto.texto()).isEqualTo(LoopDoAgente.TEXTO_NAO_ENCONTREI);
        assertThat(todas(ultimoTurno(conversa), "CHECAGEM")).extracting(Linha::resultado).containsExactly("RECUSADO", "RECUSADO");
        assertThat(fontesGravadas(conversa)).isEmpty();
    }

    @Test
    void citacaoDeUmTurnoAnteriorNaoApoiaOTurnoSeguinteEOHistoricoNaoLevaOTrechoVelho() {
        long conversa = abrir();
        modelo.chamada(BUSCAR, busca(CONSULTA));
        escrever(pedido -> frasePrazo(marcador(pedido, PRAZO, true)));
        ItemTexto primeiro = textoFinal(falar(conversa, "Até quando informo os geradores?"));
        String marcadorDoTurno1 = primeiro.fontes().get(0).marcador();
        modelo.limpar();
        // no turno 2 o modelo não busca de novo e reaproveita o marcador do turno 1, duas vezes
        modelo.texto(frasePrazo(marcadorDoTurno1)).texto(frasePrazo(marcadorDoTurno1));

        ItemTexto segundo = textoFinal(falar(conversa, "E o prazo da arena?"));

        // o turno 2 não buscou: a segunda recusa vira a frase de sempre (não há busca para dizer "não encontrei")
        assertThat(segundo.texto()).doesNotContain(PRAZO);
        assertThat(segundo.fontes()).isEmpty();
        assertThat(todas(ultimoTurno(conversa), "CHECAGEM")).extracting(Linha::resultado).containsExactly("RECUSADO", "RECUSADO");
        assertThat((String) todas(ultimoTurno(conversa), "CHECAGEM").get(0).dados().get("motivo")).contains("[" + marcadorDoTurno1 + "]");
        // o histórico do turno 2 não leva o trecho do turno 1: leva um aviso e os nomes dos documentos
        String resultadoVelho = modelo.pedidos().get(0).mensagens().stream().filter(m -> m.papel().equals("tool")).map(MensagemDeChat::conteudo)
                .findFirst().orElseThrow();
        assertThat(resultadoVelho).contains("resultado_de_turno_anterior").contains("busque de novo").contains("Manual de Operações 2027")
                .doesNotContain(PRAZO).doesNotContain("\"trechos\"").doesNotContain("\"texto\"");
    }

    // ---------------------------------------------------------------------
    // O "não encontrei"
    // ---------------------------------------------------------------------

    @Test
    void aFraseDeNaoEncontreiGanhaAIndicacaoDoAtendimentoEFicaGravadaInteira() {
        long conversa = abrir();
        modelo.chamada(BUSCAR, busca("lugar para estacionar o helicóptero"))
                .texto("Não encontrei isso nos documentos do portal.");

        ItemTexto texto = textoFinal(falar(conversa, "Posso pousar um helicóptero?"));

        assertThat(texto.texto()).isEqualTo("Não encontrei isso nos documentos do portal. Para essa dúvida, fale com o atendimento da RBS.");
        assertThat(texto.fontes()).isEmpty();
        assertThat(textosDoAssistente(conversa)).contains(LoopDoAgente.TEXTO_NAO_ENCONTREI);
        Linha saida = primeira(ultimoTurno(conversa), "SAIDA");
        assertThat(saida.resultado()).isEqualTo("OK");
        assertThat(saida.dados()).containsEntry("tipo", "TEXTO").containsEntry("motivo", "NAO_ENCONTREI");
    }

    @Test
    void aFraseDeNaoEncontreiEscritaDeOutroJeitoTambemGanhaAIndicacao() {
        for (String escrita : List.of("não encontrei isso nos documentos do portal", "  Não encontrei isso nos documentos do portal.  ",
                "NÃO ENCONTREI ISSO NOS DOCUMENTOS DO PORTAL.")) {
            modelo.limpar();
            long conversa = abrir();
            modelo.chamada(BUSCAR, busca("lugar para estacionar o helicóptero")).texto(escrita);

            assertThat(textoFinal(falar(conversa, "Posso pousar um helicóptero?")).texto()).as(escrita).isEqualTo(LoopDoAgente.TEXTO_NAO_ENCONTREI);
        }
    }

    @Test
    void naoEncontreiMaisAlgumaCoisaNaoGanhaAIndicacaoEsoPassaSeNaoTiverNumero() {
        long conversa = abrir();
        modelo.chamada(BUSCAR, busca("lugar para estacionar o helicóptero"))
                .texto("Não encontrei isso nos documentos do portal. Mas costuma ser até 15/12/2026.")
                .texto("Não encontrei isso nos documentos do portal. Mas costuma haver uma regra para isso.");

        ItemTexto texto = textoFinal(falar(conversa, "Posso pousar um helicóptero?"));

        // o número sem fonte barra a primeira; a segunda tem mais do que a frase certa, e por isso passa como texto do modelo
        assertThat(texto.texto()).isEqualTo("Não encontrei isso nos documentos do portal. Mas costuma haver uma regra para isso.");
        assertThat(todas(ultimoTurno(conversa), "CHECAGEM")).extracting(Linha::resultado).containsExactly("RECUSADO", "OK");
    }

    @Test
    void buscaSemNenhumTrechoEModeloQueResponderSemFonteViraOTextoFixo() {
        long conversa = abrir();
        // só palavras vazias: a busca volta sem trecho e com o aviso de que não havia palavra útil
        modelo.chamada(BUSCAR, busca("de a o que para")).texto("O prazo costuma ser no fim do ano.");

        ItemTexto texto = textoFinal(falar(conversa, "Qual o prazo?"));

        assertThat(texto.texto()).isEqualTo(LoopDoAgente.TEXTO_NAO_ENCONTREI);
        assertThat(texto.porModelo()).isFalse();
        Linha saida = primeira(ultimoTurno(conversa), "SAIDA");
        assertThat(saida.resultado()).isEqualTo("RECUSADO");
        assertThat(saida.dados()).containsEntry("motivo", "SEM_FONTE");
        assertThat(textosDoAssistente(conversa)).doesNotContain("fim do ano");
    }

    @Test
    void turnoEmQueTodaBuscaDeuErroEResponderSemCitacaoViraOTextoFixoDaBuscaQueFalhou() {
        long conversa = abrir();
        embeddings.falharAsProximasBuscas(1);
        modelo.chamada(BUSCAR, busca(CONSULTA)).texto("O prazo costuma ser no fim do ano.");

        ItemTexto texto = textoFinal(falar(conversa, "Qual o prazo?"));

        // a busca caiu: dizer "não encontrei" seria falso, porque nada foi procurado. O texto fixo diz que não deu para consultar
        assertThat(texto.texto()).isEqualTo(LoopDoAgente.FRASE_SEM_CONSULTA)
                .isEqualTo("Não consegui consultar os documentos agora. Tente de novo em instantes.");
        assertThat(texto.porModelo()).isFalse();
        Linha saida = primeira(ultimoTurno(conversa), "SAIDA");
        assertThat(saida.resultado()).isEqualTo("RECUSADO");
        assertThat(saida.dados()).containsEntry("tipo", "FRASE_FIXA").containsEntry("motivo", "BUSCA_FALHOU");
        assertThat(primeira(ultimoTurno(conversa), "BUSCA").resultado()).isEqualTo("ERRO");
        assertThat(textosDoAssistente(conversa)).doesNotContain("fim do ano").doesNotContain("Não encontrei");
    }

    @Test
    void asTresBuscasDeramErroEResponderSemCitacaoViraOTextoFixoDaBuscaQueFalhou() {
        long conversa = abrir();
        embeddings.falharAsProximasBuscas(3);
        modelo.chamada(BUSCAR, busca(CONSULTA)).chamada(BUSCAR, busca(CONSULTA + " novamente")).chamada(BUSCAR, busca("outro assunto"))
                .texto("Costuma ser no fim do ano.");

        ItemTexto texto = textoFinal(falar(conversa, "Qual o prazo?"));

        assertThat(texto.texto()).isEqualTo(LoopDoAgente.FRASE_SEM_CONSULTA);
        assertThat(texto.porModelo()).isFalse();
        assertThat(primeira(ultimoTurno(conversa), "SAIDA").dados()).containsEntry("motivo", "BUSCA_FALHOU");
    }

    @Test
    void oModeloQueDizNaoConseguirBuscarOuNaoEncontreiDepoisDeTodaBuscaFalharTambemViraOTextoFixoDaBuscaQueFalhou() {
        for (String escrita : List.of("Não consegui buscar esse dado agora.", "Não encontrei isso nos documentos do portal.")) {
            modelo.limpar();
            embeddings.falharAsProximasBuscas(1);
            long conversa = abrir();
            modelo.chamada(BUSCAR, busca(CONSULTA)).texto(escrita);

            ItemTexto texto = textoFinal(falar(conversa, "Qual o prazo?"));

            assertThat(texto.texto()).as(escrita).isEqualTo(LoopDoAgente.FRASE_SEM_CONSULTA);
            assertThat(texto.porModelo()).isFalse();
            assertThat(primeira(ultimoTurno(conversa), "SAIDA").dados()).containsEntry("motivo", "BUSCA_FALHOU");
        }
    }

    @Test
    void buscaVaziaMaisBuscaComErroContinuaSendoOL2() {
        long conversa = abrir();
        embeddings.falharAsProximasBuscas(1);
        // a primeira busca cai; a segunda chega ao fim e volta sem trecho (só palavras vazias)
        modelo.chamada(BUSCAR, busca(CONSULTA)).chamada(BUSCAR, busca("de a o que para")).texto("O prazo costuma ser no fim do ano.");

        ItemTexto texto = textoFinal(falar(conversa, "Qual o prazo?"));

        assertThat(texto.texto()).isEqualTo(LoopDoAgente.TEXTO_NAO_ENCONTREI);
        assertThat(texto.porModelo()).isFalse();
        assertThat(primeira(ultimoTurno(conversa), "SAIDA").dados()).containsEntry("motivo", "SEM_FONTE");
    }

    @Test
    void todaBuscaComErroMasRespostaComDadoDaParceriaOuComAFraseCertaDoModeloNaoViraOTextoFixo() {
        long conversa = abrir();
        embeddings.falharAsProximasBuscas(1);
        modelo.chamada(BUSCAR, busca(CONSULTA)).texto("Para {{parc:patrocinador}}, não consegui consultar os documentos agora.");

        ItemTexto texto = textoFinal(falar(conversa, "Qual a regra?"));

        assertThat(texto.texto()).isEqualTo("Para Cliente Amarelo, não consegui consultar os documentos agora.");
        assertThat(primeira(ultimoTurno(conversa), "SAIDA").dados()).doesNotContainEntry("motivo", "BUSCA_FALHOU");
    }

    @Test
    void duasRecusasSeguidasDepoisDeTodaBuscaFalharViramOTextoFixoDaBuscaQueFalhou() {
        long conversa = abrir();
        embeddings.falharAsProximasBuscas(1);
        modelo.chamada(BUSCAR, busca(CONSULTA)).texto("O prazo é " + PRAZO + " para todas as marcas.")
                .texto("O prazo é " + PRAZO + " e vale para todas as marcas.");

        ItemTexto texto = textoFinal(falar(conversa, "Qual o prazo?"));

        assertThat(texto.texto()).isEqualTo(LoopDoAgente.FRASE_SEM_CONSULTA);
        assertThat(primeira(ultimoTurno(conversa), "SAIDA").dados()).containsEntry("motivo", "TEXTO_RECUSADO");
    }

    @Test
    void umaBuscaQueCaiuEOutraQueDeuTrechoDeixamAResposta() {
        long conversa = abrir();
        embeddings.falharAsProximasBuscas(1);
        modelo.chamada(BUSCAR, busca(CONSULTA)).chamada(BUSCAR, busca(CONSULTA));
        escrever(pedido -> frasePrazo(marcador(pedido, PRAZO, true)));

        ItemTexto texto = textoFinal(falar(conversa, "Até quando informo os geradores?"));

        assertThat(texto.texto()).contains(PRAZO).matches(".*\\[T[1-9]\\]\\.");
        assertThat(texto.fontes()).hasSize(1);
        assertThat(primeira(ultimoTurno(conversa), "SAIDA").dados()).doesNotContainEntry("motivo", "SEM_FONTE");
    }

    @Test
    void buscaQueCaiuMasRespostaComDadoDaParceriaNaoViraNaoEncontrei() {
        long conversa = abrir();
        embeddings.falharAsProximasBuscas(1);
        modelo.chamada(BUSCAR, busca(CONSULTA)).texto("Para {{parc:patrocinador}}, não consegui consultar os documentos agora.");

        ItemTexto texto = textoFinal(falar(conversa, "Qual a regra?"));

        assertThat(texto.texto()).isEqualTo("Para Cliente Amarelo, não consegui consultar os documentos agora.");
    }

    @Test
    void buscaSemTrechoMasRespostaComDadoDaParceriaNaoViraNaoEncontrei() {
        long conversa = abrir();
        modelo.chamada(BUSCAR, busca("de a o que para")).texto("Para {{parc:patrocinador}}, o regulamento não traz essa regra.");

        ItemTexto texto = textoFinal(falar(conversa, "Qual a regra?"));

        // o marcador de dado da parceria é uma origem legítima da resposta: o servidor não troca por "não encontrei"
        assertThat(texto.texto()).isEqualTo("Para Cliente Amarelo, o regulamento não traz essa regra.");
        assertThat(primeira(ultimoTurno(conversa), "SAIDA").dados()).doesNotContainEntry("motivo", "SEM_FONTE");
    }

    @Test
    void semBuscarNaoHaNaoEncontreiAutomaticoEUmaConversaCurtaPassaNormalmente() {
        long conversa = abrir();
        modelo.texto("Olá! Posso ajudar com o portal e com os documentos da sua parceria.");

        ItemTexto texto = textoFinal(falar(conversa, "Oi"));

        assertThat(texto.texto()).isEqualTo("Olá! Posso ajudar com o portal e com os documentos da sua parceria.");
        assertThat(primeira(ultimoTurno(conversa), "SAIDA").dados()).doesNotContainEntry("motivo", "SEM_FONTE");
    }

    // ---------------------------------------------------------------------
    // O prompt
    // ---------------------------------------------------------------------

    @Test
    void oPromptDoChatTrazAFraseExataARecusaForaDeEscopoEAsRegrasDeCitacao() {
        long conversa = abrir();
        modelo.texto("Olá!");

        falar(conversa, "Oi");

        String prompt = modelo.pedidos().get(0).mensagens().get(0).conteudo();
        assertThat(prompt).contains("buscar_documentos")
                .contains("\"Não encontrei isso nos documentos do portal.\"")
                .contains("Só posso ajudar com o portal e com a sua parceria no Planeta Atlântida.")
                .contains("[T1]").contains("Nunca escreva número por extenso")
                .contains("Os marcadores de turnos anteriores não valem")
                .doesNotContain("Documentos que esta conversa já citou");
        assertThat(prompt).doesNotContain("@MARCA@").doesNotContain("@DATA@");
    }

    @Test
    void oPromptDoChatTrazAsRegrasDeOutraMarcaOutraEdicaoMudouContaTextoSimplesERespostaCurta() {
        long conversa = abrir();
        modelo.texto("Olá!");

        falar(conversa, "Oi");

        String prompt = modelo.pedidos().get(0).mensagens().get(0).conteudo();
        String naoEncontrei = "escreva exatamente \"Não encontrei isso nos documentos do portal.\" e mais nada";
        // outra marca: a frase exata e nada mais, sem dizer se existe (ataque-02, ataque-09)
        assertThat(prompt).contains("Se pedirem dados de outra marca, " + naoEncontrei).contains("não diga se os dados existem")
                .doesNotContain("diga que não tem acesso");
        // outra edição: a mesma frase exata, sem emendar dados da edição desta conversa (ver-03, ver-04)
        assertThat(prompt).contains("outra edição (\"quanto era na edição de 2026?\", \"na edição passada\"), " + naoEncontrei)
                .contains("sem juntar dados desta edição").doesNotContain("(\"em 2026\"");
        // lista simples: a citação vai em cada item, porque cada linha é uma frase
        assertThat(prompt).contains("lista simples pode, com a citação [T1] em cada item");
        // "mudou?": só a versão que vale hoje, com a citação, sem afirmar que mudou nem que não mudou
        assertThat(prompt).contains("Você só vê a versão que vale hoje").contains("diga o que vale hoje, com a citação")
                .contains("nunca afirme que mudou nem que não mudou");
        // conta com valor de documento: passa por calcular, com a origem de cada número, e nunca de cabeça
        assertThat(prompt).contains("quanto pago?").contains("chame calcular").contains("Nunca faça conta de cabeça")
                .contains("{\"fonte\":\"usuario\",\"valor\":\"3\"}").contains("nunca traz o número escrito");
        // registro do portal e conta: as citações [R1] e [C1], só deste turno
        assertThat(prompt).contains("[R1]").contains("[C1]").contains("Conta e marcador de turno anterior não valem");
        // número da parceria: consultar de novo em cada pergunta, porque o dado pode ter mudado
        assertThat(prompt).contains("de novo a cada pergunta sobre esses números").contains("o dado pode ter mudado");
        // só o que foi perguntado, sem orientação que o trecho não traz
        assertThat(prompt).contains("Responda só ao que foi perguntado").contains("Não dê orientação que não esteja no trecho");
        // texto simples e sem cumprimento
        assertThat(prompt).contains("texto simples").contains("sem negrito, títulos nem outra marcação").contains("Boa tarde!")
                .contains("vá direto à resposta");
        // continua curto
        assertThat(prompt.length()).as("o prompt do chat tem de ficar curto").isLessThan(10000);
    }
}
