package br.com.rbs.portal.atlas.relatorio;

import br.com.rbs.portal.aplicacoes.AplicacaoDtos.AplicacoesDto;
import br.com.rbs.portal.aplicacoes.AplicacaoDtos.ComprovacaoDto;
import br.com.rbs.portal.aplicacoes.AplicacaoDtos.LinhaDto;
import br.com.rbs.portal.aplicacoes.AplicacaoService;
import br.com.rbs.portal.atlas.AtlasJson;
import br.com.rbs.portal.atlas.AtlasRepositorio;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.relatorio.Calculadora.Resultado;
import br.com.rbs.portal.atlas.relatorio.Especificacao.Calculo;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.CalculoDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.FotoDto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasDtos.VariavelDto;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.consolidacao.ConsolidacaoDtos;
import br.com.rbs.portal.consolidacao.ConsolidacaoService;
import br.com.rbs.portal.dados.DadoInformadoService;
import br.com.rbs.portal.dados.DadosDtos.CampoDto;
import br.com.rbs.portal.dados.DadosDtos.VigenteDto;
import br.com.rbs.portal.parceria.ParceriaDtos.EmailResumoDto;
import br.com.rbs.portal.parceria.ParceriaDtos.ParceriaDetalheDto;
import br.com.rbs.portal.parceria.ParceriaService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

/**
 * Transforma as referências do relatório do Atlas ({@code cons:aplicacoes.previstas}, {@code dado:ativacao.visitantes_total},
 * {@code calc:taxa_cadastro}...) em valores já formatados, com a origem de cada um. [ATLAS] [RELATORIO] A gramática é a de
 * {@link Referencias}; referência fora dela, ou de um {@code dado:} que não está no catálogo ou de um {@code calc:} que o modelo
 * não define, é erro ({@link ReferenciaDesconhecidaException}), nunca valor em branco.
 * <p>
 * [ISOLAMENTO] [PERMISSAO] A resolução recebe a parceria (sempre a da linha {@code atlas_relatorio}, nunca algo vindo de fora) e o
 * usuário de quem pede, e reaproveita os services do portal com esse usuário: a parceria pelo da rota 9, a consolidação pelo da
 * rota 29 (com o filtro de aba do perfil), o plano de aplicações pelo da rota 37 e os dados informados vigentes pelo
 * DadoInformadoService. Nada é lido de {@code vw_indicadores_parceria}, que enxergaria entregas de abas fechadas.
 * <p>
 * Os números saem no padrão do portal: pela função {@code fn_numero_br} do banco (milhar com ponto, decimal com vírgula) e com
 * "%" nos percentuais. Dado sem valor não vira zero: vira um {@link Valor} sem texto e com o motivo.
 */
@Component
public class ResolvedorDeReferencias {

    /** O motivo de um dado informado que ninguém informou. */
    public static final String NAO_INFORMADO = "Não informado pela marca.";
    /** O motivo dos números de aplicações quando o plano comercial da parceria não tem aplicação de marca. */
    public static final String SEM_APLICACOES = "Esta parceria não tem aplicações de marca no plano comercial.";
    /** De onde vêm os números de {@code cons:}. */
    public static final String ORIGEM_CONSOLIDACAO = "Consolidação do portal: plano comercial e fotos de comprovação";
    /** De onde vêm as listas de {@code lista:}. */
    public static final String ORIGEM_LISTA = "Plano comercial e fotos de comprovação, no portal";
    /** De onde vêm a marca, a cota e a edição. */
    public static final String ORIGEM_PARCERIA = "Cadastro da parceria no portal";

    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String INDISPONIVEL_NO_TEXTO = "indisponível";

    private static final Map<String, String> ROTULOS_DE_CONSOLIDACAO = Map.of(
            "aplicacoes.previstas", "Aplicações previstas",
            "aplicacoes.comprovadas", "Aplicações comprovadas",
            "aplicacoes.fotos", "Fotos de comprovação",
            "aplicacoes.percentual", "Comprovação das aplicações");

