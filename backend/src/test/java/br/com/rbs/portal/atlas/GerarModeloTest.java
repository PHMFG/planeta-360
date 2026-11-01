package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.ROXO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.VERDE;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.exigirHistoricoValido;
import static br.com.rbs.portal.atlas.ProvedorRoteirizado.pedido;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemTexto;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.contexto.AtlasContextoFabrica;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.ferramentas.Execucao;
import br.com.rbs.portal.atlas.ferramentas.GerarModelo;
import br.com.rbs.portal.atlas.ferramentas.ModelosEmBranco;
import br.com.rbs.portal.atlas.ferramentas.RegistroDeFerramentas;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.MensagemDeChat;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.SemPermissaoException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

/**
 * [ATLAS] A ferramenta {@code gerar_modelo} (M5): o modelo em branco de cada tipo, com título, conteúdo e nome de arquivo
 * {@code .md}; os arquivos {@code .txt} sem algarismo, moeda nem "valor"; tipo que falta ou não existe, argumento desconhecido e
 * {@code parceria_id} como erro; a Pesquisa só para quem tem a aba aberta (Roxo e Verde recusados, Amarelo aceito, e o Amarelo
 * recusado se a RBS fechar a aba); e o loop: uma ida ao modelo só, a mesma rodada com {@code buscar_documentos} ou
 * {@code parceria_consultar} respondida nas duas, e o histórico válido para a mensagem seguinte. Cada teste é desfeito no fim
 * (@Transactional).
 */
@TesteComLlmRoteirizado
@Transactional
class GerarModeloTest {

    static final String BRIEFING = "{\"tipo\":\"BRIEFING_CONTEUDO\"}";
    static final String PESQUISA = "{\"tipo\":\"PERGUNTAS_PESQUISA\"}";
    static final String PLANO = "{\"tipo\":\"PLANO_OPERACIONAL_ATIVACAO\"}";

    @Autowired
    RegistroDeFerramentas registro;

    @Autowired
    AtlasContextoFabrica contextos;

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    ModelosEmBranco modelos;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void zerarORoteiro() {
        modelo.limpar();
    }

