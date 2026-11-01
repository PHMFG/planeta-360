package br.com.rbs.portal.atlas.busca;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.Candidato;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.DocumentoVisivel;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.Escopo;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.Pedido;
import br.com.rbs.portal.atlas.busca.BuscaHibrida.ResultadoDaBusca;
import br.com.rbs.portal.atlas.embeddings.ProvedorDeEmbeddings;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [ATLAS] [ISOLAMENTO] A busca só dos documentos {@code GLOBAL} da edição, a do Atlas da RBS (M8): a conversa dela não tem marca nem
 * cota, então nenhum documento {@code COTA} nem {@code PARCERIA} pode sair, por nenhuma das duas camadas: o filtro que o servidor
 * monta no {@code WHERE} e a RLS do {@code atlas_ro}. Cada camada tem o próprio teste. Como a {@link BuscaHibridaTest}, esta classe
 * NÃO é {@code @Transactional}: a busca roda numa transação nova e só enxerga o que está confirmado (o kit de documentos).
 */
@TesteDeIntegracao
class BuscaSoGlobalTest {

    @Autowired
    BuscaHibrida busca;

    @Autowired
    ProvedorDeEmbeddings embeddings;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager gerenteDeTransacao;

    long evento(int edicao) {
        return jdbc.sql("SELECT id FROM evento WHERE edicao = :edicao").param("edicao", edicao).query(Long.class).single();
    }

    Pedido hibrido(Escopo escopo, String consulta) {
        return new Pedido(escopo, consulta, null, BuscaHibrida.K_PADRAO, embeddings.paraConsulta(consulta), embeddings.modelo());
    }

    Pedido soTexto(Escopo escopo, String consulta) {
        return new Pedido(escopo, consulta, null, BuscaHibrida.K_PADRAO, null, null);
    }

    static List<Candidato> tudo(ResultadoDaBusca resultado) {
        List<Candidato> todos = new ArrayList<>(resultado.vetorial());
        resultado.textual().stream().filter(c -> todos.stream().noneMatch(t -> t.trechoId() == c.trechoId())).forEach(todos::add);
        return todos;
    }

    String escopoDoDocumento(long documentoId) {
        return jdbc.sql("SELECT escopo FROM documento WHERE id = :id").param("id", documentoId).query(String.class).single();
    }

    // ---------------------------------------------------------------------
    // O escopo
    // ---------------------------------------------------------------------

    @Test
    void soGlobalSeReconheceEUmEscopoDeMarcaNunca() {
        Escopo global = Escopo.soGlobal(evento(2027));

        assertThat(global.soGlobal()).isTrue();
        assertThat(global.eventoId()).isEqualTo(evento(2027));
        // o escopo de uma parceria de verdade nunca é lido como "só global"
        Escopo amarelo = jdbc.sql("""
                SELECT pa.evento_id, pa.cota_tipo_id, pa.id FROM parceria pa JOIN patrocinador p ON p.id = pa.patrocinador_id
                JOIN evento e ON e.id = pa.evento_id WHERE p.nome_canonico = 'Cliente Amarelo' AND e.edicao = 2027
                """).query((rs, n) -> new Escopo(rs.getLong(1), rs.getInt(2), rs.getLong(3))).single();
        assertThat(amarelo.soGlobal()).isFalse();
    }

    // ---------------------------------------------------------------------
    // Camada 1: o filtro do servidor
    // ---------------------------------------------------------------------

    private static final List<String> CONSULTAS_QUE_ACHARIAM_COTA_E_PARCERIA = List.of(
            "cortesias credenciais prazo de conteúdo plano comercial", "plano comercial cota Master cortesias Camarote Lounge",
            "Girassol Elétrico", "tema do espaço da ativação", "Tempestade Violeta", "anexo da parceria", "horário do espaço",
            "energia total do espaço kVA", "montagem do espaço equipe entrada", "subcota plano comercial entregas", "cortesias");