    /** Referência fora da gramática, de campo que não existe no catálogo ou de cálculo que o modelo não define. */
    public static class ReferenciaDesconhecidaException extends RuntimeException {

        public ReferenciaDesconhecidaException(String mensagem) {
            super(mensagem);
        }
    }

    /**
     * Um valor resolvido. {@code texto} é o número (ou nome) como aparece no relatório; nulo quando indisponível, e então
     * {@code motivo} diz por quê. {@code numero} é o valor para contas (nulo para texto, data ou indisponível).
     */
    public record Valor(String ref, String rotulo, String texto, BigDecimal numero, String unidade, String origem, String motivo) {

        public boolean disponivel() {
            return texto != null;
        }
    }

    /** O texto com os marcadores trocados, ou as referências sem valor (então {@code texto} é nulo). */
    public record TextoResolvido(String texto, List<Valor> indisponiveis) {
    }

    /** A tabela do plano de aplicações já formatada. */
    public record Lista(List<String> colunas, List<List<String>> linhas) {
    }

    private final ParceriaService parcerias;
    private final ConsolidacaoService consolidacao;
    private final AplicacaoService aplicacoes;
    private final DadoInformadoService dados;
    private final AtlasRepositorio repositorio;
    private final Calculadora calculadora;

    public ResolvedorDeReferencias(ParceriaService parcerias, ConsolidacaoService consolidacao, AplicacaoService aplicacoes,
                                   DadoInformadoService dados, AtlasRepositorio repositorio, Calculadora calculadora) {
        this.parcerias = parcerias;
        this.consolidacao = consolidacao;
        this.aplicacoes = aplicacoes;
        this.dados = dados;
        this.repositorio = repositorio;
        this.calculadora = calculadora;
    }

    /**
     * Abre uma resolução para uma parceria e um usuário. Os dados que os services devolvem ficam guardados dentro dela, então
     * resolver o relatório inteiro consulta cada service uma vez só. Descarte-a depois do uso.
     */
    public Resolucao abrir(UsuarioAutenticado usuario, long parceriaId, Especificacao especificacao) {
        return new Resolucao(usuario, parceriaId, especificacao);
    }

    /**
     * A resolução do contexto do Atlas: a da parceria dele ou, na conversa da RBS (M8), a {@link #abrirSemParceria sem parceria}. É o
     * que o chat usa em todo lugar que resolve marcador, para nenhum caminho esquecer a conversa que não tem parceria.
     */
    public Resolucao abrir(AtlasContexto contexto, Especificacao especificacao) {
        return contexto.daRbs() ? abrirSemParceria(contexto.usuario()) : abrir(contexto.usuario(), contexto.parceriaId(), especificacao);
    }

    /**
     * A resolução da conversa da RBS (M8), que não tem parceria: <b>só vale {@code calc:}</b>, e só as contas do chat que a resolução
     * recebeu ({@link Resolucao#registrarConta}). Qualquer outra referência ({@code parc:}, {@code evento:}, {@code cons:},
     * {@code dado:}) é {@link ReferenciaDesconhecidaException}, nunca um dado de marca. Não tem modelo-base: uma fórmula fixa de
     * relatório também não existe aqui.
     */
    public Resolucao abrirSemParceria(UsuarioAutenticado usuario) {
        return new Resolucao(usuario, null, null);
    }

    /**
     * A resolução de um relatório em montagem (M5): a de {@link #abrir}, mais as contas do {@code calcular} que a conversa gravou em
     * {@code atlas_calculo} para ele. É assim que o texto de um bloco usa {@code {{calc:nome}}} de uma conta feita no chat, e que
     * "Fontes e cálculos" a lista como lista as fórmulas fixas.
     */
    public Resolucao abrirDoRelatorio(UsuarioAutenticado usuario, long parceriaId, Especificacao especificacao, long relatorioId) {
        Resolucao resolucao = abrir(usuario, parceriaId, especificacao);
        for (String json : repositorio.contasDoChatDoRelatorio(relatorioId)) {
            ContaDoChat.deDados(AtlasJson.ler(json, new TypeReference<Map<String, Object>>() {
            })).ifPresent(resolucao::registrarConta);
        }
        return resolucao;
    }

