package br.com.rbs.portal.atlas.relatorio;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.ROXO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.relatorio.ChecagemDeTexto.Veredito;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Resolucao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] [VALIDACAO] A checagem do texto que o modelo escreve (plano, seção 10.6), com o banco da amostra para os marcadores
 * resolverem de verdade: tira os marcadores e recusa qualquer algarismo que sobrar, todo marcador tem de resolver nesta parceria,
 * até 3 frases e 600 caracteres no parágrafo do relatório, sem HTML, link, imagem nem URL. O texto do chat passa pela mesma regra,
 * menos o limite de frases. Cada teste é desfeito no fim (@Transactional).
 */
@TesteDeIntegracao
@Transactional
class ChecagemDeTextoTest {

    private static final String FRASE_BOA = "A marca {{parc:patrocinador}} teve {{cons:aplicacoes.comprovadas}} de "
            + "{{cons:aplicacoes.previstas}} aplicações de marca comprovadas no evento.";

    @Autowired
    ChecagemDeTexto checagem;

    @Autowired
    ResolvedorDeReferencias resolvedor;

    @Autowired
    ModeloBase modelos;

    @Autowired
    JdbcClient jdbc;

    Resolucao doAmarelo() {
        return resolvedor.abrir(usuario(jdbc, AMARELO), SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"),
                modelos.obter(ModeloBase.ATIVACAO_EVENTO).orElseThrow());
    }

    Veredito paragrafo(String texto) {
        return checagem.paragrafo(doAmarelo(), texto);
    }

    Veredito chat(String texto) {
        return checagem.chat(doAmarelo(), texto);
    }

    // ---------------------------------------------------------------------
    // Regra 1: sem algarismo fora de marcador
    // ---------------------------------------------------------------------

    @Test
    void aprovaTextoSoComMarcadoresELimpaOsEspacos() {
        Veredito veredito = paragrafo("  A marca {{ parc:patrocinador }}   teve\n{{cons:aplicacoes.comprovadas}} aplicações.  ");

        assertThat(veredito.aprovado()).isTrue();
        assertThat(veredito.texto()).isEqualTo("A marca {{ parc:patrocinador }} teve {{cons:aplicacoes.comprovadas}} aplicações.");
        assertThat(veredito.motivo()).isNull();
        assertThat(paragrafo(FRASE_BOA).aprovado()).isTrue();
    }