    @Test
    void aBuscaSoGlobalNuncaDevolveTrechoDeDocumentoDeCotaNemDeParceria() {
        Escopo global = Escopo.soGlobal(evento(2027));
        List<String> falhas = new ArrayList<>();
        int vistos = 0;
        for (String consulta : CONSULTAS_QUE_ACHARIAM_COTA_E_PARCERIA) {
            for (Pedido pedido : List.of(hibrido(global, consulta), soTexto(global, consulta))) {
                for (Candidato c : tudo(busca.buscar(pedido))) {
                    vistos++;
                    String escopo = escopoDoDocumento(c.documentoId());
                    if (!"GLOBAL".equals(escopo)) {
                        falhas.add("\"" + consulta + "\" devolveu " + escopo + ": " + c.documento());
                    }
                    String texto = (c.documento() + " " + c.texto()).toLowerCase();
                    if (texto.contains("girassol") || texto.contains("tempestade violeta")) {
                        falhas.add("\"" + consulta + "\" devolveu um canário de parceria em " + c.documento());
                    }
                }
            }
        }
        assertThat(falhas).isEmpty();
        assertThat(vistos).as("a busca achou trechos: sem isso o teste passaria com uma busca que não acha nada").isGreaterThan(0);
    }

    @Test
    void aBuscaSoGlobalAchaOManualDaEdicao() {
        ResultadoDaBusca resultado = busca.buscar(soTexto(Escopo.soGlobal(evento(2027)), "Até quando preciso informar os geradores na Arena"));

        assertThat(resultado.escolhidos()).anySatisfy(c -> {
            assertThat(c.documento()).isEqualTo("Manual de Operações 2027");
            assertThat(c.versao()).isEqualTo(2);
            assertThat(c.texto()).contains("04/12/2026");
        });
    }

    @Test
    void aEdicaoDe2026SoDevolveOsGlobaisDela() {
        List<Candidato> vistos = tudo(busca.buscar(hibrido(Escopo.soGlobal(evento(2026)), "tarifa de energia por kVA")));

        assertThat(vistos).isNotEmpty().allSatisfy(c -> assertThat(c.documento()).isEqualTo("Manual de Operações 2026"));
        assertThat(tudo(busca.buscar(hibrido(Escopo.soGlobal(evento(2027)), "tarifa de energia por kVA"))))
                .noneSatisfy(c -> assertThat(c.documento()).isEqualTo("Manual de Operações 2026"));
    }

    @Test
    void osDocumentosVisiveisSaoSoOsGlobais() {
        List<DocumentoVisivel> visiveis = busca.documentosVisiveis(Escopo.soGlobal(evento(2027)));

        assertThat(visiveis).isNotEmpty().allSatisfy(d -> assertThat(d.escopo()).isEqualTo("GLOBAL"));
        assertThat(visiveis).extracting(DocumentoVisivel::titulo).contains("Manual de Operações 2027", "FAQ do patrocinador 2027")
                .doesNotContain("Plano comercial · Cota Master 2027", "Plano comercial · Subcota G 2027", "Plano comercial · Subcota 2027",
                        "Anexo da parceria · Cliente Amarelo", "Anexo da parceria · Cliente Roxo");
    }

    // ---------------------------------------------------------------------
    // Camada 2: a RLS sozinha, sem o filtro do servidor
    // ---------------------------------------------------------------------

    @Test
    void aRlsDoAtlasRoComEscopoSoGlobalNaoEnxergaDocumentoDeCotaNemDeParceria() {
        TransactionTemplate leitura = new TransactionTemplate(gerenteDeTransacao);
        leitura.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        leitura.setReadOnly(true);

        List<List<String>> vistos = leitura.execute(estado -> {
            ContextoDeRls.aplicar(jdbc, ContextoDeRls.PAPEL, Escopo.soGlobal(evento(2027)), 8_000);
            // sem nenhum filtro: o que a política deixa ver, nas duas tabelas
            List<String> dosDocumentos = jdbc.sql("SELECT DISTINCT escopo FROM documento").query(String.class).list();
            List<String> dosTrechos = jdbc.sql("SELECT DISTINCT escopo FROM documento_trecho").query(String.class).list();
            return List.of(dosDocumentos, dosTrechos);
        });

        assertThat(vistos.get(0)).as("documentos que a RLS deixa ver").containsExactly("GLOBAL");
        assertThat(vistos.get(1)).as("trechos que a RLS deixa ver").containsExactly("GLOBAL");
        // a base tem documento de cota e de parceria: se a RLS os deixasse passar, eles apareceriam acima
        assertThat(jdbc.sql("SELECT count(DISTINCT escopo) FROM documento").query(Long.class).single()).isEqualTo(3);
    }
}
