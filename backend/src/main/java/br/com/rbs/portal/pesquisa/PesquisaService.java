package br.com.rbs.portal.pesquisa;

import static br.com.rbs.portal.pesquisa.PesquisaDtos.LIMITE_PADRAO_DE_PERGUNTAS;

import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.SemPermissaoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.pesquisa.PesquisaDtos.DecisaoRequisicao;
import br.com.rbs.portal.pesquisa.PesquisaDtos.EnviarPerguntasRequisicao;
import br.com.rbs.portal.pesquisa.PesquisaDtos.PerguntaDto;
import br.com.rbs.portal.pesquisa.PesquisaDtos.PerguntaRequisicao;
import br.com.rbs.portal.pesquisa.PesquisaDtos.PesquisaDaParceriaDto;
import br.com.rbs.portal.pesquisa.PesquisaDtos.PesquisaDto;
import br.com.rbs.portal.pesquisa.PesquisaDtos.ResultadoDto;
import br.com.rbs.portal.pesquisa.PesquisaDtos.UsuarioResumoDto;
import br.com.rbs.portal.pesquisa.PesquisaRepositorio.LinhaDePergunta;
import br.com.rbs.portal.pesquisa.PesquisaRepositorio.LinhaDePesquisa;
import br.com.rbs.portal.pesquisa.PesquisaRepositorio.PerguntaTravada;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regras da pesquisa da ativação: o patrocinador envia perguntas até o limite da pesquisa, a RBS também as escreve e
 * aprova ou pede ajuste, e só pergunta aprovada mostra resultado. Atende o item 5 da Entrega 2. [VALIDACAO] [PERMISSAO]
 * [ISOLAMENTO] [HISTORICO] A pergunta aprovada nunca muda: o texto, o tipo e as alternativas que a RBS aprovou são os do
 * resultado que a carga gravou.
 */
@Service
public class PesquisaService {

    private static final String ENTIDADE = "pesquisa_pergunta";
    private static final String TIPO_ESCOLHA = "ESCOLHA";
    private static final String TIPO_SIM_NAO = "SIM_NAO";
    private static final String TIPO_NOTA = "NOTA";
    private static final String TIPO_TEXTO = "TEXTO";
    private static final List<String> ALTERNATIVAS_SIM_NAO = List.of("Sim", "Não");
    private static final int MINIMO_DE_ALTERNATIVAS = 2;
    private static final int MAXIMO_DE_ALTERNATIVAS = 10;
    private static final int TAMANHO_MAXIMO_DA_ALTERNATIVA = 80;
    private static final String COTA_DA_PESQUISA = "MASTER";
    /** A frase do 403 de quem é de uma cota que não tem a aba Pesquisa. */
    public static final String SO_AS_MASTER = "A pesquisa é das cotas Master.";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> LISTA_DE_STRINGS = new TypeReference<>() {
    };

    private final PesquisaRepositorio repositorio;
    private final VerificadorDeAcesso verificador;
    private final HistoricoService historico;

    public PesquisaService(PesquisaRepositorio repositorio, VerificadorDeAcesso verificador, HistoricoService historico) {
        this.repositorio = repositorio;
        this.verificador = verificador;
        this.historico = historico;
    }

    // ---------------------------------------------------------------------
    // Rota 23: ver a pesquisa
    // ---------------------------------------------------------------------

    /**
     * Rota 23. [ISOLAMENTO] Parceria de outro patrocinador: 404. [PERMISSAO] O patrocinador só entra se a aba PESQUISA
     * estiver liberada para a parceria dele (senão 403, com a frase da cota ou a da aba ainda fechada); a RBS vê sempre, e
     * {@code abaLiberada} diz como a aba está.
     */
    @Transactional(readOnly = true)
    public PesquisaDaParceriaDto ver(UsuarioAutenticado usuario, long parceriaId) {
        verificador.exigirParceria(usuario, parceriaId);
        verificador.exigirAbaLiberada(usuario, parceriaId, "PESQUISA");
        return montar(parceriaId, repositorio.abaLiberada(parceriaId));
    }

    // ---------------------------------------------------------------------
    // Rota 24: o patrocinador envia as perguntas
    // ---------------------------------------------------------------------

    /** O que fazer com uma pergunta da lista, decidido antes de gravar qualquer coisa. */
    private enum Acao { INSERIR, REENVIAR, APAGAR }

    private record Passo(Acao acao, PerguntaTravada atual, int ordem, String texto, String tipoResposta,
                         String alternativasJson) {
    }

