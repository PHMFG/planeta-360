package br.com.rbs.portal.atlas;

import static br.com.rbs.portal.atlas.ApoioDoAtlas.AMARELO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.ROXO;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.requisicao;
import static br.com.rbs.portal.atlas.ApoioDoAtlas.usuario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.rbs.portal.acesso.SessaoDeApi;
import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeRelatorio;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.contexto.AtlasContextoFabrica;
import br.com.rbs.portal.atlas.ferramentas.RegistroDeFerramentas;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.DefinicaoDeFerramenta;
import br.com.rbs.portal.comum.DadosInvalidosException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ATLAS] O registro de ferramentas por estado (plano do Atlas completo, M4, primeira fatia): o que o modelo recebe sem relatório, com
 * ele em montagem e com o cartão aberto; ferramenta que não existe e argumento fora do esquema voltam como erro; e o
 * {@link AtlasContexto} traz a edição e a cota da parceria (a busca nos documentos depende dos dois). O conjunto de ferramentas
 * com o relatório ligado é o do perfil de teste; o do Atlas só chat está em AtlasSoChatTest.
 */
@TesteComLlmRoteirizado
@Transactional
class RegistroDeFerramentasTest {

    @Autowired
    RegistroDeFerramentas registro;

    @Autowired
    AtlasContextoFabrica contextos;

    @Autowired
    AtlasService atlas;

    @Autowired
    JdbcClient jdbc;

    static LinhaDeRelatorio relatorio(String status) {
        return new LinhaDeRelatorio(1L, 1L, 1L, "ATIVACAO_EVENTO", status, null, 1, null, 1L, "Marca", "ADMIN_PATROCINADOR", null, null,
                "Cliente Amarelo");
    }

