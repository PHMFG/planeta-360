package br.com.rbs.portal.atlas.relatorio;

import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.relatorio.RelatorioAtlasService.DadoDisponivel;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Resolucao;
import br.com.rbs.portal.atlas.relatorio.ResolvedorDeReferencias.Valor;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A consulta dos números da parceria da conversa, sem relatório (a ferramenta {@code parceria_consultar}). [ATLAS] Serve para o
 * modelo de linguagem responder no chat uma pergunta como "quantas aplicações já foram comprovadas?": devolve as referências que
 * o modelo-base usa, com o valor já formatado, a unidade e a origem, e as que estão sem valor, com o motivo. O modelo cita o
 * valor com o marcador {@code {{ref}}}; o servidor o troca pelo número na hora de mostrar.
 * <p>
 * Só lê: não cria relatório, não grava nada (a trilha da chamada é do loop) e não recebe parceria. [ISOLAMENTO] [PERMISSAO] A
 * parceria e o perfil vêm do {@link AtlasContexto}; a resolução usa os mesmos services da rota 9, da 29 (com o filtro de aba do
 * perfil) e do DadoInformadoService. Com a aba do modelo fechada para quem pede, a resposta diz isso e não traz dado nenhum.
 */
@Service
public class ConsultaDaParceria {

    /** O aviso que volta ao modelo quando a aba do modelo está fechada. */
    public static final String ABA_FECHADA = "A aba Aplicações de marca no evento está fechada para esta parceria. Não há dados "
            + "para consultar; diga isso à pessoa sem citar número.";

    /** Uma referência sem valor agora, com o motivo (dado não informado, conta sem a entrada, parceria sem aplicação). */
    public record DadoIndisponivel(String ref, String rotulo, String motivo) {
    }

    /** O que a ferramenta devolve. Com {@code abaFechada}, as listas vêm vazias e {@code aviso} diz por quê. */
    public record Resultado(boolean abaFechada, String aviso, List<DadoDisponivel> dados, List<DadoIndisponivel> indisponiveis) {
    }

    private final ModeloBase modelos;
    private final ResolvedorDeReferencias resolvedor;
    private final VerificadorDeAcesso verificador;

    public ConsultaDaParceria(ModeloBase modelos, ResolvedorDeReferencias resolvedor, VerificadorDeAcesso verificador) {
        this.modelos = modelos;
        this.resolvedor = resolvedor;
        this.verificador = verificador;
    }

    /**
     * As referências de valor do modelo-base (as dos blocos, os dados de cada lacuna e as fórmulas fixas), resolvidas para a
     * parceria da conversa. 404 se quem pede perdeu o vínculo com a parceria; a aba fechada não é erro: volta como
     * {@code abaFechada}, sem dados (a conferência é feita agora, no banco, e não pela foto do contexto).
     */
    @Transactional(readOnly = true)
    public Resultado consultar(AtlasContexto contexto) {
        Especificacao base = modelos.obter(ModeloBase.ATIVACAO_EVENTO).orElseThrow();
        verificador.exigirParceria(contexto.usuario(), contexto.parceriaId());
        if (!verificador.podeVerAba(contexto.usuario(), contexto.parceriaId(), base.aba())) {
            return new Resultado(true, ABA_FECHADA, List.of(), List.of());
        }
        Resolucao resolucao = resolvedor.abrir(contexto.usuario(), contexto.parceriaId(), base);
        List<DadoDisponivel> dados = new ArrayList<>();
        List<DadoIndisponivel> indisponiveis = new ArrayList<>();
        for (String ref : refsDaConsulta(base)) {
            Valor valor = resolucao.valor(ref);
            if (valor.disponivel()) {
                dados.add(new DadoDisponivel(ref, valor.rotulo(), valor.texto(), valor.unidade(), valor.origem()));
            } else {
                indisponiveis.add(new DadoIndisponivel(ref, valor.rotulo(), valor.motivo()));
            }
        }
        return new Resultado(false, null, dados, indisponiveis);
    }

    /** As referências dos blocos, os {@code dado:} de cada lacuna e os {@code calc:} do modelo, sem repetir. */
    static Set<String> refsDaConsulta(Especificacao base) {
        Set<String> refs = new LinkedHashSet<>(RelatorioAtlasService.refsDeValor(base));
        base.chavesDeLacuna().forEach(chave -> refs.add("dado:" + chave));
        base.calculos().keySet().forEach(nome -> refs.add("calc:" + nome));
        return refs;
    }
}