    /** Uma resolução em andamento. Não é segura para uso de várias threads. */
    public final class Resolucao {

        private final UsuarioAutenticado usuario;
        private final Long parceriaId;   // nulo na conversa da RBS
        private final Especificacao especificacao;
        private final Map<String, Valor> valores = new HashMap<>();
        private final Map<String, ContaDoChat> contasDoChat = new LinkedHashMap<>();
        private ParceriaDetalheDto<EmailResumoDto> parceria;
        private ConsolidacaoDtos.AplicacoesDto consolidadas;
        private AplicacoesDto plano;
        private Map<String, CampoDto> catalogo;
        private Map<String, VigenteDto> vigentes;

        private Resolucao(UsuarioAutenticado usuario, Long parceriaId, Especificacao especificacao) {
            this.usuario = usuario;
            this.parceriaId = parceriaId;
            this.especificacao = especificacao;
        }

        // -----------------------------------------------------------------
        // Valores
        // -----------------------------------------------------------------

        /**
         * Põe na resolução uma conta do {@code calcular} (M5): daí em diante {@code calc:<nome>} e {@code {{calc:nome}}} valem o
         * resultado dela. A checagem do texto, o filtro do fluxo, a releitura da conversa e as outras contas usam a mesma resolução,
         * e é isto que evita o "indisponível" no lugar de uma conta que existe. A conta do chat vale antes de qualquer outra com o
         * mesmo nome (o {@code calcular} não deixa o nome de uma fórmula fixa).
         */
        public void registrarConta(ContaDoChat conta) {
            contasDoChat.put(conta.nome(), conta);
        }

        /**
         * Esquece as contas do chat. A releitura da conversa usa uma resolução só e troca as contas de uma mensagem para a outra:
         * duas mensagens podem ter contas de mesmo nome e valores diferentes.
         */
        public void esquecerContas() {
            contasDoChat.clear();
        }

        /** O valor de uma referência de valor ({@code lista:} não é valor: use {@link #tabela} e {@link #fotos}). */
        public Valor valor(String ref) {
            if (ref != null && ref.startsWith("calc:") && Referencias.valida(ref)) {
                ContaDoChat conta = contasDoChat.get(Referencias.nome(ref));
                if (conta != null) {
                    return conta.comoValor();   // fora do cache de valores: a conta troca de uma mensagem para a outra
                }
            }
            Valor guardado = valores.get(ref);
            if (guardado == null) {
                guardado = resolver(ref);
                valores.put(ref, guardado);
            }
            return guardado;
        }

        private Valor resolver(String ref) {
            if (!Referencias.valida(ref)) {
                throw new ReferenciaDesconhecidaException("Referência desconhecida: " + ref);
            }
            if (parceriaId == null && !"calc".equals(Referencias.prefixo(ref))) {
                throw new ReferenciaDesconhecidaException("O Atlas da RBS não tem dado de parceria: " + ref + ". Use só as contas deste turno.");
            }
            String nome = Referencias.nome(ref);
            return switch (Referencias.prefixo(ref)) {
                case "parc" -> parceria(ref, nome);
                case "evento" -> {
                    var evento = detalhe().evento();
                    yield disponivel(ref, "Edição", evento.nome() + " " + evento.edicao(), null, null, ORIGEM_PARCERIA);
                }
                case "cons" -> consolidacao(ref, nome);
                case "dado" -> dado(ref, nome);
                case "calc" -> calculo(ref, nome);
                default -> throw new ReferenciaDesconhecidaException(
                        "Uma lista não é um valor; peça a tabela ou a galeria: " + ref);
            };
        }

