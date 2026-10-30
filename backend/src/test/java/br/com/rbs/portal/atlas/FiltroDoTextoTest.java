package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.contexto.AtlasContextoFabrica;
import br.com.rbs.portal.atlas.loop.LoopDoAgente;
import br.com.rbs.portal.atlas.loop.OuvinteDoTurno;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] O filtro do texto em fluxo, com o modelo roteirizado mandando o texto em pedaços: o marcador partido sai resolvido de
 * uma vez, a última palavra espera o espaço (o nome de ferramenta não escapa pela metade), e o que a checagem do chat recusaria
 * (algarismo, marcador que não existe ou sem valor, chave solta) nunca chega à tela: vem o {@code texto_recusado}. O loop roda
 * aqui na thread do teste, com um ouvinte que anota os eventos (o fluxo HTTP de verdade está no AtlasFluxoTest).
 */
@TesteComLlmRoteirizado
@Transactional
class FiltroDoTextoTest {

    @Autowired
    LoopDoAgente loop;

    @Autowired
    AtlasService atlas;

    @Autowired
    AtlasContextoFabrica contextos;

    @Autowired
    ProvedorRoteirizado modelo;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void zerarORoteiro() {
        modelo.limpar();
    }

    /** Anota os eventos do turno como {@code "nome:valor"}. */
    static final class Anotador implements OuvinteDoTurno {

        final List<String> eventos = new ArrayList<>();

        @Override
        public void raciocinio(String pedaco) {
            eventos.add("raciocinio:" + pedaco);
        }

        @Override
        public void etapa(String texto) {
            eventos.add("etapa:" + texto);
        }

        @Override
        public void texto(String pedaco) {
            eventos.add("texto:" + pedaco);
        }

        @Override
        public void textoRecusado() {
            eventos.add("texto_recusado");
        }
    }

    List<String> turno(String pergunta) {
        UsuarioAutenticado marca = usuario(jdbc, AMARELO);
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        long conversa = atlas.abrirConversa(marca, parceria, null, requisicao()).conversaId();
        AtlasContexto contexto = contextos.para(marca, parceria, conversa, requisicao());
        Anotador anotador = new Anotador();
        loop.prepararConversa(contexto, pergunta);
        loop.conduzir(contexto, anotador);
        assertThat(modelo.idasEmFluxo()).as("as idas foram em fluxo").isEqualTo(modelo.pedidos().size());
        return anotador.eventos;
    }

    @Test
    void marcadorPartidoSaiResolvidoEAUltimaPalavraEsperaOEspaco() {
        modelo.textoEmPedacos("Penso nisto.", "A marca ", "{{parc:", "patrocinador", "}} tem ", "tool", "box e ", "relatório.");

        List<String> eventos = turno("Oi");

        assertThat(eventos).containsExactly("raciocinio:Penso ", "raciocinio:nisto.", "texto:A marca ", "texto:Cliente Amarelo tem ",
                "texto:toolbox e ", "texto:relatório.");
    }

    @Test
    void algarismoSoltoParaORepasseEANovaRedacaoVem() {
        modelo.textoEmPedacos(null, "Foram ", "muitas, ", "mais de ", "1", "0 mil.")
                .textoEmPedacos(null, "Foram muitas, ", "segundo {{parc:patrocinador}}.");

        List<String> eventos = turno("Quantas?");

        assertThat(eventos).containsExactly("texto:Foram ", "texto:muitas, ", "texto:mais de ", "texto_recusado", "texto:Foram muitas, ",
                "texto:segundo ", "texto:Cliente Amarelo.");
    }

    @Test
    void marcadorQueNaoExisteOuSemValorEChaveSoltaSaoRecusados() {
        modelo.textoEmPedacos(null, "Veja ", "{{dado:inventado.campo}} ", "agora.")
                .textoEmPedacos(null, "Visitantes: ", "{{dado:ativacao.visitantes_total}} ", "no total.");

        List<String> eventos = turno("Oi");

        // o dado de visitantes ainda não foi informado: no chat, marcador sem valor é recusado como o que não existe
        assertThat(eventos).containsExactly("texto:Veja ", "texto_recusado", "texto:Visitantes: ", "texto_recusado",
                "texto:" + LoopDoAgente.FRASE_FIXA);
    }

    @Test
    void marcadorQueNuncaFechaERecusadoNoFimDaIda() {
        modelo.textoEmPedacos(null, "Para ", "{{parc:patrocinador e fim.")
                .textoEmPedacos(null, "Tudo certo.");

        List<String> eventos = turno("Oi");

        // um pedaço com espaço sai até o espaço; o resto, no fim da ida
        assertThat(eventos).containsExactly("texto:Para ", "texto_recusado", "texto:Tudo ", "texto:certo.");
    }

    @Test
    void semFluxoNadaETransmitido() {
        modelo.texto("Tudo certo.");
        UsuarioAutenticado marca = usuario(jdbc, AMARELO);
        long parceria = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        long conversa = atlas.abrirConversa(marca, parceria, null, requisicao()).conversaId();

        loop.conversar(contextos.para(marca, parceria, conversa, requisicao()), "Oi");

        assertThat(modelo.idasEmFluxo()).isZero();
        assertThat(modelo.pedidos()).hasSize(1);
    }
}
