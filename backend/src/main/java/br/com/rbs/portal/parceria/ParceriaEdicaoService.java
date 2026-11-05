package br.com.rbs.portal.parceria;

import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.parceria.ParceriaDtos.AbaEdicaoRequisicao;
import br.com.rbs.portal.parceria.ParceriaDtos.EditarParceriaRequisicao;
import br.com.rbs.portal.parceria.ParceriaDtos.EmailResumoDto;
import br.com.rbs.portal.parceria.ParceriaDtos.ParceriaDetalheDto;
import br.com.rbs.portal.parceria.ParceriaRepositorio.AbaDaParceria;
import br.com.rbs.portal.parceria.ParceriaRepositorio.ParceriaBase;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PATCH /api/parcerias/{id} (rota 10): a RBS ajusta o Perfil da parceria e libera ou fecha abas só para ela.
 * Atende os itens 3 e 5 da Entrega 2. [PERMISSAO] Só a RBS. A cota e o evento não mudam (mudar a cota refaria as abas).
 * [HISTORICO] Uma linha EDICAO por campo alterado (com o valor anterior e o novo) e uma LIBERACAO_ABA por aba alterada.
 */
@Service
public class ParceriaEdicaoService {

    private final ParceriaService parcerias;
    private final ParceriaRepositorio repositorio;
    private final EmailAutorizadoRepositorio emails;
    private final VerificadorDeAcesso verificador;
    private final HistoricoService historico;

    public ParceriaEdicaoService(ParceriaService parcerias, ParceriaRepositorio repositorio, EmailAutorizadoRepositorio emails,
                                 VerificadorDeAcesso verificador, HistoricoService historico) {
        this.parcerias = parcerias;
        this.repositorio = repositorio;
        this.emails = emails;
        this.verificador = verificador;
        this.historico = historico;
    }

