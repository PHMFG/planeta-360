package br.com.rbs.portal.atlas.ferramentas;

import static br.com.rbs.portal.atlas.ferramentas.Argumentos.desconhecidos;
import static br.com.rbs.portal.atlas.ferramentas.Argumentos.exigirSemErros;
import static br.com.rbs.portal.atlas.ferramentas.Argumentos.texto;

import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno.Fonte;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno.Tipo;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.ferramentas.MidiaDoPortal.Filtro;
import br.com.rbs.portal.atlas.ferramentas.MidiaDoPortal.Linha;
import br.com.rbs.portal.atlas.ferramentas.MidiaDoPortal.Total;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.DefinicaoDeFerramenta;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@code consultar_midia()}: os números da base de mídia da parceria da conversa (plano da base da RBS, AC1; a mesma regra e os mesmos
 * números da rota 105). [ATLAS] [ISOLAMENTO] [PERMISSAO]
 * <ul>
 *   <li><b>A parceria vem do {@link AtlasContexto}</b>, nunca de argumento: um {@code parceria} ou {@code parceria_id} do modelo é
 *       argumento desconhecido e volta como erro. Só a aba Mídia aberta (conferida no banco a cada chamada) tem números; fechada ou
 *       oculta, o resultado vem vazio, como no {@code listar_entregas}.</li>
 *   <li><b>O recorte por mês é das cotas Master</b> (e da RBS, na conversa dela). Nas outras cotas, {@code mes} volta como erro
 *       {@code campos.mes} = "O recorte por mês é das cotas Master.", e as linhas nunca trazem mês.</li>
 *   <li><b>Os filtros</b> {@code veiculo} (igual, sem caixa nem acento), {@code formato} (contém, sem caixa nem acento, também no
 *       espaço ou programa), {@code tipoMidia} e {@code unidade} são opcionais. Sem mês, cada linha do plano vem uma vez, somando os
 *       meses; com mês, só o mês pedido, e a linha do plano sem exibição no mês continua com o contratado.</li>
 *   <li><b>Os totais são por unidade e nunca somam unidades diferentes.</b> Até {@value #LIMITE} linhas; passando disso, o resultado
 *       leva {@code total} e {@code truncado}. Cada linha e cada total vira uma fonte {@code [R#]} (entidade {@code aproveitamento} na
 *       linha do plano e {@code veiculacao} no total).</li>
 *   <li><b>A origem importada</b>: quando a veiculação veio de uma importação ({@code veiculacao.importacao_linha_id}), a fonte leva
 *       o nome do arquivo e o localizador da origem (o campo {@code quantidade} de {@code importacao_linha.origem}).</li>
 * </ul>
 */
@Component
public class ConsultarMidia implements Ferramenta {

    public static final String NOME = "consultar_midia";
    public static final int LIMITE = 40;
    /** O recorte por mês que só a cota Master (e a RBS) pode pedir. */
    public static final String MES_SO_DA_MASTER = "O recorte por mês é das cotas Master.";
    static final Pattern MES = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");
    private static final Set<String> ARGUMENTOS_DA_MARCA = Set.of("veiculo", "formato", "mes", "tipoMidia", "unidade");
    private static final Set<String> ARGUMENTOS_DA_RBS = Set.of("veiculo", "formato", "mes", "tipoMidia", "unidade", "parceria",
            "cota");
    private static final Set<String> TIPOS_DE_MIDIA = Set.of("ASSINADA", "PROPRIA", "MERCHANDISING", "BRANDED", "CONTEUDO_DE_MARCA");
    private static final Set<String> UNIDADES = Set.of("INSERCAO", "CPM", "APLICACAO");
    private static final Set<String> COTAS = Set.of("MASTER", "SUBCOTA_G", "SUBCOTA");
    private static final Map<String, String> UNIDADES_NA_TELA = Map.of("INSERCAO", "Inserções", "CPM", "CPM", "APLICACAO", "Aplicações");
    private static final Map<String, String> TIPOS_NA_TELA = Map.of("ASSINADA", "assinada", "PROPRIA", "própria", "MERCHANDISING",
            "merchandising", "BRANDED", "branded", "CONTEUDO_DE_MARCA", "conteúdo de marca");

    private static final DefinicaoDeFerramenta DEFINICAO = new DefinicaoDeFerramenta(NOME,
            "Consulta a mídia desta parceria: o contratado no plano e o que já foi exibido, por veículo e formato, com a entrega em "
                    + "percentual e o impacto. Filtros opcionais: veiculo (igual), formato (contém, também no espaço ou programa), "
                    + "tipoMidia, unidade e mes (AAAA-MM; só as cotas Master recortam por mês). Sem mês, cada linha do plano soma todos "
                    + "os meses e vem uma vez. Devolve os totais por unidade (inserção, CPM e aplicação nunca se somam) e até 40 "
                    + "linhas. Cada linha e cada total vem com o marcador (R1, R2...): cite o marcador entre colchetes, como [R1], "
                    + "logo depois da afirmação, e copie os números exatamente como estão.",
            """
            {"type":"object","properties":{
              "veiculo":{"type":"string","maxLength":80,"description":"Opcional: só este veículo, sem caixa nem acento."},
              "formato":{"type":"string","maxLength":160,"description":"Opcional: o formato contém este texto, também no espaço ou programa."},
              "mes":{"type":"string","pattern":"^\\\\d{4}-(0[1-9]|1[0-2])$","description":"Opcional (só cotas Master): o mês no formato AAAA-MM."},
              "tipoMidia":{"type":"string","enum":["ASSINADA","PROPRIA","MERCHANDISING","BRANDED","CONTEUDO_DE_MARCA"],
                           "description":"Opcional: só este tipo de mídia."},
              "unidade":{"type":"string","enum":["INSERCAO","CPM","APLICACAO"],
                         "description":"Opcional: só esta unidade."}},
             "additionalProperties":false}""");

    /**
     * O que o modelo da RBS lê (AC2): a mídia de todas as marcas da edição, com a marca em cada linha e os filtros de parceria e
     * cota.
     */
    private static final DefinicaoDeFerramenta DEFINICAO_DA_RBS = new DefinicaoDeFerramenta(NOME,
            "Consulta a mídia das marcas da edição: o contratado no plano e o que já foi exibido, por marca, veículo e formato, com a "
                    + "entrega em percentual e o impacto. Filtros opcionais: parceria (nome do patrocinador sem caixa nem acento, ou "
                    + "id), cota (MASTER, SUBCOTA_G ou SUBCOTA), veiculo, formato, tipoMidia, unidade e mes (AAAA-MM). Sem parceria, lê "
                    + "todas as marcas da edição. Sem mês, cada linha do plano soma todos os meses e vem uma vez. Devolve os totais por "
                    + "unidade (inserção, CPM e aplicação nunca se somam) e até 40 linhas, cada uma com a marca. Cada linha e cada total "
                    + "vem com o marcador (R1, R2...): cite o marcador entre colchetes, como [R1], logo depois da afirmação, e copie "
                    + "os números exatamente como estão.",
            """
            {"type":"object","properties":{
              "parceria":{"type":"string","maxLength":120,
                          "description":"Opcional: só esta marca, pelo nome do patrocinador (sem caixa nem acento) ou pelo id."},
              "cota":{"type":"string","enum":["MASTER","SUBCOTA_G","SUBCOTA"],"description":"Opcional: só as marcas desta cota."},
              "veiculo":{"type":"string","maxLength":80,"description":"Opcional: só este veículo, sem caixa nem acento."},
              "formato":{"type":"string","maxLength":160,"description":"Opcional: o formato contém este texto, também no espaço ou programa."},
              "mes":{"type":"string","pattern":"^\\\\d{4}-(0[1-9]|1[0-2])$","description":"Opcional: o mês no formato AAAA-MM."},
              "tipoMidia":{"type":"string","enum":["ASSINADA","PROPRIA","MERCHANDISING","BRANDED","CONTEUDO_DE_MARCA"],
                           "description":"Opcional: só este tipo de mídia."},
              "unidade":{"type":"string","enum":["INSERCAO","CPM","APLICACAO"],
                         "description":"Opcional: só esta unidade."}},
             "additionalProperties":false}""");

    private final MidiaDoPortal midia;
    private final RegistrosDoPortal registros;
    private final VerificadorDeAcesso verificador;

    public ConsultarMidia(MidiaDoPortal midia, RegistrosDoPortal registros, VerificadorDeAcesso verificador) {
        this.midia = midia;
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
    public DefinicaoDeFerramenta definicaoDaRbs() {
        return DEFINICAO_DA_RBS;
    }

    @Override
    public boolean daRbs() {
        return true;
    }

    @Override
    public int ordem() {
        return 23;
    }

    @Override
    public boolean oferecida(EstadoDaConversa estado) {
        return !estado.cartaoAberto();
    }

    @Override
    public Execucao executar(AtlasContexto contexto, FontesDoTurno turno, Map<String, Object> argumentos, String chamadaId,
                             Object preparado) {
        boolean daRbs = contexto.daRbs();
        Map<String, String> erros = new LinkedHashMap<>();
        desconhecidos(argumentos, daRbs ? ARGUMENTOS_DA_RBS : ARGUMENTOS_DA_MARCA, erros);
        String veiculo = limpo(texto(argumentos, "veiculo", false, erros));
        String formato = limpo(texto(argumentos, "formato", false, erros));
        String mes = limpo(texto(argumentos, "mes", false, erros));
        String tipoMidia = limpo(texto(argumentos, "tipoMidia", false, erros));
        String unidade = limpo(texto(argumentos, "unidade", false, erros));
        String parceria = daRbs ? limpo(texto(argumentos, "parceria", false, erros)) : null;
        String cota = daRbs ? limpo(texto(argumentos, "cota", false, erros)) : null;
        LocalDate primeiroDiaDoMes = null;
        if (mes != null) {
            if (!MES.matcher(mes).matches()) {
                erros.put("mes", "Use o mês no formato AAAA-MM.");
            } else {
                primeiroDiaDoMes = LocalDate.parse(mes + "-01");
            }
        }
        if (tipoMidia != null && !TIPOS_DE_MIDIA.contains(tipoMidia)) {
            erros.put("tipoMidia", "Tipo de mídia inválido. Use " + String.join(", ", TIPOS_DE_MIDIA.stream().sorted().toList())
                    + ", ou não informe o tipo.");
        }
        if (unidade != null && !UNIDADES.contains(unidade)) {
            erros.put("unidade", "Unidade inválida. Use " + String.join(", ", UNIDADES.stream().sorted().toList())
                    + ", ou não informe a unidade.");
        }
        if (cota != null && !COTAS.contains(cota)) {
            erros.put("cota", "Cota inválida. Use " + String.join(", ", COTAS.stream().sorted().toList()) + ", ou não informe a cota.");
        }
        if (primeiroDiaDoMes != null && !daRbs && !"MASTER".equals(contexto.cota())) {
            erros.put("mes", MES_SO_DA_MASTER);
        }
        exigirSemErros(erros);

        if (daRbs) {
            Long parceriaId = null;
            if (parceria != null) {
                parceriaId = registros.parceriaDaEdicao(contexto.eventoId(), parceria).orElseThrow(() -> new DadosInvalidosException(
                        "Os argumentos da ferramenta estão errados.",
                        Map.of("parceria", "Não encontramos esta parceria nesta edição.")));
            }
            return consultar(contexto, turno, new Filtro(contexto.eventoId(), parceriaId, cota, primeiroDiaDoMes, veiculo, formato,
                    tipoMidia, unidade), true, true);
        }

        verificador.exigirParceria(contexto.usuario(), contexto.parceriaId());
        if (registros.abaOculta(contexto.parceriaId(), "MIDIA")) {
            return vazio("Não há números de mídia para consultar.");
        }
        if (!registros.abaAberta(contexto.parceriaId(), "MIDIA")) {
            String nomeDaAba = registros.abas().getOrDefault("MIDIA", "Mídia");
            return vazio("A aba " + nomeDaAba + " está fechada para esta parceria: não há números de mídia para consultar.");
        }
        // O campo mes existe para as cotas que recortam por mês (e para a RBS): sem filtro, vem nulo, como na rota 105.
        boolean comMes = "MASTER".equals(contexto.cota());
        return consultar(contexto, turno, new Filtro(null, contexto.parceriaId(), null, primeiroDiaDoMes, veiculo, formato, tipoMidia,
                unidade), false, comMes);
    }

    /**
     * Roda a consulta e monta o resultado. {@code daRbs} muda o que a resposta diz da marca em cada linha; {@code comMes} diz se a
     * linha leva o campo {@code mes} (nulo quando não houve recorte por mês).
     */
    Execucao consultar(AtlasContexto contexto, FontesDoTurno turno, Filtro filtro, boolean daRbs, boolean comMes) {
        List<Total> totais = midia.totais(filtro);
        List<Linha> linhas = midia.linhas(filtro, LIMITE);
        long total = linhas.isEmpty() ? 0 : linhas.get(0).total();
        turno.adicionarNumeroLivre(String.valueOf(total));

        List<Map<String, Object>> registrosDoTotal = new ArrayList<>();
        for (Total totalDaUnidade : totais) {
            registrosDoTotal.add(registroDoTotal(turno, totalDaUnidade));
        }
        List<Map<String, Object>> registrosDaLinha = new ArrayList<>();
        for (Linha linha : linhas) {
            registrosDaLinha.add(registroDaLinha(turno, linha, daRbs, comMes));
        }

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("totais", registrosDoTotal);
        String observacao = null;
        if (total > LIMITE) {
            extras.put("total", total);
            extras.put("truncado", true);
            observacao = "Estas são as " + LIMITE + " primeiras de " + total + " linhas de mídia. Filtre por veículo, formato, tipo ou "
                    + "mês para ver as outras.";
        } else if (registrosDaLinha.isEmpty()) {
            boolean comFiltro = filtro.veiculo() != null || filtro.formato() != null || filtro.tipoMidia() != null
                    || filtro.unidade() != null || filtro.mes() != null;
            observacao = comFiltro ? "Nenhuma linha de mídia atende a esses filtros."
                    : (daRbs ? "Esta edição não tem linha de mídia no plano." : "Esta parceria não tem linha de mídia no plano.");
        }
        return Execucao.resultado(ResultadoDeRegistros.montar(null, observacao, extras, registrosDaLinha));
    }

    /** O resultado sem nenhuma linha: sem registros, sem totais e com a frase que diz por quê. */
    private static Execucao vazio(String observacao) {
        return Execucao.resultado(ResultadoDeRegistros.montar(null, observacao, Map.of("totais", List.of()), List.of()));
    }

    /** Uma linha vira o registro do resultado e a fonte {@code [R#]} do turno. */
    private static Map<String, Object> registroDaLinha(FontesDoTurno turno, Linha linha, boolean daRbs, boolean comMes) {
        StringBuilder texto = new StringBuilder(daRbs ? "Mídia da marca " + linha.patrocinador() + ": " : "Mídia: ")
                .append(linha.veiculo()).append(", ").append(linha.formato());
        if (linha.espacoPrograma() != null && !linha.espacoPrograma().isBlank()) {
            texto.append(" (").append(linha.espacoPrograma()).append(')');
        }
        if (linha.mes() != null) {
            texto.append(", mês ").append(linha.mes());
        }
        texto.append(". Previsto: ").append(quantidade(linha.previsto())).append(' ').append(unidadeNaTela(linha.unidade()))
                .append(". Exibido: ").append(quantidade(linha.exibido())).append('.');
        if (linha.percentual() != null) {
            texto.append(" Entrega: ").append(quantidade(linha.percentual())).append("%.");
        }
        texto.append(linha.impacto() == null ? " Impacto não informado." : " Impacto: " + quantidade(linha.impacto()) + ".");
        if (linha.arquivo() != null) {
            texto.append(" Origem: ").append(linha.arquivo());
            if (linha.localizador() != null) {
                texto.append(", ").append(linha.localizador());
            }
            texto.append('.');
        }

        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("unidade", linha.unidade());
        dados.put("previsto", linha.previsto());
        dados.put("exibido", linha.exibido());
        if (linha.percentual() != null) {
            dados.put("percentual", linha.percentual());
        }
        if (linha.impacto() != null) {
            dados.put("impacto", linha.impacto());
        }
        if (linha.mes() != null) {
            dados.put("mes", linha.mes());
        }
        if (linha.arquivo() != null) {
            dados.put("arquivo", linha.arquivo());
        }
        if (linha.localizador() != null) {
            dados.put("localizador", linha.localizador());
        }
        // A mesma linha do plano pode voltar com outro recorte (outro mês, outro filtro): o marcador é novo a cada resultado, para a
        // fonte citada ser sempre a do número que o modelo acabou de ver.
        Fonte fonte = registrar(turno, "aproveitamento", linha.aproveitamentoId(), linha.veiculo() + " · " + linha.formato(),
                "Mídia e conteúdo", texto.toString(), dados);

        Map<String, Object> registro = new LinkedHashMap<>();
        registro.put("marcador", fonte.marcador());
        if (daRbs) {
            Map<String, Object> parceria = new LinkedHashMap<>();
            parceria.put("id", linha.parceriaId());
            parceria.put("patrocinador", linha.patrocinador());
            parceria.put("cota", linha.cota());
            registro.put("parceria", parceria);
        }
        registro.put("aproveitamentoId", linha.aproveitamentoId());
        registro.put("veiculo", linha.veiculo());
        registro.put("formato", linha.formato());
        if (linha.espacoPrograma() != null && !linha.espacoPrograma().isBlank()) {
            registro.put("espacoPrograma", linha.espacoPrograma());
        }
        if (linha.tipoMidia() != null) {
            registro.put("tipoMidia", linha.tipoMidia());
        }
        registro.put("unidade", linha.unidade());
        registro.put("unidadeNome", unidadeNaTela(linha.unidade()));
        if (comMes) {
            registro.put("mes", linha.mes());   // nulo quando a consulta soma todos os meses
        }
        registro.put("previsto", linha.previsto());
        registro.put("exibido", linha.exibido());
        if (linha.percentual() != null) {
            registro.put("percentual", linha.percentual());
        }
        if (linha.impacto() != null) {
            registro.put("impacto", linha.impacto());
        }
        if (linha.arquivo() != null) {
            Map<String, Object> importacao = new LinkedHashMap<>();
            importacao.put("arquivo", linha.arquivo());
            if (linha.localizador() != null) {
                importacao.put("localizador", linha.localizador());
            }
            registro.put("importacao", importacao);
        }
        return registro;
    }

    /** Um total por unidade vira uma fonte {@code [R#]}; não é uma linha do portal, e por isso o id sintético é negativo. */
    private static Map<String, Object> registroDoTotal(FontesDoTurno turno, Total total) {
        String unidade = unidadeNaTela(total.unidade());
        StringBuilder texto = new StringBuilder("Total de mídia em ").append(unidade).append(". Previsto: ")
                .append(quantidade(total.previsto())).append(". Exibido: ").append(quantidade(total.exibido())).append('.');
        if (total.percentual() != null) {
            texto.append(" Entrega: ").append(quantidade(total.percentual())).append("%.");
        }
        texto.append(total.impacto() == null ? " Impacto não informado." : " Impacto: " + quantidade(total.impacto()) + ".");
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("unidade", total.unidade());
        dados.put("previsto", total.previsto());
        dados.put("exibido", total.exibido());
        if (total.percentual() != null) {
            dados.put("percentual", total.percentual());
        }
        if (total.impacto() != null) {
            dados.put("impacto", total.impacto());
        }
        Fonte fonte = registrar(turno, "veiculacao", idDoTotal(total.unidade()), "Total de mídia · " + unidade, "Total",
                texto.toString(), dados);

        Map<String, Object> registro = new LinkedHashMap<>();
        registro.put("marcador", fonte.marcador());
        registro.put("unidade", total.unidade());
        registro.put("unidadeNome", unidade);
        registro.put("previsto", total.previsto());
        registro.put("exibido", total.exibido());
        if (total.percentual() != null) {
            registro.put("percentual", total.percentual());
        }
        if (total.impacto() != null) {
            registro.put("impacto", total.impacto());
        }
        return registro;
    }

    /** Registra a fonte com marcador novo, sem reaproveitar o de um recorte anterior do mesmo turno. */
    private static Fonte registrar(FontesDoTurno turno, String entidade, long id, String titulo, String secao, String texto,
                                   Map<String, Object> dados) {
        Fonte fonte = new Fonte(turno.novoMarcador(Tipo.REGISTRO), Tipo.REGISTRO, null, null, titulo, null, null, secao, texto,
                entidade, id, dados);
        turno.registrar(fonte);
        return fonte;
    }

    /** O id sintético do total: um por unidade, para duas unidades não dividirem o mesmo marcador. */
    private static long idDoTotal(String unidade) {
        return switch (unidade) {
            case "INSERCAO" -> -1L;
            case "CPM" -> -2L;
            case "APLICACAO" -> -3L;
            default -> -4L;
        };
    }

    private static String unidadeNaTela(String unidade) {
        return UNIDADES_NA_TELA.getOrDefault(unidade, unidade);
    }

    private static String quantidade(BigDecimal valor) {
        return ConsultarCota.quantidade(valor);
    }

    private static String limpo(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }
}
