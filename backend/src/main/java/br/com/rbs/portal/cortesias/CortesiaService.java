package br.com.rbs.portal.cortesias;

import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.cortesias.CortesiaDtos.CotaDto;
import br.com.rbs.portal.cortesias.CortesiaDtos.CotaEntrada;
import br.com.rbs.portal.cortesias.CortesiaDtos.CotasRequisicao;
import br.com.rbs.portal.cortesias.CortesiaDtos.CortesiasDto;
import br.com.rbs.portal.cortesias.CortesiaDtos.DestinacaoDto;
import br.com.rbs.portal.cortesias.CortesiaDtos.DestinacaoEdicao;
import br.com.rbs.portal.cortesias.CortesiaDtos.DestinacaoRequisicao;
import br.com.rbs.portal.cortesias.CortesiaDtos.PrazoDto;
import br.com.rbs.portal.cortesias.CortesiaDtos.TotaisDto;
import br.com.rbs.portal.cortesias.CortesiaRepositorio.CotaTravada;
import br.com.rbs.portal.cortesias.CortesiaRepositorio.DestinacaoDaParceria;
import br.com.rbs.portal.cortesias.CortesiaRepositorio.EventoBase;
import br.com.rbs.portal.cortesias.CortesiaRepositorio.LinhaDaCota;
import br.com.rbs.portal.cortesias.CortesiaRepositorio.LinhaDaDestinacao;
import br.com.rbs.portal.cortesias.CortesiaRepositorio.ParceriaBase;
import br.com.rbs.portal.materiais.MateriaisDtos.UsuarioResumoDto;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A aba Cortesias (R5.4, rotas 80 a 85). [ISOLAMENTO] Parceria de outra marca dá 404, e a destinação de outra parceria também (a
 * consulta junta a cota e filtra pela parceria da URL: nunca 403, para não confirmar que o id existe). [PERMISSAO] A marca só entra
 * com a aba CORTESIAS aberta (403 com as duas frases de aba); a cota e o prazo são só da RBS. A RBS destina, edita e retira no lugar
 * da marca. [REGRA] Parceria ENCERRADA recusa toda escrita (409). Com {@code evento.cortesias_prazo} definido, a marca recebe 422 com a
 * data depois dela; a RBS ainda ajusta. [VALIDACAO] Quantidade negativa, acima de 32.767 ou abaixo do já destinado, e destinação acima
 * do disponível, dão 422 com o campo: o corpo é conferido aqui, e nunca chega ao banco sem a conferência. [CORRIDA] A cota é travada
 * com FOR UPDATE antes de somar o destinado (CortesiaRepositorio): dois envios juntos não passam do disponível.
 * <p>
 * [DADO PESSOAL] O destinatário é só um nome (D8). Ele é gravado em cortesia_destinacao e devolvido pela rota 80; não entra no
 * histórico (que guarda só a quantidade e "Arena · sexta-feira"), nem em mensagem de erro, nem em log. A destinação é apagada de
 * verdade (rota 85).
 */
@Service
public class CortesiaService {

    static final String ABA = "CORTESIAS";
    static final int MAXIMO_DA_COLUNA = Short.MAX_VALUE;   // 32.767: as quantidades são SMALLINT
    static final int LIMITE_DO_NOME = 120;
    static final int LIMITE_DO_ACESSO = 80;
    static final int LIMITE_DE_CELULAS = 24;               // 4 setores x 3 dias = 12; folga para o par repetido ser reconhecido
    static final String ENCERRADA = "Esta parceria está encerrada e não aceita alterações.";
    static final String CONTROLE = "O texto tem caracteres que o portal não aceita.";

    private static final List<String> SETORES = List.of("ARENA", "CAMAROTE", "PREMIUM", "LOUNGE");
    private static final List<String> DIAS = List.of("PASSAPORTE", "SEXTA", "SABADO");
    private static final Map<String, String> SETOR_NA_FRASE = Map.of("ARENA", "Arena", "CAMAROTE", "Camarote", "PREMIUM", "Premium",
            "LOUNGE", "Lounge");
    private static final Map<String, String> DIA_NA_FRASE = Map.of("PASSAPORTE", "passaporte", "SEXTA", "sexta-feira",
            "SABADO", "sábado");
    private static final DateTimeFormatter DATA_BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final CortesiaRepositorio repositorio;
    private final VerificadorDeAcesso verificador;
    private final HistoricoService historico;

