package br.com.rbs.portal.atlas.debug;

import br.com.rbs.portal.atlas.debug.DebugDoLlmDtos.ChaveRequisicao;
import br.com.rbs.portal.atlas.debug.DebugDoLlmDtos.EstadoDto;
import br.com.rbs.portal.atlas.debug.DebugDoLlmDtos.TesteDto;
import br.com.rbs.portal.atlas.debug.DebugDoLlmDtos.UsuarioResumoDto;
import br.com.rbs.portal.atlas.diagnostico.DiagnosticoDtos.TesteDeProvedorDto;
import br.com.rbs.portal.atlas.diagnostico.ProvedoresService;
import br.com.rbs.portal.atlas.provedor.FabricaDeProvedor;
import br.com.rbs.portal.atlas.provedor.ProvedorAtual;
import br.com.rbs.portal.atlas.provedor.ProvedorAtual.ChaveDoDebug;
import br.com.rbs.portal.atlas.provedor.ProvedorAtual.Escolha;
import br.com.rbs.portal.atlas.provedor.ProvedorAtual.Origem;
import br.com.rbs.portal.atlas.provedor.ProvedorSpringAi;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
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
 * a origem, a base URL, o modelo e o final da chave. Só a RBS pode usar (o controller e o PermissaoAntesDoCorpo conferem o perfil): é
 * ferramenta da equipe em desenvolvimento.
 */
@Service
@ConditionalOnProperty(name = "atlas.debug.chaves", havingValue = "true")
public class DebugDoLlmService {

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
    private final ProvedoresService provedores;

    public DebugDoLlmService(ProvedorAtual provedor, FabricaDeProvedor fabrica, ProvedoresService provedores) {
        this.provedor = provedor;
        this.fabrica = fabrica;
        this.provedores = provedores;
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
                modeloUsado, raciocinio, ProvedoresService.finalDa(chave), usuario, Datas.emBrasilia(OffsetDateTime.now())));
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
     * passa de novo pela limpeza. É o mesmo teste da rota 53 para o provedor {@code llm} ({@link ProvedoresService#testarLlm}).
     */
    public TesteDto testar() {
        TesteDeProvedorDto teste = provedores.testarLlm();
        return new TesteDto(teste.ok(), teste.milissegundos(), teste.modelo(), teste.resposta(), teste.erro());
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
                    ProvedoresService.finalDa(fabrica.chaveDoAmbiente()), null, null, disponivel, raciocinio);
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

    private static String vazioViraNulo(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }
}