        private Valor parceria(String ref, String nome) {
            ParceriaDetalheDto<EmailResumoDto> detalhe = detalhe();
            return "patrocinador".equals(nome)
                    ? disponivel(ref, "Patrocinador", detalhe.patrocinador().nome(), null, null, ORIGEM_PARCERIA)
                    : disponivel(ref, "Cota", detalhe.cota().nome(), null, null, ORIGEM_PARCERIA);
        }

        private Valor consolidacao(String ref, String nome) {
            ConsolidacaoDtos.AplicacoesDto c = consolidadas();
            BigDecimal numero = switch (nome) {
                case "aplicacoes.previstas" -> c.previstas();
                case "aplicacoes.comprovadas" -> c.comprovadas();
                case "aplicacoes.fotos" -> c.fotos() == null ? null : BigDecimal.valueOf(c.fotos());
                default -> c.percentual();
            };
            String rotulo = ROTULOS_DE_CONSOLIDACAO.get(nome);
            if (numero == null) {
                return new Valor(ref, rotulo, null, null, null, ORIGEM_CONSOLIDACAO, SEM_APLICACOES);
            }
            boolean percentual = nome.endsWith(".percentual");
            return disponivel(ref, rotulo, formatar(numero) + (percentual ? "%" : ""), numero, percentual ? "%" : null,
                    ORIGEM_CONSOLIDACAO);
        }

        private Valor dado(String ref, String chave) {
            CampoDto campo = catalogo().get(chave);
            if (campo == null) {
                throw new ReferenciaDesconhecidaException("O campo " + chave + " não está no catálogo: " + ref);
            }
            if ("ARQUIVO".equals(campo.tipo())) {
                throw new ReferenciaDesconhecidaException("Campo de arquivo não vale como dado do relatório: " + ref);
            }
            VigenteDto vigente = vigentes().get(chave);
            if (vigente == null) {
                return new Valor(ref, campo.rotulo(), null, null, campo.unidade(), "", NAO_INFORMADO);
            }
            String origem = "Informado por " + vigente.informadoPor().nome() + " em " + DIA.format(vigente.informadoEm())
                    + ("ATLAS".equals(vigente.origem()) ? ", pelo Atlas" : "");
            if (vigente.valor() instanceof LocalDate data) {
                return disponivel(ref, campo.rotulo(), DIA.format(data), null, campo.unidade(), origem);
            }
            BigDecimal numero = vigente.valor() instanceof BigDecimal b ? b : BigDecimal.valueOf(((Number) vigente.valor()).longValue());
            return disponivel(ref, campo.rotulo(), formatar(numero), numero, campo.unidade(), origem);
        }

        private Valor calculo(String ref, String nome) {
            Calculo calculo = especificacao == null ? null : especificacao.calculos().get(nome);
            if (calculo == null) {
                throw new ReferenciaDesconhecidaException("O modelo não define o cálculo " + nome + ": " + ref);
            }
            Map<String, Valor> entradas = entradasDe(calculo);
            Map<String, BigDecimal> numeros = new LinkedHashMap<>();
            entradas.forEach((variavel, valor) -> numeros.put(variavel, valor.numero()));
            Resultado resultado = calculadora.calcular(calculo.script(), numeros);
            if (!resultado.disponivel()) {
                String motivo = resultado.denominadorZero() != null
                        ? "Não dá para calcular: " + entradas.get(resultado.denominadorZero()).rotulo() + " é zero."
                        : "Faltam dados para o cálculo: " + juntar(resultado.semValor().stream()
                                .map(v -> entradas.get(v).rotulo()).toList()) + ".";
                return new Valor(ref, calculo.rotulo(), null, null, calculo.unidade(), calculo.script(), motivo);
            }
            String texto = formatar(resultado.valor()) + ("%".equals(calculo.unidade()) ? "%" : "");
            List<String> partes = new ArrayList<>();
            entradas.forEach((variavel, valor) -> partes.add(variavel + " = " + valor.texto()));
            return disponivel(ref, calculo.rotulo(), texto, resultado.valor(), calculo.unidade(),
                    calculo.script() + " com " + juntar(partes));
        }

