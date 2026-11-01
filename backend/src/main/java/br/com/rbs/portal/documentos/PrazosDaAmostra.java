package br.com.rbs.portal.documentos;

import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.prazos.PrazoDtos.CriarPrazoRequisicao;
import br.com.rbs.portal.prazos.PrazoService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Os prazos do Manual de Operações 2027 v2 (o D02 do kit) na demonstração do Atlas (plano do Atlas completo, M5). [ATLAS] Quem chama é
 * o {@code DocumentosDaAmostra}, nos perfis dev e demo, logo depois de indexar o kit: cada prazo nasce da RBS, no escopo da edição
 * inteira, com o documento e a página de onde saiu, e é o que a ferramenta {@code consultar_prazos} devolve.
 *
 * <p><b>Nenhum prazo é inventado.</b> A data, a página e a descrição de cada um vêm do {@code gabarito.json} do kit (o fato dele, com o
 * {@code trecho} que o PDFBox acha na página: o {@code GabaritoDosDocumentosTest} confere); só o título (o nome do marco na tabela 1 do
 * manual) e se o prazo termina às 18h são daqui. O manual diz, na página 3, que "todos os prazos terminam às 18h da data indicada, no
 * horário de Brasília": os prazos de entrega levam a hora 18:00, e o início da montagem (que não é um prazo de entrega) não leva hora.
 * O {@code PrazosDaAmostraTest} confere cada data na página que o prazo cita.
 *
 * <p>Idempotente: o prazo que o documento já tem com o mesmo título (ativo ou desativado) não é criado de novo, então subir a API outra
 * vez não duplica nada, e um prazo que a RBS desativou não ressuscita.
 */
@Component
public class PrazosDaAmostra {

    private static final Logger log = LoggerFactory.getLogger(PrazosDaAmostra.class);
    private static final Pattern DATA = Pattern.compile("(\\d{2})/(\\d{2})/(\\d{4})");
    private static final LocalTime FIM_DO_DIA_DE_PRAZO = LocalTime.of(18, 0);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * Um prazo do manual: {@code fato} é o fato do gabarito (a página e o trecho), {@code dataDoTrecho} qual das datas do trecho é a
     * do prazo (0 é a primeira), {@code titulo} o nome do marco e {@code ate18h} se ele termina às 18h.
     */
    record PrazoDoManual(String fato, int dataDoTrecho, String titulo, boolean ate18h) {
    }

    /** O prazo já lido do gabarito, pronto para o cadastro. */
    record PrazoLido(String fato, String titulo, LocalDate data, LocalTime hora, String descricao, int pagina) {
    }

    /** Os marcos de entrega da tabela 1 do manual (página 3) e a janela de montagem, cada um com o fato de onde sai a página. */
    static final List<PrazoDoManual> DO_MANUAL = List.of(
            new PrazoDoManual("geradores-prazo", 0, "Informar os geradores próprios à produção", true),
            new PrazoDoManual("laudo-tecnico", 0, "Entregar o laudo técnico de estruturas acima de 3 metros", true),
            new PrazoDoManual("seguro-prazo", 0, "Entregar a cópia da apólice de seguro à produção", true),
            new PrazoDoManual("alvara-sanitario", 0, "Entregar o alvará sanitário do fornecedor de alimentos", true),
            new PrazoDoManual("credenciamento-prazo", 0, "Cadastrar os profissionais no Open Staff", true),
            new PrazoDoManual("montagem-janela", 0, "Iniciar a montagem das ativações", false),
            new PrazoDoManual("credenciamento-troca", 0, "Trocar nomes na lista de credenciados, com taxa", true),
            new PrazoDoManual("montagem-janela", 1, "Terminar a montagem das ativações", true));

    private final JdbcClient jdbc;
    private final PrazoService prazos;

    public PrazosDaAmostra(JdbcClient jdbc, PrazoService prazos) {
        this.jdbc = jdbc;
        this.prazos = prazos;
    }