    public CortesiaService(CortesiaRepositorio repositorio, VerificadorDeAcesso verificador, HistoricoService historico) {
        this.repositorio = repositorio;
        this.verificador = verificador;
        this.historico = historico;
    }

    // ---------------------------------------------------------------------
    // Rota 80
    // ---------------------------------------------------------------------

    /**
     * Rota 80. Ordem das respostas: 404 (parceria que não existe ou é de outra), 403 (aba CORTESIAS fechada ou oculta para a marca; a
     * RBS passa). Uma só leitura (REPEATABLE READ): a tabela, os totais e as destinações são do mesmo instante.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CortesiasDto listar(UsuarioAutenticado usuario, long parceriaId) {
        verificador.exigirParceria(usuario, parceriaId);
        verificador.exigirAbaLiberada(usuario, parceriaId, ABA);
        return montar(parceriaId);
    }

    // ---------------------------------------------------------------------
    // Rota 81: a cota por setor e dia (só a RBS)
    // ---------------------------------------------------------------------

    /**
     * Rota 81. Cada célula do corpo é a cota daquele setor e dia: cria, muda ou, com quantidade 0 e nada destinado, retira. Tudo ou
     * nada: qualquer célula recusada desfaz as outras. Reduzir abaixo do que já foi destinado dá 422 no campo da célula.
     */
    @Transactional
    public CortesiasDto gravarCotas(UsuarioAutenticado usuario, long parceriaId, CotasRequisicao corpo, HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        verificador.exigirParceria(usuario, parceriaId);
        abrirParaEscrever(usuario, parceriaId);

        Map<String, String> campos = new LinkedHashMap<>();
        List<CotaEntrada> entradas = corpo == null ? null : corpo.cotas();
        if (entradas == null || entradas.isEmpty() || entradas.size() > LIMITE_DE_CELULAS) {
            campos.put("cotas", "Envie de 1 a " + LIMITE_DE_CELULAS + " células de cota.");
            throw new DadosInvalidosException(campos);
        }
        List<Celula> celulas = new ArrayList<>();
        Set<String> vistas = new java.util.HashSet<>();
        for (int i = 0; i < entradas.size(); i++) {
            CotaEntrada e = entradas.get(i);
            String prefixo = "cotas[" + i + "].";
            if (e == null) {
                campos.put("cotas[" + i + "]", "Célula vazia.");
                continue;
            }
            String setor = setor(e.setor(), prefixo + "setor", campos);
            String dia = dia(e.dia(), prefixo + "dia", campos);
            Long quantidade = quantidade(e.quantidade(), prefixo + "quantidade", 0, campos);
            String acesso = texto(e.acesso(), prefixo + "acesso", LIMITE_DO_ACESSO, false, "O acesso", campos);
            if (setor != null && dia != null && !vistas.add(setor + "/" + dia)) {
                campos.put(prefixo + "dia", "Este setor e este tipo/dia aparecem mais de uma vez.");
            }
            if (setor != null && dia != null && quantidade != null) {
                celulas.add(new Celula(i, setor, dia, quantidade.intValue(), Boolean.TRUE.equals(e.comShuttle()), acesso));
            }
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }

        // Sempre na mesma ordem (setor e dia): duas gravações da grade ao mesmo tempo travam as cotas na mesma sequência.
        // Primeiro se trava e se confere tudo, sem gravar nada; só sem erro nenhum a grade é gravada (tudo ou nada, mesmo que a
        // transação seja de quem chamou).
        celulas.sort(Comparator.comparing(Celula::setor).thenComparing(Celula::dia));
        Map<Celula, CotaTravada> existentes = new java.util.IdentityHashMap<>();
        for (Celula c : celulas) {
            var cota = repositorio.travarCota(parceriaId, c.setor(), c.dia());
            if (cota.isPresent()) {
                existentes.put(c, cota.get());
                long destinadas = repositorio.destinadas(cota.get().id());
                if (c.quantidade() < destinadas) {
                    campos.put("cotas[" + c.indice() + "].quantidade", "A cota de " + frase(c.setor(), c.dia())
                            + " não pode ficar abaixo das " + destinadas + " cortesias já destinadas.");
                }
            }
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }
        for (Celula c : celulas) {
            aplicar(usuario, parceriaId, c, existentes.get(c), requisicao);
        }
        return montar(parceriaId);
    }

