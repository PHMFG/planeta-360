package br.com.rbs.portal.atlas.ferramentas;

import static br.com.rbs.portal.atlas.ferramentas.Argumentos.desconhecidos;
import static br.com.rbs.portal.atlas.ferramentas.Argumentos.exigirSemErros;

import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno.Fonte;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.ferramentas.RegistrosDoPortal.AbaDaCota;
import br.com.rbs.portal.atlas.ferramentas.RegistrosDoPortal.Cortesia;
import br.com.rbs.portal.atlas.ferramentas.RegistrosDoPortal.Credencial;
import br.com.rbs.portal.atlas.ferramentas.RegistrosDoPortal.DadosDaCota;
import br.com.rbs.portal.atlas.ferramentas.RegistrosDoPortal.PlanoComercial;
import br.com.rbs.portal.atlas.ferramentas.RegistrosDoPortal.Previsto;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.DefinicaoDeFerramenta;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@code consultar_cota()}: o que a parceria contratou (plano do Atlas completo, M5; ferramenta 3 do quadro). [ATLAS] [ISOLAMENTO]
 * [PERMISSAO] Sem argumento nenhum: a parceria vem do {@link AtlasContexto}. Devolve, em registros {@code R#}:
 * <ul>
 *   <li>a cota, o produto e a edição (entidade {@code parceria}) e as abas, cada uma liberada, fechada ou fora da cota;</li>
 *   <li>o previsto do plano comercial, linha a linha, <b>só das categorias de aba aberta</b>: {@code APLICACAO_MARCA} se a aba
 *       Aplicações está aberta, {@code MIDIA} se a Mídia está. Cada linha leva a unidade ({@code INSERCAO}, {@code CPM} ou
 *       {@code APLICACAO}) em {@code dados}, que o {@code calcular} lê. Havendo um plano comercial indexado e visível para a parceria,
 *       a linha leva a origem (documento e versão) e, em {@code dados}, o {@code documentoId}, a {@code versao} e, quando o cadastro
 *       a tem, a {@code pagina}: a {@code fonte_pagina} da linha do previsto, se ela é no máximo o número de páginas do documento. Sem
 *       página no cadastro, ou com uma além do fim do documento, a página fica de fora (a tela abre na primeira, sem afirmar nada);</li>
 *   <li>as cortesias e as credenciais, só se a aba estiver aberta e houver linha.</li>
 * </ul>
 * <b>O valor do investimento e a base dele nunca entram</b> (o mínimo de dado): a consulta seleciona coluna por coluna, sem
 * reaproveitar nenhum DTO da parceria. Valor sem linha não vira zero: simplesmente não há registro dele.
 */
@Component
public class ConsultarCota implements Ferramenta {

    public static final String NOME = "consultar_cota";
    /** No máximo estas linhas do previsto; o plano de uma cota tem de 5 a 40. */
    static final int LIMITE_DO_PREVISTO = 60;
    private static final Set<String> ARGUMENTOS = Set.of();

    private static final DefinicaoDeFerramenta DEFINICAO = new DefinicaoDeFerramenta(NOME,
            "Devolve o que esta parceria contratou: a cota, o produto, a edição, as abas (liberadas ou fechadas), o que o plano "
                    + "comercial prevê (inserções, CPM e aplicações de marca) das abas abertas e as cortesias e credenciais, se a aba "
                    + "estiver aberta. Não recebe argumento. Cada item vem com o marcador (R1, R2...): cite o marcador entre colchetes, "
                    + "como [R3], logo depois da afirmação, e copie números exatamente como estão. Não traz valores em reais do contrato. "
                    + "Quando o previsto tem a origem (documento e versão), o plano comercial está nos documentos do portal.",
            """
            {"type":"object","properties":{},"additionalProperties":false}""");

    private static final Map<String, String> SETORES = Map.of("ARENA", "Arena", "CAMAROTE", "Camarote", "LOUNGE", "Lounge",
            "PREMIUM", "Premium", "BAIXO_CAMAROTE", "Baixo camarote", "SALA_IMPRENSA", "Sala de imprensa", "SALA_CONTEUDO", "Sala de conteúdo");
    private static final Map<String, String> DIAS = Map.of("SEXTA", "sexta-feira", "SABADO", "sábado", "PASSAPORTE", "passaporte (os dois dias)");
    /**
     * O beneficiário da credencial como o texto o diz ("para a marca") e como a tela o escreve ({@code RelatorioParceria.tsx}:
     * "Marca", "Agência", "Equipe de conteúdo"). {@code GERAL} não tem palavra na tela e não entra: o campo sai, em vez de levar o código.
     */
    private static final Map<String, String> BENEFICIARIOS = Map.of("MARCA", "marca", "AGENCIA", "agência",
            "EQUIPE_CONTEUDO", "equipe de conteúdo");
    private static final Map<String, String> BENEFICIARIOS_NA_TELA = Map.of("MARCA", "Marca", "AGENCIA", "Agência",
            "EQUIPE_CONTEUDO", "Equipe de conteúdo");
    /** "P6" e "P4" são o Portão 6 e o Portão 4 escritos em abreviatura; só a abreviatura vira palavra, sem inventar portão. */
    private static final java.util.regex.Pattern PORTAO_ABREVIADO = java.util.regex.Pattern.compile("\\bP(\\d{1,2})\\b");
    private static final Map<String, String> TIPOS_DE_MIDIA = Map.of("ASSINADA", "assinada", "PROPRIA", "própria", "MERCHANDISING",
            "merchandising", "BRANDED", "branded", "CONTEUDO_DE_MARCA", "conteúdo de marca");

    private final RegistrosDoPortal registros;
    private final VerificadorDeAcesso verificador;

    public ConsultarCota(RegistrosDoPortal registros, VerificadorDeAcesso verificador) {
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
        return 24;
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
        exigirSemErros(erros);
        verificador.exigirParceria(contexto.usuario(), contexto.parceriaId());
        long parceria = contexto.parceriaId();
        DadosDaCota cota = registros.cota(parceria).orElseThrow(() -> new NaoEncontradoException("Não encontramos esta parceria."));
        List<AbaDaCota> abas = registros.abasDaCota(parceria);
        Set<String> abertas = new java.util.HashSet<>();
        abas.stream().filter(AbaDaCota::aberta).forEach(a -> abertas.add(a.codigo()));

        List<Map<String, Object>> linhas = new ArrayList<>();
        linhas.add(daCota(turno, cota));
        linhas.add(dasAbas(turno, parceria, abas));

        List<String> categorias = new ArrayList<>();
        if (abertas.contains("MIDIA")) {
            categorias.add("MIDIA");
        }
        if (abertas.contains("APLICACOES")) {
            categorias.add("APLICACAO_MARCA");
        }
        String observacao = null;
        if (!categorias.isEmpty()) {
            Optional<PlanoComercial> plano = registros.planoComercial(parceria);
            long total = registros.contarPrevisto(parceria, categorias);
            for (Previsto p : registros.previsto(parceria, categorias, LIMITE_DO_PREVISTO)) {
                linhas.add(doPrevisto(turno, p, plano.orElse(null)));
            }
            if (total > LIMITE_DO_PREVISTO) {
                observacao = "O plano tem " + total + " linhas previstas; estas são as " + LIMITE_DO_PREVISTO + " primeiras.";
            }
        }
        if (abertas.contains("CORTESIAS")) {
            registros.cortesias(parceria).forEach(c -> linhas.add(daCortesia(turno, c)));
        }
        if (abertas.contains("CREDENCIAMENTO")) {
            registros.credenciais(parceria).forEach(c -> linhas.add(daCredencial(turno, c)));
        }
        return Execucao.resultado(ResultadoDeRegistros.montar(null, observacao, null, linhas));
    }

    // ---------------------------------------------------------------------
    // Os registros
    // ---------------------------------------------------------------------

    private static Map<String, Object> daCota(FontesDoTurno turno, DadosDaCota c) {
        StringBuilder texto = new StringBuilder("Cota ").append(c.cotaNome()).append('.');
        if (c.produto() != null) {
            texto.append(" Produto: ").append(c.produto()).append('.');
        }
        texto.append(" Edição: ").append(c.eventoNome()).append(' ').append(c.edicao()).append('.');
        Fonte fonte = turno.registrarRegistro("parceria", c.parceriaId(), "Cota da parceria", "Cota e edição", texto.toString(), Map.of());
        Map<String, Object> registro = new LinkedHashMap<>();
        registro.put("marcador", fonte.marcador());
        registro.put("tipo", "cota");
        registro.put("cota", Map.of("codigo", c.cotaCodigo(), "nome", c.cotaNome()));
        registro.put("produto", c.produto());
        Map<String, Object> edicao = new LinkedHashMap<>();
        edicao.put("nome", c.eventoNome());
        edicao.put("ano", c.edicao());
        registro.put("edicao", edicao);
        return registro;
    }

    private static Map<String, Object> dasAbas(FontesDoTurno turno, long parceriaId, List<AbaDaCota> abas) {
        List<Map<String, Object>> itens = new ArrayList<>();
        StringBuilder texto = new StringBuilder("Abas da parceria:");
        for (AbaDaCota a : abas) {
            String situacao = a.aberta() ? "liberada" : a.naCota() ? "fechada" : "fora da cota";
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("codigo", a.codigo());
            item.put("nome", a.nome());
            item.put("situacao", situacao);
            itens.add(item);
            texto.append(' ').append(a.nome()).append(": ").append(situacao).append('.');
        }
        Fonte fonte = turno.registrarRegistro("parceria_abas", parceriaId, "Abas da parceria", "Cota e edição", texto.toString(), Map.of());
        Map<String, Object> registro = new LinkedHashMap<>();
        registro.put("marcador", fonte.marcador());
        registro.put("tipo", "abas");
        registro.put("abas", itens);
        return registro;
    }

    private static Map<String, Object> doPrevisto(FontesDoTurno turno, Previsto p, PlanoComercial plano) {
        String quantidade = quantidade(p.quantidadePrevista());
        boolean um = p.quantidadePrevista().compareTo(BigDecimal.ONE) == 0;
        String unidade = switch (p.unidade()) {
            case "INSERCAO" -> um ? "inserção" : "inserções";
            case "APLICACAO" -> um ? "aplicação" : "aplicações";
            default -> p.unidade();
        };
        StringBuilder texto = new StringBuilder("Previsto no plano comercial: ").append(quantidade).append(' ').append(unidade)
                .append(". Veículo: ").append(p.veiculo()).append(". Formato: ").append(p.formato()).append('.');
        acrescentar(texto, "Espaço", p.espacoPrograma());
        acrescentar(texto, "Período", p.janela());
        acrescentar(texto, "Tipo", p.tipoMidia() == null ? null : TIPOS_DE_MIDIA.getOrDefault(p.tipoMidia(), p.tipoMidia()));
        acrescentar(texto, "Bloco", p.bloco());
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("unidade", p.unidade());
        if (plano != null) {
            dados.put("documentoId", plano.documentoId());
            dados.put("versao", plano.versao());
            Integer pagina = p.fontePagina();
            // a página do cadastro, se ela existe no documento; senão nenhuma (a tela abre na primeira sem afirmar nada)
            if (pagina != null && pagina >= 1 && plano.paginas() != null && pagina <= plano.paginas()) {
                dados.put("pagina", pagina);
            }
        }
        boolean midia = "MIDIA".equals(p.categoria());
        Fonte fonte = turno.registrarRegistro("aproveitamento_previsto", p.id(), p.veiculo() + " · " + p.formato(),
                "Plano comercial · " + (midia ? "Mídia e conteúdo" : "Aplicações de marca"), texto.toString(), dados);
        Map<String, Object> registro = new LinkedHashMap<>();
        registro.put("marcador", fonte.marcador());
        registro.put("tipo", "previsto");
        registro.put("categoria", p.categoria());
        poeSeHouver(registro, "bloco", p.bloco());
        registro.put("veiculo", p.veiculo());
        registro.put("formato", p.formato());
        poeSeHouver(registro, "espacoPrograma", p.espacoPrograma());
        poeSeHouver(registro, "janela", p.janela());
        poeSeHouver(registro, "tipoMidia", p.tipoMidia());
        registro.put("quantidadePrevista", quantidade);
        registro.put("unidade", p.unidade());
        if (plano != null) {
            Map<String, Object> origem = new LinkedHashMap<>();
            origem.put("documento", plano.titulo());
            origem.put("versao", plano.versao());
            registro.put("origem", origem);
        }
        return registro;
    }

    private static Map<String, Object> daCortesia(FontesDoTurno turno, Cortesia c) {
        String setor = SETORES.getOrDefault(c.setor(), c.setor());
        String dia = DIAS.getOrDefault(c.dia(), c.dia());
        StringBuilder texto = new StringBuilder("Cota de cortesias: ").append(c.quantidade()).append(" no setor ").append(setor).append(", ")
                .append(dia).append(c.comShuttle() ? ", com shuttle" : ", sem shuttle").append('.');
        String acesso = acessoEmPalavras(c.acesso());
        acrescentar(texto, "Acesso", acesso);
        Fonte fonte = turno.registrarRegistro("cortesia_cota", c.id(), "Cortesias · " + setor + " · " + dia, "Cortesias", texto.toString(), Map.of());
        Map<String, Object> registro = new LinkedHashMap<>();
        registro.put("marcador", fonte.marcador());
        registro.put("tipo", "cortesia");
        registro.put("setor", c.setor());
        registro.put("dia", c.dia());
        registro.put("quantidade", c.quantidade());
        registro.put("comShuttle", c.comShuttle());
        poeSeHouver(registro, "acesso", acesso);
        return registro;
    }

    private static Map<String, Object> daCredencial(FontesDoTurno turno, Credencial c) {
        String setor = SETORES.getOrDefault(c.setor(), c.setor());
        String beneficiario = BENEFICIARIOS.get(c.beneficiario());   // nulo no GERAL: não há palavra certa, e o campo sai
        String texto = "Credenciais por dia: " + c.quantidadePorDia() + (beneficiario == null ? "" : " para a " + beneficiario) + " no setor "
                + setor + ".";
        Fonte fonte = turno.registrarRegistro("credencial_cota", c.id(), "Credenciais · " + setor + (beneficiario == null ? "" : " · " + beneficiario),
                "Credenciamento", texto, Map.of());
        Map<String, Object> registro = new LinkedHashMap<>();
        registro.put("marcador", fonte.marcador());
        registro.put("tipo", "credencial");
        registro.put("setor", c.setor());
        if (beneficiario != null) {
            registro.put("beneficiario", BENEFICIARIOS_NA_TELA.get(c.beneficiario()));
        }
        registro.put("quantidadePorDia", c.quantidadePorDia());
        return registro;
    }

    /** O acesso como o modelo o lê: a abreviatura {@code P6} vira "Portão 6"; o resto do texto fica como o cadastro o escreveu. */
    public static String acessoEmPalavras(String acesso) {
        return acesso == null ? null : PORTAO_ABREVIADO.matcher(acesso).replaceAll("Portão $1");
    }

    private static void acrescentar(StringBuilder texto, String rotulo, String valor) {
        if (valor != null && !valor.isBlank()) {
            texto.append(' ').append(rotulo).append(": ").append(valor).append('.');
        }
    }

    private static void poeSeHouver(Map<String, Object> registro, String chave, String valor) {
        if (valor != null && !valor.isBlank()) {
            registro.put(chave, valor);
        }
    }

    /** A quantidade como o portal a escreve: milhar com ponto, decimal com vírgula, sem zeros à direita ({@code 200.000}, {@code 1,5}). */
    static String quantidade(BigDecimal quantidade) {
        NumberFormat formato = NumberFormat.getNumberInstance(Locale.of("pt", "BR"));
        formato.setGroupingUsed(true);
        formato.setMaximumFractionDigits(2);
        formato.setMinimumFractionDigits(0);
        return formato.format(quantidade);
    }
}