        /** As variáveis de uma conta, na ordem da fórmula, com o valor resolvido de cada uma. */
        private Map<String, Valor> entradasDe(Calculo calculo) {
            Map<String, Valor> entradas = new LinkedHashMap<>();
            for (String variavel : Calculadora.variaveisDe(calculo.script())) {
                entradas.put(variavel, valor(calculo.variaveis().get(variavel).ref()));
            }
            return entradas;
        }

        // -----------------------------------------------------------------
        // Listas
        // -----------------------------------------------------------------

        /** A tabela de {@code lista:aplicacoes}: aplicação, previsto, comprovadas e fotos, uma linha por aplicação do plano. */
        public Lista tabela(String ref) {
            exigirParceria();
            if (!"lista:aplicacoes".equals(ref)) {
                throw new ReferenciaDesconhecidaException("Não existe a tabela " + ref);
            }
            List<LinhaDto> linhas = plano().linhas();
            List<BigDecimal> numeros = new ArrayList<>();
            for (LinhaDto linha : linhas) {
                numeros.add(linha.previsto());
                numeros.add(linha.comprovadas());
                numeros.add(BigDecimal.valueOf(linha.fotos()));
            }
            List<String> formatados = repositorio.formatarNumeros(numeros);
            List<List<String>> celulas = new ArrayList<>();
            for (int i = 0; i < linhas.size(); i++) {
                celulas.add(List.of(linhas.get(i).formato(), formatados.get(3 * i), formatados.get(3 * i + 1),
                        formatados.get(3 * i + 2)));
            }
            return new Lista(List.of("Aplicação", "Previsto", "Comprovadas", "Fotos"), celulas);
        }

        /** As fotos de {@code lista:comprovacoes}, na ordem das aplicações do plano e, em cada uma, da mais antiga à mais nova. */
        public List<FotoDto> fotos(String ref) {
            exigirParceria();
            if (!"lista:comprovacoes".equals(ref)) {
                throw new ReferenciaDesconhecidaException("Não existe a galeria " + ref);
            }
            List<FotoDto> fotos = new ArrayList<>();
            for (LinhaDto linha : plano().linhas()) {
                for (ComprovacaoDto foto : linha.comprovacoes()) {
                    fotos.add(new FotoDto(foto.arquivoId(), foto.nomeOriginal(), linha.formato(), foto.descricao(),
                            DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(foto.registradoEm())));
                }
            }
            return fotos;
        }

        // -----------------------------------------------------------------
        // Textos
        // -----------------------------------------------------------------

        /**
         * Troca os marcadores {@code {{ref}}} pelo valor formatado. Se algum não tem valor, {@code texto} vem nulo e
         * {@code indisponiveis} lista os que faltam (o bloco inteiro sai como indisponível: frase com buraco não existe).
         * Marcador fora da gramática é {@link ReferenciaDesconhecidaException}.
         */
        public TextoResolvido texto(String comMarcadores) {
            List<Valor> faltam = new ArrayList<>();
            for (String ref : Referencias.marcadores(comMarcadores)) {
                Valor valor = valor(ref);
                if (!valor.disponivel() && faltam.stream().noneMatch(f -> f.ref().equals(ref))) {
                    faltam.add(valor);
                }
            }
            return faltam.isEmpty()
                    ? new TextoResolvido(Referencias.substituir(comMarcadores, ref -> valor(ref).texto()), List.of())
                    : new TextoResolvido(null, faltam);
        }