    /** A pergunta já validada e normalizada (tipo efetivo e alternativas em JSON), pronta para comparar com o banco. */
    private record PerguntaNormalizada(int ordem, String texto, String tipoResposta, String alternativasJson) {
    }

    /**
     * Rota 24. O PUT substitui o conjunto: a lista que chega é a lista que a marca quer. [PERMISSAO] O Admin Patrocinador da
     * parceria (com a aba liberada) ou a RBS (que escreve para qualquer Master, mesmo com a aba fechada). [VALIDACAO] A
     * pergunta nova ou alterada fica ENVIADA; a devolvida para ajuste (RASCUNHO) volta a ENVIADA ao ser enviada de novo; a
     * ENVIADA sem mudança continua como está; a APROVADA nunca é alterada nem retirada (409). O limite de perguntas vem da
     * pesquisa (padrão 10). Primeiro se monta o plano inteiro, depois se grava, então um 409 não deixa a lista pela metade.
     * [HISTORICO] Uma linha EDICAO por pergunta que mudou, na mesma transação.
     */
    @Transactional
    public PesquisaDaParceriaDto enviarPerguntas(UsuarioAutenticado usuario, long parceriaId,
                                                 EnviarPerguntasRequisicao corpo, HttpServletRequest requisicao) {
        // Estas conferências também rodam antes de o corpo ser lido (PermissaoAntesDoCorpo); ficam aqui por segurança.
        verificador.exigirParceria(usuario, parceriaId);
        verificador.exigirAbaLiberada(usuario, parceriaId, "PESQUISA");
        if (usuario.ehRbs() && !COTA_DA_PESQUISA.equals(repositorio.cota(parceriaId).orElse(null))) {
            throw new SemPermissaoException(SO_AS_MASTER);
        }

        repositorio.travarParceria(parceriaId);
        Map<Integer, PerguntaTravada> atuais = repositorio.perguntasTravadas(parceriaId).stream()
                .collect(Collectors.toMap(PerguntaTravada::ordem, Function.identity()));
        LinhaDePesquisa pesquisa = repositorio.pesquisaDaAtivacao(parceriaId).orElse(null);
        int limite = pesquisa == null ? LIMITE_PADRAO_DE_PERGUNTAS : pesquisa.limitePerguntas();
        List<PerguntaNormalizada> normalizadas = normalizar(corpo.perguntas(), atuais, limite);
        List<Passo> plano = montarPlano(normalizadas, atuais);

        Long pesquisaId = pesquisa == null ? null : pesquisa.id();
        for (Passo passo : plano) {
            executar(passo, usuario, parceriaId, pesquisaId, requisicao);
        }
        return montar(parceriaId, true);
    }

    /**
     * [VALIDACAO] Confere a lista contra o limite da pesquisa e normaliza cada pergunta: ordem (1..limite, sem repetir),
     * tipo efetivo e alternativas. Junta todos os erros num 422 só, como a validação automática do corpo fazia.
     */
    private static List<PerguntaNormalizada> normalizar(List<PerguntaRequisicao> perguntas,
                                                        Map<Integer, PerguntaTravada> atuais, int limite) {
        Map<String, String> campos = new LinkedHashMap<>();
        if (perguntas.isEmpty()) {
            campos.put("perguntas", "Envie ao menos uma pergunta.");
        } else if (perguntas.size() > limite) {
            campos.put("perguntas", "Envie de 1 a " + limite + " perguntas.");
        }
        Set<Integer> vistas = new HashSet<>();
        List<PerguntaNormalizada> normalizadas = new ArrayList<>();
        for (int i = 0; i < perguntas.size(); i++) {
            PerguntaRequisicao pergunta = perguntas.get(i);
            int ordem = pergunta.ordem() == null ? 0 : pergunta.ordem();
            if (pergunta.ordem() == null) {
                campos.put(chave(i, "ordem"), "Informe a ordem da pergunta.");
            } else if (ordem < 1 || ordem > limite) {
                campos.put(chave(i, "ordem"), "A ordem da pergunta vai de 1 a " + limite + ".");
            } else if (!vistas.add(ordem)) {
                campos.put(chave(i, "ordem"), "A ordem " + ordem + " já foi usada por outra pergunta da lista.");
            }
            PerguntaTravada atual = atuais.get(ordem);
            String tipo = tipoDe(pergunta, atual, i, campos);
            String alternativas = alternativasDe(pergunta, atual, tipo, pergunta.tipoResposta() != null, i, campos);
            normalizadas.add(new PerguntaNormalizada(ordem, pergunta.texto(), tipo, alternativas));
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }
        return normalizadas;
    }