    @Transactional
    public ParceriaDetalheDto<EmailResumoDto> editar(UsuarioAutenticado usuario, long parceriaId,
                                                     EditarParceriaRequisicao corpo, HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        verificador.exigirParceria(usuario, parceriaId);
        ParceriaBase antes = parcerias.buscar(parceriaId, true);   // trava a linha: dois PATCH ao mesmo tempo esperam um ao outro
        List<AbaDaParceria> abasAntes = repositorio.abasDaParceria(parceriaId);

        // O que fica depois do PATCH: campo nulo no corpo = "não muda"; texto em branco = "limpar".
        var pat = corpo.patrocinador();
        String nomeDepois = antes.patrocinadorNome();
        String segmentoDepois = antes.segmento();
        Map<String, String> campos = new LinkedHashMap<>();
        if (pat != null && pat.nome() != null) {
            String nome = ParceriaService.nomeNormalizado(pat.nome());
            if (nome == null) {
                campos.put("patrocinador.nome", "Informe o nome do patrocinador.");
            } else {
                nomeDepois = nome;
            }
        }
        if (pat != null && pat.segmento() != null) {
            segmentoDepois = ParceriaService.textoOuNulo(pat.segmento());
        }
        String produtoDepois = corpo.nomeProduto() != null ? ParceriaService.textoOuNulo(corpo.nomeProduto()) : antes.nomeProduto();
        BigDecimal valorDepois = corpo.valorInvestimento() != null ? corpo.valorInvestimento() : antes.valorInvestimento();
        String baseDepois = corpo.baseValor() != null ? corpo.baseValor() : antes.baseValor();
        String situacaoDepois = corpo.situacao() != null ? corpo.situacao() : antes.situacao();

        // [VALIDACAO] O valor e a base vão juntos (o banco tem o CHECK ck_parceria_valor, mas a frase clara é desta camada).
        if (valorDepois != null && baseDepois == null) {
            campos.put("baseValor", "Informe a base do valor (BRUTO_TABELA ou LIQUIDO) junto com o valor.");
        } else if (valorDepois == null && baseDepois != null) {
            campos.put("valorInvestimento", "Informe o valor junto com a base do valor.");
        }
        List<AbaEdicaoRequisicao> pedidosDeAba = corpo.abas() == null ? List.of() : corpo.abas();
        Map<String, String> foraDaCota = new LinkedHashMap<>();
        for (int i = 0; i < pedidosDeAba.size(); i++) {
            String codigo = pedidosDeAba.get(i).codigo().trim();
            AbaDaParceria aba = abasAntes.stream().filter(a -> a.codigo().equals(codigo)).findFirst().orElse(null);
            if (aba == null) {
                campos.put("abas[" + i + "].codigo", "Aba não encontrada. Use PERFIL, APLICACOES, MIDIA, CORTESIAS, "
                        + "CREDENCIAMENTO, PESQUISA ou CONTRATO.");
            } else if (Boolean.TRUE.equals(pedidosDeAba.get(i).liberada()) && !aba.liberada() && !aba.incluidaNaCota()) {
                // [PERMISSAO] Só se libera aba que a cota inclui (cota_tipo_aba: por exemplo, Pesquisa só para a Master). Fechar
                // sempre pode; e repetir "liberada" numa aba que já estava aberta é um PATCH sem mudança, não uma liberação.
                foraDaCota.put("abas[" + i + "].liberada",
                        "Esta cota não inclui a aba " + aba.nome() + ". Para dar acesso, troque a cota da parceria.");
            }
        }
        if (!campos.isEmpty() || !foraDaCota.isEmpty()) {
            // Só o erro de cota: o front mostra o "detail" (o botão de liberar a aba não tem campo ao lado), então ele traz a frase.
            if (campos.isEmpty()) {
                throw new DadosInvalidosException(foraDaCota.values().iterator().next(), foraDaCota);
            }
            campos.putAll(foraDaCota);
            throw new DadosInvalidosException(campos);
        }
        if (!nomeDepois.equals(antes.patrocinadorNome())
                && repositorio.nomeDePatrocinadorEmUso(nomeDepois, antes.patrocinadorId())) {
            throw new ConflitoException("Já existe um patrocinador com este nome.");
        }

        // Patrocinador (o nome e o segmento valem para a marca, em todas as edições dela).
        if (!nomeDepois.equals(antes.patrocinadorNome()) || !Objects.equals(segmentoDepois, antes.segmento())) {
            repositorio.atualizarPatrocinador(antes.patrocinadorId(), nomeDepois, segmentoDepois);
            registrarEdicao(usuario, parceriaId, "patrocinador", antes.patrocinadorId(), "nome", antes.patrocinadorNome(), nomeDepois, requisicao);
            registrarEdicao(usuario, parceriaId, "patrocinador", antes.patrocinadorId(), "segmento", antes.segmento(), segmentoDepois, requisicao);
        }

        // Parceria.
        if (ParceriaService.mudou(antes.valorInvestimento(), valorDepois) || !Objects.equals(baseDepois, antes.baseValor())
                || !Objects.equals(produtoDepois, antes.nomeProduto()) || !situacaoDepois.equals(antes.situacao())) {
            repositorio.atualizarParceria(parceriaId, produtoDepois, valorDepois, baseDepois, situacaoDepois);
            registrarEdicao(usuario, parceriaId, "parceria", parceriaId, "nomeProduto", antes.nomeProduto(), produtoDepois, requisicao);
            registrarEdicao(usuario, parceriaId, "parceria", parceriaId, "valorInvestimento", texto(antes.valorInvestimento()),
                    texto(valorDepois), requisicao);
            registrarEdicao(usuario, parceriaId, "parceria", parceriaId, "baseValor", antes.baseValor(), baseDepois, requisicao);
            registrarEdicao(usuario, parceriaId, "parceria", parceriaId, "situacao", antes.situacao(), situacaoDepois, requisicao);
        }

        // Abas desta parceria. O estado vai sendo atualizado, para o mesmo código repetido na lista valer em ordem.
        Map<String, AbaDaParceria> estado = new LinkedHashMap<>();
        abasAntes.forEach(a -> estado.put(a.codigo(), a));
        for (AbaEdicaoRequisicao pedido : pedidosDeAba) {
            AbaDaParceria atual = estado.get(pedido.codigo().trim());
            boolean liberada = pedido.liberada() != null ? pedido.liberada() : atual.liberada();
            boolean visivel = pedido.visivel() != null ? pedido.visivel() : atual.visivel();
            if (liberada == atual.liberada() && visivel == atual.visivel()) {
                continue;   // nada mudou: não grava nem polui o histórico
            }
            repositorio.atualizarAba(parceriaId, atual.abaId(), liberada, visivel);
            historico.registrar(AcaoHistorico.LIBERACAO_ABA, usuario, parceriaId, "aba", atual.abaId().longValue(),
                    situacaoDaAba(atual.liberada(), atual.visivel()), situacaoDaAba(liberada, visivel), atual.nome(), requisicao);
            estado.put(atual.codigo(), new AbaDaParceria(atual.abaId(), atual.codigo(), atual.nome(), atual.ordem(), visivel, liberada,
                    atual.incluidaNaCota()));
        }

        return parcerias.montar(parcerias.buscar(parceriaId, false), emails.vigentes(parceriaId));
    }

    /** Só grava a linha EDICAO se o campo mudou de verdade. */
    private void registrarEdicao(UsuarioAutenticado usuario, long parceriaId, String entidade, long entidadeId, String campo,
                                 String antes, String depois, HttpServletRequest requisicao) {
        if (!Objects.equals(antes, depois) && !(antes != null && depois != null && igualEmNumero(antes, depois, campo))) {
            historico.registrar(AcaoHistorico.EDICAO, usuario, parceriaId, entidade, entidadeId, antes, depois, campo, requisicao);
        }
    }

    // "1000000.00" e "1000000" são o mesmo valor; só o campo de dinheiro compara como número.
    private static boolean igualEmNumero(String antes, String depois, String campo) {
        return "valorInvestimento".equals(campo) && new BigDecimal(antes).compareTo(new BigDecimal(depois)) == 0;
    }

    private static String texto(BigDecimal valor) {
        return valor == null ? null : valor.toPlainString();
    }

    // Como a aba aparece no histórico: aberta, fechada ou escondida do menu.
    private static String situacaoDaAba(boolean liberada, boolean visivel) {
        return !visivel ? "oculta" : liberada ? "liberada" : "fechada";
    }
}
