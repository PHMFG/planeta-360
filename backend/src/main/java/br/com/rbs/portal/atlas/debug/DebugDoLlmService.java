package br.com.rbs.portal.atlas.debug;

import br.com.rbs.portal.atlas.debug.DebugDoLlmDtos.ChaveRequisicao;
import br.com.rbs.portal.atlas.debug.DebugDoLlmDtos.EstadoDto;
import br.com.rbs.portal.atlas.debug.DebugDoLlmDtos.TesteDto;
import br.com.rbs.portal.atlas.debug.DebugDoLlmDtos.UsuarioResumoDto;
import br.com.rbs.portal.atlas.provedor.FabricaDeProvedor;
import br.com.rbs.portal.atlas.provedor.ProvedorAtual;
import br.com.rbs.portal.atlas.provedor.ProvedorAtual.ChaveDoDebug;
import br.com.rbs.portal.atlas.provedor.ProvedorAtual.Escolha;
import br.com.rbs.portal.atlas.provedor.ProvedorAtual.Origem;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.MensagemDeChat;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.PedidoAoLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.RespostaDoLlm;
import br.com.rbs.portal.atlas.provedor.ProvedorSpringAi;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * [ATLAS] Recurso TEMPORÁRIO de debug: pôr a chave do modelo de linguagem do Atlas pela tela, sem reiniciar a API e sem variável
 * de ambiente. Só existe com {@code atlas.debug.chaves=true} (ligado no perfil dev). A chave vai para o {@link ProvedorAtual} e
 * fica só na memória (some quando a API reinicia): nunca no banco, em arquivo ou em log. O log de cada troca leva só quem trocou,
 * a origem, a base URL, o modelo e o final da chave. Qualquer usuário logado pode usar: é ferramenta da equipe em desenvolvimento.
 */
@Service
@ConditionalOnProperty(name = "atlas.debug.chaves", havingValue = "true")
public class DebugDoLlmService {

    /** A mensagem do teste: mínima, sem ferramentas. */
    static final String MENSAGEM_DO_TESTE = "Responda só com a palavra ok.";
    static final String SEM_CHAVE = "Nenhuma chave configurada.";
    static final String SESSAO_DO_TESTE = "debug-teste";
    static final int LIMITE_DA_RESPOSTA = 200;

    static final String CHAVE_INVALIDA = "A chave precisa ter de 8 a 500 caracteres, sem espaços.";
    static final String BASE_URL_INVALIDA = "A base URL precisa começar com https:// (ou ser http://localhost ou http://127.0.0.1).";
    static final String MODELO_INVALIDO = "O modelo tem até 100 caracteres: letras, números e . _ : / -";

    private static final Pattern CHAVE = Pattern.compile("\\S{8,500}");
    private static final Pattern BASE_URL = Pattern.compile("https://\\S+|http://(localhost|127\\.0\\.0\\.1)(:\\d{1,5})?(/\\S*)?");
    private static final int LIMITE_DA_BASE_URL = 500;
    private static final Pattern MODELO = Pattern.compile("[A-Za-z0-9._:/-]{1,100}");

    private static final Logger log = LoggerFactory.getLogger(DebugDoLlmService.class);

    private final ProvedorAtual provedor;
    private final FabricaDeProvedor fabrica;

    public DebugDoLlmService(ProvedorAtual provedor, FabricaDeProvedor fabrica) {
        this.provedor = provedor;
        this.fabrica = fabrica;
    }

    /** GET: o que vale agora. */
    public EstadoDto estado() {
        return estado(provedor.escolha());
    }

    /** PUT: valida, monta o provedor com a chave (sem chamá-lo) e passa a usá-lo. */
    public EstadoDto configurar(UsuarioAutenticado usuario, ChaveRequisicao corpo) {
        String chave = corpo == null ? null : corpo.chave();
        String baseUrl = vazioViraNulo(corpo == null ? null : corpo.baseUrl());
        String modelo = vazioViraNulo(corpo == null ? null : corpo.modelo());
        Map<String, String> erros = new LinkedHashMap<>();
        if (chave == null || !CHAVE.matcher(chave).matches()) {
            erros.put("chave", CHAVE_INVALIDA);
        }
        if (baseUrl != null && (baseUrl.length() > LIMITE_DA_BASE_URL || !BASE_URL.matcher(baseUrl).matches())) {
            erros.put("baseUrl", BASE_URL_INVALIDA);
        }
        if (modelo != null && !MODELO.matcher(modelo).matches()) {
            erros.put("modelo", MODELO_INVALIDO);
        }
        if (!erros.isEmpty()) {
            throw new DadosInvalidosException(erros);
        }
        String baseUrlUsada = baseUrl != null ? baseUrl : fabrica.baseUrlPadrao();
        String modeloUsado = modelo != null ? modelo : fabrica.modeloPadrao();
        boolean raciocinio = corpo.raciocinio() != null ? corpo.raciocinio() : raciocinioAtual(provedor.escolha());
        provedor.usarChaveDoDebug(new ChaveDoDebug(fabrica.montar(chave, baseUrlUsada, modeloUsado, raciocinio), baseUrlUsada,
                modeloUsado, raciocinio, finalDa(chave), usuario, Datas.emBrasilia(OffsetDateTime.now())));
        return registrarTroca(usuario);
    }