    @Test
    void recusaQualquerAlgarismoQueSobrarForaDoMarcador() {
        Veredito veredito = paragrafo("A marca {{parc:patrocinador}} teve 17 aplicações comprovadas.");

        assertThat(veredito.aprovado()).isFalse();
        assertThat(veredito.motivo()).contains("algarismo \"1\"").contains("marcador");
        assertThat(veredito.texto()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Foram 17 aplicações.", "Edição 2027 do festival.", "Taxa de 89,3% dos visitantes.", "Metade, ou 1/2 dos visitantes.",
            "Quase ½ das aplicações.", "Um valor ao quadrado: x².", "Em algarismos arábicos: ٣ aplicações.", "Largura total: １２ aplicações.",
            "Ano em romano: Ⅻ aplicações.", "B2B é o segmento.", "Sala 3A."})
    void recusaDigitoDeQualquerAlfabetoFracaoEExpoente(String texto) {
        assertThat(paragrafo(texto).aprovado()).as(texto).isFalse();
        assertThat(chat(texto).aprovado()).as("chat: " + texto).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Foram dezessete aplicações comprovadas.", "Quase cem por cento das aplicações.", "Vinte e três brindes foram entregues.",
            "Foram dois mil visitantes.", "A ativação foi no primeiro de dezembro.", "Dez de dezembro foi o dia.", "Zero cadastros.",
            "Duas aplicações de marca.", "Trezentos e doze pessoas passaram."})
    void recusaNumeroEDataPorExtensoNoParagrafoDoRelatorio(String texto) {
        // M6: o prompt do relatório manda não escrever número nem data, "nem com algarismo nem por extenso", e o servidor confere
        Veredito veredito = paragrafo(texto);

        assertThat(veredito.aprovado()).as(texto).isFalse();
        assertThat(veredito.motivo()).as(texto).contains("por extenso").contains("marcador");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Um dos destaques foi o espaço da marca.", "A primeira vez da marca no festival.", "Os dois manuais valem.",
            "Em março, a marca esteve no festival.", "O espaço recebeu os visitantes no meio do festival."})
    void aceitaPalavraQueNaoENumeroNoParagrafoDoRelatorio(String texto) {
        assertThat(paragrafo(texto).aprovado()).as(texto).isTrue();
    }

    @Test
    void algarismoDentroDoMarcadorNaoContaMasMarcadorComNumeroNaoExiste() {
        assertThat(paragrafo("Veja {{dado:ativacao.visitantes_total}} e {{calc:taxa_cadastro}}.").aprovado()).isTrue();

        Veredito inventado = paragrafo("Foram {{17}} aplicações.");
        assertThat(inventado.aprovado()).isFalse();
        assertThat(inventado.motivo()).contains("{{17}}").contains("não existe");
    }

    @Test
    void chaveSoltaOuMarcadorMalFechadoERecusado() {
        assertThat(paragrafo("A marca {{parc:patrocinador} teve sucesso.").motivo()).contains("chave");
        assertThat(paragrafo("A marca parc:patrocinador}} teve sucesso.").motivo()).contains("chave");
        assertThat(paragrafo("A marca {{parc:patrocinador}} teve {{ sucesso.").motivo()).contains("chave");
    }

    // ---------------------------------------------------------------------
    // Regra 2: todo marcador resolve nesta parceria
    // ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "{{dado:ativacao.inventado}}", "{{dado:outra.chave_que_nao_existe}}", "{{cons:aplicacoes.inexistente}}",
            "{{cons:midia.previstas}}", "{{calc:conta_inventada}}", "{{lista:aplicacoes}}", "{{lista:comprovacoes}}",
            "{{parc:parceria_id}}", "{{parceria:2}}", "{{vw_indicadores_parceria}}", "{{}}", "{{ }}", "{{dado:}}"})
    void referenciaInventadaOuForaDaGramaticaERecusada(String marcador) {
        Veredito veredito = paragrafo("Veja o dado " + marcador + " no relatório.");

        assertThat(veredito.aprovado()).as(marcador).isFalse();
        assertThat(veredito.motivo()).as(marcador).contains("não existe nesta parceria");
    }

    @Test
    void oMarcadorResolveNaParceriaDeQuemConversaENuncaEmOutra() {
        // O Roxo não tem aplicação: o marcador existe e resolve (indisponível), e o texto de um marcador não escolhe parceria.
        Resolucao doRoxo = resolvedor.abrir(usuario(jdbc, ROXO), SessaoDeApi.parceriaDe2027(jdbc, "Cliente Roxo"),
                modelos.obter(ModeloBase.ATIVACAO_EVENTO).orElseThrow());

        assertThat(checagem.paragrafo(doRoxo, FRASE_BOA).aprovado()).isTrue();
        assertThat(doRoxo.valor("parc:patrocinador").texto()).isEqualTo("Cliente Roxo");
        assertThat(checagem.paragrafo(doRoxo, "Os dados de {{parc:patrocinador}} do Cliente Amarelo.").aprovado()).isTrue();
        assertThat(doRoxo.paraExibicao("{{parc:patrocinador}}")).isEqualTo("Cliente Roxo");
    }

    // ---------------------------------------------------------------------
    // Regra 3: até 3 frases e 600 caracteres (só no parágrafo do relatório)
    // ---------------------------------------------------------------------

    @Test
    void aceitaAteTresFrasesERecusaMais() {
        assertThat(paragrafo("Primeira frase. Segunda frase! Terceira frase?").aprovado()).isTrue();

        Veredito quatro = paragrafo("Primeira frase. Segunda frase. Terceira frase. Quarta frase.");
        assertThat(quatro.aprovado()).isFalse();
        assertThat(quatro.motivo()).contains("4 frases").contains("máximo é 3");
    }

    @Test
    void oPontoDoMarcadorNaoContaComoFim() {
        assertThat(ChecagemDeTexto.contarFrases("A {{dado:ativacao.visitantes_total}} foi bem. {{calc:taxa_cadastro}} também. Fim"))
                .isEqualTo(3);
        assertThat(ChecagemDeTexto.contarFrases("Só uma frase sem ponto final")).isEqualTo(1);
        assertThat(ChecagemDeTexto.contarFrases("   ")).isZero();
    }

    @Test
    void recusaParagrafoComMaisDeSeiscentosCaracteresMasOChatNaoTemEsseLimite() {
        assertThat(paragrafo("a".repeat(ChecagemDeTexto.MAXIMO_DE_CARACTERES)).aprovado()).isTrue();
        Veredito grande = paragrafo("a".repeat(601));
        assertThat(grande.aprovado()).isFalse();
        assertThat(grande.motivo()).contains("601 caracteres").contains("600");
        assertThat(chat("Uma frase. Duas. Três. Quatro. Cinco. Seis. Sete. Oito. " + "a".repeat(700)).aprovado()).isTrue();
    }

    // ---------------------------------------------------------------------
    // Regra 4: sem HTML, link, imagem nem URL
    // ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "Veja <b>isto</b>.", "Use <script>alert()</script>.", "Menor que < e maior que > aqui.", "Veja [o site](https://exemplo.example).",
            "Veja ![imagem](x.png).", "Veja [aqui] (outro).", "Veja https://exemplo.example agora.", "Veja http://exemplo.example.",
            "Veja www.exemplo.example agora.", "Escreva para ajuda@exemplo.com hoje.", "Acesse exemplo.com.br hoje.",
            "Clique em javascript:alert agora.", "Veja mailto:alguem@exemplo.example.", "Veja ftp://exemplo.example/arquivo.",
            "Imagem data:image/png;base64 aqui."})
    void recusaHtmlLinkImagemEUrl(String texto) {
        assertThat(paragrafo(texto).aprovado()).as(texto).isFalse();
        assertThat(chat(texto).aprovado()).as("chat: " + texto).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Veja coleta-atlas.invalid/relatorio agora.", "Veja coleta-atlas.invalid agora.", "Veja coleta-atlas.click hoje.", "Veja site.link hoje.",
            "Veja //coleta-atlas.click/x hoje.", "Veja coleta-atlas[.]com agora.", "Veja coleta-atlas(.)com agora.", "Veja COLETA-ATLAS.INVALID agora.",
            "Veja Coleta.Click hoje.", "Veja a.b.coleta-atlas.invalid agora.", "Veja site-novo.link.", "Veja açaí.com agora.", "Veja o portal.rbs hoje.",
            "Escreva para alguem@coleta-atlas.invalid hoje.", "Veja exemplo.com.br/x.", "Veja sub.dominio.example hoje.",
            // a regra de dois rótulos de 2 ou mais caracteres abriu estes, que a lista de domínios de topo recusava: a união das duas
            "Veja x.com/coleta hoje.", "Veja t.co/abc hoje.", "Escreva para coleta@x.io hoje.", "Veja a.b.com hoje.", "Veja x.io hoje.",
            "Veja X.COM hoje.", "Veja coleta_atlas.invalid hoje.", "Veja a_b.com hoje.", "Veja coleta_atlas.click/x hoje.",
            // o ponto de outra escrita vale como ponto
            "Veja coleta-atlas\uFF0Eclick hoje.", "Veja coleta-atlas\u3002click hoje.", "Veja coleta-atlas\uFF61click hoje.", "Veja x\uFF0Ecom hoje.",
            "Veja www\uFF0Eexemplo\uFF0Ecom hoje.", "Veja //coleta\uFF0Eclick/x hoje.", "Veja coleta-atlas[\uFF0E]com hoje."})
    void recusaEnderecoSemEsquemaDeQualquerDominioDeTopo(String texto) {
        assertThat(paragrafo(texto).aprovado()).as("parágrafo: " + texto).isFalse();
        assertThat(chat(texto).aprovado()).as("chat: " + texto).isFalse();
        assertThat(paragrafo(texto).motivo()).as(texto).contains("endereço");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Fale com a produção, p.ex. amanhã cedo.", "O titular, com R.G. e S.A. juntos, assina.", "Cliente Amarelo S.A. participa do evento.",
            "Etc. e tal. Fim.", "Veja a p. do manual.", "Aguarde... e depois decida.", "Pronto?! Sim.", "Lembre-se: e.g. isto vale.",
            "A marca teve 100% de adesão", "Ex.: o gerador próprio.", "Veja o item a.b e o c.d.", "Responda com sim ou não.", "Falta pouco... muito pouco.",
            "Abre às a.m. e fecha às p.m.", "Veja a seção 3.2 e a versão v2.0.", "O item 3.2.1 vale.", "Vale e.g. isto e i.e. aquilo.", "A S.A. e a R.G. valem.",
            "Escreva a data como 04/12/2026 e a hora como 18h.", "O gerador de 10.000 kVA custa 1.250,00 por dia."})
    void naoRecusaPontuacaoNemAbreviaturaComUmaLetra(String texto) {
        assertThat(ChecagemDeTexto.motivoDeForma(texto, texto)).as(texto).isEmpty();
    }

    @Test
    void depoisDoPontoSemEspacoPareceEnderecoEERecusadoEIssoFoiDecidido() {
        // "Prazo.Depois" tem a forma de rótulo.rótulo: a regra não distingue de um domínio, e o modelo reescreve com o espaço
        assertThat(ChecagemDeTexto.motivoDeForma("O prazo.Depois disso, não.", "O prazo.Depois disso, não.")).isPresent();
    }

    @Test
    void textoVazioOuEmBrancoERecusado() {
        for (String vazio : new String[]{null, "", "   ", "\n\t "}) {
            assertThat(paragrafo(vazio).motivo()).isEqualTo("O texto está vazio.");
            assertThat(chat(vazio).aprovado()).isFalse();
        }
    }

    @Test
    void oTextoDoChatPassaPelasMesmasRegrasDeAlgarismoEMarcador() {
        assertThat(chat("Sim: {{parc:patrocinador}} tem {{cons:aplicacoes.previstas}} aplicações previstas.").aprovado()).isTrue();
        assertThat(chat("Deu 7 por visitante.").motivo()).contains("algarismo \"7\"");
        assertThat(chat("Veja {{dado:ativacao.inventado}}.").motivo()).contains("não existe nesta parceria");
    }

    // ---------------------------------------------------------------------
    // Termo interno: a marca não lê chave, nome de ferramenta nem "marcador"
    // ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "O total de visitantes está em dado:ativacao.visitantes_total.",
            "Veja cons:aplicacoes.comprovadas no portal.",
            "A taxa sai de calc:taxa_cadastro.",
            "As fotos estão em lista:comprovacoes.",
            "A marca é parc:patrocinador.",
            "A edição é evento:edicao.",
            "Vou chamar relatorio_iniciar agora.",
            "Consultei com parceria_consultar.",
            "Esse dado fica no relatório, com o marcador próprio.",
            "Use os marcadores do relatório.",
            "A tool devolveu erro.",
            "Vou abrir um cartão de solicitação."})
    void recusaChaveCruaNomeDeFerramentaETermoInterno(String texto) {
        assertThat(chat(texto).aprovado()).as("chat: " + texto).isFalse();
        assertThat(chat(texto).motivo()).as("chat: " + texto).contains("termo interno");
        assertThat(paragrafo(texto).aprovado()).as("parágrafo: " + texto).isFalse();
    }

    @Test
    void palavrasComunsNaoSaoTermoInterno() {
        assertThat(chat("No evento: a marca {{parc:patrocinador}} teve boa presença. Dado o resultado, vale repetir.").aprovado()).isTrue();
        assertThat(chat("Esse número entra no relatório de ativação, com referência à edição {{evento:edicao}}.").aprovado()).isTrue();
    }

    @Test
    void noChatOMarcadorSemValorERecusadoMasNoParagrafoPassa() {
        String texto = "Visitantes no espaço: {{dado:ativacao.visitantes_total}}.";

        Veredito noChat = chat(texto);

        assertThat(noChat.aprovado()).isFalse();
        assertThat(noChat.motivo()).contains("não tem valor agora").contains(ResolvedorDeReferencias.NAO_INFORMADO);
        assertThat(paragrafo(texto).aprovado()).isTrue();   // no relatório, o bloco sai como indisponível, com o motivo
    }
}
