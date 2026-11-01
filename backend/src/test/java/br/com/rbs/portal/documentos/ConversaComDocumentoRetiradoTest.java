package br.com.rbs.portal.documentos;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.FonteDoTexto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemTexto;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.AtlasService;
import br.com.rbs.portal.atlas.ProvedorRoteirizado;
import br.com.rbs.portal.atlas.TesteComLlmRoteirizado;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.MensagemDeChat;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

/**
 * [ATLAS] [VALIDACAO] Um documento que a conversa já citou e que depois deixou de valer (M4, critério bloqueante). A RBS revoga o
 * manual entre dois turnos: a resposta antiga continua na conversa, mas a fonte dela passa a vir marcada como revogada, e o prompt do
 * turno seguinte diz ao modelo, por título e versão, que o documento não vale mais e que não deve repetir o que foi dito dele. O
 * resultado de busca do turno anterior nunca volta ao modelo (só o aviso e o nome do documento). Cada teste é desfeito no fim
 * (@Transactional); a revogação passa pela rota 57, como a equipe faria, na mesma transação do teste.
 */
@TesteComLlmRoteirizado
@Transactional
@TestPropertySource(properties = {"atlas.relatorio.ligado=false", "atlas.debug.ferramentas=false"})
class ConversaComDocumentoRetiradoTest extends ApoioDeDocumentos {

    private static final String BUSCAR = "buscar_documentos";
    private static final String PRAZO = "04/12/2026";
    private static final String NOTA = "Documentos que esta conversa já citou e que mudaram:";

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @BeforeEach
    void zerarORoteiro() {
        modelo.limpar();
    }