    AtlasContexto contexto(String email, String cliente) {
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, cliente);
        var pessoa = usuario(jdbc, email);
        long conversa = atlas.abrirConversa(pessoa, parceria, null, requisicao()).conversaId();
        return contextos.para(pessoa, parceria, conversa, requisicao());
    }

    FontesDoTurno turno() {
        return new FontesDoTurno("Oi", 2027, LocalDate.of(2026, 11, 1));
    }

    Execucao gerar(AtlasContexto contexto, String argumentos) {
        var preparo = registro.preparar(contexto, turno(), GerarModelo.NOME, argumentos);
        return registro.executar(contexto, turno(), preparo, "call_x");
    }

    static Map<String, Object> json(String texto) {
        return AtlasJson.ler(texto, new TypeReference<Map<String, Object>>() {
        });
    }

    static String arquivo(String nome) throws IOException {
        return new String(new ClassPathResource("atlas/modelos-em-branco/" + nome).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    static Map<String, String> camposDoErro(Throwable erro) {
        return ((DadosInvalidosException) erro).campos();
    }

    // ---------------------------------------------------------------------
    // Os modelos
    // ---------------------------------------------------------------------

    @Test
    void entregaCadaModeloComTituloConteudoENomeDeArquivoMd() throws IOException {
        AtlasContexto amarelo = contexto(AMARELO, "Cliente Amarelo");
        Map<String, List<String>> esperado = Map.of(
                "BRIEFING_CONTEUDO", List.of("Briefing de conteúdo", "briefing-de-conteudo.md", "briefing-conteudo.txt"),
                "PERGUNTAS_PESQUISA", List.of("Perguntas da pesquisa", "perguntas-da-pesquisa.md", "perguntas-pesquisa.txt"),
                "PLANO_OPERACIONAL_ATIVACAO", List.of("Plano operacional da ativação", "plano-operacional-da-ativacao.md", "plano-operacional.txt"));

        for (var entrada : esperado.entrySet()) {
            Execucao execucao = gerar(amarelo, "{\"tipo\":\"" + entrada.getKey() + "\"}");

            assertThat(execucao.direta()).as(entrada.getKey()).isTrue();
            assertThat(execucao.pausa()).isFalse();
            assertThat(execucao.pronto()).isFalse();
            Map<String, Object> resultado = json(execucao.resultadoJson());
            assertThat(resultado.keySet()).containsExactly("tipo", "modelo", "titulo", "conteudo", "nomeArquivo");
            assertThat(resultado).containsEntry("tipo", "modelo").containsEntry("modelo", entrada.getKey())
                    .containsEntry("titulo", entrada.getValue().get(0)).containsEntry("nomeArquivo", entrada.getValue().get(1));
            assertThat((String) resultado.get("conteudo")).isEqualTo(arquivo(entrada.getValue().get(2)).strip() + "\n");
            assertThat((String) resultado.get("nomeArquivo")).endsWith(".md");
        }
    }

    @Test
    void osArquivosExistemSemAlgarismoMoedaNemValor() throws IOException {
        for (String nome : List.of("briefing-conteudo.txt", "perguntas-pesquisa.txt", "plano-operacional.txt", "esqueleto-relatorio-cota.txt")) {
            String texto = arquivo(nome);

            assertThat(texto).as(nome).isNotBlank().startsWith("# ");
            assertThat(texto).as(nome + " sem algarismo").doesNotContainPattern("[0-9]");
            assertThat(texto.toLowerCase()).as(nome + " sem moeda nem valor").doesNotContain("r$").doesNotContain("valor")
                    .doesNotContain("{{").doesNotContain("brl");
        }
    }

    @Test
    void osModelosNaoLevamMarcaEdicaoNemInstrucaoAoModeloDeLinguagem() throws IOException {
        for (String nome : List.of("briefing-conteudo.txt", "perguntas-pesquisa.txt", "plano-operacional.txt", "esqueleto-relatorio-cota.txt")) {
            assertThat(arquivo(nome).toLowerCase()).as(nome).doesNotContain("atlas").doesNotContain("rbs").doesNotContain("planeta")
                    .doesNotContain("atlântida").doesNotContain("edição").doesNotContain("você é");
        }
    }

    // ---------------------------------------------------------------------
    // Os erros voltam ao modelo
    // ---------------------------------------------------------------------

    @Test
    void tipoDesconhecidoEErroQueListaOsValidos() {
        AtlasContexto amarelo = contexto(AMARELO, "Cliente Amarelo");

        for (String invalido : List.of("{\"tipo\":\"FAQ\"}", "{\"tipo\":\"briefing_conteudo\"}", "{\"tipo\":\"\"}", "{\"tipo\":\" BRIEFING_CONTEUDO\"}")) {
            assertThatThrownBy(() -> gerar(amarelo, invalido)).as(invalido).isInstanceOf(DadosInvalidosException.class).satisfies(erro -> {
                assertThat(camposDoErro(erro)).containsKey("tipo");
                assertThat(camposDoErro(erro).get("tipo")).contains("BRIEFING_CONTEUDO", "PERGUNTAS_PESQUISA", "PLANO_OPERACIONAL_ATIVACAO");
            });
        }
    }

    @Test
    void tipoQueFaltaOuNaoETextoEErro() {
        AtlasContexto amarelo = contexto(AMARELO, "Cliente Amarelo");

        assertThatThrownBy(() -> gerar(amarelo, "{}")).isInstanceOf(DadosInvalidosException.class)
                .satisfies(erro -> assertThat(camposDoErro(erro).get("tipo")).startsWith("Informe tipo. Use um destes: BRIEFING_CONTEUDO, PERGUNTAS_PESQUISA"));
        assertThatThrownBy(() -> gerar(amarelo, "{\"tipo\":3}")).isInstanceOf(DadosInvalidosException.class)
                .satisfies(erro -> assertThat(camposDoErro(erro).get("tipo")).startsWith("tipo tem de ser um texto. Use um destes:"));
    }

    @Test
    void argumentoDesconhecidoEParceriaIdSaoErroEUmModeloNaoVaiAOutraParceria() {
        AtlasContexto amarelo = contexto(AMARELO, "Cliente Amarelo");

        for (String lixo : List.of("{\"tipo\":\"BRIEFING_CONTEUDO\",\"parceria_id\":3}", "{\"tipo\":\"BRIEFING_CONTEUDO\",\"outra\":1}",
                "{\"parceria_id\":3}")) {
            assertThatThrownBy(() -> gerar(amarelo, lixo)).as(lixo).isInstanceOf(DadosInvalidosException.class);
        }
        assertThatThrownBy(() -> gerar(amarelo, "{\"tipo\":\"BRIEFING_CONTEUDO\",\"parceria_id\":3}"))
                .satisfies(erro -> assertThat(camposDoErro(erro)).containsKey("parceria_id"));
    }

    // ---------------------------------------------------------------------
    // A Pesquisa é só da Master
    // ---------------------------------------------------------------------

    @Test
    void asPerguntasDaPesquisaSoSaemParaQuemTemAAbaAberta() {
        assertThat(gerar(contexto(AMARELO, "Cliente Amarelo"), PESQUISA).direta()).isTrue();

        for (var outro : List.of(List.of(ROXO, "Cliente Roxo"), List.of(VERDE, "Cliente Verde"))) {
            AtlasContexto contexto = contexto(outro.get(0), outro.get(1));
            assertThatThrownBy(() -> gerar(contexto, PESQUISA)).as(outro.get(1)).isInstanceOf(SemPermissaoException.class)
                    .hasMessageContaining("aba Pesquisa");
            // os outros dois modelos seguem valendo, e o erro de tipo desconhecido não oferece o que não pode entregar
            assertThat(gerar(contexto, BRIEFING).direta()).isTrue();
            assertThat(gerar(contexto, PLANO).direta()).isTrue();
            assertThatThrownBy(() -> gerar(contexto, "{\"tipo\":\"FAQ\"}")).satisfies(erro -> {
                assertThat(camposDoErro(erro).get("tipo")).contains("BRIEFING_CONTEUDO", "PLANO_OPERACIONAL_ATIVACAO")
                        .doesNotContain("PERGUNTAS_PESQUISA");
            });
        }
    }

    @Test
    void aMasterTambemPerdeAsPerguntasSeARbsFechaAAbaPesquisa() {
        AtlasContexto amarelo = contexto(AMARELO, "Cliente Amarelo");
        jdbc.sql("UPDATE parceria_aba SET liberada = FALSE WHERE parceria_id = :p AND aba_id = (SELECT id FROM aba WHERE codigo = 'PESQUISA')")
                .param("p", amarelo.parceriaId()).update();

        assertThatThrownBy(() -> gerar(amarelo, PESQUISA)).isInstanceOf(SemPermissaoException.class);
        assertThat(modelos.oferecidos(amarelo.usuario(), amarelo.parceriaId())).extracting(AtlasDtos.ModeloOferecido::tipo)
                .containsExactly("BRIEFING_CONTEUDO", "PLANO_OPERACIONAL_ATIVACAO");
    }

    // ---------------------------------------------------------------------
    // No loop
    // ---------------------------------------------------------------------

    long abrir(String email, String cliente) {
        return atlas.abrirConversa(usuario(jdbc, email), SessaoDeApi.parceriaDe2027(jdbc, cliente), null, requisicao()).conversaId();
    }

    ConversaDto falar(String email, long conversa, String texto) {
        return atlas.enviarMensagem(usuario(jdbc, email), conversa, new MensagemRequisicao(texto, null, null), requisicao());
    }

    List<String> papeis(long conversa) {
        return jdbc.sql("SELECT papel FROM atlas_mensagem WHERE conversa_id = :c ORDER BY ordem").param("c", conversa).query(String.class).list();
    }

    List<String> respostas(long conversa) {
        return jdbc.sql("SELECT conteudo FROM atlas_mensagem WHERE conversa_id = :c AND papel = 'tool' ORDER BY ordem")
                .param("c", conversa).query(String.class).list();
    }

    @Test
    void oTurnoComGerarModeloFazUmaIdaSoAoModeloEOHistoricoSegueValido() {
        long conversa = abrir(AMARELO, "Cliente Amarelo");
        modelo.chamada(GerarModelo.NOME, BRIEFING);   // o roteiro só tem este passo: uma segunda ida derruba o teste

        falar(AMARELO, conversa, "Me dá um modelo de briefing de conteúdo.");

        assertThat(modelo.pedidos()).hasSize(1);
        assertThat(modelo.sobraDoRoteiro()).isZero();
        assertThat(papeis(conversa)).containsExactly("user", "assistant", "tool");
        assertThat(json(respostas(conversa).get(0))).containsEntry("tipo", "modelo").containsEntry("modelo", "BRIEFING_CONTEUDO");

        // a mensagem seguinte: o que o modelo recebe tem a ordem certa e a resposta de verdade, sem o erro sintético
        modelo.texto("Posso ajudar com mais alguma coisa.");
        falar(AMARELO, conversa, "Obrigado.");
        exigirHistoricoValido(modelo.ultimoPedido());
        assertThat(modelo.ultimoPedido().mensagens().stream().map(MensagemDeChat::papel).toList())
                .containsExactly("system", "user", "assistant", "tool", "user");
        assertThat(modelo.ultimoPedido().mensagens().get(3).conteudo()).contains("\"modelo\":\"BRIEFING_CONTEUDO\"")
                .doesNotContain("não chegou a responder");
    }

    @Test
    void naMesmaRodadaComBuscaOuConsultaAsDuasChamadasTemResposta() {
        for (String outra : List.of(LoopDoAgenteTest.BUSCAR, LoopDoAgenteTest.CONSULTAR)) {
            modelo.limpar();
            long conversa = abrir(AMARELO, "Cliente Amarelo");
            String argumentos = LoopDoAgenteTest.BUSCAR.equals(outra) ? "{\"consulta\":\"prazo de entrega do material\"}" : "{}";
            modelo.chamadas(null, null, pedido(GerarModelo.NOME, PLANO), pedido(outra, argumentos)).texto("Aqui está o resto.");

            falar(AMARELO, conversa, "Preciso de um modelo e de uma consulta.");

            // a rodada tinha a direta e outra ferramenta: o modelo aparece ao lado do resultado da outra, e o turno segue com uma nova ida
            assertThat(modelo.pedidos()).as(outra).hasSize(2);
            assertThat(papeis(conversa)).as(outra).containsExactly("user", "assistant", "tool", "tool", "assistant");
            assertThat(json(respostas(conversa).get(0))).as(outra).containsEntry("tipo", "modelo");
            assertThat(respostas(conversa).get(1)).as(outra).doesNotContain("\"erro\"");
            // na nova ida o modelo recebe o aviso e o título, nunca o conteúdo do modelo em branco, e sem as diretas na oferta
            MensagemDeChat doModelo = modelo.pedidos().get(1).mensagens().stream()
                    .filter(m -> "tool".equals(m.papel()) && GerarModelo.NOME.equals(m.ferramenta())).findFirst().orElseThrow();
            assertThat(doModelo.conteudo()).as(outra).contains("O servidor já mostrou esta resposta à pessoa", "Plano operacional da ativação")
                    .doesNotContain("conteudo", "nomeArquivo");
            assertThat(ProvedorRoteirizado.nomesDasFerramentas(modelo.pedidos().get(1))).as(outra).doesNotContain(GerarModelo.NOME, "pedido_de_relatorio");

            modelo.texto("Certo.");
            falar(AMARELO, conversa, "E agora?");
            exigirHistoricoValido(modelo.ultimoPedido());
            assertThat(modelo.ultimoPedido().mensagens().stream().filter(m -> "tool".equals(m.papel())).map(MensagemDeChat::conteudo))
                    .as(outra).noneMatch(conteudo -> conteudo.contains("não chegou a responder")).hasSize(2);
        }
    }

    @Test
    void asPerguntasDaPesquisaRecusadasVoltamAoModeloQueExplicaNaMesmaConversa() {
        for (var outro : List.of(List.of(ROXO, "Cliente Roxo"), List.of(VERDE, "Cliente Verde"))) {
            modelo.limpar();
            long conversa = abrir(outro.get(0), outro.get(1));
            modelo.chamada(GerarModelo.NOME, PESQUISA).texto("Esse modelo não está disponível para a sua cota. Posso entregar outro.");

            ConversaDto turno = falar(outro.get(0), conversa, "Quero o modelo de perguntas da pesquisa.");

            assertThat(modelo.pedidos()).as(outro.get(1)).hasSize(2);   // o erro voltou ao modelo, que respondeu
            assertThat(respostas(conversa).get(0)).as(outro.get(1)).contains("\"erro\"").contains("aba Pesquisa");
            assertThat(turno.itens().stream().filter(ItemTexto.class::isInstance).map(ItemTexto.class::cast).map(ItemTexto::texto))
                    .containsExactly("Esse modelo não está disponível para a sua cota. Posso entregar outro.");
        }
    }
}