    static List<String> nomes(List<DefinicaoDeFerramenta> ferramentas) {
        return ferramentas.stream().map(DefinicaoDeFerramenta::nome).toList();
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

    @Test
    void semRelatorioOModeloRecebeAConsultaEAAberturaDoRelatorio() {
        assertThat(nomes(registro.oferecidas(Optional.empty(), true))).containsExactly("parceria_consultar", "buscar_documentos", "contar_dias", "calcular", "gerar_modelo", "relatorio_iniciar");
    }

    @Test
    void comRelatorioEmMontagemRecebeAsOutrasTresEPerdeAAbertura() {
        assertThat(nomes(registro.oferecidas(Optional.of(relatorio("MONTANDO")), true)))
                .containsExactly("parceria_consultar", "buscar_documentos", "contar_dias", "calcular", "gerar_modelo", "relatorio_solicitar_campos", "relatorio_editar", "relatorio_finalizar");
    }

    @Test
    void depoisDeTresCartoesNaoPedeOutro() {
        assertThat(nomes(registro.oferecidas(Optional.of(relatorio("MONTANDO")), false)))
                .containsExactly("parceria_consultar", "buscar_documentos", "contar_dias", "calcular", "gerar_modelo", "relatorio_editar", "relatorio_finalizar");
    }

    @Test
    void comOCartaoAbertoNaoRecebeNenhuma() {
        assertThat(registro.oferecidas(Optional.of(relatorio("AGUARDANDO_RESPOSTA")), true)).isEmpty();
    }

    @Test
    void asDefinicoesVaoComNomeDescricaoEEsquema() {
        registro.oferecidas(Optional.empty(), true).forEach(definicao -> {
            assertThat(definicao.nome()).isNotBlank();
            assertThat(definicao.descricao()).isNotBlank();
            assertThat(definicao.esquema()).contains("\"additionalProperties\":false");
        });
    }

    @Test
    void todoNomeOferecidoExisteNoRegistro() {
        for (Optional<LinhaDeRelatorio> estado : List.of(Optional.<LinhaDeRelatorio>empty(), Optional.of(relatorio("MONTANDO")))) {
            assertThat(registro.nomes()).containsAll(nomes(registro.oferecidas(estado, true)));
        }
    }

    @Test
    void ferramentaQueNaoExisteVoltaComoErroDeFerramenta() {
        AtlasContexto contexto = contexto(AMARELO, "Cliente Amarelo");

        assertThatThrownBy(() -> registro.preparar(contexto, turno(), "apagar_tudo", "{}"))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessage("Não existe a ferramenta apagar_tudo. Use só as ferramentas oferecidas.");
    }

    @Test
    void argumentoForaDoEsquemaEmQualquerFerramentaERecusado() {
        AtlasContexto contexto = contexto(AMARELO, "Cliente Amarelo");

        // parceria_id é o ataque de sempre: nenhuma ferramenta o aceita, em nenhum estado
        for (String ferramenta : List.of("parceria_consultar", "relatorio_iniciar", "gerar_modelo")) {
            var preparo = registro.preparar(contexto, turno(), ferramenta, "{\"parceria_id\": 3}");
            assertThatThrownBy(() -> registro.executar(contexto, turno(), preparo, "call_x"))
                    .isInstanceOf(DadosInvalidosException.class)
                    .satisfies(erro -> assertThat(((DadosInvalidosException) erro).campos()).containsKey("parceria_id"));
        }
    }

    @Test
    void argumentosQueNaoSaoUmObjetoJsonSaoRecusados() {
        AtlasContexto contexto = contexto(AMARELO, "Cliente Amarelo");

        for (String lixo : List.of("[1,2]", "\"texto\"", "{", "não é json", "12")) {
            assertThatThrownBy(() -> registro.preparar(contexto, turno(), "parceria_consultar", lixo))
                    .as(lixo).isInstanceOf(DadosInvalidosException.class)
                    .hasMessage("Os argumentos da ferramenta não são um objeto JSON válido.");
        }
    }

    @Test
    void argumentosVaziosValemComoNenhumArgumento() {
        AtlasContexto contexto = contexto(AMARELO, "Cliente Amarelo");

        for (String vazio : List.of("", "   ", "{}")) {
            var preparo = registro.preparar(contexto, turno(), "parceria_consultar", vazio);
            assertThat(registro.executar(contexto, turno(), preparo, "call_x").resultadoJson()).contains("dados");
        }
    }

    @Test
    void oContextoTrazAEdicaoEACotaDaParceria() {
        AtlasContexto amarelo = contexto(AMARELO, "Cliente Amarelo");
        AtlasContexto roxo = contexto(ROXO, "Cliente Roxo");

        long evento2027 = jdbc.sql("SELECT id FROM evento WHERE nome = 'Planeta Atlântida' AND edicao = 2027").query(Long.class).single();
        assertThat(amarelo.eventoId()).isEqualTo(evento2027);
        assertThat(roxo.eventoId()).isEqualTo(evento2027);
        assertThat(amarelo.cota()).isEqualTo("MASTER");
        assertThat(roxo.cota()).isEqualTo("SUBCOTA_G");
        assertThat(amarelo.cotaTipoId()).isEqualTo(jdbc.sql("SELECT id FROM cota_tipo WHERE codigo = 'MASTER'").query(Integer.class).single());
        assertThat(roxo.cotaTipoId()).isEqualTo(jdbc.sql("SELECT id FROM cota_tipo WHERE codigo = 'SUBCOTA_G'").query(Integer.class).single());
        assertThat(amarelo.cotaTipoId()).isNotEqualTo(roxo.cotaTipoId());
    }

    @Test
    void oContextoComTracoMantemAEdicaoEACota() {
        AtlasContexto contexto = contexto(AMARELO, "Cliente Amarelo");

        AtlasContexto comTraco = contexto.comTraco(br.com.rbs.portal.atlas.diagnostico.Traco.NENHUM);

        assertThat(comTraco.eventoId()).isEqualTo(contexto.eventoId());
        assertThat(comTraco.cotaTipoId()).isEqualTo(contexto.cotaTipoId());
        assertThat(comTraco.parceriaId()).isEqualTo(contexto.parceriaId());
    }
}
