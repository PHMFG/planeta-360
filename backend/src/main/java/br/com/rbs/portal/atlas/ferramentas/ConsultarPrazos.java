package br.com.rbs.portal.atlas.ferramentas;

import static br.com.rbs.portal.atlas.ferramentas.Argumentos.desconhecidos;
import static br.com.rbs.portal.atlas.ferramentas.Argumentos.exigirSemErros;

import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno.Fonte;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.ferramentas.RegistrosDoPortal.Prazo;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.DefinicaoDeFerramenta;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@code consultar_prazos(proximos_dias?, incluir_vencidos?)}: o que a parceria precisa cumprir e quando (plano do Atlas completo, M5;
 * ferramenta 5 do quadro). [ATLAS] [ISOLAMENTO] [PERMISSAO]
 * <ul>
 *   <li><b>De onde vêm os prazos:</b> a tabela {@code prazo} (os da edição da parceria, os da cota dela e os dela, ativos) e o prazo
 *       das entregas de aba aberta que ainda não acabaram (nem aprovadas nem não aplicáveis). Prazo relativo ao evento não existe:
 *       sem data, não sai.</li>
 *   <li><b>A janela:</b> {@code proximos_dias} de 1 a 180 (padrão 30); vencido, só com {@code incluir_vencidos}. O dia de hoje é o
 *       de Brasília do turno ({@link FontesDoTurno#hoje()}, o mesmo do prompt) e {@code dias_restantes} é calculado no banco.</li>
 *   <li><b>O documento de origem manda:</b> o prazo cujo documento não está {@code INDEXADO}, ou que a parceria não vê, não sai.
 *       Revogar ou substituir um documento vale na chamada seguinte, sem cache.</li>
 *   <li><b>Até {@value #LIMITE} linhas</b>, por data; passando disso o resultado leva {@code total} e {@code truncado}. Cada linha vira
 *       uma fonte {@code [R#]} (entidade {@code prazo}, ou {@code entrega_prazo} para o prazo de uma entrega) com a data, a hora, os
 *       dias que faltam e a origem; as que vêm de um documento levam o {@code documentoId}, a {@code versao} e a {@code pagina} em
 *       {@code dados}, e a tela abre o documento nessa página.</li>
 *   <li>A quantidade de prazos da consulta vale como número da resposta, como em {@code listar_entregas}.</li>
 * </ul>
 * Nenhum argumento é parceria: um {@code parceria_id} do modelo é argumento desconhecido e volta como erro.
 */
@Component
public class ConsultarPrazos implements Ferramenta {

    public static final String NOME = "consultar_prazos";
    public static final int LIMITE = 40;
    static final int PADRAO_DE_DIAS = 30;
    static final int MAXIMO_DE_DIAS = 180;
    private static final Set<String> ARGUMENTOS = Set.of("proximos_dias", "incluir_vencidos");

    private static final DefinicaoDeFerramenta DEFINICAO = new DefinicaoDeFerramenta(NOME,
            "Lista os prazos desta parceria: os da edição, os da cota dela, os dela e o prazo das entregas que ainda não acabaram, "
                    + "por data, até 40 linhas. proximos_dias (1 a 180, padrão 30) é a janela a partir de hoje; incluir_vencidos traz "
                    + "também os que já passaram (dias_restantes negativo). Cada prazo vem com o marcador (R1, R2...), a data, a hora "
                    + "(quando tem), os dias que faltam e a origem (o documento e a página, ou a entrega): cite o marcador entre "
                    + "colchetes, como [R1], logo depois da afirmação, e copie datas exatamente como estão. Prazo de documento que "
                    + "saiu do ar não aparece. Não conte dias de cabeça: para somar dias ou achar uma data, use contar_dias.",
            """
            {"type":"object","properties":{
              "proximos_dias":{"type":"integer","minimum":1,"maximum":180,"description":"Janela a partir de hoje, em dias. Padrão 30."},
              "incluir_vencidos":{"type":"boolean","description":"Verdadeiro para trazer também os prazos que já passaram. Padrão falso."}},
             "additionalProperties":false}""");

    private final RegistrosDoPortal registros;
    private final VerificadorDeAcesso verificador;

    public ConsultarPrazos(RegistrosDoPortal registros, VerificadorDeAcesso verificador) {
        this.registros = registros;
        this.verificador = verificador;
    }

    @Override
    public String nome() {
        return NOME;
    }

    @Override
    public DefinicaoDeFerramenta definicao() {
        return DEFINICAO;
    }

    @Override
    public int ordem() {
        return 26;
    }

    @Override
    public boolean oferecida(EstadoDaConversa estado) {
        return !estado.cartaoAberto();
    }

    @Override
    public Execucao executar(AtlasContexto contexto, FontesDoTurno turno, Map<String, Object> argumentos, String chamadaId,
                             Object preparado) {
        Map<String, String> erros = new LinkedHashMap<>();
        desconhecidos(argumentos, ARGUMENTOS, erros);
        int proximosDias = proximosDias(argumentos, erros);
        boolean incluirVencidos = booleano(argumentos, "incluir_vencidos", erros);
        exigirSemErros(erros);
        verificador.exigirParceria(contexto.usuario(), contexto.parceriaId());

        List<Prazo> prazos = registros.prazos(contexto.parceriaId(), turno.hoje(), proximosDias, incluirVencidos, LIMITE);
        List<Map<String, Object>> linhas = new ArrayList<>();
        for (Prazo prazo : prazos) {
            linhas.add(registro(turno, prazo));
        }
        long total = prazos.isEmpty() ? 0 : prazos.get(0).total();
        turno.adicionarNumeroLivre(String.valueOf(total));

        Map<String, Object> extras = new LinkedHashMap<>();
        String observacao = null;
        if (total > LIMITE) {
            extras.put("total", total);
            extras.put("truncado", true);
            observacao = "Estes são os " + LIMITE + " primeiros de " + total + " prazos, por data. Peça uma janela menor para ver os outros.";
        } else if (linhas.isEmpty()) {
            observacao = "Nenhum prazo desta parceria cai nessa janela" + (incluirVencidos ? "." : " (os vencidos ficaram de fora).");
        }
        return Execucao.resultado(ResultadoDeRegistros.montar(null, observacao, extras, linhas));
    }

    /** Um prazo vira o registro do resultado e a fonte {@code [R#]} do turno. */
    private static Map<String, Object> registro(FontesDoTurno turno, Prazo p) {
        boolean deEntrega = "entrega".equals(p.tipo());
        String data = ResultadoDeRegistros.dia(p.data());
        String hora = p.hora() == null ? null : ResultadoDeRegistros.HORA.format(p.hora());
        StringBuilder texto = new StringBuilder(p.titulo()).append(". Prazo: ").append(data);
        if (hora != null) {
            texto.append(" às ").append(hora);
        }
        texto.append(". ").append(quantoFalta(p.diasRestantes())).append('.');
        if (p.descricao() != null && !p.descricao().isBlank()) {
            texto.append(' ').append(p.descricao().strip());
            if (!p.descricao().strip().endsWith(".")) {
                texto.append('.');
            }
        }
        Map<String, Object> origem = null;
        Map<String, Object> dados = new LinkedHashMap<>();
        if (deEntrega) {
            origem = new LinkedHashMap<>();
            origem.put("entrega", p.titulo());
            origem.put("aba", p.abaNome());
            texto.append(" Origem: entrega da aba ").append(p.abaNome()).append('.');
        } else if (p.documentoId() != null) {
            origem = new LinkedHashMap<>();
            origem.put("documento", p.documentoTitulo());
            origem.put("versao", p.documentoVersao());
            dados.put("documentoId", p.documentoId());
            dados.put("versao", p.documentoVersao());
            texto.append(" Origem: ").append(p.documentoTitulo()).append(", versão ").append(p.documentoVersao());
            if (p.pagina() != null) {
                origem.put("pagina", p.pagina());
                dados.put("pagina", p.pagina());
                texto.append(", página ").append(p.pagina());
            }
            texto.append('.');
        }
        Fonte fonte = turno.registrarRegistro(deEntrega ? "entrega_prazo" : "prazo", p.id(), p.titulo(), deEntrega ? p.abaNome() : "Prazos",
                texto.toString(), dados);
        Map<String, Object> registro = new LinkedHashMap<>();
        registro.put("marcador", fonte.marcador());
        registro.put("titulo", p.titulo());
        registro.put("data", data);
        registro.put("hora", hora);
        registro.put("dias_restantes", p.diasRestantes());
        registro.put("descricao", p.descricao() == null || p.descricao().isBlank() ? null : p.descricao().strip());
        registro.put("origem", origem);
        return registro;
    }

    /** Quanto falta, em palavras, para a fonte dizer o número que a resposta pode citar. */
    static String quantoFalta(int dias) {
        if (dias == 0) {
            return "Vence hoje";
        }
        if (dias > 0) {
            return dias == 1 ? "Falta 1 dia" : "Faltam " + dias + " dias";
        }
        return dias == -1 ? "Venceu há 1 dia" : "Venceu há " + -dias + " dias";
    }

    // ---------------------------------------------------------------------
    // Os argumentos
    // ---------------------------------------------------------------------

    private static int proximosDias(Map<String, Object> argumentos, Map<String, String> erros) {
        Object valor = argumentos.get("proximos_dias");
        if (valor == null) {
            return PADRAO_DE_DIAS;
        }
        boolean inteiro = valor instanceof Integer || valor instanceof Long || valor instanceof Short || valor instanceof Byte
                || valor instanceof BigInteger;
        if (!inteiro || (valor instanceof BigInteger grande && grande.bitLength() > 31) || ((Number) valor).longValue() < 1
                || ((Number) valor).longValue() > MAXIMO_DE_DIAS) {
            erros.put("proximos_dias", "proximos_dias tem de ser um número inteiro de 1 a " + MAXIMO_DE_DIAS + ".");
            return PADRAO_DE_DIAS;
        }
        return ((Number) valor).intValue();
    }

    private static boolean booleano(Map<String, Object> argumentos, String nome, Map<String, String> erros) {
        Object valor = argumentos.get(nome);
        if (valor == null) {
            return false;
        }
        if (!(valor instanceof Boolean certo)) {
            erros.put(nome, nome + " tem de ser verdadeiro ou falso.");
            return false;
        }
        return certo;
    }
}
