package br.com.rbs.portal.atlas.ferramentas;

import br.com.rbs.portal.atlas.AtlasRepositorio.LinhaDeRelatorio;
import br.com.rbs.portal.atlas.ModoDoAtlas;
import br.com.rbs.portal.atlas.checagem.FontesDoTurno;
import br.com.rbs.portal.atlas.contexto.AtlasContexto;
import br.com.rbs.portal.atlas.provedor.ProvedorDeLlm.DefinicaoDeFerramenta;
import br.com.rbs.portal.comum.DadosInvalidosException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * O registro das ferramentas do Atlas, por estado da conversa (plano do Atlas completo, seções 3.2 e M4). [ATLAS] Antes dele,
 * {@code RelatorioFerramentas} era um {@code switch} de cinco nomes e só oferecia {@code parceria_consultar} sem relatório. Agora
 * cada ferramenta se declara ({@link Ferramenta}) e o registro escolhe, a cada ida ao modelo, as do estado:
 * <ul>
 *   <li>sem relatório em montagem: {@code parceria_consultar}, {@code buscar_documentos} e, com o relatório ligado,
 *       {@code relatorio_iniciar};</li>
 *   <li>relatório {@code MONTANDO}: as duas primeiras mais {@code relatorio_solicitar_campos} (enquanto couber outro cartão),
 *       {@code relatorio_editar} e {@code relatorio_finalizar}: o relatório pode ficar montando por dias, e a pessoa continua podendo
 *       perguntar sobre os documentos;</li>
 *   <li>cartão aberto: nenhuma (o turno para).</li>
 * </ul>
 * O registro também roda a ferramenta em duas fases: {@link #preparar} (sem transação) e {@link #executar} (na transação do loop).
 * Nenhuma ferramenta recebe parceria, e argumento fora do esquema ou ferramenta que não existe volta ao modelo como erro.
 */
@Component
public class RegistroDeFerramentas {

    /** O que a fase 1 deixou pronto para a fase 2: a ferramenta, os argumentos já lidos e o que a ferramenta preparou. */
    public record Preparo(Ferramenta ferramenta, Map<String, Object> argumentos, Object preparado) {
    }

    private final Map<String, Ferramenta> porNome = new LinkedHashMap<>();
    private final ModoDoAtlas modo;

    public RegistroDeFerramentas(RelatorioFerramentas relatorio, List<Ferramenta> avulsas, ModoDoAtlas modo) {
        this.modo = modo;
        List<Ferramenta> todas = new ArrayList<>(relatorio.ferramentas());
        todas.addAll(avulsas);
        todas.sort(Comparator.comparingInt(Ferramenta::ordem));
        for (Ferramenta ferramenta : todas) {
            if (porNome.put(ferramenta.nome(), ferramenta) != null) {
                throw new IllegalStateException("Duas ferramentas do Atlas com o nome " + ferramenta.nome());
            }
        }
    }

    /** Os nomes de todas as ferramentas que existem, na ordem em que são oferecidas. */
    public List<String> nomes() {
        return List.copyOf(porNome.keySet());
    }

    /**
     * As ferramentas do estado atual. {@code relatorio} é o relatório em andamento da conversa, se houver; {@code podePedirCartao}
     * é falso quando ele já usou os três cartões que o modelo pode pedir. Com o cartão aberto, nenhuma.
     */
    public List<DefinicaoDeFerramenta> oferecidas(Optional<LinhaDeRelatorio> relatorio, boolean podePedirCartao) {
        EstadoDaConversa estado = new EstadoDaConversa(modo.relatorioLigado(), relatorio, podePedirCartao);
        if (estado.cartaoAberto()) {
            return List.of();
        }
        return porNome.values().stream().filter(ferramenta -> ferramenta.oferecida(estado)).map(Ferramenta::definicao).toList();
    }

    /**
     * Fase 1 de um pedido do modelo, <b>sem transação</b>: lê os argumentos, acha a ferramenta e deixa preparado o que ela chama de
     * fora (os embeddings da busca). Qualquer problema sobe como exceção, que o loop transforma em mensagem de ferramenta.
     */
    public Preparo preparar(AtlasContexto contexto, FontesDoTurno turno, String nome, String argumentosJson) {
        Map<String, Object> argumentos = Argumentos.ler(argumentosJson);
        Ferramenta ferramenta = porNome.get(nome);
        if (ferramenta == null) {
            throw new DadosInvalidosException(RelatorioFerramentas.ferramentaInexistente(nome), Map.of());
        }
        return new Preparo(ferramenta, argumentos, ferramenta.preparar(contexto, turno, argumentos));
    }

    /**
     * Fase 2, <b>na transação do loop</b>: grava o que a fase 1 preparou. Se falha, nada da ferramenta fica. {@code chamadaId} é o id
     * do pedido (liga o cartão à resposta que vai chegar depois).
     */
    public Execucao executar(AtlasContexto contexto, FontesDoTurno turno, Preparo preparo, String chamadaId) {
        return preparo.ferramenta().executar(contexto, turno, preparo.argumentos(), chamadaId, preparo.preparado());
    }
}
