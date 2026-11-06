package br.com.rbs.portal.comum;

import br.com.rbs.portal.materiais.AcessoAEntregas;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
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
 *   <li>{@link #ROTAS_SO_DO_PATROCINADOR}: a conversa com o Atlas (rotas 42, 43 e 46); a RBS recebe 403 ("Só o Admin Patrocinador da
 *       parceria conversa com o Atlas.") antes de o corpo ser lido. Parceria, dono da conversa e aba o service confere depois.</li>
 *   <li>{@link #ROTAS_DA_CONVERSA_DO_ATLAS}: a mensagem, a leitura e o diagnóstico da conversa (rotas 44, 45 e 49). São do dono da
 *       conversa: o patrocinador, na conversa da parceria dele, e, com {@code atlas.rbs.ligado}, a RBS, na conversa dela (a do Atlas da
 *       RBS, sem parceria). A RBS recebe o mesmo 403 antes do corpo quando o Atlas da RBS está desligado ou quando a conversa é de uma
 *       parceria; conversa que não existe ou é de outra pessoa da RBS o service responde com 404.</li>
 *   <li>O envio das perguntas da pesquisa (PUT): o patrocinador precisa ser da parceria (404 se não for) e ter a aba PESQUISA
 *       liberada (403), e só depois o corpo é lido e validado. A RBS também passa (escreve as perguntas de qualquer Master);
 *       a cota Master é conferida no service.</li>
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
            // A troca de cota (rotas 102 e 104): a prévia do impacto e a troca, R6.d3.
            "GET /api/parcerias/{id}/cota/previa",
            "PUT /api/parcerias/{id}/cota",
            "GET /api/parcerias/{id}/emails",
            "POST /api/parcerias/{id}/emails",
            "DELETE /api/parcerias/{id}/emails/{emailId}",
            "POST /api/parcerias/{id}/entregas",
            // O plano comercial do Perfil e o formulário em branco da compra de ingressos (rotas 70 e 73): a RBS publica, a marca baixa.
            "POST /api/parcerias/{id}/plano-comercial",
            "POST /api/parcerias/{id}/formulario-ingressos",
            "GET /api/entregas",
            "PATCH /api/entregas/{id}/status",
            "GET /api/historico",
            "GET /api/historico.csv",
            "PATCH /api/pesquisa/perguntas/{id}/status",
            // A abertura da pesquisa da edição (rotas 92 a 95): listar, abrir, editar e a fila das perguntas a aprovar.
            "GET /api/eventos/{id}/pesquisas",
            "POST /api/eventos/{id}/pesquisas",
            "PATCH /api/pesquisas/{id}",
            "GET /api/pesquisa/perguntas",
            "POST /api/cargas",
            "GET /api/cargas",
            "GET /api/cargas/{id}/rejeicoes",
            "GET /api/painel",
            "GET /api/relatorios",
            "GET /api/relatorios.csv",
            "POST /api/parcerias/{id}/relatorios",
            "PATCH /api/relatorios/{id}/status",
            "POST /api/parcerias/{id}/aplicacoes/{aproveitamentoId}/comprovacoes",
            // As cortesias (rotas 81 e 82): a RBS grava a cota por setor e dia e o prazo de destinação da edição. As rotas 80, 83, 84 e 85
            // são das duas pontas.
            "PATCH /api/parcerias/{id}/cortesias/cotas",
            "PATCH /api/eventos/{id}/cortesias",
            // O lote da comprovação mensal (rota 91): uma aba por parceria Master da edição. As rotas 88 a 90 são da marca Master e da RBS
            // (a cota é conferida no service).
            "GET /api/eventos/{id}/comprovacao-mensal.xlsx",
            // A consulta da mídia da RBS (rotas 107 e 108). As rotas 105 e 106 são da marca da parceria e da RBS (aba MIDIA e
            // cota conferidas no service).
            "GET /api/midia",
            "GET /api/midia.xlsx",
            "GET /api/midia.csv",
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
            // As páginas fora do Atlas (rota 103, R2.1): a RBS marca as páginas que não entram no índice, enquanto o documento aguarda.
            "PATCH /api/documentos/{id}/paginas-fora-do-atlas",
            // A busca de teste do Atlas (rota 61): a RBS escolhe uma parceria e uma consulta e vê o que a busca faria por ela. As
            // rotas 59 e 60 (o visualizador: o arquivo e um trecho do documento) são das duas pontas, como a 56.
            "POST /api/atlas/busca-teste",
            // Os prazos da edição, da cota e da parceria (rotas 62 a 64): listar, cadastrar e desativar.
            "GET /api/eventos/{id}/prazos",
            "POST /api/eventos/{id}/prazos",
            "DELETE /api/prazos/{id}",
            // O Atlas da RBS (rotas 67 e 68): abrir e listar as conversas da RBS por edição. Sempre mapeadas; com atlas.rbs.ligado=false
            // a RBS recebe 404 do service.
            "POST /api/eventos/{eventoId}/atlas/conversas",
            "GET /api/eventos/{eventoId}/atlas/conversas",
            // O contrato e o credenciamento (rotas 76, 78 e 79): o PDF do contrato assinado, a grade de credenciais e o Open Staff da
            // edição. As rotas 74, 75 e 77 são das duas pontas.
            "POST /api/parcerias/{id}/contrato/arquivo",
            "PATCH /api/parcerias/{id}/credenciais",
            "PATCH /api/eventos/{id}/credenciamento",
            // O pós-venda e os números da edição (rotas 97 a 101): publicar o anexo de uma parceria, listar as versões da edição, retirar
            // (não apaga) e os números do "festival em números". A rota 96 (ver o anexo) é das duas pontas.
            "POST /api/parcerias/{id}/posvenda",
            "GET /api/eventos/{id}/posvenda",
            "DELETE /api/posvenda/{id}",
            "GET /api/eventos/{id}/resultados",
            "PUT /api/eventos/{id}/resultados",
            // A importação (rotas 109, 110 e 115): criar, listar e retirar. As rotas 111 a 114, 116 e a 112b são da RBS e da
            // marca nas importações dela, e o service confere a parceria.
            "POST /api/importacoes",
            "GET /api/importacoes",
            "POST /api/importacoes/{id}/retirar",
            // As rotas de debug da chave do modelo (temporárias, só no perfil dev): só a RBS as usa. Só existem com
            // atlas.debug.chaves=true; o teste de classificação liga a propriedade, porque no perfil test elas não existem.
            "GET /api/debug/llm",
            "PUT /api/debug/llm",
            "DELETE /api/debug/llm",
            "POST /api/debug/llm/teste");

    /**
     * As rotas que só o Admin Patrocinador usa e que a RBS não usa por ele: abrir e listar as conversas da parceria com o Atlas e o
     * cartão de perguntas (rotas 42, 43 e 46 do docs/api.md) e o descarte do relatório (rota 65). A RBS só lê o relatório que o Atlas
     * montou (rotas 47 e 48, que não estão aqui) e o diagnóstico de todas as conversas (rotas 50 a 53, na lista da RBS). A mensagem, a
     * leitura e o diagnóstico da conversa (rotas 44, 45 e 49) estão em {@link #ROTAS_DA_CONVERSA_DO_ATLAS}. Rota nova só da marca:
     * acrescente aqui.
     */
    public static final Set<String> ROTAS_SO_DO_PATROCINADOR = Set.of(
            "POST /api/parcerias/{id}/atlas/conversas",
            "GET /api/parcerias/{id}/atlas/conversas",
            "POST /api/atlas/solicitacoes/{id}/respostas",
            // Descartar o relatório pela tela (rota 65): existe só com atlas.relatorio.ligado=true, como as rotas 46 a 48.
            "POST /api/atlas/relatorios/{id}/descartar");

    /**
     * A mensagem, a leitura e o diagnóstico de uma conversa do Atlas (rotas 44, 45 e 49): do dono dela. O patrocinador passa; a RBS só
     * passa na conversa dela (Atlas da RBS, M8) e com {@code atlas.rbs.ligado}. Rota nova da conversa: acrescente aqui.
     */
    public static final Set<String> ROTAS_DA_CONVERSA_DO_ATLAS = Set.of(
            "POST /api/atlas/conversas/{id}/mensagens",
            "GET /api/atlas/conversas/{id}",
            "GET /api/atlas/conversas/{id}/diagnostico");

    /** PUT /api/parcerias/{id}/pesquisa/perguntas: do Admin Patrocinador da parceria, com a aba liberada. */
    static final String ENVIO_DAS_PERGUNTAS = "PUT /api/parcerias/{id}/pesquisa/perguntas";
    static final String ABA_DA_PESQUISA = "PESQUISA";
    /**
     * As rotas das duas pontas com corpo em que a parceria (404) e a aba (403) vêm antes de o corpo ser lido: a marca de outra
     * parceria ou com a aba fechada não aprende nada sobre o formato do corpo. Rota e aba.
     */
    static final Map<String, String> ROTAS_DA_ABA_ANTES_DO_CORPO = Map.of(
            "PUT /api/parcerias/{id}/contrato/signatario", "CONTRATO",
            // As cortesias (rotas 83 e 84): a marca de outra parceria ou com a aba fechada não aprende nada sobre o formato do corpo.
            "POST /api/parcerias/{id}/cortesias/destinacoes", "CORTESIAS",
            "PATCH /api/parcerias/{id}/cortesias/destinacoes/{destId}", "CORTESIAS");
    /** POST /api/entregas/{id}/conteudos (rota 86): o {id} é de entrega; a parceria e a aba saem dela. */
    static final String ENVIO_DE_CONTEUDO = "POST /api/entregas/{id}/conteudos";
    /** GET /api/parcerias/{id}/espacos (rota 71): o {@code ?aba=} inválido não vem antes do 404 e do 403. */
    static final String ESPACOS_DA_PARCERIA = "GET /api/parcerias/{id}/espacos";
    static final String SO_PATROCINADOR_ENVIA_PERGUNTAS ="Só o Admin Patrocinador da parceria envia as perguntas da pesquisa.";

    private final VerificadorDeAcesso verificador;
    private final AcessoAEntregas entregas;
    private final boolean rbsLigado;

    public PermissaoAntesDoCorpo(VerificadorDeAcesso verificador, AcessoAEntregas entregas,
                                 @Value("${atlas.rbs.ligado:false}") boolean rbsLigado) {
        this.verificador = verificador;
        this.entregas = entregas;
        this.rbsLigado = rbsLigado;
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
        } else if (ROTAS_DA_CONVERSA_DO_ATLAS.contains(rota)) {
            if (usuario.ehRbs() && !rbsPodeEntrarNaConversa(requisicao)) {
                verificador.exigirPatrocinador(usuario, VerificadorDeAcesso.SO_PATROCINADOR_USA_O_ATLAS);
            }
        } else if (ENVIO_DAS_PERGUNTAS.equals(rota)) {
            // A marca precisa ser Admin Patrocinador e ter a aba PESQUISA aberta; a RBS também escreve as perguntas de
            // qualquer Master, e a cota é conferida no service. A parceria (404) vem antes do corpo para as duas.
            if (!usuario.ehRbs()) {
                verificador.exigirPatrocinador(usuario, SO_PATROCINADOR_ENVIA_PERGUNTAS);
            }
            Long parceriaId = idDoCaminho(requisicao);
            if (parceriaId != null) {   // id que não é número: quem responde é o tratador de erros (404), depois daqui
                verificador.exigirParceria(usuario, parceriaId);
                if (!usuario.ehRbs()) {
                    verificador.exigirAbaLiberada(usuario, parceriaId, ABA_DA_PESQUISA);
                }
            }
        } else if (ENVIO_DE_CONTEUDO.equals(rota)) {
            Long entregaId = idDoCaminho(requisicao);
            if (entregaId != null) {
                entregas.exigirAntesDoCorpo(usuario, entregaId);
            }
        } else if (ESPACOS_DA_PARCERIA.equals(rota)) {
            Long parceriaId = idDoCaminho(requisicao);
            if (parceriaId != null) {
                verificador.exigirParceria(usuario, parceriaId);
                String aba = requisicao.getParameter("aba");
                // aba=APLICACOES só precisa de Aplicações, aba=PERFIL só do Perfil e sem aba vale a regra das duas. Valor inválido: só o
                // 404 vem antes; o 422 do filtro sai igual para quem tem acesso à parceria, qualquer que seja a aba aberta.
                boolean valida = aba == null || "PERFIL".equals(aba) || "APLICACOES".equals(aba);
                if (valida && !"APLICACOES".equals(aba)) {
                    verificador.exigirAbaLiberada(usuario, parceriaId, "PERFIL");
                }
                if (valida && !"PERFIL".equals(aba)) {
                    verificador.exigirAbaLiberada(usuario, parceriaId, "APLICACOES");
                }
            }
        } else if (ROTAS_DA_ABA_ANTES_DO_CORPO.containsKey(rota)) {
            Long parceriaId = idDoCaminho(requisicao);
            if (parceriaId != null) {
                verificador.exigirParceria(usuario, parceriaId);
                verificador.exigirAbaLiberada(usuario, parceriaId, ROTAS_DA_ABA_ANTES_DO_CORPO.get(rota));
            }
        }
        return true;
    }

    /**
     * A RBS entra na conversa do Atlas só com o Atlas da RBS ligado e se a conversa não é de uma parceria (conversa que não existe passa:
     * o service responde 404). Fora disso ela recebe o 403 de sempre, antes do corpo e sem dizer se a conversa existe.
     */
    private boolean rbsPodeEntrarNaConversa(HttpServletRequest requisicao) {
        if (!rbsLigado) {
            return false;
        }
        Long conversaId = idDoCaminho(requisicao);
        return conversaId == null || !verificador.conversaDoAtlasEDeParceria(conversaId);
    }

    /** O {id} da URL, ou nulo se não for um número. */
    @SuppressWarnings("unchecked")
    private static Long idDoCaminho(HttpServletRequest requisicao) {
        Map<String, String> variaveis = (Map<String, String>) requisicao.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        try {
            return variaveis == null ? null : Long.valueOf(variaveis.get("id"));
        } catch (NumberFormatException naoEhNumero) {
            return null;
        }
    }
}