        /**
         * O texto para mostrar no chat: cada marcador vira o valor, e o que não tem valor (ou não existe) vira
         * "indisponível". O chat nunca mostra o marcador cru nem inventa um número.
         */
        public String paraExibicao(String comMarcadores) {
            return Referencias.substituir(comMarcadores, ref -> {
                try {
                    Valor valor = valor(ref);
                    return valor.disponivel() ? valor.texto() : INDISPONIVEL_NO_TEXTO;
                } catch (ReferenciaDesconhecidaException desconhecida) {
                    return INDISPONIVEL_NO_TEXTO;
                }
            });
        }

        // -----------------------------------------------------------------
        // Contas
        // -----------------------------------------------------------------

        /**
         * Todas as contas das fórmulas fixas do modelo, com as variáveis e o resultado (ou o motivo de faltar), e, depois delas, as
         * contas do chat que a resolução recebeu.
         */
        public List<CalculoDto> calculos() {
            List<CalculoDto> contas = new ArrayList<>();
            (especificacao == null ? Map.<String, Calculo>of() : especificacao.calculos()).forEach((nome, calculo) -> {
                Valor resultado = valor("calc:" + nome);
                List<VariavelDto> variaveis = new ArrayList<>();
                entradasDe(calculo).forEach((variavel, entrada) ->
                        variaveis.add(new VariavelDto(variavel, entrada.ref(), entrada.numero(), entrada.texto(), entrada.unidade())));
                contas.add(new CalculoDto(nome, calculo.rotulo(), calculo.script(), variaveis, resultado.numero(),
                        resultado.texto(), calculo.unidade(), resultado.motivo()));
            });
            contasDoChat.values().forEach(conta -> contas.add(conta.comoCalculo()));
            return contas;
        }

        /** Os dados vigentes que o relatório pode usar, na ordem do catálogo. */
        public Set<String> chavesComValor() {
            return new LinkedHashSet<>(vigentes().keySet());
        }

        // -----------------------------------------------------------------
        // Dados que os services devolvem (uma consulta cada, guardada)
        // -----------------------------------------------------------------

        private ParceriaDetalheDto<EmailResumoDto> detalhe() {
            exigirParceria();
            if (parceria == null) {
                parceria = parcerias.detalhe(usuario, parceriaId);
            }
            return parceria;
        }

        private ConsolidacaoDtos.AplicacoesDto consolidadas() {
            exigirParceria();
            if (consolidadas == null) {
                consolidadas = consolidacao.consolidar(usuario, parceriaId).aplicacoes();
            }
            return consolidadas;
        }

        private AplicacoesDto plano() {
            exigirParceria();
            if (plano == null) {
                plano = aplicacoes.listar(usuario, parceriaId);
            }
            return plano;
        }

        private Map<String, CampoDto> catalogo() {
            if (catalogo == null) {
                catalogo = new LinkedHashMap<>();
                dados.listarCampos(null).forEach(c -> catalogo.put(c.chave(), c));
            }
            return catalogo;
        }

        private Map<String, VigenteDto> vigentes() {
            exigirParceria();
            if (vigentes == null) {
                vigentes = dados.vigentes(parceriaId);
            }
            return vigentes;
        }

        /** Os dados de parceria só existem com parceria: a resolução da conversa da RBS nunca os busca. */
        private void exigirParceria() {
            if (parceriaId == null) {
                throw new ReferenciaDesconhecidaException("O Atlas da RBS não tem dado de parceria.");
            }
        }

        private String formatar(BigDecimal numero) {
            return repositorio.formatarNumeros(List.of(numero)).get(0);
        }
    }

    private static Valor disponivel(String ref, String rotulo, String texto, BigDecimal numero, String unidade, String origem) {
        return new Valor(ref, rotulo, texto, numero, unidade, origem, null);
    }

    /** "A", "A e B", "A, B e C". */
    static String juntar(List<String> partes) {
        if (partes.size() <= 1) {
            return partes.isEmpty() ? "" : partes.get(0);
        }
        return String.join(", ", partes.subList(0, partes.size() - 1)) + " e " + partes.get(partes.size() - 1);
    }
}