    private static String chave(int indice, String campo) {
        return "perguntas[" + indice + "]." + campo;
    }

    /**
     * O tipo efetivo: o que veio no corpo ou, quando o cliente antigo não manda tipo, o que a pergunta já tem no banco (ou
     * ESCOLHA, numa pergunta nova). O tipo explícito é validado no bean validation; aqui a conferência é só a rede de baixo.
     */
    private static String tipoDe(PerguntaRequisicao pergunta, PerguntaTravada atual, int indice, Map<String, String> campos) {
        String tipo = pergunta.tipoResposta();
        if (tipo == null) {
            return atual == null ? TIPO_ESCOLHA : atual.tipoResposta();
        }
        if (!Set.of(TIPO_ESCOLHA, TIPO_SIM_NAO, TIPO_NOTA, TIPO_TEXTO).contains(tipo)) {
            campos.put(chave(indice, "tipoResposta"), "Tipo de resposta inválido. Use ESCOLHA, SIM_NAO, NOTA ou TEXTO.");
            return TIPO_ESCOLHA;
        }
        return tipo;
    }

    /**
     * As alternativas efetivas, já em JSON, conforme o tipo. Sem tipo explícito e sem alternativas no corpo, a pergunta
     * existente mantém exatamente o que está no banco (é o caminho do cliente antigo, que não conhecia tipo nem lista).
     * SIM_NAO grava sempre ["Sim","Não"]; NOTA e TEXTO não têm lista; ESCOLHA exige de 2 a 10, cada uma de 1 a 80 caracteres.
     */
    private static String alternativasDe(PerguntaRequisicao pergunta, PerguntaTravada atual, String tipo, boolean tipoExplicito,
                                         int indice, Map<String, String> campos) {
        List<String> informadas = pergunta.alternativas();
        if (!tipoExplicito && atual != null && informadas == null) {
            return atual.alternativas();
        }
        return switch (tipo) {
            case TIPO_SIM_NAO -> paraJson(ALTERNATIVAS_SIM_NAO);
            case TIPO_NOTA, TIPO_TEXTO -> {
                if (informadas != null && !informadas.isEmpty()) {
                    campos.put(chave(indice, "alternativas"), "Só a pergunta de escolha usa alternativas.");
                }
                yield null;
            }
            default -> alternativasDaEscolha(informadas, tipoExplicito, indice, campos);
        };
    }

    private static String alternativasDaEscolha(List<String> informadas, boolean tipoExplicito, int indice,
                                                Map<String, String> campos) {
        if (informadas == null) {
            if (tipoExplicito) {
                campos.put(chave(indice, "alternativas"), "A pergunta de escolha precisa de 2 a 10 alternativas.");
            }
            return null;
        }
        List<String> limpas = new ArrayList<>();
        boolean tamanhoInvalido = false;
        for (String alternativa : informadas) {
            String limpa = alternativa == null ? "" : alternativa.trim();
            if (limpa.isEmpty() || limpa.length() > TAMANHO_MAXIMO_DA_ALTERNATIVA) {
                tamanhoInvalido = true;
            }
            limpas.add(limpa);
        }
        if (limpas.size() < MINIMO_DE_ALTERNATIVAS || limpas.size() > MAXIMO_DE_ALTERNATIVAS) {
            campos.put(chave(indice, "alternativas"), "A pergunta de escolha precisa de 2 a 10 alternativas.");
            return null;
        }
        if (tamanhoInvalido) {
            campos.put(chave(indice, "alternativas"), "Cada alternativa precisa ter de 1 a 80 caracteres.");
            return null;
        }
        return paraJson(limpas);
    }

    /** O JSONB como o banco o guarda. Nulo quando a pergunta não tem lista. */
    private static String paraJson(List<String> alternativas) {
        if (alternativas == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(alternativas);
        } catch (Exception erro) {
            throw new IllegalStateException("Não foi possível gravar as alternativas da pergunta.", erro);
        }
    }