    long abrir() {
        return atlas.abrirConversa(usuario(jdbc, AMARELO), SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo"), null, requisicao())
                .conversaId();
    }

    ConversaDto falar(long conversa, String texto) {
        return atlas.enviarMensagem(usuario(jdbc, AMARELO), conversa, new MensagemRequisicao(texto, null, null), requisicao());
    }

    static ItemTexto ultimoTexto(ConversaDto conversa) {
        List<ItemTexto> textos = conversa.itens().stream().filter(ItemTexto.class::isInstance).map(ItemTexto.class::cast).toList();
        assertThat(textos).isNotEmpty();
        return textos.get(textos.size() - 1);
    }

    /** O turno 1: busca, acha a data e responde citando o trecho que a traz. Devolve o texto da resposta. */
    ItemTexto primeiroTurno(long conversa) {
        modelo.chamada(BUSCAR, AtlasJson.escrever(Map.of("consulta", "prazo para informar os geradores próprios")));
        modelo.passo(pedido -> {
            MensagemDeChat busca = pedido.mensagens().stream().filter(m -> m.papel().equals("tool")).reduce((a, b) -> b).orElseThrow();
            List<?> trechos = (List<?>) AtlasJson.ler(busca.conteudo(), new TypeReference<Map<String, Object>>() {
            }).get("trechos");
            String marcador = trechos.stream().map(t -> (Map<?, ?>) t).filter(t -> ((String) t.get("texto")).contains(PRAZO))
                    .map(t -> (String) t.get("marcador")).findFirst().orElseThrow();
            return new RespostaDoLlm("Os geradores próprios devem ser informados à produção até " + PRAZO + " [" + marcador + "].", List.of(),
                    null);
        });
        return ultimoTexto(falar(conversa, "Até quando informo os geradores?"));
    }

    @Test
    void aFonteDeUmDocumentoRevogadoVemMarcadaEOPromptDoTurnoSeguinteAvisaOModelo() {
        long conversa = abrir();
        ItemTexto resposta = primeiroTurno(conversa);
        assertThat(resposta.fontes()).hasSize(1);
        FonteDoTexto fonte = resposta.fontes().get(0);
        assertThat(fonte.revogado()).isFalse();
        assertThat(fonte.substituido()).isFalse();
        assertThat(fonte.titulo()).isEqualTo("Manual de Operações 2027");
        // antes da revogação, o prompt não leva nota nenhuma
        modelo.limpar();
        modelo.texto("Certo.");
        falar(conversa, "Obrigado.");
        assertThat(modelo.pedidos().get(0).mensagens().get(0).conteudo()).doesNotContain(NOTA);

        assertThat(acao(entrar(RBS), fonte.documentoId(), "REVOGAR", "Saiu um manual novo.")).hasStatus(HttpStatus.OK);
        modelo.limpar();
        modelo.texto("Entendi. Vou considerar só o que vale.");
        ConversaDto depois = falar(conversa, "E sobre os geradores?");

        // o prompt do turno traz a nota, uma vez, com título e versão; o resto do prompt continua o mesmo
        String prompt = modelo.pedidos().get(0).mensagens().get(0).conteudo();
        assertThat(prompt).contains(NOTA).contains("- Manual de Operações 2027 v2 não vale mais: não use o que foi dito dele.");
        assertThat(prompt.indexOf(NOTA)).isEqualTo(prompt.lastIndexOf(NOTA));
        assertThat(prompt).startsWith("Você é o Atlas");
        // a resposta antiga segue na conversa, agora com a fonte revogada; a nova não herda fonte nenhuma
        List<ItemTexto> textos = depois.itens().stream().filter(ItemTexto.class::isInstance).map(ItemTexto.class::cast).toList();
        assertThat(textos.get(textos.size() - 1).fontes()).isEmpty();
        ConversaDto reaberta = atlas.verConversa(usuario(jdbc, AMARELO), conversa);
        List<FonteDoTexto> fontes = reaberta.itens().stream().filter(ItemTexto.class::isInstance).map(ItemTexto.class::cast)
                .flatMap(t -> t.fontes().stream()).toList();
        assertThat(fontes).hasSize(1);
        assertThat(fontes.get(0).revogado()).isTrue();
        assertThat(fontes.get(0).substituido()).isFalse();
        assertThat(fontes.get(0).marcador()).isEqualTo(fonte.marcador());
        // a nota não é gravada: nem na conversa, nem no que a conversa guarda do prompt
        assertThat(jdbc.sql("SELECT count(*) FROM atlas_mensagem WHERE conversa_id = :c AND conteudo LIKE '%não vale mais%'")
                .param("c", conversa).query(Long.class).single()).isZero();
    }

    @Test
    void oResultadoDaBuscaDeUmTurnoAnteriorNaoVoltaAoModeloNemComODocumentoVigente() {
        long conversa = abrir();
        primeiroTurno(conversa);
        modelo.limpar();
        modelo.texto("Certo.");

        falar(conversa, "Obrigado.");

        // no turno seguinte o histórico leva o aviso e o nome do documento, sem trecho, sem data e sem marcador citável
        List<MensagemDeChat> historico = modelo.pedidos().get(0).mensagens();
        MensagemDeChat antiga = historico.stream().filter(m -> m.papel().equals("tool")).findFirst().orElseThrow();
        Map<String, Object> lido = AtlasJson.ler(antiga.conteudo(), new TypeReference<Map<String, Object>>() {
        });
        assertThat(lido).containsOnlyKeys("tipo", "aviso", "documentos").containsEntry("tipo", "resultado_de_turno_anterior");
        assertThat((String) lido.get("aviso")).contains("busque de novo");
        assertThat(antiga.conteudo()).doesNotContain(PRAZO).doesNotContain("\"marcador\"").doesNotContain("\"trechos\"");
        // a resposta do assistente continua no histórico, com a citação que ela fez
        assertThat(historico).anySatisfy(m -> assertThat(m.conteudo() == null ? "" : m.conteudo()).contains(PRAZO).containsPattern("\\[T[1-9]\\]"));
    }

    @Test
    void umDocumentoSubstituidoPorOutraVersaoTambemEntraNaNota() {
        long conversa = abrir();
        FonteDoTexto fonte = primeiroTurno(conversa).fontes().get(0);
        // o manual v2 passa a SUBSTITUIDO (uma versão mais nova tomou o lugar dele): o estado de agora é o que vale
        jdbc.sql("UPDATE documento SET status = 'SUBSTITUIDO' WHERE id = :id").param("id", fonte.documentoId()).update();
        modelo.limpar();
        modelo.texto("Entendi.");

        falar(conversa, "E agora?");

        assertThat(modelo.pedidos().get(0).mensagens().get(0).conteudo()).contains(NOTA)
                .contains("- Manual de Operações 2027 v2 não vale mais: não use o que foi dito dele.");
        List<FonteDoTexto> fontes = atlas.verConversa(usuario(jdbc, AMARELO), conversa).itens().stream()
                .filter(ItemTexto.class::isInstance).map(ItemTexto.class::cast).flatMap(t -> t.fontes().stream()).toList();
        assertThat(fontes.get(0).substituido()).isTrue();
        assertThat(fontes.get(0).revogado()).isFalse();
    }
}