    /** Grava uma célula já conferida: cria, muda, retira ou (sem mudança) não faz nada. {@code cota} é a cota travada, se existia. */
    private void aplicar(UsuarioAutenticado usuario, long parceriaId, Celula c, CotaTravada cota, HttpServletRequest requisicao) {
        String onde = frase(c.setor(), c.dia());
        if (cota == null) {
            if (c.quantidade() > 0) {
                long id = repositorio.inserirCota(parceriaId, c.setor(), c.dia(), c.quantidade(), c.comShuttle(), c.acesso());
                historico.registrar(AcaoHistorico.CRIACAO, usuario, parceriaId, "cortesia_cota", id, null,
                        String.valueOf(c.quantidade()), onde, requisicao);
            }
            return;
        }
        if (c.quantidade() == 0) {   // já se sabe que não há nada destinado
            repositorio.apagarCota(cota.id());
            historico.registrar(AcaoHistorico.EXCLUSAO, usuario, parceriaId, "cortesia_cota", cota.id(),
                    String.valueOf(cota.quantidade()), null, onde, requisicao);
            return;
        }
        boolean mudouQuantidade = c.quantidade() != cota.quantidade();
        boolean mudouResto = c.comShuttle() != cota.comShuttle() || !Objects.equals(c.acesso(), cota.acesso());
        if (!mudouQuantidade && !mudouResto) {
            return;   // o PATCH sem mudança não grava nada
        }
        repositorio.atualizarCota(cota.id(), c.quantidade(), c.comShuttle(), c.acesso());
        historico.registrar(AcaoHistorico.EDICAO, usuario, parceriaId, "cortesia_cota", cota.id(), String.valueOf(cota.quantidade()),
                String.valueOf(c.quantidade()), mudouResto ? onde + " (acesso ou shuttle)" : onde, requisicao);
    }

    // ---------------------------------------------------------------------
    // Rota 82: o prazo da edição (só a RBS)
    // ---------------------------------------------------------------------

    /**
     * Rota 82. O corpo é {@code {"cortesiasPrazo": "AAAA-MM-DD"}}, ou {@code null} para tirar o prazo (sem data, não há trava). O campo
     * tem de vir no corpo: corpo sem ele dá 422, para um corpo vazio não apagar o prazo sem querer.
     */
    @Transactional
    public PrazoDto gravarPrazo(UsuarioAutenticado usuario, long eventoId, Map<String, Object> corpo, HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        EventoBase evento = repositorio.eventoTravado(eventoId).orElseThrow(() -> new NaoEncontradoException("Não encontramos esta edição."));
        Map<String, String> campos = new LinkedHashMap<>();
        LocalDate prazo = null;
        if (corpo == null || !corpo.containsKey("cortesiasPrazo")) {
            campos.put("cortesiasPrazo", "Informe a data (AAAA-MM-DD) ou null para tirar o prazo.");
        } else if (corpo.get("cortesiasPrazo") != null) {
            if (corpo.get("cortesiasPrazo") instanceof String texto && !texto.isBlank()) {
                prazo = Datas.diaDoFiltro("cortesiasPrazo", texto, campos);
            } else {
                campos.put("cortesiasPrazo", Datas.DATA_INVALIDA);
            }
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }
        if (!Objects.equals(prazo, evento.cortesiasPrazo())) {
            repositorio.gravarPrazo(eventoId, prazo);
            historico.registrar(AcaoHistorico.EDICAO, usuario, null, "evento", eventoId,
                    evento.cortesiasPrazo() == null ? null : evento.cortesiasPrazo().toString(), prazo == null ? null : prazo.toString(),
                    "prazo para destinar cortesias", requisicao);
        }
        return new PrazoDto(eventoId, evento.edicao(), prazo);
    }

    // ---------------------------------------------------------------------
    // Rota 83: destinar
    // ---------------------------------------------------------------------

