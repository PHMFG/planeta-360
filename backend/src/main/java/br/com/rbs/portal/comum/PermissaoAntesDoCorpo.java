package br.com.rbs.portal.comum;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * [PERMISSAO] Confere quem pode chamar a rota ANTES de o Spring ler o corpo, as partes do formulário e os parâmetros.
 * Atende o item 3 da Entrega 2. O contrato diz que a permissão vem antes da validação: o patrocinador que chama uma rota da
 * RBS recebe 403 qualquer que seja o corpo (vazio, JSON quebrado ou com campo errado), e não 400 ou 422, que ainda
 * contariam a ele como é o formato da rota. Sem este interceptador, o @Valid do controller rodaria primeiro.
 * Três regras, cada uma com as mesmas frases dos Services (que continuam conferindo: aqui é só a primeira linha de defesa):
 * <ul>
 *   <li>{@link #ROTAS_SO_DA_RBS}: perfil que não é Admin RBS recebe 403 ("Esta operação é restrita à equipe da RBS.").</li>
 *   <li>{@link #ROTAS_SO_DO_PATROCINADOR}: a conversa com o Atlas (rotas 42 a 46) e o diagnóstico dela (rota 49); a RBS recebe
 *       403 ("Só o Admin Patrocinador da parceria conversa com o Atlas.") antes de o corpo ser lido. Parceria, dono da conversa e
 *       aba o service confere depois.</li>
 *   <li>O envio das perguntas da pesquisa (PUT): a RBS recebe 403; o patrocinador precisa ser da parceria (404 se não for)
 *       e ter a aba PESQUISA liberada (403), e só depois o corpo é lido e validado.</li>
 * </ul>
 * A lista usa o método e o padrão exatamente como estão nos controllers; o HEAD vale como GET (senão ele contornaria a conferência
 * e a validação dos parâmetros viria antes do 403). O PermissaoAntesDoCorpoTest confere que toda rota
 * da API está em uma das listas (estas duas ou a de rotas de ambos os perfis, no teste), então rota nova não passa sem decisão.
 */
@Component
public class PermissaoAntesDoCorpo implements HandlerInterceptor, WebMvcConfigurer {

    /** As rotas que só o Admin RBS usa (a coluna "Quem chama" = "Admin RBS" do docs/api.md). Rota nova da RBS: acrescente aqui. */
    public static final Set<String> ROTAS_SO_DA_RBS = Set.of(
            "POST /api/parcerias",
            "PATCH /api/parcerias/{id}",
            "GET /api/parcerias/{id}/emails",
            "POST /api/parcerias/{id}/emails",
            "DELETE /api/parcerias/{id}/emails/{emailId}",
            "POST /api/parcerias/{id}/entregas",
            "GET /api/entregas",
            "PATCH /api/entregas/{id}/status",
            "GET /api/historico",
            "GET /api/historico.csv",
            "PATCH /api/pesquisa/perguntas/{id}/status",
            "POST /api/cargas",
            "GET /api/cargas",
            "GET /api/cargas/{id}/rejeicoes",
            "GET /api/painel",
            "GET /api/relatorios",
            "GET /api/relatorios.csv",
            "POST /api/parcerias/{id}/relatorios",
            "PATCH /api/relatorios/{id}/status",
            "POST /api/parcerias/{id}/aplicacoes/{aproveitamentoId}/comprovacoes",
            // O diagnóstico do Atlas (rotas 50 a 53): os turnos de todas as conversas e os provedores. Sempre mapeadas; com o
            // diagnóstico desligado a RBS recebe 404 do service.
            "GET /api/atlas/diagnostico/turnos",
            "GET /api/atlas/diagnostico/turnos/{turno}",
            "GET /api/atlas/provedores",
            "POST /api/atlas/provedores/{nome}/teste",
            // Os documentos do Atlas (rotas 54, 55, 57 e 58): enviar, listar, aprovar/recusar/revogar/reindexar e ver os trechos. A 56
            // (ver um documento) é das duas pontas: a marca vê o que o escopo dela permite.
            "POST /api/documentos",
            "GET /api/documentos",
            "PATCH /api/documentos/{id}/status",
            "GET /api/documentos/{id}/trechos",
            // As rotas de debug da chave do modelo (temporárias, só no perfil dev): só a RBS as usa. Só existem com
            // atlas.debug.chaves=true; o teste de classificação liga a propriedade, porque no perfil test elas não existem.
            "GET /api/debug/llm",
            "PUT /api/debug/llm",
            "DELETE /api/debug/llm",
            "POST /api/debug/llm/teste");

    /**
     * As rotas que só o Admin Patrocinador usa e que a RBS não usa por ele: a conversa com o Atlas e o cartão de perguntas (rotas 42
     * a 46 do docs/api.md) e o diagnóstico da conversa, do dono dela (rota 49). A RBS só lê o relatório que o Atlas montou (rotas 47
     * e 48, que não estão aqui) e o diagnóstico de todas as conversas (rotas 50 a 53, na lista da RBS). Rota nova só da marca:
     * acrescente aqui.
     */
    public static final Set<String> ROTAS_SO_DO_PATROCINADOR = Set.of(
            "POST /api/parcerias/{id}/atlas/conversas",
            "GET /api/parcerias/{id}/atlas/conversas",
            "POST /api/atlas/conversas/{id}/mensagens",
            "GET /api/atlas/conversas/{id}",
            "POST /api/atlas/solicitacoes/{id}/respostas",
            "GET /api/atlas/conversas/{id}/diagnostico");

    /** PUT /api/parcerias/{id}/pesquisa/perguntas: do Admin Patrocinador da parceria, com a aba liberada. */
    static final String ENVIO_DAS_PERGUNTAS = "PUT /api/parcerias/{id}/pesquisa/perguntas";
    static final String ABA_DA_PESQUISA = "PESQUISA";
    static final String SO_PATROCINADOR_ENVIA_PERGUNTAS = "Só o Admin Patrocinador da parceria envia as perguntas da pesquisa.";

    private final VerificadorDeAcesso verificador;

    public PermissaoAntesDoCorpo(VerificadorDeAcesso verificador) {
        this.verificador = verificador;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registro) {
        registro.addInterceptor(this).addPathPatterns("/api/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest requisicao, HttpServletResponse resposta, Object tratador) {
        Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
        // Sem sessão não chega aqui (o filtro de segurança já respondeu 401); a rota pública também não tem perfil a conferir.
        if (autenticacao == null || !(autenticacao.getPrincipal() instanceof UsuarioAutenticado usuario)) {
            return true;
        }
        Object padrao = requisicao.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (padrao == null) {
            return true;
        }
        // HEAD é o GET sem corpo na resposta: o Spring o atende pelo mesmo método do controller, então a regra é a mesma.
        String metodo = "HEAD".equals(requisicao.getMethod()) ? "GET" : requisicao.getMethod();
        String rota = metodo + " " + padrao;

        if (ROTAS_SO_DA_RBS.contains(rota)) {
            verificador.exigirRbs(usuario);
        } else if (ROTAS_SO_DO_PATROCINADOR.contains(rota)) {
            verificador.exigirPatrocinador(usuario, VerificadorDeAcesso.SO_PATROCINADOR_USA_O_ATLAS);
        } else if (ENVIO_DAS_PERGUNTAS.equals(rota)) {
            verificador.exigirPatrocinador(usuario, SO_PATROCINADOR_ENVIA_PERGUNTAS);
            Long parceriaId = idDaParceria(requisicao);
            if (parceriaId != null) {   // id que não é número: quem responde é o tratador de erros (404), depois daqui
                verificador.exigirParceria(usuario, parceriaId);
                verificador.exigirAbaLiberada(usuario, parceriaId, ABA_DA_PESQUISA);
            }
        }
        return true;
    }

    /** O {id} da URL, ou nulo se não for um número. */
    @SuppressWarnings("unchecked")
    private static Long idDaParceria(HttpServletRequest requisicao) {
        Map<String, String> variaveis = (Map<String, String>) requisicao.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        try {
            return variaveis == null ? null : Long.valueOf(variaveis.get("id"));
        } catch (NumberFormatException naoEhNumero) {
            return null;
        }
    }
}