    /**
     * Cadastra, como a RBS, os prazos do manual que o documento ainda não tem. Devolve quantos criou. Sem o D02 no banco (a edição
     * ou o documento não subiu), não faz nada; um prazo que não pôde ser criado é avisado no log, e os outros seguem.
     */
    public int criar(UsuarioAutenticado rbs) {
        KitDeDocumentos.Documento d02 = KitDeDocumentos.por("D02");
        Optional<Long> documento = jdbc.sql("""
                        SELECT d.id FROM documento d JOIN evento e ON e.id = d.evento_id
                        WHERE d.chave = :chave AND d.versao = :versao AND d.escopo = 'GLOBAL' AND e.nome = 'Planeta Atlântida' AND e.edicao = :edicao
                        """)
                .param("chave", d02.chave()).param("versao", d02.versao()).param("edicao", d02.edicao()).query(Long.class).optional();
        if (documento.isEmpty()) {
            log.info("Prazos da amostra: o D02 (Manual de Operações 2027 v2) não está no banco; não criei os prazos.");
            return 0;
        }
        long eventoId = jdbc.sql("SELECT evento_id FROM documento WHERE id = :id").param("id", documento.get()).query(Long.class).single();
        int criados = 0;
        for (PrazoLido p : ler(gabarito())) {
            boolean existe = jdbc.sql("SELECT EXISTS (SELECT 1 FROM prazo WHERE documento_id = :documento AND titulo = :titulo)")
                    .param("documento", documento.get()).param("titulo", p.titulo()).query(Boolean.class).single();
            if (existe) {
                continue;
            }
            try {
                prazos.criar(rbs, eventoId, new CriarPrazoRequisicao("GLOBAL", null, null, p.titulo(), p.data().toString(),
                        p.hora() == null ? null : p.hora().toString(), p.descricao(), documento.get(), p.pagina()), null);
                criados++;
            } catch (RuntimeException falha) {
                log.warn("Prazos da amostra: não criei \"{}\": {}", p.titulo(), falha.getMessage());
            }
        }
        return criados;
    }

    /** Os prazos de {@link #DO_MANUAL} lidos do gabarito: a página do fato, o trecho como descrição e a data que o trecho traz. */
    static List<PrazoLido> ler(JsonNode gabarito) {
        List<PrazoLido> lidos = new ArrayList<>();
        for (PrazoDoManual p : DO_MANUAL) {
            JsonNode fato = fato(gabarito, p.fato());
            String trecho = fato.path("trecho").asString();
            Matcher data = DATA.matcher(trecho);
            LocalDate dia = null;
            for (int i = 0; i <= p.dataDoTrecho(); i++) {
                if (!data.find()) {
                    throw new IllegalStateException("O trecho do fato " + p.fato() + " do gabarito não tem a data de número " + (i + 1));
                }
                dia = LocalDate.of(Integer.parseInt(data.group(3)), Integer.parseInt(data.group(2)), Integer.parseInt(data.group(1)));
            }
            lidos.add(new PrazoLido(p.fato(), p.titulo(), dia, p.ate18h() ? FIM_DO_DIA_DE_PRAZO : null, trecho, fato.path("pagina").asInt()));
        }
        return lidos;
    }

    private static JsonNode fato(JsonNode gabarito, String nome) {
        for (JsonNode fato : gabarito) {
            if (nome.equals(fato.path("fato").asString()) && "D02".equals(fato.path("id").asString())) {
                return fato;
            }
        }
        throw new IllegalStateException("O gabarito não tem o fato " + nome + " do D02");
    }

    static JsonNode gabarito() {
        try {
            return JSON.readTree(new ClassPathResource(KitDeDocumentos.GABARITO).getInputStream());
        } catch (IOException erro) {
            throw new UncheckedIOException("Não consegui ler o gabarito do kit", erro);
        }
    }
}