    /**
     * Rota 83. Ordem das respostas: 404 (parceria), 403 (aba), 409 (parceria encerrada), 422 (prazo da edição vencido, só para a
     * marca; ou o corpo; ou a cota que não existe; ou mais do que o disponível). O disponível é conferido com a cota travada.
     */
    @Transactional
    public CortesiasDto destinar(UsuarioAutenticado usuario, long parceriaId, DestinacaoRequisicao corpo, HttpServletRequest requisicao) {
        verificador.exigirParceria(usuario, parceriaId);
        verificador.exigirAbaLiberada(usuario, parceriaId, ABA);
        abrirParaEscrever(usuario, parceriaId);

        Map<String, String> campos = new LinkedHashMap<>();
        DestinacaoRequisicao entrada = corpo == null ? new DestinacaoRequisicao(null, null, null, null) : corpo;
        String setor = setor(entrada.setor(), "setor", campos);
        String dia = dia(entrada.dia(), "dia", campos);
        Long quantidade = quantidade(entrada.quantidade(), "quantidade", 1, campos);
        String destinatario = texto(entrada.destinatario(), "destinatario", LIMITE_DO_NOME, true, "O destinatário", campos);
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }

        String onde = frase(setor, dia);
        CotaTravada cota = repositorio.travarCota(parceriaId, setor, dia).orElseThrow(() -> new DadosInvalidosException(
                Map.of("setor", "Esta parceria não tem cota de cortesia de " + onde + ".")));
        long disponivel = cota.quantidade() - repositorio.destinadas(cota.id());
        if (quantidade > disponivel) {
            throw acimaDoDisponivel(onde, disponivel, "quantidade");
        }
        long id = repositorio.inserirDestinacao(cota.id(), destinatario, quantidade.intValue(), usuario.id());
        historico.registrar(AcaoHistorico.CRIACAO, usuario, parceriaId, "cortesia_destinacao", id, null, cortesias(quantidade), onde,
                requisicao);
        return montar(parceriaId);
    }

    // ---------------------------------------------------------------------
    // Rota 84: editar uma destinação
    // ---------------------------------------------------------------------

    /**
     * Rota 84. Muda o destinatário e/ou a quantidade de qualquer destinação da parceria (a marca e a RBS). Destinação de outra
     * parceria é 404. Aumentar a quantidade confere o disponível com a cota travada, descontando a própria destinação.
     */
    @Transactional
    public CortesiasDto editar(UsuarioAutenticado usuario, long parceriaId, long destinacaoId, DestinacaoEdicao corpo,
                               HttpServletRequest requisicao) {
        verificador.exigirParceria(usuario, parceriaId);
        verificador.exigirAbaLiberada(usuario, parceriaId, ABA);
        abrirParaEscrever(usuario, parceriaId);
        DestinacaoDaParceria destinacao = repositorio.destinacaoDaParceria(parceriaId, destinacaoId)
                .orElseThrow(() -> new NaoEncontradoException("Não encontramos esta destinação."));

        Map<String, String> campos = new LinkedHashMap<>();
        DestinacaoEdicao entrada = corpo == null ? new DestinacaoEdicao(null, null) : corpo;
        String destinatario = entrada.destinatario() == null ? null
                : texto(entrada.destinatario(), "destinatario", LIMITE_DO_NOME, true, "O destinatário", campos);
        Long quantidade = entrada.quantidade() == null ? null : quantidade(entrada.quantidade(), "quantidade", 1, campos);
        if (campos.isEmpty() && entrada.destinatario() == null && entrada.quantidade() == null) {
            campos.put("destinatario", "Informe o destinatário ou a quantidade.");
        }
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }

        // A cota primeiro (a mesma ordem da rota 83), depois a destinação: se outra pessoa a retirou no meio, é 404.
        CotaTravada cota = repositorio.travarCotaPorId(destinacao.cotaId())
                .orElseThrow(() -> new NaoEncontradoException("Não encontramos esta destinação."));
        var atual = repositorio.travarDestinacao(destinacaoId)
                .orElseThrow(() -> new NaoEncontradoException("Não encontramos esta destinação."));
        long novaQuantidade = quantidade == null ? atual.quantidade() : quantidade;
        String onde = frase(destinacao.setor(), destinacao.dia());
        long disponivel = cota.quantidade() - repositorio.destinadasExceto(cota.id(), destinacaoId);
        if (novaQuantidade > disponivel) {
            throw acimaDoDisponivel(onde, disponivel, "quantidade");
        }
        String nome = destinatario != null ? destinatario : repositorio.nomeDaDestinacao(destinacaoId);
        boolean mudouNome = destinatario != null && !destinatario.equals(repositorio.nomeDaDestinacao(destinacaoId));
        boolean mudouQuantidade = novaQuantidade != atual.quantidade();
        if (mudouNome || mudouQuantidade) {
            repositorio.atualizarDestinacao(destinacaoId, nome, (int) novaQuantidade);
            // O histórico diz o que mudou, nunca o nome (D8).
            historico.registrar(AcaoHistorico.EDICAO, usuario, parceriaId, "cortesia_destinacao", destinacaoId,
                    cortesias(atual.quantidade()), cortesias(novaQuantidade),
                    onde + (mudouNome ? " (destinatário alterado)" : ""), requisicao);
        }
        return montar(parceriaId);
    }

    // ---------------------------------------------------------------------
    // Rota 85: retirar uma destinação
    // ---------------------------------------------------------------------

    /** Rota 85. Apaga de verdade (o nome some do banco); o histórico fica com a quantidade e o setor, sem o nome. */
    @Transactional
    public void retirar(UsuarioAutenticado usuario, long parceriaId, long destinacaoId, HttpServletRequest requisicao) {
        verificador.exigirParceria(usuario, parceriaId);
        verificador.exigirAbaLiberada(usuario, parceriaId, ABA);
        abrirParaEscrever(usuario, parceriaId);
        DestinacaoDaParceria destinacao = repositorio.destinacaoDaParceria(parceriaId, destinacaoId)
                .orElseThrow(() -> new NaoEncontradoException("Não encontramos esta destinação."));
        repositorio.travarCotaPorId(destinacao.cotaId()).orElseThrow(() -> new NaoEncontradoException("Não encontramos esta destinação."));
        var atual = repositorio.travarDestinacao(destinacaoId)
                .orElseThrow(() -> new NaoEncontradoException("Não encontramos esta destinação."));
        repositorio.apagarDestinacao(destinacaoId);
        historico.registrar(AcaoHistorico.EXCLUSAO, usuario, parceriaId, "cortesia_destinacao", destinacaoId, cortesias(atual.quantidade()),
                null, frase(destinacao.setor(), destinacao.dia()), requisicao);
    }

    // ---------------------------------------------------------------------
    // Ajudas
    // ---------------------------------------------------------------------

    /**
     * Antes de toda escrita: a parceria não pode estar encerrada (409) e, para a marca, o prazo da edição não pode ter passado (422
     * com a data). A RBS ajusta mesmo depois do prazo. Chamar depois de exigirParceria.
     */
    private ParceriaBase abrirParaEscrever(UsuarioAutenticado usuario, long parceriaId) {
        ParceriaBase parceria = repositorio.parceria(parceriaId).orElseThrow(() -> new NaoEncontradoException("Não encontramos esta parceria."));
        if ("ENCERRADA".equals(parceria.situacao())) {
            throw new ConflitoException(ENCERRADA);
        }
        if (!usuario.ehRbs()) {
            LocalDate prazo = repositorio.prazoDaEdicao(parceria.eventoId());
            if (prazo != null && Datas.hoje().isAfter(prazo)) {
                String frase = "O prazo para destinar cortesias terminou em " + DATA_BR.format(prazo) + ". Peça o ajuste à RBS.";
                throw new DadosInvalidosException(frase, Map.of("cortesiasPrazo", frase));
            }
        }
        return parceria;
    }

    private CortesiasDto montar(long parceriaId) {
        ParceriaBase parceria = repositorio.parceria(parceriaId).orElseThrow(() -> new NaoEncontradoException("Não encontramos esta parceria."));
        LocalDate prazo = repositorio.prazoDaEdicao(parceria.eventoId());
        Map<Long, List<DestinacaoDto>> porCota = new LinkedHashMap<>();
        for (LinhaDaDestinacao d : repositorio.destinacoes(parceriaId)) {
            porCota.computeIfAbsent(d.cotaId(), id -> new ArrayList<>()).add(new DestinacaoDto(d.id(), d.destinatario(), d.quantidade(),
                    new UsuarioResumoDto(d.usuarioId(), d.usuarioNome(), d.usuarioPerfil()), Datas.emBrasilia(d.registradoEm())));
        }
        List<CotaDto> cotas = repositorio.cotas(parceriaId).stream().map(c -> daLinha(c, porCota.getOrDefault(c.id(), List.of()))).toList();
        TotaisDto totais = repositorio.totais(parceriaId)
                .map(t -> new TotaisDto(t.cota(), t.destinadas(), t.cota() - t.destinadas(), t.percentual()))
                .orElse(new TotaisDto(null, null, null, null));
        return new CortesiasDto(parceriaId, prazo, prazo != null && Datas.hoje().isAfter(prazo), totais, cotas);
    }

    private static CotaDto daLinha(LinhaDaCota c, List<DestinacaoDto> destinacoes) {
        return new CotaDto(c.id(), c.setor(), c.dia(), c.quantidade(), c.destinadas(), c.quantidade() - c.destinadas(), c.acesso(),
                c.comShuttle(), destinacoes);
    }

    private static DadosInvalidosException acimaDoDisponivel(String onde, long disponivel, String campo) {
        String frase = disponivel <= 0 ? "Não restam cortesias de " + onde + " para destinar."
                : "Só restam " + disponivel + " " + (disponivel == 1 ? "cortesia" : "cortesias") + " de " + onde + " para destinar.";
        return new DadosInvalidosException(frase, Map.of(campo, frase));
    }

    private static String cortesias(long quantidade) {
        return quantidade + (quantidade == 1 ? " cortesia" : " cortesias");
    }

    private static String frase(String setor, String dia) {
        return SETOR_NA_FRASE.getOrDefault(setor, setor) + " · " + DIA_NA_FRASE.getOrDefault(dia, dia);
    }

    /** O setor em maiúsculas, ou nulo (com a frase em {@code campos}) se não for um dos quatro. */
    private static String setor(String valor, String campo, Map<String, String> campos) {
        if (valor == null || !SETORES.contains(valor)) {
            campos.put(campo, "Setor inválido. Use ARENA, CAMAROTE, PREMIUM ou LOUNGE.");
            return null;
        }
        return valor;
    }

    private static String dia(String valor, String campo, Map<String, String> campos) {
        if (valor == null || !DIAS.contains(valor)) {
            campos.put(campo, "Tipo ou dia inválido. Use PASSAPORTE, SEXTA ou SABADO.");
            return null;
        }
        return valor;
    }

    /** A quantidade de 'minimo' a 32.767, ou nula (com a frase em {@code campos}). */
    private static Long quantidade(Long valor, String campo, int minimo, Map<String, String> campos) {
        if (valor == null) {
            campos.put(campo, "Informe a quantidade.");
            return null;
        }
        if (valor < 0) {
            campos.put(campo, "A quantidade não pode ser negativa.");
            return null;
        }
        if (valor > MAXIMO_DA_COLUNA) {
            campos.put(campo, "A quantidade máxima é 32.767.");
            return null;
        }
        if (valor < minimo) {
            campos.put(campo, "Destine pelo menos " + minimo + " cortesia.");
            return null;
        }
        return valor;
    }

    /**
     * [VALIDACAO] Texto de uma linha: sem NUL, caractere de controle, inversor de direção (bidi) nem metade de par substituto (que o
     * banco recusaria com 500); espaços das pontas saem e os do meio viram um. Vazio dá nulo (ou o erro, se for obrigatório).
     * A frase nunca repete o texto recebido.
     */
    static String texto(String valor, String campo, int limite, boolean obrigatorio, String nome, Map<String, String> campos) {
        if (valor == null) {
            if (obrigatorio) {
                campos.put(campo, nome + " é obrigatório.");
            }
            return null;
        }
        if (valor.codePoints().anyMatch(CortesiaService::proibido)) {
            campos.put(campo, CONTROLE);
            return null;
        }
        String limpo = valor.replaceAll("[\\p{Zs}]+", " ").trim();
        if (limpo.isEmpty()) {
            if (obrigatorio) {
                campos.put(campo, nome + " é obrigatório.");
            }
            return null;
        }
        if (limpo.length() > limite) {
            campos.put(campo, nome + " tem no máximo " + limite + " caracteres.");
            return null;
        }
        return limpo;
    }

    private static boolean proibido(int ponto) {
        int tipo = Character.getType(ponto);
        return tipo == Character.CONTROL || tipo == Character.SURROGATE || ponto == 0x200E || ponto == 0x200F
                || (ponto >= 0x202A && ponto <= 0x202E) || (ponto >= 0x2066 && ponto <= 0x2069);
    }

    private record Celula(int indice, String setor, String dia, int quantidade, boolean comShuttle, String acesso) {
    }
}