    /** DELETE: esquece a chave do debug; volta ao provedor das variáveis de ambiente, ou a nenhum. */
    public void esquecer(UsuarioAutenticado usuario) {
        provedor.esquecerChaveDoDebug();
        registrarTroca(usuario);
    }

    /**
     * POST /teste: manda ao provedor atual uma mensagem mínima, sem ferramentas, e diz se respondeu. Não grava nada (não conta
     * para o teto diário do Atlas, que conta as mensagens gravadas). O erro é o do {@link ProvedorSpringAi}, já sem a chave, e
     * passa de novo pela limpeza.
     */
    public TesteDto testar() {
        Escolha escolha = provedor.escolha();
        String modelo = estado(escolha).modelo();
        if (escolha.provedor() == null) {
            return new TesteDto(false, 0, modelo, null, SEM_CHAVE);
        }
        long inicio = System.nanoTime();
        try {
            RespostaDoLlm resposta = escolha.provedor().responder(
                    new PedidoAoLlm(SESSAO_DO_TESTE, List.of(MensagemDeChat.usuario(MENSAGEM_DO_TESTE)), List.of()));
            String texto = resposta.texto() == null ? "" : resposta.texto();
            if (texto.length() > LIMITE_DA_RESPOSTA) {
                texto = texto.substring(0, LIMITE_DA_RESPOSTA);
            }
            return new TesteDto(true, desde(inicio), modelo, texto, null);
        } catch (RuntimeException erro) {
            return new TesteDto(false, desde(inicio), modelo, null, motivo(erro));
        }
    }

    // ---------------------------------------------------------------------
    // Apoio
    // ---------------------------------------------------------------------

    private EstadoDto estado(Escolha escolha) {
        boolean disponivel = escolha.provedor() != null;
        boolean raciocinio = raciocinioAtual(escolha);
        return switch (escolha.origem()) {
            case DEBUG -> {
                ChaveDoDebug chave = escolha.debug();
                UsuarioAutenticado quem = chave.configuradoPor();
                yield new EstadoDto(Origem.DEBUG.name(), chave.baseUrl(), chave.modelo(), chave.finalDaChave(),
                        new UsuarioResumoDto(quem.id(), quem.nome(), quem.perfil().name()), chave.configuradoEm(), disponivel,
                        raciocinio);
            }
            case AMBIENTE -> new EstadoDto(Origem.AMBIENTE.name(), fabrica.baseUrlPadrao(), fabrica.modeloPadrao(),
                    finalDa(fabrica.chaveDoAmbiente()), null, null, disponivel, raciocinio);
            case NENHUM -> new EstadoDto(Origem.NENHUM.name(), fabrica.baseUrlPadrao(), fabrica.modeloPadrao(), null, null, null,
                    disponivel, raciocinio);
        };
    }

    /** O raciocínio que vale agora: o da chave do debug, se houver; senão o {@code atlas.raciocinio}. */
    private boolean raciocinioAtual(Escolha escolha) {
        if (escolha.origem() == Origem.DEBUG) {
            return escolha.debug().raciocinio();
        }
        if (escolha.provedor() instanceof ProvedorSpringAi doAmbiente) {
            return doAmbiente.raciocinio();
        }
        return fabrica.raciocinioPadrao();
    }

    /** Uma linha em INFO por troca: quem trocou e o que vale agora (origem, base URL, modelo e o final da chave, nunca ela). */
    private EstadoDto registrarTroca(UsuarioAutenticado usuario) {
        EstadoDto agora = estado();
        log.info("Atlas (debug): {} (id {}) trocou a chave do modelo de linguagem. Agora: origem {}, base URL {}, modelo {}, "
                        + "final da chave {}, raciocínio {}.", usuario.nome(), usuario.id(), agora.origem(), agora.baseUrl(),
                agora.modelo(), agora.finalDaChave() == null ? "nenhum" : agora.finalDaChave(),
                agora.raciocinio() ? "ligado" : "desligado");
        return agora;
    }

    /**
     * O motivo da falha, sem a chave: a mensagem do {@link ProvedorSpringAi} (que já risca a chave) e, quando a causa mais funda é
     * de rede ({@link IOException}: servidor fora do ar, nome que não resolve, tempo esgotado), o nome e a frase dela, que nunca
     * levam a chave. As outras causas (o erro HTTP do provedor, cujo corpo pode repetir a chave) não entram.
     */
    private static String motivo(RuntimeException erro) {
        String motivo = erro.getMessage() == null || erro.getMessage().isBlank() ? erro.getClass().getSimpleName() : erro.getMessage();
        Throwable raiz = erro;
        while (raiz.getCause() != null && raiz.getCause() != raiz) {
            raiz = raiz.getCause();
        }
        if (raiz instanceof IOException rede) {
            motivo = motivo + " Causa: " + rede.getClass().getSimpleName() + (rede.getMessage() == null ? "" : ": " + rede.getMessage());
        }
        return ProvedorSpringAi.limpar(motivo, null);
    }

    /** Os 4 últimos caracteres da chave; nulo sem chave ou com chave curta demais (menos de 8), para não mostrá-la inteira. */
    static String finalDa(String chave) {
        if (chave == null || chave.length() < 8) {
            return null;
        }
        return chave.substring(chave.length() - 4);
    }

    private static String vazioViraNulo(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }

    private static long desde(long inicio) {
        return (System.nanoTime() - inicio) / 1_000_000;
    }
}