    /** A lista de alternativas de um JSONB (o do banco ou o que acabamos de montar); nulo quando não há lista. */
    private static List<String> lerAlternativas(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, LISTA_DE_STRINGS);
        } catch (Exception erro) {
            return null;
        }
    }

    /**
     * Compara a lista nova com a que está no banco, pela ordem. [VALIDACAO] Aprovada alterada ou retirada é conflito,
     * com a frase do contrato; o conflito sai antes de qualquer gravação. Texto, tipo e alternativas contam como mudança.
     */
    private static List<Passo> montarPlano(List<PerguntaNormalizada> novas, Map<Integer, PerguntaTravada> atuais) {
        List<Passo> plano = new ArrayList<>();
        Set<Integer> ordensDaLista = new HashSet<>();
        novas.stream().sorted(Comparator.comparingInt(PerguntaNormalizada::ordem)).forEach(nova -> {
            ordensDaLista.add(nova.ordem());
            PerguntaTravada atual = atuais.get(nova.ordem());
            if (atual == null) {
                plano.add(new Passo(Acao.INSERIR, null, nova.ordem(), nova.texto(), nova.tipoResposta(),
                        nova.alternativasJson()));
            } else if ("APROVADA".equals(atual.status())) {
                if (!mesmaPergunta(atual, nova)) {
                    throw jaAprovada(atual.ordem());
                }
            } else if ("RASCUNHO".equals(atual.status()) || !mesmaPergunta(atual, nova)) {
                plano.add(new Passo(Acao.REENVIAR, atual, nova.ordem(), nova.texto(), nova.tipoResposta(),
                        nova.alternativasJson()));
            }
            // ENVIADA com o mesmo texto, tipo e alternativas: nada a fazer, a pergunta continua esperando a RBS.
        });
        atuais.values().stream().sorted(Comparator.comparing(PerguntaTravada::ordem)).forEach(atual -> {
            if (!ordensDaLista.contains(atual.ordem())) {
                if ("APROVADA".equals(atual.status())) {
                    throw jaAprovada(atual.ordem());
                }
                if (atual.temResultados()) {
                    // Pergunta reaberta pela RBS que já teve resultados: apagá-la apagaria os resultados junto (ON DELETE CASCADE).
                    throw new ConflitoException("A pergunta " + atual.ordem() + " já tem resultados carregados e não pode ser "
                            + "retirada da lista. Altere o texto dela ou fale com a RBS.");
                }
                plano.add(new Passo(Acao.APAGAR, atual, atual.ordem(), null, null, null));
            }
        });
        return plano;
    }

    private static boolean mesmaPergunta(PerguntaTravada atual, PerguntaNormalizada nova) {
        return atual.texto().equals(nova.texto())
                && atual.tipoResposta().equals(nova.tipoResposta())
                && Objects.equals(lerAlternativas(atual.alternativas()), lerAlternativas(nova.alternativasJson()));
    }

    private static ConflitoException jaAprovada(int ordem) {
        return new ConflitoException(
                "A pergunta " + ordem + " já foi aprovada pela RBS e não pode mais ser alterada.");
    }

    private void executar(Passo passo, UsuarioAutenticado usuario, long parceriaId, Long pesquisaId,
                          HttpServletRequest requisicao) {
        switch (passo.acao()) {
            case INSERIR -> {
                long id = repositorio.inserir(parceriaId, pesquisaId, passo.ordem(), passo.texto(), passo.tipoResposta(),
                        passo.alternativasJson(), usuario.id());
                historico.registrar(AcaoHistorico.EDICAO, usuario, parceriaId, ENTIDADE, id, null, passo.texto(),
                        "pergunta " + passo.ordem() + " enviada para a aprovação da RBS", requisicao);
            }
            case REENVIAR -> {
                PerguntaTravada atual = passo.atual();
                repositorio.reenviar(atual.id(), pesquisaId, passo.texto(), passo.tipoResposta(),
                        passo.alternativasJson(), usuario.id());
                boolean soTexto = atual.texto().equals(passo.texto());
                String oQue = soTexto ? "devolvida e enviada de novo" : "alterada e enviada de novo";
                historico.registrar(AcaoHistorico.EDICAO, usuario, parceriaId, ENTIDADE, atual.id(), atual.texto(),
                        passo.texto(), "pergunta " + passo.ordem() + " " + oQue + " para a aprovação da RBS", requisicao);
            }
            case APAGAR -> {
                PerguntaTravada atual = passo.atual();
                repositorio.apagar(atual.id());
                historico.registrar(AcaoHistorico.EDICAO, usuario, parceriaId, ENTIDADE, atual.id(), atual.texto(), null,
                        "pergunta " + passo.ordem() + " retirada da lista", requisicao);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Rota 25: a RBS decide
    // ---------------------------------------------------------------------

    /**
     * Rota 25. [PERMISSAO] Só a RBS (o patrocinador recebe 403, antes de qualquer consulta). [VALIDACAO] Aprovar vale só para
     * pergunta ENVIADA. Pedir ajuste vale para a ENVIADA (devolve à marca) e para a APROVADA (reabre: a marca volta a poder
     * editar; é o único caminho para mudar uma pergunta aprovada, já que a marca não altera nem retira a aprovada). Nos dois
     * casos a observação é obrigatória e a marca a lê em observacaoRbs. Em outro status, 409.
     * [HISTORICO] ALTERACAO_STATUS (de APROVADA para RASCUNHO, no caso da reabertura) com a observação em "detalhe", na mesma
     * transação. Os resultados já carregados da pergunta reaberta ficam guardados, mas só aparecem de novo quando ela voltar
     * a ser aprovada (a rota 23 mostra resultado só de pergunta APROVADA).
     * [PENDENTE] Hoje qualquer Admin RBS decide; a RBS ainda vai dizer se é só o time de pesquisa.
     */
    @Transactional
    public PerguntaDto decidir(UsuarioAutenticado usuario, long perguntaId, DecisaoRequisicao corpo,
                               HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        boolean aprovar = "APROVAR".equals(corpo.decisao());
        if (!aprovar && corpo.observacao() == null) {
            throw new DadosInvalidosException(Map.of("observacao", "Diga à marca o que precisa ser ajustado na pergunta."));
        }
        PerguntaTravada atual = repositorio.travarPergunta(perguntaId)
                .orElseThrow(() -> new NaoEncontradoException("Não encontramos esta pergunta."));
        verificador.exigirParceria(usuario, atual.parceriaId());
        boolean reabertura = !aprovar && "APROVADA".equals(atual.status());
        if (!"ENVIADA".equals(atual.status()) && !reabertura) {
            throw new ConflitoException(aprovar
                    ? "A RBS só decide sobre pergunta enviada, e a pergunta " + atual.ordem() + " está "
                            + situacaoPorExtenso(atual.status()) + "."
                    : "A RBS só pede ajuste de pergunta enviada ou aprovada, e a pergunta " + atual.ordem() + " está "
                            + situacaoPorExtenso(atual.status()) + ".");
        }

        String novoStatus = aprovar ? "APROVADA" : "RASCUNHO";
        repositorio.mudarStatus(perguntaId, novoStatus);
        historico.registrar(AcaoHistorico.ALTERACAO_STATUS, usuario, atual.parceriaId(), ENTIDADE, perguntaId,
                atual.status(), novoStatus, corpo.observacao(), requisicao);
        return paraPergunta(repositorio.pergunta(perguntaId).orElseThrow());
    }

    private static String situacaoPorExtenso(String status) {
        return switch (status) {
            case "APROVADA" -> "aprovada";
            case "RASCUNHO" -> "em rascunho (devolvida para ajuste ou ainda não enviada)";
            default -> status.toLowerCase();
        };
    }

    // ---------------------------------------------------------------------
    // Montagem do objeto da rota 23
    // ---------------------------------------------------------------------

    private PesquisaDaParceriaDto montar(long parceriaId, boolean abaLiberada) {
        LinhaDePesquisa linha = repositorio.pesquisaDaAtivacao(parceriaId).orElse(null);
        PesquisaDto pesquisa = linha == null ? null
                : new PesquisaDto(linha.id(), linha.nome(), linha.escopo(), linha.metodologia(), linha.baseRespondentes(),
                        linha.nivelConfianca());
        int limite = linha == null ? LIMITE_PADRAO_DE_PERGUNTAS : linha.limitePerguntas();
        List<PerguntaDto> perguntas = repositorio.perguntas(parceriaId).stream().map(PesquisaService::paraPergunta).toList();
        List<ResultadoDto> resultados = repositorio.resultados(parceriaId).stream()
                .map(r -> new ResultadoDto(r.perguntaId(), r.ordem(), r.alternativa(), r.valor(), r.unidade(),
                        r.baseRespondentes()))
                .toList();
        return new PesquisaDaParceriaDto(parceriaId, abaLiberada, limite, pesquisa, perguntas, resultados);
    }

    private static PerguntaDto paraPergunta(LinhaDePergunta l) {
        UsuarioResumoDto enviadaPor = l.enviadaPorId() == null ? null
                : new UsuarioResumoDto(l.enviadaPorId(), l.enviadaPorNome(), l.enviadaPorPerfil());
        return new PerguntaDto(l.id(), l.ordem(), l.texto(), l.status(), l.observacaoRbs(), l.tipoResposta(),
                lerAlternativas(l.alternativas()), enviadaPor, Datas.emBrasilia(l.enviadaEm()));
    }
}
