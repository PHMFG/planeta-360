package br.com.rbs.portal.atlas.diagnostico;

import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.UsoDoLlm;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O traço de UM turno do Atlas, na memória (plano do Atlas completo, M2). [ATLAS] O loop, as ferramentas, a checagem do texto e
 * o resumo do raciocínio abrem etapas aqui à medida que o turno anda; nada vai ao banco no meio do turno. No fim
 * ({@link #encerrar}, também quando o turno dá erro) o {@link RegistroDoTraco} grava tudo numa transação curta. Por isso o traço
 * <b>nunca segura conexão durante a chamada a um provedor</b>.
 * <ul>
 *   <li><b>Etapa sequencial</b> ({@link #abrir}): entra na lista quando termina e nunca se sobrepõe a outra sequencial.</li>
 *   <li><b>Etapa em paralelo</b> ({@link #abrirEmParalelo}, o resumo do raciocínio): entra na lista ao abrir. O que ainda não
 *       terminou quando o turno encerra é gravado como pendente ({@code resultado} {@code PULADO} e {@code dados.pendente}); se
 *       terminar depois, a linha é atualizada numa transação curta.</li>
 *   <li><b>Nada sensível entra em {@code dados}</b>: o raciocínio cru, as chaves e o prompt de sistema não passam por aqui, e o
 *       {@link RegistroDoTraco} ainda limpa cada texto (corta em 20.000 caracteres e apaga as chaves configuradas) antes de gravar.</li>
 * </ul>
 * Com o diagnóstico desligado ({@code atlas.diagnostico.ligado=false}) o traço é o {@link #NENHUM}: toda chamada é um não faz nada.
 * Seguro para duas threads (a do turno e a do resumo).
 */
public final class Traco {

    /** O traço de quando o diagnóstico está desligado: não guarda nem grava nada. */
    public static final Traco NENHUM = new Traco();

    private static final Logger log = LoggerFactory.getLogger(Traco.class);

    private final RegistroDoTraco registro;
    private final long parceriaId;
    private final long conversaId;
    private final UUID turno;
    private final Object trava = new Object();
    private final List<Etapa> etapas = new ArrayList<>();
    private boolean gravado;
    private boolean temSaida;

    private Traco() {
        this.registro = null;
        this.parceriaId = 0;
        this.conversaId = 0;
        this.turno = null;
    }

    Traco(RegistroDoTraco registro, long parceriaId, long conversaId) {
        this.registro = registro;
        this.parceriaId = parceriaId;
        this.conversaId = conversaId;
        this.turno = UUID.randomUUID();
    }

    /** Este traço grava de verdade? Falso no {@link #NENHUM}. */
    public boolean ativo() {
        return registro != null;
    }

    /** O id deste turno (o mesmo em todas as etapas dele); nulo no {@link #NENHUM}. */
    public UUID turno() {
        return turno;
    }

    // ---------------------------------------------------------------------
    // Abrir etapas
    // ---------------------------------------------------------------------

    /** Começa uma etapa. As do caminho crítico entram na lista quando terminam; {@link EtapaDoTraco#RESUMO} entra já ao abrir. */
    public Etapa abrir(EtapaDoTraco tipo) {
        Etapa etapa = new Etapa(tipo);
        if (ativo() && tipo.emParalelo()) {
            synchronized (trava) {
                etapas.add(etapa);
            }
        }
        return etapa;
    }

    /** Uma falha inesperada do turno: a etapa {@code ERRO}, com o nome da exceção e uma frase curta e limpa (nunca a pilha). */
    public void erro(Throwable causa) {
        if (!ativo()) {
            return;
        }
        Etapa etapa = abrir(EtapaDoTraco.ERRO).dado("excecao", causa.getClass().getSimpleName());
        String mensagem = causa.getMessage();
        if (mensagem != null && !mensagem.isBlank()) {
            etapa.dado("mensagem", mensagem.length() > 300 ? mensagem.substring(0, 300) : mensagem);
        }
        etapa.fim(ResultadoDaEtapa.ERRO);
    }

    /** Os dados de uma ida ao modelo (ou de um resumo) a partir do que o provedor mediu; vazio com o diagnóstico desligado. */
    public Map<String, Object> dadosDoUso(UsoDoLlm uso, String modeloDoProvedor) {
        return ativo() ? registro.dadosDoUso(uso, modeloDoProvedor) : Map.of();
    }

    // ---------------------------------------------------------------------
    // Encerrar e gravar
    // ---------------------------------------------------------------------

    /**
     * Encerra o turno e grava o traço numa transação curta. Uma vez só (as chamadas seguintes não fazem nada). Se o loop não
     * deixou a etapa {@code SAIDA} (o turno caiu no meio), grava uma com resultado {@code ERRO}. Falha de gravação é só log: o
     * diagnóstico nunca derruba o turno.
     */
    public void encerrar() {
        encerrar(ResultadoDaEtapa.ERRO, "INTERROMPIDO", null);
    }

    /**
     * O mesmo, para o turno que nem chegou a começar (o teto diário, a conversa ocupada): sem {@code SAIDA}, grava uma com o
     * resultado, o tipo e o motivo dados.
     */
    public void encerrar(ResultadoDaEtapa resultadoDaSaida, String tipo, String motivo) {
        if (!ativo()) {
            return;
        }
        synchronized (trava) {
            if (gravado) {
                return;
            }
            if (!temSaida) {
                Etapa saida = new Etapa(EtapaDoTraco.SAIDA).dado("tipo", tipo).dado("motivo", motivo);
                saida.fim(resultadoDaSaida);
            }
            try {
                registro.gravar(this, ordenadas());
            } catch (RuntimeException falha) {
                log.warn("Atlas: o traço do turno não pôde ser gravado ({})", falha.getClass().getSimpleName());
            } finally {
                gravado = true;
            }
        }
    }

    /** As etapas na ordem em que começaram (a de mesmo instante fica na ordem de chegada), com {@code ordem} de 1 em diante. */
    private List<Etapa> ordenadas() {
        List<Etapa> ordenadas = new ArrayList<>(etapas);
        ordenadas.sort(Comparator.comparing(e -> e.inicio));
        return ordenadas;
    }

    // ---------------------------------------------------------------------
    // Uma etapa
    // ---------------------------------------------------------------------

    /** Uma etapa do turno, da abertura até o {@link #fim}. Quem a abre a fecha; só o resumo do raciocínio termina em outra thread. */
    public final class Etapa {

        private final EtapaDoTraco tipo;
        private final Instant inicio = Instant.now();
        private final long inicioEmNanos = System.nanoTime();
        private final Map<String, Object> dados = new LinkedHashMap<>();
        private Long mensagemId;
        private ResultadoDaEtapa resultado;
        private int duracaoMs;
        private Long id;

        private Etapa(EtapaDoTraco tipo) {
            this.tipo = tipo;
        }

        /** A mensagem do assistente que a etapa produziu ou pediu. Pode ser dita depois do {@link #fim}, até o traço ser gravado. */
        public Etapa mensagem(Long mensagemId) {
            if (ativo()) {
                synchronized (trava) {
                    if (!gravado) {
                        this.mensagemId = mensagemId;
                    }
                }
            }
            return this;
        }

        /** Um detalhe da etapa. Valor nulo não entra. */
        public Etapa dado(String chave, Object valor) {
            if (ativo() && valor != null) {
                synchronized (trava) {
                    if (resultado == null || !gravado) {
                        dados.put(chave, valor);
                    }
                }
            }
            return this;
        }

        /** Vários detalhes de uma vez. */
        public Etapa dados(Map<String, ?> detalhes) {
            detalhes.forEach(this::dado);
            return this;
        }

        /** Termina a etapa com este resultado e mede a duração. Só a primeira chamada vale. */
        public void fim(ResultadoDaEtapa resultadoFinal) {
            if (!ativo()) {
                return;
            }
            Long linhaParaAtualizar = null;
            synchronized (trava) {
                if (resultado != null) {
                    return;
                }
                resultado = resultadoFinal;
                duracaoMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - inicioEmNanos) / 1_000_000);
                if (tipo == EtapaDoTraco.SAIDA) {
                    temSaida = true;
                }
                if (!tipo.emParalelo() && !etapas.contains(this)) {
                    if (gravado) {
                        log.debug("Atlas: etapa {} terminou depois de o traço ser gravado; fica de fora.", tipo);
                        return;
                    }
                    etapas.add(this);
                }
                if (gravado && id != null) {
                    linhaParaAtualizar = id;   // o resumo que terminou depois do fim do turno
                }
            }
            if (linhaParaAtualizar != null) {
                registro.atualizar(linhaParaAtualizar, this);
            }
        }

        // Acesso do registro: na gravação (com a trava do traço na mão) e na atualização do resumo que terminou depois.

        EtapaDoTraco tipo() {
            return tipo;
        }

        Instant inicio() {
            return inicio;
        }

        Long mensagemId() {
            return mensagemId;
        }

        Long id() {
            return id;
        }

        void gravada(long idDaLinha) {
            this.id = idDaLinha;
        }

        /** O resultado a gravar: o que a etapa tem ou, se ainda não terminou (o resumo do raciocínio), pendente ({@code PULADO}). */
        ResultadoDaEtapa resultadoAGravar() {
            return resultado == null ? ResultadoDaEtapa.PULADO : resultado;
        }

        /** A duração a gravar: a medida no fim ou, se a etapa ainda não terminou, o tempo até agora. */
        int duracaoAGravar() {
            return resultado == null ? (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - inicioEmNanos) / 1_000_000) : duracaoMs;
        }

        /** Os detalhes a gravar; a etapa que ainda não terminou leva {@code pendente: true}. */
        Map<String, Object> dadosAGravar() {
            Map<String, Object> copia = new LinkedHashMap<>(dados);
            if (resultado == null) {
                copia.put("pendente", true);
            }
            return copia;
        }
    }

    long parceriaId() {
        return parceriaId;
    }

    long conversaId() {
        return conversaId;
    }

    @Override
    public String toString() {
        return ativo() ? "Traco[turno=" + turno + "]" : "Traco[NENHUM]";
    }
}
