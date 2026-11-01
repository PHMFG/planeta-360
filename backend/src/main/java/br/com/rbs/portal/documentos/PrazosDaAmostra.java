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
 * Os prazos do Manual de Operações 2027 v2 (o D02 do kit) e dos planos comerciais das três cotas (D03, D04 e D05) na demonstração do
 * Atlas (plano do Atlas completo, M5). [ATLAS] Quem chama é o {@code DocumentosDaAmostra}, nos perfis dev e demo, logo depois de
 * indexar o kit: cada prazo nasce da RBS, com o documento e a página de onde saiu, e é o que a ferramenta {@code consultar_prazos}
 * devolve. Os do manual valem para a edição inteira (escopo {@code GLOBAL}); os de cada plano comercial, para a cota dele
 * (escopo {@code COTA}: o documento é da cota, e o prazo não vale para mais gente que o documento).
 *
 * <p><b>Nenhum prazo é inventado.</b> A data, a página e a descrição de cada um vêm do {@code gabarito.json} do kit (o fato dele, com o
 * {@code trecho} que o PDFBox acha na página: o {@code GabaritoDosDocumentosTest} confere); só o título (o nome do marco na tabela 1 do
 * manual, ou o do marco do plano) e se o prazo termina às 18h são daqui. O manual diz, na página 3, que "todos os prazos terminam às
 * 18h da data indicada, no horário de Brasília": os prazos de entrega levam a hora 18:00, e o início da montagem (que não é um prazo
 * de entrega) não leva hora. Prazo relativo ("18 dias úteis após o briefing") não tem data e não entra; o que o plano diz com data
 * entra: o briefing completo e, com ele entregue, o prazo-limite do conteúdo (a segunda data da mesma frase do gabarito), e na Master
 * também o dos convidados das cortesias e o das perguntas da pesquisa. O {@code PrazosDaAmostraTest} confere cada data na página que
 * o prazo cita.
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

    /**
     * Um prazo de um plano comercial: {@code documento} é o id do kit (D03 a D05), {@code fato} o fato do gabarito, {@code dataDoTrecho}
     * qual das datas do trecho é a do prazo (0 é a primeira) e {@code titulo} o nome do marco.
     */
    record PrazoDoPlano(String documento, String fato, int dataDoTrecho, String titulo) {
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

    /**
     * Os prazos com data dos planos comerciais, um fato do gabarito por prazo. O briefing e o prazo-limite do conteúdo do Roxo (D04) e
     * da Verde (D05) saem da mesma frase do gabarito ("Com o briefing completo entregue em 11/11/2026, o prazo-limite de entrega do
     * conteúdo é 03/12/2026"): a primeira data e a segunda. Os prazos relativos ("15 dias úteis após o briefing") ficam de fora.
     */
    static final List<PrazoDoPlano> DOS_PLANOS = List.of(
            new PrazoDoPlano("D03", "master-cortesias-prazo", 0, "Informar no portal os convidados das cortesias"),
            new PrazoDoPlano("D03", "master-briefing-data", 0, "Entregar o briefing completo"),
            new PrazoDoPlano("D03", "master-conteudo-limite", 1, "Prazo-limite de entrega do conteúdo"),
            new PrazoDoPlano("D03", "master-pesquisa-prazo", 0, "Enviar à RBS as três perguntas da pesquisa"),
            new PrazoDoPlano("D04", "g-conteudo-limite", 0, "Entregar o briefing completo"),
            new PrazoDoPlano("D04", "g-conteudo-limite", 1, "Prazo-limite de entrega do conteúdo"),
            new PrazoDoPlano("D05", "s-conteudo-limite", 0, "Entregar o briefing completo"),
            new PrazoDoPlano("D05", "s-conteudo-limite", 1, "Prazo-limite de entrega do conteúdo"));

    private final JdbcClient jdbc;
    private final PrazoService prazos;

    public PrazosDaAmostra(JdbcClient jdbc, PrazoService prazos) {
        this.jdbc = jdbc;
        this.prazos = prazos;
    }

    /**
     * Cadastra, como a RBS, os prazos do manual e dos planos comerciais que o documento ainda não tem. Devolve quantos criou. Sem o
     * documento no banco (a edição ou o documento não subiu), não cria os dele; um prazo que não pôde ser criado é avisado no log, e os
     * outros seguem.
     */
    public int criar(UsuarioAutenticado rbs) {
        JsonNode gabarito = gabarito();
        int criados = 0;
        Optional<Long> manual = documentoDoKit("D02");
        if (manual.isEmpty()) {
            log.info("Prazos da amostra: o D02 (Manual de Operações 2027 v2) não está no banco; não criei os prazos dele.");
        } else {
            for (PrazoLido p : ler(gabarito)) {
                criados += criar(rbs, "GLOBAL", null, manual.get(), p) ? 1 : 0;
            }
        }
        for (String plano : List.of("D03", "D04", "D05")) {
            Optional<Long> documento = documentoDoKit(plano);
            if (documento.isEmpty()) {
                log.info("Prazos da amostra: o {} (plano comercial) não está no banco; não criei os prazos dele.", plano);
                continue;
            }
            String cota = KitDeDocumentos.por(plano).cota();
            for (PrazoLido p : lerDoPlano(gabarito, plano)) {
                criados += criar(rbs, "COTA", cota, documento.get(), p) ? 1 : 0;
            }
        }
        return criados;
    }

    /** O documento do kit (D02 a D05) no banco, da edição e do escopo dele (e da cota, se for de cota); vazio se não subiu. */
    private Optional<Long> documentoDoKit(String id) {
        KitDeDocumentos.Documento kit = KitDeDocumentos.por(id);
        return jdbc.sql("""
                        SELECT d.id FROM documento d JOIN evento e ON e.id = d.evento_id
                        WHERE d.chave = :chave AND d.versao = :versao AND d.escopo = :escopo AND e.nome = 'Planeta Atlântida' AND e.edicao = :edicao
                          AND (CAST(:cota AS text) IS NULL OR d.cota_tipo_id = (SELECT id FROM cota_tipo WHERE codigo = :cota))
                        """)
                .param("chave", kit.chave()).param("versao", kit.versao()).param("escopo", kit.escopo()).param("edicao", kit.edicao())
                .param("cota", kit.cota()).query(Long.class).optional();
    }

    /** Cria o prazo se o documento ainda não o tem (pelo título, ativo ou desativado). Verdadeiro se criou. */
    private boolean criar(UsuarioAutenticado rbs, String escopo, String cota, long documento, PrazoLido p) {
        boolean existe = jdbc.sql("SELECT EXISTS (SELECT 1 FROM prazo WHERE documento_id = :documento AND titulo = :titulo)")
                .param("documento", documento).param("titulo", p.titulo()).query(Boolean.class).single();
        if (existe) {
            return false;
        }
        long eventoId = jdbc.sql("SELECT evento_id FROM documento WHERE id = :id").param("id", documento).query(Long.class).single();
        try {
            prazos.criar(rbs, eventoId, new CriarPrazoRequisicao(escopo, cota, null, p.titulo(), p.data().toString(),
                    p.hora() == null ? null : p.hora().toString(), p.descricao(), documento, p.pagina()), null);
            return true;
        } catch (RuntimeException falha) {
            log.warn("Prazos da amostra: não criei \"{}\": {}", p.titulo(), falha.getMessage());
            return false;
        }
    }

    /** Os prazos de {@link #DO_MANUAL} lidos do gabarito: a página do fato, o trecho como descrição e a data que o trecho traz. */
    static List<PrazoLido> ler(JsonNode gabarito) {
        List<PrazoLido> lidos = new ArrayList<>();
        for (PrazoDoManual p : DO_MANUAL) {
            JsonNode fato = fato(gabarito, p.fato());
            String trecho = fato.path("trecho").asString();
            LocalDate dia = dataDoTrecho(trecho, p.dataDoTrecho(), p.fato());
            lidos.add(new PrazoLido(p.fato(), p.titulo(), dia, p.ate18h() ? FIM_DO_DIA_DE_PRAZO : null, trecho, fato.path("pagina").asInt()));
        }
        return lidos;
    }

    /** Os prazos de {@link #DOS_PLANOS} do plano {@code documento} (D03, D04 ou D05), lidos do gabarito como os do manual. */
    static List<PrazoLido> lerDoPlano(JsonNode gabarito, String documento) {
        List<PrazoLido> lidos = new ArrayList<>();
        for (PrazoDoPlano p : DOS_PLANOS) {
            if (!p.documento().equals(documento)) {
                continue;
            }
            JsonNode fato = fato(gabarito, p.fato(), documento);
            String trecho = fato.path("trecho").asString();
            lidos.add(new PrazoLido(p.fato(), p.titulo(), dataDoTrecho(trecho, p.dataDoTrecho(), p.fato()), FIM_DO_DIA_DE_PRAZO, trecho,
                    fato.path("pagina").asInt()));
        }
        return lidos;
    }

    /** A data de número {@code indice} (0 é a primeira) do trecho, no formato {@code dd/MM/aaaa}. */
    private static LocalDate dataDoTrecho(String trecho, int indice, String fato) {
        Matcher data = DATA.matcher(trecho);
        LocalDate dia = null;
        for (int i = 0; i <= indice; i++) {
            if (!data.find()) {
                throw new IllegalStateException("O trecho do fato " + fato + " do gabarito não tem a data de número " + (i + 1));
            }
            dia = LocalDate.of(Integer.parseInt(data.group(3)), Integer.parseInt(data.group(2)), Integer.parseInt(data.group(1)));
        }
        return dia;
    }

    private static JsonNode fato(JsonNode gabarito, String nome) {
        return fato(gabarito, nome, "D02");
    }

    private static JsonNode fato(JsonNode gabarito, String nome, String documento) {
        for (JsonNode fato : gabarito) {
            if (nome.equals(fato.path("fato").asString()) && documento.equals(fato.path("id").asString())) {
                return fato;
            }
        }
        throw new IllegalStateException("O gabarito não tem o fato " + nome + " do " + documento);
    }

    static JsonNode gabarito() {
        try {
            return JSON.readTree(new ClassPathResource(KitDeDocumentos.GABARITO).getInputStream());
        } catch (IOException erro) {
            throw new UncheckedIOException("Não consegui ler o gabarito do kit", erro);
        }
    }
}
