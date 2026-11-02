package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasDtos.ConversaDto;
import br.com.rbs.portal.atlas.AtlasDtos.FonteDoTexto;
import br.com.rbs.portal.atlas.AtlasDtos.ItemTexto;
import br.com.rbs.portal.atlas.AtlasDtos.MensagemRequisicao;
import br.com.rbs.portal.atlas.FerramentaDeTeste.DocumentoDeTeste;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * [ATLAS] [VALIDACAO] As fontes {@code [R#]} (registro do portal) e {@code [C#]} (conta) que o chat cita, do turno até a releitura
 * (M5, base das fontes): o loop grava {@code REGISTRO} e {@code CALCULO} em {@code atlas_fonte}, e a conversa as devolve em
 * {@code fontes} com o título e a seção que estavam em {@code dados}. No registro que veio de um documento, o {@code documentoId}, a
 * versão e a página também ficam em {@code dados} (o CHECK da tabela proíbe as colunas), e o estado de agora do documento diz se a
 * fonte abre com aviso ou não abre. Quem registra é uma ferramenta de teste; as ferramentas de verdade chegam depois. Não é
 * {@code @Transactional}: o turno confirma de verdade e o que ficou é apagado no fim.
 */
@TesteComLlmRoteirizado
@Import({FerramentaDeTesteConfig.class, EmbeddingsControlavelConfig.class})
class FontesDeRegistroEContaTest {

    private static final String FRASE = "Há 12 cortesias previstas [R1]. A conta deu 10.608,00 [C1].";

    @Autowired
    AtlasService atlas;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    FerramentaDeTeste ferramenta;

    @Autowired
    JdbcClient jdbc;

    private ApoioDoFluxo.Limpeza limpeza;

    @BeforeEach
    void guardarOQueJaExiste() {
        modelo.limpar();
        ferramenta.limpar();
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

    static ItemTexto textoFinal(ConversaDto conversa) {
        List<ItemTexto> textos = conversa.itens().stream().filter(ItemTexto.class::isInstance).map(ItemTexto.class::cast).toList();
        assertThat(textos).as("o turno produziu um texto").isNotEmpty();
        return textos.get(textos.size() - 1);
    }

    DocumentoDeTeste documento(String titulo, int versao) {
        long id = jdbc.sql("SELECT id FROM documento WHERE titulo = :t AND versao = :v").param("t", titulo).param("v", versao)
                .query(Long.class).single();
        return new DocumentoDeTeste(id, versao, 1);
    }

    @Test
    void registroEContaCitadosViramFontesGravadasEDoItem() {
        DocumentoDeTeste origem = documento("Manual de Operações 2027", 2);
        ferramenta.registroEConta(origem);
        long conversa = abrir();
        modelo.chamada(FerramentaDeTeste.NOME, "{}").texto(FRASE);

        ItemTexto texto = textoFinal(falar(conversa, "Quantas cortesias?"));

        assertThat(texto.texto()).isEqualTo(FRASE);
        assertThat(texto.fontes()).hasSize(2);
        FonteDoTexto registro = texto.fontes().get(0);
        assertThat(registro.marcador()).isEqualTo("R1");
        assertThat(registro.tipo()).isEqualTo("REGISTRO");
        assertThat(registro.titulo()).isEqualTo("Cortesias do evento");
        assertThat(registro.secao()).isEqualTo("Cortesias › Lista");
        assertThat(registro.documentoId()).isEqualTo(origem.documentoId());
        assertThat(registro.versao()).isEqualTo(2);
        assertThat(registro.pagina()).isEqualTo(1);
        assertThat(registro.trechoId()).isNull();
        assertThat(registro.substituido()).isFalse();
        assertThat(registro.revogado()).isFalse();
        FonteDoTexto conta = texto.fontes().get(1);
        assertThat(conta.marcador()).isEqualTo("C1");
        assertThat(conta.tipo()).isEqualTo("CALCULO");
        assertThat(conta.titulo()).as("o título da conta é o nome dela").isEqualTo("tarifa");
        assertThat(conta.documentoId()).isNull();
        assertThat(conta.substituido()).isFalse();
        assertThat(conta.revogado()).isFalse();

        // o que o servidor guarda: o CHECK da tabela proíbe as colunas do documento no registro e na conta, que levam tudo em dados
        List<Map<String, Object>> linhas = jdbc.sql("""
                SELECT f.marcador, f.tipo, f.trecho_id, f.documento_id, f.versao, f.pagina, f.registro_entidade, f.registro_id,
                       f.dados ->> 'titulo' AS titulo, f.dados ->> 'secao' AS secao, f.dados ->> 'documentoId' AS documento_em_dados,
                       f.dados ->> 'nome' AS nome
                FROM atlas_fonte f JOIN atlas_mensagem m ON m.id = f.mensagem_id WHERE m.conversa_id = :c ORDER BY f.id
                """).param("c", conversa).query().listOfRows();
        assertThat(linhas).hasSize(2);
        assertThat(linhas.get(0)).containsEntry("marcador", "R1").containsEntry("tipo", "REGISTRO")
                .containsEntry("registro_entidade", "entrega").containsEntry("registro_id", 77L)
                .containsEntry("titulo", "Cortesias do evento").containsEntry("secao", "Cortesias › Lista")
                .containsEntry("documento_em_dados", String.valueOf(origem.documentoId()))
                .containsEntry("documento_id", null).containsEntry("versao", null).containsEntry("pagina", null)
                .containsEntry("trecho_id", null);
        assertThat(linhas.get(1)).containsEntry("marcador", "C1").containsEntry("tipo", "CALCULO").containsEntry("nome", "tarifa")
                .containsEntry("registro_entidade", null).containsEntry("documento_id", null);
        // reler a conversa traz as mesmas fontes
        assertThat(textoFinal(atlas.verConversa(usuario(jdbc, AMARELO), conversa)).fontes()).isEqualTo(texto.fontes());
    }

    @Test
    void fonteSoCitadaVaiAoItemEQuemNaoFoiCitadaNaoEGravada() {
        ferramenta.registroEConta(null);
        long conversa = abrir();
        modelo.chamada(FerramentaDeTeste.NOME, "{}").texto("Há 12 cortesias previstas [R1].");

        ItemTexto texto = textoFinal(falar(conversa, "Quantas cortesias?"));

        assertThat(texto.fontes()).extracting(FonteDoTexto::marcador).containsExactly("R1");
        assertThat(texto.fontes().get(0).documentoId()).as("registro que não veio de documento").isNull();
        assertThat(jdbc.sql("SELECT f.marcador FROM atlas_fonte f JOIN atlas_mensagem m ON m.id = f.mensagem_id WHERE m.conversa_id = :c")
                .param("c", conversa).query(String.class).list()).containsExactly("R1");
    }

    @Test
    void registroDeDocumentoSubstituidoOuRevogadoUsaOEstadoDeAgoraDoDocumento() {
        DocumentoDeTeste substituido = documento("Manual de Operações 2027", 1);
        DocumentoDeTeste revogado = documento("Circular 07/2026 · Pulseiras prata", 1);

        ferramenta.registroEConta(substituido);
        long umaConversa = abrir();
        modelo.chamada(FerramentaDeTeste.NOME, "{}").texto("Há 12 cortesias previstas [R1].");
        FonteDoTexto doSubstituido = textoFinal(falar(umaConversa, "Quantas cortesias?")).fontes().get(0);

        modelo.limpar();
        ferramenta.registroEConta(revogado);
        long outraConversa = abrir();
        modelo.chamada(FerramentaDeTeste.NOME, "{}").texto("Há 12 cortesias previstas [R1].");
        FonteDoTexto doRevogado = textoFinal(falar(outraConversa, "Quantas cortesias?")).fontes().get(0);

        assertThat(doSubstituido.substituido()).isTrue();
        assertThat(doSubstituido.revogado()).isFalse();
        assertThat(doRevogado.revogado()).isTrue();
        assertThat(doRevogado.substituido()).isFalse();
        // o prompt do turno seguinte avisa dos dois documentos que a conversa já citou e que saíram do ar
        modelo.limpar();
        modelo.texto("Certo.");
        falar(umaConversa, "E agora?");
        assertThat(modelo.ultimoPedido().mensagens().get(0).conteudo()).contains("Manual de Operações 2027 v1 não vale mais");
    }
}
